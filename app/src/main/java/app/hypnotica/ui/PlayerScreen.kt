package app.hypnotica.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.hypnotica.core.audio.Song
import app.hypnotica.core.audio.SoundPreset
import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Macro
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.ParamType
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.TouchEvent
import kotlinx.coroutines.delay

/**
 * The player: the sim fills the screen and the controls float over it, out of the
 * way until asked for.
 */
@Composable
fun PlayerScreen(state: AppState, surface: SimSurfaceView) {
    var overlayVisible by remember { mutableStateOf(true) }
    var uiTick by remember { mutableIntStateOf(0) }

    // Rule 1: the sim is the interface. Chrome fades out when nobody is touching.
    LaunchedEffect(overlayVisible, state.controlsOpen) {
        if (overlayVisible && !state.controlsOpen) {
            delay(2600)
            overlayVisible = false
        }
    }

    // Live counters come from the sim, which runs on the GL thread.
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            uiTick++
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
            onDoubleTap = { state.toggleFavourite(state.currentDef.id) },
            onLongPress = {
                overlayVisible = true
                state.controlsOpen = true
            }
        )

        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            AnimatedVisibility(visible = overlayVisible) {
                TopBar(state, uiTick)
            }
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(
                visible = state.controlsOpen,
                enter = slideInVertically { it },
                exit = slideOutVertically { it }
            ) {
                ControlDrawer(state)
            }
            AnimatedVisibility(visible = overlayVisible) {
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
                style = MaterialTheme.typography.titleLarge
            )
            val counters = remember(uiTick) { state.engine.core.sim.counters() }
            val line = if (state.recording) {
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
                color = if (state.recording) Tokens.Danger else Tokens.TextSecondary,
                // Tabular figures: a counter that changes every frame must not
                // make the line around it twitch.
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
            )
        }
        GlyphButton(if (state.isFavourite(state.currentDef.id)) "★" else "☆") {
            state.toggleFavourite(state.currentDef.id)
        }
    }
}

@Composable
private fun ActionBar(state: AppState, surface: SimSurfaceView) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BigButton(if (state.paused) "Play" else "Pause", Modifier.weight(1f)) { state.togglePause() }
        BigButton(state.currentDef.heroLabel, Modifier.weight(1.4f), primary = true) {
            if (state.currentDef.heroRestarts) {
                state.restart(newSeed = true)
            } else {
                state.engine.onTouch(
                    TouchEvent(
                        TouchEvent.Kind.TAP,
                        state.engine.core.env.rng.range(-0.3f, 0.3f),
                        state.engine.core.env.rng.range(-0.3f, 0.3f)
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
            .padding(16.dp)
            .heightIn(max = 420.dp)
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
                when (shown) {
                    Card.DATA -> CardControls(state, Card.DATA)
                    Card.MOTION -> MotionCard(state)
                    Card.LOOK -> LookCard(state)
                    Card.SOUND -> SoundCard(state)
                    Card.LAWS -> LawsCard(state)
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
@Composable
private fun CardControls(state: AppState, card: Card) {
    // Reading paramVersion is what makes a value change recompose this card.
    val version = state.paramVersion
    val params = remember(version, state.currentDef.id, state.advanced, card) {
        state.params().filter { it.card == card && (state.advanced || !it.advancedOnly) }
    }
    val macros = remember(state.currentDef.id, card) { state.macros(card) }
    if (macros.isNotEmpty() && !state.advanced) {
        macros.forEach { macro -> MacroControl(state, macro) }
        if (params.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            SectionLabel("Or set them one at a time")
        }
    }
    if (params.isEmpty()) {
        if (macros.isEmpty()) {
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
    val position = state.macroPosition(macro)
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
            modifier = Modifier.padding(bottom = 6.dp)
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

@Composable
private fun ParamControl(state: AppState, p: Param) {
    when (p.type) {
        ParamType.EXPR -> ExprParamField(state, p)

        ParamType.BOOL -> ToggleRow(p.label, state.paramValue(p.key) >= 0.5f) {
            state.setParamBool(p, it)
        }

        ParamType.CHOICE -> {
            SectionLabel(p.label)
            ChipRow(p.choices, Math.round(state.paramValue(p.key))) { idx ->
                state.setParamChoice(p, idx)
            }
        }

        else -> {
            val v = state.paramValue(p.key)
            ValueSlider(
                label = p.label,
                value = v,
                min = p.min,
                max = p.max,
                logScale = p.logScale,
                valueText = describe(p, v),
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
        valueText = String.format("%.2fx", state.speed)
    ) { state.speed = it }
    CardControls(state, Card.MOTION)
    Spacer(Modifier.height(4.dp))
    ToggleRow("Evolve", state.evolve) { state.evolve = it }
    Text(
        "Lets the settings drift slowly while it plays, so a loop never settles.",
        color = Tokens.TextSecondary,
        style = MaterialTheme.typography.bodySmall
    )
    Spacer(Modifier.height(10.dp))
    SectionLabel("Record moves")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.recording) {
            Pill("Stop and edit", selected = true) { state.stopRecording() }
        } else {
            Pill("Start recording") { state.notice = null; state.startRecording() }
        }
    }
    state.notice?.let {
        Text(
            it,
            color = Tokens.Accent,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
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
    ChipRow(Palette.NAMES, Palette.NAMES.indexOf(state.paletteName).coerceAtLeast(0)) {
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
    ChipRow(SoundPreset.NAMES, SoundPreset.NAMES.indexOf(state.soundPresetName).coerceAtLeast(0)) {
        state.setSoundPreset(SoundPreset.NAMES[it])
    }
    SectionLabel("Song mode")
    ChipRow(Song.NAMES, state.songIndex) { state.setSong(it) }

    // Two sliders over five values. The scale, the base pitch, the range, the
    // decay and the note ceiling were Advanced-only, which left Simple mode
    // with a preset and nothing else — and this is exactly the shape the brief
    // asked for: more capability, controlled more simply.
    val brightness = remember(version) {
        Macro.positionOf(60f..880f, 523f, mapping.baseHz, log = true)
    }
    ValueSlider(
        "Brightness", brightness, 0f, 1f,
        valueText = String.format("%.0f Hz, %.1f octaves", mapping.baseHz, mapping.octaves)
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
        valueText = String.format("%.0f notes/s, %.2f s decay", mapping.maxNotesPerSecond, mapping.decay)
    ) { t ->
        mapping.maxNotesPerSecond = Macro.valueAt(4f..400f, 60f, t, log = true)
        // Busier means shorter notes, or they pile into a drone.
        mapping.decay = Macro.valueAt(2f..0.06f, 0.6f, t, log = true)
        state.soundChanged()
    }

    CardControls(state, Card.SOUND)
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
    CardControls(state, Card.LAWS)
}
