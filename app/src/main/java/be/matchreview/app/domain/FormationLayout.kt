package be.matchreview.app.domain

import kotlin.math.ceil

data class FormationSlot(
    val id: String,
    val label: String,
    val normalizedX: Float,
    val normalizedY: Float
)

object FormationLayout {
    fun slots(formation: String, playersOnPitch: Int): List<FormationSlot> {
        if (playersOnPitch <= 0) return emptyList()
        if (playersOnPitch == 1) {
            return listOf(FormationSlot("GK", "GK", 0.5f, 0.88f))
        }

        val outfieldCount = playersOnPitch - 1
        val parsed = formation
            .split("-", "–", "—")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it > 0 }

        val rows = if (parsed.isNotEmpty() && parsed.sum() == outfieldCount) {
            parsed
        } else {
            balancedRows(outfieldCount)
        }

        val result = mutableListOf(FormationSlot("GK", "GK", 0.5f, 0.9f))
        val yPositions = when (rows.size) {
            1 -> listOf(0.5f)
            2 -> listOf(0.68f, 0.34f)
            3 -> listOf(0.72f, 0.5f, 0.27f)
            4 -> listOf(0.76f, 0.58f, 0.4f, 0.22f)
            else -> (rows.indices).map { index ->
                0.76f - (0.56f * index / (rows.size - 1).coerceAtLeast(1))
            }
        }

        rows.forEachIndexed { rowIndex, count ->
            val role = roleFor(rowIndex, rows.size)
            repeat(count) { playerIndex ->
                result += FormationSlot(
                    id = "$role-${playerIndex + 1}",
                    label = role,
                    normalizedX = (playerIndex + 1f) / (count + 1f),
                    normalizedY = yPositions[rowIndex]
                )
            }
        }
        return result
    }

    private fun balancedRows(outfieldCount: Int): List<Int> {
        if (outfieldCount <= 4) return listOf(outfieldCount)
        val rowCount = when {
            outfieldCount <= 7 -> 2
            outfieldCount <= 10 -> 3
            else -> 4
        }
        val perRow = ceil(outfieldCount / rowCount.toDouble()).toInt()
        val rows = MutableList(rowCount) { perRow }
        var excess = rows.sum() - outfieldCount
        var index = rowCount - 1
        while (excess > 0) {
            rows[index]--
            excess--
            index = (index - 1).coerceAtLeast(0)
        }
        return rows.filter { it > 0 }
    }

    private fun roleFor(rowIndex: Int, rowCount: Int): String = when {
        rowIndex == 0 -> "DEF"
        rowIndex == rowCount - 1 -> "FWD"
        else -> "MID"
    }
}
