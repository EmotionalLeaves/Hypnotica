package app.hypnotica.core.recipe

import app.hypnotica.core.render.G
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.LawKnob
import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.Sim
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.SimEnv
import app.hypnotica.core.sim.TouchEvent
import app.hypnotica.core.sim.TrapFormat
import org.luaj.vm2.Globals
import org.luaj.vm2.LoadState
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.Bit32Lib
import org.luaj.vm2.lib.CoroutineLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JseBaseLib
import org.luaj.vm2.lib.jse.JseMathLib

/**
 * A sim written in Lua.
 *
 * This is what makes "simulate almost anything" true rather than a slogan: a
 * recipe is a few dozen lines of plain script, and the app treats it exactly
 * like one of the built-in sims. The built-ins stay in Kotlin because they carry
 * the huge counts; a recipe is for the long tail of everything else.
 *
 * The sandbox has no io, no os, no package and no require: a recipe can do
 * arithmetic, call back into the engine, and nothing else.
 */
class LuaRecipe private constructor(
    val source: String,
    val meta: RecipeMeta,
    val params: List<Param>,
    /**
     * The recipe's own Simple-mode controls.
     *
     * Without these a recipe only ever got the derived "How much" and "Energy",
     * so the orbit recipe's one simple slider moved its trail buffer rather
     * than the launch speed that decides whether the thing orbits at all — the
     * promise that Simple mode is never a cut-down Advanced mode was
     * unachievable for every user-written sim.
     */
    val macros: List<Macro> = emptyList()
) {

    data class RecipeMeta(
        val id: String,
        val title: String,
        val shelf: Shelf,
        val family: String,
        val blurb: String,
        val hero: String,
        val palette: String,
        val sound: String,
        val seconds: Float,
        val usesLaws: Boolean,
        val formats: List<TrapFormat>
    )

    fun toSimDef(): SimDef = SimDef(
        id = meta.id,
        title = meta.title,
        shelf = meta.shelf,
        family = meta.family,
        blurb = meta.blurb,
        formats = meta.formats,
        params = params,
        heroLabel = meta.hero,
        defaultPalette = meta.palette,
        defaultSound = meta.sound,
        typicalSeconds = meta.seconds,
        macros = macros,
        lawKnobs = if (meta.usesLaws) knobsIn(source) else emptySet(),
        create = { LuaSim(source) }
    )

    companion object {

        const val MAX_SOURCE_BYTES = 200_000

        /**
         * The laws a recipe's own `law(...)` calls actually ask for.
         *
         * A recipe that says `uses_laws` would otherwise be offered the whole
         * Laws card, most of which it ignores — the same dead-control problem
         * the built-in sims had. Read from the source, so a recipe that asks
         * for gravity gets the gravity slider and nothing else. A recipe that
         * builds the name at runtime falls back to the common set, because that
         * cannot be read from here.
         */
        fun knobsIn(source: String): Set<LawKnob> {
            val pattern = Regex("\\blaw\\s*\\(\\s*[\"']([a-z_]+)[\"']")
            val names = pattern.findAll(source).map { it.groupValues[1] }.toSet()
            if (names.isEmpty()) return COMMON_KNOBS
            val knobs = HashSet<LawKnob>()
            for (name in names) {
                when (name) {
                    "gravity" -> knobs.add(LawKnob.GRAVITY)
                    "restitution", "bounciness" -> knobs.add(LawKnob.BOUNCE)
                    "friction" -> knobs.add(LawKnob.FRICTION)
                    "sticky", "pass_through" -> knobs.add(LawKnob.CONTACT)
                    "electric" -> knobs.add(LawKnob.CHARGE)
                    "wrap" -> knobs.add(LawKnob.SPACE)
                    "mass_sign", "g_constant", "falloff" -> knobs.add(LawKnob.PAIRWISE_FORCE)
                    "light_speed" -> knobs.add(LawKnob.LIGHT_SPEED)
                    // time_dir and time_scale are engine-wide and always shown.
                }
            }
            return if (knobs.isEmpty()) COMMON_KNOBS else knobs
        }

        /** What a recipe gets when its law names cannot be read from the source. */
        private val COMMON_KNOBS = setOf(
            LawKnob.GRAVITY, LawKnob.BOUNCE, LawKnob.FRICTION, LawKnob.CONTACT, LawKnob.SPACE
        )

        /** Build a fresh sandbox. Each sim instance gets its own. */
        fun sandbox(): Globals {
            val g = Globals()
            g.load(JseBaseLib())
            g.load(Bit32Lib())
            g.load(TableLib())
            g.load(StringLib())
            g.load(CoroutineLib())
            g.load(JseMathLib())
            LoadState.install(g)
            LuaC.install(g)
            // Nothing should be able to reach the file system or load more code.
            g.set("require", LuaValue.NIL)
            g.set("dofile", LuaValue.NIL)
            g.set("loadfile", LuaValue.NIL)
            g.set("load", LuaValue.NIL)
            g.set("loadstring", LuaValue.NIL)
            g.set("collectgarbage", LuaValue.NIL)
            return g
        }

        /**
         * Read a recipe's declarations without running its simulation.
         * Returns a readable message on failure rather than throwing.
         */
        fun parse(source: String, fallbackId: String): Result<LuaRecipe> {
            if (source.toByteArray().size > MAX_SOURCE_BYTES) {
                return Result.failure(IllegalArgumentException("Recipe is too long"))
            }
            return try {
                val g = sandbox()
                g.load(source, "recipe").call()
                val metaTable = g.get("meta")
                val id = optString(metaTable, "id", fallbackId)
                val meta = RecipeMeta(
                    id = if (id.startsWith("recipe/")) id else "recipe/$id",
                    title = optString(metaTable, "title", "Untitled recipe"),
                    shelf = shelfFrom(optString(metaTable, "shelf", "math")),
                    family = optString(metaTable, "family", "Recipes"),
                    blurb = optString(metaTable, "blurb", ""),
                    hero = optString(metaTable, "hero", "Restart"),
                    palette = optString(metaTable, "palette", "Aurora"),
                    sound = optString(metaTable, "sound", "Music box"),
                    seconds = optNumber(metaTable, "seconds", 24.0).toFloat(),
                    usesLaws = optBool(metaTable, "laws", false),
                    formats = formatsFrom(metaTable)
                )
                val params = readParams(g.get("params"))
                if (g.get("step").isnil() && g.get("draw").isnil()) {
                    return Result.failure(IllegalArgumentException("A recipe needs a step or a draw function"))
                }
                Result.success(LuaRecipe(source, meta, params, readMacros(g.get("macros"), params)))
            } catch (e: LuaError) {
                Result.failure(IllegalArgumentException(cleanMessage(e.message)))
            } catch (e: Throwable) {
                Result.failure(IllegalArgumentException(cleanMessage(e.message)))
            }
        }

        fun cleanMessage(raw: String?): String {
            val m = raw ?: "Recipe failed to load"
            return m.replace("recipe:", "line ").take(220)
        }

        private fun shelfFrom(name: String): Shelf =
            Shelf.entries.firstOrNull {
                it.name.equals(name, true) || it.title.equals(name, true)
            } ?: Shelf.MATH

        private fun formatsFrom(meta: LuaValue): List<TrapFormat> {
            if (!meta.istable()) return listOf(TrapFormat.LOOP)
            val list = meta.get("formats")
            if (!list.istable()) return listOf(TrapFormat.LOOP)
            val out = ArrayList<TrapFormat>()
            var i = 1
            while (true) {
                val v = list.get(i)
                if (v.isnil()) break
                val name = v.tojstring()
                TrapFormat.entries.firstOrNull {
                    it.name.equals(name, true) || it.title.equals(name, true)
                }?.let { out.add(it) }
                i++
            }
            return if (out.isEmpty()) listOf(TrapFormat.LOOP) else out
        }

        private fun optString(t: LuaValue, key: String, fallback: String): String {
            if (!t.istable()) return fallback
            val v = t.get(key)
            return if (v.isnil()) fallback else v.tojstring()
        }

        private fun optNumber(t: LuaValue, key: String, fallback: Double): Double {
            if (!t.istable()) return fallback
            val v = t.get(key)
            return if (v.isnumber()) v.todouble() else fallback
        }

        private fun optBool(t: LuaValue, key: String, fallback: Boolean): Boolean {
            if (!t.istable()) return fallback
            val v = t.get(key)
            return if (v.isnil()) fallback else v.toboolean()
        }

        /**
         * `macros = { { label = "Pull", card = "motion",
         *               targets = { speed = {0.2, 3}, mass = {0.5, 4} } } }`
         *
         * A target naming a parameter the recipe does not declare is dropped
         * rather than failing the load: a half-written recipe should still run.
         */
        private fun readMacros(list: LuaValue, params: List<Param>): List<Macro> {
            if (!list.istable()) return emptyList()
            val known = params.associateBy { it.key }
            val out = ArrayList<Macro>()
            var i = 1
            while (i <= 8) {
                val m = list.get(i)
                if (m.isnil()) break
                i++
                if (!m.istable()) continue
                val targetTable = m.get("targets")
                if (!targetTable.istable()) continue
                val targets = LinkedHashMap<String, ClosedFloatingPointRange<Float>>()
                val logs = HashSet<String>()
                for (key in targetTable.keys()) {
                    val name = key.tojstring()
                    val param = known[name] ?: continue
                    val pair = targetTable.get(key)
                    if (!pair.istable()) continue
                    val lo = pair.get(1)
                    val hi = pair.get(2)
                    if (lo.isnil() || hi.isnil()) continue
                    targets[name] = lo.tofloat()..hi.tofloat()
                    if (param.logScale) logs.add(name)
                }
                if (targets.isEmpty()) continue
                val label = optString(m, "label", "How much")
                out.add(
                    Macro(
                        key = optString(m, "key", "recipe-" + out.size),
                        label = label,
                        card = cardFrom(optString(m, "card", "motion")),
                        targets = targets,
                        logs = logs
                    )
                )
            }
            return out
        }

        private fun readParams(list: LuaValue): List<Param> {
            if (!list.istable()) return emptyList()
            val out = ArrayList<Param>()
            var i = 1
            while (i <= 64) {
                val p = list.get(i)
                if (p.isnil()) break
                i++
                if (!p.istable()) continue
                val key = optString(p, "key", "")
                if (key.isEmpty()) continue
                val label = optString(p, "label", key)
                val card = cardFrom(optString(p, "card", "motion"))
                val advanced = optBool(p, "advanced", false)
                val unit = optString(p, "unit", "")
                val log = optBool(p, "log", false)
                when (optString(p, "type", "float").lowercase()) {
                    "int" -> out.add(
                        Param.int(
                            key, label,
                            optNumber(p, "min", 0.0).toInt(),
                            optNumber(p, "max", 100.0).toInt(),
                            optNumber(p, "default", 10.0).toInt(),
                            card, advanced, unit, log
                        )
                    )
                    "bool" -> out.add(Param.bool(key, label, optBool(p, "default", false), card, advanced))
                    "choice" -> {
                        val choices = ArrayList<String>()
                        val cl = p.get("choices")
                        if (cl.istable()) {
                            var k = 1
                            while (true) {
                                val c = cl.get(k)
                                if (c.isnil()) break
                                choices.add(c.tojstring())
                                k++
                            }
                        }
                        if (choices.isNotEmpty()) {
                            out.add(
                                Param.choice(
                                    key, label, choices,
                                    optNumber(p, "default", 0.0).toInt(), card, advanced
                                )
                            )
                        }
                    }
                    else -> out.add(
                        Param.float(
                            key, label,
                            optNumber(p, "min", 0.0).toFloat(),
                            optNumber(p, "max", 1.0).toFloat(),
                            optNumber(p, "default", 0.5).toFloat(),
                            card, advanced, unit, log
                        )
                    )
                }
            }
            return out
        }

        private fun cardFrom(name: String): Card =
            Card.entries.firstOrNull { it.name.equals(name, true) || it.title.equals(name, true) }
                ?: Card.MOTION
    }
}

