package be.matchreview.app

import be.matchreview.app.domain.EventScore
import be.matchreview.app.domain.ScoreEventRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreEventRulesTest {
    @Test
    fun scoreCountsOnlyGoalEvents() {
        val score = ScoreEventRules.scoreForTypes(
            listOf("OUR_GOAL", "SUBSTITUTION", "OPPONENT_GOAL", "OUR_GOAL")
        )
        assertEquals(EventScore(2, 1), score)
    }

    @Test
    fun scorerCannotAssistOwnGoal() {
        assertFalse(ScoreEventRules.isValidGoalAttribution(7L, 7L))
        assertTrue(ScoreEventRules.isValidGoalAttribution(7L, 8L))
        assertTrue(ScoreEventRules.isValidGoalAttribution(null, null))
    }

    @Test
    fun correctionDeltaIsClampedToSupportedRange() {
        assertEquals(2, ScoreEventRules.correctionDelta(1, 3))
        assertEquals(-2, ScoreEventRules.correctionDelta(4, 2))
        assertEquals(95, ScoreEventRules.correctionDelta(4, 120))
    }
}
