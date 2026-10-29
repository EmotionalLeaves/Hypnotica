package app.hypnotica.sims.physics

import app.hypnotica.core.recipe.Card
import app.hypnotica.core.recipe.Param
import app.hypnotica.core.recipe.ParamInput
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.sim.EventKind
import app.hypnotica.core.sim.LawKnob
import app.hypnotica.core.sim.Shelf
import app.hypnotica.core.sim.Sim
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.sim.SimEnv
import app.hypnotica.core.sim.TrapFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A counterweight trebuchet throws a stone at a castle wall.
 *
 * The machine is solved as what it is: a beam on a pivot, a counterweight
 * hanging from a hinge on the short end, and a sling on the long end with the
 * stone in it, lying first in a trough on the ground. Three angles, one mass
 * matrix, solved every step (Lagrange's equations, with the trough as a
 * one-sided constraint that lets go when its push would turn into a pull).
 * The stone flies when its velocity points the release angle up from level —
 * the moment a real sling's ring slips off its pin — and then it is a
 * projectile under gravity alone.
 *
 * Auto-aim does what a siege engineer did with trial shots: the machine's
 * swing is the same every time, so it reads off where each release angle
 * would land and picks the one that strikes the wall face. Metres, kilograms,
 * seconds throughout.
 */
class TrebuchetSim : Sim {

    private var hw = 0.46f
    private var grav = 9.81
    // Machine, metres and kilograms.
    private var cwMass = 4000.0
    private val stoneMass = 60.0
    private val armMass = 300.0
    private val pivotH = 6.0
    private val longArm = 7.5
    private val shortArm = 2.0
    private val hinge = 2.0
    private val slingLen = 6.0
    private val stoneR = 0.22
    private var armI = 0.0
    private var armCm = 0.0

    // State: arm θ (short-end direction), counterweight φ (from hanging), sling ψ.
    private val q = DoubleArray(3)
    private val qd = DoubleArray(3)
    private var onTrough = true
    private var released = false
    private var releaseAngle = 45.0
    private var autoAim = true
    private var started = false

    // The stone in flight.
    private var sx = 0.0; private var sy = 0.0; private var svx = 0.0; private var svy = 0.0
    private var hitAt = -1f
    private var landed = false
    private var apex = 0.0
    private var flightT = 0.0
    private var markNext = 1
    private val trailX = FloatArray(TRAIL)
    private val trailY = FloatArray(TRAIL)
    private var trailN = 0
    private var trailTick = 0f

    // The castle.
    private var wallX = 160.0
    private var bx = FloatArray(0); private var by = FloatArray(0)
    private var bw = FloatArray(0); private var bh = FloatArray(0)
    private var bvx = FloatArray(0); private var bvy = FloatArray(0)
    private var brot = FloatArray(0); private var bvr = FloatArray(0)
    private var bstate = IntArray(0)
    private var bcol = IntArray(0); private var brow = IntArray(0)
    private var bTower = BooleanArray(0)
    private var blocks = 0
    private var knocked = 0
    private var lastThud = 0f

    // Camera: world point at screen centre and screen units per metre.
    private var camX = 0.0; private var camY = 0.0; private var camS = 0.06

    private var elapsed = 0f
    private var payoffFrame = false
    private var verdict = ""

    override fun setup(env: SimEnv) {
        val p = env.params
        hw = env.halfWidth
        grav = max(0.0, env.laws.gravity.toDouble())
        cwMass = p.f("weight", 4000f).coerceIn(1000f, 10000f).toDouble()
        autoAim = p.choiceIndex("aim") == 0
        val total = longArm + shortArm
        armI = armMass * (longArm * longArm * longArm + shortArm * shortArm * shortArm) / (3 * total)
        armCm = (shortArm - longArm) / 2
        reset()
        // One rehearsal swing, recorded, tells every release angle's landing.
        val rec = rehearse()
        val rMax = bestRange(rec)
        wallX = if (rMax > 30.0) rMax * env.rng.range(0.7f, 0.86f) else 120.0
        releaseAngle = if (autoAim) aim(rec) else p.f("release", 45f).coerceIn(10f, 80f).toDouble()
        reset()
        buildCastle(env)
        // A few still clouds along the way: something fixed in the sky for
        // the following camera to move against.
        cloudX = DoubleArray(CLOUDS) { 20.0 + (wallX - 10.0) * (it + env.rng.range(0.1f, 0.9f)) / CLOUDS }
        cloudY = DoubleArray(CLOUDS) { env.rng.range(18f, (max(40.0, wallX * 0.45)).toFloat()).toDouble() }
        cloudR = FloatArray(CLOUDS) { env.rng.range(4f, 8f) }
        elapsed = 0f
        payoffFrame = false
        hitAt = -1f
        landed = false
        apex = 0.0
        flightT = 0.0
        markNext = 1
        trailN = 0
        trailTick = 0f
        knocked = 0
        started = false
        verdict = ""
        camS = startScale()
        camX = -1.5; camY = startCamY()
    }

