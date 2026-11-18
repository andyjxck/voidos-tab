package com.andysocial.voidos.ui

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Edge swipes for the wheels, decided before anything else on screen sees the touch.
 *
 * MainActivity hands every touch to [EdgeRouter] first. The old version left this to
 * thin strips inside the Compose tree, and on the tablet a swipe from the bezel often
 * landed its first point outside them (the touch panel ignores the outer few
 * millimetres), so the pager took it and turned the page instead. Now:
 *
 *  - a touch that starts in the hard zone (the outer [HARD_DP]) belongs to the wheel
 *    straight away, nothing underneath ever sees it;
 *  - a touch that starts a little further in (up to [SOFT_DP]) still opens the wheel when
 *    its first movement is a quick flick inwards, which is what a swipe from the bezel
 *    looks like once the panel picks it up. Anything else there passes through.
 */
object EdgeSwipe {
    const val HARD_DP = 34f
    const val SOFT_DP = 64f
    const val SLOP_DP = 10f
    const val FLICK_MS = 220L

    /** Height Android 10 lets an app keep for itself on each side edge (gesture navigation). */
    const val EXCLUSION_DP = 200f

    /** True while the wheels may open: the main screens are up, nothing on top, no wheel pinned open. */
    @Volatile var enabled = false

    /** The wheels on screen, fed in window pixels. */
    @Volatile var sink: Sink? = null

    interface Sink {
        fun begin(left: Boolean, x: Float, y: Float)
        fun move(x: Float, y: Float)
        fun end(x: Float, y: Float)
        fun cancel()
    }
}

/** What the activity does with one touch event. */
enum class EdgeAction {
    /** Not ours: hand it to the screen as usual. */
    PASS,

    /** Ours: the wheel has it, the screen never sees it. */
    CONSUME,

    /** Ours from now on: cancel what the screen already started, then the wheel has it. */
    CLAIM,
}

/**
 * The decision, kept free of Android so it can be tested with plain numbers.
 * Coordinates are window pixels; [density] turns the dp limits into pixels.
 */
class EdgeRouter(private val density: Float) {
    private enum class S { IDLE, WATCH, OURS, IGNORE }

    private var s = S.IDLE
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    var left = true
        private set

    val hardPx get() = EdgeSwipe.HARD_DP * density
    val softPx get() = EdgeSwipe.SOFT_DP * density
    private val slopPx get() = EdgeSwipe.SLOP_DP * density

    /** Whether a gesture is currently owned by the wheel. */
    val owned get() = s == S.OURS

    fun down(x: Float, y: Float, width: Float, t: Long, enabled: Boolean): EdgeAction {
        downX = x; downY = y; downT = t
        if (!enabled || width <= 0f) { s = S.IGNORE; return EdgeAction.PASS }
        val fromLeft = x
        val fromRight = width - x
        left = fromLeft <= fromRight
        val d = minOf(fromLeft, fromRight)
        s = when {
            d <= hardPx -> S.OURS
            d <= softPx -> S.WATCH
            else -> S.IGNORE
        }
        return if (s == S.OURS) EdgeAction.CONSUME else EdgeAction.PASS
    }

    fun move(x: Float, y: Float, t: Long): EdgeAction = when (s) {
        S.OURS -> EdgeAction.CONSUME
        S.WATCH -> {
            val dx = x - downX
            val dy = y - downY
            if (hypot(dx, dy) < slopPx) EdgeAction.PASS
            else {
                val inward = if (left) dx > 0 else dx < 0
                val flat = abs(dx) > abs(dy) * 1.4f
                val quick = t - downT <= EdgeSwipe.FLICK_MS
                if (inward && flat && quick) { s = S.OURS; EdgeAction.CLAIM } else { s = S.IGNORE; EdgeAction.PASS }
            }
        }
        else -> EdgeAction.PASS
    }

    /** The finger lifted (or the gesture was cancelled when [cancelled]). */
    fun up(cancelled: Boolean = false): EdgeAction {
        val was = s
        s = S.IDLE
        return if (was == S.OURS) EdgeAction.CONSUME else EdgeAction.PASS
    }

    val startX get() = downX
    val startY get() = downY
}

/** The two side-edge strips Android should leave alone, centred on the screen's height (window px). */
fun edgeExclusion(width: Int, height: Int, density: Float): List<IntArray> {
    if (width <= 0 || height <= 0) return emptyList()
    val w = (EdgeSwipe.SOFT_DP * density).toInt()
    val h = minOf(height, (EdgeSwipe.EXCLUSION_DP * density).toInt())
    val top = (height - h) / 2
    return listOf(intArrayOf(0, top, w, top + h), intArrayOf(width - w, top, width, top + h))
}
