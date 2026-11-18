package com.andysocial.voidos.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/*
 * Moving, stretching and turning things on the face.
 *
 * One finger held still for a moment picks a widget (or the clock) up, and it
 * follows the finger. Two fingers on it pinch: spread them sideways to make it
 * wider, up and down to make it taller, and twist to turn it. The clock keeps
 * its shape, so a pinch only makes it bigger or smaller. A finger that moves
 * straight away is a swipe and goes to the pages, and a quick tap still opens
 * the widget's screen.
 *
 * The handler sits on the whole face, not on each widget, so the second finger
 * counts even when it lands just outside a small widget.
 */

/** A box on the face in pixels: its centre, its unturned width and height, and its turn in degrees. */
data class FaceBox(val cx: Float, val cy: Float, val w: Float, val h: Float, val rot: Float) {
    val centre get() = Offset(cx, cy)

    /** True when [p] is inside the box once the box's turn is undone, with [slop] pixels to spare. */
    fun contains(p: Offset, slop: Float = 0f): Boolean {
        val l = toLocal(p - centre, rot)
        return abs(l.x) <= w / 2f + slop && abs(l.y) <= h / 2f + slop
    }

    /** Half the width and height of the upright box that holds this one once turned. */
    fun halfExtent(): Offset {
        val r = Math.toRadians(rot.toDouble())
        val c = abs(cos(r)).toFloat(); val s = abs(sin(r)).toFloat()
        return Offset((w * c + h * s) / 2f, (w * s + h * c) / 2f)
    }
}

/** One thing the face can pick up. [uniform] keeps its shape when pinched (the clock). */
class FaceTarget(val id: Long, val box: FaceBox, val uniform: Boolean, val minW: Float, val maxW: Float, val minH: Float, val maxH: Float)

const val CLOCK_TARGET = -1L

/** [v] seen from inside a box turned [deg] degrees. */
internal fun toLocal(v: Offset, deg: Float): Offset {
    val r = Math.toRadians(-deg.toDouble())
    val c = cos(r).toFloat(); val s = sin(r).toFloat()
    return Offset(v.x * c - v.y * s, v.x * s + v.y * c)
}

/** Damping for the stretch: small finger gaps on one axis don't make that side jump. */
private const val STRETCH_DAMP = 48f

/**
 * One step of a two-finger gesture: fingers went from [a0], [b0] to [a1], [b1].
 * The box follows their midpoint, turns with them, and stretches along its own
 * width and height by how far the fingers spread along each (or evenly when
 * [uniform]).
 */
internal fun pinchStep(t: FaceTarget, box: FaceBox, a0: Offset, b0: Offset, a1: Offset, b1: Offset): FaceBox {
    val v0 = b0 - a0; val v1 = b1 - a1
    val mid0 = (a0 + b0) / 2f; val mid1 = (a1 + b1) / 2f
    val turn = Math.toDegrees((atan2(v1.y, v1.x) - atan2(v0.y, v0.x)).toDouble()).toFloat().let {
        when { it > 180f -> it - 360f; it < -180f -> it + 360f; else -> it }
    }
    var w = box.w; var h = box.h
    if (t.uniform) {
        val d0 = v0.getDistance(); val d1 = v1.getDistance()
        if (d0 > 1f) {
            val f = (d1 / d0).coerceIn(t.minW / box.w, t.maxW / box.w).coerceIn(t.minH / box.h, t.maxH / box.h)
            w *= f; h *= f
        }
    } else {
        val l0 = toLocal(v0, box.rot); val l1 = toLocal(v1, box.rot)
        w = (w * (abs(l1.x) + STRETCH_DAMP) / (abs(l0.x) + STRETCH_DAMP)).coerceIn(t.minW, t.maxW)
        h = (h * (abs(l1.y) + STRETCH_DAMP) / (abs(l0.y) + STRETCH_DAMP)).coerceIn(t.minH, t.maxH)
    }
    val c = box.centre + (mid1 - mid0)
    return FaceBox(c.x, c.y, w, h, box.rot + turn)
}

/** Keeps most of a box on the face: at most a quarter of it can hang over an edge. */
internal fun keepOn(box: FaceBox, faceW: Float, faceH: Float): FaceBox {
    val e = box.halfExtent()
    fun clamp(v: Float, half: Float, size: Float) = if (half >= size) size / 2f else v.coerceIn(half / 2f, size - half / 2f)
    return box.copy(cx = clamp(box.cx, e.x, faceW), cy = clamp(box.cy, e.y, faceH))
}

/**
 * The face's pick-up, drag, pinch and turn handler. [targets] lists what can
 * be picked up, the clock first and the topmost widget last. [onChange] gets
 * the live box on every move; [onEnd] when the last finger lifts.
 */
@Composable
fun Modifier.faceGestures(
    enabled: Boolean,
    faceW: Float,
    faceH: Float,
    targets: () -> List<FaceTarget>,
    onChange: (Long, FaceBox) -> Unit,
    onEnd: (Long, FaceBox) -> Unit,
): Modifier {
    val list by rememberUpdatedState(targets)
    val change by rememberUpdatedState(onChange)
    val end by rememberUpdatedState(onEnd)
    if (!enabled) return this
    return this.pointerInput(faceW, faceH) {
        val hold = viewConfiguration.longPressTimeoutMillis
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            // the Initial pass sees each touch before the widget under it, so once we
            // pick something up, its tap and the page swipe both stand down
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val t = list().lastOrNull { it.box.contains(down.position, 8f) } ?: return@awaitEachGesture
            var box = t.box
            var active = false
            val start = System.currentTimeMillis()
            var prev: Map<PointerId, Offset> = mapOf(down.id to down.position)
            while (true) {
                val wait = hold - (System.currentTimeMillis() - start)
                val ev = if (active) awaitPointerEvent(PointerEventPass.Initial)
                else if (wait <= 0) null
                else withTimeoutOrNull(wait) { awaitPointerEvent(PointerEventPass.Initial) }
                if (ev == null) {
                    // held still long enough: picked up
                    active = true
                    change(t.id, box)
                    continue
                }
                val pressed = ev.changes.filter { it.pressed }
                if (pressed.isEmpty()) {
                    if (active) ev.changes.forEach { it.consume() }
                    break
                }
                if (!active) {
                    if (pressed.size >= 2) active = true
                    else {
                        val c = pressed.first()
                        // moved before the hold finished: a swipe, not ours
                        if (c.id == down.id && (c.position - down.position).getDistance() > slop) return@awaitEachGesture
                        if (c.isConsumed) return@awaitEachGesture
                    }
                }
                val cur = pressed.associate { it.id to it.position }
                if (active) {
                    val both = cur.keys.filter { it in prev }
                    box = when {
                        both.size >= 2 -> pinchStep(t, box, prev.getValue(both[0]), prev.getValue(both[1]), cur.getValue(both[0]), cur.getValue(both[1]))
                        both.size == 1 -> (cur.getValue(both[0]) - prev.getValue(both[0])).let { d -> box.copy(cx = box.cx + d.x, cy = box.cy + d.y) }
                        else -> box
                    }
                    box = keepOn(box, faceW, faceH)
                    change(t.id, box)
                    ev.changes.forEach { it.consume() }
                }
                prev = cur
            }
            if (active) end(t.id, box)
        }
    }
}
