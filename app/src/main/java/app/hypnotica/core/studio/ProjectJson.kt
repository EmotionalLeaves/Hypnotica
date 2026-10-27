package app.hypnotica.core.studio

import app.hypnotica.core.recipe.Json
import app.hypnotica.core.recipe.JsonError

/**
 * A Studio project, written down so it survives the app being killed.
 *
 * A project is a few kilobytes — keyframes, captions, freezes — and it lived
 * only in memory: Android reclaims a backgrounded app whenever it likes, and a
 * phone call or a trip to the camera was enough to take a session's editing
 * with it. Rotation was safe; nothing else was.
 *
 * The writer and the reader sit together and ProjectJsonTest round-trips them,
 * for the same reason the run's do: a reader that drifts from its writer loses
 * one value quietly, and in an editor that is a keyframe gone.
 */
fun projectJson(p: Project): String {
    val sb = StringBuilder(1024)
    sb.append("{\"v\":1")
    sb.append(",\"sim\":").append(quote(p.simId))
    sb.append(",\"seed\":").append(p.seed)
    sb.append(",\"name\":").append(quote(p.name))
    sb.append(",\"duration\":").append(p.duration)
    sb.append(",\"tracks\":{")
    var firstTrack = true
    for ((kind, track) in p.tracks) {
        if (track.isEmpty()) continue
        if (!firstTrack) sb.append(',')
        firstTrack = false
        sb.append(quote(kind.name)).append(":[")
        track.keyframes().forEachIndexed { i, k ->
            if (i > 0) sb.append(',')
            sb.append('[').append(k.time).append(',').append(k.value)
                .append(',').append(quote(k.easing.name)).append(']')
        }
        sb.append(']')
    }
    sb.append("},\"texts\":[")
    p.texts.forEachIndexed { i, t ->
        if (i > 0) sb.append(',')
        sb.append('[').append(quote(t.text)).append(',').append(t.start)
            .append(',').append(t.end).append(',').append(t.big).append(']')
    }
    sb.append("],\"freezes\":[")
    p.freezes.forEachIndexed { i, f ->
        if (i > 0) sb.append(',')
        sb.append('[').append(f.first).append(',').append(f.second).append(']')
    }
    sb.append("]}")
    return sb.toString()
}

/**
 * Read a project back, or null if the text is not one. Never throws: this runs
 * on launch, and a damaged save must cost the save, not the app.
 *
 * A track, easing or field this build does not know is skipped rather than
 * failing the whole project, so a save from a newer build opens with what this
 * one understands.
 */
fun parseProject(text: String): Project? {
    val root = try {
        Json(text).parse() as? Map<*, *>
    } catch (e: JsonError) {
        null
    } catch (e: RuntimeException) {
        null
    } ?: return null
    val sim = root["sim"] as? String ?: return null
    val seed = (root["seed"] as? Number)?.toLong() ?: return null
    val p = Project(sim, seed)
    (root["name"] as? String)?.let { p.name = it }
    (root["duration"] as? Number)?.toFloat()?.let { if (it > 0f && it.isFinite()) p.duration = it }

    (root["tracks"] as? Map<*, *>)?.forEach { (k, v) ->
        val kind = TrackKind.entries.firstOrNull { it.name == k } ?: return@forEach
        val keys = (v as? List<*>)?.mapNotNull { entry ->
            val e = entry as? List<*> ?: return@mapNotNull null
            val time = (e.getOrNull(0) as? Number)?.toFloat() ?: return@mapNotNull null
            val value = (e.getOrNull(1) as? Number)?.toFloat() ?: return@mapNotNull null
            if (!time.isFinite() || !value.isFinite()) return@mapNotNull null
            val easing = Easing.entries.firstOrNull { it.name == e.getOrNull(2) } ?: Easing.EASE_IN_OUT
            Keyframe(time, value, easing)
        } ?: return@forEach
        p.track(kind).restore(keys)
    }
    (root["texts"] as? List<*>)?.forEach { entry ->
        val e = entry as? List<*> ?: return@forEach
        val words = e.getOrNull(0) as? String ?: return@forEach
        val start = (e.getOrNull(1) as? Number)?.toFloat() ?: return@forEach
        val end = (e.getOrNull(2) as? Number)?.toFloat() ?: return@forEach
        val big = e.getOrNull(3) as? Boolean ?: true
        p.texts.add(TextClip(words, start, end, big))
    }
    (root["freezes"] as? List<*>)?.forEach { entry ->
        val e = entry as? List<*> ?: return@forEach
        val a = (e.getOrNull(0) as? Number)?.toFloat() ?: return@forEach
        val b = (e.getOrNull(1) as? Number)?.toFloat() ?: return@forEach
        p.freezes.add(a to b)
    }
    return p
}

private fun quote(s: String): String {
    val sb = StringBuilder(s.length + 2).append('"')
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append(String.format(java.util.Locale.US, "\\u%04x", c.code)) else sb.append(c)
        }
    }
    return sb.append('"').toString()
}
