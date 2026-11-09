package be.matchreview.app

import be.matchreview.app.data.MatchEvent
import be.matchreview.app.data.RecordingSegment
import be.matchreview.app.data.RecordingStatus
import be.matchreview.app.domain.HighlightRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightRulesTest {
    private val clipA = RecordingSegment(id = 1, matchId = 1, uri = "content://a", status = RecordingStatus.COMPLETED,
        matchClockStartMs = 0, recordingDurationMs = 600_000)
    private val clipB = RecordingSegment(id = 2, matchId = 1, uri = "content://b", status = RecordingStatus.COMPLETED,
        matchClockStartMs = 900_000, recordingDurationMs = 300_000)

    private fun goal(id: Long, type: String, recording: Long?, offset: Long?) = MatchEvent(
        id = id, matchId = 1, timestampMs = id * 1_000, type = type,
        recordingSegmentId = recording, recordingOffsetMs = offset
    )

    @Test
    fun windowsAroundGoalsAreClampedAndMerged() {
        val events = listOf(
            goal(1, "OUR_GOAL", 1, 3_000),          // starts at the beginning of the clip
            goal(2, "OPPONENT_GOAL", 1, 10_000),    // overlaps the first window: merged
            goal(3, "OUR_GOAL", 2, 298_000),        // ends at the end of the clip
            goal(4, "OUR_GOAL", null, null),        // not recorded
            goal(5, "KEEPER_SAVE", 1, 100_000)      // not a goal
        )

        val clips = HighlightRules.clips(events, listOf(clipA, clipB))

        assertEquals(2, clips.size)
        assertEquals(0L, clips[0].startMs)
        assertEquals(14_000L, clips[0].endMs)
        assertEquals(listOf(1L, 2L), clips[0].eventIds)
        assertEquals(290_000L, clips[1].startMs)
        assertEquals(300_000L, clips[1].endMs)

        val ours = HighlightRules.clips(events, listOf(clipA, clipB), HighlightRules.OUR_GOALS)
        assertEquals(listOf(listOf(1L), listOf(3L)), ours.map { it.eventIds })
        assertTrue(HighlightRules.clips(events, emptyList()).isEmpty())
    }

    @Test
    fun playerReelHasTheirGoalsAssistsSavesAndShots() {
        val events = listOf(
            goal(1, "OUR_GOAL", 1, 60_000).copy(playerId = 7, relatedPlayerId = 8),
            goal(2, "OUR_GOAL", 1, 200_000).copy(playerId = 8, relatedPlayerId = 7),
            goal(3, "KEEPER_SAVE", 1, 300_000).copy(playerId = 9),
            goal(4, "OUR_SHOT_ON_TARGET", 2, 100_000).copy(playerId = 7),
            goal(5, "YELLOW_CARD", 2, 200_000).copy(playerId = 7)
        )
        val recordings = listOf(clipA, clipB)
        assertEquals(listOf(listOf(1L), listOf(2L), listOf(4L)), HighlightRules.playerClips(events, recordings, 7).map { it.eventIds })
        assertEquals(mapOf(7L to 3, 8L to 2, 9L to 1), HighlightRules.playersWithMoments(events, recordings))
    }

    @Test
    fun fullMatchTakesEveryClipWhole() {
        val clips = be.matchreview.app.domain.MatchVideoRules.clips(listOf(clipB, clipA), emptyList())
        val full = HighlightRules.fullMatch(clips)
        assertEquals(listOf(1L, 2L), full.map { it.recordingId })
        assertEquals(listOf(0L, 0L), full.map { it.startMs })
        assertEquals(listOf(600_000L, 300_000L), full.map { it.endMs })
    }
}
