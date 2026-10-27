package app.hypnotica

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import app.hypnotica.engine.Haptics
import app.hypnotica.engine.SimEngine
import app.hypnotica.sims.SimRegistry
import app.hypnotica.ui.AppState
import app.hypnotica.ui.FeedScreen
import app.hypnotica.ui.HypnoticaTheme
import app.hypnotica.ui.LibraryScreen
import app.hypnotica.ui.PlayerScreen
import app.hypnotica.ui.Prefs
import app.hypnotica.ui.Screen
import app.hypnotica.ui.SettingsScreen
import app.hypnotica.ui.SimSurfaceView
import app.hypnotica.ui.StudioScreen
import app.hypnotica.ui.TickTockScreen

class MainActivity : ComponentActivity() {

    private lateinit var engine: SimEngine
    private lateinit var surface: SimSurfaceView
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // A sim is something you watch; the screen should not go out underneath it.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = Prefs(this)
        engine = SimEngine(SimRegistry.defaultSim())
        engine.haptics = Haptics(this)
        surface = SimSurfaceView(this, engine)

        setContent {
            HypnoticaTheme {
                val state = remember { AppState(engine, prefs) }
                BackHandler(enabled = true) {
                    if (!state.goBack()) finish()
                }
                AppRoot(state, surface)
            }
        }
    }

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
private fun AppRoot(state: AppState, surface: SimSurfaceView) {
    when (state.screen) {
        Screen.FEED -> FeedScreen(state, surface)
        Screen.PLAYER -> PlayerScreen(state, surface)
        Screen.LIBRARY -> LibraryScreen(state)
        Screen.SETTINGS -> SettingsScreen(state, BuildConfigName.VERSION)
        Screen.TICKTOCK -> TickTockScreen(state, surface)
        Screen.STUDIO -> StudioScreen(state, surface)
    }
}

/** Version string, without turning on BuildConfig generation for one field. */
object BuildConfigName {
    const val VERSION = "0.2"
}
