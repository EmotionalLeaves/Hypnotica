package app.hypnotica.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * What happens to a clip after it is rendered.
 *
 * A file in the app's own directory is not much use to anybody, so an export is
 * copied into the phone's Movies folder, where the gallery and every other app
 * can see it. On Android 9 and older, where that needs a storage permission, the
 * file stays put and is handed over through the share sheet instead.
 */
object Publish {

    private const val FOLDER = "Hypnotica"

    data class Outcome(val message: String, val shareable: File?)

    fun publish(context: Context, file: File): Outcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Outcome("Saved. Tap Share to send it anywhere.", file)
        }
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/$FOLDER")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return Outcome("Saved. Tap Share to send it anywhere.", file)
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            } ?: return Outcome("Saved. Tap Share to send it anywhere.", file)
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Outcome("In your gallery, under Movies/$FOLDER.", file)
        } catch (e: Throwable) {
            Outcome("Saved. Tap Share to send it anywhere.", file)
        }
    }

    /** Hand the file to whatever the person wants to do with it. */
    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share clip"))
    }

    /**
     * Keep the newest few exports and delete the rest. Every export left a
     * private copy behind — up to eighty megabytes each, beside the one in the
     * gallery — and nothing ever removed them.
     */
    fun prune(dir: File, keep: Int = KEEP_EXPORTS) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") } ?: return
        files.sortedByDescending { it.lastModified() }.drop(keep).forEach { runCatching { it.delete() } }
    }

    const val KEEP_EXPORTS = 4
}
