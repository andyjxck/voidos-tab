package com.andysocial.voidos.ui

import androidx.compose.runtime.mutableStateOf
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

    /** The wheel in the window over other apps (EdgeOverlay), fed by its edge strips. */
    @Volatile var overlaySink: Sink? = null

    /** Opens the overlay wheel pinned, without a swipe: true for the tab wheel, false for the app wheel. */
    val overlayRequest = mutableStateOf<Boolean?>(null)

    interface Sink {
        fun begin(left: Boolean, x: Float, y: Float, t: Long)
        fun move(x: Float, y: Float, t: Long)
        fun end(x: Float, y: Float, t: Long)
        fun cancel()
    }

    /**
     * Open a wheel without a swipe, pinned open for taps: true is the tab wheel (left),
     * false the app wheel. The page dots and the edge handles use this, so getting
     * around never depends on one gesture.
     */
    val request = mutableStateOf<Boolean?>(null)

    /** Settings > About > Edge touch debug: draws the edge zones and the last touch on screen. */
    val debug = mutableStateOf(false)

    /** The last thing the router decided, for the debug overlay. */
    val last = mutableStateOf("no touch yet")

    /** Last touch-down point in window pixels, for the debug overlay. */
    val lastDown = mutableStateOf<Pair<Float, Float>?>(null)

    /**
     * Releasing a swipe only picks a slot when the finger stayed on the wheel at least this
     * long and rested on that slot for [REST_MS]. A quick swipe in from the edge (what
     * everyone does first) leaves the wheel open to tap instead. Before 1.8.0 that quick
     * swipe picked the middle slot on release and closed the wheel in 140 ms, so on the
     * tablet it looked like a page change with no wheel at all.
     */
    const val HOLD_MS = 420L
    const val REST_MS = 120L

    /** What's keeping the edges off, if anything. */
    @Volatile var offReason = ""

    const val TAG = "VoidEdge"
    fun log(msg: String) {
        last.value = msg
        try { android.util.Log.i(TAG, msg) } catch (_: Throwable) { }
    }
}

/** Whether letting go should pick the slot under the finger (true) or leave the wheel open to tap (false). */
fun releasePicks(moved: Boolean, beganAt: Long, selAt: Long, upAt: Long): Boolean =
    moved && upAt - beganAt >= EdgeSwipe.HOLD_MS && upAt - selAt >= EdgeSwipe.REST_MS

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
