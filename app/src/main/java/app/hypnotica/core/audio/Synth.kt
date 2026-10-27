package app.hypnotica.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A small polyphonic synth fed directly by sim events.
 *
 * Nothing here plays a sound file: every note is computed, which is what keeps
 * the audio locked to the picture and lets any new sim be audible for free.
 */
class Synth(private val sampleRate: Int = 44100) {

    companion object {
        const val MAX_VOICES = 64
        private const val KS_SIZE = 1024
        private const val REVERB_SAMPLES = 14700 // ~0.33 s
    }

    private class Voice {
        var active = false
        var type = VoiceType.SINE
        var phase = 0f
        var phaseInc = 0f
        var amp = 0f
        var decay = 0.998f
        var panL = 0.7f
        var panR = 0.7f
        var age = 0
        var modPhase = 0f
        var modInc = 0f
        var ks: FloatArray? = null
        var ksIndex = 0
        var ksLen = 0
        var noiseState = 1
    }

    private val voices = Array(MAX_VOICES) { Voice() }
    private var nextVoice = 0

    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    /** 0..1 master volume. */
    @Volatile var volume: Float = 0.8f
    @Volatile var reverbAmount: Float = 0.2f
    @Volatile var muted: Boolean = false

    private val reverbL = FloatArray(REVERB_SAMPLES)
    private val reverbR = FloatArray(REVERB_SAMPLES)
    private var reverbIndex = 0

    private val lock = Any()

    /** Peak of the last written block, 0..1. Used by the loudness check. */
    @Volatile var lastPeak: Float = 0f
        private set

