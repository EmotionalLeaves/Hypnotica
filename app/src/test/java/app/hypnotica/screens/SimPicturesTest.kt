package app.hypnotica.screens

import app.hypnotica.core.render.G
import app.hypnotica.core.sim.SimCore
import app.hypnotica.core.sim.SimDef
import app.hypnotica.sims.SimRegistry
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Every sim, drawn.
 *
 * The screen pictures show the chrome, but the sim behind it is a GL surface
 * that draws nothing off a phone, so the one thing the app is for never
 * appeared in them. This steps each sim on the CPU and paints its own draw
 * list — three moments side by side: early, in the build, and just after the
 * payoff — into build/screens/sims/. A small software painter, because the
 * unit tests compile against android.jar and java.awt is not there.
 *
 * Runs with the other pictures (-Pscreens); -Psims=id,id draws only those.
 */
class SimPicturesTest {

    @Test
    fun drawEverySim() {
        val only = System.getProperty("sims")?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty()
        val dir = File("build/screens/sims").apply { mkdirs() }
        for (def in SimRegistry.all) {
            if (only.isNotEmpty() && def.id !in only) continue
            try {
                sheet(def, File(dir, def.id.replace('/', '_') + ".png"))
            } catch (t: Throwable) {
                println("could not draw ${def.id}: $t")
            }
        }
    }

