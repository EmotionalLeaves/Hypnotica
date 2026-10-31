package app.hypnotica.export

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.coroutines.resume

/**
 * A spoken line for a Short, from the phone's own text-to-speech.
 *
 * Synthesised to a WAV file, read back and turned into the export's sound
 * format (44.1 kHz, stereo, 16-bit), so the line can be laid into the video
 * at any moment. A phone without a voice installed gets no line, not an
 * error: the Short is still made, just without the voice.
 */
class Voice(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false

    /** Start the engine; false if the phone has no voice to give. */
    suspend fun open(): Boolean {
        if (ready) return true
        val ok = withTimeoutOrNull(OPEN_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                var engine: TextToSpeech? = null
                engine = TextToSpeech(context.applicationContext) { status ->
                    if (cont.isActive) cont.resume(status == TextToSpeech.SUCCESS)
                }
                tts = engine
            }
        } ?: false
        if (!ok) { close(); return false }
        tts?.language = Locale.US
        // A touch quicker than the default: a Short's lines are read fast.
        tts?.setSpeechRate(1.08f)
        ready = true
        return true
    }

    /** [text] spoken, as 44.1 kHz stereo samples, or null if it couldn't be made. */
    suspend fun say(text: String): ShortArray? {
        val engine = tts ?: return null
        if (!ready) return null
        val file = File(context.cacheDir, "line-${System.nanoTime()}.wav")
        val id = "line${System.nanoTime()}"
        val done = withTimeoutOrNull(SAY_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(true) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(false) }
                    override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id && cont.isActive) cont.resume(false) }
                })
                val result = engine.synthesizeToFile(text, Bundle(), file, id)
                if (result != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
            }
        } ?: false
        return try {
            if (done && file.exists()) toExportFormat(file.readBytes()) else null
        } catch (e: Exception) {
            null
        } finally {
            file.delete()
        }
    }

    fun close() {
        tts?.shutdown()
        tts = null
        ready = false
    }

    companion object {
        private const val OPEN_TIMEOUT_MS = 6000L
        private const val SAY_TIMEOUT_MS = 10_000L
        private const val OUT_RATE = 44100

        /**
         * A 16-bit PCM WAV, any rate, mono or stereo, as 44.1 kHz stereo:
         * resampled by straight-line interpolation, which for speech at 16 to
         * 24 kHz going up is clean.
         */
        fun toExportFormat(wav: ByteArray): ShortArray? {
            val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
            if (wav.size < 44 || String(wav, 0, 4, kotlin.text.Charsets.US_ASCII) != "RIFF") return null
            var pos = 12
            var channels = 1
            var rate = 22050
            var bits = 16
            var dataAt = -1
            var dataLen = 0
            while (pos + 8 <= wav.size) {
                val id = String(wav, pos, 4, kotlin.text.Charsets.US_ASCII)
                val len = b.getInt(pos + 4)
                if (id == "fmt ") {
                    channels = b.getShort(pos + 10).toInt()
                    rate = b.getInt(pos + 12)
                    bits = b.getShort(pos + 22).toInt()
                } else if (id == "data") {
                    dataAt = pos + 8
                    dataLen = minOf(len, wav.size - dataAt)
                    break
                }
                pos += 8 + len + (len and 1)
            }
            if (dataAt < 0 || bits != 16 || channels < 1 || rate <= 0) return null
            val inFrames = dataLen / (2 * channels)
            if (inFrames <= 0) return null
            val mono = FloatArray(inFrames) { f ->
                var sum = 0f
                for (c in 0 until channels) sum += b.getShort(dataAt + (f * channels + c) * 2)
                sum / channels
            }
            val outFrames = (inFrames.toLong() * OUT_RATE / rate).toInt()
            val out = ShortArray(outFrames * 2)
            for (o in 0 until outFrames) {
                val x = o.toDouble() * rate / OUT_RATE
                val i = x.toInt().coerceAtMost(inFrames - 1)
                val j = (i + 1).coerceAtMost(inFrames - 1)
                val v = mono[i] + (mono[j] - mono[i]) * (x - i).toFloat()
                val s = v.toInt().coerceIn(-32768, 32767).toShort()
                out[o * 2] = s
                out[o * 2 + 1] = s
            }
            return out
        }
    }
}
