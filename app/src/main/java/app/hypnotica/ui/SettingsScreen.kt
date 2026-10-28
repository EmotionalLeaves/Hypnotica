package app.hypnotica.ui

import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.Dispatchers
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.hypnotica.core.audio.SoundPreset
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.TrapFormat

@Composable
fun SettingsScreen(state: AppState, versionName: String) {
    // TickTock's only door is on this page, so this is where its first clip
    // gets found: by the time the clock is tapped, one is usually waiting.
    // Cancelled with the page, so it costs nothing to someone just passing.
    LaunchedEffect(Unit) {
        if (state.tickTockQueue.isNotEmpty()) return@LaunchedEffect
        val clip = withContext(Dispatchers.Default) {
            runInterruptible { state.director.generate() }
        }
        if (clip != null && state.tickTockQueue.isEmpty()) state.tickTockQueue.addLast(clip)
    }

    var showGestures by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Tokens.Bg)
            .safeDrawingPadding()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton("‹") { state.goBack() }
            Spacer(Modifier.width(12.dp))
            Text("Settings", color = Tokens.TextPrimary, style = MaterialTheme.typography.titleLarge)
        }

        SectionLabel("Sound")
        if (!state.engine.synth.available) {
            Text(
                "No sound right now. Replug headphones or restart the app.",
                color = Tokens.Warning,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        ChipRow(SoundPreset.NAMES, SoundPreset.NAMES.indexOf(state.soundPresetName)) {
            state.setSoundPreset(SoundPreset.NAMES[it])
        }
        ValueSlider(
            label = "Volume",
            value = state.volume,
            min = 0f,
            max = 1f,
            valueText = "${(state.volume * 100f).roundToInt()}%",
            // Saved when the thumb is let go rather than on every pixel of the
            // drag: a hundred writes to disk for one gesture is not free.
            onCommit = { state.applySafety() }
        ) { state.volume = it }

        SectionLabel("Look")
        ChipRow(Palette.NAMES, Palette.NAMES.indexOf(state.paletteName)) {
            state.setPalette(Palette.NAMES[it])
        }
        PaletteStrip(state.paletteName)

        SectionLabel("Comfort and safety")
        ToggleRow("Reduce flashing", state.reduceFlashing) {
            state.reduceFlashing = it
            state.applySafety()
        }
        ToggleRow("Reduce motion", state.reduceMotion) {
            state.reduceMotion = it
            state.applySafety()
        }
        ToggleRow("Haptics", state.haptics) {
            state.haptics = it
            state.applySafety()
        }
        ToggleRow("Show live counters", state.showCounters) { state.showCounters = it }
        Text(
            "This app contains fast, flashing visuals. The engine caps large flashes " +
                "at three a second; Reduce flashing softens them further.",
            color = Tokens.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp)
        )

        SectionLabel("Time check")
        ChipRow(
            listOf("Off", "Every 15 min", "Every 30 min", "Every hour"),
            when (state.timeCheckMinutes) {
                15 -> 1
                30 -> 2
                60 -> 3
                else -> 0
            }
        ) {
            state.timeCheckMinutes = when (it) {
                1 -> 15
                2 -> 30
                3 -> 60
                else -> 0
            }
        }

        SectionLabel("TickTock formats")
        Text(
            "Hide the kinds of clip you don't want.",
            color = Tokens.TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        FormatMutes(state)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var confirmReset by remember { mutableStateOf(false) }
        Pill(
            if (confirmReset) "Forget it all — sure?" else "Reset what it learned",
            selected = confirmReset,
            danger = true
        ) {
            if (!confirmReset) {
                confirmReset = true
                state.notice = "Tap again to forget everything TickTock learned " +
                    "from what you watched. There is no undo."
            } else {
                confirmReset = false
                state.resetTaste()
                state.notice = "Forgotten. TickTock starts fresh."
            }
        }
        }
        // One notice line for the section, under the row rather than beside
        // the pill: there it was squeezed into a column six lines tall, and
        // the format pills above printed the same message a second time.
        NoticeLine(state.notice)

        // What every gesture does, any time it is wanted, rather than on a card
        // pushed at the person on first launch.
        SectionLabel("Gestures")
        if (showGestures) {
            GestureList()
            Spacer(Modifier.height(8.dp))
        }
        Pill(if (showGestures) "Hide" else "What the gestures do", selected = showGestures) {
            showGestures = !showGestures
        }

        SectionLabel("Your own sims")
        BigButton("Write a sim") { state.screen = Screen.RECIPES }
        Text(
            "A few lines of Lua becomes a sim on your shelf, with its own sliders.",
            color = Tokens.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )

        state.benchNote?.let { note ->
            Text(
                "This phone: $note",
                color = Tokens.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        // The version number sits at the foot of the page, and below it,
        // unlabelled, exactly as designed: a clock for TickTock and a film
        // strip for Studio. The version used to sit mid-page, between TickTock
        // and Gestures, so the glyphs were nowhere near it.
        Spacer(Modifier.height(28.dp))
        Row(
            Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                "Hypnotica $versionName",
                color = Tokens.TextSecondary.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            QuietGlyph("◷") { state.screen = Screen.TICKTOCK }
            Spacer(Modifier.width(24.dp))
            QuietGlyph("✇") { state.screen = Screen.STUDIO }
        }
        // The glyphs are meant to be found rather than advertised, but they
        // composite at about 1.4:1 against the background — invisible outdoors.
        // A line this quiet keeps them a discovery and stops them being lost
        // for good.
        Text(
            "◷ endless scroll   ✇ make a video",
            color = Tokens.TextSecondary.copy(alpha = 0.33f),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        Spacer(Modifier.height(36.dp))
    }
}

/**
 * The colours of the chosen palette, drawn as they will appear.
 *
 * Settings is an opaque full-screen column — there is no sim behind it and the
 * GL surface is detached while it is up — so tapping a palette name lit a pill
 * and produced no visible change at all. Twice over, since the choice was then
 * thrown away by the next sim change. A picker with nothing to look at is
 * indistinguishable from a dead control.
 */
@Composable
private fun PaletteStrip(name: String) {
    val palette = remember(name) { Palette.byName(name) }
    val colours = remember(name) {
        List(STRIP_STEPS) { Color(palette.at(it / (STRIP_STEPS - 1f))) }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
    ) {
        colours.forEach { c ->
            Box(Modifier.weight(1f).fillMaxHeight().background(c))
        }
    }
}

/** Enough bands to read as a gradient without drawing a bitmap. */
private const val STRIP_STEPS = 24

/** Almost invisible until you look for it. */
@Composable
private fun QuietGlyph(glyph: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            glyph,
            color = Tokens.TextSecondary.copy(alpha = 0.22f),
            style = MaterialTheme.typography.titleMedium
        )
    }
}

/**
 * One pill per trap format. Selected means "show me these"; a tap mutes it,
 * which the clip generator reads before it picks anything.
 */
@Composable
private fun FormatMutes(state: AppState) {
    val muted = state.mutedFormats
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TrapFormat.entries.chunked(2).forEach { pair ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pair.forEach { format ->
                    Pill(
                        format.title,
                        selected = format !in muted,
                        modifier = Modifier.weight(1f),
                        // The last one still showing cannot be turned off, so it
                        // reads as unavailable rather than as a dead pill.
                        enabled = !state.isLastShowingFormat(format)
                    ) {
                        state.setFormatMuted(format, format !in muted)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