    fun start() {
        if (running) return
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufBytes = max(minBuf, 4096)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        track = t
        running = true
        try {
            t.play()
        } catch (e: IllegalStateException) {
            running = false
            track = null
            return
        }
        val frames = 256
        val pcm = ShortArray(frames * 2)
        val mix = FloatArray(frames * 2)
        thread = Thread({
            while (running) {
                render(mix, frames)
                var peak = 0f
                for (i in 0 until frames * 2) {
                    val v = mix[i]
                    val a = abs(v)
                    if (a > peak) peak = a
                    pcm[i] = (v.coerceIn(-1f, 1f) * 32000f).toInt().toShort()
                }
                lastPeak = peak
                try {
                    t.write(pcm, 0, pcm.size)
                } catch (e: Exception) {
                    break
                }
            }
        }, "hypnotica-audio").also {
            it.priority = Thread.MAX_PRIORITY
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        try {
            track?.pause()
            track?.flush()
            track?.release()
        } catch (e: Exception) {
            // Releasing a track that never started is not worth surfacing.
        }
        track = null
        allOff()
    }

    fun allOff() {
        synchronized(lock) {
            for (v in voices) v.active = false
        }
        java.util.Arrays.fill(reverbL, 0f)
        java.util.Arrays.fill(reverbR, 0f)
    }

    /**
     * Play one note. Safe to call from the sim thread; the audio thread picks it up
     * on its next block.
     */
    fun note(hz: Float, amplitude: Float, pan: Float, type: VoiceType, decaySeconds: Float) {
        if (muted) return
        val f = hz.coerceIn(20f, 8000f)
        synchronized(lock) {
            val v = allocate()
            v.active = true
            v.type = type
            v.phase = 0f
            v.phaseInc = f / sampleRate
            v.amp = amplitude.coerceIn(0f, 1f)
            val d = decaySeconds.coerceAtLeast(0.01f)
            v.decay = exp(-1f / (d * sampleRate)).coerceIn(0.9f, 0.9999995f)
            val p = pan.coerceIn(-1f, 1f)
            v.panL = kotlin.math.sqrt((1f - p) * 0.5f)
            v.panR = kotlin.math.sqrt((1f + p) * 0.5f)
            v.age = 0
            v.modPhase = 0f
            v.modInc = f * 2.76f / sampleRate
            v.noiseState = (f.toInt() * 2654435761L.toInt()) or 1
            if (type == VoiceType.PLUCK) {
                var buf = v.ks
                if (buf == null) {
                    buf = FloatArray(KS_SIZE)
                    v.ks = buf
                }
                val len = (sampleRate / f).toInt().coerceIn(2, KS_SIZE)
                v.ksLen = len
                v.ksIndex = 0
                var seed = v.noiseState
                for (i in 0 until len) {
                    seed = seed * 1103515245 + 12345
                    buf[i] = ((seed shr 16) and 0x7FFF) / 16384f - 1f
                }
            }
        }
    }

    private fun allocate(): Voice {
        // Prefer a free voice; otherwise steal the quietest.
        for (i in 0 until MAX_VOICES) {
            val idx = (nextVoice + i) % MAX_VOICES
            if (!voices[idx].active) {
                nextVoice = (idx + 1) % MAX_VOICES
                return voices[idx]
            }
        }
        var quietest = 0
        var qa = Float.MAX_VALUE
        for (i in 0 until MAX_VOICES) {
            if (voices[i].amp < qa) {
                qa = voices[i].amp
                quietest = i
            }
        }
        return voices[quietest]
    }

    private fun render(out: FloatArray, frames: Int) {
        java.util.Arrays.fill(out, 0f)
        val vol = if (muted) 0f else volume.coerceIn(0f, 1f)
        synchronized(lock) {
            for (v in voices) {
                if (!v.active) continue
                renderVoice(v, out, frames, vol)
            }
        }
        // Cheap single-tap reverb; enough to let a payoff land.
        val rv = reverbAmount.coerceIn(0f, 0.7f)
        if (rv > 0.001f) {
            var ri = reverbIndex
            for (i in 0 until frames) {
                val l = out[i * 2]
                val r = out[i * 2 + 1]
                val tl = reverbL[ri]
                val tr = reverbR[ri]
                out[i * 2] = l + tl * rv
                out[i * 2 + 1] = r + tr * rv
                reverbL[ri] = l + tl * rv * 0.45f
                reverbR[ri] = r + tr * rv * 0.45f
                ri++
                if (ri >= REVERB_SAMPLES) ri = 0
            }
            reverbIndex = ri
        }
        // Soft limiter: keeps a wall of events from clipping.
        for (i in 0 until frames * 2) {
            val x = out[i]
            out[i] = if (x > 0.85f || x < -0.85f) {
                (0.85f + kotlin.math.tanh((abs(x) - 0.85f) * 3f) * 0.14f) * (if (x < 0) -1f else 1f)
            } else x
        }
    }

    private fun renderVoice(v: Voice, out: FloatArray, frames: Int, vol: Float) {
        var amp = v.amp
        var phase = v.phase
        val inc = v.phaseInc
        var modPhase = v.modPhase
        val gainL = v.panL * vol
        val gainR = v.panR * vol
        val ks = v.ks
        var seed = v.noiseState

        for (i in 0 until frames) {
            if (amp < 0.00015f) {
                v.active = false
                break
            }
            val s: Float = when (v.type) {
                VoiceType.SINE -> sin(phase * 2f * PI.toFloat())
                VoiceType.TRIANGLE -> {
                    val p = phase % 1f
                    if (p < 0.5f) 4f * p - 1f else 3f - 4f * p
                }
                VoiceType.SQUARE -> if ((phase % 1f) < 0.5f) 0.55f else -0.55f
                VoiceType.BELL -> {
                    val m = sin(modPhase * 2f * PI.toFloat())
                    modPhase += v.modInc
                    sin((phase + m * 0.6f * amp) * 2f * PI.toFloat())
                }
                VoiceType.PLUCK -> {
                    if (ks == null || v.ksLen < 2) 0f else {
                        val idx = v.ksIndex
                        val next = (idx + 1) % v.ksLen
                        val value = ks[idx]
                        ks[idx] = (value + ks[next]) * 0.5f * 0.996f
                        v.ksIndex = next
                        value
                    }
                }
                VoiceType.CLICK -> {
                    seed = seed * 1103515245 + 12345
                    val n = ((seed shr 16) and 0x7FFF) / 16384f - 1f
                    n * (if (v.age < 220) 1f else 0f)
                }
                VoiceType.NOISE -> {
                    seed = seed * 1103515245 + 12345
                    ((seed shr 16) and 0x7FFF) / 16384f - 1f
                }
            }
            phase += inc
            if (phase > 1e6f) phase -= 1e6f
            val value = s * amp
            out[i * 2] += value * gainL
            out[i * 2 + 1] += value * gainR
            amp *= v.decay
            v.age++
        }
        v.amp = amp
        v.phase = phase
        v.modPhase = modPhase
        v.noiseState = seed
    }

    /** Number of voices currently sounding, for the UI's load indicator. */
    fun activeVoices(): Int {
        var n = 0
        synchronized(lock) { for (v in voices) if (v.active) n++ }
        return min(n, MAX_VOICES)
    }
}
