package app.hypnotica.core.render

/**
 * Where the view is looking.
 *
 * The player drags this around, Studio animates it from keyframes, and the
 * offline exporter uses its own copy. No OpenGL here on purpose: the camera is
 * plain data so it can be driven and tested without a device.
 */
class Camera {

    // Written by the gestures on the UI thread and by the timeline on the sim
    // thread, and read every frame by the renderer: volatile so a frame cannot
    // see half of a move.
    @Volatile
    var x = 0f

    @Volatile
    var y = 0f

    /** Visible half-height in world units is 1 / zoom. */
    @Volatile
    var zoom = 1f

    /** Radians, applied about the camera centre. */
    @Volatile
    var rotation = 0f

    fun reset() {
        x = 0f
        y = 0f
        zoom = 1f
        rotation = 0f
    }

    fun copyFrom(other: Camera) {
        x = other.x
        y = other.y
        zoom = other.zoom
        rotation = other.rotation
    }
}
