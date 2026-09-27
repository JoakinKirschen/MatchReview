package be.matchreview.app

import be.matchreview.app.data.MatchEvent
import be.matchreview.app.data.RecordingSegment
import be.matchreview.app.data.RecordingStatus
import be.matchreview.app.domain.VideoEventRules
import org.junit.Assert.*
import org.junit.Test

class VideoEventRulesTest {
    private val segment = RecordingSegment(
        id = 7,
        matchId = 3,
        uri = "content://video/7",
        status = RecordingStatus.COMPLETED,
        matchClockStartMs = 10_000,
        startedAtEpochMs = 1_000_000,
        matchClockEndMs = 40_000,
        recordingDurationMs = 30_000
    )

    @Test
    fun wallClockMappingWinsAndProducesExactOffset() {
        val link = VideoEventRules.link(
            eventTimestampMs = 17_000,
            eventOccurredAtEpochMs = 1_012_500,
            recordings = listOf(segment)
        )
        assertEquals(7L, link?.recordingSegmentId)
        assertEquals(12_500L, link?.recordingOffsetMs)
    }

    @Test
    fun logicalClockBackfillsOlderRecordings() {
        val legacy = segment.copy(startedAtEpochMs = null)
        val link = VideoEventRules.link(25_000, null, listOf(legacy))
        assertEquals(15_000L, link?.recordingOffsetMs)
    }

    @Test
    fun eventOutsideAllSegmentsIsNotLinked() {
        assertNull(VideoEventRules.link(80_000, null, listOf(segment)))
    }

    @Test
    fun completedLinkedEventIsPlayable() {
        val event = MatchEvent(
            id = 4,
            matchId = 3,
            timestampMs = 20_000,
            type = "OUR_GOAL",
            recordingSegmentId = 7,
            recordingOffsetMs = 10_000
        )
        assertTrue(VideoEventRules.isPlayable(event, listOf(segment)))
    }

    @Test
    fun importedVideoTagsAreDistinguishedFromLiveEvents() {
        val tag = MatchEvent(matchId = 3, timestampMs = 90_000, type = "Chance")
        val live = tag.copy(periodNumber = 1, occurredAtEpochMs = 1_000_000)
        val correction = tag.copy(occurredAtEpochMs = 1_000_000)
        assertTrue(VideoEventRules.isImportedVideoTag(tag))
        assertFalse(VideoEventRules.isImportedVideoTag(live))
        assertFalse(VideoEventRules.isImportedVideoTag(correction))
    }
}
