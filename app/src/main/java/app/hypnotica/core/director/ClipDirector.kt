package app.hypnotica.core.director

import app.hypnotica.core.laws.Laws
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.Rng
import app.hypnotica.core.sim.SimCore
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.TrapFormat
import app.hypnotica.core.recipe.ParamType
import app.hypnotica.sims.SimRegistry
import kotlin.math.max
import kotlin.math.min

/** One generated clip: everything needed to play it, plus what the check found. */
data class Clip(
    val def: SimDef,
    val seed: Long,
    val params: Map<String, Float>,
    val laws: Laws,
    val format: TrapFormat,
    val caption: String,
    val palette: String,
    val soundPreset: String,
    /** Trail smear for this clip, 0 for none. Part of the variety, not a leftover. */
    val trails: Float,
    val payoffSeconds: Float
)

/**
 * TickTock's generator.
 *
 * Every clip is simulated through before anyone sees it: if it does not reach a
 * payoff inside the window, the seed is thrown away and another is tried. That
 * pre-check is the whole difference between an endless feed and an endless feed
 * worth watching.
 */
class ClipDirector(seed: Long = System.nanoTime()) {

    private val rng = Rng(seed)
    private val scratch = G()

    /** Learned taste, kept on this phone only. Key is a format or shelf name. */
    private val weights = HashMap<String, Float>()

    /** Formats the person has switched off. */
    val mutedFormats = HashSet<TrapFormat>()

    /**
     * How much of each size ceiling this phone may use, from the benchmark. A
     * modest phone gets smaller clips rather than stuttering ones.
     */
    var sizeScale = 1f

    /**
     * The window a clip's payoff has to land in, as a share of the sim's own
     * typical length.
     *
     * It used to be a flat four to forty seconds for every sim, which is no
     * window at all: a sim that resolves in ten seconds and one that takes
     * forty both passed on almost any seed, so the pre-check could only reject
     * a seed that never paid off. Against the sim's own declared length the
     * check has something to say — a seed whose payoff lands in the first
     * seconds, or long after the run is over, is a seed worth throwing away.
     *
     * The band is wide on purpose. The design doc puts the payoff at 70%; this
     * only rejects what is plainly outside the arc.
     */
    var minPayoffShare = 0.3f
    var maxPayoffShare = 1.15f

    /** Absolute bounds, whatever a sim declares. */
    var minPayoffSeconds = 2f
    var maxPayoffSeconds = 45f

    private companion object {
        /** How many recent sim-and-hook pairs to avoid repeating. */
        const val RECENT_PAIRS = 12

        /** How many recent sims to avoid repeating, whatever the hook. */
        const val RECENT_SIMS = 6

        /** Draws allowed before taking whatever comes up. */
        const val REDRAWS = 8

        /**
         * The cap for any size parameter not named in [sizeCap]. Generous
         * enough to look like plenty on a phone, small enough that twelve
         * headless payoff checks of it are still quick.
         */
        const val DEFAULT_CAP = 6000f
    }

    /** How many seeds the last generate() had to try. Shown in the debug counter. */
    var lastAttempts = 0
        private set

    fun like(clip: Clip) {
        bump(clip.format.name, 0.6f)
        bump(clip.def.shelf.name, 0.35f)
        bump(clip.def.id, 0.5f)
    }

    fun skip(clip: Clip) {
        bump(clip.format.name, -0.25f)
        bump(clip.def.id, -0.4f)
    }

    fun resetTaste() = weights.clear()

    /**
     * Taste as one short string, so it can live in preferences and survive a
     * restart. Format: `key=value` pairs separated by `;`.
     */
    fun exportTaste(): String = buildString {
        for ((k, v) in weights) {
            if (kotlin.math.abs(v) < 0.05f) continue
            if (isNotEmpty()) append(';')
            append(k).append('=').append(Math.round(v * 100f) / 100f)
        }
    }

    fun importTaste(text: String) {
        weights.clear()
        if (text.isBlank()) return
        for (part in text.split(';')) {
            val at = part.indexOf('=')
            if (at <= 0) continue
            val value = part.substring(at + 1).toFloatOrNull() ?: continue
            weights[part.substring(0, at)] = value.coerceIn(-2f, 3f)
        }
    }

    fun exportMuted(): Set<String> = mutedFormats.map { it.name }.toSet()

