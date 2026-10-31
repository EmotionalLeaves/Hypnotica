package app.hypnotica.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils
import app.hypnotica.core.render.SceneRenderer
import app.hypnotica.core.render.Shaders
import app.hypnotica.core.studio.TextClip
import kotlin.math.min

/**
 * Draws words over the rendered frame.
 *
 * Text in OpenGL is a texture like any other: the words are drawn once with the
 * ordinary 2D canvas, uploaded, and then composited as a quad. A handful of
 * textures are kept, keyed by the words and their style, so a caption, a
 * running count and a callout can all be on screen together without each one
 * re-uploading every frame.
 */
class TextOverlay {

    /**
     * How a line of words looks. [plate] is the colour of a rounded plate
     * behind the words (0 for none); [outline] draws a dark edge round each
     * letter, the way words are lettered over busy video.
     */
    enum class Style(val sizeShare: Float, val colour: Int, val plate: Int = 0, val outline: Boolean = false) {
        /** A Studio caption, big, a little above centre. */
        CAPTION(0.075f, 0xFFF2F4F8.toInt()),
        /** A Studio caption, small, near the bottom. */
        SMALL(0.042f, 0xFFF2F4F8.toInt()),
        /** A Short's hook: bold white on the app's violet, like a sticker. */
        HOOK(0.068f, 0xFFFFFFFF.toInt(), plate = 0xF07C5CFF.toInt()),
        /** A Short's smaller lines, on a dark plate. */
        NOTE(0.044f, 0xFFF2F4F8.toInt(), plate = 0xD0101418.toInt()),
        /** A moment called out, as the app pops it: big, yellow, outlined. */
        CALLOUT_BIG(0.1f, 0xFFFFD84D.toInt(), outline = true),
        /** A smaller moment, white, outlined. */
        CALLOUT(0.064f, 0xFFF2F4F8.toInt(), outline = true),
        /** A running number, large, outlined. */
        COUNT(0.115f, 0xFFF2F4F8.toInt(), outline = true),
        /** The label under a running number. */
        LABEL(0.036f, 0xFFD8DEE8.toInt(), outline = true),
        /** The channel's mark, small, in the corner of every Short. */
        BRAND(0.03f, 0xFFF2F4F8.toInt(), plate = 0x80101418.toInt())
    }

    private class Tex(val id: Int, val width: Int, val height: Int, var used: Long)

    private var program = 0
    private var quadVbo = 0
    private val cache = LinkedHashMap<String, Tex>()
    private var clock = 0L
    private var ready = false

    fun prepare() {
        if (ready) return
        program = Shaders.link(Shaders.OVERLAY_VERT, Shaders.OVERLAY_FRAG)
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        quadVbo = ids[0]
        val corners = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val buffer = SceneRenderer.allocFloats(corners.size)
        buffer.put(corners).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER, corners.size * 4, buffer, GLES30.GL_STATIC_DRAW
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        ready = true
    }

    /**
     * Composite [clip] onto whatever is currently bound, fading in and out over
     * [FADE] seconds so a caption never pops.
     */
    fun draw(clip: TextClip, time: Float, viewWidth: Int, viewHeight: Int) {
        val alpha = fadeAt(clip, time)
        if (alpha <= 0.01f) return
        // Big captions sit a little above centre, small ones near the bottom.
        drawText(clip.text, if (clip.big) Style.CAPTION else Style.SMALL, if (clip.big) 0.18f else -0.74f, alpha, 1f, viewWidth, viewHeight)
    }

    /**
     * A caption in a Short's style: the big one as a sticker that pops in a
     * touch large and settles, the small ones on a dark plate.
     */
    fun drawStyled(clip: TextClip, time: Float, viewWidth: Int, viewHeight: Int) {
        val alpha = fadeAt(clip, time)
        if (alpha <= 0.01f) return
        val age = time - clip.start
        val scale = if (age < POP_IN) 0.8f + 0.2f * backOut(age / POP_IN) else 1f
        drawText(
            clip.text, if (clip.big) Style.HOOK else Style.NOTE,
            if (clip.big) 0.2f else -0.22f, alpha, scale, viewWidth, viewHeight
        )
    }

    /** 0 to 1, overshooting a little before it settles. */
    private fun backOut(t: Float): Float {
        val c = 1.7f
        val u = t - 1f
        return 1f + (c + 1f) * u * u * u + c * u * u
    }

