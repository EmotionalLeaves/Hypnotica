package app.hypnotica

import app.hypnotica.core.studio.Easing
import app.hypnotica.core.studio.Keyframe
import app.hypnotica.core.studio.Project
import app.hypnotica.core.studio.TextClip
import app.hypnotica.core.studio.TrackKind
import app.hypnotica.core.studio.parseProject
import app.hypnotica.core.studio.projectJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * A Studio project survives being written down and read back.
 *
 * It used to live only in memory, so Android reclaiming the app in the
 * background — a phone call was enough — took a session's keyframes with it.
 * It is saved as it is edited now, and this holds the writer and the reader to
 * each other: a reader that drifts loses a keyframe quietly.
 */
class ProjectJsonTest {

    private fun sameProject(a: Project, b: Project) {
        assertEquals("sim", a.simId, b.simId)
        assertEquals("seed", a.seed, b.seed)
        assertEquals("name", a.name, b.name)
        assertEquals("duration", a.duration, b.duration, 0f)
        for (kind in TrackKind.entries) {
            assertEquals("${kind.name} keys", a.track(kind).keyframes(), b.track(kind).keyframes())
        }
        assertEquals("texts", a.texts.toList(), b.texts.toList())
        assertEquals("freezes", a.freezes.toList(), b.freezes.toList())
    }

    @Test
    fun `every template survives the round trip`() {
        for (name in Project.TEMPLATES) {
            for (seconds in floatArrayOf(10f, 26f, 48f)) {
                val p = Project.template(name, "physics/pendulum-wave", 1234567L, seconds)
                sameProject(p, parseProject(projectJson(p))!!)
            }
        }
    }

    @Test
    fun `keys closer than a finger's grab are all kept`() {
        // Track.set merges keys inside 0.3 s, and moveNear can leave two
        // closer than that. Restoring through set dropped one of them.
        val p = Project("art/polyrhythm", 1L)
        p.track(TrackKind.ZOOM).restore(
            listOf(Keyframe(1.00f, 1f), Keyframe(1.05f, 2f), Keyframe(1.10f, 1.5f, Easing.HOLD))
        )
        val back = parseProject(projectJson(p))!!
        assertEquals("all three keys", 3, back.track(TrackKind.ZOOM).size)
        assertEquals("and copy() keeps them too", 3, p.copy().track(TrackKind.ZOOM).size)
    }

    @Test
    fun `awkward text and extreme numbers survive`() {
        val p = Project("math/lorenz", Long.MIN_VALUE)
        p.name = "quote \" backslash \\ newline \n tab \t bell \u0007 é ✓"
        p.duration = 117.33333f
        p.texts.add(TextClip("He said \"go\"\\n", 0.1f, 2.9f, big = false))
        p.freezes.add(3.5f to 5.25f)
        p.track(TrackKind.ROTATION).restore(listOf(Keyframe(0f, -6.2831855f), Keyframe(117.3f, 1e-7f)))
        sameProject(p, parseProject(projectJson(p))!!)
    }

    @Test
    fun `a thousand random projects survive the round trip`() {
        val rnd = Random(3)
        repeat(1000) {
            val p = Project("sim/${rnd.nextInt(60)}", rnd.nextLong())
            p.duration = 4f + rnd.nextFloat() * 116f
            for (kind in TrackKind.entries) {
                if (rnd.nextFloat() < 0.4f) continue
                val keys = (0 until rnd.nextInt(0, 9)).map {
                    Keyframe(
                        rnd.nextFloat() * p.duration,
                        kind.min + rnd.nextFloat() * (kind.max - kind.min),
                        Easing.entries[rnd.nextInt(Easing.entries.size)]
                    )
                }
                p.track(kind).restore(keys)
            }
            repeat(rnd.nextInt(0, 4)) {
                val a = rnd.nextFloat() * p.duration
                p.texts.add(TextClip("caption ${rnd.nextInt()}", a, a + 1f, rnd.nextBoolean()))
            }
            repeat(rnd.nextInt(0, 3)) {
                val a = rnd.nextFloat() * p.duration
                p.freezes.add(a to a + 2f)
            }
            sameProject(p, parseProject(projectJson(p))!!)
        }
    }

    @Test
    fun `a damaged save costs the save, never the app`() {
        for (bad in listOf("", "{", "null", "[]", "{\"seed\":1}", "{\"sim\":\"x\"}",
                "{\"sim\":\"x\",\"seed\":\"no\"}", "{".repeat(300), "\u0000")) {
            assertNull("accepted <$bad>", parseProject(bad))
        }
        // Parts this build does not know are skipped, not fatal.
        val p = parseProject(
            """{"sim":"x","seed":5,"tracks":{"WARP":[[1,2,"EASE_IN_OUT"]],"ZOOM":[[1,1.2,"SOMETHING_NEW"]]},"future":true}"""
        )
        assertTrue("opened what it understood", p != null && p.track(TrackKind.ZOOM).size == 1)
    }
}
