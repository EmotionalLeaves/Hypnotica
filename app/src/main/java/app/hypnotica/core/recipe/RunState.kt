package app.hypnotica.core.recipe

import app.hypnotica.core.laws.Laws
import app.hypnotica.core.laws.Space

/**
 * A run, written down.
 *
 * The Code tab has always printed the current run as JSON under the words
 * "everything needed to replay it", which was true of the text and false of the
 * app: there was no way to get it off the phone and no way to put one back. The
 * printing lives in AppState.stateJson; this is the reading.
 *
 * Values arrive as the text they were written as, because what a value means
 * depends on the schema of the sim it belongs to and that is not known here —
 * the same `2` is an index for a CHOICE, a count for an INT and a formula for
 * an EXPR. Everything but the sim id is optional: a run pasted from an older
 * build, or typed by hand, should apply the parts it does have rather than be
 * rejected whole.
 */
data class RunState(
    val recipe: String,
    val seed: Long? = null,
    val params: Map<String, String> = emptyMap(),
    val laws: String? = null,
    /**
     * The laws that differ from our universe, by field name.
     *
     * The name alone was all a run used to carry, and `Laws.preset("Custom")`
     * falls back to our universe — so every hand-edited universe replayed as
     * no universe at all, silently, which is the worst way for it to fail.
     */
    val lawFields: Map<String, String> = emptyMap(),
    /**
     * True when the run carried the universe itself rather than only its name.
     *
     * Not the same as [lawFields] being empty: a Custom universe that happens
     * to match our universe in every field writes a name and no fields, and
     * looking it up by name — there is no preset called Custom — gave back our
     * universe under the wrong name.
     */
    val lawsExact: Boolean = false,
    val palette: String? = null,
    val trails: Float? = null,
    val soundPreset: String? = null,
    val scale: String? = null
)

sealed interface RunParse {
    data class Ok(val run: RunState) : RunParse
    /** Plain language, because it is shown to whoever pasted the thing. */
    data class Bad(val why: String) : RunParse
}

/**
 * Read a run back. Never throws: anything can be pasted in, so every failure
 * comes back as a sentence rather than an exception.
 */
fun parseRun(text: String): RunParse {
    val root = try {
        Json(text).parse()
    } catch (e: JsonError) {
        return RunParse.Bad(e.message ?: "That is not a run.")
    } catch (e: RuntimeException) {
        // A deeply nested or malformed paste can exhaust the stack rather than
        // reach a parse error; it is still just a bad paste.
        return RunParse.Bad("That is not a run.")
    }
    if (root !is Map<*, *>) return RunParse.Bad("A run is written as { … }; this is not.")
    val recipe = root.str("recipe")
        ?: return RunParse.Bad("No sim in it — a run needs a \"recipe\" line.")

    val params = LinkedHashMap<String, String>()
    (root["params"] as? Map<*, *>)?.forEach { (k, v) ->
        val key = k as? String ?: return@forEach
        params[key] = when (v) {
            null -> return@forEach
            is String -> v
            is Boolean -> v.toString()
            is Long -> v.toString()
            is Double -> trim(v)
            else -> return@forEach
        }
    }
    val lawFields = LinkedHashMap<String, String>()
    val lawsNode = root["laws"]
    if (lawsNode is Map<*, *>) {
        lawsNode.forEach { (k, v) ->
            val key = k as? String ?: return@forEach
            if (key == "name") return@forEach
            lawFields[key] = when (v) {
                is String -> v
                is Boolean -> v.toString()
                is Long -> v.toString()
                is Double -> trim(v)
                else -> return@forEach
            }
        }
    }
    val look = root["look"] as? Map<*, *>
    val sound = root["sound"] as? Map<*, *>
    return RunParse.Ok(
        RunState(
            recipe = recipe,
            seed = (root["seed"] as? Number)?.toLong(),
            params = params,
            laws = (lawsNode as? String)?.takeIf { it.isNotBlank() }
                ?: (lawsNode as? Map<*, *>)?.str("name"),
            lawFields = lawFields,
            lawsExact = lawsNode is Map<*, *>,
            palette = look?.str("palette"),
            trails = (look?.get("trails") as? Number)?.toFloat(),
            soundPreset = sound?.str("preset"),
            scale = sound?.str("scale")
        )
    )
}

