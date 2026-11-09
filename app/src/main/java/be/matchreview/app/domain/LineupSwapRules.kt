package be.matchreview.app.domain

import be.matchreview.app.i18n.tr

import be.matchreview.app.data.MatchLineupPlacement

object LineupSwapRules {
    fun swap(
        selected: MatchLineupPlacement,
        occupied: MatchLineupPlacement
    ): Pair<MatchLineupPlacement, MatchLineupPlacement> {
        require(selected.matchId == occupied.matchId) { tr("Players must belong to the same match") }
        return occupied.copy(playerId = selected.playerId) to
            selected.copy(playerId = occupied.playerId)
    }
}
