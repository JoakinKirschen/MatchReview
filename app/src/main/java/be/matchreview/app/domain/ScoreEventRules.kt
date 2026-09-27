package be.matchreview.app.domain

data class EventScore(val ourScore: Int, val opponentScore: Int)

object ScoreEventRules {
    fun scoreForTypes(types: Iterable<String>): EventScore {
        var ours = 0
        var opponents = 0
        types.forEach {
            when (it) {
                "OUR_GOAL" -> ours++
                "OPPONENT_GOAL" -> opponents++
            }
        }
        return EventScore(ours, opponents)
    }

    fun isValidGoalAttribution(scorerPlayerId: Long?, assistPlayerId: Long?): Boolean =
        scorerPlayerId == null || scorerPlayerId != assistPlayerId

    fun correctionDelta(current: Int, target: Int): Int =
        target.coerceIn(0, 99) - current.coerceAtLeast(0)
}
