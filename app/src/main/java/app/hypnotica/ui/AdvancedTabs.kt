package app.hypnotica.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
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
import app.hypnotica.core.sim.EventKind

/** The tabs Advanced mode offers, in order. */
enum class AdvancedTab(val title: String) {
    DATA("Data"), PARAMS("Params"), LAWS("Laws"), SOUND("Sound"), CODE("Code")
}

@Composable
fun AdvancedPanel(state: AppState, tab: AdvancedTab) {
    when (tab) {
        AdvancedTab.DATA -> DataTab(state)
        AdvancedTab.PARAMS -> ParamsTab(state)
        AdvancedTab.LAWS -> LawsTab(state)
        AdvancedTab.SOUND -> SoundTab(state)
        AdvancedTab.CODE -> CodeTab(state)
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
    Spacer(Modifier.height(6.dp))
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
}

/** One parameter with its slider, its exact value and a formula box. */
@Composable
private fun ExactParamRow(state: AppState, p: Param) {
    val value = state.paramValue(p.key)
    when (p.type) {
        ParamType.EXPR -> ExprParamField(state, p)
        ParamType.BOOL -> ToggleRow(p.label, value >= 0.5f) { state.setParamBool(p, it) }
        ParamType.CHOICE -> {
            SectionLabel(p.label)
            ChipRow(p.choices, Math.round(value)) { state.setParamChoice(p, it) }
        }
        else -> {
            ValueSlider(
                label = p.label,
                value = value,
                min = p.min,
                max = p.max,
                logScale = p.logScale,
                valueText = formatValue(value) + (if (p.unit.isNotEmpty()) " " + p.unit else ""),
                onCommit = { state.commitParam(p) }
            ) { state.setParam(p, it) }
            FormulaField(state, p)
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
                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                .border(
                    1.dp,
                    if (ok) Color.White.copy(alpha = 0.1f) else Tokens.Danger,
                    RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 10.dp),
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
                    if (next.isBlank()) {
                        state.engine.core.params.clearExpression(p.key)
                        ok = true
                    } else {
                        ok = state.engine.core.params.setExpression(p.key, next)
                    }
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
    SectionLabel("Universe")
    // Editing any law renames the universe "Custom"; without a chip for it the
    // row would simply show nothing selected.
    val presetNames = remember { Laws.PRESETS.map { it.name } }
    val names = remember(laws.name) {
        if (presetNames.contains(laws.name)) presetNames else presetNames + laws.name
    }
    ChipRow(names, names.indexOf(laws.name)) {
        if (it < presetNames.size) state.setLaws(presetNames[it])
    }

    ValueSlider("Gravity", laws.gravity, 0f, 40f, valueText = gravityWord(laws.gravity)) { v ->
        state.editLaws { it.copy(gravity = v) }
    }
    ValueSlider("Bounciness", laws.restitution, 0f, 1.3f, valueText = bounceWord(laws.restitution)) { v ->
        state.editLaws { it.copy(restitution = v) }
    }
    ValueSlider("Friction", laws.friction, 0f, 1f, valueText = frictionWord(laws.friction)) { v ->
        state.editLaws { it.copy(friction = v) }
    }
    ValueSlider("Time speed", laws.timeScale, 0.05f, 4f, logScale = true,
        valueText = String.format("%.2fx", laws.timeScale)) { v ->
        state.editLaws { it.copy(timeScale = v) }
    }

    SectionLabel("Break a law")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("Reverse time", selected = laws.timeDirection < 0f) {
            state.editLaws { it.copy(timeDirection = if (it.timeDirection < 0f) 1f else -1f) }
        }
        Pill("No friction", selected = laws.friction <= 0.0001f) {
            state.editLaws { it.copy(friction = if (it.friction <= 0.0001f) 0.02f else 0f) }
        }
        Pill("Negative mass", selected = laws.massSign < 0f) {
            state.editLaws { it.copy(massSign = -it.massSign) }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("Wrap space", selected = laws.space == Space.TORUS) {
            state.editLaws { it.copy(space = if (it.space == Space.TORUS) Space.BOX else Space.TORUS) }
        }
        Pill("Repulsive gravity", selected = laws.gravityConstant < 0f) {
            state.editLaws { it.copy(gravityConstant = -it.gravityConstant) }
        }
        Pill("Upside-down", selected = laws.gravityDirY < 0f) {
            state.editLaws { it.copy(gravityDirY = -it.gravityDirY) }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("Sticky", selected = laws.sticky) {
            state.editLaws { it.copy(sticky = !it.sticky, passThrough = false) }
        }
        Pill("Pass through", selected = laws.passThrough) {
            state.editLaws { it.copy(passThrough = !it.passThrough, sticky = false) }
        }
        Pill("Free energy", selected = !laws.conserveEnergy) {
            state.editLaws { it.copy(conserveEnergy = !it.conserveEnergy) }
        }
    }

    Spacer(Modifier.height(8.dp))
    ValueSlider(
        "Charge strength",
        laws.electricStrength,
        0.05f,
        8f,
        logScale = true,
        valueText = String.format("%.2fx", laws.electricStrength)
    ) { v -> state.editLaws { it.copy(electricStrength = v) } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("Like charges attract", selected = laws.chargeSign < 0f) {
            state.editLaws { it.copy(chargeSign = -it.chargeSign) }
        }
    }

    if (!advanced) return

    ValueSlider("Speed of light", laws.lightSpeed, 0.5f, 1e6f, logScale = true,
        valueText = if (laws.lightSpeed > 1e5f) "unlimited" else String.format("%.1f", laws.lightSpeed)) { v ->
        state.editLaws { it.copy(lightSpeed = v) }
    }
    ValueSlider("Force falloff", laws.falloff, 0.5f, 4f,
        valueText = String.format("1/r^%.2f", laws.falloff)) { v ->
        state.editLaws { it.copy(falloff = v) }
    }
    ValueSlider("Gravitational constant", laws.gravityConstant, -3f, 3f) { v ->
        state.editLaws { it.copy(gravityConstant = v) }
    }

    SectionLabel("Force law")
    var force by remember(laws.name) { mutableStateOf(laws.forceExpr ?: "") }
    var forceOk by remember(laws.name) { mutableStateOf(true) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
            .border(1.dp, if (forceOk) Color.White.copy(alpha = 0.1f) else Tokens.Danger, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp),
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
    SectionLabel("Preset")
    ChipRow(SoundPreset.NAMES, SoundPreset.NAMES.indexOf(state.soundPresetName).coerceAtLeast(0)) {
        state.setSoundPreset(SoundPreset.NAMES[it])
    }
    SectionLabel("Scale")
    val mapping = state.engine.mapping
    var scaleIndex by remember { mutableStateOf(Scale.NAMES.indexOf(mapping.scale.name).coerceAtLeast(0)) }
    ChipRow(Scale.NAMES, scaleIndex) {
        scaleIndex = it
        mapping.scale = Scale.byIndex(it)
    }
    SectionLabel("Song mode")
    ChipRow(Song.NAMES, state.songIndex) { state.setSong(it) }

    ValueSlider("Base pitch", mapping.baseHz, 60f, 880f, logScale = true,
        valueText = String.format("%.0f Hz", mapping.baseHz)) { mapping.baseHz = it }
    ValueSlider("Range", mapping.octaves, 1f, 6f,
        valueText = String.format("%.1f octaves", mapping.octaves)) { mapping.octaves = it }
    ValueSlider("Decay", mapping.decay, 0.03f, 2f, logScale = true,
        valueText = String.format("%.2f s", mapping.decay)) { mapping.decay = it }
    ValueSlider("Notes per second", mapping.maxNotesPerSecond, 4f, 400f, logScale = true,
        valueText = String.format("%.0f", mapping.maxNotesPerSecond)) { mapping.maxNotesPerSecond = it }

    SectionLabel("Which events make a sound")
    var tick by remember { mutableStateOf(0) }
    Column {
        EventKind.entries.forEach { kind ->
            val enabled = remember(tick, kind) { mapping.isEnabled(kind) }
            ToggleRow(kind.name.lowercase().replaceFirstChar { it.uppercase() }, enabled) {
                mapping.setEnabled(kind, it)
                tick++
            }
        }
    }
}

@Composable
private fun CodeTab(state: AppState) {
    val version = state.paramVersion
    val text = remember(version, state.currentDef.id) { state.stateJson() }
    Text(
        "This run, exactly. Everything needed to replay it.",
        color = Tokens.TextSecondary,
        style = MaterialTheme.typography.bodySmall
    )
    Spacer(Modifier.height(8.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Text(
            text,
            color = Tokens.TextPrimary,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        )
    }
    Spacer(Modifier.height(10.dp))
    BigButton("Open the recipe editor", primary = true) { state.screen = Screen.RECIPES }
    Spacer(Modifier.height(6.dp))
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

private fun bounceWord(r: Float): String = when {
    r <= 0.05f -> "dead"
    r < 0.4f -> "soft"
    r < 0.8f -> "normal"
    r < 1.0f -> "lively"
    else -> "gains energy"
}

private fun frictionWord(f: Float): String = when {
    f <= 0.001f -> "none"
    f < 0.05f -> "slight"
    f < 0.2f -> "draggy"
    f < 0.5f -> "thick"
    else -> "syrup"
}
