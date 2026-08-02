package app.designsystem.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Brand Colors
val DarkBlueStart = Color(0xFF0B101E)
val PurpleEnd = Color(0xFF311545)
val YellowAccent = Color(0xFFFCDC2A)

// Neutral / Surface Colors
val DarkBackground = Color(0xFF121212)
val DarkSurface = Color(0xFF1E1E1E)
val DarkSurfaceVariant = Color(0xFF2C2C2C)
val LightBackground = Color(0xFFF5F5F5)

// Global Brushes
val HeaderFooterGradient = Brush.horizontalGradient(
    colors = listOf(DarkBlueStart, PurpleEnd)
)

val CardBottomOverlayGradient = Brush.verticalGradient(
    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))
)

val ProgressBarGradient = Brush.horizontalGradient(
    colors = listOf(Color(0xFF963819), YellowAccent)
)