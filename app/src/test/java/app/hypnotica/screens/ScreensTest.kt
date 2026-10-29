package app.hypnotica.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import app.hypnotica.core.render.G
import app.hypnotica.core.sim.SimCore
import app.hypnotica.core.sim.SimDef
import app.hypnotica.engine.SimEngine
import app.hypnotica.sims.SimRegistry
import app.hypnotica.ui.AppState
import app.hypnotica.ui.FeedScreen
import app.hypnotica.ui.HypnoticaTheme
import app.hypnotica.ui.LibraryScreen
import app.hypnotica.ui.PlayerScreen
import app.hypnotica.ui.RecipeScreen
import app.hypnotica.ui.Screen
import app.hypnotica.ui.SettingsScreen
import app.hypnotica.ui.SimSurfaceView
import app.hypnotica.ui.StudioScreen
import app.hypnotica.ui.TickTockScreen
import app.hypnotica.ui.Tokens
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of every screen, for looking at rather than asserting on.
 *
 * The app is built and checked from a phone, so nobody sees a screen until it
 * is installed. This renders each one on the CI runner (Robolectric, native
 * graphics) and uploads the PNGs, with the sim behind the chrome painted from
 * its own draw list, since the GL surface draws nothing off-device.
 *
 * Only runs when the build is asked for screens (-Pscreens): it is slow, and a
 * picture is not a pass or a fail.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
class ScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, settleMs: Long = 2500, setup: (AppState) -> Unit = {}, content: @Composable (AppState, SimSurfaceView) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = SimEngine(SimRegistry.defaultSim())
        val surface = SimSurfaceView(context, engine)
        // The GL surface draws nothing here and would cover the painted sim.
        surface.visibility = android.view.View.INVISIBLE
        val state = AppState(engine)
        setup(state)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HypnoticaTheme {
                androidx.compose.runtime.CompositionLocalProvider(app.hypnotica.ui.LocalChipEdge provides Tokens.Bg) {
                    Box(Modifier.fillMaxSize().background(Tokens.Bg)) {
                        Backdrop(state.currentDef)
                        content(state, surface)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(settleMs)
        compose.onRoot().captureRoboImage("build/screens/$name.png")
    }

    @Test fun feed() = shot("01-feed") { s, v -> s.screen = Screen.FEED; FeedScreen(s, v) }
    @Test fun feedLater() = shot("02-feed-chrome-faded", settleMs = 9000) { s, v -> FeedScreen(s, v) }
    @Test fun player() = shot("03-player", setup = { it.screen = Screen.PLAYER }) { s, v -> PlayerScreen(s, v) }
    @Test fun playerDrawer() = shot("04-player-drawer", setup = { it.screen = Screen.PLAYER; it.controlsOpen = true }) { s, v -> PlayerScreen(s, v) }
    @Test fun playerAdvanced() = shot("05-player-advanced", setup = { it.screen = Screen.PLAYER; it.controlsOpen = true; it.advanced = true }) { s, v -> PlayerScreen(s, v) }
    @Test fun tickTock() = shot("06-ticktock", setup = { it.screen = Screen.TICKTOCK }) { s, v -> TickTockScreen(s, v) }
    @Test fun library() = shot("07-library", setup = { it.screen = Screen.LIBRARY }) { s, _ -> LibraryScreen(s) }
    @Test fun settings() = shot("08-settings", setup = { it.screen = Screen.SETTINGS }) { s, _ -> SettingsScreen(s, "0.1.test") }
    @Test fun studio() = shot("09-studio", setup = { it.screen = Screen.STUDIO }) { s, v -> StudioScreen(s, v) }
    @Test fun recipes() = shot("10-recipes", setup = { it.screen = Screen.RECIPES }) { s, _ -> RecipeScreen(s) }
}

/** The sim, simulated on the CPU to its payoff and painted from its draw list. */
@Composable
private fun Backdrop(def: SimDef) {
    val g = remember(def.id) {
        val core = SimCore(def, 3L)
        core.setAspect(393f / 852f)
        core.restart(3L)
        core.advanceSeconds(def.typicalSeconds * 0.8f)
        G().also { it.reset(); core.draw(it) } to core.fit()
    }
    Canvas(Modifier.fillMaxSize()) { paint(g.first, g.second) }
}

private fun DrawScope.paint(g: G, fit: Float) {
    val s = size.height / 2f * fit
    val cx0 = size.width / 2f
    val cy0 = size.height / 2f
    fun shapes(arr: FloatArray, n: Int) {
        for (i in 0 until n) {
            val o = i * G.STRIDE
            val x = cx0 + arr[o] * s
            val y = cy0 - arr[o + 1] * s
            val hw = arr[o + 2] * s
            val hh = arr[o + 3] * s
            val rot = arr[o + 4]
            val shape = arr[o + 5]
            val c = Color(arr[o + 8].coerceIn(0f, 1f), arr[o + 9].coerceIn(0f, 1f), arr[o + 10].coerceIn(0f, 1f), arr[o + 11].coerceIn(0f, 1f))
            rotate(-rot * 57.29578f, Offset(x, y)) {
                when (shape) {
                    G.SHAPE_RECT -> drawRect(c, Offset(x - hw, y - hh), Size(hw * 2, hh * 2))
                    G.SHAPE_DISC -> drawOval(c, Offset(x - hw, y - hh), Size(hw * 2, hh * 2))
                    G.SHAPE_RING -> {
                        val inner = arr[o + 6]
                        val w = (hw * (1f - inner)).coerceAtLeast(0.5f)
                        drawCircle(c, radius = hw - w / 2f, center = Offset(x, y), style = Stroke(w))
                    }
                    else -> if (hw > 0.5f) drawCircle(
                        Brush.radialGradient(
                            0f to c.copy(alpha = c.alpha), 0.5f to c.copy(alpha = c.alpha * 0.25f), 1f to Color.Transparent,
                            center = Offset(x, y), radius = hw
                        ),
                        radius = hw, center = Offset(x, y)
                    )
                }
            }
        }
    }
    shapes(g.glows, g.glowCount)
    shapes(g.instances, g.instanceCount)
    val pc = g.pointColor
    for (j in 0 until minOf(g.pointCount, 200_000)) {
        val r = (g.pointPos[j * 3 + 2] * s).coerceIn(1f, 96f) / 2f
        drawCircle(
            Color(pc[j * 4].toInt() and 255, pc[j * 4 + 1].toInt() and 255, pc[j * 4 + 2].toInt() and 255, pc[j * 4 + 3].toInt() and 255),
            radius = r,
            center = Offset(cx0 + g.pointPos[j * 3] * s, cy0 - g.pointPos[j * 3 + 1] * s)
        )
    }
}
