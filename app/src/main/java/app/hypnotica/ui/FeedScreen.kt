package app.hypnotica.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.util.VelocityTracker

/**
 * Home: one sim, full screen, swipe up for the next.
 *
 * Nothing loads here. The next sim is already described in the registry and
 * starts the instant the swipe lands, which is what keeps motion within a second
 * of every entry point.
 */
@Composable
fun FeedScreen(state: AppState, surface: SimSurfaceView) {
    // Dealt afresh each launch, unseen sims and favourites drawn forward. It
    // was one fixed shuffle, so every session opened on the same dozen sims.
    val order = remember { state.feedOrder }
    // Start wherever the player actually is, so arriving here does not yank the
    // sim out from under the person, and so the sim restored at launch is the
    // one they were watching.
    //
    // Resolved before anything else in this composition runs: as an effect it
    // ran after the load below, which then replaced (and remixed) the sim the
    // person had come from.
    remember {
        val here = order.indexOfFirst { it.id == state.currentDef.id }
        if (here >= 0 && here != state.feedIndex) state.feedIndex = here
        true
    }
    val index = state.feedIndex
    // Until the first swipe of the session, not of each visit: kept on the
    // screen, it came back every time the feed was re-entered, and since the
    // chrome does not fade while it shows, the buttons sat over the sim again.
    var hint by state::feedHint

    // A sim you are watching runs for as long as you watch it.
    //
    // A run that finished used to swap in the next sim on the same frame, so a
    // sort was gone the instant it was sorted and the chaos game the instant
    // its shape had formed — the feed moved on at exactly the moment there was
    // something to look at, and nothing ran for longer than its first run. It
    // holds the finished picture for the resolve beat and goes again, the same
    // as the player; the swipe is how you leave.
    var finishedAt by remember { mutableStateOf(0L) }
    var payoffAt by remember { mutableStateOf(0L) }
    DisposableEffect(Unit) {
        state.engine.onFinished = { finishedAt = System.nanoTime() }
        state.engine.onPayoff = { payoffAt = System.nanoTime() }
        onDispose {
            state.engine.onFinished = null
            state.engine.onPayoff = null
            // The remix's look stays with the feed: the player and the
            // library show a sim as it is.
            state.engine.kaleido = 1
            state.engine.spin = 0f
        }
    }
    LaunchedEffect(finishedAt) {
        if (finishedAt == 0L) return@LaunchedEffect
        val finishedRun = state.loadCount
        delay(resolveDelayMs(state.engine))
        finishedAt = 0L
        // Swiped on during the beat: the new sim is not the one that finished.
        if (state.loadCount == finishedRun) state.restart(newSeed = true)
    }

    // Keyed on the sim rather than Unit: re-entering the feed used to reload,
    // which threw away the run, the look and the person's place in it.
    DisposableEffect(index) {
        if (order[index].id != state.currentDef.id) {
            state.loadRemixed(order[index])
            // A look of its own as well, sometimes: folded into a
            // kaleidoscope, or turning slowly. Only sims drawn round their
            // centre take either; the engine sees to that.
            // Mixed from the sim and the visit: from the visit alone, the
            // first of every session (visit 0) always came out a kaleidoscope.
            val roll = (((state.feedVisits + 1L) * 2654435761L xor order[index].id.hashCode().toLong()) ushr 8 and 0xFF).toInt()
            state.engine.kaleido = if (roll < 40) (if (roll < 20) 6 else 4) else 1
            state.engine.spin = if (roll in 40 until 80) (if (roll % 2 == 0) SPIN else -SPIN) else 0f
            // Now and then — about one visit in twelve, never in the first
            // few — a rare run: gold, folded six ways and turning, and
            // tagged. A reward you can't schedule is the one you keep
            // swiping for.
            val rare = state.feedVisits >= 3 &&
                ((((state.feedVisits + 7L) * -7046029254386353131L) xor order[index].id.hashCode().toLong()) ushr 40 and 0xFF) < RARE_IN_256
            if (rare) {
                state.engine.kaleido = 6
                state.engine.spin = SPIN
            }
            state.rollRare(rare)
        }
        onDispose { }
    }

    // The swipe follows the finger.
    //
    // The sim itself cannot move — it is a GLSurfaceView, and the system draws
    // a surface a frame behind the views over it, so sliding one tears — but
    // everything over it can. Drag and the title and buttons travel with you
    // while the picture dims; let go past the line and the next sim comes up
    // out of the dark; let go short and it all springs back. It used to be a
    // dead ninety pixels and then a cut.
    val drag = remember { Animatable(0f) }

    // Rule 1: the sim is the interface, and the chrome over it fades after
    // 2.6 s with no touch. The player has always done this; the feed never
    // did, so the home screen was a hook line, a title, three round buttons
    // and two big ones sitting over the sim permanently. It stays up until the
    // first swipe, so "swipe up or down" is there to be read, and any touch
    // brings it back.
    var touchedAt by remember { mutableLongStateOf(0L) }
    var chromeShown by remember { mutableStateOf(true) }
    // Whether the chrome had faded when this touch began. The touch itself
    // brings it back, so by the time the tap lands it is always showing.
    var hiddenAtDown by remember { mutableStateOf(false) }
    LaunchedEffect(touchedAt, hint) {
        chromeShown = true
        if (hint) return@LaunchedEffect
        delay(CHROME_IDLE_MS)
        chromeShown = false
    }
    // Fades in on arrival rather than being there on the first frame: coming
    // back from the player was a hard cut from one set of bars to another.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val chromeAlpha by animateFloatAsState(
        if (chromeShown && entered) 1f else 0f,
        tween(if (chromeShown) Tokens.Fast else Tokens.Slow),
        label = "feedChrome"
    )
    var height by remember { mutableIntStateOf(1) }
    val scope = rememberCoroutineScope()

    // The camera leans in through the build, as TickTock's does, and eases
    // back after the payoff. Steered by the run's own clock and its own
    // payoff, so it follows every restart; a pinch takes the camera back.
    LaunchedEffect(state.currentDef.id) {
        val engine = state.engine
        engine.setBaseZoom(1f)
        engine.userZoomed = false
        var mine = 1f
        var after = 0f
        // At least a second: a recipe may declare no length at all, and 0/0
        // here was a NaN zoom and a blank frame.
        val build = (state.currentDef.typicalSeconds * 0.7f).coerceAtLeast(1f)
        while (true) {
            delay(100)
            if (engine.userZoomed || kotlin.math.abs(engine.baseZoom() - mine) > 0.01f) break
            val core = engine.core
            val t = kotlin.math.abs(core.env.time)
            val lean = if (!core.payoffFired) {
                after = 0f
                val x = (t / build).coerceIn(0f, 1f)
                x * x * (3f - 2f * x)
            } else {
                after += 0.1f
                (1f - after / FEED_LEAN_BACK).coerceIn(0f, 1f)
            }
            mine = 1f + FEED_LEAN * lean
            engine.setBaseZoom(mine)
        }
    }


    fun go(step: Int) {
        // Leaving before the moment breaks the run of ones watched through.
        if (!state.engine.core.payoffFired) state.watchedThrough = 0
        hint = false
        state.tick()
        scope.launch {
            val out = if (step > 0) -height * LEAVE else height * LEAVE
            try {
                drag.animateTo(out, tween(Tokens.Fast, easing = FastOutLinearInEasing))
            } finally {
                // In a finally: a second flick inside this one's exit animation
                // takes the drag and cancels it, and the move used to be lost
                // with it — two quick flicks went one sim.
                //
                // Read now, not when the gesture began: the handler used to hold
                // the index from the first time it ran, so every swipe after the
                // first computed "next" from the sim the feed opened on — and the
                // second swipe landed on the sim already showing.
                state.feedIndex = (state.feedIndex + step + order.size) % order.size
                state.feedVisits++
            }
            drag.snapTo(-out * ARRIVE)
            drag.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
        }
    }

    // Hands-free: after each payoff and its resolve, on to the next sim —
    // unless a finger has been on the screen since, which means someone is
    // playing with this one.
    LaunchedEffect(payoffAt) {
        if (payoffAt == 0L || !state.feedAutoAdvance) return@LaunchedEffect
        val run = state.loadCount
        val since = payoffAt
        delay(resolveDelayMs(state.engine) + FEED_AUTO_HOLD_MS)
        if (state.feedAutoAdvance && state.loadCount == run && touchedAt < since) go(1)
    }


    Box(
        Modifier
            .fillMaxSize()
            .background(Tokens.Bg)
            .onSizeChanged { height = it.height.coerceAtLeast(1) }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.pressed && !it.previousPressed }) hiddenAtDown = !chromeShown
                        if (event.changes.any { it.pressed }) touchedAt = System.nanoTime()
                    }
                }
            }
            .pointerInput(order.size) {
                var travelled = 0f
                // In dp, not pixels, and a quick flick counts as well as a
                // long drag: ninety raw pixels was a short swipe on one phone
                // and a long one on another, and a fast short flick sprang back.
                val swipe = SWIPE_DP.dp.toPx()
                val flick = FLICK_DP_PER_S.dp.toPx()
                val minFlick = MIN_FLICK_DP.dp.toPx()
                val tracker = VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = { travelled = 0f; tracker.resetTracking(); surface.screenSwiping = true },
                    onDragCancel = {
                        surface.screenSwiping = false
                        travelled = 0f
                        scope.launch { drag.animateTo(0f, spring()) }
                    },
                    onDragEnd = {
                        surface.screenSwiping = false
                        // One flick moves one sim, however far it went.
                        val vy = tracker.calculateVelocity().y
                        when {
                            travelled <= -swipe || (vy < -flick && travelled < -minFlick) -> go(+1)
                            travelled >= swipe || (vy > flick && travelled > minFlick) -> go(-1)
                            else -> scope.launch {
                                drag.animateTo(0f, spring(dampingRatio = 0.7f))
                            }
                        }
                        travelled = 0f
                    }
                ) { change, dragAmount ->
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    travelled += dragAmount
                    // Followed one to one at first and with growing resistance
                    // after, so a long drag does not throw the chrome off screen.
                    val limit = height * LEAVE
                    val follow = limit * kotlin.math.tanh(travelled / limit)
                    scope.launch { drag.snapTo(follow) }
                }
            }
    ) {
        SimCanvas(
            surface = surface,
            modifier = Modifier.fillMaxSize(),
            // No double-tap here. A tap on the feed opens the player, and
            // waiting a double-tap window before doing it puts a quarter of a
            // second of lag on the screen's whole purpose — worse than the bug
            // it would fix. The ★ in the row below already favourites this sim,
            // and double-tap stays what it is in TickTock, where the tap only
            // shows and hides the chrome.
            //
            // Once the buttons have faded, the first tap only brings them
            // back. It used to open the player as well, so on the feed the
            // buttons could only be got back by starting a drag.
            onTap = { if (!hiddenAtDown) state.screen = Screen.PLAYER },
            // Hold for the controls, as the gesture rules say: long-press
            // opens more. It did nothing here.
            onLongPress = {
                state.screen = Screen.PLAYER
                state.controlsOpen = true
            }
        )

        // How far through the move, 0 at rest and 1 at the swap.
        val through = (kotlin.math.abs(drag.value) / (height * LEAVE)).coerceIn(0f, 1f)
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = through * DIM }
                .background(Tokens.Bg)
        )

        // Shade behind the words, top and bottom, so they read over the
        // brightest sim; it fades with them.
        if (chromeAlpha > 0f) Scrims(chromeAlpha * (1f - through * 0.7f))

        // Not composed at all once it has faded: an invisible button is still
        // a button, and tapping the sim where one had been would press it.
        if (chromeAlpha > 0f) Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = drag.value
                    alpha = (1f - through * 0.7f) * chromeAlpha
                }
                .safeDrawingPadding()
        ) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(
                    targetState = state.currentDef.id,
                    transitionSpec = {
                        fadeIn(tween(Tokens.Base)) togetherWith fadeOut(tween(Tokens.Fast))
                    },
                    label = "feedHook",
                    modifier = Modifier.weight(1f)
                ) { _ ->
                    Text(
                        hookFor(state, state.feedVisits),
                        color = Tokens.TextPrimary,
                        style = MaterialTheme.typography.displaySmall.copy(shadow = Tokens.OverlayShadow),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                AnimatedContent(
                    targetState = state.currentDef.id,
                    transitionSpec = {
                        fadeIn(tween(Tokens.Base)) togetherWith fadeOut(tween(Tokens.Fast))
                    },
                    label = "feedTitle",
                    modifier = Modifier.weight(1f)
                ) { _ ->
                    Column {
                        // Never opened on this phone before: said, quietly, so a
                        // swipe that turns up something new feels like a find.
                        if (state.rareRun) RareTag(Modifier.padding(bottom = 8.dp))
                        else if (state.currentIsNew) NewTag(Modifier.padding(bottom = 8.dp))
                        Text(
                            state.currentDef.title,
                            color = Tokens.TextPrimary,
                            style = MaterialTheme.typography.titleLarge.copy(shadow = Tokens.OverlayShadow),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (hint) "swipe ↑↓  ·  tap to play with it  ·  hold for controls"
                            // Which variant this visit landed on, when it isn't the usual.
                            else state.remixVariant?.let { "${state.currentDef.shelf.title}  ·  $it" } ?: state.currentDef.shelf.title,
                            color = Tokens.TextSecondary,
                            style = MaterialTheme.typography.bodySmall.copy(shadow = Tokens.OverlayShadow),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GlyphButton(
                        if (state.isFavourite(state.currentDef.id)) "★" else "☆",
                        selected = state.isFavourite(state.currentDef.id),
                        pop = true
                    ) { state.toggleFavourite(state.currentDef.id) }
                    Spacer(Modifier.height(8.dp))
                    // The door to TickTock, out where it can be found: it was a
                    // faint mark under the version number in Settings. (A new
                    // run of this sim is a swipe away anyway — the feed loops.)
                    GlyphButton("◷") {
                        state.tick()
                        state.screen = Screen.TICKTOCK
                    }
                    Spacer(Modifier.height(8.dp))
                    // Not ≡, which in the player opens the controls drawer.
                    GlyphButton("▦") { state.screen = Screen.LIBRARY }
                    Spacer(Modifier.height(8.dp))
                    GlyphButton("⚙") { state.screen = Screen.SETTINGS }
                }
            }
            // The two big buttons that sat under all this — "Open controls"
            // and "Settings" — are gone: holding the sim opens the controls,
            // and Settings is the last of the small buttons. The home screen
            // is the sim, a title and four glyphs.
        }

        // How far through to the payoff, as a hairline along the bottom: the
        // pull to watch the last bit, which TickTock had and the feed didn't.
        var runShare by remember { mutableStateOf(0f) }
        var paid by remember { mutableStateOf(false) }
        LaunchedEffect(state.currentDef.id) {
            while (true) {
                val core = state.engine.core
                val build = (state.currentDef.typicalSeconds * 0.7f).coerceAtLeast(1f)
                if (core.payoffFired && !paid) state.watchedOne()
                paid = core.payoffFired
                runShare = if (core.payoffFired) 1f else (kotlin.math.abs(core.env.time) / build).coerceIn(0f, 1f)
                delay(120)
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .safeDrawingPadding()
                .fillMaxWidth(runShare)
                .height(2.dp)
                .background(Tokens.Accent.copy(alpha = 0.6f))
        )

        // Pick the winner, for sims with sides. Outside the chrome, which
        // fades: a pick made and then faded out with it lost its verdict.
        // Above the column of buttons, so the chips never sit under them.
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .safeDrawingPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 248.dp)
                .graphicsLayer {
                    translationY = drag.value
                    alpha = 1f - through * 0.7f
                }
        ) {
            Column { WinnerPick(state, state.currentDef.id) }
        }

        // The pull to stay: once the chrome has gone, a line at the top as
        // the run nears its moment ("Wait for it…"), and once it lands, what
        // is up next — so the end of one sim is already the start of the
        // next. Neither covers the chrome: both only show while it is down.
        val quiet = chromeAlpha == 0f && !hint
        TensionTag(
            visible = quiet && ((runShare >= TENSE_FROM && !paid) || paid),
            text = if (paid) afterLine(state.currentDef.id, state.feedVisits, state.watchedThrough) else tenseLine(state, state.feedVisits),
            pulsing = !paid,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .safeDrawingPadding()
                .padding(top = 20.dp)
        )
        val nextDef = order[(index + 1) % order.size]
        UpNext(
            visible = quiet && paid,
            next = nextDef,
            isNew = nextDef.id !in state.seen.value,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(bottom = 28.dp)
        ) { go(1) }

    }
}

