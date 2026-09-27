package be.matchreview.app

import be.matchreview.app.domain.StorageHealthRules
import be.matchreview.app.domain.StorageLevel
import org.junit.Assert.*
import org.junit.Test

class StorageHealthRulesTest {
    @Test fun criticalStorageBlocksRecording() {
        val health = StorageHealthRules.evaluate(100_000_000)
        assertEquals(StorageLevel.CRITICAL, health.level)
        assertFalse(health.canStartRecording)
    }

    @Test fun warningStorageStillAllowsRecording() {
        val health = StorageHealthRules.evaluate(500_000_000)
        assertEquals(StorageLevel.WARNING, health.level)
        assertTrue(health.canStartRecording)
    }

    @Test fun healthyStorageAllowsRecording() {
        assertEquals(StorageLevel.OK, StorageHealthRules.evaluate(2_000_000_000).level)
    }
}