    private fun startScale(): Double = (if (hw < 1f) 2.0 / 30.0 else 2.0 / 26.0)

    private fun startCamY(): Double = 1.0 / startScale() - 3.5

    private fun reset() {
        // Cocked: the long end down behind, its tip 0.8 m off the ground.
        val s = (pivotH - 0.8) / longArm
        q[0] = atan2(s, sqrt(1 - s * s))
        q[1] = 0.0
        val tipX = -longArm * cos(q[0]); val tipY = pivotH - longArm * sin(q[0])
        val dy = stoneR - tipY
        val dx = sqrt(slingLen * slingLen - dy * dy)
        q[2] = atan2(dy, dx)
        qd[0] = 0.0; qd[1] = 0.0; qd[2] = 0.0
        onTrough = true
        released = false
    }

    // ---------------------------------------------------------------- dynamics

    private val mm = Array(4) { DoubleArray(5) }
    private val acc = DoubleArray(3)

    /** Generalised accelerations for state (qq, vv), into [acc]. */
    private fun accel(qq: DoubleArray, vv: DoubleArray) {
        val c = cos(qq[0]); val s = sin(qq[0])
        val cf = cos(qq[1]); val sf = sin(qq[1])
        val cp = cos(qq[2]); val sp = sin(qq[2])
        val t2 = vv[0] * vv[0]; val f2 = vv[1] * vv[1]; val p2 = vv[2] * vv[2]
        // Jacobians (x and y rows per DOF) and velocity-product terms h.
        // Counterweight: A = P + L2·u, CW = A + Lc(sin φ, −cos φ).
        val jcx0 = -shortArm * s; val jcy0 = shortArm * c
        val jcx1 = hinge * cf; val jcy1 = hinge * sf
        val hcx = -shortArm * c * t2 - hinge * sf * f2
        val hcy = -shortArm * s * t2 + hinge * cf * f2
        // Stone: B = P − L1·u, stone = B + Ls(cos ψ, sin ψ).
        val jpx0 = longArm * s; val jpy0 = -longArm * c
        val jpx2 = -slingLen * sp; val jpy2 = slingLen * cp
        val hpx = longArm * c * t2 - slingLen * cp * p2
        val hpy = longArm * s * t2 - slingLen * sp * p2
        val m = cwMass
        val ms = if (released) 0.0 else stoneMass
        val n = if (released) 2 else 3
        for (i in 0 until 4) mm[i].fill(0.0)
        // M = Σ m Jᵀ J (+ the beam's own inertia on θ).
        mm[0][0] = armI + m * (jcx0 * jcx0 + jcy0 * jcy0) + ms * (jpx0 * jpx0 + jpy0 * jpy0)
        mm[0][1] = m * (jcx0 * jcx1 + jcy0 * jcy1); mm[1][0] = mm[0][1]
        mm[1][1] = m * (jcx1 * jcx1 + jcy1 * jcy1)
        // Q = Σ m Jᵀ((0, −g) − h), plus gravity on the beam and a little
        // friction in the axles.
        var q0 = m * (jcx0 * (-hcx) + jcy0 * (-grav - hcy)) - armMass * grav * armCm * c - AXLE * vv[0]
        var q1 = m * (jcx1 * (-hcx) + jcy1 * (-grav - hcy)) - AXLE * 0.2 * vv[1]
        var q2 = 0.0
        if (!released) {
            mm[0][2] = ms * (jpx0 * jpx2 + jpy0 * jpy2); mm[2][0] = mm[0][2]
            mm[2][2] = ms * (jpx2 * jpx2 + jpy2 * jpy2)
            q0 += ms * (jpx0 * (-hpx) + jpy0 * (-grav - hpy))
            q2 = ms * (jpx2 * (-hpx) + jpy2 * (-grav - hpy))
        } else {
            mm[2][2] = 1.0
        }
        mm[0][4] = q0; mm[1][4] = q1; mm[2][4] = q2
        var size = n
        if (!released && onTrough) {
            // The trough holds the stone's height: Jy·q̈ + hy = 0, with an
            // upward push λ entering as Jyᵀλ.
            mm[0][3] = -jpy0; mm[2][3] = -jpy2
            mm[3][0] = jpy0; mm[3][2] = jpy2
            mm[3][4] = -hpy
            size = 4
        }
        // Columns 0..size−1 plus the right-hand side in column 4.
        val sol = solve(size)
        acc[0] = sol[0]; acc[1] = sol[1]; acc[2] = if (released) 0.0 else sol[2]
        lambda = if (size == 4) sol[3] else 0.0
    }

