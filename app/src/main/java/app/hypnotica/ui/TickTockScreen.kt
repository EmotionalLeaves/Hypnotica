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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hypnotica.core.director.Clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
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
     * Generate one clip, and actually stop when the coroutine is cancelled.
     *
     * generate() is a dozen headless payoff checks with no suspension point in
     * it — about eleven seconds of CPU — so cancelling the coroutine could not
     * unwind it, and the director's own `isInterrupted` check never fired
     * because cancelling a coroutine sets no interrupt flag. Swiping again, or
     * leaving the screen, left the work saturating the dispatcher the GL thread
     * shares, which is what the stutter on the way out was. runInterruptible is
     * what connects the two.
     */
    suspend fun generateCancellably(): Clip? =
        withContext(Dispatchers.Default) { runInterruptible { director.generate() } }

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
        val clip = generateCancellably() ?: return false
        queue.addLast(clip)
        return true
    }

    suspend fun prefetch() {
        while (queue.size < 2) {
            val clip = generateCancellably() ?: break
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

    // Every clip has a length: its payoff at 70% of it, as the arc puts it, and
    // the resolve after. TickTock used to move on only when a sim reported
    // itself finished, and thirty-one of the fifty-eight never do — so for more
    // than half the clips it generated, auto-play simply stopped and the feed
    // sat on one sim until it was swiped. The payoff time is known before the
    // clip is ever shown; that is what the pre-check is for.
    LaunchedEffect(current) {
        val clip = current ?: return@LaunchedEffect
        if (clip.payoffSeconds <= 0f) return@LaunchedEffect
        val end = maxOf(clip.payoffSeconds / 0.7f, clip.payoffSeconds + MIN_RESOLVE_SECONDS)
        // Simulated time, not the wall clock, so slow motion at the payoff and
        // a pause both stretch the clip as they should. The run's own clock is
        // reused across clips and resets when the new one is applied, which
        // happens on the render thread a frame after this starts — so nothing
        // is timed until a run near its start has been seen, or the previous
        // clip's forty seconds would end this one before it began.
        var started = false
        while (true) {
            val elapsed = kotlin.math.abs(state.engine.core.env.time)
            if (!started && elapsed < 1f) started = true
            if (started && elapsed >= end) break
            delay(250)
        }
        if (current !== clip) return@LaunchedEffect
        generating?.cancel()
        generating = scope.launch { advance() }
    }

    LaunchedEffect(Unit) {
        // Same reason as the swipe: this fires from the GL thread and used to
        // launch a third advance alongside whatever was already running.
        state.engine.onFinished = {
            generating?.cancel()
            // After the resolve beat, not on the finishing frame: moving on
            // the instant a clip finished meant its ending was never seen.
            generating = scope.launch {
                delay(RESOLVE_MS)
                advance()
            }
        }
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
                            generating?.cancel()
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
                            Spacer(Modifier.height(8.dp))
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
                        // Nothing is drawn until there is a clip. While the
                        // first one is being found this used to render an empty
                        // title and a line beginning with a bare separator —
                        // "  ·  swipe up for the next" — with nothing in front
                        // of it, for the several seconds a generation takes.
                        val clip = current
                        if (clip != null) {
                            Text(
                                clip.def.title,
                                color = Tokens.TextPrimary,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                clip.def.family,
                                color = Tokens.TextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            // Shortened: at one clip the old line was 47
                            // characters against 272 dp of column, so it always
                            // wrapped and left one word alone on a second line.
                            Text(
                                "$watched clips  ·  hold to open",
                                color = Tokens.TextSecondary.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    GlyphButton("‹") { state.screen = Screen.SETTINGS }
                }
            }
        }
    }
}

/** Pixels of travel that count as a deliberate swipe. */
private const val SWIPE_PIXELS = 90f

/** The least a clip lingers after its payoff, however early that came. */
private const val MIN_RESOLVE_SECONDS = 3f
