package app.hypnotica

import app.hypnotica.core.recipe.Macro
import app.hypnotica.core.recipe.Macros
import app.hypnotica.core.recipe.ParamType
import app.hypnotica.core.recipe.ParamValues
import app.hypnotica.sims.SimRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Simple mode's promise, checked against every sim.
 *
 * The contract is narrow and worth stating: the middle of a macro slider is
 * exactly where the sim already is, every position maps to a legal value, the
 * sweep can express the values a person wants, and a macro that names the
 * subject of the sim moves the subject rather than a drawing detail.
 */
class MacroTest {

    @Test
    fun everySimHasAtLeastOneMacro() {
        val bare = SimRegistry.all.filter { Macros.forDef(it).isEmpty() }
        assertTrue("no simple control at all for: ${bare.map { it.id }}", bare.isEmpty())
    }

    @Test
    fun theMiddleOfEveryMacroIsWhereTheSimShips() {
        for (def in SimRegistry.all) {
            for (macro in Macros.forDef(def)) {
                for ((key, range) in macro.targets) {
                    val mid = macro.mids[key] ?: continue
                    val got = Macro.valueAt(range, mid, 0.5f, macro.logs.contains(key))
                    val scale = maxOf(1e-3f, abs(mid))
                    assertTrue(
                        "${def.id}/${macro.key}: $key sits at $got in the middle, not $mid",
                        abs(got - mid) <= scale * 0.02f
                    )
                }
            }
        }
    }

    @Test
    fun nothingMovesUntilTheSliderIsTouched() {
        for (def in SimRegistry.all) {
            val values = ParamValues(def.params)
            for (macro in Macros.forDef(def)) {
                val at = Macros.positionFor(macro, values)
                val before = macro.targets.keys.associateWith { values.f(it, 0f) }
                values.applyMacro(macro, at)
                for ((key, was) in before) {
                    val now = values.f(key, 0f)
                    val scale = maxOf(1e-3f, abs(was))
                    assertTrue(
                        "${def.id}/${macro.key}: reading the slider back moved $key " +
                            "from $was to $now",
                        abs(now - was) <= scale * 0.03f
                    )
                }
            }
        }
    }

    @Test
    fun everyPositionIsALegalValue() {
        for (def in SimRegistry.all) {
            val byKey = def.params.associateBy { it.key }
            for (macro in Macros.forDef(def)) {
                for (step in 0..20) {
                    val t = step / 20f
                    for ((key, range) in macro.targets) {
                        val p = byKey[key] ?: continue
                        val v = Macro.valueAt(range, macro.mids[key], t, macro.logs.contains(key))
                        assertTrue("${def.id}/${macro.key}: $key is $v at t=$t", v.isFinite())
                        assertTrue(
                            "${def.id}/${macro.key}: $key reaches $v at t=$t, " +
                                "outside ${p.min}..${p.max}",
                            v >= p.min - 1e-3f && v <= p.max + 1e-3f
                        )
                    }
                }
            }
        }
    }

    @Test
    fun aPositionReadBackGivesTheSamePosition() {
        for (def in SimRegistry.all) {
            for (macro in Macros.forDef(def)) {
                for ((key, range) in macro.targets) {
                    if (range.endInclusive - range.start <= 0f) continue
                    val log = macro.logs.contains(key)
                    val mid = macro.mids[key]
                    for (step in 0..10) {
                        val t = step / 10f
                        // A target already sitting at the end of its range has
                        // nowhere to go in that half, so every position there
                        // means the same value and cannot be told apart.
                        if (mid != null) {
                            if (t < 0.5f && mid <= minOf(range.start, range.endInclusive)) continue
                            if (t > 0.5f && mid >= maxOf(range.start, range.endInclusive)) continue
                        }
                        val v = Macro.valueAt(range, mid, t, log)
                        val back = Macro.positionOf(range, mid, v, log)
                        assertTrue(
                            "${def.id}/${macro.key}: $key at t=$t came back as $back",
                            abs(back - t) <= 0.02f
                        )
                    }
                }
            }
        }
    }

