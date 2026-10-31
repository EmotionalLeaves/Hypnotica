package app.hypnotica.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.hypnotica.core.audio.Song
import app.hypnotica.core.director.Clip
import app.hypnotica.core.director.ClipDirector
import app.hypnotica.core.sim.TrapFormat
import app.hypnotica.core.studio.Easing
import app.hypnotica.core.studio.Project
import app.hypnotica.core.studio.TextClip
import app.hypnotica.core.studio.TrackKind
import app.hypnotica.export.ExportSettings
import app.hypnotica.export.VideoExporter
import app.hypnotica.export.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Make a batch of Shorts and post them to the connected YouTube channel.
 *
 * Each one is a TickTock clip — a sim, a seed and settings that are known to
 * reach their moment — rendered upright at 1080 × 1920 with its hook as the
 * opening caption, cut to end a few seconds after the moment (never past
 * 58 s, so YouTube files it as a Short), uploaded, and the file deleted.
 * One at a time, in order: rendering is the phone's whole CPU.
 */
class ShortsUploader(private val prefs: Prefs?) {

    /** The channel uploads go to, or null when nothing is connected. */
    var channel by mutableStateOf(prefs?.youTubeChannel)
        private set

    var count by mutableIntStateOf(prefs?.youTubeCount ?: 3)
        private set

    var privacy by mutableIntStateOf(prefs?.youTubePrivacy ?: 0)
        private set

    var busy by mutableStateOf(false)
        private set

    /** What is happening now, in words. */
    var status by mutableStateOf<String?>(null)
        private set

    /** Share of the whole batch done, 0..1. */
    var progress by mutableFloatStateOf(0f)
        private set

    /** Posted this session: title and link, newest last. */
    val posted = mutableStateListOf<Pair<String, String>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    @Volatile private var stopAsked = false
    @Volatile private var exporter: VideoExporter? = null
    /** Its own director, checking payoffs at the video's shape, 1080 × 1920. */
    private val director = ClipDirector().apply {
        aspect = ExportSettings.VERTICAL.width.toFloat() / ExportSettings.VERTICAL.height
        // A Short is at most 58 s and should land its moment well before.
        maxPayoffSeconds = 38f
    }

    fun chooseCount(n: Int) { count = n.coerceIn(1, MAX_COUNT); prefs?.youTubeCount = count }

    fun choosePrivacy(i: Int) { privacy = i.coerceIn(0, 2); prefs?.youTubePrivacy = privacy }

    /** Remember the channel a fresh token belongs to. */
    fun connected(token: String) {
        status = "Connected. Checking the channel…"
        scope.launch {
            val name = withContext(Dispatchers.IO) { YouTube.channelName(token) }
            channel = name ?: "your channel"
            prefs?.youTubeChannel = channel
            status = null
        }
    }

    fun failed(message: String) { status = message }

    /** Forget the channel here. The grant itself is removed from the Google account's settings. */
    fun disconnect() {
        stop()
        channel = null
        prefs?.youTubeChannel = null
        status = "Disconnected here. To remove the access entirely, open myaccount.google.com → Security → Third-party connections."
    }

    fun stop() {
        stopAsked = true
        exporter?.cancel()
    }

    /** Make and post [count] Shorts. */
    fun start(context: Context) {
        if (busy) return
        val app = context.applicationContext
        busy = true
        stopAsked = false
        progress = 0f
        job = scope.launch {
            var done = 0
            try {
                for (i in 0 until count) {
                    if (stopAsked) break
                    val n = "${i + 1} of $count"
                    status = "Finding a good one ($n)…"
                    val clip = withContext(Dispatchers.Default) { runInterruptible { director.generate() } }
                        ?: throw IllegalStateException("Couldn't find a clip that pays off. Try again.")
                    if (stopAsked) break
                    status = "Rendering ${clip.def.title} ($n)…"
                    val file = File(app.cacheDir, "short-${System.currentTimeMillis()}.mp4")
                    try {
                        render(clip, file) { p -> progress = (i + p * RENDER_SHARE) / count }
                        if (stopAsked) break
                        // A fresh token each time: one lasts an hour, a batch can take longer.
                        val auth = YouTube.authorize(app)
                        val token = auth.accessToken
                        if (auth.hasResolution() || token == null) {
                            // The grant was withdrawn or ran out: back to Connect.
                            channel = null
                            prefs?.youTubeChannel = null
                            throw IllegalStateException("YouTube needs you to connect again — tap Connect YouTube.")
                        }
                        status = "Uploading ${clip.def.title} ($n)…"
                        val details = detailsFor(clip)
                        val id = withContext(Dispatchers.IO) {
                            YouTube.upload(token, file, details,
                                progress = { p -> progress = (i + RENDER_SHARE + p * (1f - RENDER_SHARE)) / count },
                                cancelled = { stopAsked })
                        }
                        posted.add(details.title to "https://youtube.com/shorts/$id")
                        done++
                    } finally {
                        file.delete()
                    }
                    progress = (i + 1f) / count
                    // A breath between uploads: back to back, the API's per-minute
                    // limits can refuse the next one.
                    if (i < count - 1) delay(GAP_MS)
                }
                status = when {
                    stopAsked -> "Stopped. $done posted."
                    done == 1 -> "Posted 1 Short."
                    else -> "Posted $done Shorts."
                } + if (done > 0 && privacy != 0) PRIVATE_NOTE else ""
            } catch (e: InterruptedException) {
                status = "Stopped. $done posted."
            } catch (e: Throwable) {
                status = (if (done > 0) "$done posted, then: " else "") + (e.message ?: "Something went wrong.")
            } finally {
                busy = false
                exporter = null
            }
        }
    }

