package be.matchreview.app.domain

import be.matchreview.app.i18n.tr

data class RecordingBudget(
    val canStart: Boolean,
    val safeBytesAvailable: Long,
    val estimatedMinutes: Int,
    val message: String
)

object StorageBudgetRules {
    const val SAFETY_RESERVE_BYTES = 512L * 1024L * 1024L
    const val DEFAULT_BYTES_PER_MINUTE = 90L * 1024L * 1024L

    fun recordingBudget(
        availableBytes: Long,
        bytesPerMinute: Long = DEFAULT_BYTES_PER_MINUTE
    ): RecordingBudget {
        val safe = (availableBytes - SAFETY_RESERVE_BYTES).coerceAtLeast(0L)
        val minutes = if (bytesPerMinute > 0) (safe / bytesPerMinute).toInt() else 0
        return RecordingBudget(
            canStart = minutes >= 2,
            safeBytesAvailable = safe,
            estimatedMinutes = minutes,
            message = if (minutes >= 2) tr("About {minutes} minutes of safe recording space.", "minutes" to minutes)
                else tr("Not enough safe storage to start recording.")
        )
    }
}