    private var lambda = 0.0
    private val solOut = DoubleArray(4)

    private fun solve(size: Int): DoubleArray {
        // Gaussian elimination with partial pivoting on the leading block.
        val cols = intArrayOf(0, 1, 2, 3).copyOf(size)
        for (col in 0 until size) {
            var piv = col
            for (r in col + 1 until size) if (abs(mm[r][cols[col]]) > abs(mm[piv][cols[col]])) piv = r
            if (piv != col) { val t = mm[piv]; mm[piv] = mm[col]; mm[col] = t }
            val d = mm[col][cols[col]]
            if (abs(d) < 1e-12) continue
            for (r in 0 until size) {
                if (r == col) continue
                val f = mm[r][cols[col]] / d
                if (f == 0.0) continue
                for (k in 0 until size) mm[r][cols[k]] -= f * mm[col][cols[k]]
                mm[r][4] -= f * mm[col][4]
            }
        }
        for (i in 0 until size) {
            val d = mm[i][cols[i]]
            solOut[i] = if (abs(d) < 1e-12) 0.0 else mm[i][4] / d
        }
        return solOut
    }

    private val k1q = DoubleArray(3); private val k1v = DoubleArray(3)
    private val k2q = DoubleArray(3); private val k2v = DoubleArray(3)
    private val k3q = DoubleArray(3); private val k3v = DoubleArray(3)
    private val k4q = DoubleArray(3); private val k4v = DoubleArray(3)
    private val tq = DoubleArray(3); private val tv = DoubleArray(3)

    private fun rk4(h: Double) {
        accel(q, qd); for (i in 0..2) { k1q[i] = qd[i]; k1v[i] = acc[i] }
        val lam = lambda
        for (i in 0..2) { tq[i] = q[i] + k1q[i] * h / 2; tv[i] = qd[i] + k1v[i] * h / 2 }
        accel(tq, tv); for (i in 0..2) { k2q[i] = tv[i]; k2v[i] = acc[i] }
        for (i in 0..2) { tq[i] = q[i] + k2q[i] * h / 2; tv[i] = qd[i] + k2v[i] * h / 2 }
        accel(tq, tv); for (i in 0..2) { k3q[i] = tv[i]; k3v[i] = acc[i] }
        for (i in 0..2) { tq[i] = q[i] + k3q[i] * h; tv[i] = qd[i] + k3v[i] * h }
        accel(tq, tv); for (i in 0..2) { k4q[i] = tv[i]; k4v[i] = acc[i] }
        for (i in 0..2) {
            q[i] += h / 6 * (k1q[i] + 2 * k2q[i] + 2 * k3q[i] + k4q[i])
            qd[i] += h / 6 * (k1v[i] + 2 * k2v[i] + 2 * k3v[i] + k4v[i])
        }
        // The trough lets go once it would have to pull the stone down.
        if (onTrough && lam < 0.0) onTrough = false
        if (released) { q[2] = q[2]; qd[2] = 0.0 }
    }

    private fun stoneX(): Double = -longArm * cos(q[0]) + slingLen * cos(q[2])
    private fun stoneY(): Double = pivotH - longArm * sin(q[0]) + slingLen * sin(q[2])
    private fun stoneVX(): Double = longArm * sin(q[0]) * qd[0] - slingLen * sin(q[2]) * qd[2]
    private fun stoneVY(): Double = -longArm * cos(q[0]) * qd[0] + slingLen * cos(q[2]) * qd[2]

    /** True once the stone's flight direction has come down to the release angle. */
    private fun ready(): Boolean {
        val vx = stoneVX(); val vy = stoneVY()
        if (vx <= 0.0 || stoneY() < 1.0) return false
        return atan2(vy, vx) * 180 / PI <= releaseAngle
    }

