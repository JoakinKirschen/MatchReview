package be.matchreview.app

import be.matchreview.app.data.AvailabilityStatus
import be.matchreview.app.data.MatchSquadPlayer
import be.matchreview.app.data.PlayerMatchState
import be.matchreview.app.domain.SquadSelectionRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SquadSelectionRulesTest {
    @Test
    fun onlyAvailablePlayersCanBeSelected() {
        assertTrue(SquadSelectionRules.canSelect(AvailabilityStatus.AVAILABLE))
        assertFalse(SquadSelectionRules.canSelect(AvailabilityStatus.UNKNOWN))
        assertFalse(SquadSelectionRules.canSelect(AvailabilityStatus.INJURED))
        assertFalse(SquadSelectionRules.canSelect(AvailabilityStatus.SUSPENDED))
    }

    @Test
    fun summaryCountsAvailabilityAndSelection() {
        val squad = listOf(
            MatchSquadPlayer(1, 10, AvailabilityStatus.AVAILABLE, true, PlayerMatchState.BENCH),
            MatchSquadPlayer(1, 11, AvailabilityStatus.AVAILABLE, false, PlayerMatchState.UNAVAILABLE),
            MatchSquadPlayer(1, 12, AvailabilityStatus.INJURED, false, PlayerMatchState.UNAVAILABLE)
        )

        val summary = SquadSelectionRules.summary(squad, rosterCount = 3, requiredOnPitch = 2)

        assertEquals(2, summary.availableCount)
        assertEquals(1, summary.selectedCount)
        assertFalse(summary.hasEnoughForStartingLineup)
        assertTrue(summary.canContinue)
    }
}
