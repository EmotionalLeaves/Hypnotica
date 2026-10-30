package app.hypnotica.ui

import app.hypnotica.core.audio.Groove
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.hypnotica.core.audio.Scale
import app.hypnotica.core.audio.Song
import kotlinx.coroutines.launch
import app.hypnotica.core.director.Clip
import app.hypnotica.core.director.ClipDirector
import app.hypnotica.core.perf.Bench
import app.hypnotica.core.sim.TrapFormat
import app.hypnotica.core.laws.Laws
import app.hypnotica.core.render.Palette
import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Macro
import app.hypnotica.core.recipe.Macros
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.RecipeStore
import app.hypnotica.core.recipe.ParamType
import app.hypnotica.core.recipe.RunParse
import app.hypnotica.core.recipe.applyRunParams
import app.hypnotica.core.recipe.lawsJson
import app.hypnotica.core.recipe.runParamsJson
import app.hypnotica.core.recipe.parseRun
import app.hypnotica.core.recipe.runLaws
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.studio.MoveRecorder
import app.hypnotica.core.studio.Project
import app.hypnotica.core.studio.parseProject
import app.hypnotica.core.studio.projectJson
import app.hypnotica.engine.SimEngine
import app.hypnotica.sims.SimRegistry

enum class Screen { FEED, LIBRARY, PLAYER, SETTINGS, TICKTOCK, STUDIO, RECIPES }

/**
 * Everything the UI needs to know, in one place.
 *
 * Parameter values live in the engine, not here; [paramVersion] is bumped on every
 * change so Compose knows to read them again.
 */
/**
 * How long a finished run is left on screen before the next one starts: the
 * design doc's resolve beat — settle, breathe, and the last frame is worth a
 * screenshot. Every screen that hosts a sim waits this long, so a finished sort
 * is something you see rather than something that was already gone.
 *
 * Most sims hold their finished picture for up to a second and a half of
 * their own before they report finished, and this came on top: measured, three
 * to four seconds of a still screen between runs. A second, with the sims'
 * holds capped at 1.5 s, keeps the stillness under two and a half.
 */
internal const val RESOLVE_MS = 1000L

/**
 * The wait after a run finishes: the resolve beat, plus whatever the sim's own
 * hold fell short of. See [app.hypnotica.core.sim.SimCore.extraHoldSeconds].
 */
internal fun resolveDelayMs(engine: SimEngine): Long =
    RESOLVE_MS + (engine.core.extraHoldSeconds() * 1000f).toLong()

/** How many screens Back remembers. */
private const val MAX_TRAIL = 8

/** Under this share of the phone's best frame rate counts as falling behind. */
private const val SLOW_SHARE = 0.7f

/** Quarter-second checks in a row: three seconds of it before saying so. */
private const val SLOW_TICKS = 12

