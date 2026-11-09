package be.matchreview.app

import be.matchreview.app.data.MatchEvent
import be.matchreview.app.data.MatchPeriod
import be.matchreview.app.data.RecordingSegment
import be.matchreview.app.data.RecordingStatus
import be.matchreview.app.domain.MatchVideoPosition
import be.matchreview.app.domain.MatchVideoRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MatchVideoRulesTest {
    private fun segment(id: Long, start: Long, duration: Long, status: RecordingStatus = RecordingStatus.COMPLETED, uri: String? = "content://video/$id") =
        RecordingSegment(
            id = id, matchId = 1, uri = uri, status = status,
            matchClockStartMs = start, matchClockEndMs = start + duration, recordingDurationMs = duration
        )

    private val periods = listOf(
        MatchPeriod(id = 1, matchId = 1, periodNumber = 1, plannedDurationMs = 900_000, startMatchTimeMs = 0, endMatchTimeMs = 900_000),
        MatchPeriod(id = 2, matchId = 1, periodNumber = 2, plannedDurationMs = 900_000, startMatchTimeMs = 900_000)
    )

    @Test
    fun `saved clips play in match order and failed ones are left out`() {
        val clips = MatchVideoRules.clips(
            listOf(
                segment(3, 900_000, 600_000),
                segment(1, 0, 400_000),
                segment(2, 400_000, 500_000),
                segment(4, 950_000, 0, status = RecordingStatus.FAILED),
                segment(5, 960_000, 10_000, uri = null)
            ),
            periods
        )
        assertEquals(listOf(1L, 2L, 3L), clips.map { it.segment.id })
        assertEquals(listOf(0, 1, 2), clips.map { it.index })
        assertEquals(listOf(1, 1, 2), clips.map { it.periodNumber })
        assertEquals(1_500_000L, MatchVideoRules.totalDurationMs(clips))
    }

    @Test
    fun `clip labels name the period and the part when a period has several clips`() {
        val clips = MatchVideoRules.clips(
            listOf(segment(1, 0, 400_000), segment(2, 400_000, 500_000), segment(3, 900_000, 600_000)),
            periods
        )
        assertEquals("Period 1 • part 1", MatchVideoRules.clipLabel(clips[0], clips))
        assertEquals("Period 1 • part 2", MatchVideoRules.clipLabel(clips[1], clips))
        assertEquals("Period 2", MatchVideoRules.clipLabel(clips[2], clips))
    }

    @Test
    fun `clip started a moment before the period clock still belongs to that period`() {
        val clips = MatchVideoRules.clips(listOf(segment(1, 897_000, 600_000)), periods)
        assertEquals(2, clips.single().periodNumber)
    }

    @Test
    fun `clips without periods are numbered`() {
        val clips = MatchVideoRules.clips(listOf(segment(1, 0, 1_000)), emptyList())
        assertNull(clips.single().periodNumber)
        assertEquals("Clip 1", MatchVideoRules.clipLabel(clips.single(), clips))
    }

    @Test
    fun `events jump into their clip a few seconds early`() {
        val clips = MatchVideoRules.clips(listOf(segment(1, 0, 400_000), segment(3, 900_000, 600_000)), periods)
        val goal = MatchEvent(id = 9, matchId = 1, timestampMs = 960_000, type = "OUR_GOAL", recordingSegmentId = 3, recordingOffsetMs = 60_000)
        val early = MatchEvent(id = 10, matchId = 1, timestampMs = 2_000, type = "OUR_GOAL", recordingSegmentId = 1, recordingOffsetMs = 2_000)
        assertEquals(MatchVideoPosition(1, 55_000L), MatchVideoRules.positionOf(goal, clips))
        assertEquals(MatchVideoPosition(0, 0L), MatchVideoRules.positionOf(early, clips))
        assertEquals(listOf(10L, 9L), MatchVideoRules.chapterEvents(listOf(goal, early), clips).map { it.id })
    }

    @Test
    fun `events outside the saved clips cannot be jumped to`() {
        val clips = MatchVideoRules.clips(listOf(segment(1, 0, 400_000)), periods)
        val unlinked = MatchEvent(id = 9, matchId = 1, timestampMs = 500_000, type = "OUR_GOAL")
        val otherClip = unlinked.copy(id = 10, recordingSegmentId = 8, recordingOffsetMs = 1_000)
        assertNull(MatchVideoRules.positionOf(unlinked, clips))
        assertNull(MatchVideoRules.positionOf(otherClip, clips))
        assertEquals(emptyList<MatchEvent>(), MatchVideoRules.chapterEvents(listOf(unlinked, otherClip), clips))
    }

    @Test
    fun `match clock follows the video position`() {
        val clip = MatchVideoRules.clips(listOf(segment(3, 900_000, 600_000)), periods).single()
        assertEquals(930_000L, MatchVideoRules.matchClockAt(clip, 30_000))
    }
}