    // ---------------------------------------------------------------- aiming

    private class Rec(val x: DoubleArray, val y: DoubleArray, val vx: DoubleArray, val vy: DoubleArray, val n: Int)

    private fun rehearse(): Rec {
        val cap = (MAX_SWING / SUB_DT).toInt()
        val xs = DoubleArray(cap); val ys = DoubleArray(cap); val vxs = DoubleArray(cap); val vys = DoubleArray(cap)
        var n = 0
        while (n < cap) {
            rk4(SUB_DT)
            xs[n] = stoneX(); ys[n] = stoneY(); vxs[n] = stoneVX(); vys[n] = stoneVY()
            n++
            if (vxs[n - 1] > 0 && atan2(vys[n - 1], vxs[n - 1]) < 5 * PI / 180 && ys[n - 1] > 1.0) break
        }
        return Rec(xs, ys, vxs, vys, n)
    }

    /** Index of release for angle [a] degrees in the rehearsal, or −1. */
    private fun releaseIndex(rec: Rec, a: Double): Int {
        for (i in 0 until rec.n) {
            if (rec.vx[i] <= 0.0 || rec.y[i] < 1.0) continue
            if (atan2(rec.vy[i], rec.vx[i]) * 180 / PI <= a) return i
        }
        return -1
    }

    private fun groundRange(rec: Rec, a: Double): Double {
        val i = releaseIndex(rec, a)
        if (i < 0 || grav <= 0.0) return 0.0
        val vy = rec.vy[i]
        val t = (vy + sqrt(vy * vy + 2 * grav * rec.y[i])) / grav
        return rec.x[i] + rec.vx[i] * t
    }

    private fun bestRange(rec: Rec): Double {
        var best = 0.0
        var a = 10.0
        while (a <= 80.0) { best = max(best, groundRange(rec, a)); a += 0.5 }
        return best
    }

    /** The highest-arcing release that strikes the wall face a little over half way up. */
    private fun aim(rec: Rec): Double {
        var bestA = 45.0; var bestErr = Double.MAX_VALUE
        var a = 80.0
        while (a >= 10.0) {
            val i = releaseIndex(rec, a)
            if (i >= 0 && rec.vx[i] > 0) {
                val t = (wallX - rec.x[i]) / rec.vx[i]
                if (t > 0) {
                    val y = rec.y[i] + rec.vy[i] * t - 0.5 * grav * t * t
                    val err = abs(y - WALL_H * 0.5)
                    // Prefer the high lob, as a trebuchet shoots, when it can make it.
                    val score = err + (if (a < 45.0) 3.0 else 0.0)
                    if (score < bestErr) { bestErr = score; bestA = a }
                }
            }
            a -= 0.25
        }
        return bestA
    }

    // ---------------------------------------------------------------- castle

    private fun buildCastle(env: SimEnv) {
        val list = ArrayList<FloatArray>()
        // The curtain wall: blocks 1 m wide and 1.2 m high, four deep, with
        // merlons along the top.
        val cols = 4; val rows = (WALL_H / 1.2).toInt()
        for (rw in 0 until rows) for (c in 0 until cols) {
            list.add(floatArrayOf((wallX + 0.5 + c).toFloat(), (0.6 + rw * 1.2).toFloat(), 0.5f, 0.6f, c.toFloat(), rw.toFloat(), 0f))
        }
        for (c in 0 until cols step 2) list.add(floatArrayOf((wallX + 0.5 + c).toFloat(), (0.6 + rows * 1.2).toFloat(), 0.5f, 0.6f, c.toFloat(), rows.toFloat(), 0f))
        // A tower behind it, taller, with a flag.
        val tx = wallX + cols + 1.0
        val tRows = (TOWER_H / 1.2).toInt()
        for (rw in 0 until tRows) for (c in 0 until 5) {
            list.add(floatArrayOf((tx + 0.6 + c * 1.2).toFloat(), (0.6 + rw * 1.2).toFloat(), 0.6f, 0.6f, (10 + c).toFloat(), rw.toFloat(), 1f))
        }
        for (c in 0 until 5 step 2) list.add(floatArrayOf((tx + 0.6 + c * 1.2).toFloat(), (0.6 + tRows * 1.2).toFloat(), 0.6f, 0.6f, (10 + c).toFloat(), tRows.toFloat(), 1f))
        blocks = list.size
        bx = FloatArray(blocks) { list[it][0] }; by = FloatArray(blocks) { list[it][1] }
        bw = FloatArray(blocks) { list[it][2] }; bh = FloatArray(blocks) { list[it][3] }
        bcol = IntArray(blocks) { list[it][4].toInt() }; brow = IntArray(blocks) { list[it][5].toInt() }
        bTower = BooleanArray(blocks) { list[it][6] > 0.5f }
        bvx = FloatArray(blocks); bvy = FloatArray(blocks); brot = FloatArray(blocks); bvr = FloatArray(blocks)
        bstate = IntArray(blocks)
        // Stones vary a little in shade so the wall reads as masonry.
        shade = FloatArray(blocks) { env.rng.range(-0.06f, 0.06f) }
    }

