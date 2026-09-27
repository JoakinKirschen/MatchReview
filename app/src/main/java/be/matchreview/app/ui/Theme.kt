package be.matchreview.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF087F5B),
    secondary = Color(0xFF2F9E44),
    tertiary = Color(0xFF1971C2),
    background = Color(0xFFF4F7FA),
    surface = Color.White,
    onPrimary = Color.White,
    onBackground = Color(0xFF102A43)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF63E6BE),
    secondary = Color(0xFF8CE99A),
    tertiary = Color(0xFF74C0FC),
    background = Color(0xFF0B1F33),
    surface = Color(0xFF102A43)
)

@Composable
fun MatchReviewTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = Typography(),
        content = content
    )
}
