package com.andysocial.voidos.ui

import androidx.compose.ui.semantics.contentDescription

import androidx.compose.ui.semantics.semantics

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** One slot on a wheel. */
class WheelItem(val key: String, val label: String, val icon: @Composable (Modifier, Color) -> Unit)

enum class Edge { LEFT, RIGHT }

/** The wheel's shape, in dp. Its centre sits [inset] past the screen edge, so you see a C-shaped band. */
private object Geo {
    const val inset = 72f
    const val rIn = 134f
    const val rItem = 188f
    const val rOut = 244f
    const val zone = 22f
    const val hub = 26f
}

/**
 * The two edge wheels. Put a finger on the left edge and the tab wheel opens
 * under it; slide down, up or out and the highlight follows the angle of your
 * finger; let go and VoidOS moves to that screen. The right edge does the same
 * for the apps you have opened from VoidOS. A tap on an edge leaves the wheel
 * open so you can tap a slot instead.
 *
 * These are ordinary touches on VoidOS's own window: once another app is in
 * front, Android gives the edges to that app, so the wheels work only while
 * VoidOS is on screen.
 */
@Composable
fun EdgeWheels(
    tabs: List<WheelItem>,
    currentTab: String,
    onTab: (String) -> Unit,
    apps: List<WheelItem>,
    onApp: (String) -> Unit,
    onAllApps: () -> Unit,
    enabled: Boolean = true,
) {
    var side by remember { mutableStateOf<Edge?>(null) }
    var anchorY by remember { mutableFloatStateOf(0f) }
    var sel by remember { mutableStateOf<Int?>(null) }
    var sticky by remember { mutableStateOf(false) }
    val appear = remember { Animatable(0f) }
    val angle = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val px = { d: Float -> with(density) { d.dp.toPx() } }

    val cbTab by rememberUpdatedState(onTab)
    val cbApp by rememberUpdatedState(onApp)
    val cbAll by rememberUpdatedState(onAllApps)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val items = if (side == Edge.RIGHT) apps else tabs
        // per edge, so a gesture that opens one wheel and moves in the same instant uses that wheel's slots
        fun nOf(e: Edge?) = (if (e == Edge.RIGHT) apps else tabs).size.coerceAtLeast(1)
        fun stepOf(e: Edge?) = Math.toRadians(minOf(20.0, 112.0 / (nOf(e) - 1).coerceAtLeast(1))).toFloat()
        fun startOf(e: Edge?) = -stepOf(e) * (nOf(e) - 1) / 2f
        val n = nOf(side)
        val step = stepOf(side)
        val start = startOf(side)

        fun centre(e: Edge) = Offset(if (e == Edge.LEFT) -px(Geo.inset) else w + px(Geo.inset), anchorY)
        fun slotAngle(i: Int, e: Edge? = side) = startOf(e) + i * stepOf(e)
        /** Where a point on the wheel at [a] radians and radius [r] lands on screen. */
        fun at(e: Edge, a: Float, r: Float): Offset {
            val c = centre(e)
            val dx = r * cos(a)
            return Offset(if (e == Edge.LEFT) c.x + dx else c.x - dx, c.y + r * sin(a))
        }
        fun indexAt(e: Edge, p: Offset): Int {
            val c = centre(e)
            val a = atan2(p.y - c.y, if (e == Edge.LEFT) p.x - c.x else c.x - p.x)
            return ((a - startOf(e)) / stepOf(e)).roundToInt().coerceIn(0, nOf(e) - 1)
        }
        fun hubAt(e: Edge): Offset = Offset(if (e == Edge.LEFT) px(Geo.hub + 8f) else w - px(Geo.hub + 8f), anchorY)

        fun moveTo(i: Int?) {
            if (i == sel) return
            sel = i
            if (i != null) scope.launch { angle.animateTo(slotAngle(i, side), spring(dampingRatio = 0.58f, stiffness = 520f)) }
        }
        fun openAt(e: Edge, y: Float) {
            val list = if (e == Edge.RIGHT) apps else tabs
            val reach = px(Geo.rOut) * sin(Math.toRadians(62.0)).toFloat() + px(8f)
            anchorY = if (h > 2 * reach) y.coerceIn(reach, h - reach) else h / 2
            side = e; sticky = false
            val cur = if (e == Edge.LEFT) list.indexOfFirst { it.key == currentTab }.takeIf { it >= 0 } else null
            sel = cur
            scope.launch { angle.snapTo(slotAngle(cur ?: ((list.size - 1) / 2), e)) }
            scope.launch { appear.snapTo(0f); appear.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)) }
        }
        fun closeWheel() {
            EdgeSwipe.log("wheel closed")
            scope.launch { appear.animateTo(0f, tween(140)); side = null; sel = null; sticky = false }
        }
        fun pick(e: Edge, i: Int) {
            val list = if (e == Edge.RIGHT) apps else tabs
            val key = list.getOrNull(i)?.key ?: return
            closeWheel()
            if (e == Edge.LEFT) cbTab(key) else cbApp(key)
        }

        // ---- the open wheel
        val open = side
        if (open != null) {
            val t = appear.value
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f * t)))
            Canvas(Modifier.fillMaxSize()) {
                val c = centre(open)
                val pad = step / 2 + Math.toRadians(5.0).toFloat()
                val a0 = start - pad
                val sweep = step * (n - 1) + 2 * pad
                val rIn = px(Geo.rIn) * (0.6f + 0.4f * t)
                val rOut = px(Geo.rOut) * (0.6f + 0.4f * t)
                // angles on screen: the right wheel is the left one mirrored
                fun deg(a: Float) = Math.toDegrees((if (open == Edge.LEFT) a else (Math.PI - a).toFloat()).toDouble()).toFloat()
                val startDeg = if (open == Edge.LEFT) deg(a0) else deg(a0 + sweep)
                val sweepDeg = Math.toDegrees(sweep.toDouble()).toFloat()
                // THE 1.4.0-1.7.0 WHEEL BUG: a bare close() inside Path().apply { } here called the
                // wheel's own close() above (Kotlin picks a local function over the Path's member), so the wheel shut
                // itself on its first frame. The swipe registered; the wheel never stayed.
                val band = Path()
                band.arcTo(Rect(c, rOut), startDeg, sweepDeg, true)
                band.arcTo(Rect(c, rIn), startDeg + sweepDeg, -sweepDeg, false)
                band.close()
                drawPath(band, Brush.radialGradient(
                    0f to Color(0xFF2C2C31), 0.75f to Color(0xFF1E1E22), 1f to Color(0xFF161619),
                    center = c, radius = rOut,
                ), alpha = 0.94f * t)
                // the screen's light pools in the glass toward the hub
                drawPath(band, Brush.radialGradient(listOf(Ambience.tint().copy(alpha = 0.20f), Color.Transparent), center = c, radius = rOut * 1.05f), alpha = t)
                // the rim catches the light: bright along the top of the outer edge, faint on the inner one
                drawArc(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.06f)), startY = c.y - rOut, endY = c.y + rOut),
                    startDeg, sweepDeg, false, topLeft = Offset(c.x - rOut, c.y - rOut),
                    size = androidx.compose.ui.geometry.Size(rOut * 2, rOut * 2), style = Stroke(px(1.2f)), alpha = t,
                )
                drawArc(
                    Color.White.copy(alpha = 0.07f), startDeg, sweepDeg, false, topLeft = Offset(c.x - rIn, c.y - rIn),
                    size = androidx.compose.ui.geometry.Size(rIn * 2, rIn * 2), style = Stroke(px(1f)), alpha = t,
                )
                // the selection: a glass bead that springs from slot to slot
                if (sel != null) {
                    val b = at(open, angle.value, px(Geo.rItem) * (0.6f + 0.4f * t))
                    val r = px(29f)
                    val hl = Ambience.tint()
                    drawCircle(Brush.radialGradient(listOf(hl.copy(alpha = 0.42f), hl.copy(alpha = 0.10f)), center = b, radius = r * 1.25f), r * 1.25f, b, alpha = t)
                    drawCircle(hl.copy(alpha = 0.85f), r, b, style = Stroke(px(1.4f)), alpha = t)
                    drawArc(
                        Color.White.copy(alpha = 0.55f), 200f, 70f, false,
                        topLeft = Offset(b.x - r * 0.72f, b.y - r * 0.72f), size = androidx.compose.ui.geometry.Size(r * 1.44f, r * 1.44f),
                        style = Stroke(px(1.6f), cap = androidx.compose.ui.graphics.StrokeCap.Round), alpha = t,
                    )
                }
            }
            // slot icons
            items.forEachIndexed { i, it ->
                val p = at(open, slotAngle(i), px(Geo.rItem) * (0.6f + 0.4f * t))
                val on = i == sel
                Box(
                    Modifier.offset { IntOffset((p.x - px(20f)).roundToInt(), (p.y - px(20f)).roundToInt()) }
                        .size(40.dp).alpha(t).scale(if (on) 1.12f else 1f)
                        .semantics { contentDescription = it.label },
                    contentAlignment = Alignment.Center,
                ) { it.icon(Modifier.size(if (open == Edge.RIGHT) 36.dp else 26.dp), if (on) V.White else V.Text.copy(alpha = 0.72f)) }
            }
            // the chosen slot's name, just outside the band
            sel?.let { i ->
                val p = at(open, angle.value, px(Geo.rOut + 18f))
                Box(
                    Modifier.layout { m, cs ->
                        val pl = m.measure(cs.copy(minWidth = 0, minHeight = 0))
                        layout(cs.maxWidth, cs.maxHeight) {
                            val x = if (open == Edge.LEFT) p.x else p.x - pl.width
                            pl.place(x.roundToInt(), (p.y - pl.height / 2).roundToInt())
                        }
                    }.alpha(t),
                ) {
                    Box(Modifier.glass(Pill, 0.9f).padding(horizontal = 16.dp, vertical = 8.dp)) {
                        VText(items.getOrNull(i)?.label ?: "", size = 17.sp, font = SansMedium, color = V.White, maxLines = 1)
                    }
                }
            }
            if (open == Edge.RIGHT && apps.isEmpty()) {
                val p = at(open, 0f, px(Geo.rOut + 18f))
                Box(Modifier.offset { IntOffset((p.x - px(300f)).roundToInt(), (p.y - px(40f)).roundToInt()) }.width(300.dp).alpha(t)) {
                    VText("Apps you open from VoidOS appear here.", size = 15.sp, color = V.Text, align = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
            // the hub: the VoidOS mark on the tab wheel, the app grid on the other
            val hubC = hubAt(open)
            Box(
                Modifier.offset { IntOffset((hubC.x - px(Geo.hub)).roundToInt(), (hubC.y - px(Geo.hub)).roundToInt()) }
                    .size((Geo.hub * 2).dp).alpha(t).clip(CircleShape).glass(CircleShape, 0.95f),
                contentAlignment = Alignment.Center,
            ) {
                if (open == Edge.LEFT) VoidMark(Modifier.size(30.dp), mono = V.Text)
                else Glyph(G.APPS, V.Text, Modifier.size(22.dp))
            }
            if (sticky) {
                // wheel left open by a tap: tap a slot, tap the hub, or tap anywhere else to close
                Box(
                    Modifier.fillMaxSize().pointerInput(open, n) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            val onHub = hypot(down.position.x - hubC.x, down.position.y - hubC.y) < px(Geo.hub + 10f)
                            var up = down
                            while (true) {
                                val ch = awaitPointerEvent().changes.first()
                                ch.consume()
                                up = ch
                                if (!ch.pressed) break
                            }
                            val p = up.position
                            when {
                                onHub -> { closeWheel(); if (open == Edge.RIGHT) cbAll() }
                                hypot(p.x - centre(open).x, p.y - centre(open).y) in px(Geo.rIn - 10f)..px(Geo.rOut + 10f) -> pick(open, indexAt(open, p))
                                else -> closeWheel()
                            }
                        }
                    }
                )
            }
        }

        // ---- the edges: MainActivity routes edge touches here before the page sees them (see EdgeSwipe)
        var origin by remember { mutableStateOf(Offset.Zero) }
        var startPt by remember { mutableStateOf(Offset.Zero) }
        var lastPt by remember { mutableStateOf(Offset.Zero) }
        var moved by remember { mutableStateOf(false) }
        var beganAt by remember { mutableStateOf(0L) }
        var selAt by remember { mutableStateOf(0L) }
        val sink = object : EdgeSwipe.Sink {
            override fun begin(left: Boolean, x: Float, y: Float, t: Long) {
                val e = if (left) Edge.LEFT else Edge.RIGHT
                startPt = Offset(x, y) - origin
                lastPt = startPt
                moved = false
                beganAt = t; selAt = t
                openAt(e, startPt.y)
                EdgeSwipe.log("wheel ${e.name.lowercase()} opened at y=${anchorY.toInt()} in ${w.toInt()}x${h.toInt()} (${if (e == Edge.RIGHT) apps.size else tabs.size} slots)")
            }
            override fun move(x: Float, y: Float, t: Long) {
                val e = side ?: return
                lastPt = Offset(x, y) - origin
                if (!moved && hypot(lastPt.x - startPt.x, lastPt.y - startPt.y) > px(14f)) moved = true
                if (moved) {
                    val i = indexAt(e, lastPt)
                    if (i != sel) selAt = t
                    moveTo(i)
                }
            }
            override fun end(x: Float, y: Float, t: Long) {
                val e = side ?: return
                move(x, y, t)
                val onHub = hypot(lastPt.x - hubAt(e).x, lastPt.y - hubAt(e).y) < px(Geo.hub)
                val list = if (e == Edge.RIGHT) apps else tabs
                val picks = releasePicks(moved, beganAt, selAt, t)
                when {
                    // a quick swipe in, or a tap on the edge: the wheel stays open to tap
                    !picks -> { sticky = true; EdgeSwipe.log("wheel pinned open (${t - beganAt}ms, tap a slot)") }
                    onHub -> closeWheel()
                    sel != null && list.isNotEmpty() -> { EdgeSwipe.log("wheel picked ${list.getOrNull(sel!!)?.label}"); pick(e, sel!!) }
                    else -> closeWheel()
                }
            }
            override fun cancel() { if (side != null && !sticky) closeWheel() }
        }
        // the page dots and the edge handles open a wheel pinned, no swipe needed
        val req by EdgeSwipe.request
        LaunchedEffect(req) {
            val r = req ?: return@LaunchedEffect
            EdgeSwipe.request.value = null
            if (!enabled || side != null) return@LaunchedEffect
            openAt(if (r) Edge.LEFT else Edge.RIGHT, h / 2)
            sticky = true
            EdgeSwipe.log("wheel ${if (r) "left" else "right"} opened by tap")
        }
        // a pinned-open wheel takes its taps itself (the overlay above), so the edges stand down meanwhile
        SideEffect {
            EdgeSwipe.sink = sink
            EdgeSwipe.enabled = enabled && !(side != null && sticky)
        }
        DisposableEffect(Unit) {
            onDispose { if (EdgeSwipe.sink === sink) EdgeSwipe.sink = null; EdgeSwipe.enabled = false }
        }
        if (!enabled && side != null) LaunchedEffect(Unit) { closeWheel() }
        Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() })

        // a glass handle on each edge: it shows where to pull, and a tap on it opens the wheel
        if (enabled && side == null) {
            val tick = rememberTicker(50L)
            listOf(Edge.LEFT, Edge.RIGHT).forEach { e ->
                Box(
                    Modifier.align(if (e == Edge.LEFT) Alignment.CenterStart else Alignment.CenterEnd)
                        .size(30.dp, 132.dp)
                        .pointerInput(e) {
                            awaitEachGesture {
                                awaitFirstDown()
                                if (waitForUpOrCancellation() != null) EdgeSwipe.request.value = e == Edge.LEFT
                            }
                        }
                        .drawBehind {
                            // breathes softly, lit in the colour of the screen you're on
                            val br = 0.5f + 0.5f * sin(phase(tick.value, 4_000L) * 2 * Math.PI.toFloat() + if (e == Edge.LEFT) 0f else 1.6f)
                            val pw = 5.dp.toPx(); val ph = 70.dp.toPx()
                            val x = if (e == Edge.LEFT) 5.dp.toPx() else size.width - 5.dp.toPx() - pw
                            val y = (size.height - ph) / 2
                            val tint = Ambience.tint()
                            drawRoundRect(
                                Brush.radialGradient(listOf(tint.copy(alpha = 0.22f + 0.14f * br), Color.Transparent), center = Offset(x + pw / 2, size.height / 2), radius = ph * 0.75f),
                                topLeft = Offset(0f, 0f), size = size,
                            )
                            drawRoundRect(
                                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.55f + 0.2f * br), Color.White.copy(alpha = 0.30f)), startY = y, endY = y + ph),
                                topLeft = Offset(x, y), size = androidx.compose.ui.geometry.Size(pw, ph), cornerRadius = androidx.compose.ui.geometry.CornerRadius(pw / 2),
                            )
                        }
                )
            }
        }
    }
}

/** Settings > About > Edge touch debug: the edge zones, the last touch and the router's last decision. */
@Composable
fun EdgeDebugOverlay() {
    if (!EdgeSwipe.debug.value) return
    val d = LocalDensity.current
    val last by EdgeSwipe.last
    val down by EdgeSwipe.lastDown
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val hard = with(d) { EdgeSwipe.HARD_DP.dp.toPx() }
            val soft = with(d) { EdgeSwipe.SOFT_DP.dp.toPx() }
            listOf(0f to hard, size.width - hard to size.width).forEach { (a, b) -> drawRect(V.Teal.copy(alpha = 0.22f), Offset(a, 0f), androidx.compose.ui.geometry.Size(b - a, size.height)) }
            listOf(hard to soft, size.width - soft to size.width - hard).forEach { (a, b) -> drawRect(V.Coral.copy(alpha = 0.14f), Offset(a, 0f), androidx.compose.ui.geometry.Size(b - a, size.height)) }
            down?.let { (x, y) -> drawCircle(V.Yellow, 14f, Offset(x, y) - origin) }
        }
        VText(
            "EDGE DEBUG  $last", size = 13.sp, color = V.White,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
