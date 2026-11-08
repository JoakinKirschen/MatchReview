package be.matchreview.app.domain

import be.matchreview.app.data.MatchEvent
import be.matchreview.app.data.MatchPeriod
import be.matchreview.app.data.RecordingSegment
import be.matchreview.app.data.RecordingStatus

/** One saved clip of the match video, in playing order. */
data class MatchVideoClip(
    val segment: RecordingSegment,
    /** Position in the playlist, from 0. */
    val index: Int,
    /** The match period the clip was filmed in, when known. */
    val periodNumber: Int?
)

/** Where to start playing to show an event, with a few seconds of build-up. */
data class MatchVideoPosition(val clipIndex: Int, val offsetMs: Long)

/**
 * The whole recorded match: every saved clip, played back to back in match order, so
 * the coach can watch the complete game instead of one event at a time.
 */
object MatchVideoRules {
    /** Seconds of play shown before an event when jumping to it. */
    const val LEAD_IN_MS = 5_000L

    /** Tolerance for a clip that started just before its period's clock did. */
    private const val PERIOD_TOLERANCE_MS = 5_000L

    fun clips(recordings: List<RecordingSegment>, periods: List<MatchPeriod>): List<MatchVideoClip> =
        recordings
            .filter { it.status == RecordingStatus.COMPLETED && !it.uri.isNullOrBlank() }
            .sortedWith(compareBy<RecordingSegment>({ it.matchClockStartMs }, { it.startedAtEpochMs ?: Long.MAX_VALUE }, { it.id }))
            .mapIndexed { index, segment -> MatchVideoClip(segment, index, periodOf(segment, periods)) }

    fun totalDurationMs(clips: List<MatchVideoClip>): Long = clips.sumOf { it.segment.recordingDurationMs }

    /**
     * The period whose match time contains the start of [segment]. A clip started before
     * kick-off belongs to the first period.
     */
    fun periodOf(segment: RecordingSegment, periods: List<MatchPeriod>): Int? =
        periods
            .filter { it.startMatchTimeMs != null }
            .sortedBy { it.periodNumber }
            .lastOrNull { (it.startMatchTimeMs ?: 0L) <= segment.matchClockStartMs + PERIOD_TOLERANCE_MS }
            ?.periodNumber
            ?: periods.minByOrNull { it.periodNumber }?.takeIf { it.startMatchTimeMs != null }?.periodNumber

    /** Match time shown at [positionMs] into [clip]. */
    fun matchClockAt(clip: MatchVideoClip, positionMs: Long): Long =
        clip.segment.matchClockStartMs + positionMs.coerceAtLeast(0L)

    /** Where to play [event] from, or null when it is not in any saved clip. */
    fun positionOf(event: MatchEvent, clips: List<MatchVideoClip>): MatchVideoPosition? {
        val offset = event.recordingOffsetMs ?: return null
        val clip = clips.firstOrNull { it.segment.id == event.recordingSegmentId } ?: return null
        return MatchVideoPosition(clip.index, (offset - LEAD_IN_MS).coerceAtLeast(0L))
    }

    /** Events that can be jumped to in the match video, in the order they appear in it. */
    fun chapterEvents(events: List<MatchEvent>, clips: List<MatchVideoClip>): List<MatchEvent> {
        val order = clips.associate { it.segment.id to it.index }
        return events
            .filter { it.recordingOffsetMs != null && it.recordingSegmentId in order }
            .sortedWith(
                compareBy<MatchEvent>({ order[it.recordingSegmentId] }, { it.recordingOffsetMs }, { it.id })
            )
    }

    fun clipLabel(clip: MatchVideoClip, clips: List<MatchVideoClip>): String {
        val period = clip.periodNumber ?: return "Clip ${clip.index + 1}"
        val inPeriod = clips.filter { it.periodNumber == period }
        return if (inPeriod.size <= 1) "Period $period"
        else "Period $period • part ${inPeriod.indexOf(clip) + 1}"
    }
}
