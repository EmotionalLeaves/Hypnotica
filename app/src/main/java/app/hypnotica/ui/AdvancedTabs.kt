package app.hypnotica.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hypnotica.core.audio.Scale
import app.hypnotica.core.audio.Song
import app.hypnotica.core.audio.SoundPreset
import app.hypnotica.core.laws.Laws
import app.hypnotica.core.laws.Space
import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.ParamType
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.LawKnob

/** The tabs Advanced mode offers, in order. */
enum class AdvancedTab(val title: String) {
    DATA("Data"), PARAMS("Params"), LAWS("Laws"), SOUND("Sound"), CODE("Code")
}

@Composable
fun AdvancedPanel(state: AppState, tab: AdvancedTab) {
    // Crossfaded, and the drawer's height is taken along with it.
    //
    // Tapping Look -> Sound replaced the whole panel in a single frame, and
    // because the drawer is content-sized the drawer's height snapped at the
    // same instant — under the finger that was still on the chip row above it.
    // The SizeTransform is the half that matters.
    AnimatedContent(
        targetState = tab,
        transitionSpec = {
            (fadeIn(tween(Tokens.Fast)) togetherWith fadeOut(tween(Tokens.Fast)))
                .using(SizeTransform(clip = false) { _, _ -> tween(Tokens.Base) })
        },
        label = "advancedTab"
    ) { shown ->
        when (shown) {
            AdvancedTab.DATA -> DataTab(state)
            AdvancedTab.PARAMS -> ParamsTab(state)
            AdvancedTab.LAWS -> LawsTab(state)
            AdvancedTab.SOUND -> SoundTab(state)
            AdvancedTab.CODE -> CodeTab(state)
        }
    }
}

@Composable
private fun DataTab(state: AppState) {
    val version = state.paramVersion
    val params = remember(version, state.currentDef.id) {
        state.params().filter { it.card == Card.DATA }
    }
    if (params.isEmpty()) {
        Text("This sim builds its own data.", color = Tokens.TextSecondary, style = MaterialTheme.typography.bodySmall)
        return
    }
    Text(
        "What the run is built from. These apply on the next run, so the sim is " +
            "not rebuilt under your finger.",
        color = Tokens.TextSecondary,
        style = MaterialTheme.typography.bodySmall
    )
    Spacer(Modifier.height(8.dp))
    if (state.currentDef.id == "math/equation") EquationPresets(state)
    params.forEach { ExactParamRow(state, it) }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("Re-run same seed") { state.restart(newSeed = false) }
        Pill("New seed") { state.restart(newSeed = true) }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "seed ${state.engine.core.seed}",
        color = Tokens.TextSecondary,
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun ParamsTab(state: AppState) {
    val version = state.paramVersion
    val params = remember(version, state.currentDef.id) { state.params() }
    Text(
        "Every value, exactly. A field also takes a formula such as 1 + sin(t).",
        color = Tokens.TextSecondary,
        style = MaterialTheme.typography.bodySmall
    )
    Spacer(Modifier.height(8.dp))
    params.forEach { ExactParamRow(state, it) }

    // The five controls that belong to the run rather than to the sim. They
    // lived only on the Simple cards, so the dual interface lost capability in
    // both directions: an advanced user had to switch to Simple to change the
    // palette and switch back again.
    Spacer(Modifier.height(16.dp))
    SectionLabel("The run")
    ValueSlider(
        "Speed", state.speed, 0.1f, 10f, logScale = true,
        valueText = String.format(java.util.Locale.US, "%.2fx", state.speed)
    ) { state.speed = it }
    ValueSlider(
        "Trails", state.trails, 0f, 0.96f,
        valueText = if (state.trails < 0.02f) "none" else String.format(java.util.Locale.US, "%.0f%%", state.trails * 100f)
    ) { state.trails = it }
    TimeControls(state)
    SectionLabel("Palette")
    ChipRow(Palette.NAMES, Palette.NAMES.indexOf(state.paletteName)) {
        state.setPalette(Palette.NAMES[it])
    }
    Spacer(Modifier.height(8.dp))
    ToggleRow("Evolve", state.evolve, enabled = state.canEvolve) { state.evolve = it }
    if (!state.canEvolve) {
        Text(
            "Nothing on this sim drifts.",
            color = Tokens.TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
    }
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.recording) {
            Pill("Stop and edit", selected = true) { state.stopRecording() }
        } else {
            Pill("Record moves") { state.notice = null; state.startRecording() }
        }
    }
    NoticeLine(state.notice)
}

