package be.matchreview.app.domain

import be.matchreview.app.data.AvailabilityStatus
import be.matchreview.app.data.MatchSquadPlayer

data class SquadSelectionSummary(
    val rosterCount: Int,
    val availableCount: Int,
    val selectedCount: Int,
    val requiredOnPitch: Int
) {
    val canContinue: Boolean get() = selectedCount > 0
    val hasEnoughForStartingLineup: Boolean get() = selectedCount >= requiredOnPitch
}

object SquadSelectionRules {
    fun canSelect(availability: AvailabilityStatus): Boolean =
        availability == AvailabilityStatus.AVAILABLE

    fun summary(
        squad: List<MatchSquadPlayer>,
        rosterCount: Int,
        requiredOnPitch: Int
    ): SquadSelectionSummary = SquadSelectionSummary(
        rosterCount = rosterCount,
        availableCount = squad.count { it.availability == AvailabilityStatus.AVAILABLE },
        selectedCount = squad.count { it.selected },
        requiredOnPitch = requiredOnPitch
    )
}
