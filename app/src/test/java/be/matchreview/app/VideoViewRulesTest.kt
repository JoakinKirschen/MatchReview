package be.matchreview.app

import be.matchreview.app.domain.VideoViewRules
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoViewRulesTest {
    @Test
    fun `speed cycles from normal to quarter and up again`() {
        assertEquals(0.25f, VideoViewRules.nextSpeed(1f))
        assertEquals(0.5f, VideoViewRules.nextSpeed(0.25f))
        assertEquals(1f, VideoViewRules.nextSpeed(0.5f))
        assertEquals("½×", VideoViewRules.speedLabel(0.5f))
    }

    @Test
    fun `frame steps stay inside the clip`() {
        assertEquals(1_033L, VideoViewRules.stepFrame(1_000L, 10_000L, forward = true))
        assertEquals(967L, VideoViewRules.stepFrame(1_000L, 10_000L, forward = false))
        assertEquals(0L, VideoViewRules.stepFrame(10L, 10_000L, forward = false))
        assertEquals(10_000L, VideoViewRules.stepFrame(9_990L, 10_000L, forward = true))
        assertEquals(1_033L, VideoViewRules.stepFrame(1_000L, -1L, forward = true))
    }

    @Test
    fun `zoom stays between normal and four times`() {
        assertEquals(1f, VideoViewRules.clampZoom(0.5f))
        assertEquals(4f, VideoViewRules.clampZoom(9f))
    }

    @Test
    fun `zoomed picture cannot be dragged off the frame`() {
        assertEquals(0f, VideoViewRules.clampPan(120f, scale = 1f, sizePx = 1000f))
        assertEquals(500f, VideoViewRules.clampPan(900f, scale = 2f, sizePx = 1000f))
        assertEquals(-500f, VideoViewRules.clampPan(-900f, scale = 2f, sizePx = 1000f))
        assertEquals(200f, VideoViewRules.clampPan(200f, scale = 2f, sizePx = 1000f))
    }
}
