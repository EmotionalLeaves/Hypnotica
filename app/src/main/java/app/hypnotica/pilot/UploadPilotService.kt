package app.hypnotica.pilot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import app.hypnotica.MainActivity

/**
 * Steps through the YouTube app's upload screens for a Short Hypnotica just
 * handed it. Idle — reading nothing, touching nothing — unless an upload
 * from Hypnotica is in progress ([Pilot.job]).
 *
 * Each tick it reads the YouTube screen and does one thing: closes a tip,
 * types the title, taps Upload, or taps Next. It checks the screen changed
 * after each tap, which is how it knows a tap worked (and remembers it) or
 * didn't (and stops trusting it). Stuck on one screen, it asks the person
 * to tap the button themselves once and learns that tap.
 */
class UploadPilotService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private var startedAt = 0L
    private var progressAt = 0L
    private var titleSet = false
    private var uploadAt = -1L
    private var goneTicks = 0

    private var lastSig: Sig? = null
    private var lastKind: Pilot.Kind? = null
    private var lastPrint = 0
    private var sameTicks = 0

    private var teaching: Pilot.Kind? = null

    // The details screen's other questions, each done once per upload.
    private var descDone = false
    private var tagsDone = false
    private var aiDone = false
    private var kidsDone = false
    private var scrolls = 0

    /** A side screen opened from the details screen, and what it was opened for. */
    private enum class Sub { DESC, TAGS, AI, KIDS }
    private var sub: Sub? = null
    private var subAt = 0L
    private var subActed = false

    override fun onServiceConnected() {
        Pilot.service = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (Pilot.service === this) Pilot.service = null
        Pilot.finish(Pilot.Outcome.Failed("Auto-tap was turned off."))
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    /** A new upload: start reading YouTube's screens. */
    fun begin() {
        startedAt = SystemClock.uptimeMillis()
        progressAt = startedAt
        titleSet = false
        uploadAt = -1L
        goneTicks = 0
        lastSig = null; lastKind = null; lastPrint = 0; sameTicks = 0
        teaching = null
        descDone = false; tagsDone = false; aiDone = false; kidsDone = false; scrolls = 0
        sub = null; subActed = false
        Pilot.activity = "Opening YouTube…"
        if (!running) {
            running = true
            handler.postDelayed(::tick, FIRST_TICK_MS)
        }
    }

    /** Back to doing nothing. */
    fun idle() {
        running = false
        teaching = null
        handler.removeCallbacksAndMessages(null)
        Pilot.activity = ""
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val kind = teaching ?: return
        if (Pilot.job == null) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        if (event.packageName?.toString() != Pilot.YOUTUBE) return
        // The person showed it the button: remember it, and carry on.
        val source = event.source ?: return
        val sig = sigOf(clickable(source) ?: source)
        Taps.learn(this, kind, sig)
        teaching = null
        progressAt = SystemClock.uptimeMillis()
        if (kind == Pilot.Kind.UPLOAD) uploadAt = progressAt
        Pilot.activity = "Learned that tap. Carrying on…"
        Toast.makeText(this, "Got it — Hypnotica will tap that from now on.", Toast.LENGTH_SHORT).show()
    }

    private fun tick() {
        if (!running) return
        if (Pilot.job == null) { idle(); return }
        try {
            step()
        } catch (e: Exception) {
            // A node can vanish between reading and acting; the next tick reads afresh.
        }
        if (running) handler.postDelayed(::tick, TICK_MS)
    }

    private fun step() {
        val now = SystemClock.uptimeMillis()
        if (now - startedAt > Pilot.ITEM_TIMEOUT_MS) { done(Pilot.Outcome.Failed("Stuck in YouTube's upload screens.")); return }
        val root = rootInActiveWindow
        val inYouTube = root?.packageName?.toString() == Pilot.YOUTUBE
        if (!inYouTube) {
            // Upload tapped and YouTube left behind: posted.
            if (uploadAt > 0 && now - uploadAt > DONE_AFTER_MS) { done(Pilot.Outcome.Posted); return }
            if (uploadAt < 0 && now - startedAt > OPEN_TIMEOUT_MS) { done(Pilot.Outcome.Failed("YouTube didn't open the upload.")); return }
            return
        }
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collect(root!!, nodes, 0)
        val print = fingerprint(nodes)

        // Did the last tap move things on?
        val sig = lastSig
        val kind = lastKind
        if (sig != null && kind != null) {
            if (print != lastPrint) {
                Taps.reward(this, kind, sig)
                lastSig = null
                progressAt = now
            } else if (++sameTicks >= JUDGE_TICKS) {
                Taps.punish(this, kind, sig)
                lastSig = null
            } else return
        }

        val titleField = titleField(nodes)
        val uploadButton = if (titleField != null) find(Pilot.Kind.UPLOAD, nodes) else null

        // On a side screen opened for the description, tags or a question.
        if (sub != null && uploadAt < 0) { onSub(nodes, titleField != null, now); return }

        // After Upload: done once the details screen has gone.
        if (uploadAt > 0) {
            if (titleField == null && uploadButton == null) {
                if (++goneTicks >= 2) done(Pilot.Outcome.Posted)
            } else goneTicks = 0
            if (now - uploadAt > UPLOAD_SETTLE_MS && titleField != null && uploadButton != null && teaching == null) {
                // Still on the details screen well after the tap: tap again.
                uploadAt = -1L
            } else return
        }

        if (teaching != null) return

        if (titleField != null) {
            if (!titleSet) {
                val job = Pilot.job ?: return
                type(titleField, job.item.title)
                titleSet = true
                progressAt = now
                Pilot.activity = "Typing the title…"
                return
            }
            // Description, tags, "altered content? No", audience: then Upload.
            if (chores(nodes, titleField, now)) { progressAt = now; return }
            if (uploadButton != null) {
                tap(uploadButton, Pilot.Kind.UPLOAD, print)
                uploadAt = now
                Pilot.activity = "Uploading…"
                return
            }
        } else {
            val dismiss = find(Pilot.Kind.DISMISS, nodes)
            val target = dismiss ?: find(Pilot.Kind.FORWARD, nodes)
            if (target != null) {
                tap(target, if (dismiss != null) Pilot.Kind.DISMISS else Pilot.Kind.FORWARD, print)
                Pilot.activity = "Next…"
                return
            }
        }

        // Nothing it knows how to press, or nothing it pressed moved things on.
        if (now - progressAt > STUCK_MS) {
            val want = if (titleField != null) Pilot.Kind.UPLOAD else Pilot.Kind.FORWARD
            teaching = want
            Pilot.activity = "Waiting for you to tap the button once…"
            Toast.makeText(
                this,
                if (want == Pilot.Kind.UPLOAD) "Hypnotica can't find Upload here. Tap it once and it'll learn it."
                else "Hypnotica can't find the next button. Tap it once and it'll learn it.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * One of the details screen's other jobs, if any is left: fill in the
     * description and tags, answer No to "altered or synthetic content" (a
     * simulation is not realistic footage of anything that happened), and
     * say the audience isn't made for kids. Each is looked for by its words;
     * a row that opens its own screen is opened and handled in [onSub]. The
     * screen is scrolled a little to find rows below the fold. None of it
     * holds up the upload: whatever can't be found is left as it is.
     * Returns true if it did something this tick.
     */
    private fun chores(nodes: List<AccessibilityNodeInfo>, titleField: AccessibilityNodeInfo, now: Long): Boolean {
        val job = Pilot.job ?: return false
        if (!descDone) {
            if (job.item.description.isEmpty()) descDone = true
            else {
                nodes.firstOrNull { it != titleField && isField(it) && hintOf(it).contains("descr") }?.let {
                    type(it, job.item.description); descDone = true
                    Pilot.activity = "Writing the description…"
                    return true
                }
                labelled(nodes) { it == "description" || it.startsWith("add description") || it.startsWith("add a description") }?.let {
                    click(it); descDone = true; openSub(Sub.DESC, now)
                    return true
                }
            }
        }
        if (!tagsDone) {
            if (job.item.tags.isEmpty()) tagsDone = true
            else {
                nodes.firstOrNull { it != titleField && isField(it) && hintOf(it).contains("tag") }?.let {
                    type(it, job.item.tags.joinToString(", ")); tagsDone = true
                    Pilot.activity = "Adding tags…"
                    return true
                }
                labelled(nodes) { it == "tags" || it.startsWith("add tags") }?.let {
                    click(it); tagsDone = true; openSub(Sub.TAGS, now)
                    return true
                }
            }
        }
        if (!aiDone) {
            labelled(nodes) { l -> AI_WORDS.any { l.contains(it) } }?.let { question ->
                aiDone = true
                Pilot.activity = "Answering No to AI / altered content…"
                val no = noBelow(nodes, question)
                if (no != null) click(no) else { click(question); openSub(Sub.AI, now) }
                return true
            }
        }
        if (!kidsDone) {
            labelled(nodes) { it.contains("not made for kids") }?.let {
                kidsDone = true
                if (!(clickable(it) ?: it).isChecked) click(it)
                return true
            }
            labelled(nodes) { it.startsWith("select audience") || it == "audience" }?.let {
                kidsDone = true; click(it); openSub(Sub.KIDS, now)
                return true
            }
        }
        // Rows below the fold: a few looks further down before Upload.
        if ((!descDone || !tagsDone || !aiDone || !kidsDone) && scrolls < MAX_SCROLLS) {
            scrolls++
            val list = nodes.firstOrNull { it.isScrollable }
            if (list != null && list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        }
        return false
    }

    private fun openSub(kind: Sub, now: Long) {
        sub = kind; subAt = now; subActed = false
    }

    /**
     * A side screen: do its one thing, then come back to the details screen —
     * by itself if YouTube returns there, else with Back. Back is only ever
     * pressed off the details screen, never on it, where it would throw the
     * upload away.
     */
    private fun onSub(nodes: List<AccessibilityNodeInfo>, onDetails: Boolean, now: Long) {
        val kind = sub ?: return
        val job = Pilot.job ?: return
        if (onDetails && (subActed || now - subAt > SUB_OPEN_MS)) { sub = null; return }
        if (onDetails) return
        if (!subActed) {
            subActed = when (kind) {
                Sub.DESC -> nodes.firstOrNull { isField(it) }?.let { type(it, job.item.description); true } ?: false
                Sub.TAGS -> nodes.firstOrNull { isField(it) }?.let { type(it, job.item.tags.joinToString(", ")); true } ?: false
                Sub.AI -> labelled(nodes) { it == "no" || (it.startsWith("no") && !it.contains("kids") && it.length < 40) }?.let { click(it); true } ?: false
                Sub.KIDS -> labelled(nodes) { it.contains("not made for kids") }?.let { click(it); true } ?: false
            }
            if (subActed) { subAt = now; return }
        }
        // Done here, or nothing to do after a few seconds: back to the details.
        if (subActed || now - subAt > SUB_GIVE_UP_MS) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            sub = null
        }
    }

    private fun isField(n: AccessibilityNodeInfo) = n.isEditable || n.className?.toString()?.contains("EditText") == true

    private fun hintOf(n: AccessibilityNodeInfo) =
        listOfNotNull(n.hintText, n.text, n.contentDescription, n.viewIdResourceName).joinToString(" ").lowercase()

    private fun labelOf(n: AccessibilityNodeInfo) = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase()

    private fun labelled(nodes: List<AccessibilityNodeInfo>, test: (String) -> Boolean): AccessibilityNodeInfo? =
        nodes.firstOrNull { n -> n.isEnabled && labelOf(n)?.let(test) == true }

    /** The "No" answer that goes with [question]: the nearest one at or below it. */
    private fun noBelow(nodes: List<AccessibilityNodeInfo>, question: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val q = Rect(); question.getBoundsInScreen(q)
        val r = Rect()
        return nodes.filter { n ->
            val l = labelOf(n) ?: return@filter false
            (l == "no" || (l.startsWith("no") && !l.contains("kids") && l.length < 40)) && n.isEnabled
        }.filter { n -> n.getBoundsInScreen(r); r.top >= q.top - 4 }
            .minByOrNull { n -> n.getBoundsInScreen(r); r.top - q.top }
    }

    private fun click(n: AccessibilityNodeInfo) {
        val target = clickable(n) ?: n
        if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        val r = Rect()
        target.getBoundsInScreen(r)
        if (r.isEmpty) return
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null)
    }

    private fun type(field: AccessibilityNodeInfo, text: String) {
        field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun done(outcome: Pilot.Outcome) {
        idle()
        Pilot.finish(outcome)
        // Back to Hypnotica for the next one.
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    private fun tap(node: AccessibilityNodeInfo, kind: Pilot.Kind, print: Int) {
        val target = clickable(node) ?: node
        lastSig = sigOf(target)
        lastKind = kind
        lastPrint = print
        sameTicks = 0
        if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        // Some buttons only answer a real touch: tap the middle of it.
        val r = Rect()
        target.getBoundsInScreen(r)
        if (r.isEmpty) return
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null)
    }

    // ---------------------------------------------------------------- finding

    private fun collect(node: AccessibilityNodeInfo, out: ArrayList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > MAX_DEPTH || out.size > MAX_NODES) return
        if (!node.isVisibleToUser) return
        out.add(node)
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            collect(c, out, depth + 1)
        }
    }

    /** A summary of what is on screen, to tell whether a tap changed it. */
    private fun fingerprint(nodes: List<AccessibilityNodeInfo>): Int {
        var h = 17
        for (n in nodes) {
            val words = n.text ?: n.contentDescription ?: continue
            h = h * 31 + words.toString().hashCode()
            h = h * 31 + (n.viewIdResourceName?.hashCode() ?: 0)
        }
        return h
    }

    private fun titleField(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        val fields = nodes.filter { it.isEditable || it.className?.toString()?.contains("EditText") == true }
        if (fields.isEmpty()) return null
        val notOthers = fields.filter { f -> hintOf(f).let { !it.contains("descr") && !it.contains("tag") } }
        return notOthers.firstOrNull { f -> TITLE_WORDS.any { hintOf(f).contains(it) } } ?: notOthers.firstOrNull()
    }

    /**
     * The button for [kind]: first one it has learned, then one that says
     * the right thing. Words that match exactly beat words that only
     * appear in a label, and a button low on the screen (where YouTube
     * keeps its forward buttons) beats one high up.
     */
    private fun find(kind: Pilot.Kind, nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        for ((sig, _) in Taps.list(this, kind)) {
            nodes.firstOrNull { n ->
                n.isEnabled && sig.matches(n.viewIdResourceName, n.text?.toString(), n.contentDescription?.toString(), n.className?.toString())
            }?.let { return it }
        }
        val words = when (kind) {
            Pilot.Kind.FORWARD -> FORWARD_WORDS
            Pilot.Kind.UPLOAD -> UPLOAD_WORDS
            Pilot.Kind.DISMISS -> DISMISS_WORDS
        }
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0f
        val r = Rect()
        for (n in nodes) {
            if (!n.isEnabled) continue
            val label = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase() ?: continue
            if (label.length > 40) continue
            var score = 0f
            for ((i, w) in words.withIndex()) {
                val s = when {
                    label == w -> 10f
                    // A whole word only: "Uploading…" is not an Upload button.
                    kind != Pilot.Kind.DISMISS && label.startsWith("$w ") -> 5f
                    else -> 0f
                }
                if (s > 0f) { score = s - i * 0.1f; break }
            }
            if (score <= 0f) continue
            if (clickable(n) == null) score -= 3f
            n.getBoundsInScreen(r)
            score += (r.centerY() / 4000f)
            if (score > bestScore) { bestScore = score; best = n }
        }
        return best
    }

    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 5) {
            if (n.isClickable) return n
            n = n.parent
            hops++
        }
        return null
    }

    private fun sigOf(n: AccessibilityNodeInfo) = Sig(
        n.viewIdResourceName,
        n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
        n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() },
        n.className?.toString()
    )

    companion object {
        const val FIRST_TICK_MS = 1500L
        const val TICK_MS = 700L
        const val JUDGE_TICKS = 4
        const val STUCK_MS = 12_000L
        const val OPEN_TIMEOUT_MS = 20_000L
        const val DONE_AFTER_MS = 1500L
        const val UPLOAD_SETTLE_MS = 8000L
        const val MAX_DEPTH = 40
        const val MAX_NODES = 1500
        val FORWARD_WORDS = listOf("next", "done", "continue", "upload as a short", "create a short")
        val UPLOAD_WORDS = listOf("upload short", "upload", "post", "publish")
        val DISMISS_WORDS = listOf("got it", "not now", "no thanks", "skip", "ok", "dismiss")
        val TITLE_WORDS = listOf("caption", "title", "short")
        /** How YouTube asks whether a video is AI-made or altered. */
        val AI_WORDS = listOf("altered content", "altered or synthetic", "synthetic content", "made with ai", "ai-generated", "generated with ai", "realistic altered")
        const val MAX_SCROLLS = 3
        /** How long a side screen gets to appear before it's taken as not having opened. */
        const val SUB_OPEN_MS = 2500L
        /** How long to look for what a side screen is for before going back anyway. */
        const val SUB_GIVE_UP_MS = 6000L
    }
}
