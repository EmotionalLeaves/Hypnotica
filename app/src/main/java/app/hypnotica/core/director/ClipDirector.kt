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

    var minPayoffSeconds = 4f
    var maxPayoffSeconds = 40f

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
    fun generate(tries: Int = 12): Clip? {
        lastAttempts = 0
        var fallback: Clip? = null
        repeat(tries) {
            lastAttempts++
            val candidate = propose() ?: return@repeat
            val payoff = checkPayoff(candidate)
            if (payoff in minPayoffSeconds..maxPayoffSeconds) {
                return candidate.copy(payoffSeconds = payoff)
            }
            if (fallback == null && payoff > 0f) fallback = candidate.copy(payoffSeconds = payoff)
        }
        return fallback
    }

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
        val pick = rng.range(0f, total)
        var index = cumulative.indexOfFirst { it >= pick }
        if (index < 0) index = pairs.size - 1
        val (def, format) = pairs[index]

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
            val value = when (p.type) {
                ParamType.BOOL -> if (rng.bool(0.3f)) 1f else 0f
                ParamType.CHOICE -> rng.int(0, max(1, p.choices.size)).toFloat()
                ParamType.INT -> {
                    val cap = min(p.max, sizeCap(p.key))
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

    private fun sizeCap(key: String): Float = when (key) {
        "n" -> 2500f
        "count" -> 24000f
        "grid" -> 220f
        "beads" -> 900f
        "walkers" -> 400f
        "trail" -> 9000f
        "balls" -> 18f
        else -> Float.MAX_VALUE
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
