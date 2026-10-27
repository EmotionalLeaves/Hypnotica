package app.hypnotica.core.recipe

import app.hypnotica.core.laws.Expr

/** Which Simple-mode card a control belongs to. */
enum class Card(val title: String) {
    DATA("Data"), MOTION("Motion"), LOOK("Look"), SOUND("Sound"), LAWS("Laws")
}

enum class ParamType { INT, FLOAT, BOOL, CHOICE, EXPR }

/** How Advanced mode renders the control. Simple mode always picks the big version. */
enum class ParamInput { SLIDER, KNOB, TILES, CHIPS, TOGGLE, FIELD }

/**
 * One tunable value. The same declaration builds both drawers: Simple mode shows
 * the controls whose [advancedOnly] is false, grouped by [card]; Advanced mode
 * shows all of them with exact numbers and accepts a formula in place of a value.
 */
data class Param(
    val key: String,
    val label: String,
    val type: ParamType,
    val min: Float = 0f,
    val max: Float = 1f,
    val default: Float = 0f,
    val choices: List<String> = emptyList(),
    val card: Card = Card.MOTION,
    val advancedOnly: Boolean = false,
    val unit: String = "",
    val input: ParamInput = ParamInput.SLIDER,
    /** Slider response. Counts that span decades read far better on a log slider. */
    val logScale: Boolean = false,
    /** Starting text for an EXPR parameter. */
    val defaultText: String = "",
    /**
     * The key of another parameter that takes this one over, or leaves it
     * unused, at the values in [mutedAt].
     *
     * Optics' Angle was the case that asked for it: the sim overwrites the
     * angle on every step while "Sweep the angle" is on, and that switch
     * defaults to on, so the Angle slider moved, read out a new number in
     * degrees, and changed nothing — the only control in the app that a
     * rebuild could not rescue. Naming the owner lets the control say so
     * and go dim, rather than lying about what it does.
     *
     * A choice can own a value too, which is what the default of [mutedAt]
     * stopped being enough for: the Equation sim reads Start x and Start y in
     * setup and, in the curve mode it ships in, overwrites both on the first
     * step — so two sliders with a four-unit range drew the same picture at
     * every position.
     */
    val mutedBy: String? = null,
    /**
     * The owner's values at which this control is inert. The default is the
     * boolean case, "while the switch is on".
     */
    val mutedAt: List<Int> = listOf(1)
) {
    companion object {
        fun int(
            key: String, label: String, min: Int, max: Int, default: Int,
            card: Card = Card.MOTION, advancedOnly: Boolean = false,
            unit: String = "", logScale: Boolean = false
        ) = Param(
            key, label, ParamType.INT, min.toFloat(), max.toFloat(), default.toFloat(),
            card = card, advancedOnly = advancedOnly, unit = unit, logScale = logScale
        )

        fun float(
            key: String, label: String, min: Float, max: Float, default: Float,
            card: Card = Card.MOTION, advancedOnly: Boolean = false,
            unit: String = "", logScale: Boolean = false, mutedBy: String? = null,
            mutedAt: List<Int> = listOf(1)
        ) = Param(
            key, label, ParamType.FLOAT, min, max, default,
            card = card, advancedOnly = advancedOnly, unit = unit, logScale = logScale,
            mutedBy = mutedBy,
            mutedAt = mutedAt
        )

        fun bool(
            key: String, label: String, default: Boolean,
            card: Card = Card.MOTION, advancedOnly: Boolean = false
        ) = Param(
            key, label, ParamType.BOOL, 0f, 1f, if (default) 1f else 0f,
            card = card, advancedOnly = advancedOnly, input = ParamInput.TOGGLE
        )

        /** A formula the person types, such as an ODE or a parametric curve. */
        fun expr(
            key: String, label: String, default: String,
            card: Card = Card.DATA, advancedOnly: Boolean = false, unit: String = ""
        ) = Param(
            key, label, ParamType.EXPR, 0f, 1f, 0f,
            card = card, advancedOnly = advancedOnly, unit = unit,
            input = ParamInput.FIELD, defaultText = default
        )

        fun choice(
            key: String, label: String, choices: List<String>, default: Int = 0,
            card: Card = Card.MOTION, advancedOnly: Boolean = false,
            input: ParamInput = ParamInput.CHIPS
        ) = Param(
            key, label, ParamType.CHOICE, 0f, (choices.size - 1).toFloat(), default.toFloat(),
            choices = choices, card = card, advancedOnly = advancedOnly, input = input
        )
    }
}

