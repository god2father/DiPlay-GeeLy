package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.message.Iap2LaneGuidance
import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Kx11DimNaviMapperTest {

    @Test
    fun mapsCoreTurnKindsIncludingSharpTurnsAndUturns() {
        assertEquals(Kx11DimNaviMapper.TURN_LEFT, Kx11DimNaviMapper.turnId(1, 0))
        assertEquals(Kx11DimNaviMapper.TURN_RIGHT, Kx11DimNaviMapper.turnId(2, 0))
        assertEquals(Kx11DimNaviMapper.TURN_STRAIGHT, Kx11DimNaviMapper.turnId(3, 0))
        assertEquals(Kx11DimNaviMapper.TURN_LEFT_FRONT, Kx11DimNaviMapper.turnId(13, 0))
        assertEquals(Kx11DimNaviMapper.TURN_RIGHT_FRONT, Kx11DimNaviMapper.turnId(14, 0))
        assertEquals(Kx11DimNaviMapper.TURN_LEFT_BACK, Kx11DimNaviMapper.turnId(47, 0))
        assertEquals(Kx11DimNaviMapper.TURN_RIGHT_BACK, Kx11DimNaviMapper.turnId(48, 0))
        assertEquals(Kx11DimNaviMapper.TURN_U_TURN_LEFT, Kx11DimNaviMapper.turnId(4, 0))
        assertEquals(Kx11DimNaviMapper.TURN_U_TURN_RIGHT, Kx11DimNaviMapper.turnId(4, 1))
        assertEquals(Kx11DimNaviMapper.TURN_ROUNDABOUT_ENTER, Kx11DimNaviMapper.turnId(6, 0))
        assertEquals(Kx11DimNaviMapper.TURN_ROUNDABOUT_EXIT, Kx11DimNaviMapper.turnId(7, 0))
        assertEquals(Kx11DimNaviMapper.TURN_DESTINATION, Kx11DimNaviMapper.turnId(10, 0))
        assertEquals(Kx11DimNaviMapper.TURN_NONE, Kx11DimNaviMapper.turnId(0, 0))
    }

    @Test
    fun mapsRoundaboutExitsForRightAndLeftHandTraffic() {
        // Right-hand traffic (China): Apple 28..37 -> exit 1..10 -> 121..130
        assertEquals(121, Kx11DimNaviMapper.turnId(28, 0))
        assertEquals(123, Kx11DimNaviMapper.turnId(30, 0))
        assertEquals(130, Kx11DimNaviMapper.turnId(37, 0))
        // Left-hand traffic: Apple 28..37 -> exit 1..10 -> 131..140
        assertEquals(131, Kx11DimNaviMapper.turnId(28, 1))
        assertEquals(140, Kx11DimNaviMapper.turnId(37, 1))
        // Exit > 10 degrades safely to generic roundabout enter
        assertEquals(Kx11DimNaviMapper.TURN_ROUNDABOUT_ENTER, Kx11DimNaviMapper.turnId(38, 0))
    }

    @Test
    fun mapsCapturedRealCarThreeLanePayloadAccurately() {
        val sample = hex(
            "00 06 00 00 00 2a" +
                " 00 06 00 01 00 00" +
                " 00 15 00 02 00 06 00 00 00 00 00 05 00 01 00 00 06 00 02 ff a6" +
                " 00 15 00 02 00 06 00 00 00 01 00 05 00 01 00 00 06 00 02 00 00" +
                " 00 1b 00 02 00 06 00 00 00 02 00 05 00 01 02 00 06 00 02 00 00" +
                " 00 06 00 03 00 5a",
        )
        val lanes = Iap2LaneGuidance.parse(sample)
        assertArrayEquals(arrayOf("0,1", "1,0", "2,33"), Kx11DimNaviMapper.encodeLanes(lanes))
    }

    @Test
    fun parsesAndMapsMultiAngleCompoundLaneWithPartialHighlight() {
        // One lane with three repeated FIELD_ANGLE (-90, 0, +90) and one FIELD_ANGLE_HIGHLIGHT (0)
        val payload = hex(
            "00 27 00 02" +
                " 00 06 00 00 00 00" +
                " 00 05 00 01 02" +
                " 00 06 00 02 ff a6" +
                " 00 06 00 02 00 00" +
                " 00 06 00 02 00 5a" +
                " 00 06 00 03 00 00",
        )
        val lanes = Iap2LaneGuidance.parse(payload)
        assertEquals(1, lanes.size)
        assertEquals(listOf(-90, 0, 90), lanes[0].angles)
        assertEquals(listOf(0), lanes[0].highlightAngles)
        // FRONT + LEFT + RIGHT with FRONT passable -> 36
        assertEquals(36, Kx11DimNaviMapper.laneIconId(lanes[0]))
    }

    @Test
    fun degradesUnsupportedMultiHighlightSafelyWithoutHighlightingWrongBranch() {
        // FRONT + LEFT + RIGHT with FRONT + RIGHT highlighted: OEM table has no two-branch highlight
        // for 3-way lane, so it must degrade to one of the passable single arrows (never LEFT=16).
        val lane = LaneGuidanceLane(
            index = 0,
            status = 2,
            angle = 0,
            highlightAngle = 0,
            angles = listOf(-90, 0, 90),
            highlightAngles = listOf(0, 90),
        )
        val icon = Kx11DimNaviMapper.laneIconId(lane)
        assertEquals(15, icon)
        assertNull(Kx11DimNaviMapper.encodeLanes(emptyList()))
    }

    private fun hex(text: String): ByteArray =
        text.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()
}