/**
 * The headline over the sim: one of the hooks of any of its formats, chosen
 * afresh each time the feed comes round to it. It was always the first
 * format's first hook, so a sim said the same thing every time it was met.
 */
private fun hookFor(state: AppState, visit: Int): String {
    val hooks = state.currentDef.formats.flatMap { it.hooks }
    if (hooks.isEmpty()) return state.currentDef.blurb
    val key = (state.currentDef.id.hashCode().toLong() * 31L + visit) and 0x7FFFFFFF
    return hooks[(key % hooks.size).toInt()]
}

/**
 * The line as the moment nears, from the sim's own formats where it has a
 * fitting one: a battle says it is down to the last few, a reveal that it is
 * nearly clean. The same each time for one visit, different across visits.
 */
private fun tenseLine(state: AppState, visit: Int): String {
    val f = state.currentDef.formats
    val pool = when {
        app.hypnotica.core.sim.TrapFormat.LAST_STANDING in f || app.hypnotica.core.sim.TrapFormat.GUESS in f ->
            listOf("Down to the last few", "Who's it going to be?", "It could still go either way")
        app.hypnotica.core.sim.TrapFormat.ESCAPE in f -> listOf("So close…", "Any second now")
        app.hypnotica.core.sim.TrapFormat.REVEAL in f || app.hypnotica.core.sim.TrapFormat.FILL in f ->
            listOf("Nearly there…", "Almost done", "Just a bit more")
        app.hypnotica.core.sim.TrapFormat.SNAP in f || app.hypnotica.core.sim.TrapFormat.CUT in f ->
            listOf("Wait for it…", "Here it comes", "Don't look away")
        else -> listOf("Wait for it…", "Almost…", "Here it comes")
    }
    val key = (state.currentDef.id.hashCode().toLong() * 17L + visit) and 0x7FFFFFFF
    return pool[(key % pool.size).toInt()]
}

