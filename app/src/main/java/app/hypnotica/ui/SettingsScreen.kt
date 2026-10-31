package app.hypnotica.ui

import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.heightIn
import app.hypnotica.core.audio.Groove
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
        val shape = state.engine.core.env.aspect
        if (shape > 0.2f) state.director.aspect = shape
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
        Row(Modifier.fillMaxWidth().heightIn(min = HEADER_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton("‹") { state.goBack() }
            Spacer(Modifier.width(12.dp))
            Text("Settings", color = Tokens.TextPrimary, style = MaterialTheme.typography.titleLarge)
        }

        SectionLabel("Sound")
        SettingsGroup {
        if (!state.engine.synth.available) {
            Text(
                "No sound right now. Replug headphones or restart the app.",
                color = Tokens.Warning,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        // Auto first: each sim its own voice, and the feed free to vary it.
        val sounds = listOf(LOOK_AUTO) + SoundPreset.NAMES
        ChipRow(sounds, if (state.soundAuto) 0 else sounds.indexOf(state.soundPresetName)) {
            state.setSoundPreset(sounds[it])
        }
        SectionLabel("Beat")
        // Auto gives each sim its own groove; the names pin one everywhere.
        val beats = listOf(BEAT_AUTO) + Groove.NAMES
        ChipRow(beats, beats.indexOf(state.beatName).coerceAtLeast(0)) { state.setBeat(beats[it]) }
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
        }

        SectionLabel("Look")
        SettingsGroup {
        val palettes = listOf(LOOK_AUTO) + Palette.NAMES
        ChipRow(palettes, if (state.paletteAuto) 0 else palettes.indexOf(state.paletteName)) {
            state.setPalette(palettes[it])
        }
        PaletteStrip(state.paletteName)
        }

        SectionLabel("Comfort and safety")
        SettingsGroup {
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
        }

        SectionLabel("Feed")
        SettingsGroup {
            ToggleRow("Move on after each payoff", state.feedAutoAdvance) { state.feedAutoAdvance = it }
            Text(
                "For watching hands-free. Touch the screen to stay on a sim.",
                color = Tokens.TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }

        SectionLabel("Time check")
        SettingsGroup {
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

        // What every gesture does, any time it is wanted, rather than on a card
        // pushed at the person on first launch.
        }

        SectionLabel("Gestures")
        SettingsGroup {
        // A row that opens, not a pill: a pill lit "selected" read as an
        // option chosen rather than a section unfolded.
        Disclosure("What the gestures do", showGestures) { showGestures = !showGestures }
        if (showGestures) {
            GestureList()
        }
        }

        SectionLabel("TickTock formats")
        SettingsGroup {
        // Folded, as Gestures is: fifteen pills in eight rows for a hidden
        // feature pushed everything after them half a screen down.
        var showFormats by remember { mutableStateOf(false) }
        val on = TrapFormat.entries.size - state.mutedFormats.size
        Disclosure("Choose formats ($on of ${TrapFormat.entries.size} on)", showFormats) {
            showFormats = !showFormats
        }
        if (showFormats) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Hide the kinds of clip you don't want.",
                color = Tokens.TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            FormatMutes(state)
        }
        Spacer(Modifier.height(8.dp))
        var confirmReset by remember { mutableStateOf(false) }
        // An armed "sure?" stands down by itself; left armed, a tap much later
        // wiped what TickTock had learned.
        LaunchedEffect(confirmReset) {
            if (!confirmReset) return@LaunchedEffect
            delay(4000)
            confirmReset = false
            state.notice = null
        }
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
        NoticeLine(state.notice)
        }

        SectionLabel("Your own sims")
        SettingsGroup {
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
        }

        SectionLabel("YouTube")
        SettingsGroup { YouTubeSection(state) }

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
        // Meant to be found rather than advertised, but each door says what
        // it is under its mark: a caption line of characters beneath them
        // drew the marks a second time, from fallback fonts, half the size.
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                QuietGlyph("◷") { state.screen = Screen.TICKTOCK }
                Text("endless scroll", color = Tokens.TextSecondary.copy(alpha = 0.4f), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(32.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                QuietGlyph("✇") { state.screen = Screen.STUDIO }
                Text("make a video", color = Tokens.TextSecondary.copy(alpha = 0.4f), style = MaterialTheme.typography.bodySmall)
            }
        }
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
            .clickable(role = androidx.compose.ui.semantics.Role.Button) { onClick() }
            .semantics(mergeDescendants = true) { contentDescription = if (glyph == "◷") "TickTock" else "Studio" },
        contentAlignment = Alignment.Center
    ) {
        // Quiet, but findable: at a fifth of the ink the doors were
        // effectively invisible.
        GlyphIcon(glyph, Tokens.TextSecondary.copy(alpha = 0.5f), size = 24.dp)
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

/** One section of settings, on its own card, so the page reads as groups. */
@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Tokens.Surface, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) { androidx.compose.runtime.CompositionLocalProvider(LocalChipEdge provides Tokens.Surface) { content() } }
}

/** A full-width row that unfolds what is under it, with a turning caret. */
@Composable
private fun Disclosure(label: String, open: Boolean, onToggle: () -> Unit) {
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(Tokens.Base), label = "caret")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onToggle() }
            .semantics { stateDescription = if (open) "open" else "closed" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Tokens.TextPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        GlyphIcon("▾", Tokens.TextSecondary, Modifier.rotate(turn), size = 20.dp)
    }
}

