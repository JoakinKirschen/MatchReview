package be.matchreview.app.domain

/** What one player did while one lineup picture was on the pitch. */
data class GoalMarks(val goals: Int = 0, val assists: Int = 0)

/**
 * Marks scorers and assist givers on the lineup pictures. Each of our goals belongs to the
 * picture of the lineup that was on the pitch when it was scored: the last lineup change
 * before it in timeline order. So every goal is marked once, on one picture.
 */
object LineupGoalRules {
    /**
     * Marks per lineup picture, keyed by [TimelineItem.key], then by player id. [items] are
     * grouped by [TimelineGrouping.group], oldest or newest first. Goals before the first
     * picture, opponent goals and players who are not on the picture get no mark.
     */
    fun marks(items: List<TimelineItem>): Map<String, Map<Long, GoalMarks>> {
        val result = mutableMapOf<String, Map<Long, GoalMarks>>()
        var current: TimelineItem.LineupChange? = null
        var marks = mutableMapOf<Long, GoalMarks>()

        fun close() {
            val change = current ?: return
            if (marks.isNotEmpty()) result[change.key] = marks
        }

        // The same order as the review timeline: match time, then the order of recording.
        items.sortedWith(compareBy<TimelineItem> { it.timestampMs }.thenBy { firstEventId(it) }).forEach { item ->
            when (item) {
                is TimelineItem.LineupChange -> {
                    close()
                    current = item
                    marks = mutableMapOf()
                }
                is TimelineItem.Single -> {
                    val goal = item.event
                    val change = current
                    if (goal.type != "OUR_GOAL" || change == null) return@forEach
                    val onPitch = change.snapshot.map { it.playerId }.toSet()
                    goal.playerId?.takeIf { it in onPitch }?.let { scorer ->
                        val now = marks[scorer] ?: GoalMarks()
                        marks[scorer] = now.copy(goals = now.goals + 1)
                    }
                    goal.relatedPlayerId?.takeIf { it in onPitch && it != goal.playerId }?.let { assist ->
                        val now = marks[assist] ?: GoalMarks()
                        marks[assist] = now.copy(assists = now.assists + 1)
                    }
                }
            }
        }
        close()
        return result
    }

    private fun firstEventId(item: TimelineItem): Long = when (item) {
        is TimelineItem.LineupChange -> item.events.minOf { it.id }
        is TimelineItem.Single -> item.event.id
    }
}
