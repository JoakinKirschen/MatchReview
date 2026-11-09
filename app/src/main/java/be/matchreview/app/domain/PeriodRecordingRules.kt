package be.matchreview.app.domain

import be.matchreview.app.data.MatchStatus

/** When the camera stops filming by itself. */
enum class RecordingStopMode(val label: String, val shortLabel: String) {
    /** Only the coach stops the recording. */
    MANUAL("Only when I stop it", "manual"),
    /** When the coach ends the period; recording restarts with the next period. */
    PERIOD_END("When I end the period", "at period end"),
    /** When the planned period time is up, even if play goes on; restarts with the next period. */
    PLANNED_TIME("When the planned time is up", "when time is up");

    /** Whether the recording restarts by itself when the next period starts. */
    val followsPeriods: Boolean get() = this != MANUAL

    companion object {
        fun fromName(name: String?): RecordingStopMode = entries.firstOrNull { it.name == name } ?: PERIOD_END
    }
}

/** Settings of a recording that was stopped at a period end and restarts with the next period. */
data class PeriodResume(
    val matchId: Long,
    val audioEnabled: Boolean,
    val frontLens: Boolean
)

/**
 * The camera follows the match periods: the recording is saved when the coach ends a
 * period and starts again, with the same sound and camera, when the next period starts.
 * The break between periods is not filmed, so every period becomes its own clip.
 */
object PeriodRecordingRules {
    /** Whether ending a period of [matchId] stops and saves the running recording. */
    fun stopsAtPeriodEnd(enabled: Boolean, recordingMatchId: Long?, matchId: Long): Boolean =
        enabled && recordingMatchId == matchId

    /** Whether reaching the planned period time of [matchId] stops the running recording. */
    fun stopsAtPlannedTime(mode: RecordingStopMode, recordingMatchId: Long?, matchId: Long): Boolean =
        mode == RecordingStopMode.PLANNED_TIME && recordingMatchId == matchId

    /** Match time at which the planned period time is reached. */
    fun plannedEndMatchMs(matchTimeMs: Long, periodTimeMs: Long, plannedPeriodMs: Long): Long =
        (matchTimeMs - periodTimeMs + plannedPeriodMs).coerceAtLeast(0L)

    /** Whether starting a period restarts a recording that was stopped at the previous period end. */
    fun resumesAtPeriodStart(
        enabled: Boolean,
        pending: PeriodResume?,
        matchId: Long,
        status: MatchStatus?
    ): Boolean = enabled && pending?.matchId == matchId && status == MatchStatus.LIVE

    /**
     * Match time when the restarted recording begins. The camera may still be saving the
     * previous clip for a moment, during which the new period's clock already runs.
     */
    fun restartMatchClockMs(
        periodStartMatchMs: Long,
        periodStartedMonotonicMs: Long,
        nowMonotonicMs: Long
    ): Long = periodStartMatchMs + (nowMonotonicMs - periodStartedMonotonicMs).coerceAtLeast(0L)

    fun encode(resume: PeriodResume): String =
        "${resume.matchId}|${if (resume.audioEnabled) 1 else 0}|${if (resume.frontLens) "FRONT" else "BACK"}"

    fun decode(value: String?): PeriodResume? {
        val parts = value?.split('|') ?: return null
        if (parts.size != 3) return null
        val matchId = parts[0].toLongOrNull()?.takeIf { it > 0L } ?: return null
        return PeriodResume(
            matchId = matchId,
            audioEnabled = parts[1] == "1",
            frontLens = parts[2] == "FRONT"
        )
    }
}
