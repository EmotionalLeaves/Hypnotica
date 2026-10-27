package app.hypnotica.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hypnotica.core.studio.Easing
import app.hypnotica.core.studio.Project
import app.hypnotica.core.studio.TrackKind
import app.hypnotica.export.ExportSettings
import app.hypnotica.export.Publish
import app.hypnotica.export.VideoExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Studio: the sim as footage.
 *
 * The timeline holds keyframes, not frames, so a project is a few kilobytes and
 * can be re-rendered at any size later.
 */
@Composable
fun StudioScreen(state: AppState, surface: SimSurfaceView) {
    val project = state.project ?: remember {
        Project.template("Build-up to payoff", state.currentDef.id, state.engine.core.seed)
            .also { state.project = it }
    }
    var selectedTrack by remember { mutableStateOf(TrackKind.ZOOM) }
    var easing by remember { mutableStateOf(Easing.EASE_IN_OUT) }
    var playhead by remember { mutableFloatStateOf(0f) }
    var playing by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var exported by remember { mutableStateOf<File?>(null) }
    var captionText by remember { mutableStateOf("") }
    var captionBig by remember { mutableStateOf(true) }
    // Flash has nothing live to read, so Studio holds the value to be keyed.
    var flashValue by remember { mutableFloatStateOf(0.6f) }
    var exportProgress by remember { mutableFloatStateOf(0f) }
    var runningExport by remember { mutableStateOf<VideoExporter?>(null) }
    var formatIndex by remember { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    DisposableEffect(Unit) {
        state.engine.project = project
        state.engine.studioPlaying = false
        state.engine.seek(0f)
        onDispose {
            // Hand the sim back as the player left it, laws and all.
            state.engine.detachProject(state.laws, state.trails, state.speed)
        }
    }

    LaunchedEffect(playing) {
        state.engine.studioPlaying = playing
        while (playing) {
            delay(50)
            playhead = state.engine.studioTime
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Tokens.Bg)
            .safeDrawingPadding()
            .padding(horizontal = 14.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton("‹") { state.screen = Screen.SETTINGS }
            Spacer(Modifier.width(10.dp))
            Text("Studio", color = Tokens.TextPrimary, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            BigButton(if (exporting) "Exporting…" else "Export", primary = true) {
                if (!exporting) {
                    exporting = true
                    exportMessage = null
                    scope.launch {
                        val settings = ExportSettings.ALL[formatIndex]
                        val dir = File(context.getExternalFilesDir(null), "exports")
                        val out = File(dir, "hypnotica-${System.currentTimeMillis()}.mp4")
                        val exporter = VideoExporter(
                            def = state.currentDef,
                            project = project,
                            laws = state.laws,
                            paletteName = state.paletteName,
                            soundPresetName = state.soundPresetName,
                            params = state.engine.core.params.snapshot(),
                            settings = settings
                        )
                        runningExport = exporter
                        val progressJob = launch {
                            while (exporting) {
                                exportProgress = exporter.progress
                                delay(120)
                            }
                        }
                        exportMessage = try {
                            withContext(Dispatchers.Default) { exporter.export(out) }
                            val outcome = withContext(Dispatchers.IO) { Publish.publish(context, out) }
                            exported = outcome.shareable
                            outcome.message
                        } catch (e: InterruptedException) {
                            runCatching { out.delete() }
                            "Cancelled."
                        } catch (e: Throwable) {
                            "Export failed: ${e.message}"
                        }
                        progressJob.cancel()
                        runningExport = null
                        exporting = false
                        exportProgress = 0f
                    }
                }
            }
        }

        // Preview
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.62f)
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
        ) {
            SimCanvas(surface = surface, modifier = Modifier.fillMaxSize())
            project.textAt(playhead)?.let { clip ->
                Text(
                    clip.text,
                    color = Tokens.TextPrimary,
                    style = if (clip.big) MaterialTheme.typography.displaySmall
                    else MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)
                )
            }
        }

        if (exporting) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    String.format("%.0f%%", exportProgress * 100f),
                    color = Tokens.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                Pill("Cancel") { runningExport?.cancel() }
            }
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(3.dp))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(exportProgress.coerceIn(0f, 1f))
                        .height(6.dp)
                        .background(Tokens.Accent, RoundedCornerShape(3.dp))
                )
            }
        }
        exportMessage?.let {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    it,
                    color = Tokens.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                exported?.let { file ->
                    Spacer(Modifier.width(8.dp))
                    Pill("Share") { Publish.share(context, file) }
                }
            }
        }

        // Transport
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BigButton(if (playing) "Pause" else "Play") {
                playing = !playing
                state.engine.studioPlaying = playing
            }
            BigButton(if (project.isFrozenAt(playhead)) "Unfreeze" else "Freeze") {
                project.toggleFreeze(playhead)
                revision++
            }
            BigButton("Key ◆", primary = true) {
                val current = if (selectedTrack == TrackKind.FLASH) flashValue
                else currentValueFor(state, selectedTrack, playhead)
                project.track(selectedTrack).set(playhead, current, easing)
                revision++
            }
            Spacer(Modifier.weight(1f))
            Text(
                String.format("%.1f / %.0fs", playhead, project.duration),
                color = Tokens.TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Scrubber
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                .pointerInput(project.duration) {
                    detectHorizontalDragGestures { change, _ ->
                        val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        playhead = fraction * project.duration
                        playing = false
                        state.engine.studioPlaying = false
                        state.engine.seek(playhead)
                    }
                }
        ) {
            Box(
                Modifier
                    .fillMaxWidth((playhead / project.duration).coerceIn(0f, 1f))
                    .height(34.dp)
                    .background(Tokens.Accent.copy(alpha = 0.22f), RoundedCornerShape(8.dp))
            )
            Text(
                "drag to scrub",
                color = Tokens.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Tracks
        Spacer(Modifier.height(10.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SectionLabel("Track")
            ChipRow(
                TrackKind.entries.map { it.title },
                TrackKind.entries.indexOf(selectedTrack)
            ) { selectedTrack = TrackKind.entries[it] }

            SectionLabel("Easing for the next key")
            ChipRow(Easing.entries.map { it.title }, Easing.entries.indexOf(easing)) {
                easing = Easing.entries[it]
            }

            if (selectedTrack == TrackKind.FLASH) {
                ValueSlider(
                    "Flash to key",
                    flashValue,
                    0f,
                    1f,
                    valueText = String.format("%.0f%%", flashValue * 100f)
                ) { flashValue = it }
            }

            val version = revision
            SectionLabel("Keyframes")
            TrackStrip(project, selectedTrack, playhead, version)

            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Remove key here") {
                    project.track(selectedTrack).removeNear(playhead)
                    revision++
                }
                Pill("Clear track") {
                    project.track(selectedTrack).clear()
                    revision++
                }
            }

            SectionLabel("Length")
            ValueSlider("Duration", project.duration, 4f, 60f, valueText = String.format("%.0f s", project.duration)) {
                project.duration = it
                revision++
            }

            SectionLabel("Template")
            ChipRow(Project.TEMPLATES, Project.TEMPLATES.indexOf(project.name).coerceAtLeast(0)) { index ->
                val fresh = Project.template(
                    Project.TEMPLATES[index], state.currentDef.id, state.engine.core.seed
                )
                state.project = fresh
                state.engine.project = fresh
                state.engine.seek(0f)
                playhead = 0f
                revision++
            }

            SectionLabel("Captions")
            CaptionEditor(
                text = captionText,
                big = captionBig,
                onText = { captionText = it },
                onBig = { captionBig = it },
                onAdd = {
                    val trimmed = captionText.trim()
                    if (trimmed.isNotEmpty()) {
                        project.addText(trimmed, playhead, captionBig)
                        captionText = ""
                        revision++
                    }
                }
            )
            val captionVersion = revision
            remember(captionVersion) { project.texts.toList() }.forEach { clip ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        String.format("%.1fs  %s", clip.start, clip.text),
                        color = Tokens.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    Spacer(Modifier.width(8.dp))
                    Pill("Remove") {
                        project.texts.remove(clip)
                        revision++
                    }
                }
            }

            SectionLabel("Export format")
            ChipRow(ExportSettings.NAMES, formatIndex) { formatIndex = it }

            Text(
                "Rendered offline from the seed, so the file is smooth even if the " +
                    "preview is not.",
                color = Tokens.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
            )
        }
    }
}