    fun importMuted(names: Set<String>) {
        mutedFormats.clear()
        for (n in names) TrapFormat.entries.firstOrNull { it.name == n }?.let { mutedFormats.add(it) }
    }

    private fun bump(key: String, by: Float) {
        weights[key] = ((weights[key] ?: 0f) + by).coerceIn(-2f, 3f)
    }

    /** How many sims share each family, so a big family cannot swamp the feed. */
    private val familySize: Map<String, Int> by lazy {
        SimRegistry.all.groupingBy { it.family }.eachCount()
    }

    private fun weight(def: SimDef, format: TrapFormat): Float {
        var w = 1f
        w += weights[format.name] ?: 0f
        w += weights[def.shelf.name] ?: 0f
        w += weights[def.id] ?: 0f
        // Twelve sorting algorithms should not mean twelve times the sorting.
        val share = familySize[def.family] ?: 1
        val spread = 1f / kotlin.math.sqrt(share.toFloat())
        return max(0.03f, w * spread)
    }

    /**
     * Produce a clip that is known to pay off. Returns null only if nothing
     * worked in [tries] attempts, which should not happen with the stock set.
     */
    // One at a time: Settings finds TickTock's first clip ahead of time, and
    // a generation still unwinding from a cancel can overlap the next. They
    // share the random stream, and two at once would interleave it.
    @Synchronized
    fun generate(tries: Int = 12): Clip? {
        lastAttempts = 0
        var fallback: Clip? = null
        repeat(tries) {
            // Generation runs on a background thread and used to have no
            // suspension point or cancellation check anywhere in it, so leaving
            // the screen did not stop it: the work carried on saturating the
            // same dispatcher the GL thread shares.
            if (Thread.currentThread().isInterrupted) return fallback
            lastAttempts++
            val candidate = propose() ?: return@repeat
            val payoff = checkPayoff(candidate)
            if (payoff in payoffWindow(candidate.def)) {
                remember(candidate)
                return candidate.copy(payoffSeconds = payoff)
            }
            if (fallback == null && payoff > 0f) fallback = candidate.copy(payoffSeconds = payoff)
        }
        // Nothing in the window. The fallback pays off outside it, so it is
        // better than a swipe that appears to be swallowed, but it is not what
        // the feed promises and the caller is told which it got.
        fallback?.let { remember(it) }
        return fallback
    }

    /**
     * The sims and hooks just shown, so the next draw can avoid them.
     *
     * The weighted pick was independent each time, which over twenty swipes
     * gave fourteen distinct sims, one of them three times — and because the
     * caption is the format's hook, a repeat shows the identical headline. An
     * infinite feed that says the same thing twice in eight swipes reads as a
     * bug.
     */
    private val recentPairs = ArrayDeque<String>()
    private val recentSims = ArrayDeque<String>()

    private fun remember(clip: Clip) {
        recentPairs.addLast(clip.def.id + "/" + clip.format.name)
        while (recentPairs.size > RECENT_PAIRS) recentPairs.removeFirst()
        recentSims.addLast(clip.def.id)
        while (recentSims.size > RECENT_SIMS) recentSims.removeFirst()
    }

    private fun seenRecently(def: SimDef, format: TrapFormat): Boolean =
        recentPairs.contains(def.id + "/" + format.name) || recentSims.contains(def.id)

    private fun propose(): Clip? {
        val pool = SimRegistry.all.filter { it.formats.any { f -> f !in mutedFormats } }
        if (pool.isEmpty()) return null

        // Weighted pick, so the feed drifts towards what gets watched.
        val pairs = ArrayList<Pair<SimDef, TrapFormat>>()
        val cumulative = ArrayList<Float>()
        var total = 0f
        for (def in pool) {
            for (f in def.formats) {
                if (f in mutedFormats) continue
                pairs.add(def to f)
                total += weight(def, f)
                cumulative.add(total)
            }
        }
        if (pairs.isEmpty()) return null
        // Draw, and draw again if it is something just shown. A handful of
        // tries, then take what comes: with a small pool or a lot of muted
        // formats there may be nothing else to offer.
        var def = pairs[0].first
        var format = pairs[0].second
        for (attempt in 0 until REDRAWS) {
            val pick = rng.range(0f, total)
            var index = cumulative.indexOfFirst { it >= pick }
            if (index < 0) index = pairs.size - 1
            def = pairs[index].first
            format = pairs[index].second
            if (!seenRecently(def, format)) break
        }

        val seed = (rng.nextInt().toLong() shl 20) xor rng.nextInt().toLong()
        val params = randomiseParams(def)
        val laws = if (def.usesLaws && rng.bool(0.35f)) Laws.PRESETS[rng.int(0, Laws.PRESETS.size)]
        else Laws.OUR_UNIVERSE
        val palette = if (rng.bool(0.5f)) def.defaultPalette else Palette.NAMES[rng.int(0, Palette.NAMES.size)]

        return Clip(
            def = def,
            seed = seed,
            params = params,
            laws = laws,
            format = format,
            caption = format.hook,
            palette = palette,
            soundPreset = def.defaultSound,
            // Most clips crisp, some smeared: the smear is part of the variety
            // rather than whatever the player happened to be left on.
            trails = if (rng.bool(0.3f)) rng.range(0.55f, 0.9f) else 0f,
            payoffSeconds = -1f
        )
    }

