package app.hypnotica.sims.algorithms

import app.hypnotica.core.render.G

/**
 * Numbers drawn as seven-segment figures, for sims whose numbers are the
 * subject (a Sudoku's digits, the sieve's grid). The renderer has no text, and
 * a figure built from the same anti-aliased bars as everything else keeps the
 * weight of the strokes consistent with the rest of the frame.
 */
internal object SegDigits {

    /** Segments a..g as bits 0..6: top, upper right, lower right, bottom, lower left, upper left, middle. */
    private val MASKS = intArrayOf(63, 6, 91, 79, 102, 109, 125, 7, 127, 111)

    /** Width of one figure as a share of its height. */
    const val ASPECT = 0.56f

    /**
     * One digit centred on ([cx], [cy]), [h] tall. The segments stop short of
     * each other, the gap a fifth of a stroke, so the figure reads as built of
     * bars rather than a box with notches.
     */
    fun digit(g: G, cx: Float, cy: Float, h: Float, d: Int, color: Int, alpha: Float = 1f) {
        if (d !in 0..9 || h <= 0f) return
        val m = MASKS[d]
        val w = h * ASPECT
        val t = h * 0.14f
        val hw = w * 0.5f - t * 0.5f
        val hh = h * 0.5f - t * 0.5f
        val gap = t * 0.2f
        val xl = cx - hw; val xr = cx + hw
        val yt = cy + hh; val yb = cy - hh
        // Horizontal bars run between the verticals' centre lines, less the gap.
        val horiz = hw - gap - t * 0.5f
        val vert = hh * 0.5f - gap - t * 0.25f
        if (m and 1 != 0) g.rect(cx, yt, horiz, t * 0.5f, color, alpha = alpha)
        if (m and 2 != 0) g.rect(xr, cy + hh * 0.5f, t * 0.5f, vert, color, alpha = alpha)
        if (m and 4 != 0) g.rect(xr, cy - hh * 0.5f, t * 0.5f, vert, color, alpha = alpha)
        if (m and 8 != 0) g.rect(cx, yb, horiz, t * 0.5f, color, alpha = alpha)
        if (m and 16 != 0) g.rect(xl, cy - hh * 0.5f, t * 0.5f, vert, color, alpha = alpha)
        if (m and 32 != 0) g.rect(xl, cy + hh * 0.5f, t * 0.5f, vert, color, alpha = alpha)
        if (m and 64 != 0) g.rect(cx, cy, horiz, t * 0.5f, color, alpha = alpha)
    }

    /** How many bars [n] takes, so a caller can reserve room for a grid of them. */
    fun digitsIn(n: Int): Int {
        var k = 1
        var v = n
        while (v >= 10) { v /= 10; k++ }
        return k
    }

    /** A whole number centred on ([cx], [cy]); figures spaced by a quarter of their width. */
    fun number(g: G, cx: Float, cy: Float, h: Float, n: Int, color: Int, alpha: Float = 1f) {
        if (n < 0) return
        val k = digitsIn(n)
        val w = h * ASPECT
        val pitch = w * 1.28f
        var x = cx + (k - 1) * pitch * 0.5f
        var v = n
        for (i in 0 until k) {
            digit(g, x, cy, h, v % 10, color, alpha)
            v /= 10
            x -= pitch
        }
    }

    /** Total width [number] draws for a figure count of [k] at height [h]. */
    fun width(k: Int, h: Float): Float = h * ASPECT * (1f + (k - 1) * 1.28f)
}
