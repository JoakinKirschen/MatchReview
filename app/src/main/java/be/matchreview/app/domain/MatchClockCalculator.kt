package be.matchreview.app.domain

/**
 * Pure live-match clock calculations.
 *
 * Room stores the accumulated logical match time plus running clock segments.
 * The UI derives the ticking value and never writes a counter every second.
 */
object MatchClockCalculator {
    fun currentMatchTimeMs(
        accumulatedMatchTimeMs: Long,
        clockRunning: Boolean,
        monotonicStartMs: Long?,
        monotonicNowMs: Long
    ): Long {
        if (!clockRunning || monotonicStartMs == null) return accumulatedMatchTimeMs.coerceAtLeast(0)
        return (accumulatedMatchTimeMs + (monotonicNowMs - monotonicStartMs).coerceAtLeast(0))
            .coerceAtLeast(0)
    }

    /** Wall-clock slack allowed before a segment is treated as started in an earlier boot. */
    const val REBOOT_DETECTION_TOLERANCE_MS = 60_000L
    const val MAXIMUM_RECOVERY_MS = 6 * 60 * 60 * 1000L

    /**
     * Uses monotonic time during the same boot and falls back to wall time after
     * a reboot. Implausible wall-clock recovery is capped so the UI can safely
     * resume rather than adding days to a match.
     */
    fun recoveredCurrentMatchTimeMs(
        segmentStartMatchTimeMs: Long,
        monotonicStartMs: Long,
        wallClockStartMs: Long,
        monotonicNowMs: Long,
        wallClockNowMs: Long,
        maximumRecoveryMs: Long = MAXIMUM_RECOVERY_MS
    ): Long = (
        segmentStartMatchTimeMs + elapsedSinceSegmentStartMs(
            monotonicStartMs,
            wallClockStartMs,
            monotonicNowMs,
            wallClockNowMs,
            maximumRecoveryMs
        )
    ).coerceAtLeast(0)

    /**
     * The monotonic clock restarts at zero on every boot. A reboot is detected when
     * the monotonic clock went backwards, or when the device has been up for less
     * time than has passed on the wall clock since the segment started (the device
     * must have booted after the segment began).
     */
    fun elapsedSinceSegmentStartMs(
        monotonicStartMs: Long,
        wallClockStartMs: Long,
        monotonicNowMs: Long,
        wallClockNowMs: Long,
        maximumRecoveryMs: Long = MAXIMUM_RECOVERY_MS
    ): Long {
        val wallElapsed = wallClockNowMs - wallClockStartMs
        val rebooted = monotonicNowMs < monotonicStartMs ||
            monotonicNowMs + REBOOT_DETECTION_TOLERANCE_MS < wallElapsed
        return if (rebooted) {
            wallElapsed.coerceIn(0, maximumRecoveryMs)
        } else {
            monotonicNowMs - monotonicStartMs
        }
    }

    fun participationTimeMs(
        closedIntervalsMs: Long,
        openIntervalStartMs: Long?,
        currentMatchTimeMs: Long
    ): Long {
        val openDuration = openIntervalStartMs
            ?.let { (currentMatchTimeMs - it).coerceAtLeast(0) }
            ?: 0
        return (closedIntervalsMs + openDuration).coerceAtLeast(0)
    }

    fun periodElapsedMs(
        currentMatchTimeMs: Long,
        periodStartMatchTimeMs: Long?
    ): Long = (currentMatchTimeMs - (periodStartMatchTimeMs ?: currentMatchTimeMs))
        .coerceAtLeast(0)

    fun displayedWholeMinutes(milliseconds: Long): Long =
        milliseconds.coerceAtLeast(0) / 60_000

    fun formatClock(milliseconds: Long): String {
        val totalSeconds = milliseconds.coerceAtLeast(0) / 1_000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }
}
