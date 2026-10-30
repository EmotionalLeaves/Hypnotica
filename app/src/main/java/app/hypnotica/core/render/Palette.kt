package app.hypnotica.core.render

import kotlin.math.abs

/**
 * Colour ramps built as hue arcs, so any two positions along one look good
 * together. Sims ask for a colour by position (0..1), never by literal hex.
 */
class Palette(val name: String, private val stops: IntArray, private val wheel: Boolean = false) {

    /**
     * A lookup table, because [at] is called once per object per frame and a
     * hundred thousand hsv conversions a frame is the difference between 60 fps
     * and a slideshow.
     */
    private val lut: IntArray = IntArray(LUT_SIZE).also { table ->
        for (i in 0 until LUT_SIZE) {
            val t = i / (LUT_SIZE - 1f)
            table[i] = if (wheel) {
                hsv(t * 360f, 0.72f, 0.98f)
            } else {
                val x = t * (stops.size - 1)
                val a = x.toInt().coerceIn(0, stops.size - 1)
                val b = (a + 1).coerceAtMost(stops.size - 1)
                lerp(stops[a], stops[b], x - a)
            }
        }
    }

    /** Colour at position t in 0..1. Wheel palettes wrap; the rest clamp. */
    fun at(t: Float): Int {
        val u = if (wheel) ((t % 1f) + 1f) % 1f else t.coerceIn(0f, 1f)
        val i = (u * (LUT_SIZE - 1)).toInt()
        return lut[if (i < 0) 0 else if (i >= LUT_SIZE) LUT_SIZE - 1 else i]
    }

    /** Brightened version, for highlights and impact flashes. */
    fun bright(t: Float, amount: Float = 0.45f): Int = brighten(at(t), amount)

    companion object {

        private const val LUT_SIZE = 512

        fun lerp(a: Int, b: Int, t: Float): Int {
            val u = t.coerceIn(0f, 1f)
            val ar = (a ushr 16) and 0xFF; val ag = (a ushr 8) and 0xFF; val ab = a and 0xFF
            val br = (b ushr 16) and 0xFF; val bg = (b ushr 8) and 0xFF; val bb = b and 0xFF
            val aa = (a ushr 24) and 0xFF; val ba = (b ushr 24) and 0xFF
            val r = (ar + (br - ar) * u).toInt()
            val g = (ag + (bg - ag) * u).toInt()
            val bl = (ab + (bb - ab) * u).toInt()
            val al = (aa + (ba - aa) * u).toInt()
            return (al shl 24) or (r shl 16) or (g shl 8) or bl
        }

        fun brighten(c: Int, amount: Float): Int {
            val a = (c ushr 24) and 0xFF
            // Clamped at both ends: a negative amount is a legitimate way to
            // darken, and without the lower bound its sign bits would run into
            // the neighbouring channels and the alpha.
            val r = (((c ushr 16) and 0xFF) + 255 * amount).toInt().coerceIn(0, 255)
            val g = (((c ushr 8) and 0xFF) + 255 * amount).toInt().coerceIn(0, 255)
            val b = ((c and 0xFF) + 255 * amount).toInt().coerceIn(0, 255)
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        fun withAlpha(c: Int, alpha: Float): Int {
            val a = (255 * alpha.coerceIn(0f, 1f)).toInt()
            return (a shl 24) or (c and 0x00FFFFFF)
        }

        fun hsv(hDeg: Float, s: Float, v: Float): Int {
            val h = ((hDeg % 360f) + 360f) % 360f / 60f
            val c = v * s
            val x = c * (1f - abs(h % 2f - 1f))
            val m = v - c
            var r = 0f; var g = 0f; var b = 0f
            when (h.toInt()) {
                0 -> { r = c; g = x }
                1 -> { r = x; g = c }
                2 -> { g = c; b = x }
                3 -> { g = x; b = c }
                4 -> { r = x; b = c }
                else -> { r = c; b = x }
            }
            val ri = ((r + m) * 255f).toInt().coerceIn(0, 255)
            val gi = ((g + m) * 255f).toInt().coerceIn(0, 255)
            val bi = ((b + m) * 255f).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
        }

        val SPECTRUM = Palette("Spectrum", intArrayOf(), wheel = true)

        val AURORA = Palette(
            "Aurora",
            intArrayOf(0xFF00F5D4.toInt(), 0xFF00BBF9.toInt(), 0xFF9B5DE5.toInt(), 0xFFF15BB5.toInt(), 0xFFFEE440.toInt())
        )

        val EMBER = Palette(
            "Ember",
            intArrayOf(0xFFFFBA08.toInt(), 0xFFF48C06.toInt(), 0xFFDC2F02.toInt(), 0xFF9D0208.toInt())
        )

        val OCEAN = Palette(
            "Ocean",
            intArrayOf(0xFFCAF0F8.toInt(), 0xFF90E0EF.toInt(), 0xFF00B4D8.toInt(), 0xFF0077B6.toInt(), 0xFF03045E.toInt())
        )

        val CANDY = Palette(
            "Candy",
            intArrayOf(0xFFFFADAD.toInt(), 0xFFFFD6A5.toInt(), 0xFFFDFFB6.toInt(), 0xFFCAFFBF.toInt(), 0xFF9BF6FF.toInt())
        )

        val MONO_GLOW = Palette(
            "Mono glow",
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFB9BEC7.toInt(), 0xFF8A8F98.toInt(), 0xFF43474E.toInt())
        )

        /** Readable by lightness alone, for colour-blind safety. */
        val VIRIDIS = Palette(
            "Colour-safe",
            intArrayOf(0xFF440154.toInt(), 0xFF3B528B.toInt(), 0xFF21918C.toInt(), 0xFF5EC962.toInt(), 0xFFFDE725.toInt())
        )

        /** The feed's rare runs wear it; it can be picked like any other. */
        val GOLD = Palette(
            "Gold",
            intArrayOf(0xFFFFF3B0.toInt(), 0xFFFFD166.toInt(), 0xFFE9A23B.toInt(), 0xFFC27C0E.toInt(), 0xFF8A5A00.toInt())
        )

        val ALL = listOf(SPECTRUM, AURORA, EMBER, OCEAN, CANDY, MONO_GLOW, VIRIDIS, GOLD)

        val NAMES: List<String> = ALL.map { it.name }

        /** What a remix may deal itself: everything but Gold, which is the rare runs' own. */
        val REMIXABLE: List<String> = NAMES.filter { it != "Gold" }

        fun byName(name: String): Palette = ALL.firstOrNull { it.name == name } ?: SPECTRUM

        fun byIndex(i: Int): Palette = ALL[i.coerceIn(0, ALL.size - 1)]

        // UI tokens, mirrored from the design system in docs/DESIGN.md.
        val BG: Int = 0xFF07080C.toInt()
        val TEXT_PRIMARY: Int = 0xFFF2F4F8.toInt()
        val TEXT_SECONDARY: Int = 0xFF9AA3B2.toInt()
        val ACCENT: Int = 0xFF7C5CFF.toInt()
        val SUCCESS: Int = 0xFF3DDC97.toInt()
        val WARNING: Int = 0xFFFFB020.toInt()
        val DANGER: Int = 0xFFFF5470.toInt()
    }
}
