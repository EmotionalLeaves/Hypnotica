package app.hypnotica.core.render

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turns a [G] draw list into GL calls: at most four passes a frame no matter how
 * many objects are on screen.
 */
class SceneRenderer {

    private var shapeProgram = 0
    private var pointProgram = 0
    private var fadeProgram = 0
    private var presentProgram = 0

    // Uniform locations, looked up once per context. They were being asked for
    // by name on every draw: eleven string lookups a frame, sixty times a
    // second, for numbers that never move while a program lives.
    private var uShapeCam = -1
    private var uShapeCamRot = -1
    private var uShapePpw = -1
    private var uPointCam = -1
    private var uPointCamRot = -1
    private var uPointPpw = -1
    private var uFadeAmount = -1
    private var uFadeTex = -1
    private var uPresentVignette = -1
    private var uPresentFlash = -1
    private var uPresentBg = -1
    private var uPresentTex = -1

    private var quadVbo = 0
    private var instanceVbo = 0
    private var pointPosVbo = 0
    private var pointColVbo = 0

    private val fbo = IntArray(2)
    private val fboTex = IntArray(2)
    private var current = 0
    private var fboWidth = 0
    private var fboHeight = 0

    private var instanceBuffer: FloatBuffer = allocFloats(4096 * G.STRIDE)
    private var pointPosBuffer: FloatBuffer = allocFloats(16384 * 3)
    private var pointColBuffer: ByteBuffer = allocBytes(16384 * 4)

    var viewportWidth = 1
        private set
    var viewportHeight = 1
        private set

    /** 0 = no trails, 0.95 = long smeary trails. */
    var trails = 0f

    var vignette = 0.35f

    var ready = false
        private set

    fun surfaceCreated() {
        // Called again on a fresh context, but anything already made here would
        // be orphaned if it is ever called twice on a live one.
        if (ready) release()
        shapeProgram = Shaders.link(Shaders.SHAPE_VERT, Shaders.SHAPE_FRAG)
        pointProgram = Shaders.link(Shaders.POINT_VERT, Shaders.POINT_FRAG)
        fadeProgram = Shaders.link(Shaders.QUAD_VERT, Shaders.FADE_FRAG)
        presentProgram = Shaders.link(Shaders.QUAD_VERT, Shaders.PRESENT_FRAG)

        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val ids = IntArray(4)
        GLES30.glGenBuffers(4, ids, 0)
        quadVbo = ids[0]; instanceVbo = ids[1]; pointPosVbo = ids[2]; pointColVbo = ids[3]

        val qb = allocFloats(quad.size)
        qb.put(quad).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, qb, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        ready = shapeProgram != 0 && pointProgram != 0 && fadeProgram != 0 && presentProgram != 0
        if (ready) resolveUniforms()
        fboWidth = 0
        fboHeight = 0
    }

    private fun resolveUniforms() {
        fun at(program: Int, name: String) = GLES30.glGetUniformLocation(program, name)
        uShapeCam = at(shapeProgram, "uCam")
        uShapeCamRot = at(shapeProgram, "uCamRot")
        uShapePpw = at(shapeProgram, "uPixelsPerWorld")
        uPointCam = at(pointProgram, "uCam")
        uPointCamRot = at(pointProgram, "uCamRot")
        uPointPpw = at(pointProgram, "uPixelsPerWorld")
        uFadeAmount = at(fadeProgram, "uFade")
        uFadeTex = at(fadeProgram, "uTex")
        uPresentVignette = at(presentProgram, "uVignette")
        uPresentFlash = at(presentProgram, "uFlash")
        uPresentBg = at(presentProgram, "uBackground")
        uPresentTex = at(presentProgram, "uTex")
    }

    fun surfaceChanged(width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        createTargets(viewportWidth, viewportHeight)
    }

    /** True when the trail buffers were made; false means draw straight out. */
    var targetsReady = false
        private set

