package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane

/**
 * Maps Apple CarPlay iAP2 route guidance & lane guidance (0x5201 / 0x5202 / 0x5204)
 * into Geely KX11_A2 OEM DIM QNX icon vocabularies (TurnKind 101..140 & Consts.LaneInfo 0..103).
 */
internal object Kx11DimNaviMapper {

    enum class LaneDir { FRONT, LEFT, RIGHT, BACK }

    // Geely KX11 TurnKind icon IDs (101..140).
    const val TURN_NONE = 0
    const val TURN_LEFT = 102
    const val TURN_RIGHT = 103
    const val TURN_LEFT_FRONT = 104
    const val TURN_RIGHT_FRONT = 105
    const val TURN_LEFT_BACK = 106
    const val TURN_RIGHT_BACK = 107
    const val TURN_U_TURN_LEFT = 108
    const val TURN_STRAIGHT = 109
    const val TURN_VIA = 110
    const val TURN_ROUNDABOUT_ENTER = 111
    const val TURN_ROUNDABOUT_EXIT = 112
    const val TURN_TOLLGATE = 114
    const val TURN_DESTINATION = 115
    const val TURN_U_TURN_RIGHT = 119

    private const val ROUNDABOUT_EXIT_BASE_RHT = 120 // + exit 1..10 -> 121..130
    private const val ROUNDABOUT_EXIT_BASE_LHT = 130 // + exit 1..10 -> 131..140

    /** Maps Apple RouteGuidanceManeuverType + drivingSide (1 = left-hand) to Geely KX11 TurnId. */
    fun turnId(appleType: Int, drivingSide: Int): Int {
        val leftHand = drivingSide == 1
        if (appleType in 28..46) {
            val exit = appleType - 27
            return when {
                exit in 1..10 && leftHand -> ROUNDABOUT_EXIT_BASE_LHT + exit
                exit in 1..10 -> ROUNDABOUT_EXIT_BASE_RHT + exit
                else -> TURN_ROUNDABOUT_ENTER
            }
        }
        return when (appleType) {
            1, 20 -> TURN_LEFT
            2, 21 -> TURN_RIGHT
            13, 22, 49, 52 -> TURN_LEFT_FRONT
            14, 23, 50, 53 -> TURN_RIGHT_FRONT
            47 -> TURN_LEFT_BACK
            48 -> TURN_RIGHT_BACK
            4, 18, 19, 26 -> if (leftHand) TURN_U_TURN_RIGHT else TURN_U_TURN_LEFT
            3, 5, 8, 9, 11, 51 -> TURN_STRAIGHT
            6 -> TURN_ROUNDABOUT_ENTER
            7 -> TURN_ROUNDABOUT_EXIT
            10, 12, 24, 25, 27 -> TURN_DESTINATION
            else -> TURN_NONE
        }
    }

    /** Quantizes signed iAP2 lane angle (0 = straight, + = right, - = left) into 4 cardinal lane dirs. */
    fun quantizeAngle(degrees: Int): LaneDir {
        val norm = ((degrees % 360) + 540) % 360 - 180
        return when {
            norm in -22..22 -> LaneDir.FRONT
            norm in 23..150 -> LaneDir.RIGHT
            norm in -150..-23 -> LaneDir.LEFT
            else -> LaneDir.BACK
        }
    }

    /**
     * Maps one iAP2 0x5204 lane into Geely Consts.LaneInfo icon id (0..103).
     * Never highlights a wrong direction: when a multi-branch partial-highlight combo has no
     * exact icon in the OEM table, degrades cleanly to the primary recommended single arrow.
     */
    fun laneIconId(lane: LaneGuidanceLane): Int {
        val baseAngles = lane.angles.ifEmpty { listOf(lane.angle) }
        val highlightList = lane.highlightAngles.ifEmpty { listOfNotNull(lane.highlightAngle) }
        val allDirs = (baseAngles + highlightList).map(::quantizeAngle).toSet()
        val passDirs = when {
            highlightList.isNotEmpty() -> highlightList.map(::quantizeAngle).toSet()
            lane.recommended -> allDirs
            else -> emptySet()
        }
        return lookupLaneIcon(allDirs, passDirs)
    }

    /** Encodes a lane list into the "index,iconId" string array consumed by DIMNaviInteractioncService. */
    fun encodeLanes(lanes: List<LaneGuidanceLane>): Array<String>? {
        if (lanes.isEmpty()) return null
        return lanes.map { lane -> "${lane.index},${laneIconId(lane)}" }.toTypedArray()
    }