class AppState(
    val engine: SimEngine,
    private val prefs: Prefs? = null,
    val recipeStore: RecipeStore? = null
) {

    private var screenState by mutableStateOf(Screen.FEED)

    /**
     * Where the person came from, so Back and the on-screen ‹ both return
     * there. They used to disagree: the player's ‹ went to the Library and
     * system Back to the feed, so someone who opened a sim from the feed
     * tapped ‹ and landed on a screen they had never been to.
     */
    private val trail = ArrayList<Screen>()

    /**
     * The screen showing. Moving to another one closes the player's drawer and
     * drops any notice: both used to follow the person around — the drawer
     * open again on the next visit, swallowing the first Back, and "Tap again
     * to forget everything" turning up in Studio.
     */
    var screen: Screen
        get() = screenState
        set(value) {
            if (value == screenState) return
            if (value == Screen.FEED) trail.clear()
            else {
                trail.remove(value)
                trail.add(screenState)
                if (trail.size > MAX_TRAIL) trail.removeAt(0)
            }
            screenState = value
            controlsOpen = false
            notice = null
        }
    var currentDef by mutableStateOf(SimRegistry.defaultSim())
        private set

    var controlsOpen by mutableStateOf(false)
    private var advancedState by mutableStateOf(false)
    var advanced: Boolean
        get() = advancedState
        set(value) {
            advancedState = value
            applySafety()
        }
    var card by mutableStateOf(Card.DATA)
    var advancedTab by mutableStateOf(AdvancedTab.DATA)
    var paramVersion by mutableIntStateOf(0)

    /**
     * Bumped a few times a second while the player is on screen.
     *
     * Everything the engine knows but Compose cannot see needs this. Evolve
     * drifts the parameter values from the GL thread, which must not touch
     * snapshot state, so without it the picture changed while every slider sat
     * frozen. The same goes for which event kinds a run has actually produced,
     * which decides whether a sound switch can do anything.
     */
    /**
     * Bumped whenever a sound value changes, so the Sound tab redraws.
     *
     * SoundMapping is plain fields read by the audio thread, not snapshot
     * state; without this the sliders that write them never showed their own
     * effect, and a preset tap silently replaced four of them.
     *
     * Declared up here with the other counters for a reason: init() calls
     * load(), load() calls soundChanged(), and Kotlin creates delegates in
     * declaration order — so sitting below the init block this one did not
     * exist yet when it was written to, and constructing AppState threw. The
     * app would not launch at all.
     */
    var soundVersion by mutableIntStateOf(0)
        private set

    fun soundChanged() {
        soundVersion++
    }

    var liveTick by mutableIntStateOf(0)
        private set

    fun tickLive() {
        liveTick++
        // The core is the truth about whether the sim is running; this is the
        // copy the Play / Pause label reads. Anything that swaps or rebuilds the
        // core starts it running, and three of those paths — loading another
        // sim, opening a TickTock clip, entering Studio — reset the core and
        // not this, so the player said "Play" over a sim that was running, and
        // one tap later paused it.
        if (paused != engine.core.paused) paused = engine.core.paused

        // Whether the phone is keeping up, measured rather than guessed. The
        // engine has smoothed its frame rate since the first build and nothing
        // read it, so a size slider could take a sim past what the phone draws
        // smoothly with nothing saying so. Judged against the best this phone
        // has managed this session, so a 30 Hz battery-saver cap or a 120 Hz
        // screen are each their own normal.
        val f = engine.fps
        if (!paused && f > 1f) {
            if (f > bestFps) bestFps = f
            slowTicks = if (f < bestFps * SLOW_SHARE) slowTicks + 1 else 0
        }
        val now = slowTicks >= SLOW_TICKS
        if (struggling != now) struggling = now
    }

    /** True while the sim has been drawing well under this phone's best for a few seconds. */
    var struggling by mutableStateOf(false)
        private set
    private var bestFps = 0f
    private var slowTicks = 0

    /** Where each Simple-mode macro slider sits, per sim. */
    private val macroPositions = mutableStateMapOf<String, Float>()

    /**
     * The parameter revision each cached macro position was true for.
     *
     * The cache used to be written once and never invalidated, so after a value
     * was changed in Advanced — or drifted by Evolve — the Simple slider still
     * sat where it had been left, and the next touch of it snapped everything
     * back to a position that no longer described the sim.
     */
    private val macroRevisions = mutableStateMapOf<String, Int>()

    /**
     * Bumped only when a formula changes from outside the keyboard, so a text
     * field knows to reload without being yanked out from under the typist.
     */
    var exprEpoch by mutableIntStateOf(0)
        private set

    private var speedState by mutableFloatStateOf(1f)
    private var trailsState by mutableFloatStateOf(0f)

    /** Playback speed, 0.1x to 10x. Setting it reaches straight into the core. */
    var speed: Float
        get() = speedState
        set(value) {
            speedState = value.coerceIn(0.1f, 10f)
            engine.core.speed = speedState
        }

    /** 0 = no trails, 0.96 = long smeary ones. */
    var trails: Float
        get() = trailsState
        set(value) {
            trailsState = value.coerceIn(0f, 0.96f)
            engine.setTrails(trailsState)
        }

    var paused by mutableStateOf(false)

    /** The variant the last remix landed on, in words, for the feed's caption. */
    var remixVariant by mutableStateOf<String?>(null)
    /** A one-tap video being made, and how far it has got. */
    var quickExporting by mutableStateOf(false)
    var quickExportProgress by mutableStateOf(0f)

    /** When the last remix landed, so the player can say what it landed on. */
    var remixedAt by mutableStateOf(0L)
    var paletteName by mutableStateOf("Spectrum")

    /**
     * A palette and sound the person picked themselves, which outrank each
     * sim's own default until they clear them. Without this, picking a palette
     * in Settings looked like it worked and was then thrown away by the next
     * sim change — and the Settings screen has no preview, so the pill lit up
     * and nothing visible happened at all.
     */
    // Snapshot state, so the Auto chips can show whether the person has chosen.
    private var chosenPalette by mutableStateOf<String?>(null)
    private var chosenSound by mutableStateOf<String?>(null)

    /** No palette chosen: each sim shows its own, and the feed varies it. */
    val paletteAuto: Boolean get() = chosenPalette == null
    val soundAuto: Boolean get() = chosenSound == null

    /**
     * Where the person is in the feed.
     *
     * It used to be a `remember` inside FeedScreen, which the navigation
     * `when` disposes: leaving the feed and coming back reset it to zero and
     * reloaded the first sim of the shuffle. So you lost your place every time
     * you opened the player, the palette you had just picked went with it, and
     * the "pick up where you left off" that the saved sim id promises was
     * overwritten one frame after launch — permanently, on disk.
     */
    var feedIndex by mutableIntStateOf(0)

    /** How many more times the drawer shows its gesture tip; kept on the phone. */
    private var gestureTipsState = prefs?.gestureTipsLeft ?: 0
    var gestureTipsLeft: Int
        get() = gestureTipsState
        set(value) {
            gestureTipsState = value.coerceAtLeast(0)
            prefs?.gestureTipsLeft = gestureTipsState
        }

    /** Winners called in a row in TickTock, and the best run; kept on the phone. */
    var guessStreak by mutableIntStateOf(prefs?.guessStreak ?: 0)
        private set
    var guessBest by mutableIntStateOf(prefs?.guessBest ?: 0)
        private set

    /**
     * A tap felt: light for moving on, medium for something kept or chosen,
     * heavy for a miss. Every event is seen, heard and felt — the UI's own
     * included, which until now were almost all silent under the finger.
     */
    fun tick(strength: Int = 0) {
        if (!haptics) return
        when (strength) {
            0 -> engine.haptics?.light()
            1 -> engine.haptics?.medium()
            else -> engine.haptics?.heavy()
        }
    }

    /** A chip tapped: a tick under the finger. */
    fun pickTapped() {
        if (haptics) engine.haptics?.light()
    }

    /** A pick settled: extend the run or end it. */
    fun recordGuess(right: Boolean) {
        // Felt as well as read: a thump for a right call.
        if (right && haptics) engine.haptics?.verdict()
        // And a miss is felt too, differently.
        if (!right) tick(2)
        guessStreak = if (right) guessStreak + 1 else 0
        if (guessStreak > guessBest) guessBest = guessStreak
        prefs?.guessStreak = guessStreak
        prefs?.guessBest = guessBest
    }

    /** Swipes on the feed this session, which varies each sim's headline. */
    var feedVisits by mutableIntStateOf(0)

    /** True until the first swipe on the feed this session. */
    var feedHint by mutableStateOf(true)

    /**
     * Why the sim stopped, or null. Mirrored from the engine, which sets it
     * from the GL thread and cannot touch snapshot state itself.
     */
    var halted by mutableStateOf<String?>(null)
    var soundPresetName by mutableStateOf("8-bit")
    /** The beat under the sound; see [setBeat]. */
    var beatName by mutableStateOf(prefs?.beat ?: BEAT_AUTO)
        private set
    var songIndex by mutableIntStateOf(0)
    // These three write themselves through. Three screens changed them without
    // calling applySafety(), which is the only thing that saved them, so they
    // came back off after a force quit.
    private var showCountersState by mutableStateOf(true)
    var showCounters: Boolean
        get() = showCountersState
        set(value) {
            showCountersState = value
            applySafety()
        }

    /**
     * Whether the feed moves on by itself a few seconds after each payoff.
     * Off by default: the feed loops a sim for as long as it is watched, and
     * the swipe is how you leave — this is for watching hands-free.
     */
    private var feedAutoState by mutableStateOf(false)
    var feedAutoAdvance: Boolean
        get() = feedAutoState
        set(value) {
            feedAutoState = value
            prefs?.feedAutoAdvance = value
        }

    /** 0 = off. Minutes between TickTock's "you have been scrolling" card. */
    private var timeCheckState by mutableIntStateOf(0)
    var timeCheckMinutes: Int
        get() = timeCheckState
        set(value) {
            timeCheckState = value
            applySafety()
        }

    /**
     * The synth's volume. Saved by the caller when the gesture ends.
     *
     * Mirrored into snapshot state rather than read straight off the synth,
     * which is the convention speed and trails already follow. Read directly,
     * the slider had nothing to recompose on: the volume really changed, and
     * the thumb stayed where it was — and since the drag offset kept
     * accumulating against a thumb that never moved, by the second drag the
     * finger and the thumb were in different places entirely.
     */
    private var volumeState by mutableFloatStateOf(0.8f)

    var volume: Float
        get() = volumeState
        set(value) {
            volumeState = value
            engine.synth.volume = value
        }

    private var evolveState by mutableStateOf(false)

    /**
     * Evolve: the sim's own settings drift slowly while it plays, so a loop
     * never settles into exactly the same thing twice.
     *
     * A property with a side effect rather than a setX() function, because a
     * `var` and a `fun setX()` compile to the same JVM method.
     */
    var evolve: Boolean
        get() = evolveState
        set(value) {
            evolveState = value
            engine.evolve = value
        }

    private var cameraDragState by mutableStateOf(false)

    /**
     * When on, one finger pans the view instead of reaching into the sim.
     *
     * The surface field is written only by SimCanvas's own `cameraMode`
     * parameter now. It used to be written from here through a callback as
     * well, and the player called SimCanvas without the parameter — so every
     * recomposition wrote the default `false` back over the person's choice.
     * The switch stayed on and the gesture went back to poking the sim.
     */
    var cameraDrag: Boolean
        get() = cameraDragState
        set(value) {
            cameraDragState = value
            prefs?.cameraDrag = value
        }

    /**
     * TickTock's generator. It lives here rather than in the screen so the
     * learned weighting survives leaving TickTock and coming back.
     */
    val director = ClipDirector()

    /**
     * Clips checked and ready, kept here rather than in TickTock so they
     * survive leaving it — and so Settings, the only door to TickTock, can
     * have one waiting before the clock is tapped. Finding a clip is a dozen
     * headless payoff checks, seconds on a phone, and every visit used to open
     * on "Finding something…".
     */
    val tickTockQueue = ArrayDeque<Clip>()
    /** TickTock's place, kept across a visit to the player: the clip on, those before it, how many. */
    var tickTockCurrent: Clip? = null
    val tickTockHistory = ArrayList<Clip>()
    var tickTockWatched = 0

    /** What the benchmark found, shown in Settings. */
    var benchNote by mutableStateOf<String?>(null)
        private set

    /**
     * Measure what this phone can carry, once, and tell the clip generator. Call
     * from a background thread: it runs the heaviest sim for about a second.
     */
    fun benchmarkIfNeeded() {
        val p = prefs
        val remembered = Bench.tierByName(p?.deviceTier)
        if (remembered != null) {
            director.sizeScale = remembered.sizeScale
            benchNote = p?.benchNote
            return
        }
        val result = Bench.run()
        director.sizeScale = result.tier.sizeScale
        benchNote = result.describe()
        // A failed measurement is not remembered, so the next launch tries again.
        if (result.measured) {
            p?.deviceTier = result.tier.name
            p?.benchNote = result.describe()
        }
    }

    /** Remember what the person liked, so the feed is still theirs next launch. */
    fun likeClip(clip: Clip) {
        director.like(clip)
        prefs?.taste = director.exportTaste()
        // And kept: a clip is a seed and settings, so the run that replays it
        // exactly is a few hundred bytes. Liked and swiped past, it was gone.
        val run = stateJson()
        if (run !in savedClips) {
            savedClips = (listOf(run) + savedClips).take(MAX_SAVED)
            prefs?.savedClips = savedClips
            notice = "Saved to your Library."
        }
    }

    /** Liked TickTock clips, as replayable runs, newest first. */
    var savedClips by mutableStateOf(prefs?.savedClips ?: emptyList())
        private set

    fun forgetSaved(run: String) {
        savedClips = savedClips - run
        prefs?.savedClips = savedClips
    }

    fun skipClip(clip: Clip) {
        director.skip(clip)
        prefs?.taste = director.exportTaste()
    }

    /** Mirrors the director's muted set so Compose can see it change. */
    var mutedFormats by mutableStateOf<Set<TrapFormat>>(emptySet())
        private set

    fun setFormatMuted(format: TrapFormat, muted: Boolean) {
        // Turning the last one off would leave the feed with nothing to show.
        // This used to add it and then quietly take it back, so the pill stayed
        // selected and the tap looked like a dead control; the guard was right,
        // its silence was the bug.
        if (muted && director.mutedFormats.size >= TrapFormat.entries.size - 1) {
            notice = "At least one kind has to stay on, or the feed has nothing to show."
            return
        }
        if (muted) director.mutedFormats.add(format) else director.mutedFormats.remove(format)
        notice = null
        mutedFormats = director.mutedFormats.toSet()
        prefs?.mutedFormats = director.exportMuted()
    }

    /** True when [format] is the last one still showing, so its pill can say so. */
    fun isLastShowingFormat(format: TrapFormat): Boolean =
        format !in director.mutedFormats &&
            director.mutedFormats.size >= TrapFormat.entries.size - 1

    fun resetTaste() {
        director.resetTaste()
        prefs?.taste = ""
    }

    /**
     * A video of what's on screen, in one tap: its own build-up-to-payoff
     * edit, rendered at 9:16, saved to the gallery and handed to the share
     * sheet. It took seven taps and a scroll, by way of a faint mark in
     * Settings. The Studio's own project is left alone.
     */
    fun quickExport(context: android.content.Context) {
        if (quickExporting) return
        tick(1)
        quickExporting = true
        quickExportProgress = 0f
        val def = currentDef
        val made = Project.template("Build-up to payoff", def.id, engine.core.seed, def.typicalSeconds)
        val exporter = app.hypnotica.export.VideoExporter(
            def = def,
            project = made,
            laws = laws,
            paletteName = paletteName,
            soundPresetName = soundPresetName,
            groove = engine.mapping.groove,
            song = Song.byIndex(songIndex).takeIf { it.notes.isNotEmpty() },
            params = engine.core.params.snapshot(),
            settings = app.hypnotica.export.ExportSettings.VERTICAL,
            trails = trails,
            safety = engine.safety
        )
        quickScope.launch {
            val progress = launch {
                while (true) { quickExportProgress = exporter.progress; kotlinx.coroutines.delay(150) }
            }
            try {
                val dir = java.io.File(context.getExternalFilesDir(null), "exports")
                runCatching { app.hypnotica.export.Publish.prune(dir) }
                val out = java.io.File(dir, "hypnotica-${System.currentTimeMillis()}.mp4")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { exporter.export(out) }
                val outcome = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    app.hypnotica.export.Publish.publish(context, out)
                }
                notice = outcome.message
                outcome.shareable?.let { runCatching { app.hypnotica.export.Publish.share(context, it) } }
                engine.haptics?.verdict()
            } catch (e: Throwable) {
                notice = "Couldn't make the video. Try again, or use the Studio for a shorter one."
            } finally {
                progress.cancel()
                quickExporting = false
            }
        }
    }

    private val quickScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    /** The Studio project being edited, kept while the app is open. */
    var project by mutableStateOf<Project?>(null)

    /** The project Studio edits, made on the spot the first time it is asked for. */
    fun projectForStudio(): Project {
        val existing = project
        if (existing != null) {
            // The project was cached for the life of the app while every visit
            // to the Feed re-seeded the engine, so the preview re-simulated one
            // run and the export re-simulated another: same sim, different
            // collisions, keyframes landing on the wrong moments. Worse, after
            // changing sims the file held the new sim with the old sim's seed.
            // The keyframes are the person's work and are kept; what the run is
            // follows the sim actually on screen.
            existing.simId = currentDef.id
            existing.seed = engine.core.seed
            return existing
        }
        // The sim's own length, so the template's payoff keyframes land on
        // this sim's payoff rather than always at twenty-six seconds.
        // A project saved before the app was last killed comes back first.
        // It follows the sim on screen, as one held in memory always has.
        prefs?.studioProject?.let { parseProject(it) }?.let { restored ->
            restored.simId = currentDef.id
            restored.seed = engine.core.seed
            project = restored
            return restored
        }
        val made = Project.template(
            "Build-up to payoff", currentDef.id, engine.core.seed, currentDef.typicalSeconds
        )
        project = made
        return made
    }

    /**
     * Write the Studio project down. It lived only in memory, and Android
     * reclaims a backgrounded app whenever it likes — a phone call was enough to
     * take a session's keyframes. Cheap: a project is a few kilobytes.
     */
    fun saveProject() {
        val p = project ?: return
        prefs?.studioProject = projectJson(p)
    }

    /** Record moves: capture the camera and the controls, then edit in Studio. */
    var recording by mutableStateOf(false)
        private set

    fun startRecording() {
        val rec = MoveRecorder(currentDef.id, engine.core.seed)
        engine.recorder = rec
        recording = true
    }

    /** Stop and open the recording in Studio, where it can be trimmed. */
    /** Set when something the person did could not be carried out, cleared on read. */
    var notice by mutableStateOf<String?>(null)

    /** Leaving the player mid-recording: stop it, keep nothing, and say so. */
    fun abandonRecording() {
        if (!recording) return
        engine.recorder = null
        recording = false
        notice = "Recording stopped — it only runs in the player."
    }

    fun stopRecording() {
        val rec = engine.recorder ?: run { recording = false; return }
        engine.recorder = null
        recording = false
        if (rec.seconds < 1.5f) {
            // Tapping start and stop in quick succession used to do nothing at
            // all, with the recording already thrown away and no word about it.
            notice = "That was too short to edit — hold the move for a second or two."
            return
        }
        val built = rec.finish()
        project = built
        saveProject()
        screen = Screen.STUDIO
    }

    /** The live rules of the universe. Every physics sim reads these. */
    var laws by mutableStateOf(Laws.OUR_UNIVERSE)
        private set

    val lawsName: String get() = laws.name
    var reduceFlashing by mutableStateOf(false)
    var reduceMotion by mutableStateOf(false)
    var haptics by mutableStateOf(true)

    /** Favourites, kept in memory for now; persisted in the storage round. */
    val favourites = mutableStateOf(setOf<String>())

    /** Every sim opened on this phone: the Library's "found" count and its NEW marks. */
    val seen = mutableStateOf(setOf<String>())

    /** The last few sims opened, newest first, for the Library's Recent shelf. */
    var recent by mutableStateOf(listOf<String>())
        private set

    /** Whether the sim now playing was new to this phone when it was opened. */
    var currentIsNew by mutableStateOf(false)
        private set

    /** A one-off line worth celebrating — a shelf finished — shown briefly over any screen. */
    var milestone by mutableStateOf<String?>(null)

    /** Bumped with every milestone, so the same words twice still show twice. */
    var milestoneSerial by mutableIntStateOf(0)
        private set

    /**
     * The feed's order for this session, dealt once and kept: the feed screen
     * is composed afresh on every visit, and an order rebuilt each time would
     * move the person's place under them.
     */
    val feedOrder: List<SimDef> by lazy {
        Discovery.feedOrder(SimRegistry.all, seen.value, favourites.value, System.nanoTime())
    }

    /** Today's pick, the same all day; see [Discovery.dailyPick]. */
    val dailyPick: SimDef? by lazy {
        val today = java.time.LocalDate.now().toEpochDay()
        val kept = prefs?.dailyPick?.split(':', limit = 2)
        val keptDef = if (kept?.size == 2 && kept[0] == today.toString()) SimRegistry.find(kept[1]) else null
        keptDef ?: Discovery.dailyPick(SimRegistry.all, seen.value, today)?.also {
            prefs?.dailyPick = "$today:${it.id}"
        }
    }

    private fun markSeen(def: SimDef) {
        recent = (listOf(def.id) + recent.filter { it != def.id }).take(RECENT_MAX)
        prefs?.recentSims = recent
        val before = seen.value
        currentIsNew = Discovery.isNew(def.id, before)
        if (def.id in before) return
        val line = Discovery.milestone(before, def.id, SimRegistry.all)
        val next = before + def.id
        seen.value = next
        prefs?.seenSims = next
        if (line != null) {
            milestone = line
            milestoneSerial++
        }
    }

    init {
        prefs?.let { p ->
            favourites.value = p.favourites
            // A phone that ran the app before this was counted has seen at
            // least what it starred and what it was last watching.
            seen.value = p.seenSims.ifEmpty { p.favourites + listOfNotNull(p.lastSimId) }
            recent = p.recentSims
            reduceFlashing = p.reduceFlashing
            reduceMotion = p.reduceMotion
            haptics = p.haptics
            showCountersState = p.showCounters
            feedAutoState = p.feedAutoAdvance
            timeCheckState = p.timeCheckMinutes
            advancedState = p.advanced
            // Through the property, so the slider shows the restored value;
            // writing the synth directly leaves the mirrored state at its
            // default and the thumb in the wrong place.
            volume = p.volume
            chosenPalette = p.paletteName
            chosenSound = p.soundPreset
            cameraDragState = p.cameraDrag
            applySafety()
            director.importTaste(p.taste)
            director.importMuted(p.mutedFormats)
            mutedFormats = director.mutedFormats.toSet()
        }
        engine.onHalt = { why ->
            halted = why
            // So the next launch does not open straight back onto the sim that
            // stopped. Without this a sim that throws on its own default values
            // could not be escaped except by clearing the app's data.
            prefs?.lastSimId = null
        }
        val remembered = prefs?.lastSimId?.let { SimRegistry.find(it) }
        load(remembered ?: currentDef, restartSeed = 1L)
    }

    /** Pick up after a halt: load something else, or try the same thing again. */
    fun recoverFrom(def: SimDef?) {
        halted = null
        engine.clearHalt()
        if (def != null) load(def) else restart(newSeed = true)
    }

    /**
     * Sims loaded so far. A restart that was waiting out a finished run's
     * resolve beat checks it hasn't changed, so it lands on the run that
     * finished rather than the one swiped to in the meantime.
     */
    var loadCount = 0L
        private set

    fun load(
        def: SimDef,
        restartSeed: Long = System.nanoTime(),
        configure: ((app.hypnotica.core.recipe.ParamValues) -> Unit)? = null
    ) {
        currentDef = def
        loadCount++
        markSeen(def)
        // Loaded as it comes, not remixed: its usual self.
        remixVariant = null
        // A new sim starts running, and says so.
        paused = false
        slowTicks = 0
        struggling = false
        val palette = chosenPalette ?: def.defaultPalette
        val sound = chosenSound ?: def.defaultSound
        paletteName = palette
        soundPresetName = sound
        applyBeat(def.id.hashCode().toLong())
        // keepLook stops the engine re-applying the sim's own defaults on the
        // next GL frame, which is what used to undo the person's choice.
        engine.loadSim(def, restartSeed, keepLook = true, configure = configure)
        engine.setPalette(palette)
        engine.setSoundPreset(sound)
        // The sound mapping is rebuilt by the preset, so the Sound tab's
        // sliders are describing the previous sim until this says otherwise.
        soundChanged()
        macroPositions.clear()
        macroRevisions.clear()
        paramVersion++
        exprEpoch++
        prefs?.lastSimId = def.id
    }

    fun restart(newSeed: Boolean = true) {
        engine.restart(if (newSeed) System.nanoTime() else engine.core.seed)
        paramVersion++
    }

    fun togglePause() {
        paused = !paused
        engine.core.paused = paused
    }

    fun setPalette(name: String) {
        if (name == LOOK_AUTO) {
            // Back to each sim's own, and the feed's variety with it: one tap
            // on a palette used to switch that off for good.
            chosenPalette = null
            prefs?.paletteName = null
            paletteName = currentDef.defaultPalette
            engine.setPalette(paletteName)
            return
        }
        paletteName = name
        engine.setPalette(name)
        chosenPalette = name
        prefs?.paletteName = name
    }

    /**
     * The beat under the sound. "Auto" gives each sim a groove of its own —
     * the same one every time you meet it, its tempo nudged so no two feel
     * alike — and TickTock a fresh one per clip. Every sim used to sound like
     * one instrument with nothing under it, which is a lot of sameness for an
     * endless feed.
     */
    fun setBeat(name: String) {
        beatName = name
        prefs?.beat = name
        applyBeat(currentDef.id.hashCode().toLong())
    }

    /** Pick the groove for what is about to play; [key] varies it under Auto. */
    private fun applyBeat(key: Long) {
        engine.mapping.groove = when (beatName) {
            "Off" -> null
            BEAT_AUTO -> {
                val mix = (key xor (key ushr 29)) * 0x9E3779B97F4A7C15uL.toLong()
                val pick = Groove.ALL[((mix ushr 33) % Groove.ALL.size).toInt()]
                val nudge = (((mix ushr 13) and 0xFF).toInt() / 255f - 0.5f) * 0.16f
                pick.withTempo(pick.bpm * (1f + nudge))
            }
            else -> Groove.byName(beatName)
        }
    }

    fun setSoundPreset(name: String) {
        if (name == LOOK_AUTO) {
            chosenSound = null
            prefs?.soundPreset = null
            soundPresetName = currentDef.defaultSound
            engine.setSoundPreset(soundPresetName)
            soundChanged()
            return
        }
        soundPresetName = name
        engine.setSoundPreset(name)
        chosenSound = name
        prefs?.soundPreset = name
        soundChanged()
    }

    fun setSong(index: Int) {
        songIndex = index
        engine.setSong(Song.byIndex(index))
        // The only sound writer that used to skip this. Harmless while nothing
        // on the tab depended on it; load-bearing now that the scale, pitch and
        // range controls dim themselves when a song is playing.
        soundChanged()
    }

    fun setLaws(name: String) {
        laws = Laws.preset(name)
        engine.setLaws(laws)
    }

    /** Change one rule, keeping the rest. The name becomes "Custom" once edited. */
    fun editLaws(block: (Laws) -> Laws) {
        val edited = block(laws)
        laws = if (edited == laws) edited else edited.copy(name = "Custom")
        engine.setLaws(laws)
    }

    fun applySafety() {
        engine.safety.reduceFlashing = reduceFlashing
        engine.safety.reduceMotion = reduceMotion
        engine.safety.haptics = haptics
        engine.haptics?.enabled = haptics
        prefs?.let {
            it.reduceFlashing = reduceFlashing
            it.reduceMotion = reduceMotion
            it.haptics = haptics
            it.showCounters = showCountersState
            it.timeCheckMinutes = timeCheckState
            it.advanced = advancedState
            it.volume = engine.synth.volume
        }
    }

    /** Go back one level; returns false when there is nowhere left to go. */
    fun goBack(): Boolean = when {
        controlsOpen -> {
            controlsOpen = false
            true
        }
        screen == Screen.FEED -> false
        else -> {
            val to = trail.removeLastOrNull() ?: Screen.FEED
            // Set directly, so going back does not add to the trail.
            screenState = to
            controlsOpen = false
            notice = null
            if (to == Screen.FEED) trail.clear()
            true
        }
    }

    // ------------------------------------------------------------- params

    fun params(): List<Param> = currentDef.params

    /**
     * Simple mode's big controls for one card. A macro moves several exact
     * values at once, which is what keeps Simple mode powerful rather than
     * merely shorter than Advanced.
     */
    fun macros(card: Card): List<Macro> = Macros.forDef(currentDef).filter { it.card == card }

    fun macroPosition(macro: Macro): Float {
        val revision = engine.core.params.revision
        val cached = macroPositions[macro.key]
        if (cached != null && macroRevisions[macro.key] == revision) return cached
        val fresh = Macros.positionFor(macro, engine.core.params)
        macroPositions[macro.key] = fresh
        macroRevisions[macro.key] = revision
        return fresh
    }

    fun setMacro(macro: Macro, position: Float) {
        val clamped = position.coerceIn(0f, 1f)
        engine.core.params.applyMacro(macro, clamped)
        // Recorded after the write, so this macro's own change does not look
        // like somebody else's and send it back to be recomputed mid-drag.
        macroPositions[macro.key] = clamped
        macroRevisions[macro.key] = engine.core.params.revision
        paramVersion++
    }

    /** True when a typed formula is driving [key], so the UI can say so. */
    fun formulaOn(key: String): String? = engine.core.params.expressionSource(key)

    /**
     * The text of a typed formula parameter.
     *
     * Not [formulaOn]: that reads the map `setExpression` writes, and an EXPR
     * parameter goes through `setText` instead, so asking it about fx or fy
     * always answered null — which is how the equation preset chips came to
     * compute a selected index that was always -1, the very literal the
     * computation replaced.
     */
    /**
     * Drive a value from a formula in t, or clear it when the box is emptied.
     *
     * The Advanced formula box used to write the engine directly, so nothing
     * bumped the version and nothing committed: on a value the run only reads
     * while being built, typing a formula did nothing whatsoever. Returns
     * whether the text parses, which is what colours the box.
     */
    /**
     * Whether Evolve has anything it is allowed to move on this sim.
     *
     * The drift skips Data-card values and anything that is not a float, so on
     * the n-body sim (whose only float is on the Data card) and curved space
     * the switch flipped on, stayed on, and nothing ever drifted.
     */
    val canEvolve: Boolean
        get() = currentDef.params.any {
            it.card != Card.DATA && it.type == ParamType.FLOAT
        }

    fun setParamFormula(p: Param, text: String): Boolean {
        val ok = if (text.isBlank()) {
            engine.core.params.clearExpression(p.key)
            true
        } else {
            engine.core.params.setExpression(p.key, text)
        }
        if (ok) paramVersion++
        return ok
    }

    fun paramText(key: String, fallback: String = ""): String =
        engine.core.params.text(key, fallback)

    /**
     * A macro rebuilds the run when any value it moves would.
     *
     * Card alone was not enough: every target of boids' Chaos, ball-escape's
     * Chaos and the derived Energy macro is a Motion value its sim reads only
     * at setup, so the headline control of Simple mode moved four numbers and
     * changed nothing at all.
     */
    fun commitMacro(macro: Macro) {
        val rebuild = macro.card == Card.DATA ||
            macro.targets.keys.any { needsRestart(macro.card, it) }
        if (rebuild) restart(newSeed = false)
    }

    fun paramValue(key: String): Float = engine.core.params.f(key)

    fun setParam(p: Param, value: Float) {
        // Dragging a slider by hand replaces a formula on the same value — but
        // only when a person does it, never when Evolve or a macro moves it.
        engine.core.params.clearExpression(p.key)
        // And a hand on the slider outranks Evolve for a few seconds, the same
        // way a typed formula does. Otherwise you drag a value, let go, and
        // watch the drift pull it back — which looks exactly like a slider
        // that does not work.
        engine.holdFromDrift(p.key)
        engine.core.params.set(p.key, if (p.type == ParamType.INT) Math.round(value).toFloat() else value)
        paramVersion++
    }

    /**
     * Store a typed formula. Nothing restarts yet: a restart on every keystroke
     * would mean the sim rebuilding itself while the person is still mid-word.
     */
    fun setParamText(p: Param, value: String): Boolean =
        engine.core.params.setText(p.key, value)

    /** Apply a typed formula, once the person is done typing it. */
    fun commitParamText(p: Param) {
        paramVersion++
        if (needsRestart(p)) restart(newSeed = false)
    }

    /**
     * Set a formula from somewhere other than the keyboard — a preset, a clip, a
     * saved state. [exprEpoch] tells the text fields to pick the new value up.
     */
    fun applyParamText(key: String, value: String) {
        val p = currentDef.params.firstOrNull { it.key == key } ?: return
        if (engine.core.params.setText(key, value)) {
            exprEpoch++
            paramVersion++
            if (needsRestart(p)) restart(newSeed = false)
        }
    }

    fun setParamChoice(p: Param, index: Int) {
        engine.core.params.set(p.key, index.toFloat())
        // The caption names the variant as it now is, picked by hand or not.
        val values = currentDef.params.filter { it.type == app.hypnotica.core.recipe.ParamType.CHOICE }
            .associate { it.key to engine.core.params.choiceIndex(it.key).toFloat() }
        remixVariant = app.hypnotica.core.director.variantOf(currentDef, values)
        paramVersion++
        if (needsRestart(p)) restart(newSeed = false)
    }

    fun setParamBool(p: Param, on: Boolean) {
        engine.core.params.set(p.key, on)
        paramVersion++
        if (needsRestart(p)) restart(newSeed = false)
    }

    /**
     * Data-card parameters shape what gets built in setup(), so they land on the
     * next run rather than mid-flight. Everything else applies live.
     */
    /**
     * Whether applying [p] means building the run again.
     *
     * This used to be "is it on the Data card", and that answer was wrong for
     * about a quarter of every control in the app: some fifty values that the
     * sims copy into a field in setup() and never look at again. Dragging one
     * moved the number, bumped the version, and left the picture untouched —
     * whole cards of them, boids' Separation/Alignment/Cohesion/Speed, the
     * sorting sims' Algorithm chip, Mandelbrot's Resolution and Detail — and
     * the Chaos and Energy macros that Simple mode leans hardest on were inert
     * on those sims for the same reason.
     *
     * The sim is asked rather than told: [ParamValues] records which keys are
     * read while a run is stepping, so a value nothing reads live can only be
     * applied by rebuilding. Until a run has been watched long enough to know,
     * the old rule stands, so the answer is never worse than it was.
     */
    fun needsRestart(p: Param): Boolean = needsRestart(p.card, p.key)

    private fun needsRestart(card: Card, key: String): Boolean {
        if (card == Card.DATA) return true
        val params = engine.core.params
        return params.readsSettled && !params.readLive(key)
    }

    fun commitParam(p: Param) {
        if (needsRestart(p)) restart(newSeed = false)
    }

    /** Load and start a generated clip exactly as the director checked it. */
    fun playClip(clip: Clip) {
        currentDef = clip.def
        applyBeat(clip.seed)
        paletteName = clip.palette
        soundPresetName = clip.soundPreset
        laws = clip.laws
        // A clip was checked for its payoff at normal speed with no smear, and
        // it inherited whatever the player had been left on: at six times speed
        // the director's four-to-forty-second window becomes under seven
        // seconds, so clips resolved before the hook had been read. A pause
        // carried over too, which showed a still frame with no way to clear it.
        engine.core.paused = false
        paused = false
        engine.core.speed = 1f
        speed = 1f
        trails = clip.trails
        engine.scene.trails = clip.trails
        engine.loadSim(clip.def, clip.seed, keepLook = true)
        engine.setLaws(clip.laws)
        engine.setPalette(clip.palette)
        engine.setSoundPreset(clip.soundPreset)
        engine.applyParams(clip.params, clip.seed)
        paramVersion++
        exprEpoch++
    }

    /**
     * Load [def] as the feed does: a fresh variation each time it comes
     * round — its settings varied within a watchable band, and a palette of
     * its own or another, unless the person has chosen one. Met again, a sim
     * used to be the same picture every time.
     */
    fun loadRemixed(def: SimDef) {
        // The same sim again (the player's dice): keep what is loaded, so a
        // formula the person typed and their advanced settings survive — a
        // fresh load would put every one back to its default first.
        if (def.id != currentDef.id) load(def) else { engine.core.paused = false; paused = false }
        val params = director.remix(def)
        remixVariant = app.hypnotica.core.director.variantOf(def, params)
        remixedAt = System.nanoTime()
        if (chosenPalette == null) {
            val palette = director.remixPalette(def)
            paletteName = palette
            engine.setPalette(palette)
        }
        if (chosenSound == null) {
            val sound = director.remixSound(def)
            soundPresetName = sound
            engine.setSoundPreset(sound)
            soundChanged()
        }
        engine.applyParams(params, System.nanoTime())
        loadCount++
        paramVersion++
    }

    /** Hand the clip on screen to the normal player, controls and all. */
    fun openClipInPlayer(clip: Clip) {
        playClip(clip)
        // After the move: changing screen closes the drawer.
        screen = Screen.PLAYER
        controlsOpen = true
    }

    /**
     * The whole run as one small object: recipe, seed, parameters, laws, look and
     * sound. This is what a replay link or a Studio project stores, and it is
     * enough to reproduce any frame exactly.
     */
    fun stateJson(): String {
        val core = engine.core
        val sb = StringBuilder(512)
        sb.append("{\n")
        sb.append("  \"recipe\": \"").append(currentDef.id).append("\",\n")
        sb.append("  \"seed\": ").append(core.seed).append(",\n")
        sb.append("  \"params\": {")
        sb.append(runParamsJson(currentDef.params, core.params))
        sb.append("\n  },\n")
        // lawsJson, not the name: Laws.preset has no entry called "Custom", so
        // a hand-edited universe written down as its name came back as our
        // universe. The writer and the reader live in one file with a round-trip
        // test over them (RunStateTest).
        sb.append("  \"laws\": ").append(lawsJson(laws)).append(",\n")
        sb.append("  \"look\": { \"palette\": \"").append(paletteName)
            .append("\", \"trails\": ").append(String.format(java.util.Locale.US, "%.2f", trails)).append(" },\n")
        sb.append("  \"sound\": { \"preset\": \"").append(soundPresetName)
            .append("\", \"scale\": \"").append(engine.mapping.scale.name).append("\" }\n")
        sb.append("}")
        return sb.toString()
    }

    /**
     * Apply a run printed by [stateJson]. Returns null when it worked, or a
     * sentence saying why not.
     *
     * The panel has called that text "everything needed to replay it" since the
     * day it was written, with nothing in the app able to read it back. This is
     * the half that makes the sentence true, and the reason a run is worth
     * copying at all: send one to someone and they get your exact picture, down
     * to the seed.
     *
     * Everything is written straight into the core and the run restarted once
     * at the end, rather than going through the per-control setters: those each
     * restart when the value they changed needs it, so a run with a dozen setup
     * values rebuilt the sim a dozen times on the way in.
     */
    fun applyRun(text: String): String? {
        val run = when (val parsed = parseRun(text)) {
            is RunParse.Bad -> return parsed.why
            is RunParse.Ok -> parsed.run
        }
        val def = SimRegistry.find(run.recipe)
            ?: return if (run.recipe.startsWith("recipe/")) {
                "This phone does not have the recipe \"${run.recipe.removePrefix("recipe/")}\" " +
                    "yet. Add it in the recipe editor first, then paste this again."
            } else {
                "There is no sim called \"${run.recipe}\" in this build."
            }

        val seed = run.seed ?: System.nanoTime()
        // Applied to the new sim's own values once it has loaded. Written here
        // they went into the outgoing sim's, which the load then replaced, so
        // every saved clip replayed at its defaults.
        val unknown = run.params.keys.filter { key -> def.params.none { it.key == key } }
        load(def, seed) { values -> applyRunParams(run, def.params, values) }

        runLaws(run)?.let {
            laws = it
            engine.setLaws(it)
        }
        // byName falls back to Spectrum rather than returning null, so an
        // unknown palette has to be recognised before it is applied or it would
        // quietly repaint the sim.
        run.palette?.let { name -> if (Palette.ALL.any { it.name == name }) setPalette(name) }
        run.trails?.let { trails = it }
        run.soundPreset?.let { setSoundPreset(it) }
        // After the preset, which rebuilds the mapping and would overwrite it.
        run.scale?.let { engine.mapping.scale = Scale.byName(it) }
        soundChanged()

        // One restart, with the run's own seed, so the picture is the one that
        // was written down rather than the same recipe from somewhere else.
        engine.restart(seed)
        paramVersion++
        exprEpoch++
        if (unknown.isEmpty()) return null
        val named = unknown.take(3).joinToString(", ") { "\"$it\"" }
        val rest = if (unknown.size > 3) " and ${unknown.size - 3} more" else ""
        return "Loaded. This build has no " +
            (if (unknown.size == 1) "setting called " else "settings called ") + named + rest + "."
    }

    fun toggleFavourite(id: String) {
        val cur = favourites.value
        val next = if (id in cur) cur - id else cur + id
        favourites.value = next
        prefs?.favourites = next
        tick(if (id in next) 1 else 0)
    }

    fun isFavourite(id: String): Boolean = id in favourites.value
}

/** Liked clips kept in the Library, most recent first. */
private const val MAX_SAVED = 60

/** How many sims the Library's Recent shelf remembers. */
private const val RECENT_MAX = 12