/**
 * Connect a YouTube channel, then make a chosen number of Shorts and post
 * them to it. Connecting goes through Google Play services; the one-time
 * Google Cloud registration it depends on is spelled out underneath, with the
 * two values to paste.
 */
@Composable
private fun YouTubeSection(state: AppState) {
    val shorts = state.shorts
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var connecting by remember { mutableStateOf(false) }
    val consent = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        connecting = false
        try {
            val result = com.google.android.gms.auth.api.identity.Identity
                .getAuthorizationClient(context).getAuthorizationResultFromIntent(res.data)
            val token = result.accessToken
            if (token != null) shorts.connected(token) else shorts.failed("YouTube didn't hand over access. Try again.")
        } catch (e: Throwable) {
            shorts.failed(app.hypnotica.export.YouTube.explain(e))
        }
    }
    fun connect() {
        if (connecting) return
        connecting = true
        scope.launch {
            try {
                val result = app.hypnotica.export.YouTube.authorize(context)
                val token = result.accessToken
                val pending = result.pendingIntent
                if (result.hasResolution() && pending != null) {
                    consent.launch(androidx.activity.result.IntentSenderRequest.Builder(pending.intentSender).build())
                    return@launch
                }
                connecting = false
                if (token != null) shorts.connected(token) else shorts.failed("YouTube didn't hand over access. Try again.")
            } catch (e: Throwable) {
                connecting = false
                shorts.failed(app.hypnotica.export.YouTube.explain(e))
            }
        }
    }

    ChipRow(ShortsUploader.ROUTE_NAMES, shorts.route, enabled = !shorts.busy) { shorts.chooseRoute(it) }
    Spacer(Modifier.height(8.dp))
    if (shorts.route == ShortsUploader.ROUTE_APP) {
        AutoTapSection(state)
        return
    }

    val channel = shorts.channel
    if (channel == null) {
        BigButton(if (connecting) "Connecting…" else "Connect YouTube", primary = true) { connect() }
        Text(
            "Post Shorts made here straight to your channel.",
            color = Tokens.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp)
        )
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Connected  ·  $channel",
                color = Tokens.TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Pill("Disconnect", danger = true, enabled = !shorts.busy) { shorts.disconnect() }
        }
        Spacer(Modifier.height(12.dp))
        Text("How many Shorts", color = Tokens.TextSecondary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        val counts = ShortsUploader.COUNTS
        ChipRow(counts.map { it.toString() }, counts.indexOf(shorts.count).coerceAtLeast(0), enabled = !shorts.busy) {
            shorts.chooseCount(counts[it])
        }
        Spacer(Modifier.height(8.dp))
        Text("Who can see them", color = Tokens.TextSecondary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        ChipRow(ShortsUploader.PRIVACY_NAMES, shorts.privacy, enabled = !shorts.busy) { shorts.choosePrivacy(it) }
        Spacer(Modifier.height(12.dp))
        if (shorts.busy) {
            // How far through the batch, as a bar, with Stop beside it.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Tokens.TextSecondary.copy(alpha = 0.2f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(shorts.progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(Tokens.Accent)
                )
            }
            Spacer(Modifier.height(8.dp))
            Pill("Stop", danger = true) { shorts.stop() }
        } else {
            BigButton(
                if (shorts.count == 1) "Make and post 1 Short" else "Make and post ${shorts.count} Shorts",
                primary = true
            ) { shorts.start(context) }
            Text(
                "Each takes a minute or so to render. Keep the app open until it's done.",
                color = Tokens.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        for ((title, link) in shorts.posted.takeLast(10)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link))
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    color = Tokens.TextPrimary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Text("Open ›", color = Tokens.AccentText, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    shorts.status?.let {
        Text(
            it,
            color = Tokens.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp)
        )
    }

    // The one-time registration, folded away.
    var showSetup by remember { mutableStateOf(false) }
    Spacer(Modifier.height(8.dp))
    Disclosure("Set up (once, on a computer)", showSetup) { showSetup = !showSetup }
    if (showSetup) {
        val facts = remember { app.hypnotica.export.YouTube.setupFacts(context) }
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        Text(
            "Google only lets an app post to YouTube once it is registered. On console.cloud.google.com:\n" +
                "1. Make a project and turn on \"YouTube Data API v3\".\n" +
                "2. OAuth consent screen: External, then add your own Google account under Test users.\n" +
                "3. Credentials → Create OAuth client ID → Android, with the package name and SHA-1 below.\n" +
                "Then come back and tap Connect YouTube. Google will warn the app is unverified; that's expected for your own project.",
            color = Tokens.TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        for ((label, value) in listOf("Package name" to facts.first, "SHA-1" to facts.second)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(value))
                        state.notice = "$label copied."
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, color = Tokens.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    Text(value, color = Tokens.TextPrimary, style = MaterialTheme.typography.bodySmall)
                }
                Text("Copy", color = Tokens.AccentText, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * Posting through the YouTube app: Hypnotica renders the batch, hands each
 * Short to YouTube and auto-tap steps through the upload. Needs no Google
 * Cloud project, only the accessibility service turned on.
 */
@Composable
private fun AutoTapSection(state: AppState) {
    val shorts = state.shorts
    val context = androidx.compose.ui.platform.LocalContext.current
    // Turned on in another screen, so looked at again every second while this is open.
    var on by remember { mutableStateOf(app.hypnotica.pilot.Pilot.isOn(context)) }
    var learned by remember { mutableStateOf(app.hypnotica.pilot.Taps.count(context)) }
    var doing by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            on = app.hypnotica.pilot.Pilot.isOn(context)
            learned = app.hypnotica.pilot.Taps.count(context)
            doing = app.hypnotica.pilot.Pilot.activity
            delay(1000)
        }
    }
    fun open(intent: android.content.Intent) {
        runCatching { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    if (!on) {
        BigButton("Turn on auto-tap", primary = true) {
            open(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        Text(
            "In Accessibility, open \"Hypnotica auto-tap\" and switch it on. If the switch is greyed out, " +
                "Android is blocking it because the app didn't come from the Play Store: open App info, " +
                "tap ⋮ (top right) → Allow restricted settings, then try again.",
            color = Tokens.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(Modifier.height(8.dp))
        Pill("Open App info") {
            open(
                android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", context.packageName, null)
                )
            )
        }
    } else {
        Text("Auto-tap is on", color = Tokens.TextPrimary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Text("How many Shorts", color = Tokens.TextSecondary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        val counts = ShortsUploader.COUNTS
        ChipRow(counts.map { it.toString() }, counts.indexOf(shorts.count).coerceAtLeast(0), enabled = !shorts.busy) {
            shorts.chooseCount(counts[it])
        }
        Spacer(Modifier.height(12.dp))
        if (shorts.busy) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Tokens.TextSecondary.copy(alpha = 0.2f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(shorts.progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(Tokens.Accent)
                )
            }
            if (doing.isNotEmpty()) {
                Text(doing, color = Tokens.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(8.dp))
            Pill("Stop", danger = true) { shorts.stop() }
        } else {
            BigButton(
                if (shorts.count == 1) "Make and post 1 Short" else "Make and post ${shorts.count} Shorts",
                primary = true
            ) { shorts.start(context) }
            Text(
                "Renders them all first (about a minute each), then posts each one through the YouTube app. " +
                    "Hands off the phone while it taps. Who can see them is your channel's upload default.",
                color = Tokens.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (learned == 0) "No taps learned yet" else "$learned taps learned",
                color = Tokens.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            if (learned > 0) Pill("Forget them", enabled = !shorts.busy) {
                app.hypnotica.pilot.Taps.forget(context)
                learned = 0
            }
        }
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        Spacer(Modifier.height(4.dp))
        Pill("Copy auto-tap log") {
            val text = app.hypnotica.pilot.Pilot.traceText()
            clipboard.setText(androidx.compose.ui.text.AnnotatedString(if (text.isEmpty()) "(nothing logged yet)" else text))
            state.notice = "Copied what auto-tap saw on its last runs."
        }
        NoticeLine(state.notice)
        Text(
            "It finds YouTube's buttons by what they say and remembers the ones that work. " +
                "When the YouTube app updates it checks them again, and if a screen changes so much " +
                "it can't find a button, it stops and asks you to tap it once — then it knows.",
            color = Tokens.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
    shorts.status?.let {
        Text(it, color = Tokens.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
    Text(
        "YouTube's terms don't allow automated use of the service; auto-tap posts to your own channel at your own risk.",
        color = Tokens.TextSecondary.copy(alpha = 0.7f),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp)
    )
}
