package app.hypnotica.pilot

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * Posting a Short through the YouTube app, with the taps done for you.
 *
 * Hypnotica hands the video to the YouTube app the way the share sheet does,
 * and [UploadPilotService] — an accessibility service the person turns on —
 * steps through YouTube's upload screens: past any tip, Next until the
 * details screen, the title typed in, then Upload.
 *
 * Nothing about YouTube's screens is written in here as fixed. Buttons are
 * found by what they say and where they sit; a button that worked is
 * remembered ([Taps]) and tried first next time; one that stops working is
 * forgotten. When the YouTube app updates, every remembered tap is put back
 * on probation. And when it is stuck — a screen it has never seen — it asks
 * for one tap and learns that, so a redesign costs a single tap, once.
 */
object Pilot {

    const val YOUTUBE = "com.google.android.youtube"

    /** What a remembered tap is for. */
    enum class Kind { FORWARD, UPLOAD, DISMISS }

    /** A Short to post: the file, its caption (the title), its description and tags. */
    class Item(val uri: Uri, val title: String, val description: String = "", val tags: List<String> = emptyList())

    sealed class Outcome {
        object Posted : Outcome()
        object Stopped : Outcome()
        class Failed(val why: String) : Outcome()
    }

    /** The upload being stepped through, and where its outcome goes. */
    class Job(val item: Item, val done: CompletableDeferred<Outcome>)

    @Volatile var job: Job? = null
        private set

    /** The service, while the person has it turned on. */
    @Volatile var service: UploadPilotService? = null

    /** What the service is doing, in words, for Settings to show. */
    @Volatile var activity: String = ""

    /**
     * What auto-tap saw and did on its last uploads — each new screen's
     * words, each tap — so a run that went wrong can be copied out of
     * Settings and read. Kept in memory only, the newest few hundred lines.
     */
    private val log = ArrayDeque<String>()
    private val started = android.os.SystemClock.uptimeMillis()

    fun trace(line: String) {
        val t = (android.os.SystemClock.uptimeMillis() - started) / 100 / 10f
        synchronized(log) {
            log.addLast("$t  $line")
            while (log.size > MAX_LOG) log.removeFirst()
        }
    }

    fun traceText(): String = synchronized(log) { log.joinToString("\n") }

    private const val MAX_LOG = 400