/** A simple strip showing where the keys sit on the selected track. */
@Composable
private fun TrackStrip(project: Project, kind: TrackKind, playhead: Float, version: Int) {
    val keys = remember(version, kind) { project.track(kind).keyframes() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(8.dp))
    ) {
        if (keys.isEmpty()) {
            Text(
                "no keys yet — set one with Key",
                color = Tokens.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        // Laid out by offset rather than by nested full-width boxes: a key at
        // t=0 used to have width zero, which made it invisible.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val span = maxWidth - MARKER
            keys.forEach { k ->
                val fraction = (k.time / project.duration.coerceAtLeast(0.001f)).coerceIn(0f, 1f)
                Text(
                    "◆",
                    color = Tokens.Accent,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .offset(x = span * fraction)
                        .align(Alignment.CenterStart)
                )
            }
            val head = (playhead / project.duration.coerceAtLeast(0.001f)).coerceIn(0f, 1f)
            Box(
                Modifier
                    .offset(x = span * head + MARKER / 2)
                    .width(2.dp)
                    .height(40.dp)
                    .background(Tokens.Success)
            )
        }
    }
}

/** What the live engine is showing right now for this track. */
private fun currentValueFor(state: AppState, kind: TrackKind, time: Float): Float = when (kind) {
    TrackKind.CAMERA_X -> state.engine.camera.x
    TrackKind.CAMERA_Y -> state.engine.camera.y
    TrackKind.ZOOM -> state.engine.camera.zoom
    TrackKind.ROTATION -> state.engine.camera.rotation
    TrackKind.SPEED -> state.engine.core.speed
    TrackKind.GRAVITY -> state.laws.gravity
    TrackKind.BOUNCINESS -> state.laws.restitution
    TrackKind.TRAILS -> state.trails
    // Flash is keyed from Studio's own slider, not from here.
    TrackKind.FLASH -> state.project?.track(TrackKind.FLASH)?.valueAt(time) ?: 0f
}

/** Type a caption, choose its size, drop it at the playhead. */
@Composable
private fun CaptionEditor(
    text: String,
    big: Boolean,
    onText: (String) -> Unit,
    onBig: (Boolean) -> Unit,
    onAdd: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 11.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (text.isEmpty()) {
                Text(
                    "Caption at the playhead",
                    color = Tokens.TextSecondary.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            BasicTextField(
                value = text,
                onValueChange = onText,
                singleLine = true,
                textStyle = TextStyle(color = Tokens.TextPrimary, fontSize = 15.sp),
                cursorBrush = SolidColor(Tokens.Accent),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Big", selected = big) { onBig(true) }
            Pill("Small", selected = !big) { onBig(false) }
            Spacer(Modifier.weight(1f))
            Pill("Add here", selected = true) { onAdd() }
        }
    }
}

/** Width of a keyframe marker, kept out of the strip's usable span. */
private val MARKER = 14.dp
