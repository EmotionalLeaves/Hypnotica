package app.hypnotica.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
            value = state.engine.synth.volume,
            min = 0f,
            max = 1f
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
        Spacer(Modifier.height(36.dp))
    }
}
