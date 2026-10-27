package app.hypnotica.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * The only thing in the app that explains itself, shown once.
 *
 * It floats over a sim that is already running — rule 2, motion before menus —
 * so the first second of the app is still the simulation, not a tour.
 */
@Composable
fun FirstRunCard(state: AppState) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable { state.dismissFirstRun() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            Modifier
                .safeDrawingPadding()
                .padding(16.dp)
                .fillMaxWidth()
                .background(Tokens.Surface.copy(alpha = 0.97f), RoundedCornerShape(24.dp))
                // The card swallows its own taps. The scrim behind it dismisses,
                // and the card had no handler of its own, so a tap anywhere on
                // it fell through and closed the only explanation the app ever
                // shows — while reading the gesture list.
                .pointerInput(Unit) { detectTapGestures { } }
                // And it scrolls: the content is about 400 dp, so on a first
                // launch in landscape the "Start watching" button was pushed
                // off the bottom edge where it could not be reached at all.
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text(
                "One gesture, one meaning",
                color = Tokens.TextPrimary,
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(8.dp))
            GESTURES.forEach { (gesture, meaning) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        gesture,
                        color = Tokens.Accent,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(132.dp)
                    )
                    Text(meaning, color = Tokens.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(16.dp))
            BigButton("Start watching", Modifier.fillMaxWidth(), primary = true) {
                state.dismissFirstRun()
            }
        }
    }
}

private val GESTURES = listOf(
    "Tap" to "show or hide the controls",
    "Long-press" to "open the full drawer",
    "Pinch" to "zoom",
    "Two-finger twist" to "rotate the whole sim",
    "Two-finger drag" to "pan",
    "Double-tap" to "like, in TickTock",
    "Settings" to "two hidden buttons: TickTock and Studio"
)
