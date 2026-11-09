package be.matchreview.app.domain

import be.matchreview.app.i18n.tr

import be.matchreview.app.data.MatchEvent
import be.matchreview.app.data.MatchLineupPlacement
import be.matchreview.app.data.ParticipationReason
import be.matchreview.app.data.PlayerParticipation

/** The players and positions a match started with, shown before the lineup changes. */
object StartingLineupRules {
    /** Stored at kick-off with the starting positions. */
    const val KICK_OFF = "KICK_OFF"

    /** Id of a starting lineup rebuilt for matches played before kick-off pictures existed. */
    const val RECONSTRUCTED_ID = -1L

    fun isReconstructed(event: MatchEvent): Boolean = event.id == RECONSTRUCTED_ID

    /**
     * Returns [events] with a starting-lineup event. Matches kicked off with this version
     * already have one. For older matches it is rebuilt: substitutes in the first lineup
     * change stood where the player they replaced had stood, and other starters keep their
     * last known spot. The rebuilt lineup is marked so it can be labelled as approximate.
     */
    fun withStartingLineup(
        matchId: Long,
        events: List<MatchEvent>,
        participations: List<PlayerParticipation>,
        placements: List<MatchLineupPlacement>
    ): List<MatchEvent> {
        if (events.any { it.type == KICK_OFF && !it.lineupSnapshot.isNullOrBlank() }) return events
        val starters = participations
            .filter { it.entryReason == ParticipationReason.STARTER }
            .map { it.playerId }
            .toSet()
        if (starters.isEmpty()) return events

        val lastKnown = placements.associate { it.playerId to SnapshotPosition(it.playerId, it.normalizedX, it.normalizedY) }
        val firstChange = TimelineGrouping
            .group(events.sortedWith(compareBy<MatchEvent> { it.timestampMs }.thenBy { it.id }))
            .filterIsInstance<TimelineItem.LineupChange>()
            .firstOrNull()

        val positions = mutableMapOf<Long, SnapshotPosition>()
        if (firstChange != null) {
            val replacedBy = firstChange.events
                .filter { it.type == "SUBSTITUTION" && it.playerId != null && it.relatedPlayerId != null }
                .associate { it.relatedPlayerId!! to it.playerId!! }
            firstChange.snapshot.forEach { position ->
                val starterId = replacedBy[position.playerId] ?: position.playerId
                positions[starterId] = position.copy(playerId = starterId)
            }
        }
        starters.forEach { id ->
            if (id !in positions) lastKnown[id]?.let { positions[id] = it }
        }
        val lineup = positions.filterKeys { it in starters }.values.toList()
        if (lineup.isEmpty()) return events

        val kickOff = MatchEvent(
            id = RECONSTRUCTED_ID,
            matchId = matchId,
            timestampMs = 0L,
            type = KICK_OFF,
            note = tr("Starting lineup (approximate)"),
            periodNumber = 1,
            lineupSnapshot = lineup
                .sortedBy { it.playerId }
                .joinToString(";") { "${it.playerId}:${format(it.normalizedX)}:${format(it.normalizedY)}" }
        )
        return listOf(kickOff) + events
    }

    private fun format(value: Float): String = String.format(java.util.Locale.ROOT, "%.3f", value.coerceIn(0f, 1f))
}
