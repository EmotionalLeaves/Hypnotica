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

    /** The screen as last read, and for how many ticks it hasn't changed. */
    private var seenPrint = 0
    private var stableTicks = 0

    /** A tap that was tried and changed nothing: next time, a real touch instead. */
    private var failedSig: Sig? = null

    /** Screenshots read for words, for screens the tree doesn't describe. */
    private var reader: ScreenReader? = null
    private var readingAt = -1L
    private var reading = false
    /** The caption box was tapped from a screenshot: type into whatever has focus next. */
    private var captionTapped = false

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
        reader = ScreenReader(this)
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
        seenPrint = 0; stableTicks = 0; failedSig = null
        readingAt = -1L; reading = false; captionTapped = false
        descDone = false; tagsDone = false; aiDone = false; kidsDone = false; scrolls = 0
        sub = null; subActed = false
        Pilot.activity = "Opening YouTube…"
        Pilot.trace("--- new upload")
        Toast.makeText(this, "Hypnotica auto-tap is on it — hands off for a moment", Toast.LENGTH_SHORT).show()
        if (!running) {
            running = true
            handler.postDelayed(::tick, FIRST_TICK_MS)
        }
    }

    /** Back to doing nothing. */
    fun idle() {
        running = false
        handler.removeCallbacksAndMessages(null)
        Pilot.activity = ""
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Read on its own clock, not per event: see tick().
    }

    private fun tick() {
        if (!running) return
        if (Pilot.job == null) { idle(); return }
        try {
            step()
        } catch (e: Exception) {
            // A node can vanish between reading and acting; the next tick reads afresh.
            Pilot.trace("error: ${e.javaClass.simpleName}: ${e.message}")
        }
        if (running) handler.postDelayed(::tick, TICK_MS)
    }

    private fun step() {
        val now = SystemClock.uptimeMillis()
        if (now - startedAt > Pilot.ITEM_TIMEOUT_MS) { done(Pilot.Outcome.Failed("Stuck in YouTube's upload screens.")); return }
        val root = youTubeRoot()
        val inYouTube = root != null
        if (!inYouTube) {
            if (now - lastTraceAt > 2000) {
                lastTraceAt = now
                Pilot.trace("not in YouTube (active: ${rootInActiveWindow?.packageName ?: "nothing readable"})")
            }
            // Upload tapped and YouTube left behind: posted.
            if (uploadAt > 0 && now - uploadAt > DONE_AFTER_MS) { done(Pilot.Outcome.Posted); return }
            if (uploadAt < 0 && now - startedAt > OPEN_TIMEOUT_MS) { done(Pilot.Outcome.Failed("YouTube didn't open the upload.")); return }
            return
        }
        val nodes = ArrayList<AccessibilityNodeInfo>()
        for (r in youTubeRoots()) collect(r, nodes, 0)
        val print = fingerprint(nodes)
        if (print != tracedPrint) {
            // A new screen: what is on it, so a run that goes wrong can be read back.
            tracedPrint = print
            val words = nodes.mapNotNull { n ->
                val l = (n.text ?: n.contentDescription)?.toString()?.trim()?.take(30)
                if (l.isNullOrEmpty()) null else (if (n.isEditable) "[$l]" else if (n.isClickable) "<$l>" else l)
            }.distinct().take(40)
            Pilot.trace("screen: ${nodes.size} nodes — ${words.joinToString(" | ")}")
        }

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
                failedSig = sig
                lastSig = null
            } else return
        }

        // Nothing is touched until the screen has held still for a moment:
        // tapped while YouTube was still loading its editor, Next did nothing.
        if (print == seenPrint) stableTicks++ else { seenPrint = print; stableTicks = 0 }
        if (stableTicks < STABLE_TICKS) return

        if (now - progressAt > GIVE_UP_MS) { done(Pilot.Outcome.Failed("Couldn't get through YouTube's upload screens.")); return }
        if (reading) return
        // Taps from the tree that keep changing nothing: read the screen instead.
        if (uploadAt < 0 && now - progressAt > READ_AFTER_MS * 2 && now - readingAt > READ_EVERY_MS) { readScreen(now); return }

        // A question that throws work away ("Discard edits?", "Delete edits?")
        // is always answered Cancel. "Continue" there deleted the video.
        val screenWords = nodes.mapNotNull { labelOf(it) }
        if (screenWords.any { destructive(it) }) {
            labelled(nodes) { it == "cancel" || it == "keep editing" || it == "keep" || it == "no" }?.let {
                Pilot.trace("a question that would lose the video — cancel")
                click(it)
                stableTicks = 0
                return
            }
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
            if (now - uploadAt > UPLOAD_SETTLE_MS && titleField != null && uploadButton != null) {
                // Still on the details screen well after the tap: tap again.
                uploadAt = -1L
            } else return
        }

        if (reading) return

        // The caption box was found in a screenshot and tapped: whatever now
        // has the keyboard's focus is it.
        if (captionTapped && !titleSet) {
            focusedField()?.let { f ->
                Pilot.job?.let { type(f, it.item.title) }
                titleSet = true
                captionTapped = false
                progressAt = now
                return
            }
        }

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

        // Nothing it knows how to press, or nothing it pressed moved things on:
        // read the screen from a screenshot and tap what it says.
        if (now - progressAt > READ_AFTER_MS && !reading && now - readingAt > READ_EVERY_MS) readScreen(now)
        if (now - progressAt > GIVE_UP_MS) done(Pilot.Outcome.Failed("Couldn't find YouTube's next button, even reading the screen."))
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
        Pilot.trace("tap: ${labelOf(n) ?: target.viewIdResourceName ?: "?"}")
        if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        val r = Rect()
        target.getBoundsInScreen(r)
        if (r.isEmpty) return
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null)
    }

    private fun type(field: AccessibilityNodeInfo, text: String) {
        Pilot.trace("type into ${field.viewIdResourceName ?: field.hintText ?: "a field"}: ${text.take(40)}")
        field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        // The keyboard can sit over Upload Short: put it away. Back only while
        // the keyboard is up, where it closes the keyboard and nothing else.
        handler.postDelayed({
            val keyboardUp = try {
                windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            } catch (e: Exception) { false }
            if (keyboardUp) performGlobalAction(GLOBAL_ACTION_BACK)
        }, 400)
    }

    /**
     * The YouTube app's window. Usually the active one, but not always — the
     * keyboard, a toast or a system bar can be what Android calls active —
     * so the other windows on screen are looked through too.
     */
    private fun youTubeRoot(): AccessibilityNodeInfo? = youTubeRoots().firstOrNull()

    /**
     * Every window YouTube has on screen, the active one first. Its upload
     * screens can be split across more than one — a sheet over the editor —
     * and all of them are read.
     */
    private fun youTubeRoots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { if (it.packageName?.toString() == Pilot.YOUTUBE) out.add(it) }
        try {
            for (w in windows) {
                val r = w.root ?: continue
                if (r.packageName?.toString() == Pilot.YOUTUBE && out.none { it == r }) out.add(r)
            }
        } catch (e: Exception) {}
        return out
    }

    /** The text box the keyboard is typing into, in any of YouTube's windows. */
    private fun focusedField(): AccessibilityNodeInfo? {
        for (r in youTubeRoots()) {
            val f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: continue
            if (f.isEditable) return f
        }
        return null
    }

    /**
     * The screen read from a screenshot, for when the tree has nothing to go
     * on, and the first thing it says that auto-tap knows: Cancel on a
     * question that would lose the video; on the details screen the caption
     * box, then Upload Short; otherwise Next or Done. Tapped with a real
     * touch at the words' place.
     */
    private fun readScreen(now: Long) {
        val r = reader ?: return
        if (!r.available) return
        reading = true
        readingAt = now
        r.read { lines ->
            reading = false
            if (Pilot.job == null || !running) return@read
            val said = lines.map { it.text.lowercase() to it.box }
            Pilot.trace("read the screen: " + said.joinToString(" | ") { it.first.take(24) }.take(400))
            fun first(test: (String) -> Boolean) = said.filter { test(it.first) }.maxByOrNull { it.second.centerY() }
            val words = said.map { it.first }
            val details = words.any { w -> UPLOAD_WORDS.any { w == it } } || words.any { it.contains("add details") }
            val target: Pair<String, Rect>? = when {
                words.any { destructive(it) } -> first { it == "cancel" || it == "keep editing" }
                details && !titleSet && !captionTapped -> first { w -> CAPTION_WORDS.any { w.contains(it) } }?.also { captionTapped = true }
                details -> first { w -> UPLOAD_WORDS.any { w == it } }?.also { uploadAt = SystemClock.uptimeMillis() }
                else -> first { w -> w == "next" || w == "done" }
            }
            if (target == null) { Pilot.trace("nothing on it to tap"); return@read }
            Pilot.trace("tap (from the screenshot): ${target.first}")
            touch(target.second)
            progressAt = SystemClock.uptimeMillis()
            stableTicks = 0
        }
    }

    private fun touch(r: Rect) {
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null)
    }

    /** Whether [label] asks to throw something away: whole words, so "close" is not "lose". */
    private fun destructive(label: String): Boolean {
        if (label.contains("your edits will")) return true
        return label.split(Regex("[^a-z]+")).any { it in DESTRUCTIVE }
    }

    /** A few words on the screen as it is now, to recognise it again. */
    private fun screenContext(): List<String> {
        val root = youTubeRoot() ?: return emptyList()
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collect(root, nodes, 0)
        return nodes.mapNotNull { labelOf(it) }.filter { it.length in 2..30 }.distinct().take(8)
    }

    private var tracedPrint = 0
    private var lastTraceAt = 0L

    private fun done(outcome: Pilot.Outcome) {
        Pilot.trace("done: ${when (outcome) { is Pilot.Outcome.Posted -> "posted"; is Pilot.Outcome.Stopped -> "stopped"; is Pilot.Outcome.Failed -> "failed — ${outcome.why}" }}")
        idle()
        Pilot.finish(outcome)
        // Back to Hypnotica for the next one.
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    private fun tap(node: AccessibilityNodeInfo, kind: Pilot.Kind, print: Int) {
        val target = clickable(node) ?: node
        Pilot.trace("tap ${kind.name.lowercase()}: ${labelOf(node) ?: target.viewIdResourceName ?: "?"}")
        val sig = sigOf(target)
        lastSig = sig
        lastKind = kind
        lastPrint = print
        sameTicks = 0
        stableTicks = 0
        // A tap that did nothing last time is tried as a real touch.
        val touch = sig == failedSig
        if (!touch && target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
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
        // A container can report itself not visible while what is in it is
        // on screen: YouTube's details screen read as five empty nodes when
        // the walk stopped at such a container. Its children are still read.
        if (node.isVisibleToUser) out.add(node)
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
        val here = nodes.mapNotNull { labelOf(it) }.toSet()
        for ((sig, _) in Taps.list(this, kind)) {
            // A tap learned on one screen is only used on that screen.
            val ctx = sig.ctx
            if (!ctx.isNullOrEmpty() && ctx.count { it in here } * 2 < ctx.size) continue
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
        const val FIRST_TICK_MS = 2500L
        /** Ticks the screen must hold still before anything is tapped. */
        const val STABLE_TICKS = 2
        const val TICK_MS = 700L
        const val JUDGE_TICKS = 4
        /** Seconds with no progress before the screen is read from a screenshot, and how often again. */
        const val READ_AFTER_MS = 4000L
        const val READ_EVERY_MS = 3000L
        /** With no progress at all for this long, the upload is given up. */
        const val GIVE_UP_MS = 75_000L
        const val OPEN_TIMEOUT_MS = 20_000L
        const val DONE_AFTER_MS = 1500L
        const val UPLOAD_SETTLE_MS = 8000L
        const val MAX_DEPTH = 40
        const val MAX_NODES = 1500
        val FORWARD_WORDS = listOf("next", "done", "continue", "upload as a short")
        val UPLOAD_WORDS = listOf("upload short", "upload", "post", "publish")
        val DISMISS_WORDS = listOf("got it", "not now", "no thanks", "skip", "ok", "dismiss")
        val TITLE_WORDS = listOf("caption", "title", "short")
        /** How a Short's caption box introduces itself, on screen. */
        val CAPTION_WORDS = listOf("caption", "create a title", "add a title", "title")
        /** Words in a question whose yes throws the video or its edits away. */
        val DESTRUCTIVE = setOf("discard", "delete", "lose")
        /** The editor's own tools and ways out: never a way forward. */
        val NOT_FORWARD = listOf("volume", "text", "effects", "filters", "stickers", "captions", "more tools", "more",
            "add sound", "edit", "exit editor", "close", "cancel", "record button", "flip camera", "timer controls",
            "speed controls", "green screen", "close camera", "create with ai", "home", "shorts", "create", "you",
            "search", "notifications", "cast", "messages inbox")
        /** How YouTube asks whether a video is AI-made or altered. */
        val AI_WORDS = listOf("altered content", "altered or synthetic", "synthetic content", "made with ai", "ai-generated", "generated with ai", "realistic altered")
        const val MAX_SCROLLS = 3
        /** How long a side screen gets to appear before it's taken as not having opened. */
        const val SUB_OPEN_MS = 2500L
        /** How long to look for what a side screen is for before going back anyway. */
        const val SUB_GIVE_UP_MS = 6000L
    }
}
