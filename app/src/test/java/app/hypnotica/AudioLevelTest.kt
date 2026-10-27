package app.hypnotica

import app.hypnotica.core.audio.SoundMapping
import app.hypnotica.core.audio.SoundPreset
import app.hypnotica.core.audio.Synth
import app.hypnotica.core.audio.VoiceType
import app.hypnotica.core.sim.SimCore
import app.hypnotica.sims.SimRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Every preset sits beside 8-bit: about as loud, and not much brighter.
 *
 * 8-bit is the default because it was the one that sounded right. Rendered
 * through the real synth and mapping, the others were not close: Music box
 * played 2.3 times louder, Ambient pad 2.9 times, and Marimba's pluck — excited
 * with raw white noise — had three times 8-bit's brightness. Switching preset
 * was a jump in volume, and the presets that were loud or bright were the ones
 * that read as grating. This renders each one and holds it to 8-bit.
 *
 * Brightness is the RMS frequency from the signal's derivative, the second
 * spectral moment: what "harsh" mostly is. Ticks is exempt from it, because a
 * tick is a transient and is meant to be bright.
 */
class AudioLevelTest {

    private val rate = 44100
    private val sims = listOf("sorting/quicksort", "art/polyrhythm")

    /** (mean RMS, mean brightness in Hz) over the sims. */
    private fun measure(preset: SoundPreset): Pair<Double, Double> {
        var loud = 0.0
        var bright = 0.0
        for (id in sims) {
            val core = SimCore(SimRegistry.all.first { it.id == id }, 77L)
            core.setAspect(0.56f)
            val synth = Synth(rate)
            val mapping = SoundMapping(synth)
            mapping.applyPreset(preset)
            val perFrame = rate / 60
            val buf = FloatArray(perFrame * 2)
            var sumSq = 0.0
            var sumDiff = 0.0
            var prev = 0f
            var n = 0L
            repeat(SECONDS * 60) {
                core.advance(1f / 60f)
                mapping.pump(core.events, 1f / 60f)
                java.util.Arrays.fill(buf, 0f)
                synth.renderOffline(buf, perFrame)
                for (i in 0 until perFrame) {
                    val x = buf[i * 2]
                    sumSq += (x * x).toDouble()
                    val d = x - prev
                    sumDiff += (d * d).toDouble()
                    prev = x
                    n++
                }
            }
            loud += sqrt(sumSq / n)
            bright += if (sumSq > 0) sqrt(sumDiff / sumSq) * rate / (2 * Math.PI) else 0.0
        }
        return loud / sims.size to bright / sims.size
    }

    @Test
    fun everyPresetSitsBeside8Bit() {
        val (refLoud, refBright) = measure(SoundPreset.EIGHT_BIT)
        assertTrue("8-bit made no sound, so this proves nothing", refLoud > 0.01)
        val wrong = ArrayList<String>()
        for (preset in SoundPreset.ALL) {
            if (preset.silent || preset === SoundPreset.EIGHT_BIT) continue
            val (loud, bright) = measure(preset)
            val ratio = loud / refLoud
            // Clicks are transients: their RMS over time reads low however
            // loud each one is, so they are only held to not drowning 8-bit.
            val tooQuiet = preset.voice != VoiceType.CLICK && ratio < 0.7
            if (tooQuiet || ratio > 1.35) {
                wrong.add(String.format(java.util.Locale.US, "%s plays at %.2fx 8-bit's level", preset.name, ratio))
            }
            if (preset.voice != VoiceType.CLICK && bright > refBright * 1.6) {
                wrong.add(String.format(java.util.Locale.US, "%s is %.0f Hz bright against 8-bit's %.0f", preset.name, bright, refBright))
            }
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    private companion object {
        const val SECONDS = 10
    }
}
