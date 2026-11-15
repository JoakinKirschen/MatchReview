package be.matchreview.app

import be.matchreview.app.data.MatchEvent
import be.matchreview.app.domain.GoalMarks
import be.matchreview.app.domain.LineupGoalRules
import be.matchreview.app.domain.StartingLineupRules
import be.matchreview.app.domain.TimelineGrouping
import be.matchreview.app.domain.TimelineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineupGoalRulesTest {
    // Players 1-4 start; at 20:00 player 5 comes on for player 4.
    private val startSnapshot = "1:0.500:0.900;2:0.300:0.600;3:0.700:0.600;4:0.500:0.200"
    private val afterSubSnapshot = "1:0.500:0.900;2:0.300:0.600;3:0.700:0.600;5:0.500:0.200"

    private fun kickOff() = MatchEvent(
        id = 1, matchId = 1, timestampMs = 0, type = StartingLineupRules.KICK_OFF, lineupSnapshot = startSnapshot
    )

    private fun goal(id: Long, minute: Int, scorer: Long?, assist: Long? = null) = MatchEvent(
        id = id, matchId = 1, playerId = scorer, relatedPlayerId = assist,
        timestampMs = minute * 60_000L, type = "OUR_GOAL"
    )

    private fun sub(id: Long, minute: Int, off: Long, on: Long) = MatchEvent(
        id = id, matchId = 1, playerId = off, relatedPlayerId = on,
        timestampMs = minute * 60_000L, type = "SUBSTITUTION", lineupSnapshot = afterSubSnapshot
    )

    private fun items(events: List<MatchEvent>) = TimelineGrouping.group(
        events.sortedWith(compareBy<MatchEvent> { it.timestampMs }.thenBy { it.id })
    )

    private fun keys(items: List<TimelineItem>) = items.filterIsInstance<TimelineItem.LineupChange>().map { it.key }

    @Test
    fun eachGoalIsMarkedOnTheLineupThatWasOnThePitch() {
        val timeline = items(
            listOf(
                kickOff(),
                goal(2, 7, scorer = 4, assist = 2),
                goal(3, 12, scorer = 3),
                goal(4, 18, scorer = 4, assist = 3),
                sub(5, 20, off = 4, on = 5),
                goal(6, 26, scorer = 5, assist = 1)
            )
        )
        val (start, afterSub) = keys(timeline)

        val marks = LineupGoalRules.marks(timeline)

        assertEquals(
            mapOf(4L to GoalMarks(goals = 2), 2L to GoalMarks(assists = 1), 3L to GoalMarks(goals = 1, assists = 1)),
            marks[start]
        )
        assertEquals(mapOf(5L to GoalMarks(goals = 1), 1L to GoalMarks(assists = 1)), marks[afterSub])
    }

    @Test
    fun newestFirstTimelinesGiveTheSameMarks() {
        val events = listOf(kickOff(), goal(2, 7, scorer = 4), sub(3, 20, off = 4, on = 5), goal(4, 26, scorer = 5))
        val oldestFirst = items(events)
        val newestFirst = TimelineGrouping.group(
            events.sortedWith(compareByDescending<MatchEvent> { it.timestampMs }.thenByDescending { it.id })
        )

        assertEquals(LineupGoalRules.marks(oldestFirst), LineupGoalRules.marks(newestFirst))
    }

    @Test
    fun goalsThatCannotBePlacedAreNotMarked() {
        val timeline = items(
            listOf(
                // Before any lineup picture.
                goal(1, 0, scorer = 2).copy(timestampMs = -1),
                kickOff().copy(id = 2),
                // Opponent goal, goal without a scorer, and a scorer who is not on the picture.
                MatchEvent(id = 3, matchId = 1, playerId = 2, timestampMs = 60_000, type = "OPPONENT_GOAL"),
                goal(4, 2, scorer = null, assist = 3),
                goal(5, 3, scorer = 9, assist = 9)
            )
        )

        val marks = LineupGoalRules.marks(timeline)

        assertEquals(mapOf(3L to GoalMarks(assists = 1)), marks.values.single())
    }

    @Test
    fun picturesWithoutGoalsHaveNoEntry() {
        val timeline = items(listOf(kickOff(), sub(2, 20, off = 4, on = 5), goal(3, 25, scorer = 5)))
        val (start, afterSub) = keys(timeline)

        val marks = LineupGoalRules.marks(timeline)

        assertTrue(start !in marks)
        assertEquals(mapOf(5L to GoalMarks(goals = 1)), marks[afterSub])
    }
}
