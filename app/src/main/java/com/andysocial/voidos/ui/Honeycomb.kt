package com.andysocial.voidos.ui

import androidx.compose.ui.platform.testTag

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
import androidx.compose.runtime.LaunchedEffect
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
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

/** Up to this many apps sit as one still, centred cluster; more become the pannable lens comb. */
internal const val HONEY_COMPACT = 12

/** The smallest and largest a honeycomb cell may be: about 7 across a portrait page at the smallest. */
internal val HONEY_MIN_CELL = 84.dp
internal val HONEY_MAX_CELL = 196.dp

/**
 * The rows of a small honeycomb of [n] cells, top to bottom, picked to sit well in a
 * viewport [aspect] (width / height) wide. Neighbouring rows differ by one cell, so each
 * row centred under the last packs into true hexagons ([2, 3] for five, [2, 3, 2] for
 * seven), and a mirror-image shape is preferred, so a few apps make a tidy cluster
 * rather than a broken ring.
 */
internal fun honeyRows(n: Int, aspect: Float): List<Int> {
    if (n <= 0) return emptyList()
    if (n == 1) return listOf(1)
    var best: List<Int> = listOf(n)
    var bestScore = Float.MAX_VALUE
    // the cluster leans towards the page's shape, but only partly: a compact clump reads best
    val target = sqrt(aspect.coerceIn(0.4f, 2.5f))
    fun score(rows: List<Int>): Float {
        val wide = rows.max().toFloat()
        val tall = 1f + (rows.size - 1) * sqrt(3f) / 2f
        val shape = abs(ln((wide / tall) / target))
        val lopsided = if (rows != rows.reversed()) 0.25f else 0f
        val ragged = (rows.max() - rows.min()) * 0.12f
        val empty = 0.8f * (wide * tall / n)
        // a row narrower than both its neighbours looks like a waist pinched in
        val pinch = (1 until rows.size - 1).count { rows[it] < rows[it - 1] && rows[it] < rows[it + 1] }.toFloat()
        return shape + lopsided + ragged + empty + pinch
    }
    fun grow(rows: MutableList<Int>, left: Int) {
        if (left == 0) { val sc = score(rows); if (sc < bestScore) { bestScore = sc; best = rows.toList() }; return }
        if (rows.size >= 6) return
        val last = rows.last()
        for (next in listOf(last - 1, last + 1)) if (next in 1..left && next <= 7) {
            rows.add(next); grow(rows, left - next); rows.removeAt(rows.size - 1)
        }
    }
    for (first in 1..minOf(n, 7)) grow(mutableListOf(first), n - first)
    return best
}

/** The cells of [honeyRows], centred on the middle, in units of one cell's spacing. */
internal fun honeyCluster(n: Int, aspect: Float): List<Offset> {
    val rows = honeyRows(n, aspect)
    val dy = sqrt(3f) / 2f
    val top = -(rows.size - 1) * dy / 2f
    return rows.flatMapIndexed { r, count ->
        List(count) { i -> Offset(i - (count - 1) / 2f, top + r * dy) }
    }
}

/**
 * The cells of a honeycomb laid out in [rows] (counts top to bottom), centred on the middle, in
 * units of one cell's spacing. Each row is centred under the last; when a row's count would put it
 * straight under the row above (same odd/even), it slides half a cell so it still packs into hexagons.
 */
internal fun honeyCells(rows: List<Int>): List<Offset> {
    val dy = sqrt(3f) / 2f
    val top = -(rows.size - 1) * dy / 2f
    val out = ArrayList<Offset>(rows.sum())
    var prevHalf = false
    rows.forEachIndexed { r, count ->
        var start = -(count - 1) / 2f
        val half = count % 2 == 0
        if (r > 0 && half == prevHalf) start -= 0.5f
        prevHalf = if (r > 0 && half == prevHalf) !half else half
        for (i in 0 until count) out.add(Offset(start + i, top + r * dy))
    }
    // a slid row leaves the whole comb half a cell off centre; centre it again
    val cx = if (out.isEmpty()) 0f else (out.maxOf { it.x } + out.minOf { it.x }) / 2f
    return if (abs(cx) < 0.001f) out else out.map { Offset(it.x - cx, it.y) }
}

