package be.matchreview.app

import be.matchreview.app.data.MatchStatus
import be.matchreview.app.domain.PeriodRecordingRules
import be.matchreview.app.domain.PeriodResume
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
}