/**
 * Time speed and direction, which belong to every sim.
 *
 * These were on the Laws tab, and that tab is hidden for any sim with no law
 * knobs — forty-seven of the fifty-eight. But SimCore applies both of these
 * globally, to everything, so they were working controls that most of the app
 * could not reach. The sim written specifically so that reversing time reverses
 * the picture was one of the ones that could not reverse it.
 */
@Composable
internal fun TimeControls(state: AppState) {
    val laws = state.laws
    ValueSlider(
        "Time speed", laws.timeScale, 0.05f, 4f, logScale = true,
        valueText = String.format(java.util.Locale.US, "%.2fx", laws.timeScale)
    ) { v -> state.editLaws { it.copy(timeScale = v) } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("Reverse time", selected = laws.timeDirection < 0f) {
            state.editLaws { it.copy(timeDirection = if (it.timeDirection < 0f) 1f else -1f) }
        }
    }
}

/** One parameter with its slider, its exact value and a formula box. */
@Composable
private fun ExactParamRow(state: AppState, p: Param) {
    // See ParamControl in PlayerScreen: the parameter values are not snapshot
    // state, so without reading the version this row never redraws and every
    // slider on the Params tab springs back when you let go.
    val version = state.paramVersion + state.liveTick
    val value = remember(version, p.key) { state.paramValue(p.key) }
    val muted = remember(version, p.key) { mutedReason(state, p) }
    when (p.type) {
        ParamType.EXPR -> ExprParamField(state, p)
        ParamType.BOOL -> ToggleRow(p.label, value >= 0.5f, enabled = muted == null) {
            state.setParamBool(p, it)
        }
        ParamType.CHOICE -> {
            SectionLabel(p.label)
            ChipRow(p.choices, Math.round(value), enabled = muted == null) {
                state.setParamChoice(p, it)
            }
        }
        else -> {
            ValueSlider(
                label = p.label,
                value = value,
                min = p.min,
                max = p.max,
                logScale = p.logScale,
                valueText = muted
                    ?: (formatValue(value) + (if (p.unit.isNotEmpty()) " " + p.unit else "")),
                enabled = muted == null,
                onCommit = { state.commitParam(p) }
            ) { state.setParam(p, it) }
            // Not offered for a value the run only reads while being built: a
            // formula in t cannot mean anything there, and it silently did
            // nothing.
            if (!state.needsRestart(p)) FormulaField(state, p)
        }
    }
}