/** Rows of [cols] and [cols] - 1 in turn, the honeycomb's own brick pattern, holding [n] cells. */
internal fun honeyBrick(n: Int, cols: Int): List<Int> {
    val rows = ArrayList<Int>()
    var left = n
    var r = 0
    while (left > 0) {
        val c = if (cols <= 1) 1 else if (r % 2 == 0) cols else cols - 1
        rows.add(minOf(c, left)); left -= c; r++
    }
    return rows
}

/** How many cells wide and tall a set of [cells] is, edge to edge. */
internal fun honeySpan(cells: List<Offset>): Pair<Float, Float> =
    if (cells.isEmpty()) 0f to 0f
    else (cells.maxOf { it.x } - cells.minOf { it.x } + 1f) to (cells.maxOf { it.y } - cells.minOf { it.y } + 1f)

/**
 * Where the apps sit: [cells] in units of one cell, [cell] the spacing in px, and [overflow] how many
 * px of comb are hidden above and below together (0 when it all fits, which is the usual case).
 */
internal data class HoneyLayout(val cells: List<Offset>, val rows: List<Int>, val cell: Float, val overflow: Float)

/** The share of the page the comb may cover, so it never touches the edges. */
internal const val HONEY_FILL_W = 0.94f
internal const val HONEY_FILL_H = 0.90f

/**
 * Lays [n] apps out to fill a [w] x [h] px page. Every honeycomb shape that could hold them is tried:
 * the tidy small clusters ([honeyRows]) and brick rows of every width. The one whose cells come out
 * biggest wins, so a wide landscape page gets wide rows and a tall portrait page gets more rows,
 * instead of one round clump in the middle with empty sides. Cells are kept between [minCell] and
 * [maxCell] px; when even [minCell] won't fit them all, the rows fill the width and the comb pans
 * up and down.
 */
internal fun honeyLayout(n: Int, w: Float, h: Float, minCell: Float, maxCell: Float): HoneyLayout {
    if (n <= 0 || w <= 0f || h <= 0f) return HoneyLayout(emptyList(), emptyList(), maxCell.coerceAtLeast(1f), 0f)
    val aw = w * HONEY_FILL_W; val ah = h * HONEY_FILL_H
    fun fit(cells: List<Offset>): Float { val (sw, sh) = honeySpan(cells); return minOf(aw / sw, ah / sh) }
    data class Cand(val rows: List<Int>, val cells: List<Offset>, val size: Float, val tidy: Boolean)
    val cands = ArrayList<Cand>()
    if (n <= HONEY_COMPACT) {
        val rows = honeyRows(n, w / h)
        val cells = honeyCells(rows)
        cands.add(Cand(rows, cells, fit(cells), true))
    }
    for (cols in 1..minOf(n, 24)) {
        val rows = honeyBrick(n, cols)
        val cells = honeyCells(rows)
        cands.add(Cand(rows, cells, fit(cells), false))
    }
    val top = cands.maxOf { minOf(it.size, maxCell) }
    if (top >= minCell) {
        // among the shapes within a few percent of the biggest, take a tidy cluster, then the fewest
        // half-empty rows, then the most even, mirror-image shape
        val near = cands.filter { minOf(it.size, maxCell) >= top * 0.97f }
        val pick = near.minWith(compareBy<Cand>({ !it.tidy }, { it.rows.size > 1 && it.rows.last() < it.rows.max() - 1 },
            { if (it.rows == it.rows.reversed()) 0 else 1 }, { -minOf(it.size, maxCell) }))
        return HoneyLayout(pick.cells, pick.rows, minOf(pick.size, maxCell), 0f)
    }
    // too many to fit: the widest brick whose cells are still at least minCell, filling the width
    val cols = (1..minOf(n, 24)).lastOrNull { c -> aw / honeySpan(honeyCells(honeyBrick(n, c))).first >= minCell } ?: 1
    val rows = honeyBrick(n, cols)
    val cells = honeyCells(rows)
    val cell = minOf(aw / honeySpan(cells).first, maxCell)
    val contentH = honeySpan(cells).second * cell
    return HoneyLayout(cells, rows, cell, (contentH - ah).coerceAtLeast(0f))
}