    private var shade = FloatArray(0)
    private var cloudX = DoubleArray(0)
    private var cloudY = DoubleArray(0)
    private var cloudR = FloatArray(0)

    // ---------------------------------------------------------------- step

    override fun step(env: SimEnv, dt: Float) {
        payoffFrame = false
        val h = abs(dt)
        elapsed += h
        if (elapsed < HOLD) { camera(h); return }
        if (!started) {
            started = true
            env.emit(EventKind.STEP, 0.3f, panOf(0.0), 0.7f)
        }
        val n = max(1, (h / SUB_DT).toInt())
        val sub = h.toDouble() / n
        repeat(n) {
            rk4(sub)
            if (!released && ready()) {
                released = true
                sx = stoneX(); sy = stoneY(); svx = stoneVX(); svy = stoneVY()
                env.emit(EventKind.CROSS, 0.7f, panOf(sx), 0.8f)
            }
        }
        if (released && hitAt < 0f && !landed) fly(env, h.toDouble())
        stepBlocks(env, h)
        camera(h)
    }

    private fun fly(env: SimEnv, h: Double) {
        val n = 8
        val d = h / n
        repeat(n) {
            if (hitAt >= 0f || landed) return@repeat
            val px = sx; val py = sy
            svy -= grav * d
            sx += svx * d; sy += svy * d
            flightT += d
            apex = max(apex, sy)
            // The wall face, crossed this substep below the battlements?
            if (px < wallX && sx >= wallX) {
                val f = (wallX - px) / (sx - px)
                val yAt = py + (sy - py) * f
                if (yAt in 0.0..(WALL_H + 1.2)) {
                    sx = wallX - stoneR; sy = yAt
                    impact(env, true)
                    return@repeat
                }
            }
            if (sy <= stoneR) {
                sy = stoneR
                // Behind the wall it still smashes whatever it lands on.
                impact(env, sx > wallX && sx < wallX + 12.0)
                landed = true
            }
        }
        // Distance posts every 25 m tick by as it passes them.
        while (sx >= markNext * MARK && markNext * MARK < wallX - 5) {
            env.emit(EventKind.COMPARE, 0.75f - 0.03f * markNext, 0.4f, 0.2f)
            markNext++
        }
        trailTick += h.toFloat()
        if (trailTick > 0.04f) {
            trailTick = 0f
            if (trailN < TRAIL) { trailX[trailN] = sx.toFloat(); trailY[trailN] = sy.toFloat(); trailN++ }
        }
    }

    private fun impact(env: SimEnv, onCastle: Boolean) {
        hitAt = elapsed
        payoffFrame = true
        val speed = sqrt(svx * svx + svy * svy)
        verdict = if (onCastle) "hit the castle" else if (sx < wallX) String.format(java.util.Locale.US, "%.0f m short", wallX - sx) else "over the wall"
        env.emit(EventKind.HIT, 0.2f, panOf(sx), 1f)
        if (onCastle) {
            // Stones near the strike are thrown, the harder the faster.
            val blast = 2.2 + speed * 0.04
            for (i in 0 until blocks) {
                val dx = bx[i] - sx; val dy = by[i] - sy
                val d = sqrt(dx * dx + dy * dy)
                if (d < blast) {
                    val k = (1.0 - d / blast)
                    bstate[i] = 1
                    bvx[i] = (svx * 0.25 * k + dx / (d + 0.3) * 4 * k).toFloat() + env.rng.range(-1f, 1f)
                    bvy[i] = (abs(svy) * 0.1 * k + dy / (d + 0.3) * 4 * k + 3 * k).toFloat() + env.rng.range(0f, 2f)
                    bvr[i] = env.rng.range(-6f, 6f)
                    knocked++
                }
            }
        }
        // The stone itself drops away from the face.
        svx = -svx * 0.12; svy = 1.0
        landed = false
    }

