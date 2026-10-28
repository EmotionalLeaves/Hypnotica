@file:kotlin.OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.hypnotica.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.combinedClickable
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
private const val SAVED_SHELF = 2

/**
 * The TickTock clips liked, each replayed exactly by its run: tap to watch it
 * again, hold to let it go.
 */
@Composable
private fun SavedClips(state: AppState) {
    val runs = state.savedClips
    if (runs.isEmpty()) {
        Text(
            "Nothing saved yet. Double-tap or ♡ a clip in TickTock to keep it here.",
            color = Tokens.TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        return
    }
    androidx.compose.foundation.lazy.LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        items(runs.size) { i ->
            val run = runs[i]
            val parsed = remember(run) { app.hypnotica.core.recipe.parseRun(run) }
            val def = (parsed as? app.hypnotica.core.recipe.RunParse.Ok)?.let { SimRegistry.find(it.run.recipe) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .background(Tokens.Surface, RoundedCornerShape(12.dp))
                    .combinedClickable(
                        onClick = {
                            val problem = state.applyRun(run)
                            if (problem == null) state.screen = Screen.PLAYER else state.notice = problem
                        },
                        onLongClick = {
                            state.forgetSaved(run)
                            state.tick()
                        }
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("♥", color = Tokens.AccentText, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(def?.title ?: "A clip", color = Tokens.TextPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(def?.family ?: "", color = Tokens.TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun LibraryScreen(state: AppState) {
    var query by remember { mutableStateOf("") }
    var shelfIndex by remember { mutableStateOf(0) }

    // Index 1 is the favourites, which had no way of being seen at all: the
    // star persisted across restarts and the only thing that read it was the
    // badge on a tile, so a collection could be built and then only found by
    // scrolling fifty-eight tiles looking for badges.
    val shelves = remember { listOf<Shelf?>(null, null, null) + SimRegistry.shelves() }
    val shelfNames = remember {
        listOf("All", "★ Favourites", "♥ Saved clips") + SimRegistry.shelves().map { it.title }
    }

    // The favourites are snapshot state, so unstarring from the grid refreshes
    // the list rather than leaving a tile behind that is no longer a favourite.
    val starred = state.favourites.value
    val results = remember(query, shelfIndex, starred) {
        val base = SimRegistry.search(query)
        when {
            shelfIndex == FAVOURITES_SHELF -> base.filter { it.id in starred }
            shelfIndex == SAVED_SHELF -> emptyList()
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
            // ‹ like every other panel, rather than a ▶ that had to be guessed.
            GlyphButton("‹") { state.goBack() }
            Spacer(Modifier.width(12.dp))
            Text("Library", color = Tokens.TextPrimary, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
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
                modifier = Modifier.fillMaxWidth().padding(end = if (query.isEmpty()) 0.dp else 40.dp)
            )
            // Clearing a search on a phone meant holding backspace.
            if (query.isNotEmpty()) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .size(48.dp)
                        .clickable { query = "" },
                    contentAlignment = Alignment.Center
                ) {
                    Text("×", color = Tokens.TextSecondary, style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        ChipRow(shelfNames, shelfIndex) { shelfIndex = it }
        Spacer(Modifier.height(12.dp))
        // A search that matched nothing used to render an empty grid and
        // nothing else — a blank dark page under a field still offering to
        // "Search 61 sims", which reads as a fault rather than a result.
        if (shelfIndex == SAVED_SHELF) {
            SavedClips(state)
            return@Column
        }
        if (results.isEmpty()) {
            Text(
                when {
                    shelfIndex == FAVOURITES_SHELF ->
                        // Not "double-tap a sim": double-tap left the player and
                        // the feed, where waiting to see if a second tap was
                        // coming put a quarter-second lag on every tap.
                        "Nothing starred yet. Tap ★ on a sim, or hold its card here, to keep it."
                    query.isNotBlank() ->
                        if (shelfIndex == 0) "Nothing matches \"$query\". Try a shorter word."
                        else "Nothing matches \"$query\". Try a shorter word, or tap All " +
                            "to search every shelf."
                    else -> "Nothing on this shelf yet."
                },
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
                SimTile(def, state.isFavourite(def.id), onLongClick = { state.toggleFavourite(def.id) }) {
                    // The sim already playing carries on rather than restarting.
                    if (def.id != state.currentDef.id) state.load(def)
                    state.screen = Screen.PLAYER
                }
            }
        }
    }
}

@Composable
private fun SimTile(def: SimDef, favourite: Boolean, onLongClick: () -> Unit = {}, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            // Hold to star it, without opening it and coming back.
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
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

