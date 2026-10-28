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

    /**
     * Set while a screen's own swipe has the gesture: the feed's swipe to the
     * next sim was also a drag through the sim it was leaving.
     */
    var screenSwiping = false

    /**
     * The system's own touch slop, scaled for the screen: a fixed 18 pixels
     * was a third of it on a dense screen, so a tap with any wobble turned
     * into a drag and was lost.
     */
    private val tapSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    var onDoubleTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null

    /**
     * When true, gestures move the view and never reach the sim.
     *
     * Studio runs in this mode. A poke there looks like it works and then
     * cannot be exported: the file is re-simulated from the seed with no input,
     * and scrubbing wipes the poke out of the preview too.
     */
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

    /**
     * A single tap, held back in case a second one is coming.
     *
     * The first tap used to fire onTap immediately, which made double-tap
     * unreachable wherever the single tap navigates: on the feed the first tap
     * opened the player, Compose rebound onDoubleTap to the player's handler
     * before the second tap landed, and the feed's own double-tap handler was
     * dead code. In TickTock the like worked but the first tap had already
     * hidden the caption — including the line that says double-tap is a thing.
     *
     * Held only when a double-tap handler is actually registered, so a screen
     * with nothing on double-tap keeps responding to taps instantly.
     */
    private var pendingTap: Runnable? = null

    /**
     * Drop a tap that is still waiting to see whether a second one follows.
     *
     * The surface outlives every screen, and the handlers are rebound as the
     * screens change. Without this, a tap on the way out of TickTock could land
     * a quarter of a second later against whatever screen had replaced it.
     */
    fun cancelPendingTap() {
        pendingTap?.let { removeCallbacks(it) }
        pendingTap = null
        lastTapAt = 0L
    }

    private val longPressRunnable = Runnable {
        if (!moved && !pinching) {
            longPressFired = true
            onLongPress?.invoke()
            if (!cameraMode) {
                val (wx, wy) = engine.worldFromScreen(downX, downY)
                engine.onTouch(TouchEvent(TouchEvent.Kind.LONG_PRESS, wx, wy))
            }
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
                if (!cameraMode) {
                    val (wx, wy) = engine.worldFromScreen(event.x, event.y)
                    engine.onTouch(TouchEvent(TouchEvent.Kind.DRAG_START, wx, wy))
                }
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
                        engine.userZoomed = true
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
                        // In Studio this is how a shot gets framed before it is
                        // keyed, so the timeline stops driving the view.
                        engine.poseCamera()
                        lastPinchAngle = angle
                    }

                    val cx = (event.getX(0) + event.getX(1)) * 0.5f
                    val cy = (event.getY(0) + event.getY(1)) * 0.5f
                    panBy(cx - lastPanX, cy - lastPanY)
                    lastPanX = cx
                    lastPanY = cy
                } else {
                    if (abs(event.x - downX) > tapSlop || abs(event.y - downY) > tapSlop) {
                        if (!moved) removeCallbacks(longPressRunnable)
                        moved = true
                    }
                    if (moved) {
                        if (cameraMode) {
                            panBy(event.x - lastPanX, event.y - lastPanY)
                        } else if (!screenSwiping) {
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
                    val double = onDoubleTap
                    if (now - lastTapAt < DOUBLE_TAP_MS && double != null) {
                        lastTapAt = 0L
                        pendingTap?.let { removeCallbacks(it) }
                        pendingTap = null
                        double.invoke()
                    } else {
                        lastTapAt = now
                        val fire = Runnable {
                            pendingTap = null
                            if (!cameraMode) {
                                engine.onTouch(TouchEvent(TouchEvent.Kind.TAP, wx, wy))
                            }
                            onTap?.invoke()
                        }
                        if (double == null) {
                            fire.run()
                        } else {
                            pendingTap?.let { removeCallbacks(it) }
                            pendingTap = fire
                            postDelayed(fire, DOUBLE_TAP_MS)
                        }
                    }
                } else if (!cameraMode) {
                    engine.onTouch(TouchEvent(TouchEvent.Kind.DRAG_END, wx, wy))
                }
                pinching = false
                moved = false
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                pendingTap?.let { removeCallbacks(it) }
                pendingTap = null
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
        val worldPerPixel = 2f / (height * engine.viewScale())
        // Along the screen, not along the world: after a twist, a sideways drag
        // used to send the view off at the camera's angle instead.
        val dx = -dxPixels * worldPerPixel
        val dy = dyPixels * worldPerPixel
        val c = kotlin.math.cos(engine.camera.rotation)
        val s = kotlin.math.sin(engine.camera.rotation)
        engine.camera.x += dx * c - dy * s
        engine.camera.y += dx * s + dy * c
        engine.poseCamera()
    }

    private fun pinchDistance(e: MotionEvent): Float =
        hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    companion object {
        /** A twist under this is pinch wobble, not an attempt to rotate. */
        const val ROTATE_SLOP = 0.012f
        const val DOUBLE_TAP_MS = 280L
        const val TWO_PI = (Math.PI * 2.0).toFloat()

    }
}
