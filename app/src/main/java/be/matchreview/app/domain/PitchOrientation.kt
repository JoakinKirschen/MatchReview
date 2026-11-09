package be.matchreview.app.domain

/**
 * Lineup positions are stored for an upright pitch: x runs across the pitch (0 = left
 * touchline) and y runs along it (0 = the opponent's goal, 1 = our goal). In landscape the
 * pitch is drawn on its side with our goal on the left, so the free space of a wide screen
 * is used instead of squeezing an upright pitch into a short strip.
 */
object PitchOrientation {
    /** Fraction of the drawn pitch's width and height where a lineup position is shown. */
    fun toDisplay(normalizedX: Float, normalizedY: Float, horizontal: Boolean): Pair<Float, Float> =
        if (horizontal) (1f - normalizedY) to normalizedX else normalizedX to normalizedY

    /** The lineup position under a point given as a fraction of the drawn pitch. */
    fun toLineup(displayX: Float, displayY: Float, horizontal: Boolean): Pair<Float, Float> =
        if (horizontal) displayY to (1f - displayX) else displayX to displayY

    /**
     * The pitch size in pixels along the lineup's x and y axes, for distance checks made in
     * lineup coordinates.
     */
    fun lineupAxes(widthPx: Float, heightPx: Float, horizontal: Boolean): Pair<Float, Float> =
        if (horizontal) heightPx to widthPx else widthPx to heightPx

    /**
     * The lineup position under a pointer in root coordinates, kept inside the marker-safe
     * inset like [LineupDragDropRules.normalize].
     */
    fun normalizePointer(
        rootX: Float,
        rootY: Float,
        pitchLeft: Float,
        pitchTop: Float,
        pitchWidth: Float,
        pitchHeight: Float,
        horizontal: Boolean
    ): Pair<Float, Float> {
        val (displayX, displayY) = LineupDragDropRules.normalize(
            rootX, rootY, pitchLeft, pitchTop, pitchWidth, pitchHeight
        )
        return toLineup(displayX, displayY, horizontal)
    }
}
