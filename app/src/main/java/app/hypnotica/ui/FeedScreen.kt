package app.hypnotica.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.hypnotica.sims.SimRegistry

/**
 * Home: one sim, full screen, swipe up for the next.
 *
 * Nothing loads here. The next sim is already described in the registry and
 * starts the instant the swipe lands, which is what keeps motion within a second
 * of every entry point.
 */
@Composable
fun FeedScreen(state: AppState, surface: SimSurfaceView) {
    val order = remember { SimRegistry.all.shuffled(java.util.Random(7)) }
    var index by remember { mutableStateOf(0) }
    var hint by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        // Endless mode: when a run resolves, move on by itself.
        state.engine.onFinished = {
            index = (index + 1) % order.size
        }
        onDispose { state.engine.onFinished = null }
    }

    DisposableEffect(index) {
        state.load(order[index])
        onDispose { }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Tokens.Bg)
            .pointerInput(order.size) {
                detectVerticalDragGestures { _, dragAmount ->
                    if (dragAmount < -28f) {
                        hint = false
                        index = (index + 1) % order.size
                    } else if (dragAmount > 28f) {
                        hint = false
                        index = (index - 1 + order.size) % order.size
                    }
                }
            }
    ) {
        SimCanvas(
            surface = surface,
            modifier = Modifier.fillMaxSize(),
            onTap = { state.screen = Screen.PLAYER }
        )

        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    hookFor(state),
                    color = Tokens.TextPrimary,
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        state.currentDef.title,
                        color = Tokens.TextPrimary,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        if (hint) "${state.currentDef.shelf.title}  ·  swipe up for the next"
                        else state.currentDef.shelf.title,
                        color = Tokens.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GlyphButton(
                        if (state.isFavourite(state.currentDef.id)) "★" else "☆",
                        selected = state.isFavourite(state.currentDef.id)
                    ) { state.toggleFavourite(state.currentDef.id) }
                    Spacer(Modifier.height(10.dp))
                    GlyphButton("↻") { state.restart(newSeed = true) }
                    Spacer(Modifier.height(10.dp))
                    GlyphButton("≡") { state.screen = Screen.LIBRARY }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                BigButton("Open controls", Modifier.weight(1f), primary = true) {
                    state.controlsOpen = true
                    state.screen = Screen.PLAYER
                }
                Spacer(Modifier.width(2.dp))
                BigButton("Settings") { state.screen = Screen.SETTINGS }
            }
        }
    }
}

private fun hookFor(state: AppState): String {
    val format = state.currentDef.formats.firstOrNull()
    return format?.hook ?: state.currentDef.blurb
}
