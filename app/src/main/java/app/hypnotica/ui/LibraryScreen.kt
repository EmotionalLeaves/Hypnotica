@file:kotlin.OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.hypnotica.ui

import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
private const val RECENT_SHELF = 3

/**
 * The TickTock clips liked, each replayed exactly by its run: tap to watch it
 * again, hold to let it go.
 */
@Composable
private fun SavedClips(state: AppState) {
    val runs = state.savedClips
    if (runs.isEmpty()) {
        EmptyState("♡", "Nothing saved yet. Double-tap or ♡ a clip in TickTock to keep it here.")
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
                    // Clipped first, so the press ripple has the row's corners.
                    .clip(RoundedCornerShape(12.dp))
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
                GlyphIcon("♥", Tokens.AccentText, size = 20.dp)
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
    val shelves = remember { listOf<Shelf?>(null, null, null, null) + SimRegistry.shelves() }
    val shelfNames = remember {
        listOf("All", "★ Favourites", "♥ Saved clips", "Recent") + SimRegistry.shelves().map { it.title }
    }

    // The favourites are snapshot state, so unstarring from the grid refreshes
    // the list rather than leaving a tile behind that is no longer a favourite.
    val starred = state.favourites.value
    val seen = state.seen.value
    val recent = state.recent
    val results = remember(query, shelfIndex, starred, recent) {
        val base = SimRegistry.search(query)
        when {
            shelfIndex == FAVOURITES_SHELF -> base.filter { it.id in starred }
            shelfIndex == SAVED_SHELF -> emptyList()
            // Most recent first, as opened — the way back to the one from
            // earlier that the feed has since moved on from.
            shelfIndex == RECENT_SHELF -> {
                val hit = base.associateBy { it.id }
                recent.mapNotNull { hit[it] }
            }
            else -> shelves.getOrNull(shelfIndex)?.let { shelf ->
                base.filter { it.shelf == shelf }
            } ?: if (query.isBlank()) mixed(base) else base
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
            Modifier.fillMaxWidth().heightIn(min = HEADER_HEIGHT),
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
                // 12, as every other input is.
                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                .padding(start = 44.dp, end = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            GlyphIcon("⌕", Tokens.TextSecondary, Modifier.align(Alignment.CenterStart).offset(x = (-32).dp), size = 20.dp)
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
                // The whole style, so typed text sits on the placeholder's baseline.
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Tokens.TextPrimary),
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
                    GlyphIcon("×", Tokens.TextSecondary, size = 20.dp)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        FoundBar(Discovery.foundCount(seen, SimRegistry.all), SimRegistry.all.size)
        Spacer(Modifier.height(12.dp))
        ChipRow(shelfNames, shelfIndex) { shelfIndex = it }
        Spacer(Modifier.height(16.dp))
        // A search that matched nothing used to render an empty grid and
        // nothing else — a blank dark page under a field still offering to
        // "Search 61 sims", which reads as a fault rather than a result.
        if (shelfIndex == SAVED_SHELF) {
            SavedClips(state)
            return@Column
        }
        if (results.isEmpty()) {
            EmptyState(
                if (shelfIndex == FAVOURITES_SHELF) "☆" else "⌕",
                when {
                    shelfIndex == RECENT_SHELF -> "Nothing opened yet. Whatever you watch shows up here."
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
                }
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            // Today's pick heads the All view: one sim a day, something not
            // opened yet while there is anything left, the same all day.
            val pick = state.dailyPick
            if (pick != null && shelfIndex == 0 && query.isBlank()) {
                item(key = "daily", span = { GridItemSpan(maxLineSpan) }) {
                    DailyCard(pick, isNew = pick.id !in seen) {
                        if (pick.id != state.currentDef.id) state.load(pick)
                        state.screen = Screen.PLAYER
                    }
                }
            }
            items(results, key = { it.id }) { def ->
                SimTile(
                    def,
                    state.isFavourite(def.id),
                    isNew = Discovery.isNew(def.id, seen),
                    onLongClick = { state.toggleFavourite(def.id) }
                ) {
                    // The sim already playing carries on rather than restarting.
                    if (def.id != state.currentDef.id) state.load(def)
                    state.screen = Screen.PLAYER
                }
            }
        }
    }
}

@Composable
private fun SimTile(
    def: SimDef,
    favourite: Boolean,
    isNew: Boolean = false,
    onLongClick: () -> Unit = {},
    onClick: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            // Clipped first, so the press ripple has the card's corners rather
            // than a sharp rectangle over the title too.
            .clip(RoundedCornerShape(12.dp))
            // Hold to star it, without opening it and coming back.
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.25f)
                .background(Tokens.Surface, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            // The sim's own first second, simulated and painted on the card.
            SimThumbnail(def, Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)))
            // The edge goes on top: drawn first, the thumbnail painted over it.
            Box(Modifier.matchParentSize().border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp)))
            // A dot, not the word: most of the shelf is unopened at first, and a
            // word on every card was a wall of labels over the pictures.
            if (isNew) Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .size(8.dp)
                    .background(Tokens.Accent, CircleShape)
                    .border(1.dp, Tokens.Bg.copy(alpha = 0.6f), CircleShape)
            )
            if (favourite) {
                // On a dark disc, so it reads on the brightest preview.
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(24.dp)
                        .background(Tokens.Bg.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("★", color = Tokens.AccentText, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            def.title,
            color = Tokens.TextPrimary,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(
            def.family,
            color = Tokens.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}


/** The day's pick, a full row wide: its picture, its name and its one line. */
@Composable
private fun DailyCard(def: SimDef, isNew: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(2.1f)
            .clip(RoundedCornerShape(12.dp))
            .background(Tokens.Surface)
            .clickable(onClick = onClick)
    ) {
        SimThumbnail(def, Modifier.fillMaxSize())
        // Shade along the bottom, so the words read over the brightest picture.
        Box(
            Modifier
                .matchParentSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0.35f to Color.Transparent,
                        1f to Tokens.Bg.copy(alpha = 0.88f)
                    )
                )
        )
        Box(Modifier.matchParentSize().border(1.dp, Tokens.Accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp)))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "TODAY'S PICK",
                    color = Tokens.AccentText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                if (isNew) {
                    Spacer(Modifier.width(8.dp))
                    NewTag()
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                def.title,
                color = Tokens.TextPrimary,
                style = MaterialTheme.typography.titleLarge.copy(shadow = Tokens.OverlayShadow),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val line = def.blurb.ifBlank { def.family }
            Text(
                line,
                color = Tokens.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * How much of the Library this phone has opened: a count and a hairline.
 * The pull of a collection, and an honest one — it counts what was opened,
 * not what was swiped past.
 */
@Composable
private fun FoundBar(found: Int, total: Int) {
    val share = if (total > 0) found.toFloat() / total else 0f
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (found >= total) "All $total found" else "$found of $total found",
            color = if (found >= total) Tokens.Success else Tokens.TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .weight(1f)
                .height(3.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.08f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(share.coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(if (found >= total) Tokens.Success else Tokens.Accent)
            )
        }
    }
}

/** What a list says when it has nothing in it: a mark and a sentence, centred. */
@Composable
private fun EmptyState(glyph: String, message: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp, start = 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(glyph, color = Tokens.TextSecondary.copy(alpha = 0.5f), fontSize = 32.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            color = Tokens.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Every shelf in turn, for the All view. In registry order it opened on fifteen
 * sorting sims in a row — a wall of near-identical bar charts as the first
 * thing the Library showed of eighty-nine sims.
 */
private fun mixed(all: List<SimDef>): List<SimDef> {
    // Each shelf is itself dealt out by family first. The Algorithms shelf is
    // the Sorting lab followed by thirteen sorts, so a plain round-robin put
    // the lab and Quicksort, whose thumbnails are the same bar chart, two rows
    // apart on the first screen; spreading families keeps the opening varied.
    val byShelf = all.groupBy { it.shelf }.values.map { shelf ->
        val families = shelf.groupBy { it.family }.values.map { it.toMutableList() }
        val dealt = ArrayList<SimDef>(shelf.size)
        var k = 0
        while (dealt.size < shelf.size) {
            for (f in families) if (k < f.size) dealt.add(f[k])
            k++
        }
        dealt
    }
    val out = ArrayList<SimDef>(all.size)
    var round = 0
    while (out.size < all.size) {
        for (list in byShelf) if (round < list.size) out.add(list[round])
        round++
    }
    return out
}
