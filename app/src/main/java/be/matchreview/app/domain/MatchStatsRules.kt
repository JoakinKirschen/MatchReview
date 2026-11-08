package be.matchreview.app.domain

import be.matchreview.app.data.MatchEvent

/** Event types for the quick match actions; a red card for our player is a DISMISSAL. */
object MatchActions {
    const val OUR_SHOT_ON_TARGET = "OUR_SHOT_ON_TARGET"
    const val OUR_SHOT_OFF_TARGET = "OUR_SHOT_OFF_TARGET"
    const val OUR_CORNER = "OUR_CORNER"
    const val YELLOW_CARD = "YELLOW_CARD"
    const val RED_CARD = "RED_CARD"
    const val DISMISSAL = "DISMISSAL"
    const val OPPONENT_SHOT_ON_TARGET = "OPPONENT_SHOT_ON_TARGET"
    const val OPPONENT_SHOT_OFF_TARGET = "OPPONENT_SHOT_OFF_TARGET"
    const val OPPONENT_CORNER = "OPPONENT_CORNER"
    const val OPPONENT_YELLOW_CARD = "OPPONENT_YELLOW_CARD"
    const val OPPONENT_RED_CARD = "OPPONENT_RED_CARD"
    /** A moment flagged while filming, to find it back in the video. */
    const val MARK = "MARK"

    /** Actions that can simply be deleted from the timeline. */
    val DELETABLE = setOf(
        OUR_SHOT_ON_TARGET, OUR_SHOT_OFF_TARGET, OUR_CORNER, YELLOW_CARD, RED_CARD,
        OPPONENT_SHOT_ON_TARGET, OPPONENT_SHOT_OFF_TARGET, OPPONENT_CORNER,
        OPPONENT_YELLOW_CARD, OPPONENT_RED_CARD, "KEEPER_SAVE", MARK
    )

    fun label(type: String): String? = when (type) {
        OUR_SHOT_ON_TARGET, OPPONENT_SHOT_ON_TARGET -> "Shot on target"
        OUR_SHOT_OFF_TARGET, OPPONENT_SHOT_OFF_TARGET -> "Shot off target"
        OUR_CORNER, OPPONENT_CORNER -> "Corner"
        YELLOW_CARD, OPPONENT_YELLOW_CARD -> "Yellow card"
        RED_CARD, OPPONENT_RED_CARD -> "Red card"
        MARK -> "Marked moment"
        else -> null
    }

    fun isOpponent(type: String): Boolean = type.startsWith("OPPONENT_")
}

data class TeamMatchStats(
    val goals: Int,
    val shotsOnTarget: Int,
    val shotsOffTarget: Int,
    val corners: Int,
    val yellowCards: Int,
    val redCards: Int
) {
    val shots: Int get() = shotsOnTarget + shotsOffTarget
}

data class MatchStats(val ours: TeamMatchStats, val opponent: TeamMatchStats) {
    /** True when anything beyond goals was recorded, so a stats table is worth showing. */
    val hasDetail: Boolean
        get() = listOf(ours, opponent).any {
            it.shotsOffTarget > 0 || it.corners > 0 || it.yellowCards > 0 || it.redCards > 0 ||
                it.shotsOnTarget > it.goals
        }
}

object MatchStatsRules {
    /**
     * Goals always count as shots on target, and a save by our keeper is an opponent shot on
     * target, so the coach does not have to record those twice.
     */
    fun compute(events: List<MatchEvent>): MatchStats {
        fun count(vararg types: String) = events.count { it.type in types }
        val ourGoals = count("OUR_GOAL")
        val opponentGoals = count("OPPONENT_GOAL")
        return MatchStats(
            ours = TeamMatchStats(
                goals = ourGoals,
                shotsOnTarget = ourGoals + count(MatchActions.OUR_SHOT_ON_TARGET),
                shotsOffTarget = count(MatchActions.OUR_SHOT_OFF_TARGET),
                corners = count(MatchActions.OUR_CORNER),
                yellowCards = count(MatchActions.YELLOW_CARD),
                redCards = count(MatchActions.RED_CARD, MatchActions.DISMISSAL)
            ),
            opponent = TeamMatchStats(
                goals = opponentGoals,
                shotsOnTarget = opponentGoals + count(MatchActions.OPPONENT_SHOT_ON_TARGET, "KEEPER_SAVE"),
                shotsOffTarget = count(MatchActions.OPPONENT_SHOT_OFF_TARGET),
                corners = count(MatchActions.OPPONENT_CORNER),
                yellowCards = count(MatchActions.OPPONENT_YELLOW_CARD),
                redCards = count(MatchActions.OPPONENT_RED_CARD)
            )
        )
    }

    /** Rows of (label, ours, opponent) for tables in the app and the PDF. */
    fun rows(stats: MatchStats): List<Triple<String, Int, Int>> = listOf(
        Triple("Goals", stats.ours.goals, stats.opponent.goals),
        Triple("Shots", stats.ours.shots, stats.opponent.shots),
        Triple("On target", stats.ours.shotsOnTarget, stats.opponent.shotsOnTarget),
        Triple("Corners", stats.ours.corners, stats.opponent.corners),
        Triple("Yellow cards", stats.ours.yellowCards, stats.opponent.yellowCards),
        Triple("Red cards", stats.ours.redCards, stats.opponent.redCards)
    )
}
