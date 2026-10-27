package app.hypnotica.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hypnotica.core.laws.Expr
import app.hypnotica.core.recipe.Param
import app.hypnotica.sims.math.EquationSim

/**
 * A formula the person types.
 *
 * It validates on every keystroke but only applies when they are done — on Done
 * or when the field loses focus — so a half-written expression never restarts
 * the run mid-word, and a broken one never reaches the sim at all.
 */
@Composable
fun ExprParamField(state: AppState, p: Param) {
    val keyboard = LocalSoftwareKeyboardController.current
    val stored = state.engine.core.params.text(p.key, p.defaultText)
    val text = remember(p.key, state.currentDef.id, state.exprEpoch) { mutableStateOf(stored) }
    val ok = remember(p.key, state.currentDef.id, state.exprEpoch) { mutableStateOf(true) }
    val dirty = remember(p.key, state.currentDef.id, state.exprEpoch) { mutableStateOf(false) }

    fun commit() {
        if (!dirty.value) return
        dirty.value = false
        if (ok.value && state.setParamText(p, text.value)) state.commitParamText(p)
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(p.label, color = Tokens.TextPrimary, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                .border(
                    1.dp,
                    if (ok.value) Color.White.copy(alpha = 0.12f) else Tokens.Danger,
                    RoundedCornerShape(10.dp)
                )
                .padding(horizontal = 12.dp, vertical = 11.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = text.value,
                onValueChange = { next ->
                    text.value = next
                    dirty.value = true
                    ok.value = Expr.compileOrNull(next) != null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    commit()
                    keyboard?.hide()
                }),
                textStyle = TextStyle(
                    color = if (ok.value) Tokens.TextPrimary else Tokens.Danger,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                ),
                cursorBrush = SolidColor(Tokens.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (!it.isFocused) commit() }
            )
        }
        Text(
            if (ok.value) "Press Done to apply" else "Not a formula yet",
            color = if (ok.value) Tokens.TextSecondary else Tokens.Danger,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}

/**
 * The ready-made formulas, offered under the equation fields so the sim is
 * usable without typing anything at all.
 */
@Composable
fun EquationPresets(state: AppState) {
    Spacer(Modifier.height(4.dp))
    SectionLabel("Start from")
    ChipRow(EquationSim.PRESETS.map { it.name }, -1) { index ->
        val preset = EquationSim.PRESETS[index]
        state.params().firstOrNull { it.key == "mode" }
            ?.let { state.setParam(it, preset.mode.toFloat()) }
        state.params().firstOrNull { it.key == "step" }
            ?.let { state.setParam(it, preset.step) }
        state.params().firstOrNull { it.key == "rate" }
            ?.let { state.setParam(it, preset.rate) }
        state.applyParamText("fx", preset.fx)
        state.applyParamText("fy", preset.fy)
        state.restart(newSeed = false)
    }
    Spacer(Modifier.height(6.dp))
    Text(
        "Variables: t (time), x and y (the current point). Functions: sin cos tan " +
            "sqrt abs exp log min max clamp pow atan2 floor sign.",
        color = Tokens.TextSecondary,
        style = MaterialTheme.typography.bodySmall
    )
    Spacer(Modifier.height(8.dp))
}