/**
 * Runs one Lua recipe.
 *
 * The script sees a small, fixed API and a budget: if a step or a frame takes
 * too long the recipe is stopped rather than allowed to freeze the app, and the
 * last good frame stays on screen with a badge.
 */
class LuaSim(private val source: String) : Sim {

    private var globals: Globals? = null
    private var state: LuaValue = LuaValue.NIL
    private var fnSetup: LuaValue = LuaValue.NIL
    private var fnStep: LuaValue = LuaValue.NIL
    private var fnDraw: LuaValue = LuaValue.NIL
    private var fnInput: LuaValue = LuaValue.NIL
    private var fnPayoff: LuaValue = LuaValue.NIL

    private var env: SimEnv? = null
    private var g: G? = null
    private var payoffFrame = false
    private var overBudgetSteps = 0
    private var overBudgetDraws = 0

    /** Set when the recipe threw or ran away; shown in the UI, never a crash. */
    var error: String? = null
        private set

    val isBroken: Boolean get() = error != null

    override fun setup(env: SimEnv) {
        this.env = env
        payoffFrame = false
        overBudgetSteps = 0
        error = null
        try {
            val lua = LuaRecipe.sandbox()
            installApi(lua)
            lua.load(source, "recipe").call()
            fnSetup = lua.get("setup")
            fnStep = lua.get("step")
            fnDraw = lua.get("draw")
            fnInput = lua.get("on_input")
            fnPayoff = lua.get("payoff")
            state = LuaTable()
            globals = lua
            if (!fnSetup.isnil()) {
                val setupStart = System.nanoTime()
                fnSetup.call(state)
                val setupMs = (System.nanoTime() - setupStart) / 1_000_000f
                if (setupMs > SETUP_BUDGET_MS) {
                    error = "Recipe setup took ${setupMs.toInt()} ms; the limit is " +
                        "${SETUP_BUDGET_MS.toInt()}"
                    globals = null
                }
            }
        } catch (e: Throwable) {
            error = LuaRecipe.cleanMessage(e.message)
            globals = null
        }
    }

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        if (globals == null || error != null) return
        val started = System.nanoTime()
        try {
            if (!fnStep.isnil()) fnStep.call(state, LuaValue.valueOf(dt.toDouble()))
            if (!fnPayoff.isnil()) {
                payoffFrame = fnPayoff.call(state).toboolean()
            }
        } catch (e: Throwable) {
            error = LuaRecipe.cleanMessage(e.message)
            return
        }
        // A recipe that cannot keep up is slowed, then stopped: the app stays
        // responsive whatever the script does.
        val tookMs = (System.nanoTime() - started) / 1_000_000f
        if (tookMs > STEP_BUDGET_MS) {
            overBudgetSteps++
            if (overBudgetSteps > OVER_BUDGET_LIMIT) {
                error = "Recipe is too slow: each step took over ${STEP_BUDGET_MS.toInt()} ms"
            }
        } else if (overBudgetSteps > 0) {
            overBudgetSteps--
        }
    }

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        this.g = g
        this.env = env
        // A recipe stopped for being too slow kept drawing every frame, which
        // was the freeze the budget existed to prevent.
        if (globals == null || error != null) return
        val started = System.nanoTime()
        try {
            if (!fnDraw.isnil()) fnDraw.call(state, LuaValue.valueOf(alpha.toDouble()))
        } catch (e: Throwable) {
            if (error == null) error = LuaRecipe.cleanMessage(e.message)
            return
        }
        // draw had no budget at all, so a slow one froze the frame however well
        // behaved its step was.
        val tookMs = (System.nanoTime() - started) / 1_000_000f
        if (tookMs > DRAW_BUDGET_MS) {
            overBudgetDraws++
            if (overBudgetDraws > OVER_BUDGET_LIMIT) {
                error = "Recipe draws too slowly: over ${DRAW_BUDGET_MS.toInt()} ms a frame"
            }
        } else if (overBudgetDraws > 0) {
            overBudgetDraws--
        }
    }

    override fun onInput(env: SimEnv, e: TouchEvent) {
        if (globals == null || fnInput.isnil()) return
        try {
            // call() tops out at three arguments; four go through invoke().
            fnInput.invoke(
                LuaValue.varargsOf(
                    arrayOf(
                        state,
                        LuaValue.valueOf(e.kind.name.lowercase()),
                        LuaValue.valueOf(e.x.toDouble()),
                        LuaValue.valueOf(e.y.toDouble())
                    )
                )
            )
        } catch (ex: Throwable) {
            if (error == null) error = LuaRecipe.cleanMessage(ex.message)
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun counters(): List<Pair<String, String>> =
        error?.let { listOf("recipe error" to it) } ?: emptyList()

    // ----------------------------------------------------------------- api

    private fun installApi(lua: Globals) {
        lua.set("emit", fn { args ->
            val e = env
            if (e != null) {
                e.emit(
                    EventKind.fromName(args.optjstring(1, "step")),
                    args.optdouble(2, 0.5).toFloat(),
                    args.optdouble(3, 0.0).toFloat(),
                    args.optdouble(4, 0.7).toFloat()
                )
            }
            LuaValue.NIL
        })
        lua.set("param", fn { args ->
            val e = env ?: return@fn LuaValue.valueOf(0.0)
            LuaValue.valueOf(e.params.f(args.optjstring(1, ""), args.optdouble(2, 0.0).toFloat()).toDouble())
        })
        lua.set("choice", fn { args ->
            val e = env ?: return@fn LuaValue.valueOf("")
            LuaValue.valueOf(e.params.choice(args.optjstring(1, "")))
        })
        lua.set("rng", fn { args ->
            val e = env ?: return@fn LuaValue.valueOf(0.0)
            val lo = args.optdouble(1, Double.NaN)
            val hi = args.optdouble(2, Double.NaN)
            when {
                lo.isNaN() -> LuaValue.valueOf(e.rng.nextFloat().toDouble())
                hi.isNaN() -> LuaValue.valueOf(e.rng.range(0f, lo.toFloat()).toDouble())
                else -> LuaValue.valueOf(e.rng.range(lo.toFloat(), hi.toFloat()).toDouble())
            }
        })
        lua.set("rng_int", fn { args ->
            val e = env ?: return@fn LuaValue.valueOf(0)
            LuaValue.valueOf(e.rng.int(args.optint(1, 0), args.optint(2, 2)))
        })
        lua.set("aspect", fn { _ ->
            LuaValue.valueOf((env?.halfWidth ?: 0.5f).toDouble())
        })
        lua.set("sim_time", fn { _ -> LuaValue.valueOf((env?.time ?: 0f).toDouble()) })
        lua.set("law", fn { args ->
            val l = env?.laws ?: return@fn LuaValue.valueOf(0.0)
            val v = when (args.optjstring(1, "")) {
                "gravity" -> l.gravity
                "restitution", "bounciness" -> l.restitution
                "friction" -> l.friction
                "time_dir" -> l.timeDirection
                "time_scale" -> l.timeScale
                "sticky" -> if (l.sticky) 1f else 0f
                "pass_through" -> if (l.passThrough) 1f else 0f
                "electric" -> l.electricStrength
                "wrap" -> if (l.space != app.hypnotica.core.laws.Space.BOX) 1f else 0f
                "mass_sign" -> l.massSign
                "g_constant" -> l.gravityConstant
                "falloff" -> l.falloff
                "light_speed" -> l.lightSpeed
                else -> 0f
            }
            LuaValue.valueOf(v.toDouble())
        })

        // Drawing
        lua.set("disc", fn { a ->
            g?.disc(a.f(1), a.f(2), a.f(3), colour(a, 4), a.optdouble(5, 1.0).toFloat()); LuaValue.NIL
        })
        lua.set("rect", fn { a ->
            g?.rect(a.f(1), a.f(2), a.f(3), a.f(4), colour(a, 5), a.optdouble(6, 0.0).toFloat(),
                a.optdouble(7, 1.0).toFloat()); LuaValue.NIL
        })
        lua.set("bar", fn { a ->
            g?.bar(a.f(1), a.f(2), a.f(3), a.f(4), colour(a, 5), a.optdouble(6, 1.0).toFloat()); LuaValue.NIL
        })
        lua.set("line", fn { a ->
            g?.line(a.f(1), a.f(2), a.f(3), a.f(4), a.optdouble(5, 0.004).toFloat(), colour(a, 6),
                a.optdouble(7, 1.0).toFloat()); LuaValue.NIL
        })
        lua.set("ring", fn { a ->
            g?.ring(a.f(1), a.f(2), a.f(3), a.optdouble(4, 0.004).toFloat(), colour(a, 5),
                a.optdouble(6, 0.0).toFloat(), a.optdouble(7, 0.0).toFloat(),
                a.optdouble(8, 1.0).toFloat()); LuaValue.NIL
        })
        lua.set("glow", fn { a ->
            g?.glow(a.f(1), a.f(2), a.f(3), colour(a, 4), a.optdouble(5, 0.5).toFloat()); LuaValue.NIL
        })
        lua.set("point", fn { a ->
            g?.point(a.f(1), a.f(2), a.optdouble(3, 0.006).toFloat(), colour(a, 4)); LuaValue.NIL
        })
        lua.set("flash", fn { a -> g?.flash(a.optdouble(1, 0.3).toFloat()); LuaValue.NIL })
        lua.set("palette", fn { a ->
            val e = env ?: return@fn LuaValue.valueOf(0)
            LuaValue.valueOf(e.palette.at(a.optdouble(1, 0.5).toFloat()))
        })
        lua.set("hsv", fn { a ->
            LuaValue.valueOf(
                app.hypnotica.core.render.Palette.hsv(
                    a.optdouble(1, 0.0).toFloat(),
                    a.optdouble(2, 0.8).toFloat(),
                    a.optdouble(3, 1.0).toFloat()
                )
            )
        })
    }

    /** A colour argument: an int from palette()/hsv(), or a palette position. */
    private fun colour(args: Varargs, index: Int): Int {
        val v = args.arg(index)
        if (v.isnil()) return env?.palette?.at(0.5f) ?: -1
        val d = v.optdouble(0.5)
        return if (d > 1.0 || d < 0.0) d.toInt() else (env?.palette?.at(d.toFloat()) ?: -1)
    }

    private fun Varargs.f(index: Int): Float = this.optdouble(index, 0.0).toFloat()

    private inline fun fn(crossinline body: (Varargs) -> LuaValue): VarArgFunction =
        object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs = body(args)
        }

    companion object {
        const val STEP_BUDGET_MS = 8f

        /** A frame is 16 ms; drawing may have half of it before it counts. */
        const val DRAW_BUDGET_MS = 8f

        /** setup() builds the world once, so it may take longer — but not forever. */
        const val SETUP_BUDGET_MS = 600f
        const val OVER_BUDGET_LIMIT = 90
    }
}
