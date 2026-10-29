package app.hypnotica

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import app.hypnotica.core.recipe.RecipeStore
import app.hypnotica.engine.Haptics
import app.hypnotica.engine.SimEngine
import app.hypnotica.sims.SimRegistry
import app.hypnotica.ui.AppState
import app.hypnotica.ui.BigButton
import app.hypnotica.ui.FeedScreen
import app.hypnotica.ui.HypnoticaTheme
import app.hypnotica.ui.LibraryScreen
import app.hypnotica.ui.PlayerScreen
import app.hypnotica.ui.Prefs
import app.hypnotica.ui.RecipeScreen
import app.hypnotica.ui.Screen
import app.hypnotica.ui.ScreenEnter
import app.hypnotica.ui.SettingsScreen
import app.hypnotica.ui.SimSurfaceView
import app.hypnotica.ui.StudioScreen
import app.hypnotica.ui.TickTockScreen
import app.hypnotica.ui.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var engine: SimEngine
    private lateinit var surface: SimSurfaceView
    private lateinit var prefs: Prefs
    private lateinit var recipeStore: RecipeStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // A sim is something you watch; the screen should not go out underneath it.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = Prefs(this)
        // Recipes register themselves before the first sim is chosen, so a
        // recipe can be the thing the app opens on.
        recipeStore = RecipeStore(java.io.File(filesDir, "recipes"))
        runCatching { recipeStore.load() }
        engine = SimEngine(SimRegistry.defaultSim())
        engine.haptics = Haptics(this)
        surface = SimSurfaceView(this, engine)

        setContent {
            HypnoticaTheme {
                val state = remember { AppState(engine, prefs, recipeStore) }
                // Measure the phone once, off the main thread — and not in the
                // first seconds, which belong to the simulation that is already
                // running. Rule 2: motion before anything else.
                LaunchedEffect(Unit) {
                    delay(BENCHMARK_DELAY_MS)
                    withContext(Dispatchers.Default) { state.benchmarkIfNeeded() }
                }
                BackHandler(enabled = true) {
                    if (!state.goBack()) finish()
                }
                AppRoot(state, surface, versionName())
            }
        }
    }

    /** The installed version, so Settings shows the build that is actually running. */
    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull() ?: "dev"

    override fun onResume() {
        super.onResume()
        surface.onResume()
        engine.running = true
        engine.startAudio()
    }

    override fun onPause() {
        super.onPause()
        engine.running = false
        engine.stopAudio()
        surface.onPause()
    }

    override fun onDestroy() {
        // The GL objects can only be deleted on the thread that owns the
        // context; calling this straight from here made every glDelete a
        // silent no-op.
        surface.queueEvent { engine.release() }
        super.onDestroy()
    }
}

/**
 * Shown over everything when a sim has stopped the engine.
 *
 * The GL thread used to be unguarded, so a throw in any sim's setup, step or
 * draw took the process with it — and because the last sim loaded is restored
 * on launch, the sim that killed the app was the one the next launch opened on.
 * A recipe is user-written Lua, so that was reachable without touching this
 * repo, and the only ways out were clearing the app's data or uninstalling.
 */
@Composable
private fun HaltedCard(state: AppState, why: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.82f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .safeDrawingPadding()
                .padding(24.dp)
                .background(Tokens.Surface, RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            Text(
                "This sim stopped",
                color = Tokens.TextPrimary,
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Something in ${state.currentDef.title} went wrong, so it was " +
                    "halted rather than allowed to take the app down.",
                color = Tokens.TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                why,
                color = Tokens.Danger,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton("Something else", Modifier.weight(1f)) {
                    state.screen = Screen.LIBRARY
                    state.recoverFrom(SimRegistry.defaultSim())
                }
                BigButton("Try it again", Modifier.weight(1f), primary = true) {
                    state.recoverFrom(null)
                }
            }
        }
    }
}

@Composable
private fun AppRoot(state: AppState, surface: SimSurfaceView, versionName: String) {
    androidx.compose.runtime.CompositionLocalProvider(app.hypnotica.ui.LocalChipEdge provides app.hypnotica.ui.Tokens.Bg) {
    val halted = state.halted
    if (halted != null) HaltedCard(state, halted) else when (state.screen) {
        Screen.FEED -> FeedScreen(state, surface)
        Screen.PLAYER -> PlayerScreen(state, surface)
        // The opaque panels fade up; the four screens that host the sim do not,
        // because the picture behind them is already continuous.
        Screen.LIBRARY -> ScreenEnter { LibraryScreen(state) }
        Screen.SETTINGS -> ScreenEnter { SettingsScreen(state, versionName) }
        Screen.TICKTOCK -> TickTockScreen(state, surface)
        Screen.STUDIO -> StudioScreen(state, surface)
        Screen.RECIPES -> ScreenEnter { RecipeScreen(state) }
    }
    }
}

/** Long enough for the first sim to be up and smooth before anything else runs. */
private const val BENCHMARK_DELAY_MS = 6_000L