    fun isOn(context: Context): Boolean {
        if (service != null) return true
        val flat = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(context, UploadPilotService::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** The installed YouTube app's version, or -1 if it isn't installed. */
    fun youTubeVersion(context: Context): Long = try {
        val info = context.packageManager.getPackageInfo(YOUTUBE, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (e: Exception) {
        -1L
    }

    /**
     * Hand [item] to the YouTube app and wait while the service steps
     * through the upload. Returns when it is posted, stopped or stuck past
     * help.
     */
    suspend fun post(context: Context, item: Item): Outcome {
        trace("post: ${item.title.take(50)}")
        val svc = service ?: return Outcome.Failed("Auto-tap is switched on but Android hasn't started it yet — turn it off and on again in Accessibility.").also { trace("no service") }
        if (youTubeVersion(context) < 0) return Outcome.Failed("The YouTube app isn't installed.")
        Taps.checkVersion(context, youTubeVersion(context))
        val done = CompletableDeferred<Outcome>()
        job = Job(item, done)
        withContext(Dispatchers.Main) { svc.begin() }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, item.uri)
            putExtra(Intent.EXTRA_TITLE, item.title)
            putExtra(Intent.EXTRA_SUBJECT, item.title)
            putExtra(Intent.EXTRA_TEXT, item.description)
            setPackage(YOUTUBE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            job = null
            return Outcome.Failed("The YouTube app won't take videos shared to it.")
        }
        return try {
            withTimeout(ITEM_TIMEOUT_MS + 10_000L) { done.await() }
        } catch (e: TimeoutCancellationException) {
            Outcome.Failed("YouTube's upload didn't finish in time.")
        } finally {
            job = null
            withContext(Dispatchers.Main) { service?.idle() }
        }
    }

    /** Called by the service when the job is over. */
    fun finish(outcome: Outcome) {
        job?.done?.complete(outcome)
    }

    fun stop() {
        finish(Outcome.Stopped)
    }

    const val ITEM_TIMEOUT_MS = 4 * 60_000L
}

/**
 * A button, as the accessibility tree describes it: its view id when YouTube
 * gives one, its words, its kind of view.
 */
data class Sig(
    val id: String?, val text: String?, val desc: String?, val cls: String?,
    /** For a tap the person showed it: words on the screen it was shown on. */
    val ctx: List<String>? = null
) {
    fun toJson(score: Int): JSONObject = JSONObject()
        .put("id", id ?: JSONObject.NULL).put("text", text ?: JSONObject.NULL)
        .put("desc", desc ?: JSONObject.NULL).put("cls", cls ?: JSONObject.NULL).put("score", score)
        .put("ctx", ctx?.let { JSONArray(it) } ?: JSONObject.NULL)

    /** The same button, whatever screen it was learned on. */
    fun sameButton(o: Sig) = id == o.id && text == o.text && desc == o.desc && cls == o.cls

    /** Whether a node with these features is this button. */
    fun matches(nId: String?, nText: String?, nDesc: String?, nCls: String?): Boolean {
        val words = (text != null && text.equals(nText, true)) || (desc != null && desc.equals(nDesc, true))
        return if (id != null) id == nId && (text == null && desc == null || words)
        else words && (cls == null || cls == nCls)
    }

    companion object {
        fun fromJson(o: JSONObject) = Sig(
            o.optString("id").takeIf { !o.isNull("id") && it.isNotEmpty() },
            o.optString("text").takeIf { !o.isNull("text") && it.isNotEmpty() },
            o.optString("desc").takeIf { !o.isNull("desc") && it.isNotEmpty() },
            o.optString("cls").takeIf { !o.isNull("cls") && it.isNotEmpty() },
            o.optJSONArray("ctx")?.let { a -> (0 until a.length()).map { a.getString(it) } }
        )
    }
}

/**
 * The taps that have worked, by what they are for, each with a score: up
 * when tapping it moved YouTube on, down when it didn't. Kept on the phone.
 */
object Taps {
    private const val PREFS = "pilot_taps"
    private const val KEY_VERSION = "youtube_version"
    /** Bumped when what is stored changes meaning: taps learned before are dropped. */
    private const val KEY_SCHEMA = "schema"
    private const val SCHEMA = 2
    private const val LEARNED = 3
    private const val DROP_AT = -2
    private const val CAP = 6

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { p ->
        // The first version learned any tap as "the way on", Volume included.
        if (p.getInt(KEY_SCHEMA, 1) != SCHEMA) p.edit().clear().putInt(KEY_SCHEMA, SCHEMA).apply()
    }

    fun list(context: Context, kind: Pilot.Kind): List<Pair<Sig, Int>> {
        val raw = prefs(context).getString(kind.name, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getJSONObject(it) }.map { Sig.fromJson(it) to it.optInt("score", 0) }
                .sortedByDescending { it.second }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, kind: Pilot.Kind, items: List<Pair<Sig, Int>>) {
        val a = JSONArray()
        items.filter { it.second > DROP_AT }.sortedByDescending { it.second }.take(CAP).forEach { a.put(it.first.toJson(it.second)) }
        prefs(context).edit().putString(kind.name, a.toString()).apply()
    }

    private fun adjust(context: Context, kind: Pilot.Kind, sig: Sig, by: Int, floor: Int = Int.MIN_VALUE) {
        val items = list(context, kind).toMutableList()
        val i = items.indexOfFirst { it.first.sameButton(sig) }
        if (i >= 0) items[i] = sig to maxOf(floor, items[i].second + by) else items.add(sig to maxOf(floor, by))
        save(context, kind, items)
    }

    fun reward(context: Context, kind: Pilot.Kind, sig: Sig) = adjust(context, kind, sig, 1)
    fun punish(context: Context, kind: Pilot.Kind, sig: Sig) = adjust(context, kind, sig, -1)

    /** A tap the person showed it: trusted straight away. */
    fun learn(context: Context, kind: Pilot.Kind, sig: Sig) = adjust(context, kind, sig, LEARNED, floor = LEARNED)

    fun count(context: Context): Int = Pilot.Kind.entries.sumOf { list(context, it).size }

    fun forget(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /**
     * After the YouTube app updates, everything remembered goes back on
     * probation: tried first still, but one failure and it's out.
     */
    fun checkVersion(context: Context, version: Long): Boolean {
        val p = prefs(context)
        val was = p.getLong(KEY_VERSION, -1L)
        if (was == version) return false
        for (kind in Pilot.Kind.entries) save(context, kind, list(context, kind).map { it.first to minOf(it.second, 0) })
        p.edit().putLong(KEY_VERSION, version).apply()
        return was != -1L
    }
}
