package app.hypnotica

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import app.hypnotica.core.recipe.RecipeStore
import app.hypnotica.engine.Haptics
import app.hypnotica.engine.SimEngine
import app.hypnotica.sims.SimRegistry
import app.hypnotica.ui.AppState
import app.hypnotica.ui.FeedScreen
import app.hypnotica.ui.FirstRunCard
import app.hypnotica.ui.HypnoticaTheme
import app.hypnotica.ui.LibraryScreen
import app.hypnotica.ui.PlayerScreen
import app.hypnotica.ui.Prefs
import app.hypnotica.ui.RecipeScreen
import app.hypnotica.ui.Screen
import app.hypnotica.ui.SettingsScreen
import app.hypnotica.ui.SimSurfaceView
import app.hypnotica.ui.StudioScreen
import app.hypnotica.ui.TickTockScreen
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
                val state = remember {
                    AppState(engine, prefs, recipeStore).also { made ->
                        made.onCameraDrag = { on -> surface.cameraMode = on }
                    }
                }
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
                Box(Modifier.fillMaxSize()) {
                    AppRoot(state, surface, versionName())
                    if (state.showFirstRun) FirstRunCard(state)
                }
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
        engine.release()
        super.onDestroy()
    }
}

@Composable
private fun AppRoot(state: AppState, surface: SimSurfaceView, versionName: String) {
    when (state.screen) {
        Screen.FEED -> FeedScreen(state, surface)
        Screen.PLAYER -> PlayerScreen(state, surface)
        Screen.LIBRARY -> LibraryScreen(state)
        Screen.SETTINGS -> SettingsScreen(state, versionName)
        Screen.TICKTOCK -> TickTockScreen(state, surface)
        Screen.STUDIO -> StudioScreen(state, surface)
        Screen.RECIPES -> RecipeScreen(state)
    }
}

/** Long enough for the first sim to be up and smooth before anything else runs. */
private const val BENCHMARK_DELAY_MS = 6_000L
