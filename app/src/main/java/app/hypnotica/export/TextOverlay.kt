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
 * Draws a caption over the rendered frame.
 *
 * Text in OpenGL is a texture like any other: the words are drawn once with the
 * ordinary 2D canvas, uploaded, and then composited as a quad. One bitmap is
 * kept at a time, rebuilt only when the caption changes, which for a Studio clip
 * means a handful of uploads across a whole render.
 */
class TextOverlay {

    private var program = 0
    private var quadVbo = 0
    private var texture = 0
    private var bitmapWidth = 1
    private var bitmapHeight = 1
    private var cached: String? = null
    private var cachedBig = false
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
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        texture = tex[0]
        ready = true
    }

    /**
     * Composite [clip] onto whatever is currently bound, fading in and out over
     * [FADE] seconds so a caption never pops.
     */
    fun draw(clip: TextClip, time: Float, viewWidth: Int, viewHeight: Int) {
        if (!ready || viewWidth <= 0 || viewHeight <= 0) return
        val alpha = fadeAt(clip, time)
        if (alpha <= 0.01f) return
        upload(clip.text, clip.big, viewWidth)

        val halfW = bitmapWidth.toFloat() / viewWidth
        val halfH = bitmapHeight.toFloat() / viewHeight
        // Big captions sit a little above centre, small ones near the bottom.
        val centreY = if (clip.big) 0.18f else -0.74f

        GLES30.glUseProgram(program)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUniform4f(
            GLES30.glGetUniformLocation(program, "uRect"), 0f, centreY, halfW, halfH
        )
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uAlpha"), alpha)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTex"), 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glVertexAttribDivisor(0, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun upload(text: String, big: Boolean, viewWidth: Int) {
        if (cached == text && cachedBig == big) return
        cached = text
        cachedBig = big

        val textSize = (if (big) viewWidth * 0.075f else viewWidth * 0.042f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF2F4F8.toInt()
            this.textSize = textSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val lines = wrap(text, paint, viewWidth * 0.86f)
        val lineHeight = textSize * 1.22f
        val width = min(viewWidth.toFloat(), viewWidth * 0.92f).toInt().coerceAtLeast(8)
        val height = (lineHeight * lines.size + textSize * 0.7f).toInt().coerceAtLeast(8)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // A soft shadow keeps white text readable over a bright simulation.
        paint.setShadowLayer(textSize * 0.28f, 0f, textSize * 0.05f, 0xCC000000.toInt())
        var y = textSize
        for (line in lines) {
            val lineWidth = paint.measureText(line)
            canvas.drawText(line, (width - lineWidth) * 0.5f, y, paint)
            y += lineHeight
        }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE
        )
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmapWidth = width
        bitmapHeight = height
        bitmap.recycle()
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
        GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        ready = false
        cached = null
    }

    companion object {
        const val FADE = 0.35f
        const val MAX_LINES = 3

        /** 0 outside the clip, ramping in and out at its edges. */
        fun fadeAt(clip: TextClip, time: Float): Float {
            if (time < clip.start || time > clip.end) return 0f
            val inPart = ((time - clip.start) / FADE).coerceIn(0f, 1f)
            val outPart = ((clip.end - time) / FADE).coerceIn(0f, 1f)
            return min(inPart, outPart)
        }
    }
}