/** Icons are this share of their cell; the rest is the gap between them. */
internal const val HONEY_ICON = 0.84f

/**
 * How large an icon is when the comb pans: full size anywhere it's wholly on the page, easing
 * smaller only as it slides past the top or bottom edge. [y] is the icon's centre from the middle,
 * [halfH] half the page's height, [cell] the spacing.
 */
internal fun honeyEdgeScale(y: Float, halfH: Float, cell: Float): Float {
    val inside = halfH - abs(y)   // how far the centre is inside the edge
    val f = ((inside + cell * 0.5f) / cell).coerceIn(0f, 1f)
    return f * f * (3 - 2 * f)
}

/**
 * Apps as a honeycomb: round icons packed in hexagons, laid out to fill the page in either
 * orientation ([honeyLayout]): the icon size, the gaps and the rows all follow the page's size and
 * shape and how many apps there are. Tap to open. Press, hold and drag an app up to the Home drop at
 * the top to open it beside the clock in split view. When there are too many to fit at a usable size,
 * the comb pans up and down, easing icons smaller as they pass the top or bottom edge.
 * [bottomInset] keeps the comb clear of the starred-apps dock.
 */
@Composable
fun HoneycombApps(store: Store, apps: List<AppEntry>, onOpen: (AppEntry) -> Unit, onSplit: (AppEntry) -> Unit, bottomInset: androidx.compose.ui.unit.Dp = 0.dp) {
    val density = LocalDensity.current
    val px = remember { Animatable(0f) }
    val py = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf<AppEntry?>(null) }
    val dragX = remember { mutableFloatStateOf(0f) }
    val dragY = remember { mutableFloatStateOf(0f) }
    var overHome by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize().testTag("honeycomb")) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val inset = with(density) { bottomInset.toPx() }.coerceIn(0f, h * 0.3f)
        val hh = h - inset            // the part of the page the comb fills
        val minCell = with(density) { HONEY_MIN_CELL.toPx() }
        val maxCell = with(density) { HONEY_MAX_CELL.toPx() }
        val lay = remember(apps.size, w.roundToInt(), hh.roundToInt(), minCell, maxCell) { honeyLayout(apps.size, w, hh, minCell, maxCell) }
        val cells = lay.cells
        val cell = lay.cell
        val cellNow = with(density) { cell.toDp() }
        val iconNow = cellNow * HONEY_ICON
        val panning = lay.overflow > 0f
        // pans up and down only, and only when the comb is taller than the page
        val limit = lay.overflow / 2f
        val half = hh / 2f
        LaunchedEffect(lay) { px.snapTo(0f); if (!panning) py.snapTo(0f) else py.snapTo(py.value.coerceIn(-limit, limit)) }
        val spanX = honeySpan(cells).first * cell / 2f
        fun clampBack() = scope.launch {
            if (abs(py.value) > limit) launch { py.animateTo(py.value.coerceIn(-limit, limit), spring(0.8f, 300f)) }
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
                        if (!moved && hypot(ch.position.x - down.position.x, ch.position.y - down.position.y) > viewConfiguration.touchSlop) {
                            // a sideways swipe turns the page unless there's comb left to see that way
                            val dx = ch.position.x - down.position.x; val dy = ch.position.y - down.position.y
                            if (abs(dx) > abs(dy) * 1.4f && !combLeftThatWay(dx, px.value, spanX, w, limit)) break
                            moved = true
                        }
                        if (moved) {
                            ch.consume()
                            vt.addPosition(ch.uptimeMillis, ch.position)
                            // rubber band past the comb's edge
                            val over = (abs(py.value) - limit).coerceAtLeast(0f)
                            val damp = 1f / (1f + over / (cell * 0.8f))
                            scope.launch { py.snapTo(py.value + dlt.y * damp) }
                        }
                    }
                    if (moved && dragging == null) {
                        val v = vt.calculateVelocity()
                        scope.launch {
                            val ty = (py.value + v.y * 0.18f).coerceIn(-limit, limit)
                            launch { py.animateTo(ty, spring(0.85f, 120f), v.y) }
                        }
                    }
                }
            },
        ) {
            apps.forEachIndexed { i, app ->
                val c = cells[i]
                Box(
                    Modifier
                        .offset {
                            IntOffset((w / 2 + c.x * cell + px.value - cell / 2).roundToInt(), (half + c.y * cell + py.value - cell / 2).roundToInt())
                        }
                        .size(cellNow)
                        .graphicsLayer {
                            val x = c.x * cell + px.value; val y = c.y * cell + py.value
                            val s = if (!panning) 1f else honeyEdgeScale(y, half, cell)
                            // icons drift slightly towards the middle as they shrink past the edge
                            translationY = -y * (1f - s) * 0.12f
                            scaleX = s; scaleY = s
                            alpha = if (dragging == app) 0.25f else s.coerceIn(0f, 1f)
                        }
                        .semantics { contentDescription = app.label }
                        .pointerInput(app.pkg) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { o ->
                                    dragging = app
                                    dragX.floatValue = w / 2 + c.x * cell + px.value - cell / 2 + o.x
                                    dragY.floatValue = half + c.y * cell + py.value - cell / 2 + o.y
                                },
                                onDrag = { ch, d -> ch.consume(); dragX.floatValue += d.x; dragY.floatValue += d.y; overHome = dragY.floatValue < h * 0.16f },
                                onDragEnd = { val a = dragging; dragging = null; if (overHome && a != null) onSplit(a); overHome = false },
                                onDragCancel = { dragging = null; overHome = false },
                            )
                        }
                        .tap { onOpen(app) },
                    contentAlignment = Alignment.Center,
                ) { AppGlyph(app, store.iconStyle, iconNow) }
            }
            // the Home drop, while an app is being dragged
            val d = dragging
            if (d != null) {
                Box(Modifier.fillMaxSize().padding(top = 12.dp), contentAlignment = Alignment.TopCenter) {
                    Box(
                        Modifier.droplet(RoundedCornerShape(50), if (overHome) V.Teal else V.Purple, if (overHome) 2f else 1f).padding(horizontal = 24.dp, vertical = 14.dp),
                    ) { VText(if (overHome) "Open beside the clock" else "Beside the clock", size = Type.headline, color = androidx.compose.ui.graphics.Color.White) }
                }
                Box(
                    Modifier.offset { IntOffset((dragX.floatValue - cell / 2).roundToInt(), (dragY.floatValue - cell / 2).roundToInt()) }.size(cellNow)
                        .graphicsLayer { scaleX = 1.12f; scaleY = 1.12f },
                    contentAlignment = Alignment.Center,
                ) { AppGlyph(d, store.iconStyle, iconNow) }
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
        // the app takes the right half of the book on Home (ui/Book.kt), so its close button shows
        Book.drop(ctx, Pane.app(pkg), toFirst = false)
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

/**
 * True when a sideways drag of [dx] has comb still hidden that way: the comb is wider
 * than the screen and hasn't been panned to that edge yet. Otherwise the swipe is the page's.
 */
internal fun combLeftThatWay(dx: Float, panX: Float, spanX: Float, width: Float, limit: Float = Float.MAX_VALUE): Boolean {
    val room = minOf(spanX - width / 2f, limit)
    if (room <= 1f) return false
    // dragging left (dx < 0) shows the right side: there's more while panX > -room
    return if (dx < 0f) panX > -room + 1f else panX < room - 1f
}
