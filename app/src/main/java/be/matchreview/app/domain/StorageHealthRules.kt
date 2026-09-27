package be.matchreview.app.domain

import java.util.Locale
import kotlin.math.roundToInt

enum class StorageLevel { OK, WARNING, CRITICAL }

data class StorageHealth(
    val availableBytes: Long,
    val level: StorageLevel
) {
    val canStartRecording: Boolean get() = level != StorageLevel.CRITICAL
}

object StorageHealthRules {
    const val WARNING_BYTES: Long = 1_000_000_000L
    const val CRITICAL_BYTES: Long = 250_000_000L

    fun evaluate(availableBytes: Long): StorageHealth {
        val safeBytes = availableBytes.coerceAtLeast(0L)
        val level = when {
            safeBytes < CRITICAL_BYTES -> StorageLevel.CRITICAL
            safeBytes < WARNING_BYTES -> StorageLevel.WARNING
            else -> StorageLevel.OK
        }
        return StorageHealth(safeBytes, level)
    }

    fun formatAvailable(bytes: Long): String {
        val gb = bytes.coerceAtLeast(0L) / 1_000_000_000.0
        return if (gb >= 1.0) String.format(Locale.US, "%.1f GB", gb)
        else "${(bytes.coerceAtLeast(0L) / 1_000_000.0).roundToInt()} MB"
    }
}
