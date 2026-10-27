package app.hypnotica.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The gestures, as a block Settings can show.
 *
 * There used to be a card that put this over the running sim on first launch —
 * nine rows of documentation in a modal, which is both of the things design
 * rule 2 and rule 7 forbid: menus before motion, and a dialog over a sim. The
 * feed's own subtitle says "swipe up or down" until you swipe, and the rest is
 * ordinary touchscreen vocabulary that nobody needs taught. This stays for the
 * person who goes looking.
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
