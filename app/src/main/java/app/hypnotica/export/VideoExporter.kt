package app.hypnotica.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.GLES30
import app.hypnotica.core.audio.SoundMapping
import app.hypnotica.core.audio.SoundPreset
import app.hypnotica.core.audio.Synth
import app.hypnotica.core.laws.Laws
import app.hypnotica.core.render.Camera
import app.hypnotica.core.render.G
import app.hypnotica.core.render.Palette
import app.hypnotica.core.render.SceneRenderer
import app.hypnotica.core.safety.FlashLimiter
import app.hypnotica.core.sim.SimCore
import app.hypnotica.core.sim.SimDef
import app.hypnotica.core.studio.Project
import app.hypnotica.core.studio.TrackKind
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/** Shape of the exported file. */
data class ExportSettings(
    val width: Int = 1080,
    val height: Int = 1920,
    val fps: Int = 60,
    val bitRate: Int = 10_000_000,
    val withAudio: Boolean = true
) {
    companion object {
        val VERTICAL = ExportSettings(1080, 1920, 60, 10_000_000)
        val SQUARE = ExportSettings(1080, 1080, 60, 9_000_000)
        val WIDE = ExportSettings(1920, 1080, 60, 12_000_000)
        val VERTICAL_LIGHT = ExportSettings(720, 1280, 30, 4_000_000)
        val ALL = listOf(VERTICAL, SQUARE, WIDE, VERTICAL_LIGHT)
        val NAMES = listOf("1080x1920 60fps", "1080x1080 60fps", "1920x1080 60fps", "720x1280 30fps")
    }
}

/**
 * Renders a Studio project to an MP4 with its sound.
 *
 * The run is re-simulated from its seed at a fixed timestep, so the file is not
 * a recording of a laggy preview; it is the ideal version of the same run, frame
 * for frame. Both streams are encoded into memory first and muxed at the end,
 * because a muxer has to know every track before it starts.
 */
