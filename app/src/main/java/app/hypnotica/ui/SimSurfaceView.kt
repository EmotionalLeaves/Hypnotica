package app.hypnotica.ui

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import app.hypnotica.core.sim.TouchEvent
import app.hypnotica.engine.SimEngine
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The surface the sim draws on.
 *
 * Gestures follow the one-gesture-one-meaning rule: tap toggles the controls,
 * double-tap favourites, long-press opens the drawer, drag goes to the sim,
 * pinch zooms, two-finger drag pans and a two-finger twist rotates.
 */
@SuppressLint("ViewConstructor")
class SimSurfaceView(context: Context, val engine: SimEngine) : GLSurfaceView(context) {

    var onTap: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null

    /** When true, a one-finger drag pans the view instead of reaching the sim. */
    var cameraMode: Boolean = false

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false
    private var pinching = false
    private var lastPinchDist = 0f
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var longPressFired = false
    private var lastPinchAngle = 0f
    private var lastTapAt = 0L

    private val longPressRunnable = Runnable {
        if (!moved && !pinching) {
            longPressFired = true
            onLongPress?.invoke()
            val (wx, wy) = engine.worldFromScreen(downX, downY)
            engine.onTouch(TouchEvent(TouchEvent.Kind.LONG_PRESS, wx, wy))
        }
    }

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(engine)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = System.currentTimeMillis()
                moved = false
                longPressFired = false
                lastPanX = event.x
                lastPanY = event.y
                postDelayed(longPressRunnable, 450)
                val (wx, wy) = engine.worldFromScreen(event.x, event.y)
                engine.onTouch(TouchEvent(TouchEvent.Kind.DRAG_START, wx, wy))
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    pinching = true
                    removeCallbacks(longPressRunnable)
                    lastPinchDist = pinchDistance(event)
                    lastPinchAngle = pinchAngle(event)
                    lastPanX = (event.getX(0) + event.getX(1)) * 0.5f
                    lastPanY = (event.getY(0) + event.getY(1)) * 0.5f
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (pinching && event.pointerCount >= 2) {
                    val d = pinchDistance(event)
                    if (lastPinchDist > 1f) {
                        engine.setBaseZoom(engine.baseZoom() * (d / lastPinchDist))
                    }
                    lastPinchDist = d

                    // Twist to rotate. Small wobble is ignored, so a plain pinch
                    // does not tilt the sim by accident.
                    val angle = pinchAngle(event)
                    var turn = angle - lastPinchAngle
                    while (turn > Math.PI) turn -= TWO_PI
                    while (turn < -Math.PI) turn += TWO_PI
                    if (abs(turn) > ROTATE_SLOP) {
                        engine.camera.rotation += turn
                        lastPinchAngle = angle
                    }

                    val cx = (event.getX(0) + event.getX(1)) * 0.5f
                    val cy = (event.getY(0) + event.getY(1)) * 0.5f
                    panBy(cx - lastPanX, cy - lastPanY)
                    lastPanX = cx
                    lastPanY = cy
                } else {
                    if (abs(event.x - downX) > TAP_SLOP || abs(event.y - downY) > TAP_SLOP) {
                        if (!moved) removeCallbacks(longPressRunnable)
                        moved = true
                    }
                    if (moved) {
                        if (cameraMode) {
                            panBy(event.x - lastPanX, event.y - lastPanY)
                        } else {
                            val (wx, wy) = engine.worldFromScreen(event.x, event.y)
                            engine.onTouch(TouchEvent(TouchEvent.Kind.DRAG, wx, wy))
                        }
                        lastPanX = event.x
                        lastPanY = event.y
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) pinching = false
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                val dur = System.currentTimeMillis() - downTime
                val (wx, wy) = engine.worldFromScreen(event.x, event.y)
                if (!moved && !pinching && !longPressFired && dur < 400) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapAt < DOUBLE_TAP_MS) {
                        lastTapAt = 0L
                        onDoubleTap?.invoke()
                    } else {
                        lastTapAt = now
                        engine.onTouch(TouchEvent(TouchEvent.Kind.TAP, wx, wy))
                        onTap?.invoke()
                    }
                } else {
                    engine.onTouch(TouchEvent(TouchEvent.Kind.DRAG_END, wx, wy))
                }
                pinching = false
                moved = false
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                pinching = false
                moved = false
            }
        }
        return true
    }

    /** Angle between the two pointers, for the twist gesture. */
    private fun pinchAngle(event: MotionEvent): Float =
        atan2(event.getY(1) - event.getY(0), event.getX(1) - event.getX(0))

    private fun panBy(dxPixels: Float, dyPixels: Float) {
        if (height <= 0) return
        val worldPerPixel = 2f / (height * engine.camera.zoom)
        engine.camera.x -= dxPixels * worldPerPixel
        engine.camera.y += dyPixels * worldPerPixel
    }

    private fun pinchDistance(e: MotionEvent): Float =
        hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    companion object {
        /** A twist under this is pinch wobble, not an attempt to rotate. */
        const val ROTATE_SLOP = 0.012f
        const val DOUBLE_TAP_MS = 280L
        const val TWO_PI = (Math.PI * 2.0).toFloat()

        private const val TAP_SLOP = 18f
    }
}
