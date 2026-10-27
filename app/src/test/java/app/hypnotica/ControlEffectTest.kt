package app.hypnotica

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.ParamType
import app.hypnotica.core.render.G
import app.hypnotica.core.sim.SimCore
import app.hypnotica.sims.SimRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every control changes the picture.
 *
 * The user's report was "not all buttons and sliders work", and it was right:
 * eighty-eight values across the sims were copied into a field in setup() and
 * never looked at again, so the slider moved, the number changed and the
 * picture did not. Whole cards of them — boids' entire Motion card, the sorting
 * sims' Algorithm chip, Mandelbrot's Resolution and Detail — and the Chaos and
 * Energy macros that Simple mode leans on hardest were inert for the same
 * reason.
 *
 * The fix was not a list: ParamValues records which keys a sim reads while it
 * is running, and a value nothing reads live is applied by rebuilding the run,
 * which is what the Data card has always done. This is the test that says the
 * rebuild actually shows: for each such value, set it low, draw; set it high,
 * draw; the frames must differ. Nothing here trusts the mechanism's own
 * bookkeeping — it compares what would be on screen.
 */
class ControlEffectTest {

    /** Long enough that a trail has something to draw; shorter runs lie. */
    private val frames = 250

    /** Everything a frame would put on screen, as one number. */
    private fun digest(core: SimCore): Long {
        val g = G()
        g.reset()
        core.sim.draw(core.env, g, 1f)
        var h = 1125899906842597L
        h = h * 31 + g.instanceCount
        h = h * 31 + g.pointCount
        h = h * 31 + g.glowCount
        for (i in 0 until g.instanceCount * G.STRIDE) h = h * 31 + g.instances[i].toRawBits()
        for (i in 0 until g.pointCount * 3) h = h * 31 + g.pointPos[i].toRawBits()
        for (i in 0 until g.pointCount * 4) h = h * 31 + g.pointColor[i]
        return h
    }

    /** A fresh run at one value of one parameter. Fresh, so nothing carries over. */
    private fun frameAt(id: String, key: String, value: Float): Long {
        val def = SimRegistry.all.first { it.id == id }
        val core = SimCore(def)
        core.setAspect(0.56f)
        core.params.set(key, value)
        core.restart()
        repeat(frames) { core.advance(1f / 60f) }
        return digest(core)
    }

    /** The values a sim only reads while being built, as it reports them itself. */
    private fun setupOnly(id: String): List<Param> {
        val def = SimRegistry.all.first { it.id == id }
        val core = SimCore(def)
        core.setAspect(0.56f)
        repeat(40) { core.advance(1f / 60f) }
        assertTrue("$id never settled, so it cannot say what it reads", core.params.readsSettled)
        return def.params.filter {
            it.card != Card.DATA &&
                it.type != ParamType.EXPR &&
                it.mutedBy == null &&
                it.min < it.max &&
                !core.params.readLive(it.key)
        }
    }

