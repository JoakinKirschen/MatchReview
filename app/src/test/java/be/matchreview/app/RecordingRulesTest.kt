package be.matchreview.app

import be.matchreview.app.data.RecordingStatus
import be.matchreview.app.domain.RecordingRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRulesTest {
    @Test fun activeSegmentsBlockAnotherRecording() {
        assertFalse(RecordingRules.canStart(RecordingStatus.PREPARING))
        assertFalse(RecordingRules.canStart(RecordingStatus.RECORDING))
        assertTrue(RecordingRules.canStart(RecordingStatus.COMPLETED))
        assertTrue(RecordingRules.canStart(null))
    }

    @Test fun interruptionAndUserFailureAreDistinguished() {
        assertEquals(RecordingStatus.COMPLETED, RecordingRules.terminalStatus(true, false))
        assertEquals(RecordingStatus.FAILED, RecordingRules.terminalStatus(false, true))
        assertEquals(RecordingStatus.INTERRUPTED, RecordingRules.terminalStatus(false, false))
    }

    @Test fun requestedClockEndCannotPrecedeStart() {
        assertEquals(5_000L, RecordingRules.endMatchClockMs(5_000L, 4_000L, 2_000L))
        assertEquals(7_000L, RecordingRules.endMatchClockMs(5_000L, null, 2_000L))
    }

    @Test fun playableFileIsKeptDespiteFinalizeError() {
        assertTrue(RecordingRules.keepsRecordedFile(true, false, true, 0L))
        assertTrue(RecordingRules.keepsRecordedFile(false, true, true, 4_000_000_000L))
    }

    @Test fun unusableOrMissingFileIsNotKept() {
        assertFalse(RecordingRules.keepsRecordedFile(false, false, true, 5_000L))
        assertFalse(RecordingRules.keepsRecordedFile(false, true, true, 0L))
        assertFalse(RecordingRules.keepsRecordedFile(true, false, false, 5_000L))
    }
}
