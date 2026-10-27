package app.hypnotica

import app.hypnotica.core.laws.Expr
import app.hypnotica.core.laws.Laws
import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.ParamValues
import app.hypnotica.core.render.Palette
import app.hypnotica.core.safety.FlashLimiter
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.EventQueue
import app.hypnotica.core.sim.Rng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RngTest {

    @Test
    fun sameSeedGivesSameStream() {
        val a = Rng(12345)
        val b = Rng(12345)
        repeat(1000) { assertEquals(a.nextInt(), b.nextInt()) }
    }

    @Test
    fun differentSeedsDiverge() {
        val a = Rng(1)
        val b = Rng(2)
        var same = 0
        repeat(200) { if (a.nextInt() == b.nextInt()) same++ }
        assertTrue("streams should not match", same < 5)
    }

    @Test
    fun floatsStayInRange() {
        val r = Rng(99)
        repeat(20000) {
            val v = r.nextFloat()
            assertTrue("nextFloat out of range: $v", v >= 0f && v < 1f)
        }
    }

    @Test
    fun intRangeIsRespected() {
        val r = Rng(5)
        repeat(10000) {
            val v = r.int(3, 9)
            assertTrue("int out of range: $v", v in 3..8)
        }
        assertEquals(4, r.int(4, 4))
    }

    @Test
    fun shuffleKeepsEveryElement() {
        val r = Rng(8)
        val a = IntArray(500) { it }
        r.shuffle(a)
        assertEquals(500, a.toHashSet().size)
    }
}

class ExprTest {

    private fun eval(src: String, vars: Map<String, Float> = emptyMap()): Float =
        Expr.compile(src).eval { name -> vars[name] ?: Float.NaN }

    @Test
    fun arithmeticAndPrecedence() {
        assertEquals(7f, eval("1 + 2 * 3"), 1e-5f)
        assertEquals(9f, eval("(1 + 2) * 3"), 1e-5f)
        assertEquals(8f, eval("2 ^ 3"), 1e-5f)
        assertEquals(512f, eval("2 ^ 3 ^ 2"), 1e-3f) // right associative
        assertEquals(-5f, eval("-5"), 1e-5f)
        assertEquals(1f, eval("7 % 3"), 1e-5f)
    }

    @Test
    fun functionsAndConstants() {
        assertEquals(0f, eval("sin(0)"), 1e-5f)
        assertEquals(1f, eval("cos(0)"), 1e-5f)
        assertEquals(3f, eval("sqrt(9)"), 1e-5f)
        assertEquals(2f, eval("min(2, 5)"), 1e-5f)
        assertEquals(5f, eval("max(2, 5)"), 1e-5f)
        assertEquals(4f, eval("clamp(10, 1, 4)"), 1e-5f)
        assertEquals(Math.PI.toFloat(), eval("pi"), 1e-5f)
    }

    @Test
    fun variablesResolve() {
        assertEquals(5f, eval("t + 2", mapOf("t" to 3f)), 1e-5f)
        // An unknown name is 0, so a half-typed formula cannot crash a sim.
        assertEquals(0f, eval("nope"), 1e-5f)
    }

    @Test
    fun divisionByZeroIsSafe() {
        assertEquals(0f, eval("1 / 0"), 1e-5f)
        assertEquals(0f, eval("1 % 0"), 1e-5f)
    }

    @Test
    fun badInputDoesNotThrowFromCompileOrNull() {
        assertNull(Expr.compileOrNull("1 +"))
        assertNull(Expr.compileOrNull("(1"))
        assertNull(Expr.compileOrNull("frobnicate(2)"))
        assertNotNull(Expr.compileOrNull("1 + 1"))
    }

    @Test
    fun timeVaryingExpressionDrivesAParameter() {
        val schema = listOf(Param.float("speed", "Speed", 0f, 4f, 1f, card = Card.MOTION))
        val values = ParamValues(schema)
        assertTrue(values.setExpression("speed", "1 + sin(t)"))
        values.tick(0f)
        assertEquals(1f, values.f("speed"), 1e-4f)
        values.tick((Math.PI / 2).toFloat())
        assertEquals(2f, values.f("speed"), 1e-3f)
    }
}

class LawsTest {

    @Test
    fun inverseSquareByDefault() {
        val l = Laws.OUR_UNIVERSE
        val near = l.pairForce(1f, 1f, 1f)
        val far = l.pairForce(2f, 1f, 1f)
        assertEquals(4f, near / far, 1e-3f)
    }

    @Test
    fun customForceLawOverridesTheDefault() {
        val l = Laws(forceExpr = "m1 * m2 / r")
        assertEquals(0.5f, l.pairForce(2f, 1f, 1f), 1e-4f)
    }

