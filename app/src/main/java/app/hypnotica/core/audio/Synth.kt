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

    /** A click is this many samples of noise and then silence. */
    private val CLICK_SAMPLES = 220

    /** Smoothing passes over a pluck's excitation: how soft the mallet is. */
    private val PLUCK_SOFTEN = 7

    /** The click's one-pole low-pass, per sample, at 44.1 kHz about 2.5 kHz. */
    private val CLICK_LP = 0.3f

    /** Filtering takes level with it; this puts the click back where it was. */
    private val CLICK_GAIN = 2.2f

    companion object {
        const val MAX_VOICES = 64

        /** How long a note takes to reach full height. Short enough to still read as a hit. */
        const val ATTACK_SECONDS = 0.004f

        /**
         * One-pole coefficient for a cutoff near 6 kHz at 44.1 kHz.
         * k = 1 - exp(-2*pi*fc/fs).
         */
        const val LOWPASS_K = 0.575f
        /** The leveller's aim: about where the middle of the catalogue sat. */
        private const val LEVEL_TARGET = 0.09
        private const val LEVEL_MIN_GAIN = 0.5f
        private const val LEVEL_MAX_GAIN = 2f
        private const val LEVEL_SECONDS = 2.0
        private const val GAIN_DOWN_SECONDS = 0.8f
        private const val GAIN_UP_SECONDS = 3f
        /** Below this the mix is silence, and the leveller holds still. */
        private const val SILENCE_POWER = 1e-7
        private const val KS_SIZE = 1024
        private const val STEREO_SPREAD = 23
        /** Freeverb's room: a tail of about a second and a half, the highs dying first. */
        private const val COMB_FEEDBACK = 0.80f
        private const val COMB_DAMP = 0.3f
        private const val COMB_INPUT = 0.12f
        /** Set so the room at a preset's own amount sits about where the old echo did. */
        private const val WET_GAIN = 1.0f
        private const val KEYS_TINE_SECONDS = 0.09f
        private const val KICK_DROP_SECONDS = 0.035f
        private const val KICK_SWEEP = 3.2f
        private const val SNARE_SHELL_HZ = 185f
        private const val SNARE_SHELL_SECONDS = 0.05f
        /** About twelve cents: a slow, wide beating, not an out-of-tune one. */
        private const val WARM_DETUNE = 1.007f
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
        /** Low-pass state for the click, which is filtered noise rather than raw. */
        var clickLp = 0f

        /**
         * Samples left of the attack ramp.
         *
         * Every note used to begin at full amplitude on its first sample — a
         * step from silence, which is a click. With events arriving dozens of
         * times a second that is a continuous crackle riding on top of the
         * notes, and it is most of what "harsh" means here. A few milliseconds
         * of ramp removes it and is far too short to soften an attack.
         */
        var attackLeft = 0

        /** The keys' tine and the kick's thump: a second envelope, faster than the note's. */
        var modEnv = 0f
        var modDecay = 0.999f
        /** The warm voice's second, detuned oscillator. */
        var phase2 = 0f
        /** Low-pass state the hat subtracts, leaving the top. */
        var hp = 0f
    }

    private val voices = Array(MAX_VOICES) { Voice() }

    /** Attack ramp length, and the one-pole low-pass state for the mix. */
    private val attackSamples = (sampleRate * ATTACK_SECONDS).toInt().coerceAtLeast(1)
    private var lowL = 0f

    /**
     * The leveller's state: a slow running mean of the mix's power, measured
     * before the master volume, and the gain it is currently applying.
     */
    private var levelPower = -1.0
    private var levelGain = 1f
    private var lowR = 0f
    private var nextVoice = 0

    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    /** 0..1 master volume. */
    @Volatile var volume: Float = 0.8f
    @Volatile var reverbAmount: Float = 0.2f
    @Volatile var muted: Boolean = false

    /**
     * A small room: four damped comb filters in parallel and two all-passes
     * after them, per side (Schroeder's design, as Freeverb tunes it), the
     * right side's delays a little longer so the tail is wide. It replaced a
     * single 0.33-second echo, which on a fast sim was a slapback of every
     * note rather than a space around them.
     */
    private val combLen = intArrayOf(1116, 1188, 1277, 1356).map { (it * sampleRate / 44100f).toInt() }.toIntArray()
    private val apLen = intArrayOf(556, 441).map { (it * sampleRate / 44100f).toInt() }.toIntArray()
    private val combL = Array(4) { FloatArray(combLen[it]) }
    private val combR = Array(4) { FloatArray(combLen[it] + STEREO_SPREAD) }
    private val combIdxL = IntArray(4)
    private val combIdxR = IntArray(4)
    private val combLpL = FloatArray(4)
    private val combLpR = FloatArray(4)
    private val apL = Array(2) { FloatArray(apLen[it]) }
    private val apR = Array(2) { FloatArray(apLen[it] + STEREO_SPREAD) }
    private val apIdxL = IntArray(2)
    private val apIdxR = IntArray(2)

    private val lock = Any()

    /**
     * False when this device would not give us an audio track.
     *
     * Failure was silent: the synth set running = false and returned, and
     * nothing in the app ever said the sound was not running — the volume
     * slider and all four sound sliders stayed bright and draggable.
     */
    @Volatile
    var available: Boolean = true
        private set

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
        // Guarded, because build() throws UnsupportedOperationException when a
        // device or an audio route will not give the parameters asked for —
        // and PERFORMANCE_MODE_LOW_LATENCY below is exactly the sort of request
        // that gets refused. This is called from onResume, so an unguarded
        // throw here was a crash on every resume on an affected phone.
        val t = try {
            AudioTrack.Builder()
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
        } catch (e: Throwable) {
            available = false
            return
        }
        // A track can build and still be unusable.
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { t.release() }
            available = false
            return
        }
        available = true
        track = t
        running = true
        try {
            t.play()
        } catch (e: IllegalStateException) {
            running = false
            track = null
            available = false
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
        for (c in combL) java.util.Arrays.fill(c, 0f)
        for (c in combR) java.util.Arrays.fill(c, 0f)
        for (a in apL) java.util.Arrays.fill(a, 0f)
        for (a in apR) java.util.Arrays.fill(a, 0f)
        combLpL.fill(0f); combLpR.fill(0f)
    }

    /**
     * Play one note. Safe to call from the sim thread; the audio thread picks it up
     * on its next block.
     */
    fun note(hz: Float, amplitude: Float, pan: Float, type: VoiceType, decaySeconds: Float) {
        if (muted) return
        // coerceIn does not rescue a NaN, and one NaN voice poisons the mix and
        // the reverb tail for the rest of the session, so it stops here.
        if (!hz.isFinite() || !amplitude.isFinite() || !pan.isFinite()) return
        val f = hz.coerceIn(20f, 8000f)
        synchronized(lock) {
            val v = allocate()
            v.active = true
            v.type = type
            v.phase = 0f
            v.phaseInc = f / sampleRate
            v.amp = amplitude.coerceIn(0f, 1f)
            v.attackLeft = attackSamples
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
                // A soft mallet, not a hard one. Raw white noise excites every
                // harmonic equally, and measured against the other voices it put
                // the pluck's energy near 3 kHz — three times as bright as the
                // 8-bit preset that was the one that sounded right. Smoothing
                // the excitation is what a felt mallet does to a bar.
                for (pass in 0 until PLUCK_SOFTEN) {
                    var prev = buf[len - 1]
                    val first = buf[0]
                    for (i in 0 until len) {
                        val next = if (i + 1 < len) buf[i + 1] else first
                        val here = buf[i]
                        buf[i] = (prev + 2f * here + next) * 0.25f
                        prev = here
                    }
                }
                // Smoothing takes level with it; put the peak back at one.
                var peak = 1e-6f
                for (i in 0 until len) peak = max(peak, abs(buf[i]))
                for (i in 0 until len) buf[i] /= peak
            }
            v.clickLp = 0f
            v.phase2 = 0.25f
            v.hp = 0f
            when (type) {
                VoiceType.KEYS -> {
                    v.modInc = f / sampleRate
                    v.modEnv = 1f
                    v.modDecay = exp(-1f / (KEYS_TINE_SECONDS * sampleRate))
                }
                VoiceType.KICK -> {
                    v.modEnv = 1f
                    v.modDecay = exp(-1f / (KICK_DROP_SECONDS * sampleRate))
                }
                VoiceType.SNARE -> {
                    v.modInc = SNARE_SHELL_HZ / sampleRate
                    v.modEnv = 1f
                    v.modDecay = exp(-1f / (SNARE_SHELL_SECONDS * sampleRate))
                }
                else -> v.modEnv = 0f
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

    /**
     * Render a block without an output device.
     *
     * The offline exporter drives this directly, so a saved video carries exactly
     * the sound the run produced rather than a recording of the speaker.
     */
    fun renderOffline(out: FloatArray, frames: Int) = render(out, frames)

    private fun comb(buf: FloatArray, idx: IntArray, lp: FloatArray, c: Int, input: Float): Float {
        val i = idx[c]
        val y = buf[i]
        lp[c] = y * (1f - COMB_DAMP) + lp[c] * COMB_DAMP
        buf[i] = input + lp[c] * COMB_FEEDBACK
        idx[c] = if (i + 1 >= buf.size) 0 else i + 1
        return y
    }

    private fun allpass(buf: FloatArray, idx: IntArray, k: Int, input: Float): Float {
        val i = idx[k]
        val b = buf[i]
        val y = b - input
        buf[i] = input + b * 0.5f
        idx[k] = if (i + 1 >= buf.size) 0 else i + 1
        return y
    }

    /** A voice whose envelope is no longer a number can never decay away. */
    private fun Voice.isSane(): Boolean = amp.isFinite() && phaseInc.isFinite()

    private fun render(out: FloatArray, frames: Int) {
        java.util.Arrays.fill(out, 0f)
        val vol = if (muted) 0f else volume.coerceIn(0f, 1f)
        synchronized(lock) {
            for (v in voices) {
                if (!v.active) continue
                if (!v.isSane()) {
                    v.active = false
                    continue
                }
                renderVoice(v, out, frames, vol)
            }
        }
        // The room: each side's dry signal into the combs, their sum through
        // the all-passes, mixed back under the dry by [reverbAmount].
        val rv = reverbAmount.coerceIn(0f, 0.7f)
        if (rv > 0.001f) {
            val wet = rv * WET_GAIN
            for (i in 0 until frames) {
                val inL = out[i * 2] * COMB_INPUT
                val inR = out[i * 2 + 1] * COMB_INPUT
                var sl = 0f; var sr = 0f
                for (c in 0 until 4) {
                    sl += comb(combL[c], combIdxL, combLpL, c, inL)
                    sr += comb(combR[c], combIdxR, combLpR, c, inR)
                }
                for (k in 0 until 2) {
                    sl = allpass(apL[k], apIdxL, k, sl)
                    sr = allpass(apR[k], apIdxR, k, sr)
                }
                out[i * 2] += sl * wet
                out[i * 2 + 1] += sr * wet
            }
        }
        // One-pole low-pass on the mix.
        //
        // The square, click and noise voices are generated point by point with
        // no band limiting, so the harmonics they put above half the sample
        // rate fold back down as inharmonic fizz that follows the pitch around.
        // This is the cheapest thing that takes the edge off without dulling
        // the notes: six kilohertz leaves every fundamental and the first few
        // harmonics of all of them untouched.
        val k = LOWPASS_K
        for (i in 0 until frames) {
            lowL += (out[i * 2] - lowL) * k
            lowR += (out[i * 2 + 1] - lowR) * k
            out[i * 2] = lowL
            out[i * 2 + 1] = lowR
        }

        level(out, frames, vol)

        // Soft limiter: keeps a wall of events from clipping.
        for (i in 0 until frames * 2) {
            val x = out[i]
            out[i] = if (x > 0.85f || x < -0.85f) {
                (0.85f + kotlin.math.tanh((abs(x) - 0.85f) * 3f) * 0.14f) * (if (x < 0) -1f else 1f)
            } else x
        }
    }

    /**
     * A slow leveller, so one sim is not five times louder than the next.
     *
     * Measured at 8-bit over every sim, the quietest ran at a sixteenth of the
     * loudest's level: swiping from the Lorenz attractor to bubble sort was a
     * jolt. This follows the mix over a couple of seconds and leans it toward
     * one level, by at most a factor of two either way, so a sim keeps its own
     * character — a sparse one stays sparser than a dense one — and a payoff
     * still lands, because nothing here moves faster than about a second.
     *
     * Measured before the master volume, so it never undoes the volume slider,
     * and frozen through silence, so a pause is not met by a swell of gain
     * when the next note comes.
     */
    private fun level(out: FloatArray, frames: Int, vol: Float) {
        if (frames <= 0 || vol <= 0.001f) return
        var sum = 0.0
        for (i in 0 until frames * 2) {
            val x = out[i]
            sum += (x * x).toDouble()
        }
        val power = sum / (frames * 2) / (vol * vol)
        val seconds = frames.toDouble() / sampleRate
        if (power > SILENCE_POWER) {
            if (levelPower < 0.0) levelPower = power
            val a = 1.0 - kotlin.math.exp(-seconds / LEVEL_SECONDS)
            levelPower += (power - levelPower) * a
        }
        val target = if (levelPower > SILENCE_POWER) {
            (LEVEL_TARGET / kotlin.math.sqrt(levelPower)).toFloat().coerceIn(LEVEL_MIN_GAIN, LEVEL_MAX_GAIN)
        } else levelGain
        // Down faster than up: a sudden wall of notes is tamed within a
        // second, a quiet stretch is brought up over a few.
        val tc = if (target < levelGain) GAIN_DOWN_SECONDS else GAIN_UP_SECONDS
        val next = levelGain + (target - levelGain) * (1f - kotlin.math.exp(-seconds / tc).toFloat())
        val from = levelGain
        for (i in 0 until frames) {
            val g = from + (next - from) * (i + 1) / frames
            out[i * 2] *= g
            out[i * 2 + 1] *= g
        }
        levelGain = next
    }

    private fun renderVoice(v: Voice, out: FloatArray, frames: Int, vol: Float) {
        var amp = v.amp
        var attackLeft = v.attackLeft
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
                // Softened from a hard +/-0.55: a square's harmonics fall away
                // slowly, so at equal amplitude it is much the brightest voice
                // in the set. Rounding the corners costs nothing and keeps the
                // squareness audible.
                VoiceType.SQUARE -> {
                    val p = phase % 1f
                    val raw = if (p < 0.5f) 1f else -1f
                    val edge = min(min(p, abs(p - 0.5f)), 1f - p) * 40f
                    raw * 0.42f * min(1f, edge)
                }
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
                    // Filtered, so a tick is a tap on wood rather than a burst
                    // of hiss: raw noise measured at 7.4 kHz of brightness.
                    v.clickLp += (n - v.clickLp) * CLICK_LP
                    v.clickLp * CLICK_GAIN * (if (v.age < CLICK_SAMPLES) 1f else 0f)
                }
                VoiceType.NOISE -> {
                    seed = seed * 1103515245 + 12345
                    ((seed shr 16) and 0x7FFF) / 16384f - 1f
                }
                VoiceType.KEYS -> {
                    // Frequency modulation at the note's own pitch, the index
                    // falling away fast: a struck tine, then a round tone, and
                    // a quiet octave on top for the bark.
                    val m = sin(modPhase * 2f * PI.toFloat())
                    modPhase += v.modInc
                    val idx = 1.4f * v.modEnv
                    v.modEnv *= v.modDecay
                    sin((phase + m * idx * 0.25f) * 2f * PI.toFloat()) * 0.85f +
                        sin(phase * 4f * PI.toFloat()) * 0.12f * v.modEnv
                }
                VoiceType.WARM -> {
                    val p1 = phase % 1f
                    val p2 = v.phase2 % 1f
                    v.phase2 += inc * WARM_DETUNE
                    if (v.phase2 > 1e6f) v.phase2 -= 1e6f
                    val t1 = if (p1 < 0.5f) 4f * p1 - 1f else 3f - 4f * p1
                    val t2 = if (p2 < 0.5f) 4f * p2 - 1f else 3f - 4f * p2
                    (t1 + t2) * 0.5f
                }
                VoiceType.KICK -> {
                    // The pitch starts a couple of octaves up and drops to the
                    // note: that drop is the thump.
                    val sweep = 1f + KICK_SWEEP * v.modEnv
                    v.modEnv *= v.modDecay
                    phase += inc * (sweep - 1f)
                    sin(phase * 2f * PI.toFloat())
                }
                VoiceType.SNARE -> {
                    seed = seed * 1103515245 + 12345
                    val n = ((seed shr 16) and 0x7FFF) / 16384f - 1f
                    v.hp += (n - v.hp) * 0.45f
                    val shell = sin(modPhase * 2f * PI.toFloat()) * v.modEnv
                    modPhase += v.modInc
                    v.modEnv *= v.modDecay
                    v.hp * 0.75f + shell * 0.6f
                }
                VoiceType.HAT -> {
                    seed = seed * 1103515245 + 12345
                    val n = ((seed shr 16) and 0x7FFF) / 16384f - 1f
                    v.hp += (n - v.hp) * 0.35f
                    (n - v.hp) * 1.4f
                }
            }
            phase += inc
            if (phase > 1e6f) phase -= 1e6f
            // Ramped in over a few milliseconds rather than starting at full
            // height, which is what stops each note beginning with a click.
            val env = if (attackLeft > 0) {
                attackLeft--
                1f - attackLeft.toFloat() / attackSamples
            } else {
                1f
            }
            val value = s * amp * env
            out[i * 2] += value * gainL
            out[i * 2 + 1] += value * gainR
            amp *= v.decay
            v.age++
        }
        v.amp = amp
        v.attackLeft = attackLeft
        v.phase = phase
        v.modPhase = modPhase
        v.noiseState = seed
        // A click is five milliseconds of noise and then nothing, but its
        // envelope kept decaying for as long as the preset said — holding a
        // voice that made no sound, and one the allocator preferred to keep
        // because it was the quietest.
        if (v.type == VoiceType.CLICK && v.age >= CLICK_SAMPLES) v.active = false
    }

    /** Number of voices currently sounding, for the UI's load indicator. */
    fun activeVoices(): Int {
        var n = 0
        synchronized(lock) { for (v in voices) if (v.active) n++ }
        return min(n, MAX_VOICES)
    }
}
