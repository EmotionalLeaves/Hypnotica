package app.hypnotica.ui

import app.hypnotica.core.audio.Song
import app.hypnotica.core.sim.TrapFormat
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
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
    val queue = state.tickTockQueue
    var current by remember { mutableStateOf<Clip?>(null) }
    var showChrome by remember { mutableStateOf(true) }
    var liked by remember { mutableStateOf(false) }
    var watched by remember { mutableIntStateOf(0) }
    var secondsWatching by remember { mutableIntStateOf(0) }
    var showTimeCheck by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    /**
     * Clips already watched, newest last: a swipe down goes back to one, as it
     * does on the feeds this is named after. It used to leave TickTock — the
     * clip you had just skipped past by accident was gone for good.
     */
    val history = remember { ArrayList<Clip>() }

    // The swipe follows the finger, as on the feed: the chrome travels with
    // it, the picture dims, and the next clip comes up out of the dark.
    val drag = remember { Animatable(0f) }
    var swiping by remember { mutableStateOf(false) }
    var height by remember { mutableIntStateOf(1) }

    // How far through the clip, for the line along the bottom.
    var progress by remember { mutableFloatStateOf(0f) }
    val shownProgress by animateFloatAsState(progress, tween(260, easing = LinearEasing), label = "clipProgress")

    // The like: a star that blooms where the double-tap landed and fades.
    val pop = remember { Animatable(0f) }

    // The twist's word, while it lasts.
    var twistLabel by remember { mutableStateOf<String?>(null) }

    // For number formats, the number itself, big: the thing the clip asks you
    // to watch, rather than a counter line in small print.
    var bigNumber by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(current) {
        bigNumber = null
        val clip = current ?: return@LaunchedEffect
        if (clip.format != TrapFormat.NUMBER_UP) return@LaunchedEffect
        while (true) {
            bigNumber = state.engine.counters.firstOrNull()
            delay(100)
        }
    }

    // A clip that arrived some other way — the timer, a finished run — while
    // a swipe's animation had been cancelled part-way: put the chrome back.
    LaunchedEffect(current) {
        if (drag.value != 0f && !drag.isRunning) drag.animateTo(0f, spring())
    }

    // Rule 1: the chrome fades after 2.6 s with no touch, as it does on the
    // feed and in the player. Here it sat over every clip for good. Each new
    // clip brings it back, so its hook is read before it goes.
    LaunchedEffect(current) { showChrome = true }
    LaunchedEffect(showChrome, current) {
        if (!showChrome) return@LaunchedEffect
        delay(CHROME_IDLE_MS)
        showChrome = false
    }
    // A notice is read and then goes by itself. The last one is kept for the
    // fade out, which would otherwise have nothing to draw.
    var lastNotice by remember { mutableStateOf("") }
    LaunchedEffect(notice) {
        val n = notice ?: return@LaunchedEffect
        lastNotice = n
        delay(NOTICE_MS)
        notice = null
    }

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
        progress = 0f
        state.playClip(clip)
        // Some clips are folded into a kaleidoscope: three, four or six ways,
        // from the clip's seed. The engine only folds sims drawn round their
        // centre, so a sort or a grid is never turned into confetti.
        // A song clip plays a tune everyone knows, one note per hit.
        state.engine.setSong(
            if (clip.format == TrapFormat.SONG) Song.TUNES[((clip.seed ushr 23) and 0xFFFF).toInt() % Song.TUNES.size]
            else Song.NONE
        )
        val roll = ((clip.seed ushr 17) and 0xFF).toInt()
        state.engine.kaleido = when {
            roll < 40 -> 6
            roll < 64 -> 4
            roll < 80 -> 3
            else -> 1
        }
    }

    /** Bring the chrome back in from the side it leaves by. */
    suspend fun arrive(fromBelow: Boolean) {
        val out = height * LEAVE
        drag.snapTo(if (fromBelow) out * ARRIVE else -out * ARRIVE)
        drag.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
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
        current?.let {
            history.add(it)
            if (history.size > HISTORY) history.removeAt(0)
        }
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
        // The twist this clip gets, from its seed so a replay gets the same one.
        val twist = Twist.entries[((clip.seed ushr 7) and 0x7FFFFFFF).toInt() % Twist.entries.size]
        val payoff = clip.payoffSeconds
        var twisted = false
        var twistOver = false
        twistLabel = null
        while (true) {
            val elapsed = kotlin.math.abs(state.engine.core.env.time)
            if (!started && elapsed < 1f) started = true
            if (started) progress = (elapsed / end).coerceIn(0f, 1f)
            if (started && elapsed >= end) break
            if (started) {
                // The camera leans in as the build goes on — a tenth closer by
                // the payoff — and eases back out after it, as an edit would.
                val toward = (elapsed / payoff).coerceIn(0f, 1f)
                val lean = if (elapsed <= payoff) toward * toward * (3f - 2f * toward)
                else (1f - (elapsed - payoff) / LEAN_BACK_SECONDS).coerceIn(0f, 1f)
                if (!state.engine.userZoomed) state.engine.setBaseZoom(1f + LEAN * lean)

                // The twist: a moment in the build where the clip changes gear.
                val (from, until) = twist.window(payoff)
                if (!twisted && elapsed >= from && twist != Twist.NONE) {
                    twisted = true
                    twistLabel = twist.label
                    when (twist) {
                        Twist.SURGE -> state.engine.core.speed = 1.9f
                        Twist.HUSH -> state.engine.core.speed = 0.45f
                        Twist.DROP -> state.engine.setPalette(otherPalette(clip.palette, clip.seed))
                        Twist.NONE -> {}
                    }
                }
                if (twisted && !twistOver && elapsed >= until) {
                    twistOver = true
                    twistLabel = null
                    state.engine.core.speed = 1f
                }
            }
            delay(100)
        }
        if (current !== clip) return@LaunchedEffect
        generating?.cancel()
        generating = scope.launch { advance() }
    }
    // Whatever a clip leaned or sped up, the next one starts level.
    LaunchedEffect(current) {
        state.engine.setBaseZoom(1f)
        state.engine.userZoomed = false
        state.engine.core.speed = 1f
    }

    LaunchedEffect(Unit) {
        // Same reason as the swipe: this fires from the GL thread and used to
        // launch a third advance alongside whatever was already running.
        state.engine.onFinished = {
            generating?.cancel()
            // After the resolve beat, not on the finishing frame: moving on
            // the instant a clip finished meant its ending was never seen.
            generating = scope.launch {
                delay(resolveDelayMs(state.engine))
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
            state.engine.kaleido = 1
            // A twist or a lean in progress does not follow the person out:
            // the feed ran at 1.9x under a slider reading 1x.
            state.engine.core.speed = state.speed
            state.engine.setBaseZoom(1f)
            state.engine.setPalette(state.paletteName)
            // Back to whatever song the person had chosen, usually none.
            state.engine.setSong(Song.byIndex(state.songIndex))
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
            .onSizeChanged { height = it.height.coerceAtLeast(1) }
            .pointerInput(Unit) {
                // The whole flick is one decision: acting per drag delta used to
                // throw away several generated clips, and teach the feed that the
                // person disliked all of them.
                var travelled = 0f
                detectVerticalDragGestures(
                    onDragStart = { travelled = 0f },
                    onDragCancel = {
                        travelled = 0f
                        scope.launch { drag.animateTo(0f, spring()) }
                    },
                    onDragEnd = {
                        val out = height * LEAVE
                        if (swiping) {
                            // One swipe at a time: a second touch during the
                            // first's animation used to cancel it, after the
                            // skip had already been counted against the clip.
                        } else if (travelled <= -SWIPE_PIXELS) {
                            val skipped = current
                            swiping = true
                            generating?.cancel()
                            generating = scope.launch {
                                try {
                                    drag.animateTo(-out, tween(Tokens.Fast, easing = FastOutLinearInEasing))
                                    advance()
                                    // Counted once it has really been left.
                                    if (skipped != null && current !== skipped) state.skipClip(skipped)
                                    arrive(fromBelow = true)
                                } finally {
                                    swiping = false
                                }
                            }
                        } else if (travelled >= SWIPE_PIXELS && history.isNotEmpty()) {
                            // Back to the clip before, which goes to the front
                            // of the queue so a swipe up returns here.
                            swiping = true
                            generating?.cancel()
                            generating = scope.launch {
                                try {
                                    drag.animateTo(out, tween(Tokens.Fast, easing = FastOutLinearInEasing))
                                    current?.let { queue.addFirst(it) }
                                    play(history.removeAt(history.size - 1))
                                    arrive(fromBelow = false)
                                } finally {
                                    swiping = false
                                }
                            }
                        } else if (travelled >= SWIPE_PIXELS) {
                            scope.launch { drag.animateTo(0f, spring(dampingRatio = 0.7f)) }
                            notice = "This is the first one. ‹ to leave."
                        } else {
                            scope.launch { drag.animateTo(0f, spring(dampingRatio = 0.7f)) }
                        }
                        travelled = 0f
                    }
                ) { change, amount ->
                    change.consume()
                    if (swiping) return@detectVerticalDragGestures
                    travelled += amount
                    val limit = height * LEAVE
                    val follow = limit * kotlin.math.tanh(travelled / limit)
                    scope.launch { drag.snapTo(follow) }
                }
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
            onDoubleTap = {
                current?.let { state.likeClip(it); liked = true }
                scope.launch {
                    pop.snapTo(1f)
                    pop.animateTo(0f, tween(LIKE_POP_MS, easing = LinearEasing))
                }
            },
            onLongPress = { current?.let { state.openClipInPlayer(it) } }
        )

        // The dark the next clip comes up out of.
        val through = (kotlin.math.abs(drag.value) / (height * LEAVE)).coerceIn(0f, 1f)
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = through * DIM }
                .background(Tokens.Bg)
        )

        // The like, blooming in the middle and fading.
        if (pop.value > 0f) {
            Text(
                "★",
                color = Tokens.Accent,
                fontSize = 120.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer {
                        val v = pop.value
                        alpha = (v * 1.6f).coerceAtMost(1f)
                        val scale = 0.7f + 0.5f * (1f - v) * (1f - v) + 0.3f * (1f - v)
                        scaleX = scale
                        scaleY = scale
                    }
            )
        }

        // The twist's word, centred and brief.
        AnimatedVisibility(
            visible = twistLabel != null,
            enter = fadeIn(tween(Tokens.Fast)),
            exit = fadeOut(tween(Tokens.Slow)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Text(
                twistLabel ?: "",
                color = Tokens.Accent,
                style = MaterialTheme.typography.displaySmall
            )
        }

        // The number a number clip is about.
        bigNumber?.let { (label, value) ->
            Column(
                Modifier.align(Alignment.Center).padding(top = 160.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(value, color = Tokens.TextPrimary, fontSize = 56.sp,
                    style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"))
                Text(label, color = Tokens.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }

        // How far through the clip: a hairline along the bottom, there even
        // when the rest has faded, as on the feeds this is named after.
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .safeDrawingPadding()
                .fillMaxWidth(shownProgress)
                .height(2.dp)
                .background(Tokens.Accent.copy(alpha = 0.7f))
        )

        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(20.dp)
                .graphicsLayer {
                    translationY = drag.value
                    alpha = 1f - through * 0.7f
                }
        ) {
            AnimatedVisibility(visible = showChrome, enter = fadeIn(tween(Tokens.Base)), exit = fadeOut(tween(Tokens.Slow))) {
                // Crossfaded from one clip's hook to the next, as the feed's is,
                // rather than swapped in a frame.
                AnimatedContent(
                    targetState = current?.caption ?: "Finding something…",
                    transitionSpec = { fadeIn(tween(Tokens.Base)) togetherWith fadeOut(tween(Tokens.Fast)) },
                    label = "tickTockHook"
                ) { caption ->
                    Text(
                        caption,
                        color = Tokens.TextPrimary,
                        style = MaterialTheme.typography.displaySmall
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(visible = liked, enter = fadeIn(tween(Tokens.Base)), exit = fadeOut(tween(Tokens.Slow))) {
                Text("★ more like this", color = Tokens.Accent, style = MaterialTheme.typography.titleMedium)
            }
            // Faded rather than cut: it used to appear and vanish in a frame.
            AnimatedVisibility(visible = notice != null, enter = fadeIn(tween(Tokens.Base)), exit = fadeOut(tween(Tokens.Slow))) {
                Text(lastNotice, color = Tokens.TextSecondary, style = MaterialTheme.typography.bodySmall)
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
            WinnerPick(state, current)
            AnimatedVisibility(visible = showChrome, enter = fadeIn(tween(Tokens.Base)), exit = fadeOut(tween(Tokens.Slow))) {
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
                                (if (watched == 1) "1 clip" else "$watched clips") + "  ·  hold to open  ·  ↓ back",
                                color = Tokens.TextSecondary.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    GlyphButton("‹") { state.goBack() }
                }
            }
        }
    }
}

/**
 * Picking the winner: a chip per side the run has, tapped before it is
 * settled, and the verdict when it is. A run of right calls is kept. Keyed on
 * whatever identifies the run on screen; a run that starts over (the feed
 * loops) is a fresh pick too.
 */
@Composable
fun WinnerPick(state: AppState, key: Any?) {
    var sides by remember { mutableStateOf(IntArray(0)) }
    var winnerSide by remember { mutableIntStateOf(-1) }
    var pick by remember { mutableIntStateOf(-1) }
    var verdict by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(key) {
        sides = IntArray(0)
        winnerSide = -1
        pick = -1
        verdict = null
        if (key == null) return@LaunchedEffect
        // Everything is keyed to the run the snapshot belongs to: a new run
        // (a restart, a loop, a new sim) clears the pick, so it can only be
        // settled by the run it was made in.
        var run = -1L
        while (true) {
            val snap = state.engine.pick
            if (snap.run != run) {
                run = snap.run
                pick = -1
                verdict = null
                winnerSide = -1
            }
            if (!snap.sides.contentEquals(sides)) {
                sides = snap.sides.copyOf()
                if (pick >= sides.size) pick = -1
            }
            if (snap.winner != winnerSide) {
                winnerSide = snap.winner
                if (snap.winner >= 0 && pick >= 0 && verdict == null) {
                    val right = pick == snap.winner
                    verdict = right
                    state.recordGuess(right)
                }
            }
            delay(100)
        }
    }
    // Shown while there is still a pick to make, and with the verdict.
    if (sides.isNotEmpty() && (winnerSide < 0 || pick >= 0)) {
        PickRow(
            sides = sides,
            pick = pick,
            winner = winnerSide,
            verdict = verdict,
            streak = state.guessStreak,
            best = state.guessBest
        ) { i -> if (pick < 0 && winnerSide < 0) pick = i }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * One chip per side, in the colour it is drawn in. Before a pick: "Pick one".
 * After: the pick stays lit and the rest fade. When it is settled: the winner
 * gets a ring, and the line says whether it was called.
 */
@Composable
private fun PickRow(
    sides: IntArray,
    pick: Int,
    winner: Int,
    verdict: Boolean?,
    streak: Int,
    best: Int,
    onPick: (Int) -> Unit
) {
    Column {
        val line = when (verdict) {
            true -> if (streak > 1) "Called it  ·  $streak in a row" else "Called it"
            false -> if (best > 1) "Not this time  ·  best $best" else "Not this time"
            null -> if (pick >= 0) "Your pick" else if (streak > 0) "Pick the winner  ·  $streak in a row" else "Pick the winner"
        }
        Text(
            line,
            color = when (verdict) { true -> Tokens.Accent; else -> Tokens.TextPrimary },
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (i in sides.indices) {
                val c = Color(sides[i])
                val faded = (pick >= 0 && i != pick && i != winner)
                val ring = when {
                    i == winner -> Tokens.Accent
                    i == pick -> Tokens.TextPrimary
                    else -> Color.Transparent
                }
                Box(
                    Modifier
                        .size(if (i == winner || i == pick) 40.dp else 32.dp)
                        .graphicsLayer { alpha = if (faded) 0.3f else 1f }
                        .border(3.dp, ring, CircleShape)
                        .padding(4.dp)
                        .background(c, CircleShape)
                        .clickable(enabled = pick < 0 && winner < 0, role = Role.Button) { onPick(i) }
                )
            }
        }
    }
}

/** Pixels of travel that count as a deliberate swipe. */
private const val SWIPE_PIXELS = 90f

/**
 * A change of gear partway through a clip's build — what an editor does to
 * keep someone watching. Each is timed against the clip's payoff, and none
 * touches the physics, so the payoff the pre-check found still comes.
 */
private enum class Twist(val label: String?) {
    NONE(null),
    /** Doubled speed through the middle of the build. */
    SURGE("faster"),
    /** Slowed right before the payoff: the held breath. */
    HUSH("wait for it"),
    /** The colours change on the way in. */
    DROP(null);

    /** When it starts and stops, in seconds of the run. */
    fun window(payoff: Float): Pair<Float, Float> = when (this) {
        SURGE -> payoff * 0.35f to payoff * 0.55f
        HUSH -> (payoff - 1.6f).coerceAtLeast(payoff * 0.5f) to payoff - 0.2f
        DROP -> payoff * 0.5f to payoff * 0.5f + 0.1f
        NONE -> Float.MAX_VALUE to Float.MAX_VALUE
    }
}

/** A palette other than [current], picked by [seed]. */
private fun otherPalette(current: String, seed: Long): String {
    val others = app.hypnotica.core.render.Palette.NAMES.filter { it != current }
    if (others.isEmpty()) return current
    return others[((seed ushr 11) and 0x7FFFFFFF).toInt() % others.size]
}

/** How much closer the camera is at the payoff. */
private const val LEAN = 0.1f

/** Seconds to ease back out after it. */
private const val LEAN_BACK_SECONDS = 1.5f

/** Clips a swipe down can go back through. */
private const val HISTORY = 30

/** Share of the screen the chrome travels before the next clip comes in. */
private const val LEAVE = 0.32f

/** Where the arriving chrome starts, as a share of [LEAVE], on the far side. */
private const val ARRIVE = 0.45f

/** How dark the picture goes at the swap. */
private const val DIM = 0.92f

/** How long the like's star takes to bloom and fade. */
private const val LIKE_POP_MS = 700

/** How long a line like "Nothing came up" stays before fading. */
private const val NOTICE_MS = 3500L

/** The least a clip lingers after its payoff, however early that came. */
private const val MIN_RESOLVE_SECONDS = 3f
