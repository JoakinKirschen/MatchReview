package be.matchreview.app.domain

import be.matchreview.app.i18n.tr

import be.matchreview.app.data.MatchEvent

/** One line per goal, in match order, such as "12' Sam (assist Lee)". */
object GoalSummaryRules {
    /** Football minutes count from 1: a goal after 11:20 is a 12th-minute goal. */
    fun minute(timestampMs: Long): Long = timestampMs.coerceAtLeast(0L) / 60_000L + 1

    fun lines(
        events: List<MatchEvent>,
        playerNames: Map<Long, String>,
        teamName: String,
        opponentName: String
    ): List<String> =
        events
            .filter { it.type == "OUR_GOAL" || it.type == "OPPONENT_GOAL" }
            .sortedWith(compareBy<MatchEvent> { it.timestampMs }.thenBy { it.id })
            .map { goal ->
                val scorer = if (goal.type == "OUR_GOAL") {
                    goal.playerId?.let(playerNames::get) ?: teamName
                } else {
                    opponentName
                }
                val assist = goal.relatedPlayerId
                    ?.takeIf { goal.type == "OUR_GOAL" }
                    ?.let(playerNames::get)
                    ?.let { tr(" (assist {value})", "value" to it) }
                    .orEmpty()
                "${minute(goal.timestampMs)}' $scorer$assist"
            }
}
