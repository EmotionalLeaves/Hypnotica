package app.hypnotica.ui

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The simulation itself, filling whatever space it is given.
 *
 * One GLSurfaceView lives for the whole session; this just re-parents it, so
 * switching screens never tears down the GL context or the running sim.
 */
@Composable
fun SimCanvas(
    surface: SimSurfaceView,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {},
    onDoubleTap: () -> Unit = {},
    onLongPress: () -> Unit = {},
    /** True where gestures frame the shot rather than poke the sim. */
    cameraMode: Boolean = false
) {
    AndroidView(
        modifier = modifier,
        factory = {
            (surface.parent as? ViewGroup)?.removeView(surface)
            surface
        },
        update = {
            it.onTap = onTap
            it.onDoubleTap = onDoubleTap
            it.onLongPress = onLongPress
            it.cameraMode = cameraMode
        }
    )
}
