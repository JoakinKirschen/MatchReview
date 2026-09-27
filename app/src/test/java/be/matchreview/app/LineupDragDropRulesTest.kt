package be.matchreview.app

import be.matchreview.app.domain.FormationSlot
import be.matchreview.app.domain.LineupDragDropRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LineupDragDropRulesTest {
    private val slots = listOf(
        FormationSlot("GK", "GK", 0.5f, 0.9f),
        FormationSlot("DEF-1", "DEF", 0.25f, 0.7f)
    )

    @Test
    fun `drop near free slot snaps to slot`() {
        val result = LineupDragDropRules.resolvePitchDrop(
            normalizedX = 0.27f,
            normalizedY = 0.68f,
            slots = slots,
            occupiedSlotIds = emptySet()
        )

        assertTrue(result.snapped)
        assertEquals("DEF-1", result.formationSlot)
        assertEquals("DEF", result.role)
        assertEquals(0.25f, result.normalizedX, 0.0001f)
        assertEquals(0.7f, result.normalizedY, 0.0001f)
    }

    @Test
    fun `occupied slot is ignored`() {
        val result = LineupDragDropRules.resolvePitchDrop(
            normalizedX = 0.25f,
            normalizedY = 0.7f,
            slots = slots,
            occupiedSlotIds = setOf("DEF-1")
        )

        assertFalse(result.snapped)
        assertEquals("", result.formationSlot)
    }

    @Test
    fun `free drop is constrained inside marker-safe pitch inset`() {
        val result = LineupDragDropRules.resolvePitchDrop(
            normalizedX = -1f,
            normalizedY = 2f,
            slots = emptyList(),
            occupiedSlotIds = emptySet()
        )

        assertEquals(0.08f, result.normalizedX, 0.0001f)
        assertEquals(0.92f, result.normalizedY, 0.0001f)
    }

    @Test
    fun `root coordinates are normalized relative to pitch`() {
        val result = LineupDragDropRules.normalize(
            rootX = 150f,
            rootY = 250f,
            pitchLeft = 100f,
            pitchTop = 200f,
            pitchWidth = 200f,
            pitchHeight = 100f
        )

        assertEquals(0.25f, result.first, 0.0001f)
        assertEquals(0.5f, result.second, 0.0001f)
    }
}
