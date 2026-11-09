package be.matchreview.app.ui

import be.matchreview.app.i18n.tr

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** How the app looks; Outdoor is a high-contrast light scheme for bright sunlight. */
enum class ThemeMode(private val englishLabel: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
    OUTDOOR("Outdoor");

    /** Looked up when shown, so it follows a change of language. */
    val label: String get() = tr(englishLabel)

    companion object {
        fun fromName(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

// Every role is set, so no Material default purple shows up in cards, chips or bars.
private val LightColors = lightColorScheme(
    primary = Color(0xFF087F5B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC6EFDC),
    onPrimaryContainer = Color(0xFF00261A),
    secondary = Color(0xFF2B7A4B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9EFE2),
    onSecondaryContainer = Color(0xFF0D2A19),
    tertiary = Color(0xFF1971C2),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD8E7F8),
    onTertiaryContainer = Color(0xFF0A2540),
    background = Color(0xFFEFF3F6),
    onBackground = Color(0xFF14232F),
    surface = Color(0xFFEFF3F6),
    onSurface = Color(0xFF14232F),
    surfaceVariant = Color(0xFFE2E8ED),
    onSurfaceVariant = Color(0xFF46535E),
    surfaceTint = Color(0xFF087F5B),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF9FBFC),
    surfaceContainer = Color(0xFFEEF2F5),
    surfaceContainerHigh = Color(0xFFE9EEF2),
    surfaceContainerHighest = Color(0xFFFFFFFF),
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFDDE3E8),
    outline = Color(0xFF78858F),
    outlineVariant = Color(0xFFC9D2D9)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF63E6BE),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF0B5A43),
    onPrimaryContainer = Color(0xFFC6EFDC),
    secondary = Color(0xFF8CE99A),
    onSecondary = Color(0xFF0D3A1F),
    secondaryContainer = Color(0xFF1F4D33),
    onSecondaryContainer = Color(0xFFD9EFE2),
    tertiary = Color(0xFF74C0FC),
    onTertiary = Color(0xFF00324F),
    tertiaryContainer = Color(0xFF16426B),
    onTertiaryContainer = Color(0xFFD8E7F8),
    background = Color(0xFF0B1F33),
    onBackground = Color(0xFFE3EAF0),
    surface = Color(0xFF0B1F33),
    onSurface = Color(0xFFE3EAF0),
    surfaceVariant = Color(0xFF1E364C),
    onSurfaceVariant = Color(0xFFB9C7D3),
    surfaceTint = Color(0xFF63E6BE),
    surfaceContainerLowest = Color(0xFF071726),
    surfaceContainerLow = Color(0xFF0F2438),
    surfaceContainer = Color(0xFF12293F),
    surfaceContainerHigh = Color(0xFF162F47),
    surfaceContainerHighest = Color(0xFF16314A),
    surfaceBright = Color(0xFF1E3A55),
    surfaceDim = Color(0xFF0B1F33),
    outline = Color(0xFF7F8F9C),
    outlineVariant = Color(0xFF34495C)
)

/** Pure white surfaces, black text and dark, saturated accents that stay readable in the sun. */
private val OutdoorColors = lightColorScheme(
    primary = Color(0xFF00492F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9EF2C9),
    onPrimaryContainer = Color.Black,
    secondary = Color(0xFF14532D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB7F0C8),
    onSecondaryContainer = Color.Black,
    tertiary = Color(0xFF0B3D91),
    tertiaryContainer = Color(0xFFC9DAFF),
    onTertiaryContainer = Color.Black,
    error = Color(0xFFB00020),
    errorContainer = Color(0xFFFFD6DB),
    onErrorContainer = Color.Black,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFE2E2E2),
    onSurfaceVariant = Color.Black,
    surfaceTint = Color(0xFF00492F),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF2F2F2),
    surfaceContainerHigh = Color(0xFFEDEDED),
    surfaceContainerHighest = Color.White,
    outline = Color.Black,
    outlineVariant = Color(0xFF5C5C5C)
)

/** Fields, chips and buttons share one corner radius; cards and sheets are a little rounder. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * True in the Outdoor theme: match-day screens then use bigger buttons and text, easier to
 * read and hit in bright sun, with gloves or in a hurry.
 */
val LocalLargeControls = staticCompositionLocalOf { false }

@Composable
fun MatchReviewTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val colors = when (mode) {
        ThemeMode.SYSTEM -> if (isSystemInDarkTheme()) DarkColors else LightColors
        ThemeMode.LIGHT -> LightColors
        ThemeMode.DARK -> DarkColors
        ThemeMode.OUTDOOR -> OutdoorColors
    }
    CompositionLocalProvider(LocalLargeControls provides (mode == ThemeMode.OUTDOOR)) {
        MaterialTheme(
            colorScheme = colors,
            typography = Typography(),
            shapes = AppShapes,
            content = content
        )
    }
}
