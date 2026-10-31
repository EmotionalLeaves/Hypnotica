package app.hypnotica.pilot

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Reading the screen the way a person does: a screenshot, and the words in
 * it found by text recognition, each with where it sits.
 *
 * For screens whose buttons the accessibility tree doesn't describe — some
 * of YouTube's are drawn without labels — this is how auto-tap still finds
 * "Next" and "Upload Short" and taps them, with no help. The recogniser runs
 * on the phone, through Google Play services.
 */
class ScreenReader(private val service: AccessibilityService) {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** One line of words on screen and its box, in screen pixels. */
    class Line(val text: String, val box: Rect)

    val available: Boolean get() = Build.VERSION.SDK_INT >= 30

    /** Read the screen; [done] hears the lines found, empty if it couldn't. */
    fun read(done: (List<Line>) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { done(emptyList()); return }
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(shot: AccessibilityService.ScreenshotResult) {
                    val buffer = shot.hardwareBuffer
                    val bitmap = try {
                        Bitmap.wrapHardwareBuffer(buffer, shot.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (e: Exception) {
                        null
                    } finally {
                        buffer.close()
                    }
                    if (bitmap == null) { done(emptyList()); return }
                    recognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { text ->
                            val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { l ->
                                l.boundingBox?.let { Line(l.text.trim(), it) }
                            }
                            bitmap.recycle()
                            done(lines)
                        }
                        .addOnFailureListener { bitmap.recycle(); done(emptyList()) }
                }

                override fun onFailure(errorCode: Int) { done(emptyList()) }
            })
        } catch (e: Exception) {
            done(emptyList())
        }
    }

    fun close() {
        try { recognizer.close() } catch (e: Exception) {}
    }
}
