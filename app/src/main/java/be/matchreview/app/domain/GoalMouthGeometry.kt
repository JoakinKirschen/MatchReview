package be.matchreview.app.domain

import be.matchreview.app.i18n.tr

/**
 * Maps between a drawn goal mouth and stored goal positions. Stored values are
 * 0..1 from the left post to the right post and from the crossbar down to the ground.
 */
object GoalMouthGeometry {
    /** Width / height of the drawing area; a full-size goal is 7.32 m by 2.44 m. */
    const val ASPECT_RATIO = 2.6f
    const val SIDE_INSET = 0.07f
    const val TOP_INSET = 0.12f
    const val GROUND = 0.92f

    data class Frame(val left: Float, val top: Float, val right: Float, val bottom: Float)

    fun frame(width: Float, height: Float): Frame = Frame(
        left = width * SIDE_INSET,
        top = height * TOP_INSET,
        right = width * (1f - SIDE_INSET),
        bottom = height * GROUND
    )

    /** Converts a tap to a stored position; taps outside the frame land on the nearest edge. */
    fun normalize(x: Float, y: Float, width: Float, height: Float): Pair<Float, Float> {
        val frame = frame(width, height)
        val frameWidth = (frame.right - frame.left).takeIf { it > 0f } ?: return 0.5f to 0.5f
        val frameHeight = (frame.bottom - frame.top).takeIf { it > 0f } ?: return 0.5f to 0.5f
        return ((x - frame.left) / frameWidth).coerceIn(0f, 1f) to
            ((y - frame.top) / frameHeight).coerceIn(0f, 1f)
    }

    fun toCanvas(goalX: Float, goalY: Float, width: Float, height: Float): Pair<Float, Float> {
        val frame = frame(width, height)
        return frame.left + (frame.right - frame.left) * goalX.coerceIn(0f, 1f) to
            frame.top + (frame.bottom - frame.top) * goalY.coerceIn(0f, 1f)
    }

    /** A short description such as "top left" for timelines and exports. */
    fun describe(goalX: Float?, goalY: Float?): String? {
        if (goalX == null || goalY == null) return null
        val height = when {
            goalY < 0.34f -> "top"
            goalY < 0.67f -> "middle"
            else -> "bottom"
        }
        val side = when {
            goalX < 0.34f -> "left"
            goalX < 0.67f -> "centre"
            else -> "right"
        }
        // Translated as a whole phrase: word order differs between languages.
        return tr(if (height == "middle" && side == "centre") "centre" else "$height $side")
    }
}