    /**
     * A control that says it is inert really is, and really is not otherwise.
     *
     * `mutedBy` is a promise the drawer makes out loud: it dims the control and
     * prints why. Two things can go wrong with it, and both are worse than
     * having no mechanism. If the owner does not actually take the value over,
     * the app dims a working control and tells the person a lie about their own
     * settings. If the value is dead at other owner values too, the dimming is
     * cover for a control that never worked.
     *
     * Optics' Angle was the first: swept every step while "Sweep the angle" is
     * on, which it is by default. The Equation sim's Start x and Start y were
     * the second, and the reason mutedBy stopped being a boolean — the curve
     * mode it ships in computes both coordinates from t on the first step, so
     * the two sliders drew the same picture at all nine positions of a
     * four-unit range. They steer both other modes.
     */
    @Test
    fun aMutedControlIsInertWhereItSaysAndLiveElsewhere() {
        val wrong = ArrayList<String>()
        var checked = 0
        for (def in SimRegistry.all) {
            for (p in def.params) {
                val ownerKey = p.mutedBy ?: continue
                val owner = def.params.firstOrNull { it.key == ownerKey }
                if (owner == null) {
                    wrong.add("${def.id}/${p.key} is muted by \"$ownerKey\", which it does not have")
                    continue
                }
                checked++
                // Inert at every value it names.
                for (muted in p.mutedAt) {
                    val lo = frameWith(def.id, ownerKey, muted.toFloat(), p.key, p.min)
                    val hi = frameWith(def.id, ownerKey, muted.toFloat(), p.key, p.max)
                    if (lo != hi) {
                        wrong.add(
                            "${def.id}/${p.key} says it is inert with $ownerKey=$muted and is not, " +
                                "so the drawer dims a control that works"
                        )
                    }
                }
                // And live at some value it does not.
                val free = (Math.round(owner.min)..Math.round(owner.max))
                    .firstOrNull { it !in p.mutedAt }
                if (free == null) {
                    wrong.add("${def.id}/${p.key} is muted at every value of $ownerKey, so it is just dead")
                    continue
                }
                val lo = frameWith(def.id, ownerKey, free.toFloat(), p.key, p.min)
                val hi = frameWith(def.id, ownerKey, free.toFloat(), p.key, p.max)
                if (lo == hi) {
                    wrong.add(
                        "${def.id}/${p.key} draws the same frame at both ends with " +
                            "$ownerKey=$free too, so the dimming is covering for a dead control"
                    )
                }
            }
        }
        assertTrue("no muted controls were checked, so this proves nothing", checked > 0)
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    /** A fresh run with the owner held at one value and the subject at another. */
    private fun frameWith(
        id: String, ownerKey: String, ownerValue: Float, key: String, value: Float
    ): Long {
        val def = SimRegistry.all.first { it.id == id }
        val core = SimCore(def)
        core.setAspect(0.56f)
        core.params.set(ownerKey, ownerValue)
        core.params.set(key, value)
        core.restart()
        repeat(frames) { core.advance(1f / 60f) }
        return digest(core)
    }

    @Test
    fun everySetupOnlyControlChangesTheFrameOnceTheRunIsRebuilt() {
        val inert = ArrayList<String>()
        var checked = 0
        for (def in SimRegistry.all) {
            for (p in setupOnly(def.id)) {
                checked++
                if (frameAt(def.id, p.key, p.min) == frameAt(def.id, p.key, p.max)) {
                    inert.add("${def.id}/${p.key} (${p.label})")
                }
            }
        }
        assertTrue("nothing was checked, so this test proves nothing", checked > 0)
        assertTrue(
            "these controls still draw an identical frame at both ends of their " +
                "range, so moving them does nothing: $inert",
            inert.isEmpty()
        )
    }

    /**
     * A value the sim reads live steers the run without restarting it.
     *
     * This is the other half of the contract, and the better half: a slider
     * that rebuilds the run does apply, but it throws every bird, walker and
     * particle back to its starting place, so the thing worth watching — the
     * flock reorganising, the walkers overshooting — never happens. Boids' four
     * weights, gradient descent's rate and momentum and the particle field's
     * flow, swirl and drag were all cached at setup for that reason, and are
     * read each step now.
     *
     * Two runs from the same seed, identical up to frame 60, then one of them
     * gets the new value. Comparing a run against its own past would call a
     * switch that freezes the picture "no effect" — freezing is precisely what
     * makes the later frame match the earlier one — and two of these do exactly
     * that.
     */
    @Test
    fun everyLiveValueSteersTheRunWithoutRestartingIt() {
        val inert = ArrayList<String>()
        var checked = 0
        for (def in SimRegistry.all) {
            for (p in def.params) {
                if (p.card == Card.DATA || p.type == ParamType.EXPR) continue
                if (p.mutedBy != null || p.min >= p.max) continue

                val changed = SimCore(def)
                val control = SimCore(def)
                changed.setAspect(0.56f)
                control.setAspect(0.56f)
                repeat(60) { changed.advance(1f / 60f) }
                repeat(60) { control.advance(1f / 60f) }
                if (!changed.params.readLive(p.key)) continue
                checked++

                val now = changed.params.f(p.key)
                val other = if (now > (p.min + p.max) / 2f) p.min else p.max
                changed.params.set(p.key, other)
                repeat(60) { changed.advance(1f / 60f) }
                repeat(60) { control.advance(1f / 60f) }
                if (digest(changed) == digest(control)) {
                    inert.add("${def.id}/${p.key} (${p.label})")
                }
            }
        }
        assertTrue("nothing was checked, so this test proves nothing", checked > 0)
        assertTrue(
            "these values are read live, so changing them should change the run " +
                "with no rebuild, and did not: $inert",
            inert.isEmpty()
        )
    }

    /**
     * A control taken over by another setting has to say which one, so the
     * person can find the switch instead of concluding the slider is broken.
     */
    @Test
    fun everyMutedControlNamesAnOwnerThatExists() {
        for (def in SimRegistry.all) {
            for (p in def.params) {
                val owner = p.mutedBy ?: continue
                val target = def.params.firstOrNull { it.key == owner }
                assertTrue(
                    "${def.id}/${p.key} says it is set by '$owner', which it does not declare",
                    target != null
                )
                // A switch takes a value over; a choice can leave it
                // unused in one of its modes, which is what the Equation sim's
                // Start x and Start y needed. Nothing else can own a value:
                // "muted above 0.7 on a slider" would be a rule no one reading
                // the drawer could follow.
                assertTrue(
                    "${def.id}/${p.key} is set by '$owner', which is neither a switch nor a choice",
                    target!!.type == ParamType.BOOL || target.type == ParamType.CHOICE
                )
                assertTrue(
                    "${def.id}/${p.key} names no owner values it is muted at",
                    p.mutedAt.isNotEmpty()
                )
            }
        }
    }
}
