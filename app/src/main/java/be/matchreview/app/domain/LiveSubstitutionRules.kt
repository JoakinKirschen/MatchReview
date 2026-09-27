package be.matchreview.app.domain

import be.matchreview.app.data.MatchLineupPlacement
import be.matchreview.app.data.MatchSquadPlayer
import be.matchreview.app.data.PlayerMatchState

object LiveSubstitutionRules {
    fun eligibleBenchPlayerIds(
        placements: List<MatchLineupPlacement>,
        squad: List<MatchSquadPlayer>
    ): Set<Long> {
        val benchIds = placements.filterNot { it.onPitch }.mapTo(mutableSetOf()) { it.playerId }
        return squad.asSequence()
            .filter { it.playerId in benchIds }
            .filter { it.selected }
            .filter { it.state !in setOf(
                PlayerMatchState.UNAVAILABLE,
                PlayerMatchState.REMOVED,
                PlayerMatchState.DISMISSED
            ) }
            .map { it.playerId }
            .toSet()
    }

    fun canSubstitute(
        outgoingPlayerId: Long,
        incomingPlayerId: Long,
        placements: List<MatchLineupPlacement>,
        squad: List<MatchSquadPlayer>
    ): Boolean {
        if (outgoingPlayerId == incomingPlayerId) return false
        val outgoing = placements.firstOrNull { it.playerId == outgoingPlayerId }
        if (outgoing?.onPitch != true) return false
        return incomingPlayerId in eligibleBenchPlayerIds(placements, squad)
    }

    fun canAddWithoutReplacement(
        incomingPlayerId: Long,
        maximumOnPitch: Int,
        placements: List<MatchLineupPlacement>,
        squad: List<MatchSquadPlayer>
    ): Boolean =
        placements.count { it.onPitch } < maximumOnPitch &&
            incomingPlayerId in eligibleBenchPlayerIds(placements, squad)

    fun outgoingState(rollingSubstitutions: Boolean): PlayerMatchState =
        if (rollingSubstitutions) PlayerMatchState.BENCH else PlayerMatchState.REMOVED
}
