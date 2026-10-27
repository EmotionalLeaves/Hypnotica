package app.hypnotica.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.SimDef
import app.hypnotica.sims.SimRegistry

/** Browse every sim, grouped into shelves. */
/** The chip index of the favourites, which is not one of the registry's shelves. */
private const val FAVOURITES_SHELF = 1

@Composable
fun LibraryScreen(state: AppState) {
    var query by remember { mutableStateOf("") }
    var shelfIndex by remember { mutableStateOf(0) }

    // Index 1 is the favourites, which had no way of being seen at all: the
    // star persisted across restarts and the only thing that read it was the
    // badge on a tile, so a collection could be built and then only found by
    // scrolling fifty-eight tiles looking for badges.
    val shelves = remember { listOf<Shelf?>(null, null) + SimRegistry.shelves() }
    val shelfNames = remember {
        listOf("All", "★ Favourites") + SimRegistry.shelves().map { it.title }
    }

    // The favourites are snapshot state, so unstarring from the grid refreshes
    // the list rather than leaving a tile behind that is no longer a favourite.
    val starred = state.favourites.value
    val results = remember(query, shelfIndex, starred) {
        val base = SimRegistry.search(query)
        when {
            shelfIndex == FAVOURITES_SHELF -> base.filter { it.id in starred }
            else -> shelves.getOrNull(shelfIndex)?.let { shelf ->
                base.filter { it.shelf == shelf }
            } ?: base
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Tokens.Bg)
            .safeDrawingPadding()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Library", color = Tokens.TextPrimary, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            GlyphButton("▶") { state.screen = Screen.PLAYER }
            Spacer(Modifier.width(8.dp))
            GlyphButton("⚙") { state.screen = Screen.SETTINGS }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (query.isEmpty()) {
                Text(
                    "Search ${SimRegistry.all.size} sims",
                    color = Tokens.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Tokens.TextPrimary, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                cursorBrush = SolidColor(Tokens.Accent),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(12.dp))
        ChipRow(shelfNames, shelfIndex) { shelfIndex = it }
        Spacer(Modifier.height(12.dp))
        if (shelfIndex == FAVOURITES_SHELF && results.isEmpty()) {
            Text(
                "Nothing starred yet — double-tap a sim, or use the ★ on the feed.",
                color = Tokens.TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(results, key = { it.id }) { def ->
                SimTile(def, state.isFavourite(def.id)) {
                    state.load(def)
                    state.screen = Screen.PLAYER
                }
            }
        }
    }
}

@Composable
private fun SimTile(def: SimDef, favourite: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.25f)
                .background(Tokens.Surface, RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            // The sim's own first second, simulated and painted on the card.
            SimThumbnail(def, Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)))
            if (favourite) {
                Text(
                    "★",
                    color = Tokens.Accent,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            def.title,
            color = Tokens.TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            def.family,
            color = Tokens.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

