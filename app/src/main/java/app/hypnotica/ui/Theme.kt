package app.hypnotica.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Design tokens from docs/DESIGN.md.
 *
 * The app is dark-first on purpose: a near-black canvas makes the simulation's
 * colour pop and saves battery on an OLED screen.
 */
object Tokens {
    val Bg = Color(0xFF07080C)
    val Surface = Color(0xFF14161C)
    val SurfaceStrong = Color(0xFF1E212A)
    val TextPrimary = Color(0xFFF2F4F8)
    val TextSecondary = Color(0xFF9AA3B2)
    val Accent = Color(0xFF7C5CFF)
    val Success = Color(0xFF3DDC97)
    val Warning = Color(0xFFFFB020)
    val Danger = Color(0xFFFF5470)

    val PaperBg = Color(0xFFF7F8FA)
    val PaperSurface = Color(0xFFFFFFFF)
    val PaperText = Color(0xFF14161C)
}

private val DarkScheme = darkColorScheme(
    primary = Tokens.Accent,
    onPrimary = Color.White,
    secondary = Tokens.Success,
    background = Tokens.Bg,
    onBackground = Tokens.TextPrimary,
    surface = Tokens.Surface,
    onSurface = Tokens.TextPrimary,
    surfaceVariant = Tokens.SurfaceStrong,
    onSurfaceVariant = Tokens.TextSecondary,
    error = Tokens.Danger
)

private val LightScheme = lightColorScheme(
    primary = Tokens.Accent,
    onPrimary = Color.White,
    secondary = Tokens.Success,
    background = Tokens.PaperBg,
    onBackground = Tokens.PaperText,
    surface = Tokens.PaperSurface,
    onSurface = Tokens.PaperText,
    error = Tokens.Danger
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun HypnoticaTheme(forceDark: Boolean = true, content: @Composable () -> Unit) {
    val dark = forceDark || isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content
    )
}
