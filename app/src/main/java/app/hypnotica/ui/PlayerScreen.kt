package app.hypnotica.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hypnotica.core.audio.Scale
import app.hypnotica.core.audio.Song
import app.hypnotica.core.audio.SoundPreset
import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Macro
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.ParamType
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.TouchEvent
import kotlinx.coroutines.delay

/**
 * The player: the sim fills the screen and the controls float over it, out of the
 * way until asked for.
 */
/** How long a finished run holds its last frame before going again. */
private const val RESOLVE_MS = 1800L

@Composable
fun PlayerScreen(state: AppState, surface: SimSurfaceView) {
    var overlayVisible by remember { mutableStateOf(true) }
    var uiTick by remember { mutableIntStateOf(0) }

    // When the run reported itself finished, or 0.
    var finishedAt by remember { mutableStateOf(0L) }

    // Rule 1: the sim is the interface. Chrome fades out when nobody is touching.
    LaunchedEffect(overlayVisible, state.controlsOpen) {
        if (overlayVisible && !state.controlsOpen) {
            delay(2600)
            overlayVisible = false
        }
    }

    // A run that ends should resolve and go again, not stop dead.
    //
    // SimCore.advance returns immediately once a sim reports finished, and only
    // the feed and TickTock ever set onFinished — so in the player the picture
    // simply froze, indefinitely, until the hero button was pressed. Measured,
    // n-queens finishes at 4.8 s of its declared 14 and pathfinding at 9.5 of
    // 24, so this was a still frame for most of the run's own length. The beat
    // before restarting is the resolve the design asks for: settle, breathe,
    // and the last frame is worth a screenshot.
    DisposableEffect(Unit) {
        state.engine.onFinished = {
            finishedAt = System.nanoTime()
        }
        onDispose { state.engine.onFinished = null }
    }
    LaunchedEffect(finishedAt) {
        if (finishedAt == 0L) return@LaunchedEffect
        delay(RESOLVE_MS)
        finishedAt = 0L
        state.restart(newSeed = true)
    }

    // Live counters come from the sim, which runs on the GL thread.
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            uiTick++
            // Not only while Evolve is on: the sound switches also need it, to
            // know which kinds of event this run has produced.
            state.tickLive()
        }
    }

    Box(Modifier.fillMaxSize().background(Tokens.Bg)) {
        SimCanvas(
            surface = surface,
            modifier = Modifier.fillMaxSize(),
            onTap = {
                overlayVisible = !overlayVisible
                if (!overlayVisible) state.controlsOpen = false
            },
            // No double-tap here either. Holding the first tap back by a
            // double-tap window to find out whether a second is coming puts a
            // quarter of a second between touching the sim and the controls
            // appearing — on the gesture this screen is mostly used through.
            // The ★ in the top bar favourites the sim, and TickTock is the one
            // screen where double-tap is worth that wait, because the tap there
            // only shows and hides the chrome.
            onLongPress = {
                overlayVisible = true
                state.controlsOpen = true
            },
            cameraMode = state.cameraDrag
        )

        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AnimatedVisibility(
                visible = overlayVisible,
                // Fade, not the default expand-and-shrink: growing the bar's
                // height clips the title and squashes the buttons on the way.
                enter = fadeIn(tween(Tokens.Base)),
                exit = fadeOut(tween(Tokens.Base))
            ) {
                TopBar(state, uiTick)
            }
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(
                visible = state.controlsOpen,
                enter = slideInVertically(tween(Tokens.Sheet, easing = FastOutSlowInEasing)) { it } +
                    fadeIn(tween(Tokens.Sheet)),
                exit = slideOutVertically(tween(Tokens.Sheet, easing = FastOutSlowInEasing)) { it } +
                    fadeOut(tween(Tokens.Fast))
            ) {
                ControlDrawer(state)
            }
            AnimatedVisibility(
                visible = overlayVisible,
                enter = fadeIn(tween(Tokens.Base)),
                exit = fadeOut(tween(Tokens.Base))
            ) {
                ActionBar(state, surface)
            }
        }
    }
}

