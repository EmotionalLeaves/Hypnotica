package app.hypnotica.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** A glass panel floating over the simulation. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier
            .background(Tokens.Surface.copy(alpha = 0.92f), RoundedCornerShape(24.dp))
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(24.dp))
            .padding(16.dp)
    ) { content() }
}

/** A pill that can be selected. The whole of Simple mode is built from these. */
@Composable
fun Pill(
    label: String,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier
            .heightIn(min = 44.dp)
            .background(
                if (selected) Tokens.Accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f),
                CircleShape
            )
            .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) Tokens.Accent else Color.White.copy(alpha = 0.12f),
                CircleShape
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) Tokens.TextPrimary else Tokens.TextSecondary,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** The big one-tap action: the hero button, play, record. */
@Composable
fun BigButton(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier
            .heightIn(min = 52.dp)
            .background(
                if (primary) Tokens.Accent.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.06f),
                RoundedCornerShape(14.dp)
            )
            .border(
                if (primary) 2.dp else 1.dp,
                if (primary) Tokens.Accent else Color.White.copy(alpha = 0.12f),
                RoundedCornerShape(14.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = Tokens.TextPrimary,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A chip row that scrolls sideways when it does not fit. */
@Composable
fun ChipRow(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEachIndexed { i, label ->
            Pill(label, selected = i == selectedIndex) { onSelect(i) }
        }
    }
}

/**
 * A labelled slider that says what the value means in words, not units.
 * Log scaling keeps counts that span decades usable with one thumb.
 */
@Composable
fun ValueSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    modifier: Modifier = Modifier,
    logScale: Boolean = false,
    valueText: String? = null,
    onCommit: (() -> Unit)? = null,
    onChange: (Float) -> Unit
) {
    val position = if (logScale) toLog(value, min, max) else ((value - min) / (max - min)).coerceIn(0f, 1f)
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Tokens.TextPrimary, style = MaterialTheme.typography.titleMedium)
            Text(
                valueText ?: formatValue(value),
                color = Tokens.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.End
            )
        }
        Slider(
            value = position,
            onValueChange = { p ->
                onChange(if (logScale) fromLog(p, min, max) else min + p * (max - min))
            },
            onValueChangeFinished = { onCommit?.invoke() },
            colors = SliderDefaults.colors(
                thumbColor = Tokens.Accent,
                activeTrackColor = Tokens.Accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.12f)
            )
        )
    }
}

@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Tokens.TextPrimary, style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Tokens.Accent
            )
        )
    }
}

/** A small round icon-ish button drawn from a glyph, so no image assets are needed. */
@Composable
fun GlyphButton(
    glyph: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier
            .size(48.dp)
            .background(
                if (selected) Tokens.Accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f),
                CircleShape
            )
            .border(1.dp, Color.White.copy(alpha = if (selected) 0.3f else 0.12f), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(glyph, color = Tokens.TextPrimary, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(top = 10.dp, bottom = 4.dp),
        color = Tokens.TextSecondary,
        style = MaterialTheme.typography.labelMedium
    )
}

fun formatValue(v: Float): String = when {
    abs(v) >= 10000f -> String.format("%,.0f", v)
    abs(v) >= 100f -> String.format("%.0f", v)
    abs(v) >= 10f -> String.format("%.1f", v)
    else -> String.format("%.2f", v)
}

private fun toLog(value: Float, min: Float, max: Float): Float {
    val lo = ln(min.coerceAtLeast(0.0001f))
    val hi = ln(max.coerceAtLeast(min + 0.001f))
    val v = ln(value.coerceIn(min.coerceAtLeast(0.0001f), max))
    return ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
}

private fun fromLog(position: Float, min: Float, max: Float): Float {
    val lo = ln(min.coerceAtLeast(0.0001f))
    val hi = ln(max.coerceAtLeast(min + 0.001f))
    return kotlin.math.E.toFloat().pow(lo + (hi - lo) * position.coerceIn(0f, 1f))
}
