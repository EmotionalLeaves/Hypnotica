package app.hypnotica.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
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

    /**
     * How long things take to move, in milliseconds.
     *
     * DESIGN specifies these four and the code had none of them, so every
     * animation in the app ran on Compose's default spring — around 350-400 ms,
     * slower than any of these, and springy where the contract asks for timed
     * easing.
     */
    const val Fast = 120
    const val Base = 200
    const val Sheet = 280
    const val Slow = 350

    val Bg = Color(0xFF07080C)
    val Surface = Color(0xFF14161C)
    val SurfaceStrong = Color(0xFF1E212A)
    val TextPrimary = Color(0xFFF2F4F8)
    val TextSecondary = Color(0xFF9AA3B2)
    val Accent = Color(0xFF7C5CFF)

    /** The accent for words: lighter, so small text on the surface reads (6.5:1, not 4.2). */
    val AccentText = Color(0xFFA58FFF)
    val Success = Color(0xFF3DDC97)
    val Warning = Color(0xFFFFB020)
    val Danger = Color(0xFFFF5470)

    /**
     * Under every word drawn straight over the simulation. The scrims fade with
     * the chrome; a bright frame behind a white hook then left it unreadable.
     */
    val OverlayShadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 2f), blurRadius = 12f)

    /** Chrome arrives quickly and leaves slowly, on every screen. */
    val chromeIn: EnterTransition get() = fadeIn(tween(Fast, easing = LinearOutSlowInEasing))
    val chromeOut: ExitTransition get() = fadeOut(tween(Slow, easing = FastOutLinearInEasing))

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
    // Left unset, these fall back to Material's baseline purple-greys, which
    // showed up in switches and dividers.
    outline = Color.White.copy(alpha = 0.2f),
    outlineVariant = Color.White.copy(alpha = 0.08f),
    surfaceContainerHighest = Tokens.SurfaceStrong,
    surfaceContainerHigh = Tokens.SurfaceStrong,
    surfaceContainer = Tokens.Surface,
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