/**
 * The parameters of a run, written down.
 *
 * Next to the reader, like the laws, and for the same reason: these two are the
 * whole of "everything needed to replay it", and a writer that drifts from its
 * reader fails silently — the run loads, and one value is quietly not the one
 * that was written. RunStateTest round-trips this over every sim in the app.
 */
fun runParamsJson(schema: List<Param>, params: ParamValues): String {
    val sb = StringBuilder(256)
    schema.forEachIndexed { i, p ->
        if (i > 0) sb.append(",")
        sb.append("\n    \"").append(p.key).append("\": ")
        val expr = params.expressionSource(p.key)
        when {
            expr != null -> sb.append(jsonString(expr))
            // A formula field holds text, not a number, and nothing used to
            // write it down — so the Equation sim, whose entire subject is the
            // two formulas you type into it, printed a run that replayed as the
            // formulas it shipped with.
            p.type == ParamType.EXPR -> sb.append(jsonString(params.text(p.key, p.defaultText)))
            p.type == ParamType.CHOICE -> sb.append(jsonString(params.choice(p.key)))
            p.type == ParamType.INT -> sb.append(params.i(p.key))
            p.type == ParamType.BOOL -> sb.append(params.b(p.key))
            else -> sb.append(String.format(java.util.Locale.US, "%.4f", params.f(p.key)))
        }
    }
    return sb.toString()
}

/**
 * Write a run's values into [params], and report the keys this build does not
 * have — a run from a newer version applies the part it can rather than being
 * turned away whole.
 *
 * Nothing is restarted here. The caller restarts once, afterwards: the
 * per-control setters each rebuild the sim when the value they changed needs
 * it, so going through them rebuilt it a dozen times on the way in.
 */
fun applyRunParams(run: RunState, schema: List<Param>, params: ParamValues): List<String> {
    val unknown = ArrayList<String>(2)
    for ((key, value) in run.params) {
        val p = schema.firstOrNull { it.key == key }
        if (p == null) { unknown.add(key); continue }
        val number = value.toFloatOrNull()?.takeIf { it.isFinite() }
        when {
            p.type == ParamType.EXPR -> params.setText(key, value)
            p.type == ParamType.BOOL ->
                params.set(key, value.equals("true", ignoreCase = true) || number == 1f)
            p.type == ParamType.CHOICE -> {
                val byName = p.choices.indexOfFirst { it.equals(value, ignoreCase = true) }
                val index = if (byName >= 0) byName else number?.toInt() ?: continue
                params.set(key, index.coerceIn(0, maxOf(0, p.choices.size - 1)).toFloat())
            }
            // A value written as a string on a numeric parameter is a formula:
            // that is how runParamsJson prints one.
            number == null -> params.setExpression(key, value)
            p.type == ParamType.INT -> params.set(key, Math.round(number).toFloat())
            else -> params.set(key, number)
        }
    }
    return unknown
}

/** Quoted, with the quote character itself turned into an apostrophe. */
private fun jsonString(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "'").replace("\n", " ") + "\""

/**
 * A universe, written down: its name alone when that name still describes it,
 * and the fields that differ from our universe when it does not.
 *
 * The reader is a few lines below the writer on purpose. They are the only
 * guarantee the app can replay what it prints, and a round-trip test
 * (LawsRoundTripTest) holds them to it.
 */
fun lawsJson(laws: Laws): String {
    val named = Laws.preset(laws.name)
    // Name only when the preset of that name is this universe — a hand-edited
    // "Moon" is not Moon.
    if (named.name == laws.name && named == laws) return quote(laws.name)
    val base = Laws.OUR_UNIVERSE
    val out = StringBuilder("{ ").append("\"name\": ").append(quote(laws.name))
    fun field(key: String, value: String) = out.append(", \"").append(key).append("\": ").append(value)
    if (laws.gravity != base.gravity) field("gravity", num(laws.gravity))
    if (laws.gravityDirX != base.gravityDirX) field("gravityDirX", num(laws.gravityDirX))
    if (laws.gravityDirY != base.gravityDirY) field("gravityDirY", num(laws.gravityDirY))
    if (laws.gravityConstant != base.gravityConstant) field("gravityConstant", num(laws.gravityConstant))
    if (laws.falloff != base.falloff) field("falloff", num(laws.falloff))
    if (laws.forceExpr != base.forceExpr) field("forceExpr", quote(laws.forceExpr ?: ""))
    if (laws.massSign != base.massSign) field("massSign", num(laws.massSign))
    if (laws.restitution != base.restitution) field("restitution", num(laws.restitution))
    if (laws.friction != base.friction) field("friction", num(laws.friction))
    if (laws.sticky != base.sticky) field("sticky", laws.sticky.toString())
    if (laws.passThrough != base.passThrough) field("passThrough", laws.passThrough.toString())
    if (laws.conserveEnergy != base.conserveEnergy) field("conserveEnergy", laws.conserveEnergy.toString())
    if (laws.lightSpeed != base.lightSpeed) field("lightSpeed", num(laws.lightSpeed))
    if (laws.timeDirection != base.timeDirection) field("timeDirection", num(laws.timeDirection))
    if (laws.timeScale != base.timeScale) field("timeScale", num(laws.timeScale))
    if (laws.chargeSign != base.chargeSign) field("chargeSign", num(laws.chargeSign))
    if (laws.electricStrength != base.electricStrength) field("electricStrength", num(laws.electricStrength))
    if (laws.space != base.space) field("space", quote(laws.space.name))
    return out.append(" }").toString()
}

