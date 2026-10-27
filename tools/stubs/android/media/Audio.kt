// Stand-ins for the four android.media classes the synth touches, so the
// local compiler can build core/audio and the harness can render real audio
// offline. They do nothing: the synth's sound is arithmetic, and only its
// output stream is Android's. Never shipped — this directory is not a source
// set of the app; only tools/typecheck.sh and tools/harness.sh read it.
package android.media

class AudioAttributes {
    class Builder {
        fun setUsage(usage: Int) = this
        fun setContentType(type: Int) = this
        fun build() = AudioAttributes()
    }
    companion object {
        const val USAGE_GAME = 14
        const val CONTENT_TYPE_SONIFICATION = 4
    }
}

class AudioFormat {
    class Builder {
        fun setSampleRate(rate: Int) = this
        fun setEncoding(encoding: Int) = this
        fun setChannelMask(mask: Int) = this
        fun build() = AudioFormat()
    }
    companion object {
        const val CHANNEL_OUT_STEREO = 12
        const val ENCODING_PCM_16BIT = 2
    }
}

class AudioManager

class AudioTrack {
    val state: Int = 0
    fun play() {}
    fun pause() {}
    fun flush() {}
    fun stop() {}
    fun release() {}
    fun write(data: ShortArray, offset: Int, size: Int): Int = size

    class Builder {
        fun setAudioAttributes(a: AudioAttributes) = this
        fun setAudioFormat(f: AudioFormat) = this
        fun setBufferSizeInBytes(n: Int) = this
        fun setTransferMode(m: Int) = this
        fun setPerformanceMode(m: Int) = this
        fun build() = AudioTrack()
    }
    companion object {
        const val MODE_STREAM = 1
        const val PERFORMANCE_MODE_LOW_LATENCY = 1
        const val STATE_INITIALIZED = 1
        fun getMinBufferSize(rate: Int, channels: Int, encoding: Int) = 4096
    }
}