/**
 * A Simple-mode knob that moves several parameters at once, so one control can
 * carry what Advanced mode spreads over a tab.
 */
data class Macro(
    val key: String,
    val label: String,
    val card: Card,
    /** parameter key -> value at macro 0 .. value at macro 1 */
    val targets: Map<String, ClosedFloatingPointRange<Float>>,
    val default: Float = 0f,
    /**
     * parameter key -> the value it holds at the middle of the slider.
     *
     * Without this a macro is a straight line from one end of each range to the
     * other, and the single position that averages several targets does not
     * reproduce any of them — so the first touch of the slider jumps values the
     * person never asked to change. With it, the middle is exactly where the
     * sim already is, and dragging goes down towards each minimum and up towards
     * each maximum.
     */
    val mids: Map<String, Float> = emptyMap(),
    /**
     * Which targets are spaced logarithmically, matching the parameter's own
     * `logScale`. Without this a macro over a count of 1..100000 spent its
     * whole upper half between twenty thousand and a hundred thousand, so the
     * simple control could not express any of the values the precise slider
     * right beneath it reached easily.
     */
    val logs: Set<String> = emptySet()
) {
    companion object {
        /**
         * One target's value at slider position [t]: a straight line when there
         * is no midpoint, and two lines meeting at the midpoint when there is.
         */
        fun valueAt(
            range: ClosedFloatingPointRange<Float>,
            mid: Float?,
            t: Float,
            log: Boolean = false
        ): Float {
            val lo = range.start
            val hi = range.endInclusive
            // exp(ln(x)) does not come back exactly, and a few parts in a
            // hundred million of four hundred thousand is enough to step
            // outside the parameter's declared range.
            val low = minOf(lo, hi)
            val high = maxOf(lo, hi)
            if (mid == null) {
                if (log && canLog(lo, hi)) return (lo * pow(hi / lo, t)).coerceIn(low, high)
                return lo + (hi - lo) * t
            }
            val m = mid.coerceIn(low, high)
            if (log && canLog(lo, hi) && canLog(lo, m) && canLog(m, hi)) {
                val v = if (t < 0.5f) lo * pow(m / lo, t / 0.5f)
                else m * pow(hi / m, (t - 0.5f) / 0.5f)
                return v.coerceIn(low, high)
            }
            return if (t < 0.5f) lo + (m - lo) * (t / 0.5f)
            else m + (hi - m) * ((t - 0.5f) / 0.5f)
        }

        /** Log spacing needs both ends on the same side of zero, and apart. */
        private fun canLog(a: Float, b: Float): Boolean =
            a > 0f && b > 0f && a != b

        private fun pow(base: Float, e: Float): Float =
            kotlin.math.exp(kotlin.math.ln(base) * e)

        /** The position that reproduces [value], the inverse of [valueAt]. */
        fun positionOf(
            range: ClosedFloatingPointRange<Float>,
            mid: Float?,
            value: Float,
            log: Boolean = false
        ): Float {
            val lo = range.start
            val hi = range.endInclusive
            if (mid == null) {
                if (log && canLog(lo, hi)) {
                    val v = value.coerceIn(minOf(lo, hi), maxOf(lo, hi))
                    if (!canLog(lo, v)) return 0f
                    return (kotlin.math.ln(v / lo) / kotlin.math.ln(hi / lo)).coerceIn(0f, 1f)
                }
                val span = hi - lo
                return if (span == 0f) 0f else ((value - lo) / span).coerceIn(0f, 1f)
            }
            val m = mid.coerceIn(minOf(lo, hi), maxOf(lo, hi))
            if (log && canLog(lo, hi) && canLog(lo, m) && canLog(m, hi)) {
                val v = value.coerceIn(minOf(lo, hi), maxOf(lo, hi))
                if (!canLog(lo, v)) return 0f
                return if (v <= m) {
                    (0.5f * kotlin.math.ln(v / lo) / kotlin.math.ln(m / lo)).coerceIn(0f, 0.5f)
                } else {
                    (0.5f + 0.5f * kotlin.math.ln(v / m) / kotlin.math.ln(hi / m))
                        .coerceIn(0.5f, 1f)
                }
            }
            return if (value <= m) {
                val span = m - lo
                if (span == 0f) 0.5f else (0.5f * (value - lo) / span).coerceIn(0f, 0.5f)
            } else {
                val span = hi - m
                if (span == 0f) 0.5f else (0.5f + 0.5f * (value - m) / span).coerceIn(0.5f, 1f)
            }
        }
    }
}