    private fun stepBlocks(env: SimEnv, h: Float) {
        if (hitAt < 0f) return
        // A stone with nothing under it in its column falls.
        for (i in 0 until blocks) {
            if (bstate[i] != 0 || brow[i] == 0) continue
            var held = false
            for (j in 0 until blocks) {
                if (bstate[j] == 0 && bcol[j] == bcol[i] && brow[j] == brow[i] - 1) { held = true; break }
            }
            if (!held) { bstate[i] = 1; bvx[i] = env.rng.range(-0.4f, 0.4f); bvr[i] = env.rng.range(-1f, 1f); knocked++ }
        }
        val g = grav.toFloat()
        for (i in 0 until blocks) {
            if (bstate[i] != 1) continue
            bvy[i] -= g * h
            bx[i] += bvx[i] * h; by[i] += bvy[i] * h
            brot[i] += bvr[i] * h
            if (by[i] < bh[i]) {
                by[i] = bh[i]
                val hard = -bvy[i]
                bvy[i] = -bvy[i] * 0.25f
                bvx[i] *= 0.55f; bvr[i] *= 0.4f
                if (hard > 3f && elapsed - lastThud > 0.07f) {
                    lastThud = elapsed
                    env.emit(EventKind.HIT, 0.25f + 0.1f * (i % 3), panOf(bx[i].toDouble()), (hard / 20f).coerceIn(0.1f, 0.5f))
                }
                if (abs(bvy[i]) < 0.6f && abs(bvx[i]) < 0.3f) { bstate[i] = 2; bvy[i] = 0f; bvx[i] = 0f }
            }
        }
        // The stone itself, after the strike.
        if (!landed) {
            svy -= grav * h
            sx += svx * h; sy += svy * h
            if (sy <= stoneR) { sy = stoneR; landed = true }
        }
    }

    // ---------------------------------------------------------------- camera

    private fun camera(h: Float) {
        val halfH: Double
        var tx: Double
        var ty: Double
        if (!released) {
            halfH = 1.0 / startScale()
            tx = -1.5; ty = startCamY()
        } else if (hitAt < 0f) {
            // Follow the stone at a fixed 50 m of sky, ground along the bottom
            // until the stone climbs past it, looking ahead to the castle.
            // Pulling back to hold the whole arc shrank everything to specks.
            halfH = FLIGHT_VIEW / 2
            val halfW = halfH * hw
            tx = min(sx + halfW * 0.45, wallX + 6)
            ty = max(sy, halfH - 4)
        } else {
            halfH = 16.0
            tx = wallX + 4; ty = halfH - 3
        }
        val targetS = 1.0 / halfH
        // Eased, so the camera glides rather than cuts; zoom and pan together.
        val k = 1.0 - kotlin.math.exp(-h * (if (released && hitAt < 0f) 3.0 else 2.2))
        camS += (targetS - camS) * k
        camX += (tx - camX) * k
        camY += (ty - camY) * k
    }

    private fun sxw(x: Double): Float = ((x - camX) * camS).toFloat()
    private fun syw(y: Double): Float = ((y - camY) * camS).toFloat()
    private fun visible(x: Float, ext: Float): Boolean = abs(x) - ext < hw * 1.05f
    private fun panOf(x: Double) = (((x - camX) * camS).toFloat() / max(0.1f, hw)).coerceIn(-1f, 1f)

    // ---------------------------------------------------------------- draw

