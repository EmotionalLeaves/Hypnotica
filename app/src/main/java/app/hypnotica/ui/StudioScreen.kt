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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.BiasAlignment
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
 * The shape of the slot the preview sits in. The picture is letterboxed inside
 * it, so switching export format never reflows the screen under your thumb.
 */
private const val PREVIEW_SLOT = 0.62f

/**
 * Studio: the sim as footage.
 *
 * The timeline holds keyframes, not frames, so a project is a few kilobytes and
 * can be re-rendered at any size later.
 */
@Composable
fun StudioScreen(state: AppState, surface: SimSurfaceView) {
    // The project belongs to the state, not to this composition; making one
    // inside a remember would write snapshot state during composition.
    val project = state.projectForStudio()
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
    // The value the next key will take. Every track gets one, not just Flash:
    // the other eight could only be keyed to whatever the timeline was already
    // producing, which meant they could not be authored at all.
    var keyValue by remember { mutableFloatStateOf(0f) }
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
            // An export is a blocking job on another thread: cancelling the
            // composition does not touch it, so it is told to stop here.
            runningExport?.cancel()
            // Hand the sim back as the player left it, laws and all.
            state.engine.detachProject(state.laws, state.trails, state.speed)
        }
    }

    LaunchedEffect(selectedTrack, state.currentDef.id) {
        keyValue = currentValueFor(state, selectedTrack, playhead)
            .coerceIn(selectedTrack.min, selectedTrack.max)
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
                            settings = settings,
                            trails = state.trails,
                            safety = state.engine.safety
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
                            if (exporter.audioTruncated) {
                                outcome.message + " The sound is short — the encoder ran out of time."
                            } else {
                                outcome.message
                            }
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

        // Preview. The frame keeps the same slot on screen whichever format is
        // chosen, and the picture inside it is letterboxed to that format's
        // shape: the renderer reads the view's own aspect, so lining a shot up
        // here is lining up the file.
        val format = ExportSettings.ALL[formatIndex]
        val previewAspect = format.width.toFloat() / format.height.toFloat()
        val tallerThanSlot = previewAspect <= PREVIEW_SLOT
        Box(
            Modifier.fillMaxWidth().aspectRatio(PREVIEW_SLOT),
            contentAlignment = Alignment.Center
        ) {
            Box(
                (if (tallerThanSlot) Modifier.fillMaxHeight() else Modifier.fillMaxWidth())
                    .aspectRatio(previewAspect, matchHeightConstraintsFirst = tallerThanSlot)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            ) {
                SimCanvas(
                    surface = surface,
                    modifier = Modifier.fillMaxSize(),
                    cameraMode = true
                )
                project.textAt(playhead)?.let { clip ->
                    // Where TextOverlay will actually put it: clip space is y-up,
                    // Compose's bias is y-down, hence the sign.
                    val centreY = if (clip.big) 0.18f else -0.74f
                    Text(
                        clip.text,
                        color = Tokens.TextPrimary,
                        style = if (clip.big) MaterialTheme.typography.displaySmall
                        else MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .align(BiasAlignment(0f, -centreY))
                            .padding(horizontal = 12.dp)
                    )
                }
            }
        }

        if (state.engine.seeking) {
            Text(
                "catching up…",
                color = Tokens.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
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
                // A view framed by hand wins: posing with a twist or a drag and
                // then keying is the whole authoring flow for a camera move.
                val value = if (isCameraTrack(selectedTrack) && state.engine.cameraPosed) {
                    currentValueFor(state, selectedTrack, playhead)
                } else {
                    keyValue
                }
                project.track(selectedTrack).set(playhead, value, easing)
                state.engine.releaseCamera()
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

            // Dragging this previews the value on screen as well as choosing
            // it, so a key is always set to something that has been seen.
            ValueSlider(
                "Value to key",
                keyValue,
                selectedTrack.min,
                selectedTrack.max,
                valueText = keyValueText(selectedTrack, keyValue)
            ) { v ->
                keyValue = v
                previewKeyValue(state, selectedTrack, v)
            }
            if (isCameraTrack(selectedTrack)) {
                Text(
                    "Or frame it by hand — drag and twist the preview, then Key.",
                    color = Tokens.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            val version = revision
            SectionLabel("Keyframes")
            TrackStrip(project, selectedTrack, playhead, version) { from, to ->
                if (project.track(selectedTrack).moveNear(from, to)) revision++
            }

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
            // A template replaces the project outright. After Record moves the
            // row used to highlight a template that was not loaded, so tapping
            // it to confirm what you were looking at destroyed the recording.
            if (Project.TEMPLATES.indexOf(project.name) < 0) {
                Text(
                    "Showing \"" + project.name + "\". Choosing a template replaces it.",
                    color = Tokens.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            ChipRow(Project.TEMPLATES, Project.TEMPLATES.indexOf(project.name)) { index ->
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
private fun TrackStrip(
    project: Project,
    kind: TrackKind,
    playhead: Float,
    version: Int,
    onMove: (Float, Float) -> Unit
) {
    val keys = remember(version, kind) { project.track(kind).keyframes() }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(8.dp))
    ) {
        // Keys can be dragged along the strip, which the guide has promised
        // since the strip was built: without it a recorded move could only be
        // thrown away, never adjusted.
        val duration = project.duration.coerceAtLeast(0.001f)
        var dragFrom by remember(version, kind) { mutableFloatStateOf(-1f) }
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(version, kind, duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragFrom = (offset.x / size.width).coerceIn(0f, 1f) * duration
                        },
                        onDragEnd = { dragFrom = -1f },
                        onDragCancel = { dragFrom = -1f }
                    ) { change, _ ->
                        if (dragFrom < 0f) return@detectHorizontalDragGestures
                        val to = (change.position.x / size.width).coerceIn(0f, 1f) * duration
                        onMove(dragFrom, to)
                        dragFrom = to
                    }
                }
        )
        if (keys.isEmpty()) {
            Text(
                "no keys yet — set one with Key, or drag one along once it is there",
                color = Tokens.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        // Laid out by offset rather than by nested full-width boxes: a key at
        // t=0 used to have width zero, which made it invisible.
        val span = maxWidth - MARKER
        keys.forEach { k ->
            val fraction = (k.time / duration).coerceIn(0f, 1f)
            Text(
                "◆",
                color = Tokens.Accent,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .offset(x = span * fraction)
                    .align(Alignment.CenterStart)
            )
        }
        val head = (playhead / duration).coerceIn(0f, 1f)
        Box(
            Modifier
                .offset(x = span * head + MARKER / 2)
                .width(2.dp)
                .height(40.dp)
                .background(Tokens.Success)
        )
    }
}

private fun isCameraTrack(kind: TrackKind): Boolean = when (kind) {
    TrackKind.CAMERA_X, TrackKind.CAMERA_Y, TrackKind.ZOOM, TrackKind.ROTATION -> true
    else -> false
}

/** The value in the units the track is actually in. */
private fun keyValueText(kind: TrackKind, v: Float): String = when (kind) {
    TrackKind.FLASH, TrackKind.TRAILS -> String.format("%.0f%%", v * 100f)
    TrackKind.ROTATION -> String.format("%.0f°", v * 180f / Math.PI.toFloat())
    TrackKind.ZOOM, TrackKind.SPEED -> String.format("%.2fx", v)
    else -> String.format("%.2f", v)
}

/**
 * Show the value that is about to be keyed, so a key is never set to a number
 * nobody has seen. Flash is the exception: a flash is one frame, and previewing
 * it on every slider pixel would be a strobe.
 */
private fun previewKeyValue(state: AppState, kind: TrackKind, v: Float) {
    val engine = state.engine
    when (kind) {
        TrackKind.CAMERA_X -> { engine.camera.x = v; engine.poseCamera() }
        TrackKind.CAMERA_Y -> { engine.camera.y = v; engine.poseCamera() }
        TrackKind.ZOOM -> { engine.setBaseZoom(v); engine.poseCamera() }
        TrackKind.ROTATION -> { engine.camera.rotation = v; engine.poseCamera() }
        TrackKind.SPEED -> engine.core.speed = v
        TrackKind.TRAILS -> engine.scene.trails = v
        TrackKind.GRAVITY -> engine.core.setLaws(engine.core.env.laws.copy(gravity = v))
        TrackKind.BOUNCINESS -> engine.core.setLaws(engine.core.env.laws.copy(restitution = v))
        TrackKind.FLASH -> Unit
    }
}

/** What the live engine is showing right now for this track. */
private fun currentValueFor(state: AppState, kind: TrackKind, time: Float): Float = when (kind) {
    TrackKind.CAMERA_X -> state.engine.camera.x
    TrackKind.CAMERA_Y -> state.engine.camera.y
    TrackKind.ZOOM -> state.engine.camera.zoom
    TrackKind.ROTATION -> state.engine.camera.rotation
    TrackKind.SPEED -> state.engine.core.speed
    // The laws the sim is running under, not the player's: with a project
    // attached the timeline may be driving them, and keying the player's value
    // recorded a number that was not on screen.
    TrackKind.GRAVITY -> state.engine.core.env.laws.gravity
    TrackKind.BOUNCINESS -> state.engine.core.env.laws.restitution
    TrackKind.TRAILS -> state.engine.scene.trails
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
