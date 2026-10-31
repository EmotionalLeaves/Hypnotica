package app.hypnotica

import app.hypnotica.core.director.ClipDirector
import app.hypnotica.core.sim.SimCore
import app.hypnotica.core.sim.TrapFormat
import app.hypnotica.core.studio.TrackKind
import app.hypnotica.ui.ShortsUploader
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * A Short's edit speeds up the middle of the build, so its moment lands at
 * a different time in the video than in the run. The captions around it
 * ("Wait for it…", "Did you call it?") are placed from that time, so it has
 * to be right: rendered the way the exporter renders, the run's own payoff
 * must land where the plan says.
 */
class ShortsPlanTest {

    @Test
    fun theMomentLandsWhereTheEditPutsIt() {
        val aspect = 1080f / 1920f
        val director = ClipDirector(seed = 777L).apply {
            this.aspect = aspect
            maxPayoffSeconds = 38f
            mutedFormats.addAll(listOf(TrapFormat.LOOP, TrapFormat.GROW))
            minCallouts = 2
        }
        val wrong = ArrayList<String>()
        val report = StringBuilder()
        repeat(CLIPS) {
            val clip = director.generate(28) ?: return@repeat
            val plan = ShortsUploader.plan(clip)
            val core = SimCore(clip.def, clip.seed)
            core.setAspect(aspect)
            core.setLaws(clip.laws)
            for ((k, v) in clip.params) core.params.set(k, v)
            core.restart(clip.seed)
            val fps = 60
            val speedTrack = plan.project.track(TrackKind.SPEED)
            var t = 0f
            var landed = -1f
            while (t < plan.project.duration && landed < 0f) {
                val speed = if (speedTrack.isEmpty()) 1f else speedTrack.valueAt(t)
                core.advanceSeconds(1f / fps, speed)
                t += 1f / fps
                if (core.payoffFired) landed = t
            }
            report.append(String.format(java.util.Locale.US, "%-28s %-14s run %5.1f s  video %5.1f s (planned %5.1f)  length %5.1f  callouts %d\n",
                clip.def.id, clip.format.name, clip.payoffSeconds, landed, plan.payoffAt, plan.project.duration, director.lastCallouts))
            if (landed < 0f || abs(landed - plan.payoffAt) > 0.35f) wrong.add("${clip.def.id}: planned ${plan.payoffAt}, landed $landed")
            if (plan.project.duration > 58.5f) wrong.add("${clip.def.id}: ${plan.project.duration} s is too long for a Short")
        }
        println(report)
        assertTrue("Shorts whose moment lands away from its edit:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    /** Every post gets a caption YouTube will take: under 100 characters, a description, tags within YouTube's 500. */
    @Test
    fun everyPostHasACaptionDescriptionAndTags() {
        val director = ClipDirector(seed = 31L)
        val report = StringBuilder()
        val wrong = ArrayList<String>()
        repeat(CLIPS) {
            val clip = director.generate() ?: return@repeat
            val c = ShortsUploader.captionFor(clip)
            report.append("${c.title}\n   tags: ${c.tags.joinToString(", ")}\n")
            if (c.title.length > 100 || !c.title.contains("#shorts")) wrong.add("title: ${c.title}")
            if (c.description.length < 40 || c.description.length > 4900) wrong.add("description for ${clip.def.id}")
            if (c.tags.isEmpty() || c.tags.sumOf { it.length + 1 } > 500) wrong.add("tags for ${clip.def.id}")
        }
        println(report)
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    /** The hook names the sim, or asks you to guess the sort and names it at the end. */
    @Test
    fun hooksAreAboutTheirOwnSim() {
        val director = ClipDirector(seed = 99L)
        val wrong = ArrayList<String>()
        val seen = StringBuilder()
        repeat(CLIPS * 2) {
            val clip = director.generate() ?: return@repeat
            val h = ShortsUploader.hookFor(clip)
            val sort = ShortsUploader.sortName(clip)
            seen.append("${clip.def.id}: ${h.onScreen} / ${h.reveal}\n")
            if (sort != null) {
                if (h.onScreen.contains(sort) || h.reveal?.contains(sort) != true) wrong.add("${clip.def.id}: ${h.onScreen} / ${h.reveal}")
                if (ShortsUploader.captionFor(clip).title.contains(sort)) wrong.add("${clip.def.id}: the title gives the answer away")
            } else if (!h.onScreen.contains(clip.def.title)) wrong.add("${clip.def.id}: ${h.onScreen}")
        }
        println(seen)
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    companion object { const val CLIPS = 12 }
}
