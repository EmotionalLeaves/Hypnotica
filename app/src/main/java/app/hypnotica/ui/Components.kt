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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
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

/**
 * The one-line message channel, wherever controls can produce one.
 *
 * It used to be rendered in exactly one place — Simple mode's Motion card —
 * while three screens wrote to it, so the Advanced "Stop and edit" pill
 * discarded a recording too short to use and said nothing at all, which is the
 * silence the message was added to fix.
 */
@Composable
fun NoticeLine(text: String?, modifier: Modifier = Modifier) {
    text ?: return
    Text(
        text,
        color = Tokens.Accent,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.padding(top = 4.dp)
    )
}

/** A chip row that scrolls sideways when it does not fit. */
@Composable
fun ChipRow(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit
) {
    // The selected chip is scrolled to, which it never used to be. A row of
    // palettes or scales is wider than a phone, so the chip you were on was
    // usually off the right-hand edge — and the row then looked as though
    // nothing was selected at all. Worse on the Laws tab, where the first edit
    // appends a "Custom" chip at position sixteen and selects it.
    val scroll = rememberScrollState()
    var measured by remember(options.size) { mutableStateOf(IntArray(options.size)) }
    LaunchedEffect(selectedIndex, options.size, scroll.maxValue) {
        if (selectedIndex < 0 || selectedIndex >= measured.size) return@LaunchedEffect
        if (scroll.maxValue <= 0) return@LaunchedEffect
        val target = measured.take(selectedIndex).sum()
        scroll.animateScrollTo(target.coerceIn(0, scroll.maxValue))
    }
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEachIndexed { i, label ->
            Pill(
                label,
                selected = i == selectedIndex,
                modifier = Modifier.onSizeChanged { size ->
                    // Chip widths vary with their labels, so where to scroll to
                    // has to be measured rather than guessed.
                    if (i < measured.size && measured[i] != size.width + 8) {
                        measured = measured.copyOf().also { it[i] = size.width + 8 }
                    }
                },
                enabled = enabled
            ) { onSelect(i) }
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
    enabled: Boolean = true,
    onCommit: (() -> Unit)? = null,
    onChange: (Float) -> Unit
) {
    // A control that another setting has taken over should look like it. Until
    // this existed, every overridden slider in the app was full brightness and
    // fully draggable while doing nothing at all, which is indistinguishable
    // from broken.
    val dim = if (enabled) 1f else 0.38f
    val span = max - min
    val position = when {
        span <= 0f -> 0f
        logScale -> toLog(value, min, max)
        else -> ((value - min) / span).coerceIn(0f, 1f)
    }
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                color = Tokens.TextPrimary.copy(alpha = dim),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                valueText ?: formatValue(value),
                color = Tokens.TextSecondary.copy(alpha = dim),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.End
            )
        }
        Slider(
            value = position,
            enabled = enabled,
            onValueChange = { p ->
                val raw = if (logScale) fromLog(p, min, max) else min + p * span
                // Clamped on the way out as well as on the way in: a saved
                // state or a macro can leave a value past the end of the track,
                // and without this the first touch silently jumped it.
                onChange(raw.coerceIn(min, max))
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
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier
            .fillMaxWidth()
            // One toggleable node for the whole row rather than a clickable row
            // wrapped around a switch that is also toggleable: two nodes doing
            // the same job made a screen reader announce every row twice.
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onChange
            )
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = Tokens.TextPrimary.copy(alpha = if (enabled) 1f else 0.38f),
            style = MaterialTheme.typography.bodyMedium
        )
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = null,
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
    abs(v) >= 10000f -> String.format(java.util.Locale.US, "%,.0f", v)
    abs(v) >= 100f -> String.format(java.util.Locale.US, "%.0f", v)
    abs(v) >= 10f -> String.format(java.util.Locale.US, "%.1f", v)
    else -> String.format(java.util.Locale.US, "%.2f", v)
}

/**
 * Where a value sits on a log slider, as a fraction of the track.
 *
 * A range that starts at or below zero cannot be mapped by log alone, and the
 * old code substituted 0.0001 for the floor: ln(0.0001) is -9.2 against
 * ln(40000) = 10.6, so the left 46% of that slider mapped to values between a
 * ten-thousandth and one — every one of which rounded to the same integer. Half
 * the travel did nothing. For those, the log is taken over the distance from the
 * bottom of the range instead, which puts the bottom of the range at exactly
 * position zero and spends the travel evenly.
 *
 * The offset form is only used when it has to be. Applied to a range like
 * 0.0002..0.08 it would be very nearly linear, which is the opposite of what a
 * log slider is for, so a range that is positive throughout keeps the plain log.
 */
private fun toLog(value: Float, min: Float, max: Float): Float {
    val span = max - min
    if (span <= 0f) return 0f
    if (min <= 0f) {
        val hi = ln(span + 1f)
        return (ln((value - min).coerceIn(0f, span) + 1f) / hi).coerceIn(0f, 1f)
    }
    val lo = ln(min)
    val hi = ln(max.coerceAtLeast(min + 0.001f))
    val v = ln(value.coerceIn(min, max))
    return ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
}

/** The exact inverse of [toLog], so a thumb cannot drift under the finger. */
private fun fromLog(position: Float, min: Float, max: Float): Float {
    val p = position.coerceIn(0f, 1f)
    val span = max - min
    if (span <= 0f) return min
    if (min <= 0f) {
        return min + kotlin.math.E.toFloat().pow(p * ln(span + 1f)) - 1f
    }
    val lo = ln(min)
    val hi = ln(max.coerceAtLeast(min + 0.001f))
    return kotlin.math.E.toFloat().pow(lo + (hi - lo) * p)
}
