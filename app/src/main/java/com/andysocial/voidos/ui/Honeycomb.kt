package com.andysocial.voidos.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andysocial.voidos.Store
import com.andysocial.voidos.Su
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The [n] cells of a hexagonal honeycomb, spiralling out from the middle: ring 0 is the
 * centre, ring k has 6k cells. Returned as offsets in units of one cell's spacing.
 */
internal fun honeycomb(n: Int): List<Offset> {
    val out = ArrayList<Offset>(n)
    if (n <= 0) return out
    out.add(Offset.Zero)
    // axial directions round a hexagon, starting so ring k begins on the left
    val dirs = listOf(1 to -1, 1 to 0, 0 to 1, -1 to 1, -1 to 0, 0 to -1)
    var ring = 1
    while (out.size < n) {
        var q = -ring; var r = 0
        for ((dq, dr) in dirs) for (s in 0 until ring) {
            if (out.size >= n) return out
            out.add(Offset(q + r / 2f, r * sqrt(3f) / 2f))
            q += dq; r += dr
        }
        ring++
    }
    return out
}

/** How large an icon is at [d] from the middle of a viewport of half-size [half]: full size in the middle, shrinking to nothing past the edge. */
internal fun honeyScale(d: Float, half: Float): Float {
    val f = (d / half).coerceAtLeast(0f)
    return when {
        f < 0.55f -> 1f
        f > 1.15f -> 0f
        else -> (1f - (f - 0.55f) / 0.6f).let { it * it * (3 - 2 * it) }
    }
}

/**
 * Apps as a honeycomb, like a watch's app view: round icons packed in hexagons, full size in
 * the middle and shrinking towards the edges. Drag to pan (it glides and springs back inside
 * the comb), tap to open. Press, hold and drag an app up to the Home drop at the top to open
 * it beside the clock in split view.
 */
