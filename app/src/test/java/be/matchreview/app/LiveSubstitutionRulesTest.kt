package be.matchreview.app

import be.matchreview.app.data.*
import be.matchreview.app.domain.LiveSubstitutionRules
import org.junit.Assert.*
import org.junit.Test

class LiveSubstitutionRulesTest {
    private val placements = listOf(
        MatchLineupPlacement(matchId = 1, playerId = 10, onPitch = true),
        MatchLineupPlacement(matchId = 1, playerId = 20, onPitch = false),
        MatchLineupPlacement(matchId = 1, playerId = 30, onPitch = false)
    )

    @Test
    fun selectedBenchPlayerCanReplaceOnPitchPlayer() {
        val squad = listOf(
            MatchSquadPlayer(1, 10, AvailabilityStatus.AVAILABLE, true, PlayerMatchState.ON_PITCH),
            MatchSquadPlayer(1, 20, AvailabilityStatus.AVAILABLE, true, PlayerMatchState.BENCH)
        )

        assertTrue(LiveSubstitutionRules.canSubstitute(10, 20, placements, squad))
    }

    @Test
    fun removedPlayerCannotReturn() {
        val squad = listOf(
            MatchSquadPlayer(1, 10, AvailabilityStatus.AVAILABLE, true, PlayerMatchState.ON_PITCH),
            MatchSquadPlayer(1, 20, AvailabilityStatus.AVAILABLE, true, PlayerMatchState.REMOVED)
        )

        assertFalse(LiveSubstitutionRules.canSubstitute(10, 20, placements, squad))
    }

    @Test
    fun dismissedPlayerCannotReturn() {
        val squad = listOf(
            MatchSquadPlayer(1, 10, AvailabilityStatus.AVAILABLE, true, PlayerMatchState.ON_PITCH),
            MatchSquadPlayer(1, 20, AvailabilityStatus.AVAILABLE, true, PlayerMatchState.DISMISSED)
        )

        assertFalse(LiveSubstitutionRules.canSubstitute(10, 20, placements, squad))
    }

    @Test
    fun extraPlayerOnlyEntersWhenPitchHasSpace() {
        val squad = listOf(
            MatchSquadPlayer(1, 20, AvailabilityStatus.AVAILABLE, true, PlayerMatchState.BENCH)
        )

        assertTrue(LiveSubstitutionRules.canAddWithoutReplacement(20, 2, placements, squad))
        assertFalse(LiveSubstitutionRules.canAddWithoutReplacement(20, 1, placements, squad))
    }

    @Test
    fun nonRollingSubstitutionRemovesOutgoingPlayer() {
        assertEquals(
            PlayerMatchState.REMOVED,
            LiveSubstitutionRules.outgoingState(rollingSubstitutions = false)
        )
        assertEquals(
            PlayerMatchState.BENCH,
            LiveSubstitutionRules.outgoingState(rollingSubstitutions = true)
        )
    }
}
