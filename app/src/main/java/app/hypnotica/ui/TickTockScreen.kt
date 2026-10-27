package app.hypnotica.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.hypnotica.core.director.Clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TickTock: an endless feed of generated clips, hidden behind the clock in
 * Settings.
 *
 * Nothing is stored and nothing repeats: each clip is a sim, a seed and a set of
 * parameters put together on the spot, checked for a payoff, and thrown away
 * afterwards. Two clips are kept warm so a swipe never waits.
 */
@Composable
fun TickTockScreen(state: AppState, surface: SimSurfaceView) {
    // The director lives in the state, so taste survives leaving TickTock.
    val director = state.director
    val queue = remember { ArrayDeque<Clip>() }
    var current by remember { mutableStateOf<Clip?>(null) }
    var showChrome by remember { mutableStateOf(true) }
    var liked by remember { mutableStateOf(false) }
    var watched by remember { mutableIntStateOf(0) }
    var secondsWatching by remember { mutableIntStateOf(0) }
    var showTimeCheck by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    /** The generation in flight, so leaving the screen can stop it. */
    var generating by remember { mutableStateOf<Job?>(null) }

    /**
     * Make sure there is something to play, then top the queue up behind it.
     *
     * Entering the screen used to wait for two whole generations before showing
     * anything, and each one is up to twelve headless payoff checks: measured at
     * eleven and a half seconds for one sim on a desktop machine, so longer on a
     * phone, with the caption reading "Finding something…" over the previous
     * sim the whole time. One clip is enough to start.
     */
    suspend fun fillOne(): Boolean {
        if (queue.isNotEmpty()) return true
        val clip = withContext(Dispatchers.Default) { director.generate() } ?: return false
        queue.addLast(clip)
        return true
    }

    suspend fun prefetch() {
        while (queue.size < 2) {
            val clip = withContext(Dispatchers.Default) { director.generate() } ?: break
            queue.addLast(clip)
        }
    }

    fun play(clip: Clip) {
        current = clip
        liked = false
        state.playClip(clip)
    }

    suspend fun advance() {
        fillOne()
        val next = queue.removeFirstOrNull()
        if (next == null) {
            // The swipe used to look swallowed: the gesture landed, nothing
            // happened, and the clip on screen stayed put with no explanation.
            notice = "Nothing came up — swipe again."
            return
        }
        notice = null
        play(next)
        watched++
        // Behind the clip that is already playing, so the next swipe is instant
        // and this one was not waiting on it.
        prefetch()
    }

    LaunchedEffect(Unit) {
        state.engine.onFinished = { generating = scope.launch { advance() } }
        generating = scope.launch { advance() }
    }

    // The optional time check, off unless the person turned it on.
    LaunchedEffect(state.timeCheckMinutes) {
        if (state.timeCheckMinutes <= 0) return@LaunchedEffect
        while (true) {
            delay(60_000)
            secondsWatching += 60
            if (secondsWatching % (state.timeCheckMinutes * 60) == 0) showTimeCheck = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            state.engine.onFinished = null
            // Generation is a long CPU burn on the shared dispatcher, and
            // leaving the screen used not to stop it: the work carried on
            // stuttering whatever came next. withContext propagates the
            // cancellation, and the director checks for it between tries.
            generating?.cancel()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Tokens.Bg)
            .pointerInput(Unit) {
                // The whole flick is one decision: acting per drag delta used to
                // throw away several generated clips, and teach the feed that the
                // person disliked all of them.
                var travelled = 0f
                detectVerticalDragGestures(
                    onDragStart = { travelled = 0f },
                    onDragCancel = { travelled = 0f },
                    onDragEnd = {
                        if (travelled <= -SWIPE_PIXELS) {
                            current?.let { state.skipClip(it) }
                            generating = scope.launch { advance() }
                        } else if (travelled >= SWIPE_PIXELS) {
                            state.screen = Screen.SETTINGS
                        }
                        travelled = 0f
                    }
                ) { _, drag -> travelled += drag }
            }
    ) {
        // The taps go to the surface, not to an ancestor of it. The surface
        // consumes the touch down on the initial pass, so a tap detector out
        // here never started: double-tap to like, long-press to open the clip
        // and tap to hide the chrome were all dead, and the taste model only
        // ever heard about skips. Vertical drags still arrived, which is why
        // this looked wired up.
        SimCanvas(
            surface = surface,
            modifier = Modifier.fillMaxSize(),
            onTap = { showChrome = !showChrome },
            onDoubleTap = { current?.let { state.likeClip(it); liked = true } },
            onLongPress = { current?.let { state.openClipInPlayer(it) } }
        )

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
            AnimatedVisibility(visible = showChrome) {
                Text(
                    current?.caption ?: "Finding something…",
                    color = Tokens.TextPrimary,
                    style = MaterialTheme.typography.displaySmall
                )
            }
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(visible = liked) {
                Text("★ more like this", color = Tokens.Accent, style = MaterialTheme.typography.titleMedium)
            }
            notice?.let {
                Text(it, color = Tokens.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            AnimatedVisibility(visible = showTimeCheck) {
                Column {
                    GlassCard {
                        Column {
                            Text(
                                "You have been scrolling for ${secondsWatching / 60} minutes.",
                                color = Tokens.TextPrimary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Pill("Keep going") { showTimeCheck = false }
                                Pill("Leave TickTock") {
                                    showTimeCheck = false
                                    state.screen = Screen.FEED
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            AnimatedVisibility(visible = showChrome) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            current?.def?.title ?: "",
                            color = Tokens.TextPrimary,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            buildString {
                                append(current?.def?.family ?: "")
                                append("  ·  swipe up for the next")
                            },
                            color = Tokens.TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "$watched clips  ·  double-tap to like  ·  hold to open",
                            color = Tokens.TextSecondary.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    GlyphButton("‹") { state.screen = Screen.SETTINGS }
                }
            }
        }
    }
}

/** Pixels of travel that count as a deliberate swipe. */
private const val SWIPE_PIXELS = 90f
