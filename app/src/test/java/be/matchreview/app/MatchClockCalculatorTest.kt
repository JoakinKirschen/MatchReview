package be.matchreview.app

import be.matchreview.app.domain.MatchClockCalculator
import org.junit.Assert.assertEquals
import org.junit.Test

class MatchClockCalculatorTest {
    @Test fun runningClockAddsOnlyMonotonicElapsedTime() {
        assertEquals(
            75_000,
            MatchClockCalculator.currentMatchTimeMs(
                accumulatedMatchTimeMs = 60_000,
                clockRunning = true,
                monotonicStartMs = 1_000,
                monotonicNowMs = 16_000
            )
        )
    }

    @Test fun pausedClockDoesNotAdvance() {
        assertEquals(
            60_000,
            MatchClockCalculator.currentMatchTimeMs(60_000, false, 1_000, 99_000)
        )
    }

    @Test fun participationIncludesOpenInterval() {
        assertEquals(
            150_000,
            MatchClockCalculator.participationTimeMs(
                closedIntervalsMs = 90_000,
                openIntervalStartMs = 120_000,
                currentMatchTimeMs = 180_000
            )
        )
    }

    @Test fun rebootRecoveryFallsBackToWallClock() {
        assertEquals(
            90_000,
            MatchClockCalculator.recoveredCurrentMatchTimeMs(
                segmentStartMatchTimeMs = 60_000,
                monotonicStartMs = 500_000,
                wallClockStartMs = 1_000_000,
                monotonicNowMs = 2_000,
                wallClockNowMs = 1_030_000
            )
        )
    }

    @Test fun implausibleWallRecoveryIsCapped() {
        assertEquals(
            21_660_000,
            MatchClockCalculator.recoveredCurrentMatchTimeMs(
                segmentStartMatchTimeMs = 60_000,
                monotonicStartMs = 500_000,
                wallClockStartMs = 1_000_000,
                monotonicNowMs = 2_000,
                wallClockNowMs = 100_000_000
            )
        )
    }

    @Test fun rebootAfterShortUptimeIsDetectedFromWallClock() {
        // Kick-off 5 minutes after boot; the phone restarts and has now been up for
        // 10 minutes while 40 minutes passed on the wall clock.
        assertEquals(
            40 * 60_000L,
            MatchClockCalculator.recoveredCurrentMatchTimeMs(
                segmentStartMatchTimeMs = 0,
                monotonicStartMs = 5 * 60_000L,
                wallClockStartMs = 1_000_000,
                monotonicNowMs = 10 * 60_000L,
                wallClockNowMs = 1_000_000 + 40 * 60_000L
            )
        )
    }

    @Test fun sameBootIgnoresWallClockAdjustments() {
        // The wall clock was set back by an hour during the match; monotonic time wins.
        assertEquals(
            20 * 60_000L,
            MatchClockCalculator.recoveredCurrentMatchTimeMs(
                segmentStartMatchTimeMs = 0,
                monotonicStartMs = 5 * 60_000L,
                wallClockStartMs = 10_000_000,
                monotonicNowMs = 25 * 60_000L,
                wallClockNowMs = 10_000_000 + 20 * 60_000L - 60 * 60_000L
            )
        )
    }

    @Test fun periodElapsedUsesLogicalPeriodStart() {
        assertEquals(300_000, MatchClockCalculator.periodElapsedMs(1_500_000, 1_200_000))
    }

    @Test fun clockFormattingSupportsLongMatches() {
        assertEquals("102:09", MatchClockCalculator.formatClock(6_129_000))
    }
}