    override fun draw(env: SimEnv, g: G, alpha: Float) {
        val pal = env.palette
        val s = camS.toFloat()
        val groundY = syw(0.0)
        // Ground.
        g.rect(0f, (groundY - 1.2f) * 0.5f, hw * 1.02f, max(0f, (groundY + 1.2f) * 0.5f), GROUND)
        g.rect(0f, groundY, hw * 1.02f, 0.002f, GROUND_LIT)
        // Distance posts.
        var m = MARK
        while (m < wallX - 5) {
            val x = sxw(m)
            if (visible(x, 0.01f)) {
                g.rect(x, groundY + 0.8f * s, 0.0025f, 0.8f * s, POST)
                if ((m / MARK).toInt() % 2 == 0) g.rect(x, groundY + 1.7f * s, 0.006f, 0.0025f, POST)
            }
            m += MARK
        }
        for (i in 0 until cloudX.size) {
            val x = sxw(cloudX[i]); val r = cloudR[i] * s
            if (!visible(x, r * 1.6f)) continue
            val y = syw(cloudY[i])
            if (abs(y) > 1f + r) continue
            g.glow(x - r * 0.7f, y, r, DUST, 0.08f)
            g.glow(x + r * 0.6f, y + r * 0.1f, r * 0.85f, DUST, 0.07f)
            g.glow(x, y + r * 0.35f, r * 0.9f, DUST, 0.08f)
        }
        drawCastle(env, g, s)
        drawMachine(env, g, s)
        // The stone and its arc.
        val accent = pal.at(0.08f)
        if (released) {
            for (i in 1 until trailN) {
                val x0 = sxw(trailX[i - 1].toDouble()); val x1 = sxw(trailX[i].toDouble())
                if (!visible(x0, 0f) && !visible(x1, 0f)) continue
                g.line(x0, syw(trailY[i - 1].toDouble()), x1, syw(trailY[i].toDouble()), 0.003f, accent, 0.35f + 0.4f * i / trailN)
            }
            val px = sxw(sx); val py = syw(sy)
            val rr = max(stoneR.toFloat() * s, 0.008f)
            if (visible(px, rr)) {
                g.glow(px, py, rr * 4f, accent, 0.4f)
                g.disc(px, py, rr, STONE)
            }
            if (hitAt >= 0f) {
                // Dust where it struck: grows once and settles, no throb.
                val u = ((elapsed - hitAt) / 1.2f).coerceIn(0f, 1f)
                val ix = sxw(wallX); val iy = py
                g.glow(ix, iy, (2f + 5f * u) * s, DUST, 0.35f * (1f - u))
            }
        }
    }

    private fun drawMachine(env: SimEnv, g: G, s: Float) {
        val pivX = sxw(0.0); val pivY = syw(pivotH)
        if (!visible(pivX, 16f * s)) return
        val gy = syw(0.0)
        // A-frame, base beam and trough.
        g.line(sxw(-3.2), gy, pivX, pivY, 0.4f * s, WOOD_DARK)
        g.line(sxw(3.2), gy, pivX, pivY, 0.4f * s, WOOD_DARK)
        g.line(sxw(-4.2), gy + 0.2f * s, sxw(4.2), gy + 0.2f * s, 0.4f * s, WOOD_DARK)
        g.line(sxw(-6.5), gy + 0.05f * s, sxw(2.5), gy + 0.05f * s, 0.15f * s, WOOD)
        val c = cos(q[0]); val sn = sin(q[0])
        val ax = shortArm * c; val ay = pivotH + shortArm * sn
        val tipX = -longArm * c; val tipY = pivotH - longArm * sn
        // Counterweight hanging from its hinge.
        val cwx = ax + hinge * sin(q[1]); val cwy = ay - hinge * cos(q[1])
        g.line(sxw(ax), syw(ay), sxw(cwx), syw(cwy), 0.12f * s, IRON)
        val box = 0.9f + 0.25f * (cwMass / 4000.0).toFloat()
        g.rect(sxw(cwx), syw(cwy) - box * 0.6f * s, box * s, box * 0.8f * s, WOOD_DARK, q[1].toFloat())
        g.rect(sxw(cwx), syw(cwy) - box * 0.6f * s, box * 0.85f * s, box * 0.65f * s, CW_FILL, q[1].toFloat())
        // The beam, thicker at the pivot.
        g.line(sxw(ax), syw(ay), pivX, pivY, 0.5f * s, WOOD)
        g.line(pivX, pivY, sxw(tipX), syw(tipY), 0.34f * s, WOOD)
        g.disc(pivX, pivY, 0.32f * s, IRON)
        // The sling, while it holds the stone.
        if (!released) {
            val stx = stoneX(); val sty = stoneY()
            g.line(sxw(tipX), syw(tipY), sxw(stx), syw(sty), 0.05f * s + 0.0015f, ROPE)
            val rr = max(stoneR.toFloat() * s, 0.008f)
            g.disc(sxw(stx), syw(sty), rr, STONE)
        } else {
            // Empty, trailing from the tip.
            val ang = q[0] + PI * 0.5
            g.line(sxw(tipX), syw(tipY), sxw(tipX + slingLen * 0.6 * cos(ang)), syw(tipY + slingLen * 0.6 * sin(ang)), 0.05f * s + 0.0015f, ROPE, 0.7f)
        }
    }

