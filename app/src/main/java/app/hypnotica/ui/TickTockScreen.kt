package app.hypnotica.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import app.hypnotica.core.director.ClipDirector
import kotlinx.coroutines.Dispatchers
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
    val director = remember { ClipDirector() }
    val queue = remember { ArrayDeque<Clip>() }
    var current by remember { mutableStateOf<Clip?>(null) }
    var showChrome by remember { mutableStateOf(true) }
    var liked by remember { mutableStateOf(false) }
    var watched by remember { mutableIntStateOf(0) }
    var secondsWatching by remember { mutableIntStateOf(0) }
    var showTimeCheck by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun fill() {
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
        fill()
        val next = queue.removeFirstOrNull() ?: return
        play(next)
        watched++
        fill()
    }

    LaunchedEffect(Unit) {
        state.engine.onFinished = { scope.launch { advance() } }
        advance()
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
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Tokens.Bg)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { }
                ) { _, drag ->
                    if (drag < -30f) {
                        current?.let { director.skip(it) }
                        scope.launch { advance() }
                    } else if (drag > 30f) {
                        state.screen = Screen.SETTINGS
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        current?.let { director.like(it); liked = true }
                    },
                    onLongPress = {
                        current?.let { state.openClipInPlayer(it) }
                    },
                    onTap = { showChrome = !showChrome }
                )
            }
    ) {
        SimCanvas(surface = surface, modifier = Modifier.fillMaxSize())

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