class VideoExporter(
    private val def: SimDef,
    private val project: Project,
    private val laws: Laws,
    private val paletteName: String,
    private val soundPresetName: String,
    private val params: Map<String, Float>,
    private val settings: ExportSettings = ExportSettings.VERTICAL
) {

    /** 0..1, safe to read from any thread. */
    @Volatile
    var progress: Float = 0f
        private set

    @Volatile
    var cancelled: Boolean = false

    private class Sample(val data: ByteArray, val info: MediaCodec.BufferInfo)

    /**
     * Blocking. Call from a background thread.
     * Returns the written file, or throws with a message worth showing.
     */
    fun export(outFile: File): File {
        val durationSeconds = project.duration.coerceIn(1f, MAX_SECONDS)
        val totalFrames = (durationSeconds * settings.fps).toInt().coerceAtLeast(1)

        val encoder = EncoderSurface(settings.width, settings.height, settings.bitRate, settings.fps)
        val videoSamples = ArrayList<Sample>(totalFrames + 8)
        var videoFormat: MediaFormat? = null

        val audioPcm = if (settings.withAudio) ArrayList<ShortArray>(totalFrames) else null

        try {
            encoder.makeCurrent()

            val scene = SceneRenderer()
            scene.surfaceCreated()
            scene.surfaceChanged(settings.width, settings.height)
            scene.vignette = 0.35f

            val core = SimCore(def, project.seed)
            core.setAspect(settings.width.toFloat() / settings.height.toFloat())
            core.setPalette(Palette.byName(paletteName))
            for ((k, v) in params) core.params.set(k, v)
            core.setLaws(laws)
            core.restart(project.seed)

            val synth = Synth()
            val mapping = SoundMapping(synth)
            mapping.applyPreset(SoundPreset.byName(soundPresetName))

            val camera = Camera()
            val g = G()
            val flash = FlashLimiter()
            val frameSeconds = 1f / settings.fps
            val audioFramesPerVideoFrame = (SAMPLE_RATE / settings.fps)
            val mixBuffer = FloatArray(audioFramesPerVideoFrame * 2)

            for (frame in 0 until totalFrames) {
                if (cancelled) throw InterruptedException("Export cancelled")
                val time = frame * frameSeconds

                // Tracks drive the run rather than the other way round.
                project.applyCamera(time, camera)
                val speed = project.track(TrackKind.SPEED).valueAt(time)
                val frozen = project.isFrozenAt(time)
                core.setLaws(
                    laws.copy(
                        gravity = project.track(TrackKind.GRAVITY).valueAt(time),
                        restitution = project.track(TrackKind.BOUNCINESS).valueAt(time)
                    )
                )
                scene.trails = project.track(TrackKind.TRAILS).valueAt(time)

                if (!frozen) {
                    val steps = max(0, (core.stepHz * frameSeconds * max(0f, speed)).toInt())
                    core.advanceSteps(steps)
                }

                g.reset()
                g.background = Palette.BG
                core.draw(g)

                val wanted = max(g.flashRequest, project.track(TrackKind.FLASH).valueAt(time))
                val allowed = flash.allow(wanted, frameSeconds)

                GLES30.glViewport(0, 0, settings.width, settings.height)
                scene.render(g, camera, allowed)

                if (audioPcm != null) {
                    mapping.pump(core.events, frameSeconds)
                    java.util.Arrays.fill(mixBuffer, 0f)
                    synth.renderOffline(mixBuffer, audioFramesPerVideoFrame)
                    val pcm = ShortArray(audioFramesPerVideoFrame * 2)
                    for (i in pcm.indices) {
                        pcm[i] = (mixBuffer[i].coerceIn(-1f, 1f) * 32000f).toInt().toShort()
                    }
                    audioPcm.add(pcm)
                } else {
                    core.events.clear()
                }

                encoder.present((time * 1_000_000_000.0).toLong())
                videoFormat = drain(encoder.codec, videoSamples, videoFormat, endOfStream = false)
                progress = (frame + 1).toFloat() / totalFrames * 0.75f
            }

            encoder.signalEndOfStream()
            videoFormat = drain(encoder.codec, videoSamples, videoFormat, endOfStream = true)
            scene.release()
        } finally {
            encoder.release()
        }

        val vf = videoFormat ?: throw IllegalStateException("The encoder produced no video")

        var audioFormat: MediaFormat? = null
        val audioSamples = ArrayList<Sample>()
        if (audioPcm != null && audioPcm.isNotEmpty()) {
            val encoded = encodeAudio(audioPcm)
            audioFormat = encoded.first
            audioSamples.addAll(encoded.second)
        }
        progress = 0.9f

        writeMp4(outFile, vf, videoSamples, audioFormat, audioSamples)
        progress = 1f
        return outFile
    }

    /** Pull everything the encoder has ready into memory. */
    private fun drain(
        codec: MediaCodec,
        into: ArrayList<Sample>,
        knownFormat: MediaFormat?,
        endOfStream: Boolean
    ): MediaFormat? {
        var format = knownFormat
        val info = MediaCodec.BufferInfo()
        val timeout = if (endOfStream) 10_000L else 0L
        // Bounded so a stalled encoder ends the export instead of hanging it.
        var spins = if (endOfStream) 600 else 64
        while (spins-- > 0) {
            val index = codec.dequeueOutputBuffer(info, timeout)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return format
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> format = codec.outputFormat
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 &&
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(bytes, 0, info.size)
                        val copy = MediaCodec.BufferInfo()
                        copy.set(0, info.size, info.presentationTimeUs, info.flags)
                        into.add(Sample(bytes, copy))
                    }
                    codec.releaseOutputBuffer(index, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return format
                }
            }
            if (!endOfStream && index < 0) return format
        }
        return format
    }

    private fun encodeAudio(blocks: List<ShortArray>): Pair<MediaFormat?, List<Sample>> {
        val format = MediaFormat.createAudioFormat(AUDIO_MIME, SAMPLE_RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(AUDIO_MIME)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val out = ArrayList<Sample>()
        var outFormat: MediaFormat? = null
        var totalFrames = 0L
        var blockIndex = 0
        var finished = false
        var spins = blocks.size * 4 + 2000

        try {
            while (!finished && spins-- > 0) {
                if (blockIndex <= blocks.size) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val input = codec.getInputBuffer(inIndex)
                        if (input == null) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, 0)
                        } else if (blockIndex == blocks.size) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0,
                                totalFrames * 1_000_000L / SAMPLE_RATE,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            blockIndex++
                        } else {
                            val block = blocks[blockIndex]
                            input.clear()
                            val bytes = min(block.size * 2, input.capacity())
                            val shorts = bytes / 2
                            val bb = ByteBuffer.allocate(shorts * 2).order(java.nio.ByteOrder.nativeOrder())
                            for (i in 0 until shorts) bb.putShort(block[i])
                            bb.flip()
                            input.put(bb)
                            codec.queueInputBuffer(
                                inIndex, 0, shorts * 2,
                                totalFrames * 1_000_000L / SAMPLE_RATE, 0
                            )
                            totalFrames += shorts / 2
                            blockIndex++
                        }
                    }
                }
                val info = MediaCodec.BufferInfo()
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = codec.outputFormat
                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0 &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            val bytes = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.get(bytes, 0, info.size)
                            val copy = MediaCodec.BufferInfo()
                            copy.set(0, info.size, info.presentationTimeUs, info.flags)
                            out.add(Sample(bytes, copy))
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) finished = true
                    }
                }
            }
        } finally {
            try {
                codec.stop()
            } catch (e: Exception) {
                // Nothing useful to do if the audio codec was already broken.
            }
            codec.release()
        }
        return outFormat to out
    }

    private fun writeMp4(
        outFile: File,
        videoFormat: MediaFormat,
        videoSamples: List<Sample>,
        audioFormat: MediaFormat?,
        audioSamples: List<Sample>
    ) {
        outFile.parentFile?.mkdirs()
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val videoTrack = muxer.addTrack(videoFormat)
            val audioTrack = if (audioFormat != null && audioSamples.isNotEmpty()) {
                muxer.addTrack(audioFormat)
            } else -1
            muxer.start()

            var vi = 0
            var ai = 0
            // Interleave by timestamp so players can stream the file.
            while (vi < videoSamples.size || (audioTrack >= 0 && ai < audioSamples.size)) {
                val vTime = if (vi < videoSamples.size) videoSamples[vi].info.presentationTimeUs else Long.MAX_VALUE
                val aTime = if (audioTrack >= 0 && ai < audioSamples.size) {
                    audioSamples[ai].info.presentationTimeUs
                } else Long.MAX_VALUE
                if (vTime <= aTime) {
                    val s = videoSamples[vi++]
                    muxer.writeSampleData(videoTrack, ByteBuffer.wrap(s.data), s.info)
                } else {
                    val s = audioSamples[ai++]
                    muxer.writeSampleData(audioTrack, ByteBuffer.wrap(s.data), s.info)
                }
            }
            muxer.stop()
        } finally {
            muxer.release()
        }
    }

    companion object {
        const val SAMPLE_RATE = 44100
        const val AUDIO_MIME = "audio/mp4a-latm"
        /** Buffered in memory, so the length is capped rather than unbounded. */
        const val MAX_SECONDS = 60f
    }
}