    private fun lookupLaneIcon(all: Set<LaneDir>, pass: Set<LaneDir>): Int {
        // 1. All impassable (dim grey arrows)
        if (pass.isEmpty()) {
            return when (all) {
                setOf(LaneDir.FRONT) -> 0
                setOf(LaneDir.LEFT) -> 1
                setOf(LaneDir.FRONT, LaneDir.LEFT) -> 2
                setOf(LaneDir.RIGHT) -> 3
                setOf(LaneDir.FRONT, LaneDir.RIGHT) -> 4
                setOf(LaneDir.BACK) -> 5
                setOf(LaneDir.LEFT, LaneDir.RIGHT) -> 6
                setOf(LaneDir.FRONT, LaneDir.LEFT, LaneDir.RIGHT) -> 7
                setOf(LaneDir.FRONT, LaneDir.BACK) -> 9
                setOf(LaneDir.LEFT, LaneDir.BACK) -> 11
                setOf(LaneDir.FRONT, LaneDir.LEFT, LaneDir.BACK) -> 49
                setOf(LaneDir.RIGHT, LaneDir.BACK) -> 50
                setOf(LaneDir.LEFT, LaneDir.RIGHT, LaneDir.BACK) -> 51
                setOf(LaneDir.FRONT, LaneDir.RIGHT, LaneDir.BACK) -> 52
                setOf(LaneDir.FRONT, LaneDir.LEFT, LaneDir.RIGHT, LaneDir.BACK) -> 90
                else -> 0
            }
        }
        // 2. All passable (all arrows highlighted)
        if (pass == all) {
            return when (all) {
                setOf(LaneDir.FRONT) -> 15
                setOf(LaneDir.LEFT) -> 16
                setOf(LaneDir.FRONT, LaneDir.LEFT) -> 17
                setOf(LaneDir.RIGHT) -> 18
                setOf(LaneDir.FRONT, LaneDir.RIGHT) -> 19
                setOf(LaneDir.BACK) -> 20
                setOf(LaneDir.LEFT, LaneDir.RIGHT) -> 21
                setOf(LaneDir.FRONT, LaneDir.LEFT, LaneDir.RIGHT) -> 22
                setOf(LaneDir.FRONT, LaneDir.BACK) -> 24
                setOf(LaneDir.LEFT, LaneDir.BACK) -> 26
                setOf(LaneDir.RIGHT, LaneDir.BACK) -> 27
                setOf(LaneDir.FRONT, LaneDir.LEFT, LaneDir.BACK) -> 56
                setOf(LaneDir.LEFT, LaneDir.RIGHT, LaneDir.BACK) -> 58
                setOf(LaneDir.FRONT, LaneDir.RIGHT, LaneDir.BACK) -> 59
                setOf(LaneDir.FRONT, LaneDir.LEFT, LaneDir.RIGHT, LaneDir.BACK) -> 103
                else -> singlePassableIcon(pass.first())
            }
        }
        // 3. Partial highlight on compound lanes (KX11 LaneInfo icon table)
        val exact = when (all) {
            setOf(LaneDir.FRONT, LaneDir.LEFT) -> when (pass) {
                setOf(LaneDir.FRONT) -> 30
                setOf(LaneDir.LEFT) -> 31
                else -> null
            }
            setOf(LaneDir.FRONT, LaneDir.RIGHT) -> when (pass) {
                setOf(LaneDir.FRONT) -> 32
                setOf(LaneDir.RIGHT) -> 33
                else -> null
            }
            setOf(LaneDir.LEFT, LaneDir.RIGHT) -> when (pass) {
                setOf(LaneDir.LEFT) -> 34
                setOf(LaneDir.RIGHT) -> 35
                else -> null
            }
            setOf(LaneDir.FRONT, LaneDir.LEFT, LaneDir.RIGHT) -> when (pass) {
                setOf(LaneDir.FRONT) -> 36
                setOf(LaneDir.LEFT) -> 37
                setOf(LaneDir.RIGHT) -> 38
                else -> null
            }
            setOf(LaneDir.FRONT, LaneDir.BACK) -> when (pass) {
                setOf(LaneDir.FRONT) -> 39
                setOf(LaneDir.BACK) -> 40
                else -> null
            }
            setOf(LaneDir.LEFT, LaneDir.BACK) -> when (pass) {
                setOf(LaneDir.LEFT) -> 43
                setOf(LaneDir.BACK) -> 44
                else -> null
            }
            setOf(LaneDir.RIGHT, LaneDir.BACK) -> when (pass) {
                setOf(LaneDir.RIGHT) -> 45
                setOf(LaneDir.BACK) -> 75
                else -> null
            }
            setOf(LaneDir.FRONT, LaneDir.LEFT, LaneDir.BACK) -> when (pass) {
                setOf(LaneDir.FRONT) -> 71
                setOf(LaneDir.LEFT) -> 72
                setOf(LaneDir.BACK) -> 73
                else -> null
            }
            setOf(LaneDir.LEFT, LaneDir.RIGHT, LaneDir.BACK) -> when (pass) {
                setOf(LaneDir.LEFT) -> 76
                setOf(LaneDir.RIGHT) -> 77
                setOf(LaneDir.BACK) -> 78
                else -> null
            }
            setOf(LaneDir.FRONT, LaneDir.RIGHT, LaneDir.BACK) -> when (pass) {
                setOf(LaneDir.FRONT) -> 79
                setOf(LaneDir.RIGHT) -> 80
                setOf(LaneDir.BACK) -> 81
                else -> null
            }
            setOf(LaneDir.FRONT, LaneDir.LEFT, LaneDir.RIGHT, LaneDir.BACK) -> when (pass) {
                setOf(LaneDir.FRONT) -> 94
                setOf(LaneDir.LEFT) -> 92
                setOf(LaneDir.RIGHT) -> 93
                setOf(LaneDir.BACK) -> 91
                else -> null
            }
            else -> null
        }
        return exact ?: singlePassableIcon(pass.first())
    }

    private fun singlePassableIcon(dir: LaneDir): Int = when (dir) {
        LaneDir.FRONT -> 15
        LaneDir.LEFT -> 16
        LaneDir.RIGHT -> 18
        LaneDir.BACK -> 20
    }
}