@Composable
private fun FormulaField(state: AppState, p: Param) {
    val existing = state.engine.core.params.expressionSource(p.key)
    var text by remember(p.key, state.currentDef.id) { mutableStateOf(existing ?: "") }
    var ok by remember(p.key, state.currentDef.id) { mutableStateOf(true) }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("f(t)", color = Tokens.TextSecondary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .weight(1f)
                .height(36.dp)
                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                .border(
                    1.dp,
                    if (ok) Color.White.copy(alpha = 0.1f) else Tokens.Danger,
                    RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (text.isEmpty()) {
                Text(
                    "constant",
                    color = Tokens.TextSecondary.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { next ->
                    text = next
                    ok = state.setParamFormula(p, next)
                },
                singleLine = true,
                textStyle = TextStyle(
                    color = if (ok) Tokens.TextPrimary else Tokens.Danger,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                ),
                cursorBrush = SolidColor(Tokens.Accent),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun LawsTab(state: AppState) {
    LawsControls(state, advanced = true)
}

/**
 * The Laws card and the Laws tab are the same controls at two levels of detail:
 * Simple gets universe tiles and four big sliders, Advanced adds the rest and the
 * force-law formula.
 */
@Composable
fun LawsControls(state: AppState, advanced: Boolean) {
    val laws = state.laws
    // Only what the sim in front of you actually reads. The card used to be
    // identical everywhere, so on the pendulum wave six of these controls and
    // the force-law box did nothing at all while each one renamed the universe
    // to "Custom": a menu promising what the code does not do. Time is the
    // exception — the engine applies it to every sim.
    val knobs = state.currentDef.lawKnobs
    fun has(knob: LawKnob) = knobs.contains(knob)

    SectionLabel("Universe")
    // Editing any law renames the universe "Custom"; without a chip for it the
    // row would simply show nothing selected.
    // Only presets this sim would actually show a difference for. Offering the
    // other twelve was the last ungated control on this tab.
    val presetNames = remember(state.currentDef.id) {
        val ours = Laws.PRESETS.first { it.name == Laws.OUR_UNIVERSE.name }
        Laws.PRESETS.filter { it.name == ours.name || it.differsFor(ours, knobs) }.map { it.name }
    }
    val names = remember(laws.name) {
        if (presetNames.contains(laws.name)) presetNames else presetNames + laws.name
    }
    ChipRow(names, names.indexOf(laws.name)) {
        if (it < presetNames.size) state.setLaws(presetNames[it])
    }

    if (has(LawKnob.GRAVITY)) {
        ValueSlider("Gravity", laws.gravity, 0f, 40f, valueText = gravityWord(laws.gravity)) { v ->
            state.editLaws { it.copy(gravity = v) }
        }
    }
    if (has(LawKnob.BOUNCE)) {
        ValueSlider(
            "Bounciness", laws.restitution, 0f, 1.3f,
            valueText = bounceWord(laws.restitution, laws.conserveEnergy)
        ) { v ->
            state.editLaws { it.copy(restitution = v) }
        }
    }
    if (has(LawKnob.FRICTION)) {
        ValueSlider("Friction", laws.friction, 0f, 1f, valueText = frictionWord(laws.friction)) { v ->
            state.editLaws { it.copy(friction = v) }
        }
    }
    SectionLabel("Break a law")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (has(LawKnob.FRICTION)) {
            Pill("No friction", selected = laws.friction <= 0.0001f) {
                state.editLaws { it.copy(friction = if (it.friction <= 0.0001f) 0.02f else 0f) }
            }
        }
        if (has(LawKnob.PAIRWISE_FORCE)) {
            Pill("Negative mass", selected = laws.massSign < 0f) {
                state.editLaws { it.copy(massSign = -it.massSign) }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (has(LawKnob.SPACE)) {
            Pill("Wrap space", selected = laws.space == Space.TORUS) {
                state.editLaws {
                    it.copy(space = if (it.space == Space.TORUS) Space.BOX else Space.TORUS)
                }
            }
        }
        if (has(LawKnob.PAIRWISE_FORCE)) {
            Pill("Repulsive gravity", selected = laws.gravityConstant < 0f) {
                state.editLaws { it.copy(gravityConstant = -it.gravityConstant) }
            }
        }
        if (has(LawKnob.GRAVITY_DIRECTION)) {
            Pill("Upside-down", selected = laws.gravityDirY < 0f) {
                state.editLaws { it.copy(gravityDirY = -it.gravityDirY) }
            }
        }
    }
    if (has(LawKnob.GRAVITY_DIRECTION)) {
        Spacer(Modifier.height(8.dp))
        // Two sims read this and nothing could ever set it, so sideways gravity
        // was a field in the table with no way in.
        ValueSlider(
            "Gravity tilt", laws.gravityDirX, -1f, 1f,
            valueText = tiltWord(laws.gravityDirX)
        ) { v ->
            state.editLaws { it.copy(gravityDirX = v) }
        }
    }
    if (has(LawKnob.CONTACT) || has(LawKnob.BOUNCE)) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (has(LawKnob.CONTACT)) {
                Pill("Sticky", selected = laws.sticky) {
                    state.editLaws { it.copy(sticky = !it.sticky, passThrough = false) }
                }
                Pill("Pass through", selected = laws.passThrough) {
                    state.editLaws { it.copy(passThrough = !it.passThrough, sticky = false) }
                }
            }
            if (has(LawKnob.BOUNCE)) {
                Pill("Free energy", selected = !laws.conserveEnergy) {
                    state.editLaws { it.copy(conserveEnergy = !it.conserveEnergy) }
                }
            }
        }
    }

    if (has(LawKnob.CHARGE)) {
        Spacer(Modifier.height(8.dp))
        ValueSlider(
            "Charge strength",
            laws.electricStrength,
            0.05f,
            8f,
            logScale = true,
            valueText = String.format(java.util.Locale.US, "%.2fx", laws.electricStrength)
        ) { v -> state.editLaws { it.copy(electricStrength = v) } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Like charges attract", selected = laws.chargeSign < 0f) {
                state.editLaws { it.copy(chargeSign = -it.chargeSign) }
            }
        }
    }

    // The speed of light and the shape of the force law belong to the sims that
    // read them, so Simple mode gets them there rather than not at all: the
    // brief asked for a way to change the laws of physics, and dialling 1/r^1.5
    // is that act.
    if (has(LawKnob.LIGHT_SPEED)) {
        ValueSlider("Speed of light", laws.lightSpeed, 0.5f, 1e6f, logScale = true,
            valueText = if (laws.lightSpeed > 1e5f) "unlimited"
            else String.format(java.util.Locale.US, "%.1f", laws.lightSpeed)) { v ->
            state.editLaws { it.copy(lightSpeed = v) }
        }
    }
    if (has(LawKnob.PAIRWISE_FORCE)) {
        // A typed force law replaces the whole pairwise force, so the falloff
        // is not read at all while one is set. The slider still moved and
        // renamed the universe, and the orbits did not change. The constant
        // survives, because an expression can reference g.
        val typedForce = laws.forceExpr != null
        ValueSlider("Force falloff", laws.falloff, 0.5f, 4f,
            enabled = !typedForce,
            valueText = if (typedForce) "set by the force law below"
                else String.format(java.util.Locale.US, "1/r^%.2f", laws.falloff)) { v ->
            state.editLaws { it.copy(falloff = v) }
        }
        ValueSlider(
            if (typedForce) "Gravitational constant (g in the force law)"
            else "Gravitational constant",
            laws.gravityConstant, -3f, 3f
        ) { v ->
            state.editLaws { it.copy(gravityConstant = v) }
        }
    }

    // A typed formula is the one thing that genuinely needs the exact controls.
    if (!advanced || !has(LawKnob.PAIRWISE_FORCE)) return

    SectionLabel("Force law")
    var force by remember(laws.name) { mutableStateOf(laws.forceExpr ?: "") }
    var forceOk by remember(laws.name) { mutableStateOf(true) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .border(1.dp, if (forceOk) Color.White.copy(alpha = 0.1f) else Tokens.Danger, RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (force.isEmpty()) {
            Text(
                "g * m1 * m2 / r^2",
                color = Tokens.TextSecondary.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        BasicTextField(
            value = force,
            onValueChange = { next ->
                force = next
                if (next.isBlank()) {
                    forceOk = true
                    state.editLaws { it.copy(forceExpr = null) }
                } else {
                    forceOk = app.hypnotica.core.laws.Expr.compileOrNull(next) != null
                    if (forceOk) state.editLaws { it.copy(forceExpr = next) }
                }
            },
            singleLine = true,
            textStyle = TextStyle(
                color = if (forceOk) Tokens.TextPrimary else Tokens.Danger,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp
            ),
            cursorBrush = SolidColor(Tokens.Accent),
            modifier = Modifier.fillMaxWidth()
        )
    }
    Text(
        "Variables: r, m1, m2, g",
        color = Tokens.TextSecondary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun SoundTab(state: AppState) {
    val mapping = state.engine.mapping
    // SoundMapping holds plain fields, not snapshot state, so nothing here
    // recomposes on its own: these four sliders used to show their launch
    // values for ever while the sound changed underneath them. Reading the
    // version first makes every change redraw the readouts, and a preset tap
    // — which overwrites pitch, range, decay and scale — now moves them too.
    val version = state.soundVersion

    SectionLabel("Preset")
    ChipRow(SoundPreset.NAMES, SoundPreset.NAMES.indexOf(state.soundPresetName)) {
        state.setSoundPreset(SoundPreset.NAMES[it])
    }
    // A song supplies its own notes, so the scale, the base pitch and the range
    // are not consulted at all while one is playing. All three sat directly
    // under the Song chips looking fully live, which is the most reachable
    // cluster of dead controls in the app: pick "Rising", drag Base pitch from
    // 262 Hz to 700 Hz, and the sound is identical.
    val songOn = remember(version, state.songIndex) { mapping.song != null }
    val songNote = "set by Song mode"

    SectionLabel("Scale")
    ChipRow(
        Scale.NAMES,
        remember(version) { Scale.NAMES.indexOf(mapping.scale.name) },
        enabled = !songOn
    ) {
        mapping.scale = Scale.byIndex(it)
        state.soundChanged()
    }
    SectionLabel("Song mode")
    ChipRow(Song.NAMES, state.songIndex) { state.setSong(it) }

    ValueSlider("Base pitch", mapping.baseHz, 60f, 880f, logScale = true,
        enabled = !songOn,
        valueText = if (songOn) songNote
            else String.format(java.util.Locale.US, "%.0f Hz", mapping.baseHz)) {
        mapping.baseHz = it
        state.soundChanged()
    }
    // The safety ceiling caps this well below the slider's own top, and the
    // readout used to claim the number it could not deliver.
    val fits = remember(version) { mapping.octavesThatFit() }
    ValueSlider("Range", mapping.octaves, 1f, 6f,
        enabled = !songOn,
        valueText = when {
            songOn -> songNote
            // Short enough to sit beside the label on a 360 dp phone: the long
            // form ran to about 350 dp of a 332 dp row.
            mapping.octaves > fits + 0.05f -> String.format(
                java.util.Locale.US, "%.1f of %.1f — pitch caps it",
                mapping.octaves, fits
            )
            else -> String.format(java.util.Locale.US, "%.1f octaves", mapping.octaves)
        }) {
        mapping.octaves = it
        state.soundChanged()
    }
    ValueSlider("Decay", mapping.decay, 0.03f, 2f, logScale = true,
        valueText = String.format(java.util.Locale.US, "%.2f s", mapping.decay)) {
        mapping.decay = it
        state.soundChanged()
    }
    ValueSlider("Notes per second", mapping.maxNotesPerSecond, 4f, 400f, logScale = true,
        valueText = String.format(java.util.Locale.US, "%.0f", mapping.maxNotesPerSecond)) {
        mapping.maxNotesPerSecond = it
        state.soundChanged()
    }

    SectionLabel("Which events make a sound")
    // Said once above the group rather than appended to all nine rows, which
    // was both repetitive and wider than a small phone at this text size.
    Text(
        "Dimmed kinds are ones this sim does not make.",
        color = Tokens.TextSecondary,
        style = MaterialTheme.typography.bodySmall
    )
    Column {
        // Dimmed rather than hidden for the kinds this sim does not make, so
        // the list does not reflow as a run produces its first of each. Every
        // sim was offered all nine; most make two to six, so on a sorting sim
        // five of these switches flipped and changed nothing.
        val events = state.engine.core.events
        EventKind.entries.forEach { kind ->
            val on = remember(version, kind) { mapping.isEnabled(kind) }
            val made = remember(version, kind, state.currentDef.id, state.liveTick) {
                events.hasEmitted(kind)
            }
            ToggleRow(
                kind.name.lowercase().replaceFirstChar { it.uppercase() },
                on,
                enabled = made
            ) {
                mapping.setEnabled(kind, it)
                state.soundChanged()
            }
        }
    }
}

@Composable
private fun CodeTab(state: AppState) {
    val version = state.paramVersion
    // Everything stateJson reports has to be in these keys. It also prints the
    // laws, the palette, the trails and the sound preset, none of which bump
    // paramVersion — so the panel that calls itself "everything needed to
    // replay it" went on saying "Our universe" after you switched to Moon.
    val text = remember(
        version, state.currentDef.id, state.laws, state.paletteName,
        state.trails, state.soundPresetName, state.soundVersion
    ) { state.stateJson() }
    val clipboard = LocalClipboardManager.current
    var notice by remember { mutableStateOf<String?>(null) }
    // A notice is about the run it was written for. Leaving it up after the run
    // changed meant "Loaded Polyrhythm" sitting under a Boids recipe.
    LaunchedEffect(text) { notice = null }
    Text(
        "This run, exactly. Copy it to keep it or to send it to someone, and " +
            "paste one in to watch theirs — same sim, same seed, same picture.",
        color = Tokens.TextSecondary,
        style = MaterialTheme.typography.bodySmall
    )
    Spacer(Modifier.height(8.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(
            text,
            color = Tokens.TextPrimary,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BigButton("Copy this run", Modifier.weight(1f)) {
            clipboard.setText(AnnotatedString(text))
            notice = "Copied. It is a few lines of text — paste it anywhere."
        }
        BigButton("Paste a run", Modifier.weight(1f)) {
            val pasted = clipboard.getText()?.text
            notice = when {
                pasted.isNullOrBlank() -> "There is nothing on the clipboard to paste."
                else -> state.applyRun(pasted) ?: "Loaded ${state.currentDef.title}."
            }
        }
    }
    NoticeLine(notice)
    Spacer(Modifier.height(8.dp))
    BigButton("Open the recipe editor", primary = true) { state.screen = Screen.RECIPES }
    Spacer(Modifier.height(8.dp))
    Text(
        "Recipes are Lua. A few dozen lines make a sim the app treats exactly " +
            "like a built-in one.",
        color = Tokens.TextSecondary,
        style = MaterialTheme.typography.bodySmall
    )
}

private fun gravityWord(g: Float): String = when {
    g <= 0.01f -> "none"
    g < 2.5f -> "moon"
    g < 7f -> "light"
    g < 12f -> "earth"
    g < 22f -> "heavy"
    else -> "crushing"
}

/**
 * [conserveEnergy] caps a bounce at the energy it was given, so the top of the
 * slider does nothing while it is on. Saying "gains energy" there was a label
 * describing a setting the physics was ignoring.
 */
private fun bounceWord(r: Float, conserveEnergy: Boolean): String = when {
    r <= 0.05f -> "dead"
    r < 0.4f -> "soft"
    r < 0.8f -> "normal"
    r < 1.0f -> "lively"
    conserveEnergy -> "capped — turn on Free energy"
    else -> "gains energy"
}

private fun tiltWord(x: Float): String = when {
    x <= -0.6f -> "hard left"
    x < -0.08f -> "left"
    x < 0.08f -> "straight down"
    x < 0.6f -> "right"
    else -> "hard right"
}

private fun frictionWord(f: Float): String = when {
    f <= 0.001f -> "none"
    f < 0.05f -> "slight"
    f < 0.2f -> "draggy"
    f < 0.5f -> "thick"
    else -> "syrup"
}