@Composable
private fun TopBar(state: AppState, uiTick: Int) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlyphButton("‹") { state.screen = Screen.LIBRARY }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                state.currentDef.title,
                color = Tokens.TextPrimary,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val counters = remember(uiTick) { state.engine.core.sim.counters() }
            // A recipe that fails at run time reports through this same counter
            // list, which meant two things: it was hidden entirely when "Show
            // live counters" was off — the default is on, but it is a setting —
            // and it was printed by the same joiner as everything else, value
            // first, so it read "line 14: attempt to index a nil value recipe
            // error". It gets its own line, in red, whatever the setting says.
            val recipeError = counters.firstOrNull { it.first == "recipe error" }?.second
            val line = if (recipeError != null) {
                "Recipe stopped — $recipeError"
            } else if (state.recording) {
                val rec = remember(uiTick) { state.engine.recorder }
                // A recording that has run its length stops itself, rather than
                // sitting at "60s" until somebody notices. Done in an effect,
                // not here: stopRecording writes the project and the screen,
                // and writing snapshot state while the frame is being composed
                // can invalidate it twice over.
                val full = rec != null && rec.full
                LaunchedEffect(full) { if (full) state.stopRecording() }
                "\u25CF  recording  ${(rec?.seconds ?: 0f).toInt()}s"
            } else if (state.showCounters && counters.isNotEmpty()) {
                counters.joinToString("   ") { "${it.second} ${it.first}" }
            } else {
                state.currentDef.family
            }
            Text(
                line,
                color = if (state.recording || recipeError != null) Tokens.Danger
                    else Tokens.TextSecondary,
                // Tabular figures: a counter that changes every frame must not
                // make the line around it twitch. One line, for the same
                // reason at the scale above: a seven-figure comparison count
                // wrapped and grew the whole bar mid-run.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
            )
        }
        // The title column ran flush into the star, with 12 dp of air on its
        // other side.
        Spacer(Modifier.width(12.dp))
        GlyphButton(if (state.isFavourite(state.currentDef.id)) "★" else "☆") {
            state.toggleFavourite(state.currentDef.id)
        }
    }
}

@Composable
private fun ActionBar(state: AppState, surface: SimSurfaceView) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The hero gets more of the row, because it is the one control the
        // design calls "whatever this sim's one big action is" — and five of
        // the hero labels ("Restart the cycle", "Drop them again") did not fit
        // the share it had on a 360 dp phone and were cut mid-word.
        BigButton(if (state.paused) "Play" else "Pause", Modifier.weight(0.8f)) {
            state.togglePause()
        }
        BigButton(state.currentDef.heroLabel, Modifier.weight(1.4f), primary = true) {
            if (state.currentDef.heroRestarts) {
                state.restart(newSeed = true)
            } else {
                state.engine.onTouch(
                    TouchEvent(
                        TouchEvent.Kind.TAP,
                        uiRandom.nextFloat() * 0.6f - 0.3f,
                        uiRandom.nextFloat() * 0.6f - 0.3f
                    )
                )
            }
        }
        GlyphButton("≡", selected = state.controlsOpen) {
            state.controlsOpen = !state.controlsOpen
        }
    }
}

