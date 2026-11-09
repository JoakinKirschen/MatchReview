package be.matchreview.app.domain

import be.matchreview.app.i18n.tr

import be.matchreview.app.data.GameMatch
import be.matchreview.app.data.MatchEvent
import be.matchreview.app.data.MatchLineupPlacement
import be.matchreview.app.data.MatchStatus
import be.matchreview.app.data.PlayerParticipation
import be.matchreview.app.data.RecordingSegment
import be.matchreview.app.data.RecordingStatus

enum class IntegritySeverity { INFO, WARNING, BLOCKING }

data class IntegrityIssue(
    val code: String,
    val message: String,
    val severity: IntegritySeverity
)

/** Pure cross-record checks used before export and while reviewing a completed match. */
object MatchIntegrityRules {
    fun inspect(
        match: GameMatch,
        events: List<MatchEvent>,
        lineup: List<MatchLineupPlacement>,
        participations: List<PlayerParticipation>,
        recordings: List<RecordingSegment>
    ): List<IntegrityIssue> = buildList {
        val derivedOurScore = events.count { it.type == "OUR_GOAL" }
        val derivedOpponentScore = events.count { it.type == "OPPONENT_GOAL" }
        if (match.ourScore != derivedOurScore || match.opponentScore != derivedOpponentScore) {
            add(IntegrityIssue("SCORE_MISMATCH", tr("Stored score does not match goal events."), IntegritySeverity.BLOCKING))
        }
        if (lineup.count { it.onPitch } > match.playersOnPitch) {
            add(IntegrityIssue("TOO_MANY_ON_PITCH", tr("The lineup exceeds the configured match size."), IntegritySeverity.BLOCKING))
        }
        participations.filter { it.endMatchTimeMs != null }.forEach {
            if (it.endMatchTimeMs!! < it.startMatchTimeMs) {
                add(IntegrityIssue("NEGATIVE_PARTICIPATION", tr("A player interval ends before it starts."), IntegritySeverity.BLOCKING))
            }
        }
        if (match.status == MatchStatus.FINISHED && participations.any { it.endMatchTimeMs == null }) {
            add(IntegrityIssue("OPEN_PARTICIPATION", tr("A finished match still has an open player interval."), IntegritySeverity.WARNING))
        }
        recordings.filter { it.status == RecordingStatus.COMPLETED }.forEach {
            if (it.uri.isNullOrBlank() || it.recordingDurationMs <= 0L) {
                add(IntegrityIssue("INVALID_COMPLETED_RECORDING", tr("A completed recording has no usable file metadata."), IntegritySeverity.WARNING))
            }
        }
        events.filter { it.recordingSegmentId != null && it.recordingOffsetMs == null }.forEach {
            add(IntegrityIssue("INCOMPLETE_VIDEO_LINK", tr("An event has a recording link without an offset."), IntegritySeverity.WARNING))
        }
    }.distinctBy { it.code }
}
