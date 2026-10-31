package app.hypnotica.export

import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * Every video the app has saved, in one place: the copies in the gallery
 * (Movies/Hypnotica) and the app's own working copies, so they can be
 * counted and cleared together.
 */
object SavedVideos {

    private const val FOLDER = "Movies/Hypnotica"

    class Summary(val count: Int, val bytes: Long)

    /** The app's own copies: exports, Shorts waiting to be posted, renders in progress. */
    private fun privateFiles(context: Context): List<File> {
        val dirs = listOfNotNull(
            context.getExternalFilesDir(null)?.let { File(it, "exports") },
            context.getExternalFilesDir(null)?.let { File(it, "shorts") },
            context.cacheDir
        )
        return dirs.flatMap { d -> d.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }?.toList() ?: emptyList() }
    }

    /** The gallery copies, with their sizes. */
    private fun galleryVideos(context: Context): List<Pair<Uri, Long>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val out = ArrayList<Pair<Uri, Long>>()
        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.SIZE),
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("$FOLDER%"),
                null
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                while (c.moveToNext()) {
                    out.add(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol)) to c.getLong(sizeCol))
                }
            }
        } catch (e: Exception) {}
        return out
    }

    fun summary(context: Context): Summary {
        val files = privateFiles(context)
        val gallery = galleryVideos(context)
        return Summary(files.size + gallery.size, files.sumOf { it.length() } + gallery.sumOf { it.second })
    }

    /**
     * Delete them all. The app's own files and the gallery copies it still
     * owns go straight away; gallery copies Android no longer counts as the
     * app's (saved before a reinstall, say) need the person's OK, and come
     * back as a request to show — null when there are none.
     */
    fun deleteAll(context: Context): IntentSender? {
        privateFiles(context).forEach { runCatching { it.delete() } }
        val left = ArrayList<Uri>()
        for ((uri, _) in galleryVideos(context)) {
            val gone = try {
                context.contentResolver.delete(uri, null, null) > 0
            } catch (e: java.lang.SecurityException) {
                false
            }
            if (!gone) left.add(uri)
        }
        if (left.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            MediaStore.createDeleteRequest(context.contentResolver, left).intentSender
        } catch (e: Exception) {
            null
        }
    }
}