@Composable
fun HoneycombApps(store: Store, apps: List<AppEntry>, onOpen: (AppEntry) -> Unit, onSplit: (AppEntry) -> Unit) {
    val density = LocalDensity.current
    val cellDp = 112.dp
    val iconDp = 92.dp
    val cell = with(density) { cellDp.toPx() }
    val cells = remember(apps.size) { honeycomb(apps.size) }
    val maxR = remember(cells) { cells.maxOfOrNull { hypot(it.x, it.y) } ?: 0f }
    val px = remember { Animatable(0f) }
    val py = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf<AppEntry?>(null) }
    val dragX = remember { mutableFloatStateOf(0f) }
    val dragY = remember { mutableFloatStateOf(0f) }
    var overHome by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val half = minOf(w, h) / 2f
        val limit = (maxR * cell - half * 0.4f).coerceAtLeast(0f)
        fun clampBack() = scope.launch {
            val d = hypot(px.value, py.value)
            if (d > limit) {
                val k = if (d == 0f) 0f else limit / d
                launch { px.animateTo(px.value * k, spring(0.8f, 300f)) }
                launch { py.animateTo(py.value * k, spring(0.8f, 300f)) }
            }
        }
        Box(
            Modifier.fillMaxSize().pointerInput(limit) {
                val vt = VelocityTracker()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    vt.resetTracking(); vt.addPosition(down.uptimeMillis, down.position)
                    scope.launch { px.stop(); py.stop() }
                    var moved = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        if (dragging != null) break
                        val dlt = ch.positionChange()
                        if (!moved && hypot(ch.position.x - down.position.x, ch.position.y - down.position.y) > viewConfiguration.touchSlop) moved = true
                        if (moved) {
                            ch.consume()
                            vt.addPosition(ch.uptimeMillis, ch.position)
                            // rubber band past the comb's edge
                            val over = (hypot(px.value, py.value) - limit).coerceAtLeast(0f)
                            val damp = 1f / (1f + over / (cell * 0.8f))
                            scope.launch { px.snapTo(px.value + dlt.x * damp); py.snapTo(py.value + dlt.y * damp) }
                        }
                    }
                    if (moved && dragging == null) {
                        val v = vt.calculateVelocity()
                        scope.launch {
                            val tx = px.value + v.x * 0.18f; val ty = py.value + v.y * 0.18f
                            val d = hypot(tx, ty); val k = if (d > limit && d > 0f) limit / d else 1f
                            launch { px.animateTo(tx * k, spring(0.85f, 120f), v.x) }
                            launch { py.animateTo(ty * k, spring(0.85f, 120f), v.y) }
                        }
                    }
                }
            },
        ) {
            apps.forEachIndexed { i, app ->
                val c = cells[i]
                Box(
                    Modifier
                        .semantics { contentDescription = app.label }
                        .offset {
                            IntOffset((w / 2 + c.x * cell + px.value - cell / 2).roundToInt(), (h / 2 + c.y * cell + py.value - cell / 2).roundToInt())
                        }
                        .size(cellDp)
                        .graphicsLayer {
                            val x = c.x * cell + px.value; val y = c.y * cell + py.value
                            val s = honeyScale(hypot(x, y * (half / (h / 2f))), half)
                            // icons drift slightly towards the middle as they shrink, closing the comb up like a lens
                            translationX = -x * (1f - s) * 0.18f; translationY = -y * (1f - s) * 0.18f
                            scaleX = s; scaleY = s
                            alpha = if (dragging == app) 0.25f else s.coerceIn(0f, 1f)
                        }
                        .semantics { contentDescription = app.label }
                        .pointerInput(app.pkg) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { o ->
                                    dragging = app
                                    dragX.floatValue = w / 2 + c.x * cell + px.value - cell / 2 + o.x
                                    dragY.floatValue = h / 2 + c.y * cell + py.value - cell / 2 + o.y
                                },
                                onDrag = { ch, d -> ch.consume(); dragX.floatValue += d.x; dragY.floatValue += d.y; overHome = dragY.floatValue < h * 0.16f },
                                onDragEnd = { val a = dragging; dragging = null; if (overHome && a != null) onSplit(a); overHome = false },
                                onDragCancel = { dragging = null; overHome = false },
                            )
                        }
                        .tap { onOpen(app) },
                    contentAlignment = Alignment.Center,
                ) { AppGlyph(app, store.iconStyle, iconDp) }
            }
            // the Home drop, while an app is being dragged
            val d = dragging
            if (d != null) {
                Box(Modifier.fillMaxSize().padding(top = 12.dp), contentAlignment = Alignment.TopCenter) {
                    Box(
                        Modifier.droplet(RoundedCornerShape(50), if (overHome) V.Teal else V.Purple, if (overHome) 2f else 1f).padding(horizontal = 24.dp, vertical = 14.dp),
                    ) { VText(if (overHome) "Let go to open beside the clock" else "Drag here to open beside the clock", size = Type.headline, color = androidx.compose.ui.graphics.Color.White) }
                }
                Box(
                    Modifier.offset { IntOffset((dragX.floatValue - cell / 2).roundToInt(), (dragY.floatValue - cell / 2).roundToInt()) }.size(cellDp)
                        .graphicsLayer { scaleX = 1.12f; scaleY = 1.12f },
                    contentAlignment = Alignment.Center,
                ) { AppGlyph(d, store.iconStyle, iconDp) }
            }
        }
    }
}

/**
 * Split view: VoidOS's clock on the left, [pkg] on the right. Android 10 only splits the
 * screen outside the kiosk lock, so VoidOS steps out of it for the split and back in when
 * the split ends. Apps that don't declare split support are allowed in too.
 */
object Split {
    fun open(ctx: Context, store: Store, pkg: String): Boolean {
        val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString() ?: return false
        store.setSplit(pkg)
        (ctx as? com.andysocial.voidos.MainActivity)?.let { a -> runCatching { a.stopLockTask() } }
        val me = "${ctx.packageName}/.MainActivity"
        return Su.ok(
            "settings put global force_resizable_activities 1; " +
                "am start --windowingMode 3 -n $me >/dev/null; sleep 0.4; " +
                "am start --windowingMode 4 -n $launch >/dev/null",
            timeoutS = 15,
        )
    }

    /** Ends split view: VoidOS full screen again, back in its kiosk lock. */
    fun close(ctx: Context, store: Store) {
        store.setSplit("")
        Su.ok("am start --windowingMode 1 -n ${ctx.packageName}/.MainActivity >/dev/null", timeoutS = 10)
    }
}

