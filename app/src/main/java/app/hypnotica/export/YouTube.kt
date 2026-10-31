package app.hypnotica.export

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Posting Shorts to the person's own YouTube channel.
 *
 * Sign-in goes through Google Play services' authorization client: one tap,
 * the account chooser, a consent screen, and an access token for this app.
 * Nothing secret ships in the APK — Google recognises the app by its package
 * name and the certificate it is signed with, which have to be registered
 * once as an Android OAuth client in a Google Cloud project with the YouTube
 * Data API turned on. [setupFacts] gives the two values to paste.
 *
 * Uploads use the YouTube Data API's resumable protocol: a POST with the
 * video's details opens a session, and the file goes up in one PUT, picked
 * up from where it stopped if the connection drops.
 */
object YouTube {

    const val SCOPE_UPLOAD = "https://www.googleapis.com/auth/youtube.upload"
    const val SCOPE_READ = "https://www.googleapis.com/auth/youtube.readonly"

    private const val INSERT_URL =
        "https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status"
    private const val CHANNEL_URL =
        "https://www.googleapis.com/youtube/v3/channels?part=snippet&mine=true"

    fun request(): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(SCOPE_UPLOAD), Scope(SCOPE_READ)))
        .build()

    /**
     * Ask Play services for a token. Already granted, it comes straight back;
     * otherwise the result carries a screen to show ([AuthorizationResult.hasResolution]).
     */
    suspend fun authorize(context: Context): AuthorizationResult =
        suspendCancellableCoroutine { cont ->
            Identity.getAuthorizationClient(context).authorize(request())
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

    /** A failure from sign-in, in words, with the likely fix. */
    fun explain(e: Throwable): String {
        val code = (e as? ApiException)?.statusCode
        return when (code) {
            10 -> "Google doesn't recognise this app yet. Register it once — the steps are under \"Set up\" below."
            12501, 16 -> "Sign-in was cancelled."
            7 -> "No connection to Google. Check the internet and try again."
            17 -> "Google Play services can't do this sign-in on this phone."
            else -> "Couldn't connect: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** The channel the token belongs to, or null if it can't be read. */
    fun channelName(token: String): String? = try {
        val c = (URL(CHANNEL_URL).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 15_000; readTimeout = 15_000
        }
        if (c.responseCode != 200) null
        else {
            val body = c.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).optJSONArray("items")?.optJSONObject(0)
                ?.optJSONObject("snippet")?.optString("title")?.takeIf { it.isNotBlank() }
        }
    } catch (e: Exception) {
        null
    }

    /** What a video is called and how it is shown. */
    class Details(
        val title: String,
        val description: String,
        val tags: List<String>,
        /** "private", "unlisted" or "public". */
        val privacy: String,
        /** 28 is Science & Technology. */
        val categoryId: String = "28"
    )

    /** An upload refused by YouTube, with its reason when it gave one. */
    class UploadError(message: String, val reason: String?) : IOException(message)

    /**
     * Upload [file] and return the new video's id. [progress] hears the share
     * sent so far; [cancelled] is checked between chunks.
     */
    fun upload(
        token: String,
        file: File,
        details: Details,
        progress: (Float) -> Unit,
        cancelled: () -> Boolean
    ): String {
        val total = file.length()
        val session = openSession(token, total, details)
        var sent = 0L
        var attempt = 0
        while (true) {
            if (cancelled()) throw InterruptedException("Upload cancelled")
            try {
                return send(token, session, file, sent, total, progress, cancelled)
            } catch (e: UploadError) {
                throw e
            } catch (e: IOException) {
                // Dropped part-way: ask how much arrived and go on from there.
                if (++attempt > MAX_RESUMES) throw e
                Thread.sleep(1000L * attempt)
                sent = received(token, session, total)
                if (sent < 0) throw e
            }
        }
    }

    private fun openSession(token: String, total: Long, d: Details): String {
        val meta = JSONObject()
            .put("snippet", JSONObject()
                .put("title", d.title.take(100))
                .put("description", d.description.take(4900))
                .put("tags", JSONArray(d.tags))
                .put("categoryId", d.categoryId))
            .put("status", JSONObject()
                .put("privacyStatus", d.privacy)
                .put("selfDeclaredMadeForKids", false))
        val bytes = meta.toString().toByteArray(kotlin.text.Charsets.UTF_8)
        val c = (URL(INSERT_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000; readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("X-Upload-Content-Type", "video/mp4")
            setRequestProperty("X-Upload-Content-Length", total.toString())
            setFixedLengthStreamingMode(bytes.size)
        }
        c.outputStream.use { it.write(bytes) }
        val code = c.responseCode
        if (code != 200) throw refusal(c, code)
        return c.getHeaderField("Location") ?: throw IOException("YouTube didn't open an upload session.")
    }

    private fun send(
        token: String, session: String, file: File, from: Long, total: Long,
        progress: (Float) -> Unit, cancelled: () -> Boolean
    ): String {
        val c = (URL(session).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 20_000; readTimeout = 120_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "video/mp4")
            if (from > 0) setRequestProperty("Content-Range", "bytes $from-${total - 1}/$total")
            setFixedLengthStreamingMode(total - from)
        }
        file.inputStream().use { input ->
            input.skip(from)
            c.outputStream.use { out ->
                val buf = ByteArray(CHUNK)
                var sent = from
                while (true) {
                    if (cancelled()) { c.disconnect(); throw InterruptedException("Upload cancelled") }
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sent += n
                    progress(sent.toFloat() / total.coerceAtLeast(1))
                }
            }
        }
        val code = c.responseCode
        if (code != 200 && code != 201) {
            if (code >= 500) throw IOException("YouTube hiccuped ($code)")
            throw refusal(c, code)
        }
        val body = c.inputStream.bufferedReader().use { it.readText() }
        return JSONObject(body).optString("id").takeIf { it.isNotBlank() }
            ?: throw IOException("YouTube took the video but didn't say where it is.")
    }

    /** How many bytes of the session YouTube already has, or -1 if it can't say. */
    private fun received(token: String, session: String, total: Long): Long = try {
        val c = (URL(session).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Range", "bytes */$total")
            setFixedLengthStreamingMode(0)
        }
        c.outputStream.close()
        when (c.responseCode) {
            308 -> c.getHeaderField("Range")?.substringAfter("-")?.toLongOrNull()?.plus(1) ?: 0L
            else -> -1L
        }
    } catch (e: IOException) {
        -1L
    }

    private fun refusal(c: HttpURLConnection, code: Int): UploadError {
        val body = try { c.errorStream?.bufferedReader()?.use { it.readText() } } catch (e: IOException) { null }
        val error = try { body?.let { JSONObject(it).optJSONObject("error") } } catch (e: Exception) { null }
        val reason = error?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason")
        val message = when (reason) {
            "quotaExceeded" -> "Today's YouTube upload allowance for your Google Cloud project is used up. It resets at midnight Pacific time."
            "uploadLimitExceeded" -> "YouTube says this channel has hit its upload limit for now."
            "youtubeSignupRequired" -> "This Google account doesn't have a YouTube channel yet. Make one on youtube.com first."
            "forbidden", "insufficientPermissions" -> "YouTube refused the upload. Disconnect and connect again."
            else -> when (code) {
                401 -> "The YouTube sign-in ran out. Connect again."
                403 -> "YouTube refused the upload (${reason ?: "403"})."
                else -> "YouTube said no ($code${reason?.let { ", $it" } ?: ""})."
            }
        }
        return UploadError(message, reason)
    }

    /**
     * The two values Google needs to recognise this app: its package name and
     * the SHA-1 of the certificate this copy is signed with, as Cloud Console
     * shows them.
     */
    fun setupFacts(context: Context): Pair<String, String> {
        val pkg = context.packageName
        val sha1 = try {
            val sigs = if (Build.VERSION.SDK_INT >= 28) {
                val info = context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
            }
            val cert = sigs?.firstOrNull()?.toByteArray()
            if (cert == null) "unknown" else MessageDigest.getInstance("SHA-1").digest(cert)
                .joinToString(":") { String.format(java.util.Locale.US, "%02X", it) }
        } catch (e: Exception) {
            "unknown"
        }
        return pkg to sha1
    }

    private const val CHUNK = 256 * 1024
    private const val MAX_RESUMES = 3
}
