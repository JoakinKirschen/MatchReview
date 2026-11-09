package be.matchreview.app

import be.matchreview.app.domain.PitchOrientation
import org.junit.Assert.assertEquals
import org.junit.Test

class PitchOrientationTest {
    @Test
    fun `upright pitch shows positions unchanged`() {
        assertEquals(0.3f to 0.8f, PitchOrientation.toDisplay(0.3f, 0.8f, horizontal = false))
        assertEquals(0.3f to 0.8f, PitchOrientation.toLineup(0.3f, 0.8f, horizontal = false))
    }

    @Test
    fun `sideways pitch puts our goalkeeper on the left and the left back at the top`() {
        // Goalkeeper near our own goal line (bottom of the upright pitch).
        val (keeperX, keeperY) = PitchOrientation.toDisplay(0.5f, 0.9f, horizontal = true)
        assertEquals(0.1f, keeperX, 0.0001f)
        assertEquals(0.5f, keeperY, 0.0001f)
        // Attacking to the right, the left touchline is at the top.
        val (_, leftBackY) = PitchOrientation.toDisplay(0.1f, 0.7f, horizontal = true)
        assertEquals(0.1f, leftBackY, 0.0001f)
    }

    @Test
    fun `sideways display and lineup positions convert back and forth`() {
        val (x, y) = PitchOrientation.toDisplay(0.25f, 0.65f, horizontal = true)
        val (backX, backY) = PitchOrientation.toLineup(x, y, horizontal = true)
        assertEquals(0.25f, backX, 0.0001f)
        assertEquals(0.65f, backY, 0.0001f)
    }

    @Test
    fun `pointer on a sideways pitch is converted to a lineup position`() {
        // A 400 x 200 pitch at (100, 50); the pointer is a quarter across and three quarters down.
        val (x, y) = PitchOrientation.normalizePointer(
            rootX = 200f, rootY = 200f, pitchLeft = 100f, pitchTop = 50f,
            pitchWidth = 400f, pitchHeight = 200f, horizontal = true
        )
        assertEquals(0.75f, x, 0.0001f)
        assertEquals(0.75f, y, 0.0001f)
    }

    @Test
    fun `distance axes swap for a sideways pitch`() {
        assertEquals(200f to 400f, PitchOrientation.lineupAxes(400f, 200f, horizontal = true))
        assertEquals(400f to 200f, PitchOrientation.lineupAxes(400f, 200f, horizontal = false))
    }
}
