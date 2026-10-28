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
import app.hypnotica.sims.SimRegistry
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

/**
 * Home: one sim, full screen, swipe up for the next.
 *
 * Nothing loads here. The next sim is already described in the registry and
 * starts the instant the swipe lands, which is what keeps motion within a second
 * of every entry point.
 */
@Composable
fun FeedScreen(state: AppState, surface: SimSurfaceView) {
    val order = remember { SimRegistry.all.shuffled(java.util.Random(7)) }
    // Start wherever the player actually is, so arriving here does not yank the
    // sim out from under the person, and so the sim restored at launch is the
    // one they were watching.
    LaunchedEffect(Unit) {
        val here = order.indexOfFirst { it.id == state.currentDef.id }
        if (here >= 0 && here != state.feedIndex) state.feedIndex = here
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
    DisposableEffect(Unit) {
        state.engine.onFinished = { finishedAt = System.nanoTime() }
        onDispose { state.engine.onFinished = null }
    }
    LaunchedEffect(finishedAt) {
        if (finishedAt == 0L) return@LaunchedEffect
        delay(resolveDelayMs(state.engine))
        finishedAt = 0L
        state.restart(newSeed = true)
    }

    // Keyed on the sim rather than Unit: re-entering the feed used to reload,
    // which threw away the run, the look and the person's place in it.
    DisposableEffect(index) {
        if (order[index].id != state.currentDef.id) state.load(order[index])
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
        hint = false
        scope.launch {
            val out = if (step > 0) -height * LEAVE else height * LEAVE
            drag.animateTo(out, tween(Tokens.Fast, easing = FastOutLinearInEasing))
            // Read now, not when the gesture began: the handler used to hold
            // the index from the first time it ran, so every swipe after the
            // first computed "next" from the sim the feed opened on — and the
            // second swipe landed on the sim already showing.
            state.feedIndex = (state.feedIndex + step + order.size) % order.size
            state.feedVisits++
            drag.snapTo(-out * ARRIVE)
            drag.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
        }
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
                detectVerticalDragGestures(
                    onDragStart = { travelled = 0f },
                    onDragCancel = {
                        travelled = 0f
                        scope.launch { drag.animateTo(0f, spring()) }
                    },
                    onDragEnd = {
                        // One flick moves one sim, however far it went.
                        when {
                            travelled <= -SWIPE -> go(+1)
                            travelled >= SWIPE -> go(-1)
                            else -> scope.launch {
                                drag.animateTo(0f, spring(dampingRatio = 0.7f))
                            }
                        }
                        travelled = 0f
                    }
                ) { change, dragAmount ->
                    change.consume()
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
                        style = MaterialTheme.typography.displaySmall,
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
                        Text(
                            state.currentDef.title,
                            color = Tokens.TextPrimary,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (hint) "${state.currentDef.shelf.title}  ·  swipe up · hold for controls"
                            else state.currentDef.shelf.title,
                            color = Tokens.TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
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
                    GlyphButton("↻") { state.restart(newSeed = true) }
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

/** Pixels of travel that count as a deliberate swipe. */
private const val SWIPE = 90f

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