    @Test
    fun forceStaysFiniteAtZeroDistance() {
        val l = Laws.OUR_UNIVERSE
        val f = l.pairForce(0f, 1f, 1f)
        assertTrue("force must stay finite, was $f", f.isFinite())
    }

    @Test
    fun everyPresetHasAUniqueName() {
        val names = Laws.PRESETS.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertEquals("Moon", Laws.preset("Moon").name)
        assertEquals("Our universe", Laws.preset("nonsense").name)
    }
}

class ParamsTest {

    private val schema = listOf(
        Param.int("n", "Size", 8, 1000, 200, card = Card.DATA),
        Param.bool("on", "On", true, card = Card.MOTION),
        Param.choice("view", "View", listOf("Bars", "Dots", "Spiral"), 1, card = Card.LOOK)
    )

    @Test
    fun defaultsApply() {
        val v = ParamValues(schema)
        assertEquals(200, v.i("n"))
        assertTrue(v.b("on"))
        assertEquals("Dots", v.choice("view"))
    }

    @Test
    fun valuesAreClampedToTheirRange() {
        val v = ParamValues(schema)
        v.set("n", 99999f)
        assertEquals(1000, v.i("n"))
        v.set("n", 0f)
        assertEquals(8, v.i("n"))
    }

    @Test
    fun macrosMoveSeveralParametersAtOnce() {
        val v = ParamValues(schema)
        val macro = app.hypnotica.core.recipe.Macro(
            "size", "Size", Card.DATA, mapOf("n" to 10f..500f)
        )
        v.applyMacro(macro, 0f)
        assertEquals(10, v.i("n"))
        v.applyMacro(macro, 1f)
        assertEquals(500, v.i("n"))
    }

    @Test
    fun snapshotAndRestoreRoundTrip() {
        val v = ParamValues(schema)
        v.set("n", 321f)
        val snap = v.snapshot()
        v.set("n", 12f)
        v.restore(snap)
        assertEquals(321, v.i("n"))
    }
}

class EventQueueTest {

    @Test
    fun eventsComeBackInOrder() {
        val q = EventQueue(16)
        q.emit(EventKind.COMPARE, 0.1f)
        q.emit(EventKind.SWAP, 0.2f)
        val seen = ArrayList<Pair<EventKind, Float>>()
        q.drain(10) { k, v, _, _ -> seen.add(k to v) }
        assertEquals(2, seen.size)
        assertEquals(EventKind.COMPARE, seen[0].first)
        assertEquals(EventKind.SWAP, seen[1].first)
    }

    @Test
    fun aFullQueueDropsInsteadOfBlocking() {
        val q = EventQueue(8)
        repeat(100) { q.emit(EventKind.HIT, 0.5f) }
        assertTrue("should have dropped events", q.dropped > 0)
        var n = 0
        q.drain(100) { _, _, _, _ -> n++ }
        assertTrue("queue should still deliver what it kept", n in 1..8)
    }
}

class SafetyTest {

    @Test
    fun largeFlashesAreCappedAtThreePerSecond() {
        val limiter = FlashLimiter()
        limiter.reset()
        var full = 0
        // Ten big flashes inside a tenth of a second.
        repeat(10) {
            val allowed = limiter.allow(1f, 0.01f)
            if (allowed > 0.5f) full++
        }
        assertTrue("expected at most 3 full flashes, got $full", full <= 3)
    }

    @Test
    fun smallGlowsAreNotRateLimited() {
        val limiter = FlashLimiter()
        limiter.reset()
        repeat(50) {
            val allowed = limiter.allow(0.05f, 0.001f)
            assertEquals(0.05f, allowed, 1e-5f)
        }
    }

    @Test
    fun reduceFlashingSoftensEverything() {
        val limiter = FlashLimiter()
        limiter.reset()
        limiter.reduceFlashing = true
        assertTrue(limiter.allow(1f, 0.5f) <= 0.3f)
    }
}

class PaletteTest {

    @Test
    fun everyPaletteReturnsOpaqueColours() {
        for (p in Palette.ALL) {
            for (t in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val c = p.at(t)
                val alpha = (c ushr 24) and 0xFF
                assertEquals("${p.name} lost its alpha at $t", 255, alpha)
            }
        }
    }

    @Test
    fun outOfRangePositionsAreClamped() {
        for (p in Palette.ALL) {
            assertEquals(p.at(0f), p.at(-5f))
            assertEquals(p.at(1f), p.at(5f))
        }
    }

    @Test
    fun namesAreUnique() {
        assertEquals(Palette.NAMES.size, Palette.NAMES.toSet().size)
        assertFalse(Palette.NAMES.isEmpty())
    }
}
