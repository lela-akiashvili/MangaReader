package app.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = YellowAccent,
    background = DarkBackground,
    surface = DarkSurface,
    onBackground = Color.White,
    onSurface = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = YellowAccent,
    background = LightBackground,
    surface = Color.White,
    onBackground = Color.Black,
    onSurface = Color.Black
)

@Composable
fun MangaTheme(
    isDark: Boolean = true,
    content: @Composable () -> Unit
) {
    val colors = if (isDark) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}