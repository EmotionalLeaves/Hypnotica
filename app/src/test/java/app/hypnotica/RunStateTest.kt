package app.hypnotica

import app.hypnotica.core.laws.Laws
import app.hypnotica.core.laws.Space
import app.hypnotica.core.recipe.ParamType
import app.hypnotica.core.recipe.ParamValues
import app.hypnotica.core.recipe.RunParse
import app.hypnotica.core.recipe.applyRunParams
import app.hypnotica.core.recipe.runParamsJson
import app.hypnotica.core.recipe.lawsJson
import app.hypnotica.core.recipe.parseRun
import app.hypnotica.core.recipe.runLaws
import app.hypnotica.sims.SimRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * A run has to read back as the run that was written.
 *
 * The Code tab prints the current run as JSON under the words "everything
 * needed to replay it". That was true of the text and false of the app — there
 * was no way to copy it out and no way to paste one in — and it was false of
 * the text too for a universe: only the name was written down, and there is no
 * preset called Custom, so every hand-edited universe replayed as our universe
 * without saying so.
 *
 * These tests hold the writer and the reader to each other. They live in one
 * file for the same reason.
 */
class RunStateTest {

    /** Exactly what AppState.stateJson writes, down to the whitespace. */
    private val printed = """
        {
          "recipe": "art/polyrhythm",
          "seed": 1761524873918292,
          "params": {
            "wheels": 7,
            "speed": 0.4200,
            "glow": "sin(t * 0.5) * 0.3 + 0.5",
            "shape": "ring",
            "mirror": true,
            "fade": 0.0000
          },
          "laws": "Moon",
          "look": { "palette": "Ember", "trails": 0.96 },
          "sound": { "preset": "Music box", "scale": "PENTATONIC" }
        }
    """.trimIndent()

    @Test
    fun `reads back what the panel prints`() {
        val run = (parseRun(printed) as RunParse.Ok).run
        assertEquals("art/polyrhythm", run.recipe)
        assertEquals(1761524873918292L, run.seed)
        // An INT parameter should not have to cope with "7.0".
        assertEquals("7", run.params["wheels"])
        assertEquals("0.42", run.params["speed"])
        assertEquals("sin(t * 0.5) * 0.3 + 0.5", run.params["glow"])
        assertEquals("ring", run.params["shape"])
        assertEquals("true", run.params["mirror"])
        assertEquals("0", run.params["fade"])
        assertEquals("Moon", run.laws)
        assertEquals("Ember", run.palette)
        assertEquals(0.96f, run.trails!!, 1e-6f)
        assertEquals("Music box", run.soundPreset)
        assertEquals("PENTATONIC", run.scale)
    }

    @Test
    fun `a seed comes back exactly, however large`() {
        // A double holds 53 bits; a seed is 64. Read as a double, a seed this
        // size came back as a neighbouring number — a different run entirely.
        for (seed in longArrayOf(Long.MAX_VALUE, Long.MIN_VALUE, 9_007_199_254_740_993L, -1L, 0L)) {
            val run = (parseRun("""{"recipe":"x","seed":$seed}""") as RunParse.Ok).run
            assertEquals("seed $seed", seed, run.seed)
        }
    }

    @Test
    fun `a run with nothing but a sim in it still applies`() {
        val run = (parseRun("""{"recipe":"physics/pendulum-wave"}""") as RunParse.Ok).run
        assertNull(run.seed)
        assertTrue(run.params.isEmpty())
        assertNull(run.palette)
        assertNull(runLaws(run))
    }

    @Test
    fun `every bad paste comes back as a sentence`() {
        val bad = listOf(
            "", "   ", "hello", "{", "}", "[1,2,3]", "null", "42",
            """{"seed": 1}""", """{"recipe": ""}""", """{"recipe": 7}""",
            """{"recipe":"x",}""", """{"recipe":"x"} junk""",
            """{"recipe":"x","params":{"a":}}""", """{"recipe":"x","params":{a:1}}""",
            """{"recipe":"x","laws":"unclosed""", """{"recipe":"x","params":{"a":0x1}}""",
            """{"recipe":"x","laws":"\q"}""", """{"recipe":"x","laws":"\u00"}""",
            "{".repeat(200), "\u0000\u0001"
        )
        for (text in bad) {
            val r = parseRun(text)
            assertTrue("accepted <$text>", r is RunParse.Bad)
            val why = (r as RunParse.Bad).why
            // It is shown to whoever pasted the thing, so it has to read as
            // something a person wrote.
            assertTrue("unhelpful for <$text>: $why", why.length > 8 && why.endsWith("."))
        }
    }

    @Test
    fun `a preset universe writes as its own name`() {
        val moon = Laws.PRESETS.first { it.name == "Moon" }
        assertEquals("\"Moon\"", lawsJson(moon))
        // But a hand-edited Moon is not Moon, and must not write as one.
        assertTrue(lawsJson(moon.copy(friction = 0.3f)).startsWith("{"))
    }

    @Test
    fun `every shipped universe survives the round trip`() {
        for (preset in Laws.PRESETS) assertRoundTrips(preset)
    }

    @Test
    fun `a custom universe survives the round trip`() {
        val edited = Laws.OUR_UNIVERSE.copy(
            gravity = 2.4f, restitution = 1.2f, conserveEnergy = false, name = "Custom"
        )
        assertRoundTrips(edited)
        // The case that was silently wrong: Custom in name only. Looking that
        // up as a preset gave back our universe.
        assertRoundTrips(Laws.OUR_UNIVERSE.copy(name = "Custom"))
    }

