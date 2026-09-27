package be.matchreview.app.domain

/**
 * Supported match sizes and formations. Keeping these choices in one place prevents a
 * formation whose outfield rows do not add up to the selected number of players.
 */
object MatchSetupRules {
    private val formationsBySize = linkedMapOf(
        3 to listOf("1-1", "2"),
        5 to listOf("2-1-1", "1-2-1", "2-2"),
        8 to listOf("3-3-1", "2-4-1", "2-3-2", "3-2-2", "2-2-3"),
        11 to listOf("4-3-3", "4-4-2", "3-5-2", "3-4-3", "4-2-3-1", "4-1-4-1", "5-3-2")
    )

    val supportedMatchSizes: List<Int> = formationsBySize.keys.toList()

    fun formationsFor(playersOnPitch: Int): List<String> =
        formationsBySize[playersOnPitch].orEmpty()

    fun defaultFormation(playersOnPitch: Int): String =
        formationsFor(playersOnPitch).firstOrNull() ?: ""

    fun isLegalFormation(playersOnPitch: Int, formation: String): Boolean =
        formation in formationsFor(playersOnPitch)

    fun legalFormationOrDefault(playersOnPitch: Int, formation: String): String =
        formation.takeIf { isLegalFormation(playersOnPitch, it) }
            ?: defaultFormation(playersOnPitch)
}
