package be.matchreview.app.domain

/** Slow motion, frame stepping and zoom for studying the match video. */
object VideoViewRules {
    /** Playback speeds offered, slowest first. */
    val SPEEDS = listOf(0.25f, 0.5f, 1f)

    /** One frame at 30 frames per second, the rate the camera records at. */
    const val FRAME_MS = 33L

    const val MAX_ZOOM = 4f

    fun nextSpeed(current: Float): Float =
        SPEEDS.firstOrNull { it > current + 0.001f } ?: SPEEDS.first()

    fun speedLabel(speed: Float): String = when (speed) {
        0.25f -> "¼×"
        0.5f -> "½×"
        else -> "1×"
    }

    /** The position one frame forward ([forward]) or back, kept inside the clip. */
    fun stepFrame(positionMs: Long, durationMs: Long, forward: Boolean): Long {
        val target = positionMs + if (forward) FRAME_MS else -FRAME_MS
        val end = if (durationMs > 0L) durationMs else Long.MAX_VALUE
        return target.coerceIn(0L, end)
    }

    fun clampZoom(scale: Float): Float = scale.coerceIn(1f, MAX_ZOOM)

    /**
     * Keeps a zoomed picture covering the frame: it may move at most half of the extra
     * size in each direction, and not at all when not zoomed.
     */
    fun clampPan(offset: Float, scale: Float, sizePx: Float): Float {
        val limit = (scale - 1f) * sizePx / 2f
        return offset.coerceIn(-limit, limit)
    }
}