    private fun sheet(def: SimDef, out: File) {
        val img = Canvas(W * 3 + GAP * 2, H)
        val core = SimCore(def, 3L)
        core.setAspect(W.toFloat() / H)
        core.restart(3L)
        // -Pmoments=a,b,c: three shares of the run to draw instead (0 = just after the payoff).
        val moments = System.getProperty("moments")?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }
            ?.takeIf { it.size == 3 }?.toFloatArray() ?: floatArrayOf(0.15f, 0.55f, 0f)
        var done = 0
        for ((k, m) in moments.withIndex()) {
            // Counted in frames, not read off the sim's clock: a run that
            // finishes and holds, or stands still, never reaches a time.
            if (m > 0f) {
                val frames = (def.typicalSeconds * m * 60f).toInt() - done
                repeat(max(0, frames)) { core.advance(1f / 60f) }
                done += max(0, frames)
            } else {
                // The last waits for the payoff (or half again the run's length) and a breath after.
                var guard = 0
                while (!core.payoffFired && core.env.time < def.typicalSeconds * 1.5f && guard < 20000) {
                    core.advance(1f / 60f); guard++
                }
                repeat(30) { core.advance(1f / 60f) }
            }
            val g = G()
            g.reset()
            core.draw(g)
            img.paint(g, core.fit(), k * (W + GAP))
        }
        out.writeBytes(img.png())
    }

    /** ARGB pixels, source-over, with just the four shapes the draw list has. */
    private class Canvas(val w: Int, val h: Int) {
        val px = IntArray(w * h) { BG }

        fun paint(g: G, fit: Float, left: Int) {
            val s = H / 2f * fit
            val cx0 = left + W / 2f
            val cy0 = H / 2f
            fun shapes(arr: FloatArray, n: Int) {
                for (i in 0 until n) {
                    val o = i * G.STRIDE
                    val a = arr[o + 11].coerceIn(0f, 1f)
                    if (a <= 0.003f) continue
                    shape(
                        cx0 + arr[o] * s, cy0 - arr[o + 1] * s, arr[o + 2] * s, arr[o + 3] * s,
                        arr[o + 4], arr[o + 5], arr[o + 6],
                        arr[o + 8], arr[o + 9], arr[o + 10], a, left
                    )
                }
            }
            shapes(g.glows, g.glowCount)
            shapes(g.instances, g.instanceCount)
            for (i in 0 until g.pointCount) {
                val r = (g.pointPos[i * 3 + 2] * s).coerceIn(0.7f, 6f)
                shape(
                    cx0 + g.pointPos[i * 3] * s, cy0 - g.pointPos[i * 3 + 1] * s, r, r, 0f, G.SHAPE_DISC, 0f,
                    (g.pointColor[i * 4].toInt() and 0xFF) / 255f, (g.pointColor[i * 4 + 1].toInt() and 0xFF) / 255f,
                    (g.pointColor[i * 4 + 2].toInt() and 0xFF) / 255f, (g.pointColor[i * 4 + 3].toInt() and 0xFF) / 255f, left
                )
            }
        }

        private fun shape(
            x: Float, y: Float, hw0: Float, hh0: Float, rot: Float, kind: Float, inner: Float,
            r: Float, gr: Float, b: Float, a: Float, left: Int
        ) {
            val hw = max(hw0, 0.5f); val hh = max(hh0, 0.5f)
            val c = cos(rot); val sn = sin(rot)
            val ext = if (kind == G.SHAPE_RECT) sqrt(hw * hw + hh * hh) else max(hw, hh)
            val x0 = max(left, (x - ext).toInt()); val x1 = min(left + W - 1, (x + ext).toInt() + 1)
            val y0 = max(0, (y - ext).toInt()); val y1 = min(h - 1, (y + ext).toInt() + 1)
            for (py in y0..y1) for (pxi in x0..x1) {
                val dx = pxi + 0.5f - x; val dy = py + 0.5f - y
                val cov = when (kind) {
                    G.SHAPE_RECT -> {
                        val u = dx * c - dy * sn; val v = dx * sn + dy * c
                        if (kotlin.math.abs(u) <= hw && kotlin.math.abs(v) <= hh) 1f else 0f
                    }
                    G.SHAPE_DISC -> if (dx * dx + dy * dy <= hw * hw) 1f else 0f
                    G.SHAPE_RING -> {
                        val d = sqrt(dx * dx + dy * dy)
                        if (d <= hw && d >= hw * inner.coerceIn(0f, 0.999f) - 0.5f) 1f else 0f
                    }
                    else -> {
                        val d = sqrt(dx * dx + dy * dy) / hw
                        if (d >= 1f) 0f else (1f - d) * (1f - d)
                    }
                }
                if (cov > 0f) blend(py * w + pxi, r, gr, b, a * cov, kind == G.SHAPE_GLOW)
            }
        }

        private fun blend(i: Int, r: Float, g: Float, b: Float, a: Float, add: Boolean) {
            val d = px[i]
            val dr = (d shr 16 and 0xFF) / 255f; val dg = (d shr 8 and 0xFF) / 255f; val db = (d and 0xFF) / 255f
            val nr: Float; val ng: Float; val nb: Float
            if (add) { nr = dr + r * a; ng = dg + g * a; nb = db + b * a }
            else { nr = dr + (r - dr) * a; ng = dg + (g - dg) * a; nb = db + (b - db) * a }
            px[i] = (0xFF shl 24) or (q(nr) shl 16) or (q(ng) shl 8) or q(nb)
        }

        private fun q(v: Float): Int = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

        fun png(): ByteArray {
            val raw = ByteArrayOutputStream()
            DeflaterOutputStream(raw).use { z ->
                for (y in 0 until h) {
                    z.write(0)
                    for (x in 0 until w) {
                        val p = px[y * w + x]
                        z.write(p shr 16 and 0xFF); z.write(p shr 8 and 0xFF); z.write(p and 0xFF)
                    }
                }
            }
            val out = ByteArrayOutputStream()
            val o = DataOutputStream(out)
            o.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            fun chunk(type: String, data: ByteArray) {
                o.writeInt(data.size)
                val t = type.toByteArray(Charsets.US_ASCII)
                o.write(t); o.write(data)
                val crc = CRC32(); crc.update(t); crc.update(data)
                o.writeInt(crc.value.toInt())
            }
            val ihdr = ByteArrayOutputStream()
            DataOutputStream(ihdr).apply { writeInt(w); writeInt(h); write(8); write(2); write(0); write(0); write(0) }
            chunk("IHDR", ihdr.toByteArray())
            chunk("IDAT", raw.toByteArray())
            chunk("IEND", ByteArray(0))
            return out.toByteArray()
        }
    }

    private companion object {
        const val W = 393
        const val H = 852
        const val GAP = 8
        const val BG = 0xFF07080C.toInt()
    }
}