    /**
     * [text] in [style], centred across the frame at [centreY] (−1 bottom to
     * 1 top), at [alpha] and [scale].
     */
    fun drawText(text: String, style: Style, centreY: Float, alpha: Float, scale: Float, viewWidth: Int, viewHeight: Int) {
        if (!ready || viewWidth <= 0 || viewHeight <= 0 || alpha <= 0.01f) return
        val tex = texture(text, style, viewWidth)
        val halfW = tex.width.toFloat() / viewWidth * scale
        val halfH = tex.height.toFloat() / viewHeight * scale

        GLES30.glUseProgram(program)
        GLES30.glEnable(GLES30.GL_BLEND)
        // The texture is premultiplied; see OVERLAY_FRAG.
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUniform4f(
            GLES30.glGetUniformLocation(program, "uRect"), 0f, centreY, halfW, halfH
        )
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uAlpha"), alpha.coerceAtMost(1f))
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex.id)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTex"), 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glVertexAttribDivisor(0, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun texture(text: String, style: Style, viewWidth: Int): Tex {
        val key = style.name + "\u0000" + text
        clock++
        cache[key]?.let { it.used = clock; return it }
        // Room for one more: the least recently drawn goes.
        if (cache.size >= MAX_TEXTURES) {
            val oldest = cache.entries.minByOrNull { it.value.used }
            if (oldest != null) {
                GLES30.glDeleteTextures(1, intArrayOf(oldest.value.id), 0)
                cache.remove(oldest.key)
            }
        }
        val textSize = viewWidth * style.sizeShare
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.colour
            this.textSize = textSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val lines = wrap(text, paint, viewWidth * 0.8f)
        val lineHeight = textSize * 1.22f
        val width = min(viewWidth.toFloat(), viewWidth * 0.92f).toInt().coerceAtLeast(8)
        val height = (lineHeight * lines.size + textSize * 0.7f).toInt().coerceAtLeast(8)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (style.plate != 0) {
            // A rounded plate just wider than the longest line.
            val widest = lines.maxOf { paint.measureText(it) }
            val padX = textSize * 0.55f
            val plateW = min(width.toFloat(), widest + 2f * padX)
            val left = (width - plateW) * 0.5f
            val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.plate }
            canvas.drawRoundRect(left, textSize * 0.05f, left + plateW, height - textSize * 0.05f, textSize * 0.5f, textSize * 0.5f, platePaint)
        } else {
            // A soft shadow keeps the words readable over a bright simulation.
            paint.setShadowLayer(textSize * 0.28f, 0f, textSize * 0.05f, 0xCC000000.toInt())
        }
        val edge = if (style.outline) Paint(paint).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = textSize * 0.16f
            strokeJoin = Paint.Join.ROUND
            color = 0xFF0B0D12.toInt()
            clearShadowLayer()
        } else null
        var y = textSize
        for (line in lines) {
            val lineWidth = paint.measureText(line)
            val x = (width - lineWidth) * 0.5f
            edge?.let { canvas.drawText(line, x, y, it) }
            canvas.drawText(line, x, y, paint)
            y += lineHeight
        }

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        val tex = Tex(ids[0], width, height, clock)
        cache[key] = tex
        return tex
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.trim().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return listOf(" ")
        val lines = ArrayList<String>()
        var line = StringBuilder()
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= maxWidth || line.isEmpty()) {
                line = StringBuilder(candidate)
            } else {
                lines.add(line.toString())
                line = StringBuilder(word)
            }
            if (lines.size >= MAX_LINES) break
        }
        if (line.isNotEmpty() && lines.size < MAX_LINES) lines.add(line.toString())
        return lines
    }

    fun release() {
        if (!ready) return
        GLES30.glDeleteProgram(program)
        GLES30.glDeleteBuffers(1, intArrayOf(quadVbo), 0)
        for (t in cache.values) GLES30.glDeleteTextures(1, intArrayOf(t.id), 0)
        cache.clear()
        program = 0
        quadVbo = 0
        ready = false
    }

    companion object {
        const val FADE = 0.35f
        /** Seconds a Short's caption takes to pop in. */
        const val POP_IN = 0.28f
        const val MAX_LINES = 3
        const val MAX_TEXTURES = 8

        /** 0 outside the clip, ramping in and out at its edges. */
        fun fadeAt(clip: TextClip, time: Float): Float {
            if (time < clip.start || time > clip.end) return 0f
            val inPart = ((time - clip.start) / FADE).coerceIn(0f, 1f)
            val outPart = ((clip.end - time) / FADE).coerceIn(0f, 1f)
            return min(inPart, outPart)
        }
    }
}