    @Test
    fun `a thousand universes at random survive the round trip`() {
        val rnd = Random(7)
        repeat(1000) { n ->
            assertRoundTrips(
                rounded(
                    Laws(
                        gravity = rnd.nextFloat() * 40f - 10f,
                        gravityDirX = rnd.nextFloat() * 2f - 1f,
                        gravityDirY = rnd.nextFloat() * 2f - 1f,
                        gravityConstant = rnd.nextFloat() * 8f,
                        falloff = rnd.nextFloat() * 4f,
                        forceExpr = if (rnd.nextBoolean()) null else "m1 * m2 / r^${rnd.nextInt(1, 4)}",
                        massSign = if (rnd.nextBoolean()) 1f else -1f,
                        restitution = rnd.nextFloat() * 1.5f,
                        friction = rnd.nextFloat(),
                        sticky = rnd.nextBoolean(),
                        passThrough = rnd.nextBoolean(),
                        conserveEnergy = rnd.nextBoolean(),
                        lightSpeed = if (rnd.nextBoolean()) 1e9f else rnd.nextFloat() * 500f,
                        timeDirection = if (rnd.nextBoolean()) 1f else -1f,
                        timeScale = rnd.nextFloat() * 3f,
                        chargeSign = if (rnd.nextBoolean()) 1f else -1f,
                        electricStrength = rnd.nextFloat() * 5f,
                        space = if (rnd.nextBoolean()) Space.BOX else Space.TORUS,
                        name = if (n % 3 == 0) "Custom" else "Mine $n"
                    )
                )
            )
        }
    }

    @Test
    fun `every sim's settings survive the round trip`() {
        val rnd = Random(11)
        var checked = 0
        for (def in SimRegistry.all) {
            val written = ParamValues(def.params)
            for (p in def.params) {
                when (p.type) {
                    // A formula field, filled with something that parses.
                    ParamType.EXPR -> written.setText(p.key, FORMULAS[rnd.nextInt(FORMULAS.size)])
                    ParamType.BOOL -> written.set(p.key, rnd.nextBoolean())
                    ParamType.CHOICE ->
                        written.set(p.key, rnd.nextInt(maxOf(1, p.choices.size)).toFloat())
                    ParamType.INT ->
                        written.set(p.key, Math.round(p.min + rnd.nextFloat() * (p.max - p.min)).toFloat())
                    ParamType.FLOAT -> written.set(p.key, p.min + rnd.nextFloat() * (p.max - p.min))
                }
            }
            // And a formula on a numeric value, which is the other thing a run
            // has to carry: stateJson prints it as a string in a number's place.
            def.params.firstOrNull { it.type == ParamType.FLOAT }?.let {
                written.setExpression(it.key, "sin(t * 0.5) * 0.3 + 0.5")
            }

            val json = """{"recipe":"${def.id}","params":{${runParamsJson(def.params, written)}
  }}"""
            val parsed = parseRun(json)
            assertTrue("${def.id} did not parse: $json", parsed is RunParse.Ok)
            val read = ParamValues(def.params)
            val unknown = applyRunParams((parsed as RunParse.Ok).run, def.params, read)
            assertTrue("${def.id} reported ${unknown.size} unknown keys: $unknown", unknown.isEmpty())

            for (p in def.params) {
                val where = "${def.id} ${p.key} (${p.type})"
                when (p.type) {
                    ParamType.EXPR ->
                        assertEquals(where, written.text(p.key), read.text(p.key))
                    ParamType.BOOL ->
                        assertEquals(where, written.b(p.key).toString(), read.b(p.key).toString())
                    ParamType.CHOICE ->
                        assertEquals(where, written.choice(p.key), read.choice(p.key))
                    ParamType.INT ->
                        assertEquals(where, written.i(p.key), read.i(p.key))
                    // Four decimal places is what the panel prints, so that is
                    // the most a round trip can promise.
                    ParamType.FLOAT -> {
                        val expr = written.expressionSource(p.key)
                        if (expr != null) assertEquals(where, expr, read.expressionSource(p.key))
                        else assertEquals(where, written.f(p.key), read.f(p.key), 2e-4f)
                    }
                }
                checked++
            }
        }
        // If the registry ever comes back empty this test would pass having
        // proven nothing.
        assertTrue("checked only $checked settings", checked > 150)
    }

    private companion object {
        val FORMULAS = arrayOf("sin(3 * t)", "cos(4 * t + 1.57)", "t * 0.5", "x0 + 1")
    }

    private fun assertRoundTrips(laws: Laws) {
        val json = """{"recipe":"x","laws":${lawsJson(laws)}}"""
        val parsed = parseRun(json)
        assertTrue("did not parse: $json", parsed is RunParse.Ok)
        val back = runLaws((parsed as RunParse.Ok).run)
        assertNotNull("came back as no universe: $json", back)
        assertEquals("changed in the round trip: $json", laws, back)
    }

    /** What four decimal places can hold, which is what the panel prints. */
    private fun rounded(l: Laws): Laws = l.copy(
        gravity = r4(l.gravity), gravityDirX = r4(l.gravityDirX), gravityDirY = r4(l.gravityDirY),
        gravityConstant = r4(l.gravityConstant), falloff = r4(l.falloff), massSign = r4(l.massSign),
        restitution = r4(l.restitution), friction = r4(l.friction), lightSpeed = r4(l.lightSpeed),
        timeDirection = r4(l.timeDirection), timeScale = r4(l.timeScale),
        chargeSign = r4(l.chargeSign), electricStrength = r4(l.electricStrength)
    )

    private fun r4(v: Float): Float =
        if (kotlin.math.abs(v) >= 1e9f) v
        else String.format(java.util.Locale.US, "%.4f", v).toFloat()
}