    /** The clip as an upright video, its hook up front and no flashes. */
    private suspend fun render(clip: Clip, out: File, onProgress: (Float) -> Unit) {
        val payoff = clip.payoffSeconds
        val length = maxOf(payoff / 0.7f, payoff + 3f).coerceIn(MIN_SECONDS, MAX_SECONDS)
        val project = Project(clip.def.id, clip.seed)
        project.name = "Short"
        project.duration = length
        // The same gentle lean in towards the moment that TickTock gives it.
        if (payoff in 1f..length) {
            project.track(TrackKind.ZOOM).set(0f, 1f)
            project.track(TrackKind.ZOOM).set(payoff, 1.1f, Easing.EASE_IN_OUT)
            project.track(TrackKind.ZOOM).set(minOf(length, payoff + 1.5f), 1f, Easing.EASE_IN_OUT)
        }
        project.texts.add(TextClip(clip.caption, 0.3f, minOf(3.5f, length * 0.3f)))
        val exporter = VideoExporter(
            def = clip.def,
            project = project,
            laws = clip.laws,
            paletteName = clip.palette,
            soundPresetName = clip.soundPreset,
            params = clip.params,
            song = if (clip.format == TrapFormat.SONG) Song.TUNES[((clip.seed ushr 23) and 0xFFFF).toInt() % Song.TUNES.size] else null,
            settings = ExportSettings.VERTICAL,
            trails = clip.trails
        )
        this.exporter = exporter
        coroutineScopeProgress(exporter, onProgress) {
            withContext(Dispatchers.Default) { exporter.export(out) }
        }
    }

    private suspend fun coroutineScopeProgress(exporter: VideoExporter, onProgress: (Float) -> Unit, body: suspend () -> Unit) {
        val watcher = scope.launch { while (true) { onProgress(exporter.progress); delay(200) } }
        try { body() } finally { watcher.cancel() }
    }

    private fun detailsFor(clip: Clip): YouTube.Details {
        val hook = clip.caption.trimEnd('?', '!', '.', ' ')
        val title = "${clip.caption} ${clip.def.title} #shorts".let { if (it.length > 100) "$hook #shorts" else it }
        val description = buildString {
            append(clip.def.blurb)
            append("\n\nA simulation, not an animation: every frame is computed.")
            append("\nMade with Hypnotica.")
            append("\n\n#shorts #satisfying #simulation")
        }
        val tags = listOf("shorts", "satisfying", "simulation", clip.def.title, clip.def.family, clip.format.title)
        return YouTube.Details(title, description, tags, PRIVACY[privacy])
    }

    companion object {
        const val MAX_COUNT = 10
        val COUNTS = listOf(1, 2, 3, 5, 10)
        val PRIVACY = listOf("private", "unlisted", "public")
        val PRIVACY_NAMES = listOf("Private", "Unlisted", "Public")
        private const val MIN_SECONDS = 8f
        private const val MAX_SECONDS = 58f
        private const val RENDER_SHARE = 0.7f
        private const val GAP_MS = 3000L
        private const val PRIVATE_NOTE =
            " If they show as private, that's YouTube: uploads from a Google Cloud project that hasn't passed its API audit stay private."
    }
}