    /**
     * A logarithmic count has to be reachable across its decades. On a linear
     * sweep a count of 1..100000 put every interesting value in the first few
     * percent of the travel, which made the simple control the one that could
     * not express what the person wanted.
     */
    @Test
    fun aLogarithmicTargetIsReachableAcrossItsDecades() {
        for (def in SimRegistry.all) {
            val byKey = def.params.associateBy { it.key }
            for (macro in Macros.forDef(def)) {
                for ((key, range) in macro.targets) {
                    val p = byKey[key] ?: continue
                    if (!p.logScale || p.max < p.min * 100f) continue
                    assertTrue(
                        "${def.id}/${macro.key}: $key is logarithmic but its macro is not",
                        macro.logs.contains(key)
                    )
                    // Measured against the straight line it replaces, in the
                    // lower quarter, which is where a linear sweep over three
                    // decades puts nothing a person wants. Only worth asserting
                    // where that half spans a decade of its own: a mid-anchored
                    // half covering five times its floor has little to gain.
                    val mid = macro.mids[key]
                    val lo = minOf(range.start, range.endInclusive)
                    val halfTop = mid ?: maxOf(range.start, range.endInclusive)
                    if (lo <= 0f || halfTop < lo * 10f) continue
                    val logged = Macro.valueAt(range, mid, 0.25f, true)
                    val straight = Macro.valueAt(range, mid, 0.25f, false)
                    assertTrue(
                        "${def.id}/${macro.key}: $key is $logged a quarter along, " +
                            "no better than the straight line's $straight",
                        logged < straight * 0.75f
                    )
                }
            }
        }
    }

    /** "How much" should move the subject, not the trail buffer. */
    @Test
    fun howMuchMovesTheSubject() {
        val wrong = ArrayList<String>()
        for (def in SimRegistry.all) {
            val macro = Macros.forDef(def).firstOrNull { it.key == "derived-size" } ?: continue
            val subject = macro.targets.keys.firstOrNull() ?: continue
            if (subject in setOf("trail", "detail", "resolution", "maxbars", "addend", "teeth")) {
                // Only a problem when the sim had something better to offer.
                val better = def.params.any {
                    it.type == ParamType.INT && !it.advancedOnly &&
                        it.key in setOf("n", "count", "bodies", "nodes", "cells", "grid", "bits", "gears")
                }
                if (better) wrong.add("${def.id} -> $subject")
            }
        }
        assertTrue("How much moves a drawing detail on: $wrong", wrong.isEmpty())
    }

    /** Both axes of a grid, or the result is a ribbon. */
    @Test
    fun aGridMacroMovesBothAxes() {
        val pairs = listOf("width" to "height", "rows" to "cols", "gridx" to "gridy")
        for (def in SimRegistry.all) {
            val macro = Macros.forDef(def).firstOrNull { it.key == "derived-size" } ?: continue
            for ((a, b) in pairs) {
                val hasBoth = def.params.any { it.key == a && !it.advancedOnly } &&
                    def.params.any { it.key == b && !it.advancedOnly }
                if (!hasBoth) continue
                val touches = macro.targets.keys.contains(a) || macro.targets.keys.contains(b)
                if (!touches) continue
                assertTrue(
                    "${def.id}: How much moves only one of $a/$b",
                    macro.targets.keys.contains(a) && macro.targets.keys.contains(b)
                )
            }
        }
    }

    /** A macro drag must win over a formula rather than being quietly overruled. */
    @Test
    fun aMacroDragReplacesAnAttachedFormula() {
        val def = SimRegistry.all.first { d ->
            Macros.forDef(d).any { m -> m.targets.keys.any { k ->
                d.params.any { it.key == k && it.type == ParamType.FLOAT }
            } }
        }
        val macro = Macros.forDef(def).first { m ->
            m.targets.keys.any { k -> def.params.any { it.key == k && it.type == ParamType.FLOAT } }
        }
        val key = macro.targets.keys.first { k ->
            def.params.any { it.key == k && it.type == ParamType.FLOAT }
        }
        val values = ParamValues(def.params)
        assertTrue("could not attach a formula to $key", values.setExpression(key, "0.5"))
        values.applyMacro(macro, 0.9f)
        values.tick(1f)
        val expected = Macro.valueAt(
            macro.targets.getValue(key), macro.mids[key], 0.9f, macro.logs.contains(key)
        )
        val got = values.f(key, Float.NaN)
        assertTrue(
            "the formula overwrote the macro: $key is $got, not $expected",
            abs(got - expected) <= maxOf(1e-3f, abs(expected) * 0.01f)
        )
    }
}