    /**
     * Randomise within a watchable band.
     *
     * Sizes are deliberately kept modest here: a clip has to be simulated through
     * before it is shown, and a 400,000 particle field would make the check cost
     * more than the clip is worth.
     */
    private fun randomiseParams(def: SimDef): Map<String, Float> {
        val out = HashMap<String, Float>()
        for (p in def.params) {
            if (p.advancedOnly) continue
            // A typed formula is the person's, not something to randomise.
            if (p.type == ParamType.EXPR) continue
            val value = when (p.type) {
                ParamType.EXPR -> p.default
                ParamType.BOOL -> if (rng.bool(0.3f)) 1f else 0f
                ParamType.CHOICE -> rng.int(0, max(1, p.choices.size)).toFloat()
                ParamType.INT -> {
                    val cap = min(p.max, sizeCap(p.key) * sizeScale)
                    val lo = p.min
                    if (cap <= lo) lo else rng.range(lo, cap)
                }
                ParamType.FLOAT -> {
                    // Stay near the default, which is the value that was tuned.
                    val span = (p.max - p.min) * 0.35f
                    (p.default + rng.range(-span, span)).coerceIn(p.min, p.max)
                }
            }
            out[p.key] = value
        }
        return out
    }

    /**
     * How large a generated clip may be.
     *
     * The default used to be no cap at all, which contradicted the paragraph
     * above it: nine of sixteen big parameters fell through, so a proposal could
     * be four hundred thousand darts or eighty thousand tracers, and each one is
     * simulated through before anybody sees it. One flick could stall for
     * seconds. Anything not named here is held to [DEFAULT_CAP], which is wide
     * enough for every sim's shipped default and far short of its maximum.
     */
    private fun sizeCap(key: String): Float = when (key) {
        "n" -> 2500f
        "count" -> 24000f
        "grid" -> 220f
        "beads" -> 900f
        "walkers" -> 400f
        "trail" -> 9000f
        "balls" -> 18f
        "dots", "points", "ink", "samples", "limit" -> 12000f
        "nodes", "cells", "circles", "history", "tracers" -> 4000f
        "maxbars" -> 900f
        "bodies" -> 400f
        "start" -> 9999f
        "particles" -> 24000f
        else -> DEFAULT_CAP
    }

    /** The seconds a payoff may land in for this sim, from what it declares. */
    fun payoffWindow(def: SimDef): ClosedFloatingPointRange<Float> {
        val d = def.typicalSeconds.coerceAtLeast(1f)
        val lo = (d * minPayoffShare).coerceAtLeast(minPayoffSeconds)
        val hi = (d * maxPayoffShare).coerceIn(lo + 1f, maxPayoffSeconds)
        return lo..hi
    }

    /**
     * Run the clip headless and report when the payoff lands, or -1.
     * Nothing is rendered; only the simulation runs.
     */
    fun checkPayoff(clip: Clip, limitSeconds: Float = 45f): Float {
        return try {
            val core = SimCore(clip.def, clip.seed)
            core.setAspect(0.46f)
            core.setLaws(clip.laws)
            for ((k, v) in clip.params) core.params.set(k, v)
            core.restart(clip.seed)
            val steps = (limitSeconds * core.stepHz).toInt()
            var done = 0
            val chunk = 240
            while (done < steps && !core.payoffFired && !core.finished) {
                core.advanceSteps(chunk)
                core.events.clear()
                done += chunk
            }
            scratch.reset()
            if (core.payoffFired) core.payoffAt else -1f
        } catch (e: Throwable) {
            -1f
        }
    }
}
