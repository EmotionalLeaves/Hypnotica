package app.hypnotica.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.hypnotica.core.sim.TrapFormat
import app.hypnotica.core.audio.SoundPreset
import app.hypnotica.core.render.Palette

@Composable
fun SettingsScreen(state: AppState, versionName: String) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Tokens.Bg)
            .safeDrawingPadding()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton("‹") { state.screen = Screen.FEED }
            Spacer(Modifier.width(12.dp))
            Text("Settings", color = Tokens.TextPrimary, style = MaterialTheme.typography.titleLarge)
        }

        SectionLabel("Sound")
        ChipRow(SoundPreset.NAMES, SoundPreset.NAMES.indexOf(state.soundPresetName).coerceAtLeast(0)) {
            state.setSoundPreset(SoundPreset.NAMES[it])
        }
        ValueSlider(
            label = "Volume",
            value = state.volume,
            min = 0f,
            max = 1f,
            // Saved when the thumb is let go rather than on every pixel of the
            // drag: a hundred writes to disk for one gesture is not free.
            onCommit = { state.applySafety() }
        ) { state.engine.synth.volume = it }

        SectionLabel("Look")
        ChipRow(Palette.NAMES, Palette.NAMES.indexOf(state.paletteName).coerceAtLeast(0)) {
            state.setPalette(Palette.NAMES[it])
        }

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
            modifier = Modifier.padding(top = 6.dp)
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
            "Switch off the kinds of clip you do not want to see. The feed learns " +
                "from what you watch as well.",
            color = Tokens.TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        FormatMutes(state)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Reset what it learned") { state.resetTaste() }
        }

        Spacer(Modifier.height(28.dp))
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                "Hypnotica $versionName",
                color = Tokens.TextSecondary.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall
            )
        }

        // The recipe editor is the largest thing in the app — a sim written in
        // Lua becomes a sim like any other — and its only door was Advanced,
        // the fifth tab, below a JSON blob. Somebody handed a recipe in a chat
        // message had no way to paste it in.
        SectionLabel("Your own sims")
        BigButton("Write a sim") { state.screen = Screen.RECIPES }
        Text(
            "A few lines of Lua becomes a sim on your shelf, with its own sliders.",
            color = Tokens.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
        )

        state.benchNote?.let { note ->
            Text(
                "This phone: $note",
                color = Tokens.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        // Below the version number, unlabelled, exactly as designed: a clock for
        // TickTock and a film strip for Studio.
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            QuietGlyph("◷") { state.screen = Screen.TICKTOCK }
            Spacer(Modifier.width(26.dp))
            QuietGlyph("✇") { state.screen = Screen.STUDIO }
        }
        Spacer(Modifier.height(36.dp))
    }
}

/** Almost invisible until you look for it. */
@Composable
private fun QuietGlyph(glyph: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
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
                        modifier = Modifier.weight(1f)
                    ) {
                        state.setFormatMuted(format, format !in muted)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
