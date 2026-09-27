package be.matchreview.app.domain

import be.matchreview.app.data.MatchEvent
import be.matchreview.app.data.RecordingSegment
import be.matchreview.app.data.RecordingStatus
import kotlin.math.abs

data class VideoEventLink(
    val recordingSegmentId: Long,
    val recordingOffsetMs: Long
)

object VideoEventRules {
    private const val CLOCK_TOLERANCE_MS = 2_000L

    fun link(
        eventTimestampMs: Long,
        eventOccurredAtEpochMs: Long?,
        recordings: List<RecordingSegment>
    ): VideoEventLink? {
        val usable = recordings.filter {
            it.status in setOf(
                RecordingStatus.RECORDING,
                RecordingStatus.COMPLETED
            )
        }

        val wallClockMatch = eventOccurredAtEpochMs?.let { eventEpoch ->
            usable
                .filter { segment ->
                    val started = segment.startedAtEpochMs ?: return@filter false
                    val duration = effectiveDuration(segment)
                    eventEpoch >= started - CLOCK_TOLERANCE_MS &&
                        (segment.status != RecordingStatus.COMPLETED ||
                            eventEpoch <= started + duration + CLOCK_TOLERANCE_MS)
                }
                .minByOrNull { segment ->
                    abs(eventEpoch - (segment.startedAtEpochMs ?: eventEpoch))
                }
        }

        val segment = wallClockMatch ?: usable
            .filter { segment ->
                val end = logicalEnd(segment)
                eventTimestampMs >= segment.matchClockStartMs - CLOCK_TOLERANCE_MS &&
                    eventTimestampMs <= end + CLOCK_TOLERANCE_MS
            }
            .minByOrNull { abs(eventTimestampMs - it.matchClockStartMs) }
            ?: return null

        val rawOffset = if (
            eventOccurredAtEpochMs != null &&
            segment.startedAtEpochMs != null
        ) {
            eventOccurredAtEpochMs - segment.startedAtEpochMs
        } else {
            eventTimestampMs - segment.matchClockStartMs
        }

        val maximum = effectiveDuration(segment)
            .takeIf { it > 0L }
            ?: Long.MAX_VALUE

        return VideoEventLink(
            recordingSegmentId = segment.id,
            recordingOffsetMs = rawOffset.coerceIn(0L, maximum)
        )
    }

    fun link(event: MatchEvent, recordings: List<RecordingSegment>): VideoEventLink? =
        link(event.timestampMs, event.occurredAtEpochMs, recordings)

    fun isPlayable(event: MatchEvent, recordings: List<RecordingSegment>): Boolean {
        val segment = recordings.firstOrNull { it.id == event.recordingSegmentId }
        return event.recordingOffsetMs != null &&
            segment?.status == RecordingStatus.COMPLETED &&
            !segment.uri.isNullOrBlank()
    }

    /**
     * Moments tagged while watching an imported video store the video position in
     * [MatchEvent.timestampMs]. They are never recorded during a live period and carry
     * no wall-clock anchor; live events always have one or a period number.
     */
    fun isImportedVideoTag(event: MatchEvent): Boolean =
        event.periodNumber == 0 && event.occurredAtEpochMs == null

    fun eventsForSegment(
        segmentId: Long,
        events: List<MatchEvent>
    ): List<MatchEvent> = events
        .filter { it.recordingSegmentId == segmentId && it.recordingOffsetMs != null }
        .sortedWith(compareBy<MatchEvent> { it.recordingOffsetMs }.thenBy { it.id })

    private fun effectiveDuration(segment: RecordingSegment): Long =
        segment.recordingDurationMs.takeIf { it > 0L }
            ?: segment.matchClockEndMs?.minus(segment.matchClockStartMs)?.coerceAtLeast(0L)
            ?: Long.MAX_VALUE

    private fun logicalEnd(segment: RecordingSegment): Long =
        segment.matchClockEndMs
            ?: if (segment.recordingDurationMs > 0L)
                segment.matchClockStartMs + segment.recordingDurationMs
            else Long.MAX_VALUE
}