/** Where a macro's targets sit at a given slider position. */
/** Live values for one sim instance. */
class ParamValues(val schema: List<Param>) {

    // Read and written from both the UI thread and the sim thread: the person
    // drags a slider while the sim is mid-step. Concurrent maps rather than locks,
    // because the sim reads these thousands of times a frame.
    private val values = java.util.concurrent.ConcurrentHashMap<String, Float>(schema.size * 2)
    private val texts = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val exprs = java.util.concurrent.ConcurrentHashMap<String, Expr>()
    private val exprSources = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val byKey = schema.associateBy { it.key }

    /** Bumped whenever something changes, so the renderer/UI can notice cheaply. */
    var revision: Int = 0
        private set

    /**
     * Which values the sim reads while it is running, as opposed to only while
     * it is being built.
     *
     * A value a sim copies into a field in setup() and never looks at again
     * cannot change anything mid-run: dragging its slider moved the number and
     * left the picture alone, which was true of about a quarter of every
     * control in the app — whole cards of them, and the headline Chaos and
     * Energy macros along with them. The alternative to this was a hand-kept
     * list of fifty-odd keys that would rot the first time a sim changed, so
     * the sim is asked instead: reads are recorded over the first few frames of
     * a run, and whatever shows up is what can be changed in flight. Anything
     * else needs the run built again to take effect, which is what the Data
     * card has always done.
     */
    private val liveReads = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** False for all but the first few frames of a run, so reads cost one read. */
    @Volatile
    private var watching = false

    /** True once a run has been watched long enough for [readLive] to mean something. */
    @Volatile
    var readsSettled = false
        private set

    /** Called after setup(), so what setup itself read does not count as live. */
    fun watchReads() {
        liveReads.clear()
        readsSettled = false
        watching = true
    }

    /** Called a few frames later: whatever was read by then is read live. */
    fun settleReads() {
        if (!watching) return
        watching = false
        readsSettled = true
    }

    /** True when the running sim reads [key] as it steps or draws. */
    fun readLive(key: String): Boolean = liveReads.contains(key)

    private fun mark(key: String) {
        if (watching) liveReads.add(key)
    }

    init {
        reset()
    }

    fun reset() {
        values.clear()
        exprs.clear()
        exprSources.clear()
        texts.clear()
        for (p in schema) {
            values[p.key] = p.default
            if (p.type == ParamType.EXPR) texts[p.key] = p.defaultText
        }
        revision++
    }

    fun has(key: String): Boolean = values.containsKey(key)

    fun f(key: String, fallback: Float = 0f): Float {
        mark(key)
        return values[key] ?: fallback
    }

