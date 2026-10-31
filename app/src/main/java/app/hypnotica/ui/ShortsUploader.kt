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
import app.hypnotica.pilot.Pilot
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

    /** [ROUTE_APP] or [ROUTE_API]. */
    var route by mutableIntStateOf(prefs?.youTubeRoute ?: ROUTE_APP)
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
        // The slow kinds — a perfect loop, a garden growing — make a calm
        // clip and a dull Short.
        mutedFormats.addAll(listOf(TrapFormat.LOOP, TrapFormat.GROW))
    }

    /**
     * A clip where things happen on the way: at least two called moments
     * (a KO, a near miss, a lead change, the payoff itself) if one can be
     * found, then one, then any that pays off.
     */
    private fun lively(): Clip? {
        for ((need, tries) in listOf(2 to 28, 1 to 12, 0 to 12)) {
            director.minCallouts = need
            director.generate(tries)?.let { return it }
        }
        return null
    }

    fun chooseCount(n: Int) { count = n.coerceIn(1, MAX_COUNT); prefs?.youTubeCount = count }

    fun choosePrivacy(i: Int) { privacy = i.coerceIn(0, 2); prefs?.youTubePrivacy = privacy }

    fun chooseRoute(i: Int) { if (!busy) { route = i.coerceIn(0, 1); prefs?.youTubeRoute = route; status = null } }

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
        Pilot.stop()
    }

    /** Make and post [count] Shorts, the chosen way. */
    fun start(context: Context) {
        if (route == ROUTE_APP) startViaApp(context) else startViaApi(context)
    }

    /**
     * Render the whole batch first, while Hypnotica is on screen, then hand
     * them to the YouTube app one at a time with auto-tap stepping through
     * each upload. The files are kept until the next batch: the YouTube app
     * goes on reading one after its upload screen has closed.
     */
    private fun startViaApp(context: Context) {
        if (busy) return
        val app = context.applicationContext
        if (!Pilot.isOn(app)) {
            status = "Turn on Hypnotica auto-tap first (the button above)."
            return
        }
        busy = true
        stopAsked = false
        progress = 0f
        job = scope.launch {
            var done = 0
            try {
                val dir = File(app.getExternalFilesDir(null), "shorts").apply { mkdirs() }
                // Last batch's files: YouTube has long finished with them.
                dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > KEEP_MS) it.delete() }
                val made = ArrayList<Pair<File, Clip>>()
                for (i in 0 until count) {
                    if (stopAsked) break
                    val n = "${i + 1} of $count"
                    status = "Finding a good one ($n)…"
                    val clip = withContext(Dispatchers.Default) { runInterruptible { lively() } }
                        ?: throw IllegalStateException("Couldn't find a clip that pays off. Try again.")
                    if (stopAsked) break
                    status = "Rendering ${clip.def.title} ($n)…"
                    val file = File(dir, "short-${System.currentTimeMillis()}.mp4")
                    render(clip, file, { p -> progress = (i + p) / count * RENDER_SHARE })
                    made.add(file to clip)
                }
                for ((i, pair) in made.withIndex()) {
                    if (stopAsked) break
                    val (file, clip) = pair
                    val caption = captionFor(clip)
                    val title = caption.title
                    status = "Posting ${i + 1} of ${made.size} in YouTube — hands off for a moment…"
                    progress = RENDER_SHARE + (1f - RENDER_SHARE) * i / made.size
                    val uri = androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".files", file)
                    when (val outcome = Pilot.post(app, Pilot.Item(uri, title, caption.description, caption.tags))) {
                        is Pilot.Outcome.Posted -> { done++; posted.add(title to "https://studio.youtube.com") }
                        is Pilot.Outcome.Stopped -> break
                        is Pilot.Outcome.Failed -> throw IllegalStateException(outcome.why)
                    }
                    if (i < made.size - 1) delay(GAP_MS)
                }
                progress = 1f
                status = when {
                    stopAsked -> "Stopped. $done posted."
                    done == 1 -> "Posted 1 Short. YouTube finishes uploading it in the background."
                    else -> "Posted $done Shorts. YouTube finishes uploading them in the background."
                }
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

    /** Make and post [count] Shorts through the YouTube Data API. */
    private fun startViaApi(context: Context) {
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
                    val clip = withContext(Dispatchers.Default) { runInterruptible { lively() } }
                        ?: throw IllegalStateException("Couldn't find a clip that pays off. Try again.")
                    if (stopAsked) break
                    status = "Rendering ${clip.def.title} ($n)…"
                    val file = File(app.cacheDir, "short-${System.currentTimeMillis()}.mp4")
                    try {
                        render(clip, file, { p -> progress = (i + p * RENDER_SHARE) / count })
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

    /**
     * The clip as an upright Short, edited the way the ones that get watched
     * are: the hook up front, the slow middle of the build sped up, back to
     * real time for the last stretch so the moment lands at its own pace, the
     * words the app pops burned in as they happen, a number clip's number
     * big on screen, and a line to answer at the end. No flashes, no shake.
     */
    /**
     * Render [clip] as a Short to [out]: the edit from [plan], a beat under
     * the sim's own notes so its sound is music rather than a buzz, and a
     * soft start. Nothing written on the picture and no voice.
     */
    private suspend fun render(clip: Clip, out: File, onProgress: (Float) -> Unit) {
        val plan = plan(clip)
        // The picture and its sound only: no words on screen and no voice.
        // The caption, description and tags carry the words, on the post.
        plan.project.texts.clear()
        val exporter = VideoExporter(
            def = clip.def,
            project = plan.project,
            laws = clip.laws,
            paletteName = clip.palette,
            soundPresetName = clip.soundPreset,
            params = clip.params,
            groove = grooveFor(clip),
            song = if (clip.format == TrapFormat.SONG) Song.TUNES[((clip.seed ushr 23) and 0xFFFF).toInt() % Song.TUNES.size] else null,
            settings = ExportSettings.VERTICAL,
            trails = clip.trails,
            live = VideoExporter.LiveWords(
                callouts = false,
                count = false,
                fadeInSeconds = FADE_IN_SECONDS
            )
        )
        this.exporter = exporter
        coroutineScopeProgress(exporter, onProgress) {
            withContext(Dispatchers.Default) { exporter.export(out) }
        }
    }

    /** A beat for the clip, as the app's Auto beat picks one: by the clip, nudged in tempo. */
    private fun grooveFor(clip: Clip): app.hypnotica.core.audio.Groove {
        val key = clip.seed
        val mix = (key xor (key ushr 29)) * 0x9E3779B97F4A7C15uL.toLong()
        val pick = app.hypnotica.core.audio.Groove.ALL[((mix ushr 33) % app.hypnotica.core.audio.Groove.ALL.size).toInt()]
        val nudge = (((mix ushr 13) and 0xFF).toInt() / 255f - 0.5f) * 0.16f
        return pick.withTempo(pick.bpm * (1f + nudge))
    }

    private suspend fun coroutineScopeProgress(exporter: VideoExporter, onProgress: (Float) -> Unit, body: suspend () -> Unit) {
        val watcher = scope.launch { while (true) { onProgress(exporter.progress); delay(200) } }
        try { body() } finally { watcher.cancel() }
    }

    private fun detailsFor(clip: Clip): YouTube.Details {
        val c = captionFor(clip)
        return YouTube.Details(c.title, c.description, c.tags, PRIVACY[privacy])
    }

    companion object {
        /** What a post says: its caption (the title), its description, and its tags. */
        class Caption(val title: String, val description: String, val tags: List<String>)

        /**
         * A caption, a description and tags for [clip], in the voice of the
         * Shorts that do well with these: a question or a dare up front, the
         * sim named, a line asking for a comment, and hashtags for what it
         * is. Chosen from a few ways of saying each, by the clip's seed, so a
         * batch doesn't read like one post five times.
         */
        fun captionFor(clip: Clip): Caption {
            val def = clip.def
            val pick = { options: List<String> -> options[((clip.seed ushr 9) and 0x7FFFFFFF).toInt() % options.size] }
            val lead = when (clip.format) {
                TrapFormat.RACE, TrapFormat.COLOR_BATTLE, TrapFormat.LAST_STANDING, TrapFormat.GUESS ->
                    pick(listOf("Pick one before it ends 👀", "Which one wins? 👇", "Call it now — who takes it?", "Choose your colour 👇"))
                TrapFormat.NUMBER_UP -> pick(listOf("How high does it go? 🤯", "Guess the final number 👇", "The number at the end 😳"))
                TrapFormat.ESCAPE -> pick(listOf("Will it get out? 👀", "Does it escape?", "Find the gap…"))
                TrapFormat.SNAP, TrapFormat.CUT, TrapFormat.CHAIN -> pick(listOf("Wait for it… 😳", "Watch the end 👀", "Didn't expect that ending"))
                TrapFormat.FILL -> pick(listOf("So satisfying to watch it fill 😌", "Watch it fill up", "Wait till it's full"))
                TrapFormat.REVEAL -> pick(listOf("Oddly satisfying 😌", "Watch it come clean", "So satisfying"))
                TrapFormat.SONG -> pick(listOf("Every hit is a note 🎶", "Turn the sound on 🔊", "Listen to it play 🎶"))
                else -> pick(listOf("So satisfying 😌", "Can't stop watching this", "Watch till the end 👀"))
            }
            val ask = when (clip.format) {
                TrapFormat.RACE, TrapFormat.COLOR_BATTLE, TrapFormat.LAST_STANDING, TrapFormat.GUESS ->
                    "Comment your pick before the end — did you call it?"
                TrapFormat.NUMBER_UP -> "Comment your guess before the end."
                TrapFormat.ESCAPE -> "Did you think it would make it? Comment below."
                else -> "Watch it again — you'll see something new."
            }
            val shelfTag = when (def.shelf) {
                app.hypnotica.core.sim.Shelf.PHYSICS -> "physics"
                app.hypnotica.core.sim.Shelf.MATH -> "math"
                app.hypnotica.core.sim.Shelf.ALGORITHMS -> "algorithm"
                app.hypnotica.core.sim.Shelf.LIFE -> "nature"
                app.hypnotica.core.sim.Shelf.ART -> "art"
                app.hypnotica.core.sim.Shelf.DIMENSIONS -> "geometry"
            }
            val formatTag = when (clip.format) {
                TrapFormat.RACE -> "marblerace"
                TrapFormat.COLOR_BATTLE, TrapFormat.LAST_STANDING -> "battle"
                TrapFormat.NUMBER_UP -> "numbers"
                TrapFormat.SONG -> "music"
                TrapFormat.REVEAL -> "asmr"
                else -> "oddlysatisfying"
            }
            val hashtags = listOf("shorts", "satisfying", "simulation", shelfTag, formatTag).distinct()
            val tagLine = hashtags.joinToString(" ") { "#$it" }
            // The caption is the title on a Short: the video's own hook, which
            // names the sim (or, for a sort, asks you to guess it), and two
            // hashtags. A line that could go on any video ("Listen to it") was
            // what YouTube's review marked down.
            val hook = hookFor(clip)
            var title = "${hook.onScreen} #shorts #$shelfTag"
            if (title.length > 100) title = "$lead ${def.title} #shorts"
            if (title.length > 100) title = "$lead #shorts"
            val description = buildString {
                if (sortName(clip) != null) append("Watch it sort, guess the algorithm — the answer is at the end.")
                else append(def.blurb)
                append("\n\n")
                append(ask)
                append("\n\nA real simulation, not an animation: every frame is computed, nothing is drawn by hand.")
                append("\n\n")
                append(tagLine)
            }
            val tags = (hashtags + listOf(def.title.lowercase(), def.family.lowercase(), clip.format.title.lowercase(), "satisfying video", "physics simulation"))
                .distinct().filter { it.length <= 30 }
            return Caption(title.take(100), description, tags)
        }

        /**
         * What a Short says first, on screen and out loud, and what it says
         * once the moment has landed. Named for the sim — "Snake battle: which
         * one wins?" — never a line that could go on any video; a sort hides
         * its name and asks you to guess it, and says it at the end.
         */
        class Hook(val onScreen: String, val spoken: String, val reveal: String?, val revealSpoken: String?)

        /** The algorithm a sorting clip runs, when it is one. */
        fun sortName(clip: Clip): String? {
            val id = clip.def.id
            if (!id.startsWith("sorting/") || id == "sorting/race") return null
            if (id == "sorting/choose") {
                val p = clip.def.params.firstOrNull { it.key == "algorithm" } ?: return null
                val i = (clip.params["algorithm"] ?: p.default).toInt()
                return p.choices.getOrNull(i)
            }
            return clip.def.title
        }

        fun hookFor(clip: Clip): Hook {
            val def = clip.def
            sortName(clip)?.let { name ->
                return Hook(
                    "Can you guess this sorting algorithm? 🤔",
                    "Can you guess which sorting algorithm this is?",
                    "It's $name!",
                    "It was $name. Did you get it?"
                )
            }
            val title = def.title
            return when (clip.format) {
                TrapFormat.RACE, TrapFormat.COLOR_BATTLE, TrapFormat.LAST_STANDING, TrapFormat.GUESS ->
                    Hook("$title: which one wins? 👇", "$title. Pick one now. Which one wins?", "Did you call it?", "Did you call it?")
                TrapFormat.NUMBER_UP ->
                    Hook("$title: guess the final number 🤯", "$title. Guess the number it ends on.", null, "Did you guess it?")
                TrapFormat.ESCAPE -> Hook("$title: will it get out? 👀", "Will it get out?", null, null)
                TrapFormat.SNAP, TrapFormat.CUT, TrapFormat.CHAIN -> Hook("$title — wait for it… 😳", "$title. Wait for it.", null, null)
                TrapFormat.FILL -> Hook("$title: watch it fill 😌", "Watch it fill up.", null, null)
                TrapFormat.REVEAL -> Hook("$title 😌", "Watch this.", null, null)
                TrapFormat.SONG -> Hook("$title — sound on 🔊", "$title. Turn the sound on.", null, null)
                else -> Hook("$title: ${clip.caption}", title, null, null)
            }
        }

        /** The edit of a Short: its timeline, and when its moment lands in it. */
        class Plan(val project: Project, val payoffAt: Float, val hook: Hook)

        /**
         * How [clip] is cut into a Short. Real time while the hook is read,
         * quicker through the middle of the build, real time again, then a
         * little slower for the last breath before the moment — the build is
         * kept, the dull stretch of it isn't — and real time after.
         * Pure, so it can be checked.
         */
        fun plan(clip: Clip): Plan {
            val payoff = clip.payoffSeconds
            val project = Project(clip.def.id, clip.seed)
            project.name = "Short"
            val hook = hookFor(clip)

            val fast = if (payoff > 20f) 2f else FAST
            fun rampSim(a: Float, b: Float) = RAMP * (a + b) / 2f
            // The sim seconds every part but the fast hold takes.
            val fixedSim = HOOK_SECONDS + 2f * rampSim(1f, fast) + NORMAL_SECONDS + rampSim(1f, SLOW) + SLOW_SECONDS * SLOW
            val fastSim = payoff - fixedSim
            val speed = project.track(TrackKind.SPEED)
            val payoffAt = if (fastSim > 1f) {
                var t = 0f
                speed.set(t, 1f, Easing.LINEAR)
                t += HOOK_SECONDS; speed.set(t, 1f, Easing.LINEAR)
                t += RAMP; speed.set(t, fast, Easing.LINEAR)
                t += fastSim / fast; speed.set(t, fast, Easing.LINEAR)
                t += RAMP; speed.set(t, 1f, Easing.LINEAR)
                t += NORMAL_SECONDS; speed.set(t, 1f, Easing.LINEAR)
                t += RAMP; speed.set(t, SLOW, Easing.LINEAR)
                t += SLOW_SECONDS; speed.set(t, SLOW, Easing.LINEAR)
                val at = t
                speed.set(at + RAMP, 1f, Easing.LINEAR)
                at
            } else payoff
            val length = (payoffAt + RESOLVE_SECONDS).coerceIn(MIN_SECONDS, MAX_SECONDS)
            project.duration = length

            // The same gentle lean in towards the moment that TickTock gives it.
            if (payoffAt in 1f..length) {
                project.track(TrackKind.ZOOM).set(0f, 1f)
                project.track(TrackKind.ZOOM).set(payoffAt, 1.1f, Easing.EASE_IN_OUT)
                project.track(TrackKind.ZOOM).set(minOf(length, payoffAt + 1.5f), 1f, Easing.EASE_IN_OUT)
            }

            // Words on screen: the hook up front, a nudge to answer, and the
            // reveal or a question once it's landed.
            val picks = clip.format in PICK_FORMATS && sortName(clip) == null
            project.texts.add(TextClip(hook.onScreen, 0.15f, minOf(HOOK_SECONDS + 1.4f, payoffAt - 0.5f).coerceAtLeast(1.6f)))
            if (picks) project.texts.add(TextClip("Comment your pick 👇", 1.2f, minOf(5f, payoffAt - 0.5f).coerceAtLeast(2.5f), big = false))
            if (clip.format in WAIT_FORMATS && payoffAt > 6f) {
                project.texts.add(TextClip("Wait for it…", payoffAt - 3f, payoffAt - 0.3f, big = false))
            }
            hook.reveal?.let { if (length - payoffAt > 1f) project.texts.add(TextClip(it, payoffAt + 0.5f, length, big = true)) }
            if (length - payoffAt > 2.4f) project.texts.add(TextClip("Follow for a new sim every day", length - 1.8f, length, big = false))
            return Plan(project, payoffAt, hook)
        }

        const val ROUTE_APP = 0
        const val ROUTE_API = 1
        val ROUTE_NAMES = listOf("YouTube app", "Google API")
        private const val KEEP_MS = 6 * 60 * 60_000L
        const val MAX_COUNT = 10
        val COUNTS = listOf(1, 2, 3, 5, 10)
        val PRIVACY = listOf("private", "unlisted", "public")
        val PRIVACY_NAMES = listOf("Private", "Unlisted", "Public")
        private const val MIN_SECONDS = 8f
        /** The mark in the corner of every Short. */
        const val BRAND = "HYPNOTICA"
        /** When the hook is spoken. */
        private const val VOICE_START = 0.3f
        /** Seconds the sound fades in over. */
        private const val FADE_IN_SECONDS = 0.8f
        /** Seconds of real time at the start, while the hook is read. */
        private const val HOOK_SECONDS = 1.6f
        /** How much quicker the middle of the build runs. */
        private const val FAST = 1.6f
        /** Seconds a change of speed takes. */
        private const val RAMP = 0.5f
        /** Real time after the speed-up, before the last slow breath. */
        private const val NORMAL_SECONDS = 0.8f
        /** The last breath before the moment: this much slower, for this long. */
        private const val SLOW = 0.72f
        private const val SLOW_SECONDS = 0.7f
        /** Seconds after the moment before the Short ends. */
        private const val RESOLVE_SECONDS = 3.6f
        private val PICK_FORMATS = setOf(TrapFormat.RACE, TrapFormat.COLOR_BATTLE, TrapFormat.LAST_STANDING, TrapFormat.GUESS)
        private val WAIT_FORMATS = setOf(TrapFormat.SNAP, TrapFormat.CUT, TrapFormat.CHAIN, TrapFormat.ESCAPE)
        private const val MAX_SECONDS = 58f
        private const val RENDER_SHARE = 0.7f
        private const val GAP_MS = 3000L
        private const val PRIVATE_NOTE =
            " If they show as private, that's YouTube: uploads from a Google Cloud project that hasn't passed its API audit stay private."
    }
}
