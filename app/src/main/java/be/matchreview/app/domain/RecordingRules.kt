package be.matchreview.app.domain

import be.matchreview.app.data.RecordingStatus

object RecordingRules {
    fun canStart(status: RecordingStatus?): Boolean =
        status == null || status in setOf(
            RecordingStatus.COMPLETED,
            RecordingStatus.INTERRUPTED,
            RecordingStatus.FAILED
        )

    fun terminalStatus(cameraFinalizeSucceeded: Boolean, userRequestedStop: Boolean): RecordingStatus =
        when {
            cameraFinalizeSucceeded -> RecordingStatus.COMPLETED
            userRequestedStop -> RecordingStatus.FAILED
            else -> RecordingStatus.INTERRUPTED
        }

    fun endMatchClockMs(
        startMatchClockMs: Long,
        requestedEndMatchClockMs: Long?,
        recordedDurationMs: Long
    ): Long = (requestedEndMatchClockMs ?: startMatchClockMs + recordedDurationMs)
        .coerceAtLeast(startMatchClockMs)

    /**
     * CameraX reports some finalize "errors" (file-size limit, low storage, camera
     * source lost) after it has already written a playable file. Such clips are kept
     * as completed segments instead of being discarded.
     */
    fun keepsRecordedFile(
        finalizeSucceeded: Boolean,
        errorLeavesPlayableFile: Boolean,
        hasOutputUri: Boolean,
        bytesRecorded: Long
    ): Boolean = hasOutputUri &&
        (finalizeSucceeded || (errorLeavesPlayableFile && bytesRecorded > 0L))
}
