package be.matchreview.app.domain

import be.matchreview.app.data.MatchEvent
import be.matchreview.app.data.RecordingSegment

/** A part of one recorded clip that goes into the highlights video. */
data class HighlightClip(
    val recordingId: Long,
    val uri: String,
    val startMs: Long,
    val endMs: Long,
    val eventIds: List<Long>
) {
    val durationMs: Long get() = endMs - startMs
}

object HighlightRules {
    /** The build-up matters more than the celebration. */
    const val BEFORE_MS = 8_000L
    const val AFTER_MS = 4_000L

    val OUR_GOALS = setOf("OUR_GOAL")
    val ALL_GOALS = setOf("OUR_GOAL", "OPPONENT_GOAL")
    val MARKS = setOf(MatchActions.MARK)

    /** What a player did that is worth seeing again. */
    private val PLAYER_MOMENTS = setOf(
        "OUR_GOAL", "KEEPER_SAVE", MatchActions.OUR_SHOT_ON_TARGET, MatchActions.OUR_SHOT_OFF_TARGET
    )

    /** Longest clip handled when a recording's length is unknown: a whole day. */
    private const val UNKNOWN_LENGTH_MS = 24 * 60 * 60 * 1000L

    /** The goals, assists, saves and shots of [playerId]. */
    fun isPlayerMoment(event: MatchEvent, playerId: Long): Boolean =
        (event.type in PLAYER_MOMENTS && event.playerId == playerId) ||
            (event.type == "OUR_GOAL" && event.relatedPlayerId == playerId)

    fun playerClips(
        events: List<MatchEvent>,
        recordings: List<RecordingSegment>,
        playerId: Long
    ): List<HighlightClip> = clips(events, recordings) { isPlayerMoment(it, playerId) }

    /** Players with at least one recorded moment, and how many. */
    fun playersWithMoments(events: List<MatchEvent>, recordings: List<RecordingSegment>): Map<Long, Int> {
        val ids = events
            .filter { VideoEventRules.isPlayable(it, recordings) }
            .flatMap { listOfNotNull(it.playerId, it.relatedPlayerId) }
            .toSet()
        return ids.associateWith { id ->
            events.count { VideoEventRules.isPlayable(it, recordings) && isPlayerMoment(it, id) }
        }.filterValues { it > 0 }
    }

    /** Every saved clip from start to end, in match order: the whole match as one video. */
    fun fullMatch(clips: List<MatchVideoClip>): List<HighlightClip> = clips.map { clip ->
        HighlightClip(
            recordingId = clip.segment.id,
            uri = clip.segment.uri.orEmpty(),
            startMs = 0L,
            endMs = clip.segment.recordingDurationMs.takeIf { it > 0L } ?: UNKNOWN_LENGTH_MS,
            eventIds = emptyList()
        )
    }

    /**
     * One window around each recorded event of [types], in match order. Windows that touch
     * in the same recording are merged so no moment is shown twice.
     */
    fun clips(
        events: List<MatchEvent>,
        recordings: List<RecordingSegment>,
        types: Set<String> = ALL_GOALS
    ): List<HighlightClip> = clips(events, recordings) { it.type in types }

    /** One window around each recorded event that [include] accepts; see [clips]. */
    fun clips(
        events: List<MatchEvent>,
        recordings: List<RecordingSegment>,
        include: (MatchEvent) -> Boolean
    ): List<HighlightClip> {
        val byId = recordings.associateBy { it.id }
        val windows = events
            .filter { include(it) && VideoEventRules.isPlayable(it, recordings) }
            .sortedWith(compareBy<MatchEvent> { it.timestampMs }.thenBy { it.id })
            .mapNotNull { event ->
                val recording = byId[event.recordingSegmentId] ?: return@mapNotNull null
                val offset = event.recordingOffsetMs ?: return@mapNotNull null
                val length = recording.recordingDurationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
                HighlightClip(
                    recordingId = recording.id,
                    uri = recording.uri ?: return@mapNotNull null,
                    startMs = (offset - BEFORE_MS).coerceAtLeast(0L),
                    endMs = (offset + AFTER_MS).coerceAtMost(length),
                    eventIds = listOf(event.id)
                )
            }
            .filter { it.endMs > it.startMs }
        return windows.fold(mutableListOf()) { merged, clip ->
            val last = merged.lastOrNull()
            if (last != null && last.recordingId == clip.recordingId && clip.startMs <= last.endMs) {
                merged[merged.lastIndex] = last.copy(
                    endMs = maxOf(last.endMs, clip.endMs),
                    eventIds = last.eventIds + clip.eventIds
                )
            } else {
                merged += clip
            }
            merged
        }
    }
}