@Composable
private fun ControlDrawer(state: AppState) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Tokens.Surface.copy(alpha = 0.96f), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .heightIn(max = 420.dp)
            .padding(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Pill("Simple", selected = !state.advanced, Modifier.weight(1f)) { state.advanced = false }
            Pill("Advanced", selected = state.advanced, Modifier.weight(1f)) { state.advanced = true }
        }
        Spacer(Modifier.height(12.dp))

        if (state.advanced) {
            // The Laws tab goes where the Laws card goes. It used to be shown on
            // all fifty-seven sims while only eleven read a law, so two thirds
            // of the time it offered four sliders, nine pills and a force-law
            // box that the running sim could not respond to — and editing any
            // of them renamed the universe to "Custom" while the picture stayed
            // exactly the same.
            val tabs = remember(state.currentDef.id) {
                AdvancedTab.entries.filter {
                    it != AdvancedTab.LAWS || state.currentDef.usesLaws
                }
            }
            val selected = tabs.indexOf(state.advancedTab).coerceAtLeast(0)
            val shown = tabs[selected]
            ChipRow(tabs.map { it.title }, selected) { state.advancedTab = tabs[it] }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AdvancedPanel(state, shown)
                Spacer(Modifier.height(16.dp))
            }
        } else {
            val cards = visibleCards(state)
            // The highlighted chip and the panel below it are the same value.
            // Reading the panel from state.card instead let a sim with no laws
            // highlight the first chip while still drawing the laws panel,
            // because the card outlives the sim it was chosen on.
            val selected = cards.indexOf(state.card).coerceAtLeast(0)
            val shown = cards[selected]
            ChipRow(cards.map { it.title }, selected) { state.card = cards[it] }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // Crossfaded, with the drawer's height carried along: Sound is
                // much taller than Look, so switching used to swap the panel
                // and snap the drawer's height in the same frame.
                AnimatedContent(
                    targetState = shown,
                    transitionSpec = {
                        (fadeIn(tween(Tokens.Fast)) togetherWith fadeOut(tween(Tokens.Fast)))
                            .using(SizeTransform(clip = false) { _, _ -> tween(Tokens.Base) })
                    },
                    label = "simpleCard"
                ) { card ->
                    // A Column, because AnimatedContent's slot stacks what it
                    // is given the way a Box does, and every one of these emits
                    // a card's worth of siblings. Without it the whole of
                    // Simple mode drew on one line: the macro slider, its
                    // "Moves nodes, speed", the section label and the Nodes
                    // slider all at the same y, two thumbs on top of each other
                    // and the readouts overlapping in the corner.
                    Column {
                        when (card) {
                            Card.DATA -> CardControls(state, Card.DATA)
                            Card.MOTION -> MotionCard(state)
                            Card.LOOK -> LookCard(state)
                            Card.SOUND -> SoundCard(state)
                            Card.LAWS -> LawsCard(state)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

private fun visibleCards(state: AppState): List<Card> {
    val base = mutableListOf(Card.DATA, Card.MOTION, Card.LOOK, Card.SOUND)
    if (state.currentDef.usesLaws) base.add(Card.LAWS)
    return base
}

/** Renders every parameter that belongs on one card. */
/**
 * The hero button's own randomness.
 *
 * Not the sim's: that generator is seeded, stepped on the GL thread, and is
 * what makes a run reproducible from its seed — which replay links, Studio and
 * the exporter all depend on. Drawing from it here both raced the sim thread
 * and advanced the stream, so an exported clip no longer matched the screen.
 */
private val uiRandom = java.util.Random()

@Composable
private fun CardControls(state: AppState, card: Card, showEmpty: Boolean = true) {
    // Reading paramVersion is what makes a value change recompose this card.
    val version = state.paramVersion
    val params = remember(version, state.currentDef.id, state.advanced, card) {
        state.params().filter { it.card == card && (state.advanced || !it.advancedOnly) }
    }
    // A macro earns its place by reaching more than the sliders under it. One
    // that moves a single value which is already shown below, exactly, is the
    // same control stated vaguely — "How much: a lot" above a slider reading
    // "Trail 8,000" is strictly worse than the slider.
    // state.advanced belongs in these keys: `params` above is remembered with
    // it, and this closes over `params`, so without it a round trip through
    // Advanced could leave the two describing different parameter sets.
    val shown = remember(version, state.currentDef.id, state.advanced, card) {
        val exact = params.map { it.key }.toSet()
        state.macros(card).filter { it.targets.size > 1 || !exact.containsAll(it.targets.keys) }
    }
    if (shown.isNotEmpty() && !state.advanced) {
        shown.forEach { macro -> MacroControl(state, macro) }
        if (params.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            SectionLabel("Or set them one at a time")
        }
    }
    if (params.isEmpty()) {
        if (shown.isEmpty() && showEmpty) {
            Text(
                "Nothing to set here for this sim.",
                color = Tokens.TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        return
    }
    if (card == Card.DATA && state.currentDef.id == "math/equation") EquationPresets(state)
    params.forEach { p -> ParamControl(state, p) }
}

/**
 * One Simple-mode macro: a single slider that moves everything it points at.
 * The values it writes are the same ones Advanced mode shows exactly.
 */
@Composable
private fun MacroControl(state: AppState, macro: Macro) {
    // Same reason as ParamControl: the cached position is checked against the
    // parameter map's own revision, which is not snapshot state.
    val version = state.paramVersion + state.liveTick
    val position = remember(version, macro.key) { state.macroPosition(macro) }
    ValueSlider(
        label = macro.label,
        value = position,
        min = 0f,
        max = 1f,
        valueText = describeMacro(position),
        onCommit = { state.commitMacro(macro) }
    ) { state.setMacro(macro, it) }
    // Naming them beats counting them: "Moves beads, drop rate" tells you what
    // the one slider reaches, where "Moves 2 settings" told you nothing.
    val moves = remember(macro.key, state.currentDef.id) {
        val labels = state.params()
            .filter { macro.targets.containsKey(it.key) }
            .map { it.label.lowercase() }
        if (labels.isEmpty()) "" else "Moves " + labels.joinToString(", ")
    }
    if (moves.isNotEmpty()) {
        Text(
            moves,
            color = Tokens.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
}

/** Plain words, because a macro has no unit of its own. */
private fun describeMacro(position: Float): String = when {
    position < 0.12f -> "barely"
    position < 0.3f -> "a little"
    position < 0.55f -> "some"
    position < 0.78f -> "a lot"
    position < 0.94f -> "heaps"
    else -> "everything"
}


/**
 * The reason a parameter's control is inert right now, or null.
 *
 * Some values are taken over by another switch: optics overwrites its Angle on
 * every step while "Sweep the angle" is on. Saying so, and dimming the control,
 * is the difference between a setting the person can find and a slider that
 * looks broken.
 */
internal fun mutedReason(state: AppState, p: Param): String? {
    val owner = p.mutedBy ?: return null
    if (Math.round(state.paramValue(owner)) !in p.mutedAt) return null
    val ownerParam = state.params().firstOrNull { it.key == owner }
    val label = ownerParam?.label ?: owner
    // A switch takes a value over; a choice leaves it unused in that mode, and
    // saying "set by What the formulas mean" would explain nothing.
    val choice = ownerParam
        ?.takeIf { it.type == ParamType.CHOICE }
        ?.choices
        ?.getOrNull(Math.round(state.paramValue(owner)))
    return if (choice != null) "not used with \"$choice\"" else "set by $label"
}

@Composable
private fun ParamControl(state: AppState, p: Param) {
    // Subscribing to the version is what makes this row redraw. The values
    // themselves live in a concurrent map the sim thread reads every step, not
    // in snapshot state, so nothing in here recomposes on its own: the slider
    // moved under your finger and sprang straight back, because the value it
    // was told to show was still the old one.
    val version = state.paramVersion + state.liveTick
    val shown = remember(version, p.key) { state.paramValue(p.key) }
    val muted = remember(version, p.key) { mutedReason(state, p) }
    when (p.type) {
        ParamType.EXPR -> ExprParamField(state, p)

        ParamType.BOOL -> ToggleRow(p.label, shown >= 0.5f, enabled = muted == null) {
            state.setParamBool(p, it)
        }

        ParamType.CHOICE -> {
            SectionLabel(p.label)
            ChipRow(p.choices, Math.round(shown), enabled = muted == null) { idx ->
                state.setParamChoice(p, idx)
            }
        }

        else -> {
            val v = shown
            ValueSlider(
                label = p.label,
                value = v,
                min = p.min,
                max = p.max,
                logScale = p.logScale,
                valueText = muted ?: describe(p, v),
                enabled = muted == null,
                onCommit = { state.commitParam(p) }
            ) { nv -> state.setParam(p, nv) }
        }
    }
}

/** Plain words in Simple mode, exact numbers in Advanced. */
private fun describe(p: Param, v: Float): String {
    if (p.unit.isNotEmpty()) return formatValue(v) + " " + p.unit
    return formatValue(v)
}

@Composable
private fun MotionCard(state: AppState) {
    ValueSlider(
        label = "Speed",
        value = state.speed,
        min = 0.1f,
        max = 10f,
        logScale = true,
        valueText = String.format(java.util.Locale.US, "%.2fx", state.speed)
    ) { state.speed = it }
    // Simple mode's Laws card is gated on law knobs too, so these were
    // unreachable here for the same forty-seven sims.
    TimeControls(state)
    CardControls(state, Card.MOTION)
    Spacer(Modifier.height(4.dp))
    ToggleRow("Evolve", state.evolve, enabled = state.canEvolve) { state.evolve = it }
    if (!state.canEvolve) {
        Text(
            "Nothing on this sim drifts.",
            color = Tokens.TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
    }
    Text(
        "Lets the settings drift slowly while it plays, so a loop never settles.",
        color = Tokens.TextSecondary,
        style = MaterialTheme.typography.bodySmall
    )
    Spacer(Modifier.height(8.dp))
    SectionLabel("Record moves")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.recording) {
            Pill("Stop and edit", selected = true) { state.stopRecording() }
        } else {
            Pill("Start recording") { state.notice = null; state.startRecording() }
        }
    }
    NoticeLine(state.notice)
    Text(
        if (state.recording) {
            "Pinch, drag and twist the sim. Stopping opens it in Studio as keyframes."
        } else {
            "Captures your pinches, drags and twists, then hands them to Studio."
        },
        color = Tokens.TextSecondary,
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun LookCard(state: AppState) {
    SectionLabel("Palette")
    ChipRow(Palette.NAMES, Palette.NAMES.indexOf(state.paletteName)) {
        state.setPalette(Palette.NAMES[it])
    }
    ValueSlider(
        label = "Trails",
        value = state.trails,
        min = 0f,
        max = 0.96f,
        valueText = if (state.trails < 0.02f) "off" else formatValue(state.trails)
    ) { state.trails = it }
    ToggleRow("Drag pans the view", state.cameraDrag) { state.cameraDrag = it }
    Text(
        "On, one finger moves the camera. Off, it reaches into the sim. Two " +
            "fingers always pan, pinch zooms and a twist rotates.",
        color = Tokens.TextSecondary,
        style = MaterialTheme.typography.bodySmall
    )
    CardControls(state, Card.LOOK)
}

@Composable
private fun SoundCard(state: AppState) {
    val mapping = state.engine.mapping
    // Reading the version is what makes these sliders show their own effect:
    // SoundMapping holds plain fields for the audio thread, not snapshot state.
    val version = state.soundVersion

    SectionLabel("Sound")
    ChipRow(SoundPreset.NAMES, SoundPreset.NAMES.indexOf(state.soundPresetName)) {
        state.setSoundPreset(SoundPreset.NAMES[it])
    }
    Text(
        "A preset sets the scale, the pitch, the range and the decay together.",
        color = Tokens.TextSecondary,
        style = MaterialTheme.typography.bodySmall
    )
    SectionLabel("Scale")
    // A chip row is as simple as a control gets, and the scale is the thing a
    // person is most likely to want to change about the sound: it was behind
    // Advanced, which left Simple mode with a preset and nothing else.
    ChipRow(Scale.NAMES, remember(version) {
        Scale.NAMES.indexOf(mapping.scale.name)
    }) {
        mapping.scale = Scale.byIndex(it)
        state.soundChanged()
    }
    SectionLabel("Song mode")
    ChipRow(Song.NAMES, state.songIndex) { state.setSong(it) }

    SectionLabel("What makes a sound")
    // Pills rather than nine toggle rows: tapping one silences that kind of
    // event, which is a thing people want on a long run and was Advanced-only.
    val kinds = remember { EventKind.entries.toList() }
    Text(
        "Dimmed kinds are ones this sim does not make.",
        color = Tokens.TextSecondary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    Column {
        kinds.chunked(3).forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { kind ->
                    val on = remember(version, kind) { mapping.isEnabled(kind) }
                    // Same as the Advanced tab: most sims make two to six of
                    // the nine kinds, and the rest were live-looking pills that
                    // silenced nothing.
                    val made = remember(version, kind, state.currentDef.id, state.liveTick) {
                        state.engine.core.events.hasEmitted(kind)
                    }
                    Pill(
                        kind.name.lowercase().replaceFirstChar { it.uppercase() },
                        selected = on,
                        modifier = Modifier.weight(1f),
                        enabled = made
                    ) {
                        mapping.setEnabled(kind, !on)
                        state.soundChanged()
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }

    // Two sliders over five values. The scale, the base pitch, the range, the
    // decay and the note ceiling were Advanced-only, which left Simple mode
    // with a preset and nothing else — and this is exactly the shape the brief
    // asked for: more capability, controlled more simply.
    val brightness = remember(version) {
        Macro.positionOf(60f..880f, 523f, mapping.baseHz, log = true)
    }
    ValueSlider(
        "Brightness", brightness, 0f, 1f,
        valueText = String.format(java.util.Locale.US, "%.0f Hz, %.1f octaves", mapping.baseHz, mapping.octaves)
    ) { t ->
        mapping.baseHz = Macro.valueAt(60f..880f, 523f, t, log = true)
        mapping.octaves = Macro.valueAt(1f..6f, 3f, t)
        state.soundChanged()
    }
    val busyness = remember(version) {
        Macro.positionOf(4f..400f, 60f, mapping.maxNotesPerSecond, log = true)
    }
    ValueSlider(
        "Busyness", busyness, 0f, 1f,
        valueText = String.format(java.util.Locale.US, "%.0f notes/s, %.2f s decay", mapping.maxNotesPerSecond, mapping.decay)
    ) { t ->
        mapping.maxNotesPerSecond = Macro.valueAt(4f..400f, 60f, t, log = true)
        // Busier means shorter notes, or they pile into a drone.
        mapping.decay = Macro.valueAt(2f..0.06f, 0.6f, t, log = true)
        state.soundChanged()
    }

    CardControls(state, Card.SOUND, showEmpty = false)
}

@Composable
private fun LawsCard(state: AppState) {
    LawsControls(state, advanced = false)
    Text(
        "Every physics sim reads these rules, so changing the universe changes them all.",
        color = Tokens.TextSecondary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp)
    )
    CardControls(state, Card.LAWS, showEmpty = false)
}
