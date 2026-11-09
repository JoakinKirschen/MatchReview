package be.matchreview.app.domain

import be.matchreview.app.i18n.tr

import be.matchreview.app.data.RecordingSegment
import be.matchreview.app.data.RecordingStatus

enum class MediaIssueType { ACTIVE_STALE, COMPLETED_WITHOUT_URI, EMPTY_COMPLETED_FILE, FAILED_WITHOUT_REASON }

data class MediaIssue(val recordingId: Long, val type: MediaIssueType, val message: String)

object MediaIntegrityRules {
    fun inspect(
        recordings: List<RecordingSegment>,
        activeRecordingId: Long? = null,
        activeMatchId: Long? = null
    ): List<MediaIssue> = recordings.mapNotNull { segment ->
            when {
                segment.status == RecordingStatus.PREPARING &&
                    activeMatchId != segment.matchId ->
                    MediaIssue(segment.id, MediaIssueType.ACTIVE_STALE, tr("Recording was interrupted before it was finalized."))
                segment.status == RecordingStatus.RECORDING &&
                    segment.id != activeRecordingId ->
                    MediaIssue(segment.id, MediaIssueType.ACTIVE_STALE, tr("Recording was interrupted before it was finalized."))
                segment.status == RecordingStatus.COMPLETED && segment.uri.isNullOrBlank() ->
                    MediaIssue(segment.id, MediaIssueType.COMPLETED_WITHOUT_URI, tr("Completed recording has no file reference."))
                segment.status == RecordingStatus.COMPLETED && segment.bytesRecorded <= 0L ->
                    MediaIssue(segment.id, MediaIssueType.EMPTY_COMPLETED_FILE, tr("Completed recording is empty."))
                segment.status == RecordingStatus.FAILED && segment.errorMessage.isNullOrBlank() ->
                    MediaIssue(segment.id, MediaIssueType.FAILED_WITHOUT_REASON, tr("Recording failed without a diagnostic reason."))
                else -> null
            }
        }
}
