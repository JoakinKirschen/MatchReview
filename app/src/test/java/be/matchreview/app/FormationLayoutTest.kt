package be.matchreview.app

import be.matchreview.app.domain.FormationLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormationLayoutTest {
    @Test
    fun elevenPlayer433CreatesGoalkeeperAndTenOutfieldSlots() {
        val slots = FormationLayout.slots("4-3-3", 11)
        assertEquals(11, slots.size)
        assertEquals("GK", slots.first().label)
        assertEquals(4, slots.count { it.label == "DEF" })
        assertEquals(3, slots.count { it.label == "MID" })
        assertEquals(3, slots.count { it.label == "FWD" })
    }


    @Test
    fun eightPlayerTwoFourOneCreatesCorrectRows() {
        val slots = FormationLayout.slots("2-4-1", 8)
        assertEquals(8, slots.size)
        assertEquals(1, slots.count { it.label == "GK" })
        assertEquals(2, slots.count { it.label == "DEF" })
        assertEquals(4, slots.count { it.label == "MID" })
        assertEquals(1, slots.count { it.label == "FWD" })
    }

    @Test
    fun mismatchedFormationFallsBackToRequestedPlayerCount() {
        val slots = FormationLayout.slots("4-3-3", 7)
        assertEquals(7, slots.size)
        assertTrue(slots.all { it.normalizedX in 0f..1f && it.normalizedY in 0f..1f })
    }
}
