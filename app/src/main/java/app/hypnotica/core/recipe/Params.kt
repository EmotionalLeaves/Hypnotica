package app.hypnotica.core.recipe

import app.hypnotica.core.laws.Expr

/** Which Simple-mode card a control belongs to. */
enum class Card(val title: String) {
    DATA("Data"), MOTION("Motion"), LOOK("Look"), SOUND("Sound"), LAWS("Laws")
}

enum class ParamType { INT, FLOAT, BOOL, CHOICE }

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
    val logScale: Boolean = false
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
            unit: String = "", logScale: Boolean = false
        ) = Param(
            key, label, ParamType.FLOAT, min, max, default,
            card = card, advancedOnly = advancedOnly, unit = unit, logScale = logScale
        )

        fun bool(
            key: String, label: String, default: Boolean,
            card: Card = Card.MOTION, advancedOnly: Boolean = false
        ) = Param(
            key, label, ParamType.BOOL, 0f, 1f, if (default) 1f else 0f,
            card = card, advancedOnly = advancedOnly, input = ParamInput.TOGGLE
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
    val default: Float = 0f
)

/** Live values for one sim instance. */
class ParamValues(val schema: List<Param>) {

    private val values = HashMap<String, Float>(schema.size * 2)
    private val exprs = HashMap<String, Expr>()
    private val exprSources = HashMap<String, String>()
    private val byKey = schema.associateBy { it.key }

    /** Bumped whenever something changes, so the renderer/UI can notice cheaply. */
    var revision: Int = 0
        private set

    init {
        reset()
    }

    fun reset() {
        values.clear()
        exprs.clear()
        exprSources.clear()
        for (p in schema) values[p.key] = p.default
        revision++
    }

    fun has(key: String): Boolean = values.containsKey(key)

    fun f(key: String, fallback: Float = 0f): Float = values[key] ?: fallback

    fun i(key: String, fallback: Int = 0): Int =
        values[key]?.let { Math.round(it) } ?: fallback

    fun b(key: String, fallback: Boolean = false): Boolean =
        values[key]?.let { it >= 0.5f } ?: fallback

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

    fun set(key: String, value: Float) {
        val p = byKey[key]
        val v = if (p != null) value.coerceIn(p.min, p.max) else value
        if (values[key] != v) {
            values[key] = v
            revision++
        }
        exprs.remove(key)
        exprSources.remove(key)
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

    /** Apply a macro position (0..1) across its target parameters. */
    fun applyMacro(macro: Macro, position: Float) {
        val t = position.coerceIn(0f, 1f)
        for ((key, range) in macro.targets) {
            set(key, range.start + (range.endInclusive - range.start) * t)
        }
    }

    fun snapshot(): Map<String, Float> = HashMap(values)

    fun restore(map: Map<String, Float>) {
        for ((k, v) in map) if (byKey.containsKey(k)) values[k] = v
        revision++
    }
}