    fun i(key: String, fallback: Int = 0): Int {
        mark(key)
        return values[key]?.let { Math.round(it) } ?: fallback
    }

    fun b(key: String, fallback: Boolean = false): Boolean {
        mark(key)
        return values[key]?.let { it >= 0.5f } ?: fallback
    }

    /** The selected option of a CHOICE parameter. */
    fun choice(key: String, fallback: String = ""): String {
        val p = byKey[key] ?: return fallback
        if (p.choices.isEmpty()) return fallback
        val idx = i(key).coerceIn(0, p.choices.size - 1)
        return p.choices[idx]
    }

    fun choiceIndex(key: String): Int {
        val p = byKey[key] ?: return 0
        return i(key).coerceIn(0, maxOf(0, p.choices.size - 1))
    }

    /**
     * Set a value. A formula attached to the same key is left alone: it used to
     * be dropped here, which meant a slider nudge — or one tick of Evolve —
     * silently deleted something the person had typed. Use [clearExpression] to
     * remove a formula on purpose.
     */
    fun set(key: String, value: Float) {
        val p = byKey[key]
        val v = if (p != null) value.coerceIn(p.min, p.max) else value
        if (values[key] != v) {
            values[key] = v
            revision++
        }
    }

    fun hasExpression(key: String): Boolean = exprs.containsKey(key)

    /** The text of an EXPR parameter. */
    fun text(key: String, fallback: String = ""): String = texts[key] ?: fallback

    /** Returns false (and changes nothing) if the formula does not parse. */
    fun setText(key: String, value: String): Boolean {
        if (Expr.compileOrNull(value) == null) return false
        if (texts[key] != value) {
            texts[key] = value
            revision++
        }
        return true
    }

    fun set(key: String, value: Int) = set(key, value.toFloat())
    fun set(key: String, value: Boolean) = set(key, if (value) 1f else 0f)

    fun param(key: String): Param? = byKey[key]

    /** Attach a formula. Returns false (and changes nothing) if it does not parse. */
    fun setExpression(key: String, source: String): Boolean {
        val e = Expr.compileOrNull(source) ?: return false
        exprs[key] = e
        exprSources[key] = source
        revision++
        return true
    }

    fun expressionSource(key: String): String? = exprSources[key]

    fun clearExpression(key: String) {
        if (exprs.remove(key) != null) {
            exprSources.remove(key)
            revision++
        }
    }

    /** Re-evaluate every attached formula. Called once per sim step. */
    fun tick(time: Float) {
        if (exprs.isEmpty()) return
        for ((key, e) in exprs) {
            val p = byKey[key]
            val v = e.eval { name ->
                when (name) {
                    "t", "time" -> time
                    else -> values[name] ?: Float.NaN
                }
            }
            val clamped = if (p != null) v.coerceIn(p.min, p.max) else v
            values[key] = clamped
        }
        revision++
    }

    /**
     * Apply a macro position (0..1) across its target parameters.
     *
     * Any formula attached to a target is dropped, because tick() would
     * otherwise overwrite the value on the next step: the slider moved, the
     * label changed, and the sim carried on exactly as before with nothing to
     * say why.
     */
    fun applyMacro(macro: Macro, position: Float) {
        val t = position.coerceIn(0f, 1f)
        for ((key, range) in macro.targets) {
            clearExpression(key)
            set(key, Macro.valueAt(range, macro.mids[key], t, macro.logs.contains(key)))
        }
    }

    fun textSnapshot(): Map<String, String> = HashMap(texts)

    fun restoreTexts(map: Map<String, String>) {
        for ((k, v) in map) texts[k] = v
        revision++
    }

    fun snapshot(): Map<String, Float> = HashMap(values)

    fun restore(map: Map<String, Float>) {
        for ((k, v) in map) if (byKey.containsKey(k)) values[k] = v
        revision++
    }
}