/**
 * The universe a run asks for, or null when it does not mention one.
 *
 * A field the run does not carry keeps its value from our universe rather than
 * from whatever happens to be loaded, so pasting the same run twice gives the
 * same picture both times.
 */
fun runLaws(run: RunState): Laws? {
    val name = run.laws ?: return null
    if (!run.lawsExact) return Laws.preset(name)
    val b = Laws.OUR_UNIVERSE
    val f = run.lawFields
    return Laws(
        gravity = f.num("gravity", b.gravity),
        gravityDirX = f.num("gravityDirX", b.gravityDirX),
        gravityDirY = f.num("gravityDirY", b.gravityDirY),
        gravityConstant = f.num("gravityConstant", b.gravityConstant),
        falloff = f.num("falloff", b.falloff),
        forceExpr = f["forceExpr"]?.takeIf { it.isNotBlank() } ?: b.forceExpr,
        massSign = f.num("massSign", b.massSign),
        restitution = f.num("restitution", b.restitution),
        friction = f.num("friction", b.friction),
        sticky = f.bool("sticky", b.sticky),
        passThrough = f.bool("passThrough", b.passThrough),
        conserveEnergy = f.bool("conserveEnergy", b.conserveEnergy),
        lightSpeed = f.num("lightSpeed", b.lightSpeed),
        timeDirection = f.num("timeDirection", b.timeDirection),
        timeScale = f.num("timeScale", b.timeScale),
        chargeSign = f.num("chargeSign", b.chargeSign),
        electricStrength = f.num("electricStrength", b.electricStrength),
        space = if (f["space"].equals(Space.TORUS.name, ignoreCase = true)) Space.TORUS else b.space,
        name = name
    )
}

private fun Map<String, String>.num(key: String, fallback: Float): Float =
    this[key]?.toFloatOrNull()?.takeIf { it.isFinite() } ?: fallback

private fun Map<String, String>.bool(key: String, fallback: Boolean): Boolean =
    when (this[key]?.lowercase()) { "true" -> true; "false" -> false; else -> fallback }

private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "'") + "\""

/** Short where it can be: 1.5 rather than 1.500000, 2 rather than 2.0. */
private fun num(v: Float): String = when {
    // Negative zero is a real float with a sign bit, and a data class compares
    // it as different from zero. Dropping the sign here made a universe come
    // back unequal to the one that was written, for a difference nothing can
    // see — so the sign is kept and the guarantee stays unconditional.
    v == 0f -> if (1f / v < 0f) "-0" else "0"
    v == Math.floor(v.toDouble()).toFloat() && kotlin.math.abs(v) < 1e9f -> v.toLong().toString()
    else -> String.format(java.util.Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
}

private fun Map<*, *>.str(key: String): String? = (this[key] as? String)?.takeIf { it.isNotBlank() }

/** 2.0 reads as "2" — an INT parameter should not have to cope with "2.0". */
private fun trim(d: Double): String = when {
    // Negative zero keeps its sign for the same reason it does in num(): the
    // two are one picture but not one value, and a round trip that quietly
    // changes a value is not a round trip.
    d == 0.0 -> if (1.0 / d < 0.0) "-0" else "0"
    d == Math.floor(d) && !d.isInfinite() -> d.toLong().toString()
    else -> d.toString()
}
