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
            GestureList()
            Spacer(Modifier.height(16.dp))
            BigButton("Start watching", Modifier.fillMaxWidth(), primary = true) {
                state.dismissFirstRun()
            }
        }
    }
}

/**
 * The gestures, as a block that can be shown anywhere.
 *
 * It used to exist only inside the first-run card, which is shown once ever:
 * dismissFirstRun writes a flag that nothing else clears, so a stray tap on
 * the scrim meant the only explanation the app offers was gone for good. The
 * card keeps its one-time job; Settings can show the same list any time.
 */
@Composable
fun GestureList() {
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
                modifier = Modifier.width(116.dp)
            )
            Text(
                meaning,
                color = Tokens.TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/**
 * What the app can be told to do, shown once on first launch and available
 * from Settings afterwards.
 *
 * Swipe was missing, which is the feed's entire navigation — the only place it
 * was ever mentioned was a hint line that disappears after the first swipe and
 * never says that down works too. Drag was missing, and many sims respond to
 * it. "Tap" also said "show or hide the controls", which is true in the player
 * and in TickTock but not on the feed, where the card is actually shown.
 */
internal val GESTURES = listOf(
    "Swipe up or down" to "the next sim, or the one before",
    "Tap" to "open the controls, or hide them",
    "Drag" to "reach into the sim",
    "Long-press" to "open the full drawer",
    "Pinch" to "zoom",
    "Two-finger twist" to "rotate the whole sim",
    "Two-finger drag" to "pan",
    "Double-tap" to "like, in TickTock",
    "Settings, at the bottom" to "two hidden buttons"
)