    private fun createTargets(w: Int, h: Int) {
        if (w == fboWidth && h == fboHeight) return
        releaseTargets()
        targetsReady = false
        GLES30.glGenTextures(2, fboTex, 0)
        GLES30.glGenFramebuffers(2, fbo, 0)
        for (i in 0 until 2) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTex[i])
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
            )
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[i])
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, fboTex[i], 0
            )
            // A framebuffer that is not complete — an oversized texture on a
            // tablet, a format a driver will not attach — renders nothing at
            // all, and silently. Asking is cheap and happens once per resize.
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                android.util.Log.e(
                    Shaders.TAG,
                    "Trail buffer ${w}x$h is not usable (status $status); drawing without trails"
                )
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
                releaseTargets()
                fboWidth = 0
                fboHeight = 0
                return
            }
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        fboWidth = w
        fboHeight = h
        targetsReady = true
    }

    private fun releaseTargets() {
        if (fboWidth != 0) {
            GLES30.glDeleteFramebuffers(2, fbo, 0)
            GLES30.glDeleteTextures(2, fboTex, 0)
            fboWidth = 0
            fboHeight = 0
        }
    }

    /** Draw one frame of [g] through [camera], then present it to the screen. */
    fun render(g: G, camera: Camera, flash: Float) {
        if (!ready) return
        if (fboWidth == 0) createTargets(viewportWidth, viewportHeight)
        // Without usable trail buffers there is nothing to ping-pong between,
        // and binding framebuffer zero here would draw the scene to the screen
        // twice. One black frame beats a corrupt one; the log says why.
        if (!targetsReady) return

        val prev = current
        val next = 1 - current

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[next])
        GLES30.glViewport(0, 0, fboWidth, fboHeight)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        if (trails > 0.001f) {
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glUseProgram(fadeProgram)
            GLES30.glUniform1f(uFadeAmount, trails.coerceIn(0f, 0.985f))
            bindTexture(uFadeTex, fboTex[prev])
            drawFullscreen()
            GLES30.glEnable(GLES30.GL_BLEND)
        }

        val aspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        val scaleY = camera.zoom
        val scaleX = camera.zoom / aspect
        val cr = cos(-camera.rotation)
        val sr = sin(-camera.rotation)
        val pixelsPerWorld = viewportHeight * scaleY * 0.5f

        // Glow underneath, added, so bright things bloom into the dark canvas.
        if (g.glowCount > 0) {
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
            drawShapes(g.glows, g.glowCount, camera, scaleX, scaleY, cr, sr, pixelsPerWorld)
        }

        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        if (g.pointCount > 0) {
            drawPoints(g, camera, scaleX, scaleY, cr, sr, pixelsPerWorld)
        }
        if (g.instanceCount > 0) {
            drawShapes(g.instances, g.instanceCount, camera, scaleX, scaleY, cr, sr, pixelsPerWorld)
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(presentProgram)
        GLES30.glUniform1f(uPresentVignette, vignette)
        GLES30.glUniform1f(uPresentFlash, flash.coerceIn(0f, 1f))
        val bg = g.background
        GLES30.glUniform3f(
            uPresentBg,
            ((bg ushr 16) and 0xFF) / 255f,
            ((bg ushr 8) and 0xFF) / 255f,
            (bg and 0xFF) / 255f
        )
        bindTexture(uPresentTex, fboTex[next])
        drawFullscreen()
        GLES30.glEnable(GLES30.GL_BLEND)

        current = next
    }

    private fun bindTexture(texUniform: Int, tex: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glUniform1i(texUniform, 0)
    }

    private fun drawFullscreen() {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glVertexAttribDivisor(0, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun drawShapes(
        data: FloatArray, count: Int, camera: Camera,
        scaleX: Float, scaleY: Float, cr: Float, sr: Float, pixelsPerWorld: Float
    ) {
        val floats = count * G.STRIDE
        if (instanceBuffer.capacity() < floats) {
            instanceBuffer = allocFloats(floats * 2)
        }
        instanceBuffer.clear()
        instanceBuffer.put(data, 0, floats)
        instanceBuffer.position(0)

        GLES30.glUseProgram(shapeProgram)
        GLES30.glUniform4f(uShapeCam, camera.x, camera.y, scaleX, scaleY)
        GLES30.glUniform2f(uShapeCamRot, cr, sr)
        GLES30.glUniform1f(uShapePpw, pixelsPerWorld)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glVertexAttribDivisor(0, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, floats * 4, instanceBuffer, GLES30.GL_DYNAMIC_DRAW)
        val stride = G.STRIDE * 4
        for (loc in 1..3) {
            GLES30.glEnableVertexAttribArray(loc)
            GLES30.glVertexAttribPointer(loc, 4, GLES30.GL_FLOAT, false, stride, (loc - 1) * 16)
            GLES30.glVertexAttribDivisor(loc, 1)
        }

        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, count)

        for (loc in 1..3) {
            GLES30.glVertexAttribDivisor(loc, 0)
            GLES30.glDisableVertexAttribArray(loc)
        }
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun drawPoints(
        g: G, camera: Camera, scaleX: Float, scaleY: Float,
        cr: Float, sr: Float, pixelsPerWorld: Float
    ) {
        val n = g.pointCount
        if (pointPosBuffer.capacity() < n * 3) pointPosBuffer = allocFloats(n * 6)
        if (pointColBuffer.capacity() < n * 4) pointColBuffer = allocBytes(n * 8)

        pointPosBuffer.clear()
        pointPosBuffer.put(g.pointPos, 0, n * 3)
        pointPosBuffer.position(0)
        pointColBuffer.clear()
        pointColBuffer.put(g.pointColor, 0, n * 4)
        pointColBuffer.position(0)

        GLES30.glUseProgram(pointProgram)
        GLES30.glUniform4f(uPointCam, camera.x, camera.y, scaleX, scaleY)
        GLES30.glUniform2f(uPointCamRot, cr, sr)
        GLES30.glUniform1f(uPointPpw, pixelsPerWorld)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, pointPosVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * 12, pointPosBuffer, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glVertexAttribDivisor(0, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, pointColVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * 4, pointColBuffer, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_UNSIGNED_BYTE, true, 4, 0)
        GLES30.glVertexAttribDivisor(1, 0)

        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, n)

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    fun release() {
        releaseTargets()
        for (p in intArrayOf(shapeProgram, pointProgram, fadeProgram, presentProgram)) {
            if (p != 0) GLES30.glDeleteProgram(p)
        }
        shapeProgram = 0
        pointProgram = 0
        fadeProgram = 0
        presentProgram = 0
        val buffers = intArrayOf(quadVbo, instanceVbo, pointPosVbo, pointColVbo)
        GLES30.glDeleteBuffers(buffers.size, buffers, 0)
        quadVbo = 0
        instanceVbo = 0
        pointPosVbo = 0
        pointColVbo = 0
        // Both of these matter on the way down: render() is gated on ready, and
        // a uniform location belongs to a program that no longer exists.
        resetUniforms()
        ready = false
    }

    private fun resetUniforms() {
        uShapeCam = -1; uShapeCamRot = -1; uShapePpw = -1
        uPointCam = -1; uPointCamRot = -1; uPointPpw = -1
        uFadeAmount = -1; uFadeTex = -1
        uPresentVignette = -1; uPresentFlash = -1; uPresentBg = -1; uPresentTex = -1
    }

    companion object {
        fun allocFloats(n: Int): FloatBuffer =
            ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        fun allocBytes(n: Int): ByteBuffer =
            ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder())
    }
}
