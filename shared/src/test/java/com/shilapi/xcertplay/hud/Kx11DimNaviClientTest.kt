package com.shilapi.xcertplay.hud

import android.os.Binder
import android.os.Parcel
import com.shilapi.xcertplay.iap2.message.Iap2LaneGuidance
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class Kx11DimNaviClientTest {

    private data class QnxEmittedPacket(
        val status: Int?,
        val turnId: Int?,
        val road: String?,
        val distNext: Int?,
        val distDest: Int?,
        val lanes: List<String>?,
        val etaSeconds: Int?,
    )

    /**
     * High-fidelity simulation of KX11_A2's `DIMNaviInteractioncService`:
     * - Replicates `OnceExecute` wiping the server's `DimNaviInteraction.Builder` on every `updateETA`
     *   emission (moment=2).
     * - Replicates `mUpdateETA == data && mCheckDataTime <= 1` dropping duplicate-ETA emissions.
     * - Asserts no forbidden status (`2`) or `notifyTurnByTurnEvent` transactions are ever sent.
     */
    private class FakeDimNaviServiceBinder : Binder() {
        val txHistory = mutableListOf<Int>()
        val emittedToQnx = mutableListOf<QnxEmittedPacket>()
        val statusHistory = mutableListOf<Int>()

        private var curStatus: Int? = null
        private var curTurnId: Int? = null
        private var curRoad: String? = null
        private var curDistNext: Int? = null
        private var curDistDest: Int? = null
        private var curLanes: List<String>? = null
        private var lastEta: Int? = null

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(Kx11DimNaviBinder.DESCRIPTOR)
            txHistory += code
            when (code) {
                Kx11DimNaviBinder.TX_NOTIFY_NAVIGATION_STATUS -> {
                    val status = data.readInt()
                    statusHistory += status
                    curStatus = status
                }
                Kx11DimNaviBinder.TX_UPDATE_NAVIGATION_TURN_ID -> curTurnId = data.readInt()
                Kx11DimNaviBinder.TX_UPDATE_DISTANCE_TO_DESTINATION -> curDistDest = data.readInt()
                Kx11DimNaviBinder.TX_UPDATE_DISTANCE_TO_NEXT_GUIDANCE -> curDistNext = data.readInt()
                Kx11DimNaviBinder.TX_UPDATE_NEXT_GUIDANCE_POINT_NAME -> curRoad = data.readString()
                Kx11DimNaviBinder.TX_UPDATE_LANE_INFO -> {
                    curLanes = data.createStringArray()?.toList()
                    // consume typed Bitmap array (expected null)
                    data.createTypedArray(android.graphics.Bitmap.CREATOR)
                }
                Kx11DimNaviBinder.TX_UPDATE_ETA -> {
                    val eta = data.readInt()
                    // Replicate DIMNaviInteractioncService's duplicate-ETA swallow bug
                    if (lastEta != eta) {
                        lastEta = eta
                        emittedToQnx += QnxEmittedPacket(
                            status = curStatus,
                            turnId = curTurnId,
                            road = curRoad,
                            distNext = curDistNext,
                            distDest = curDistDest,
                            lanes = curLanes,
                            etaSeconds = eta,
                        )
                        // Replicate OnceExecute FINISH wiping Builder!
                        curStatus = null
                        curTurnId = null
                        curRoad = null
                        curDistNext = null
                        curDistDest = null
                        curLanes = null
                    }
                }
                else -> error("Unexpected transaction code: $code")
            }
            reply?.writeNoException()
            return true
        }
    }

    private class FakeScheduler {
        var nowMs = 1_000L
        private val scheduled = linkedMapOf<Runnable, Long>()

        fun schedule(delayMs: Long, action: Runnable) {
            scheduled[action] = nowMs + delayMs
        }

        fun cancel(action: Runnable) {
            scheduled.remove(action)
        }

        fun advanceBy(ms: Long) {
            nowMs += ms
            while (true) {
                val due = scheduled.entries.filter { it.value <= nowMs }.minByOrNull { it.value } ?: break
                scheduled.remove(due.key)
                due.key.run()
            }
        }
    }

    @Test
    fun coalescesBurstReplaysAllFieldsAndBypassesDuplicateEtaSwallow() {
        val fakeService = FakeDimNaviServiceBinder()
        val clock = FakeScheduler()
        val publisher = Kx11DimNaviPublisher(
            binderProvider = { fakeService },
            scheduleDelayed = clock::schedule,
            cancelScheduled = clock::cancel,
            elapsedRealtimeMs = { clock.nowMs },
            nanoTime = { clock.nowMs * 1_000_000L },
        )

        // 1. First burst: maneuver (left turn onto "Tianfu Rd") + route (300m, 4200m rem, 600s ETA) + 2 lanes
        publisher.onFrame(maneuverFrame(id = 1, type = 1, road = "Tianfu Rd"))
        publisher.onFrame(routeFrame(state = 1, distNext = 300, distDest = 4200, etaSec = 600, maneuverId = 1))
        publisher.onFrame(laneFrameSingleRightHighlight())
        assertEquals(0, fakeService.emittedToQnx.size) // held inside 200ms coalesce window

        clock.advanceBy(200L)
        assertEquals(1, fakeService.emittedToQnx.size)
        val first = fakeService.emittedToQnx[0]
        assertEquals(Kx11DimNaviBinder.STATUS_SUCCEED, first.status)
        assertEquals(Kx11DimNaviMapper.TURN_LEFT, first.turnId)
        assertEquals("Tianfu Rd", first.road)
        assertEquals(300, first.distNext)
        assertEquals(4200, first.distDest)
        assertEquals(listOf("0,33"), first.lanes)
        assertEquals(600, first.etaSeconds)

        // 2. Second update 1s later: distance drops to 240m while ETA stays 600s!
        // Because server wiped Builder on first emission and drops duplicate ETA=600, publisher must:
        // - replay ALL fields (turnId, road, distNext, distDest, lanes)
        // - perturb ETA (600 xor 1 = 601) so FakeDimNaviServiceBinder emits to QNX!
        clock.advanceBy(1_000L)
        publisher.onFrame(routeFrame(state = 1, distNext = 240, distDest = 4140, etaSec = 600, maneuverId = 1))
        clock.advanceBy(200L)

        assertEquals(2, fakeService.emittedToQnx.size)
        val second = fakeService.emittedToQnx[1]
        assertEquals(Kx11DimNaviMapper.TURN_LEFT, second.turnId)
        assertEquals("Tianfu Rd", second.road)
        assertEquals(240, second.distNext)
        assertEquals(4140, second.distDest)
        assertEquals(listOf("0,33"), second.lanes)
        assertNotEquals(first.etaSeconds, second.etaSeconds)
        assertEquals(601, second.etaSeconds)

        // 3. Re-assert status=1 after 5s periodic interval
        clock.advanceBy(5_000L)
        publisher.onFrame(routeFrame(state = 1, distNext = 120, distDest = 4020, etaSec = 600, maneuverId = 1))
        clock.advanceBy(200L)
        assertEquals(3, fakeService.emittedToQnx.size)
        assertEquals(Kx11DimNaviBinder.STATUS_SUCCEED, fakeService.emittedToQnx[2].status)
        assertEquals(600, fakeService.emittedToQnx[2].etaSeconds)

        // 4. Route arrival / stop: strict clear sequence ending with STATUS_UNNAVI (0)
        fakeService.txHistory.clear()
        publisher.onFrame(routeFrame(state = 2, distNext = 0, distDest = 0, etaSec = 0, maneuverId = null))
        assertEquals(
            listOf(
                Kx11DimNaviBinder.TX_UPDATE_LANE_INFO,
                Kx11DimNaviBinder.TX_UPDATE_NAVIGATION_TURN_ID,
                Kx11DimNaviBinder.TX_UPDATE_DISTANCE_TO_NEXT_GUIDANCE,
                Kx11DimNaviBinder.TX_UPDATE_DISTANCE_TO_DESTINATION,
                Kx11DimNaviBinder.TX_UPDATE_NEXT_GUIDANCE_POINT_NAME,
                Kx11DimNaviBinder.TX_NOTIFY_NAVIGATION_STATUS,
            ),
            fakeService.txHistory,
        )
        assertEquals(Kx11DimNaviBinder.STATUS_UNNAVI, fakeService.statusHistory.last())
        assertFalse(fakeService.statusHistory.contains(2))
        assertTrue(fakeService.statusHistory.all { it == 0 || it == 1 })
    }

    @Test
    fun disablingPublisherClearsActiveHudGuidanceImmediately() {
        val fakeService = FakeDimNaviServiceBinder()
        val clock = FakeScheduler()
        val publisher = Kx11DimNaviPublisher(
            binderProvider = { fakeService },
            scheduleDelayed = clock::schedule,
            cancelScheduled = clock::cancel,
            elapsedRealtimeMs = { clock.nowMs },
            nanoTime = { clock.nowMs * 1_000_000L },
        )

        publisher.onFrame(maneuverFrame(id = 2, type = 2, road = "East Ring"))
        publisher.onFrame(routeFrame(state = 1, distNext = 500, distDest = 1000, etaSec = 120, maneuverId = 2))
        clock.advanceBy(200L)
        assertEquals(1, fakeService.emittedToQnx.size)

        publisher.setEnabled(false)
        assertEquals(Kx11DimNaviBinder.STATUS_UNNAVI, fakeService.statusHistory.last())

        // Further frames are ignored while disabled
        publisher.onFrame(routeFrame(state = 1, distNext = 400, distDest = 900, etaSec = 110, maneuverId = 2))
        clock.advanceBy(500L)
        assertEquals(1, fakeService.emittedToQnx.size)
    }

    @Test
    fun tracksRouteActiveForClusterEvenWhenHudOutputDisabled() {
        val fakeService = FakeDimNaviServiceBinder()
        val clock = FakeScheduler()
        val activeEvents = mutableListOf<Boolean>()
        val publisher = Kx11DimNaviPublisher(
            binderProvider = { fakeService },
            scheduleDelayed = clock::schedule,
            cancelScheduled = clock::cancel,
            elapsedRealtimeMs = { clock.nowMs },
            nanoTime = { clock.nowMs * 1_000_000L },
            onNaviActiveChanged = { activeEvents += it },
        )

        publisher.setEnabled(false)
        publisher.onFrame(maneuverFrame(id = 3, type = 1, road = "Cluster Road"))
        publisher.onFrame(routeFrame(state = 1, distNext = 300, distDest = 1200, etaSec = 90, maneuverId = 3))
        clock.advanceBy(300L)

        // HUD output stays suppressed, but cluster route-active callback still fires.
        assertEquals(0, fakeService.emittedToQnx.size)
        assertEquals(listOf(true), activeEvents)

        // Ending guidance clears routeActive cleanly.
        publisher.onFrame(routeFrame(state = 2, distNext = 0, distDest = 0, etaSec = 0, maneuverId = null))
        assertEquals(listOf(true, false), activeEvents)
    }

    private fun maneuverFrame(id: Int, type: Int, road: String): Iap2Frame = Iap2Frame(
        messageId = BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
        payload = tlvs(
            tlv(0x01, (id ushr 8) and 0xff, id and 0xff),
            tlv(0x03, type),
            tlvBytes(0x04, (road + "\u0000").toByteArray(Charsets.UTF_8)),
            tlv(0x08, 0),
        ),
    )

    private fun routeFrame(state: Int, distNext: Int, distDest: Int, etaSec: Int, maneuverId: Int?): Iap2Frame {
        val parts = mutableListOf(
            tlv(0x01, state),
            tlvU64(0x06, etaSec.toLong()),
            tlvU32(0x07, distDest),
            tlvU32(0x0a, distNext),
        )
        if (maneuverId != null) {
            parts += tlv(0x0d, (maneuverId ushr 8) and 0xff, maneuverId and 0xff)
        }
        return Iap2Frame(BydHudRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(*parts.toTypedArray()))
    }

    private fun laneFrameSingleRightHighlight(): Iap2Frame {
        val laneInner = tlvs(
            tlv(0x00, 0, 0),
            tlv(0x01, 2),
            tlv(0x02, 0, 0),
            tlv(0x03, 0, 90),
        )
        return Iap2Frame(Iap2LaneGuidance.LANE_GUIDANCE_INFO_UPDATE, tlvBytes(0x02, laneInner))
    }

    private fun tlvs(vararg entries: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        entries.forEach(::write)
    }.toByteArray()

    private fun tlv(id: Int, vararg values: Int): ByteArray =
        tlvBytes(id, ByteArray(values.size) { values[it].toByte() })

    private fun tlvU32(id: Int, value: Int): ByteArray = tlv(
        id,
        (value ushr 24) and 0xff,
        (value ushr 16) and 0xff,
        (value ushr 8) and 0xff,
        value and 0xff,
    )

    private fun tlvU64(id: Int, value: Long): ByteArray = tlv(
        id,
        ((value ushr 56) and 0xff).toInt(),
        ((value ushr 48) and 0xff).toInt(),
        ((value ushr 40) and 0xff).toInt(),
        ((value ushr 32) and 0xff).toInt(),
        ((value ushr 24) and 0xff).toInt(),
        ((value ushr 16) and 0xff).toInt(),
        ((value ushr 8) and 0xff).toInt(),
        (value and 0xff).toInt(),
    )

    private fun tlvBytes(id: Int, payload: ByteArray): ByteArray {
        val length = 4 + payload.size
        return ByteArray(length).apply {
            this[0] = (length ushr 8).toByte()
            this[1] = length.toByte()
            this[2] = (id ushr 8).toByte()
            this[3] = id.toByte()
            payload.copyInto(this, destinationOffset = 4)
        }
    }
}