    private fun drawCastle(env: SimEnv, g: G, s: Float) {
        val pal = env.palette
        val stoneBase = Palette.lerp(0xFF6B6F78.toInt(), pal.at(0.6f), 0.12f)
        val towerBase = Palette.lerp(0xFF5A5E68.toInt(), pal.at(0.6f), 0.12f)
        for (i in 0 until blocks) {
            val x = sxw(bx[i].toDouble())
            if (!visible(x, bw[i] * s * 1.5f)) continue
            val base = if (bTower[i]) towerBase else stoneBase
            val c = if (shade[i] >= 0f) Palette.brighten(base, shade[i]) else Palette.lerp(base, 0xFF000000.toInt(), -shade[i] * 2f)
            val y = syw(by[i].toDouble())
            // A hairline of mortar: each stone drawn a touch smaller than its cell.
            g.rect(x, y, bw[i] * s * 0.94f, bh[i] * s * 0.92f, c, brot[i])
        }
        // A flag on the tower.
        val fx = sxw(wallX + 4 + 1 + 3.0)
        val tTop = (TOWER_H / 1.2).toInt() * 1.2 + 1.2
        if (visible(fx, 3f * s)) {
            val top = syw(tTop + 4.0)
            g.rect(fx, (syw(tTop) + top) * 0.5f, 0.08f * s + 0.001f, (top - syw(tTop)) * 0.5f, POST)
            g.rect(fx + 1.2f * s, top - 0.6f * s, 1.2f * s, 0.6f * s, pal.at(0.08f))
        }
    }

    override fun payoff(): Boolean = payoffFrame

    override fun finished(): Boolean = hitAt >= 0f && elapsed - hitAt > 4.5f

    override fun counters(): List<Pair<String, String>> {
        val l = java.util.Locale.US
        return if (!released) listOf(
            "release angle" to String.format(l, "%.1f°%s", releaseAngle, if (autoAim) " (aimed)" else ""),
            "counterweight" to String.format(l, "%,.0f kg", cwMass),
            "castle" to String.format(l, "%.0f m", wallX)
        ) else listOf(
            "range" to String.format(l, "%.0f m", max(0.0, sx)),
            "height" to String.format(l, "%.0f m", max(0.0, if (hitAt < 0f) sy else apex)),
            if (hitAt >= 0f) "result" to verdict else "flight" to String.format(l, "%.1f s", flightT)
        )
    }

    companion object {
        /** 12 substeps of a 1/120 s step exactly, so the live swing retraces the rehearsal. */
        const val SUB_DT = 1.0 / 1440.0
        const val MAX_SWING = 6.0
        const val AXLE = 40.0
        const val HOLD = 1.2f
        const val WALL_H = 12.0
        const val TOWER_H = 20.0
        const val MARK = 25.0
        const val FLIGHT_VIEW = 50.0
        const val CLOUDS = 14
        const val TRAIL = 400
        const val GROUND = 0xFF12151B.toInt()
        const val GROUND_LIT = 0xFF3A404D.toInt()
        const val POST = 0xFF4A505C.toInt()
        const val WOOD = 0xFF7A5A3E.toInt()
        const val WOOD_DARK = 0xFF4E3A29.toInt()
        const val IRON = 0xFF4A4F59.toInt()
        const val CW_FILL = 0xFF3A3F48.toInt()
        const val ROPE = 0xFFC9B48A.toInt()
        const val STONE = 0xFFD9D4C8.toInt()
        const val DUST = 0xFFB8AE9C.toInt()

        fun def(): SimDef = SimDef(
            id = "physics/trebuchet",
            title = "Trebuchet",
            shelf = Shelf.PHYSICS,
            family = "Mechanics",
            blurb = "Four tonnes drop two metres. A stone flies two hundred metres. Does it hit the wall?",
            formats = listOf(TrapFormat.SNAP, TrapFormat.GUESS),
            lawKnobs = setOf(LawKnob.GRAVITY),
            heroLabel = "Load another stone",
            defaultPalette = "Ember",
            typicalSeconds = 14f,
            params = listOf(
                Param.float("weight", "Counterweight", 1000f, 10000f, 4000f, card = Card.MOTION, unit = "kg"),
                Param.choice("aim", "Aim", listOf("Auto-aim", "By hand"), 0, card = Card.DATA, input = ParamInput.TILES),
                Param.float("release", "Release angle", 10f, 80f, 45f, card = Card.MOTION, unit = "°", mutedBy = "aim", mutedAt = listOf(0))
            ),
            create = { TrebuchetSim() }
        )
    }
}
