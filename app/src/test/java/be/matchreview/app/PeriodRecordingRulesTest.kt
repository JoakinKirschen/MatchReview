package be.matchreview.app

import be.matchreview.app.data.MatchStatus
import be.matchreview.app.domain.PeriodRecordingRules
import be.matchreview.app.domain.PeriodResume
import be.matchreview.app.domain.RecordingStopMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodRecordingRulesTest {
    private val resume = PeriodResume(matchId = 4, audioEnabled = true, frontLens = false)

    @Test
    fun `ending a period stops only the recording of that match`() {
        assertTrue(PeriodRecordingRules.stopsAtPeriodEnd(enabled = true, recordingMatchId = 4, matchId = 4))
        assertFalse(PeriodRecordingRules.stopsAtPeriodEnd(enabled = true, recordingMatchId = 5, matchId = 4))
        assertFalse(PeriodRecordingRules.stopsAtPeriodEnd(enabled = true, recordingMatchId = null, matchId = 4))
        assertFalse(PeriodRecordingRules.stopsAtPeriodEnd(enabled = false, recordingMatchId = 4, matchId = 4))
    }

    @Test
    fun `next period restarts a recording stopped at the period end`() {
        assertTrue(PeriodRecordingRules.resumesAtPeriodStart(true, resume, 4, MatchStatus.LIVE))
    }

    @Test
    fun `no restart without a stopped recording, for another match, when off, or when the period did not start`() {
        assertFalse(PeriodRecordingRules.resumesAtPeriodStart(true, null, 4, MatchStatus.LIVE))
        assertFalse(PeriodRecordingRules.resumesAtPeriodStart(true, resume, 5, MatchStatus.LIVE))
        assertFalse(PeriodRecordingRules.resumesAtPeriodStart(false, resume, 4, MatchStatus.LIVE))
        assertFalse(PeriodRecordingRules.resumesAtPeriodStart(true, resume, 4, MatchStatus.PERIOD_ENDED))
    }

    @Test
    fun `restarted clip starts at the match time when the camera is free again`() {
        assertEquals(
            1_202_500L,
            PeriodRecordingRules.restartMatchClockMs(
                periodStartMatchMs = 1_200_000L,
                periodStartedMonotonicMs = 50_000L,
                nowMonotonicMs = 52_500L
            )
        )
        assertEquals(1_200_000L, PeriodRecordingRules.restartMatchClockMs(1_200_000L, 50_000L, 49_000L))
    }

    @Test
    fun `pending restart survives being stored`() {
        assertEquals(resume, PeriodRecordingRules.decode(PeriodRecordingRules.encode(resume)))
        val front = resume.copy(audioEnabled = false, frontLens = true)
        assertEquals(front, PeriodRecordingRules.decode(PeriodRecordingRules.encode(front)))
    }

    @Test
    fun `damaged stored values are ignored`() {
        assertNull(PeriodRecordingRules.decode(null))
        assertNull(PeriodRecordingRules.decode(""))
        assertNull(PeriodRecordingRules.decode("x|1|BACK"))
        assertNull(PeriodRecordingRules.decode("0|1|BACK"))
        assertNull(PeriodRecordingRules.decode("4|1"))
    }

    @Test
    fun `planned time stops only when chosen and only that match`() {
        assertTrue(PeriodRecordingRules.stopsAtPlannedTime(RecordingStopMode.PLANNED_TIME, 4, 4))
        assertFalse(PeriodRecordingRules.stopsAtPlannedTime(RecordingStopMode.PERIOD_END, 4, 4))
        assertFalse(PeriodRecordingRules.stopsAtPlannedTime(RecordingStopMode.MANUAL, 4, 4))
        assertFalse(PeriodRecordingRules.stopsAtPlannedTime(RecordingStopMode.PLANNED_TIME, 5, 4))
        assertFalse(PeriodRecordingRules.stopsAtPlannedTime(RecordingStopMode.PLANNED_TIME, null, 4))
    }

    @Test
    fun `planned end is the period start plus its planned length`() {
        // 32 minutes played, 2 of them in this 15-minute period that started at 30:00.
        assertEquals(2_700_000L, PeriodRecordingRules.plannedEndMatchMs(1_920_000L, 120_000L, 900_000L))
    }

    @Test
    fun `stop modes restart with the next period except manual`() {
        assertTrue(RecordingStopMode.PERIOD_END.followsPeriods)
        assertTrue(RecordingStopMode.PLANNED_TIME.followsPeriods)
        assertFalse(RecordingStopMode.MANUAL.followsPeriods)
        assertEquals(RecordingStopMode.PERIOD_END, RecordingStopMode.fromName(null))
        assertEquals(RecordingStopMode.PLANNED_TIME, RecordingStopMode.fromName("PLANNED_TIME"))
    }
}
