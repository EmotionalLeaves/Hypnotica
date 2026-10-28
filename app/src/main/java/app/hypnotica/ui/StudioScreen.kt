package app.hypnotica.ui

import app.hypnotica.core.studio.parseProject
import app.hypnotica.core.studio.projectJson
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hypnotica.core.studio.Easing
import app.hypnotica.core.studio.Project
import app.hypnotica.core.studio.TrackKind
import app.hypnotica.export.ExportSettings
import app.hypnotica.export.Publish
import app.hypnotica.export.VideoExporter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The shape of the slot the preview sits in. The picture is letterboxed inside
 * it, so switching export format never reflows the screen under your thumb.
 */
private const val PREVIEW_SLOT = 1f

/**
 * How tall the preview is allowed to get, for a screen that is not a phone held
 * upright. In portrait the square slot above is already shorter than this.
 */
private val PREVIEW_MAX_HEIGHT = 360.dp

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

    /** True when the last export ended in the catch, so the line can be red. */
    var exportFailed by remember { mutableStateOf(false) }
    var captionText by remember { mutableStateOf("") }
    var captionBig by remember { mutableStateOf(true) }
    // Flash has nothing live to read, so Studio holds the value to be keyed.
    // The value the next key will take. Every track gets one, not just Flash:
    // the other eight could only be keyed to whatever the timeline was already
    // producing, which meant they could not be authored at all.
    var keyValue by remember { mutableFloatStateOf(0f) }

    /** The template waiting for a second tap, or -1. */
    var confirmTemplate by remember { mutableIntStateOf(-1) }

    /** Clearing a track takes two taps, like replacing a template does. */
    // Keyed on the track: armed on Zoom, the pill still read "sure?" after
    // switching to Speed, and the next tap wiped Speed with no warning.
    var confirmClear by remember(selectedTrack) { mutableStateOf(false) }
    // An armed confirmation stands down by itself after a few seconds.
    LaunchedEffect(confirmClear, confirmTemplate) {
        if (!confirmClear && confirmTemplate < 0) return@LaunchedEffect
        delay(CONFIRM_MS)
        confirmClear = false
        confirmTemplate = -1
        state.notice = null
    }
    var exportProgress by remember { mutableFloatStateOf(0f) }
    var runningExport by remember { mutableStateOf<VideoExporter?>(null) }
    var formatIndex by remember { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // Undo: the project as it was before each edit, newest last. Every edit
    // already bumps the revision, so that is where the snapshot is taken.
    val undo = remember { ArrayDeque<String>() }
    var lastSnapshot by remember { mutableStateOf(projectJson(project)) }
    var undoing by remember { mutableStateOf(false) }
    LaunchedEffect(revision) {
        val now = projectJson(project)
        if (undoing) {
            undoing = false
        } else if (now != lastSnapshot) {
            undo.addLast(lastSnapshot)
            while (undo.size > UNDO_DEPTH) undo.removeFirst()
        }
        lastSnapshot = now
    }

    // Saved shortly after every edit, so the work outlives the process.
    LaunchedEffect(revision) {
        if (revision == 0) return@LaunchedEffect
        delay(SAVE_AFTER_MS)
        state.saveProject()
    }
    val context = LocalContext.current

    DisposableEffect(Unit) {
        state.engine.project = project
        state.engine.studioPlaying = false
        // Pause survives into Studio otherwise, and nothing here clears it: the
        // route Player -> Pause -> back -> Settings -> Studio left Play
        // flipping its label and counting up over a frozen still.
        state.engine.core.paused = false
        state.paused = false
        state.engine.seek(0f)
        onDispose {
            state.saveProject()
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

    // engine.seeking is a plain volatile the GL thread writes, so nothing told
    // Compose when it went false: the label appeared during a scrub, because
    // the playhead was changing, and then stayed up until some unrelated
    // interaction redrew the screen — shifting the layout while it was there.
    var catchingUp by remember { mutableStateOf(false) }
    LaunchedEffect(playing) {
        state.engine.studioPlaying = playing
        // No exit: a scrub sets seeking without changing `playing`, so a loop
        // that stopped when paused would never see it clear. The effect is
        // cancelled when the screen goes away, which is the right end for it.
        while (true) {
            delay(50)
            if (playing) playhead = state.engine.studioTime
            val now = state.engine.seeking
            if (now != catchingUp) catchingUp = now
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Tokens.Bg)
            .safeDrawingPadding()
            .padding(horizontal = 16.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton("‹") { state.goBack() }
            Spacer(Modifier.width(8.dp))
            Text("Studio", color = Tokens.TextPrimary, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            // Undo the last edit. There was none: a slip of the thumb on the
            // timeline, or a template over an hour's keys, was permanent.
            run {
                GlyphButton("↶", selected = false) {
                    val previous = undo.removeLastOrNull()
                    if (previous == null) {
                        state.notice = "Nothing to undo."
                    } else {
                        parseProject(previous)?.let { project.restoreFrom(it) }
                        undoing = true
                        revision++
                        state.engine.seek(playhead)
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            BigButton(
            if (exporting) "Exporting…" else "Export",
            Modifier.widthIn(min = 132.dp),
            primary = true
        ) {
                if (!exporting) {
                    exporting = true
                    exportMessage = null
                    // Cleared with the message. It was not, so an export that
                    // failed after one that worked showed the failure with a
                    // live Share button beside it — which shared the OLD file.
                    exported = null
                    exportFailed = false
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
                            groove = state.engine.mapping.groove,
                            song = app.hypnotica.core.audio.Song.byIndex(state.songIndex).takeIf { it.notes.isNotEmpty() },
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
                            // MediaCodec and MediaMuxer failures routinely carry
                            // a null message, so this used to read "Export
                            // failed: null" — a Java field name shown to someone
                            // who just wanted a video. Say what to try instead,
                            // and keep the detail only when there is one.
                            exportFailed = true
                            val detail = e.message?.takeIf { m -> m.isNotBlank() && m.length < 80 }
                            "Could not finish the export. Try the smaller format, " +
                                "or a shorter duration." + (detail?.let { d -> " ($d)" } ?: "")
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
        // A full-width square, which is what docs/images/studio.svg has always
        // specified: 358 by 360 of a 390 by 844 screen, with the editor below
        // it. The code had 0.62 — taller than wide, about 1.6 times the screen
        // width, some 620 dp of an 860 dp phone — and the editor below had no
        // weight, so it was measured against what was left: fifteen dp on a
        // large phone, none on a small one. Fifteen of this screen's
        // twenty-two controls were a grey sliver or absent.
        //
        // Square also letterboxes every export shape without reflowing, which
        // is what the slot is for: a 9:16 fits as 201 wide, a 16:9 as 201 tall.
        // The height cap only binds on a screen wider than it is tall.
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = PREVIEW_MAX_HEIGHT)
                .aspectRatio(PREVIEW_SLOT),
            contentAlignment = Alignment.Center
        ) {
            Box(
                // No rounded clip and no hairline frame: the sim is the
                // subject, and a border around it makes the thing you are
                // editing look like a thumbnail of itself.
                (if (tallerThanSlot) Modifier.fillMaxHeight() else Modifier.fillMaxWidth())
                    .aspectRatio(previewAspect, matchHeightConstraintsFirst = tallerThanSlot)
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

        // Transport
        Spacer(Modifier.height(8.dp))
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
                project.track(selectedTrack).set(playhead, value, easing, project.grab)
                state.engine.releaseCamera()
                revision++
            }
        }

        // Scrubber
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                .pointerInput(project.duration) {
                    // The playhead follows the finger every sample; the sim is
                    // asked to catch up when the finger stops. Seeking on every
                    // delta meant a hundred seeks a second, each of which the
                    // next one interrupted.
                    detectHorizontalDragGestures(
                        onDragStart = {
                            playing = false
                            state.engine.studioPlaying = false
                        },
                        onDragEnd = { state.engine.seek(playhead) },
                        onDragCancel = { state.engine.seek(playhead) }
                    ) { change, _ ->
                        val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        playhead = fraction * project.duration
                    }
                }
        ) {
            val fill by animateFloatAsState(
                (playhead / project.duration).coerceIn(0f, 1f),
                tween(60, easing = LinearEasing),
                label = "scrubFill"
            )
            Box(
                Modifier
                    .fillMaxWidth(fill)
                    .height(48.dp)
                    .background(Tokens.Accent.copy(alpha = 0.22f), RoundedCornerShape(12.dp))
            )
            // The time sits on the scrubber. At the end of the button row it was
            // left forty-odd dp on a phone and cut off mid-number.
            Text(
                String.format(java.util.Locale.US, "%.1f / %.0fs  ·  drag", playhead, project.duration),
                color = Tokens.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // The run's status sits here, between the scrubber and the editor,
        // rather than above the transport row.
        //
        // These four blocks appear and disappear — a notice, the catching-up
        // line, the export progress, the export result — and above the
        // transport they moved it and the scrubber down by as much as 176 dp
        // while a finger was on them. Below the scrubber the only thing they
        // can push is the editor, which has weight(1f) and absorbs it.
        NoticeLine(state.notice)

        Box(Modifier.fillMaxWidth().height(20.dp)) {
            val fade by animateFloatAsState(
                if (catchingUp) 1f else 0f,
                tween(Tokens.Fast),
                label = "catchingUp"
            )
            if (fade > 0.01f) {
                Text(
                    "catching up…",
                    color = Tokens.TextSecondary.copy(alpha = fade),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        if (exporting) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    String.format(java.util.Locale.US, "%.0f%%", exportProgress * 100f),
                    color = Tokens.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                Pill("Cancel") { runningExport?.cancel() }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(3.dp))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(exportProgress.coerceIn(0f, 1f))
                        .height(8.dp)
                        .background(Tokens.Accent, RoundedCornerShape(3.dp))
                )
            }
        }
        exportMessage?.let {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    it,
                    // "In your gallery", "Cancelled." and a failure were all the
                    // same grey.
                    color = if (exportFailed) Tokens.Danger else Tokens.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                exported?.let { file ->
                    Spacer(Modifier.width(8.dp))
                    Pill("Share") { Publish.share(context, file) }
                }
            }
        }

        // Tracks
        Spacer(Modifier.height(8.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SectionLabel("Track")
            ChipRow(
                TrackKind.entries.map { it.title },
                TrackKind.entries.indexOf(selectedTrack)
            ) { selectedTrack = TrackKind.entries[it] }

            val firstKey = remember(revision, selectedTrack) {
                project.track(selectedTrack).size == 0
            }
            SectionLabel(
                if (firstKey) "Easing — nothing to ease from yet"
                else "Easing into the next key"
            )
            ChipRow(
                Easing.entries.map { it.title },
                Easing.entries.indexOf(easing),
                enabled = !firstKey
            ) {
                easing = Easing.entries[it]
            }

            // Dragging this previews the value on screen as well as choosing
            // it, so a key is always set to something that has been seen —
            // except for the four tracks where there is nothing to see yet.
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
            // Flash has nothing live to show, and speed, gravity and bounciness
            // only show once the sim steps — which it does not do with a project
            // attached unless Play is on. Without this the slider moved, the
            // readout changed and the picture sat still, which reads as broken.
            if (!previewsOnScreen(selectedTrack)) {
                Text(
                    if (selectedTrack == TrackKind.FLASH) {
                        "Flash only shows in the export and while playing."
                    } else {
                        "${selectedTrack.title} only shows while playing — press Play to see it."
                    },
                    color = Tokens.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
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
                if (project.track(selectedTrack).moveNear(from, to, project.grab)) revision++
            }

            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Remove key here") {
                    // Saying so when there is nothing to remove: silence here
                    // read as a dead button, and with the old quarter-second
                    // window it was silent most of the time.
                    if (project.track(selectedTrack).removeNear(playhead, project.grab)) {
                        revision++
                    } else {
                        state.notice = "No key here — scrub onto a ◆ first."
                    }
                }
                val keyCount = remember(revision, selectedTrack) {
                    project.track(selectedTrack).size
                }
                Pill(
                    if (confirmClear) "Clear track — sure?" else "Clear track",
                    selected = confirmClear,
                    danger = true
                ) {
                    when {
                        keyCount == 0 ->
                            state.notice = "That track has no keys to clear."
                        !confirmClear -> {
                            confirmClear = true
                            state.notice =
                                "Tap again to remove all $keyCount keys on " +
                                "${selectedTrack.title}. There is no undo."
                        }
                        else -> {
                            confirmClear = false
                            state.notice = null
                            project.track(selectedTrack).clear()
                            revision++
                        }
                    }
                }
            }

            SectionLabel("Length")
            // Read through the revision: the project is a plain object, so this
            // slider only redraws because something in scope asked for it.
            val shownDuration = remember(revision) { project.duration }
            ValueSlider("Duration", shownDuration, 4f, 60f, valueText = String.format(java.util.Locale.US, "%.0f s", shownDuration)) {
                project.duration = it
                revision++
            }

            SectionLabel("Template")
            // "Perfect loop" only where the sim loops: on any other it promises
            // a clip whose last frame meets its first, and the sim never comes
            // back to where it started.
            val templates = remember(state.currentDef.id) {
                Project.TEMPLATES.filter { it != "Perfect loop" || state.currentDef.loopSeconds != null }
            }
            // A template replaces the project outright. After Record moves the
            // row used to highlight a template that was not loaded, so tapping
            // it to confirm what you were looking at destroyed the recording.
            if (confirmTemplate >= 0) {
                Text(
                    "Tap \"" + templates[confirmTemplate] +
                        "\" again to replace every keyframe and caption here.",
                    color = Tokens.Accent,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            } else if (templates.indexOf(project.name) < 0) {
                Text(
                    "Showing \"" + project.name + "\". Choosing a template replaces it.",
                    color = Tokens.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            // Asked once, because a tap replaces every keyframe and caption
            // with no undo — and after Record moves the row used to highlight a
            // template that was not loaded, so tapping it to confirm what you
            // were looking at destroyed the recording.
            ChipRow(templates, templates.indexOf(project.name)) { index ->
                if (confirmTemplate != index && project.hasAnyKeyframes()) {
                    confirmTemplate = index
                    return@ChipRow
                }
                confirmTemplate = -1
                val fresh = Project.template(
                    templates[index], state.currentDef.id, state.engine.core.seed,
                    state.currentDef.typicalSeconds, state.currentDef.loopSeconds
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
                        String.format(java.util.Locale.US, "%.1fs  %s", clip.start, clip.text),
                        color = Tokens.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        // Without this a long caption is cut mid-letter and
                        // nothing says it continues.
                        overflow = TextOverflow.Ellipsis
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
                "Rendered smooth, whatever the preview did.",
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
            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(12.dp))
    ) {
        // Keys can be dragged along the strip, which the guide has promised
        // since the strip was built: without it a recorded move could only be
        // thrown away, never adjusted.
        val duration = project.duration.coerceAtLeast(0.001f)
        // Neither of these may depend on `version`. onMove bumps the revision
        // on every successful move, and a changed pointerInput key tears down
        // the node and cancels the drag in flight — so a key nudged once and
        // then stopped following the finger. The strip still redraws, because
        // `keys` aliases the live list and `version` invalidates TrackStrip.
        var dragFrom by remember(kind) { mutableFloatStateOf(-1f) }
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(kind, duration) {
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

/**
 * Whether dragging "Value to key" shows anything in the paused preview.
 *
 * The camera and the trails are applied to the frame as it is drawn, so they
 * show at once. Speed, gravity and bounciness only become visible once the sim
 * takes a step, and with a project attached it does not step unless Play is on;
 * flash is composited by the exporter and the playing frame loop. For those
 * four the slider moved and the picture did not, which is a dead control until
 * it says why.
 */
private fun previewsOnScreen(kind: TrackKind): Boolean = when (kind) {
    TrackKind.SPEED, TrackKind.GRAVITY, TrackKind.BOUNCINESS, TrackKind.FLASH -> false
    else -> true
}

/** The value in the units the track is actually in. */
private fun keyValueText(kind: TrackKind, v: Float): String = when (kind) {
    TrackKind.FLASH, TrackKind.TRAILS -> String.format(java.util.Locale.US, "%.0f%%", v * 100f)
    TrackKind.ROTATION -> String.format(java.util.Locale.US, "%.0f°", v * 180f / Math.PI.toFloat())
    TrackKind.ZOOM, TrackKind.SPEED -> String.format(java.util.Locale.US, "%.2fx", v)
    else -> String.format(java.util.Locale.US, "%.2f", v)
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
                .heightIn(min = 48.dp)
                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
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
private val MARKER = 16.dp

/** A pause in editing long enough to be worth writing down. */
private const val SAVE_AFTER_MS = 400L

/** How long a two-tap confirmation stays armed. */
private const val CONFIRM_MS = 4000L

/** Edits Studio can step back through. */
private const val UNDO_DEPTH = 40