/** The line once it has landed. */
private fun afterLine(id: String, visit: Int, through: Int): String {
    val pool = listOf("There it is", "Did you see that?", "Every time", "Again?", "Called it?")
    val key = (id.hashCode().toLong() * 13L + visit) and 0x7FFFFFFF
    val line = pool[(key % pool.size).toInt()]
    // A run of them watched to the end, counted once it is a run.
    return if (through >= 3) "$line  ·  $through watched through" else line
}

/** From this share of the way to the payoff, the tension line shows. */
private const val TENSE_FROM = 0.72f

/** Travel that counts as a deliberate swipe. */
internal const val SWIPE_DP = 56f

/** Or a flick this fast, over at least [MIN_FLICK_DP]. */
internal const val FLICK_DP_PER_S = 800f
internal const val MIN_FLICK_DP = 12f

/** A rare run's chance, out of 256 visits. */
private const val RARE_IN_256 = 21

/** A remixed sim's slow turn, radians a second. */
private const val SPIN = 0.12f

/** Rule 1's figure: chrome fades after this long with no touch. */
internal const val CHROME_IDLE_MS = 2600L

/** How much closer the feed's camera is at a run's payoff. */
private const val FEED_LEAN = 0.08f

/** Seconds to ease back out after it. */
private const val FEED_LEAN_BACK = 1.5f

/** How far the chrome travels, as a share of the screen, before the swap. */
private const val LEAVE = 0.32f

/** Where the next sim's chrome comes in from, as a share of that. */
private const val ARRIVE = 0.45f

/** How dark the picture gets at the swap, so the cut happens unseen. */
private const val DIM = 0.92f

/** How long a payoff is held before the feed moves on by itself. */
private const val FEED_AUTO_HOLD_MS = 2500L
