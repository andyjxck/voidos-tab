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
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Size
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
import kotlin.math.max
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
    /** True in the window over other apps: it takes its touches from the edge strips there, not from MainActivity. */
    overlay: Boolean = false,
    /** Called once the wheel has finished closing. */
    onClosed: () -> Unit = {},
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
    val cbClosed by rememberUpdatedState(onClosed)

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
            scope.launch { appear.animateTo(0f, tween(140)); side = null; sel = null; sticky = false; cbClosed() }
        }
        fun pick(e: Edge, i: Int) {
            val list = if (e == Edge.RIGHT) apps else tabs
            val key = list.getOrNull(i)?.key ?: return
            closeWheel()
            if (e == Edge.LEFT) cbTab(key) else cbApp(key)
        }

        // ---- the open wheel: a lens of liquid glass
        val open = side
        if (open != null) {
            val t = appear.value
            val room = LocalRoom.current
            val vel = angle.velocity
            // the scrim falls off from the edge the wheel comes from, so the page stays faintly there
            Canvas(Modifier.fillMaxSize()) {
                val c = centre(open)
                drawRect(Color.Black.copy(alpha = 0.22f * t))
                drawRect(
                    Brush.radialGradient(
                        0f to Color.Black.copy(alpha = 0.55f * t), 0.55f to Color.Black.copy(alpha = 0.30f * t), 1f to Color.Transparent,
                        center = c, radius = max(w, h) * 0.75f,
                    ),
                )
            }
            Canvas(Modifier.fillMaxSize()) {
                val c = centre(open)
                val pad = step / 2 + Math.toRadians(3.0).toFloat()
                val a0 = start - pad
                val sweep = step * (n - 1) + 2 * pad
                val grow = 0.62f + 0.38f * t
                val rIn = px(Geo.rIn) * grow
                val rOut = px(Geo.rOut) * grow
                val rMid = (rIn + rOut) / 2
                val thick = rOut - rIn
                // angles on screen: the right wheel is the left one mirrored
                fun deg(a: Float) = Math.toDegrees((if (open == Edge.LEFT) a else (Math.PI - a).toFloat()).toDouble()).toFloat()
                val startDeg = if (open == Edge.LEFT) deg(a0) else deg(a0 + sweep)
                val sweepDeg = Math.toDegrees(sweep.toDouble()).toFloat()
                // THE 1.4.0-1.7.0 WHEEL BUG lived here: a bare close() inside Path().apply { } called the
                // wheel's own close(), so the wheel shut itself on its first frame. Keep path calls qualified.
                val band = wheelBand(c, rMid, thick, startDeg, sweepDeg)
                val bandA = band.asAndroidPath()
                // 1. depth: the band floats, casting a soft shadow out from the edge
                drawIntoCanvas { cv ->
                    val sh = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.Black.copy(alpha = 0.55f * t).toArgb()
                        maskFilter = android.graphics.BlurMaskFilter(px(22f), android.graphics.BlurMaskFilter.Blur.NORMAL)
                    }
                    cv.nativeCanvas.save()
                    cv.nativeCanvas.translate(if (open == Edge.LEFT) px(6f) else -px(6f), px(10f))
                    cv.nativeCanvas.drawPath(bandA, sh)
                    cv.nativeCanvas.restore()
                }
                val m = room.moodNow()
                val tint = Ambience.tint()
                clipPath(band) {
                    // 2. the lens: the light behind, bent and magnified through thick glass
                    if (!room.detached) {
                        withTransform({ scale(1.32f, 1.32f, c) }) {
                            paintAurora(w, h, m, room.clock.longValue, Ambience.pos.floatValue, room.intensity.floatValue * 2.1f, base = false)
                        }
                    } else {
                        drawRect(Brush.radialGradient(listOf(m.a.copy(alpha = 0.42f), Color.Transparent), center = c + Offset(0f, -rOut), radius = rOut * 1.3f))
                        drawRect(Brush.radialGradient(listOf(m.b.copy(alpha = 0.36f), Color.Transparent), center = c + Offset(0f, rOut), radius = rOut * 1.2f))
                    }
                    // 3. the body: smoked glass, clearer in the middle of the band, denser at its edges
                    drawCircle(
                        Brush.radialGradient(
                            0f to Color(0xFF15151A).copy(alpha = 0.55f),
                            (rIn / rOut) to Color(0xFF1B1B21).copy(alpha = 0.62f),
                            ((rIn + thick * 0.5f) / rOut) to Color(0xFF26252D).copy(alpha = 0.34f),
                            1f to Color(0xFF101014).copy(alpha = 0.66f),
                            center = c, radius = rOut,
                        ),
                        rOut, c,
                    )
                    // 4. thickness: light soaking in from every edge, in the colour of the room
                    val rim = lerp(tint, Color.White, 0.4f)
                    drawPath(band, rim.copy(alpha = 0.05f), style = Stroke(px(30f)))
                    drawPath(band, rim.copy(alpha = 0.06f), style = Stroke(px(14f)))
                    drawPath(band, rim.copy(alpha = 0.08f), style = Stroke(px(5f)))
                    // 5. gloss: a broad sheen across the upper half of the band
                    drawCircle(
                        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), center = c + Offset(if (open == Edge.LEFT) rMid * 0.55f else -rMid * 0.55f, -rMid * 0.8f), radius = rOut * 0.75f),
                        rOut, c,
                    )
                    // 6. thick glass pools coloured light along its lower inner curve
                    drawCircle(
                        Brush.radialGradient(listOf(tint.copy(alpha = 0.30f), Color.Transparent), center = c + Offset(if (open == Edge.LEFT) rIn * 0.4f else -rIn * 0.4f, rIn * 0.95f), radius = rOut * 0.55f),
                        rOut, c,
                    )
                    // 7. specular: the rim catches the light; the brightest point follows your finger
                    val peak = deg(angle.value - 0.55f)
                    fun spec(a: Float, peakDeg: Float, spread: Float): Brush {
                        // a sweep gradient around the wheel's centre, bright at peakDeg and gone by +-spread degrees
                        fun f(d: Float) = (((d - peakDeg) % 360f + 540f) % 360f - 180f)
                        val stops = (0..36).map { k ->
                            val d = k * 10f
                            val x = (1f - kotlin.math.abs(f(d)) / spread).coerceIn(0f, 1f)
                            (k / 36f) to Color.White.copy(alpha = a * x * x)
                        }.toTypedArray()
                        return Brush.sweepGradient(*stops, center = c)
                    }
                    drawPath(band, spec(0.16f, peak, 70f), style = Stroke(px(12f)))
                    drawPath(band, spec(0.72f, peak, 50f), style = Stroke(px(2.2f)))
                    drawPath(band, spec(0.40f, deg(angle.value + 1.1f), 45f), style = Stroke(px(2.2f)))
                }

                // 8. the selection: a drop of liquid glass that magnifies; it stretches along the arc as it moves
                if (sel != null) {
                    val b = at(open, angle.value, px(Geo.rItem) * grow)
                    val r = px(31f)
                    val stretch = (kotlin.math.abs(vel) * 0.06f).coerceAtMost(0.45f)
                    val tangent = deg(angle.value) + 90f
                    drawIntoCanvas { cv ->
                        val sh = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.Black.copy(alpha = 0.45f * t).toArgb()
                            maskFilter = android.graphics.BlurMaskFilter(px(10f), android.graphics.BlurMaskFilter.Blur.NORMAL)
                        }
                        cv.nativeCanvas.drawCircle(b.x, b.y + px(6f), r * 0.95f, sh)
                    }
                    withTransform({ rotate(tangent, b); scale(1f + stretch, 1f - stretch * 0.35f, b) }) {
                        val hl = tint
                        // stained, clear glass: the colour of the screen, brighter at the bottom where the light gathers
                        drawCircle(Brush.verticalGradient(listOf(lerp(hl, Color.White, 0.25f).copy(alpha = 0.30f), hl.copy(alpha = 0.55f)), startY = b.y - r, endY = b.y + r), r, b, alpha = t)
                        drawCircle(Brush.radialGradient(listOf(Color.Transparent, Color.Transparent, Color.White.copy(alpha = 0.22f)), center = b, radius = r), r, b, alpha = t)
                        // the rim bends the light into a bright crescent at the top left and a softer one opposite
                        drawCircle(
                            Brush.sweepGradient(
                                0f to Color.White.copy(alpha = 0.08f), 0.55f to Color.White.copy(alpha = 0.10f), 0.64f to Color.White.copy(alpha = 0.95f),
                                0.75f to Color.White.copy(alpha = 0.12f), 0.92f to Color.White.copy(alpha = 0.05f), 1f to Color.White.copy(alpha = 0.08f),
                                center = b,
                            ),
                            r - px(1.2f), b, style = Stroke(px(2.4f)), alpha = t,
                        )
                        drawCircle(
                            Brush.sweepGradient(0f to Color.Transparent, 0.12f to Color.White.copy(alpha = 0.45f), 0.25f to Color.Transparent, 1f to Color.Transparent, center = b),
                            r - px(1.2f), b, style = Stroke(px(2f)), alpha = t,
                        )
                        // the gloss: a soft oval highlight over the top
                        drawOval(
                            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.0f)), startY = b.y - r * 0.9f, endY = b.y - r * 0.05f),
                            topLeft = Offset(b.x - r * 0.62f, b.y - r * 0.9f), size = Size(r * 1.24f, r * 0.8f), alpha = t,
                        )
                    }
                }
            }
            // slot icons: magnified as the lens passes over them, like looking through a drop of water
            items.forEachIndexed { i, it ->
                val p = at(open, slotAngle(i), px(Geo.rItem) * (0.62f + 0.38f * t))
                val on = i == sel
                val near = if (sel != null) (1f - kotlin.math.abs(slotAngle(i) - angle.value) / step).coerceIn(0f, 1f) else 0f
                val mag = 1f + 0.34f * near * near
                Box(
                    Modifier.offset { IntOffset((p.x - px(20f)).roundToInt(), (p.y - px(20f)).roundToInt()) }
                        .size(40.dp).alpha(t).scale(mag)
                        .semantics { contentDescription = it.label },
                    contentAlignment = Alignment.Center,
                ) { it.icon(Modifier.size(if (open == Edge.RIGHT) 36.dp else 26.dp), if (on) V.White else V.Text.copy(alpha = 0.66f + 0.34f * near)) }
            }
            // the chosen slot's name, floating just outside the band in its own drop of glass
            sel?.let { i ->
                val p = at(open, angle.value, px(Geo.rOut + 22f))
                Box(
                    Modifier.layout { m, cs ->
                        val pl = m.measure(cs.copy(minWidth = 0, minHeight = 0))
                        layout(cs.maxWidth, cs.maxHeight) {
                            val x = if (open == Edge.LEFT) p.x else p.x - pl.width
                            pl.place(x.roundToInt(), (p.y - pl.height / 2).roundToInt())
                        }
                    }.alpha(t),
                ) {
                    Box(Modifier.glass(Pill, 0.75f).padding(horizontal = 20.dp, vertical = 10.dp)) {
                        VText(items.getOrNull(i)?.label ?: "", size = 18.sp, font = SansMedium, color = V.White, maxLines = 1)
                    }
                }
            }
            if (open == Edge.RIGHT && apps.isEmpty()) {
                val p = at(open, 0f, px(Geo.rOut + 18f))
                Box(Modifier.offset { IntOffset((p.x - px(300f)).roundToInt(), (p.y - px(40f)).roundToInt()) }.width(300.dp).alpha(t)) {
                    VText("Apps you open from VoidOS appear here.", size = 15.sp, color = V.Text, align = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
            // the hub: a glass orb with the VoidOS mark on the tab wheel, the app grid on the other
            val hubC = hubAt(open)
            Box(
                Modifier.offset { IntOffset((hubC.x - px(Geo.hub)).roundToInt(), (hubC.y - px(Geo.hub)).roundToInt()) }
                    .size((Geo.hub * 2).dp).alpha(t)
                    .liquidGlass(CircleShape, GlassLook(smoke = 0.55f, depth = 1.2f, shine = 1.4f, magnify = 1.5f)),
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
                // nothing open: over another app, give the screen straight back
                val e = side ?: run { if (overlay) cbClosed(); return }
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
            override fun cancel() { if (side != null && !sticky) closeWheel() else if (side == null && overlay) cbClosed() }
        }
        // the page dots and the edge handles open a wheel pinned, no swipe needed
        val req by (if (overlay) EdgeSwipe.overlayRequest else EdgeSwipe.request)
        LaunchedEffect(req) {
            val r = req ?: return@LaunchedEffect
            if (overlay) EdgeSwipe.overlayRequest.value = null else EdgeSwipe.request.value = null
            if (!enabled || side != null) return@LaunchedEffect
            openAt(if (r) Edge.LEFT else Edge.RIGHT, h / 2)
            sticky = true
            EdgeSwipe.log("wheel ${if (r) "left" else "right"} opened by tap")
        }
        // a pinned-open wheel takes its taps itself (the overlay above), so the edges stand down meanwhile
        SideEffect {
            if (overlay) {
                EdgeSwipe.overlaySink = sink
            } else {
                EdgeSwipe.sink = sink
                EdgeSwipe.enabled = enabled && !(side != null && sticky)
            }
        }
        DisposableEffect(Unit) {
            onDispose {
                if (overlay) { if (EdgeSwipe.overlaySink === sink) EdgeSwipe.overlaySink = null }
                else { if (EdgeSwipe.sink === sink) EdgeSwipe.sink = null; EdgeSwipe.enabled = false }
            }
        }
        if (!enabled && side != null) LaunchedEffect(Unit) { closeWheel() }
        Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() })

        // no handles drawn on the edges: the edges are simply where the wheels live.
        // Tapping the page dots still opens the tab wheel without a swipe.
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

/** The wheel's band as one closed shape: a thick arc with fully round ends, so it's a capsule bent around the edge. */
internal fun wheelBand(c: Offset, rMid: Float, thick: Float, startDeg: Float, sweepDeg: Float): Path {
    val arc = android.graphics.Path()
    arc.addArc(c.x - rMid, c.y - rMid, c.x + rMid, c.y + rMid, startDeg, sweepDeg)
    val p = android.graphics.Paint().apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = thick
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    val out = android.graphics.Path()
    p.getFillPath(arc, out)
    return out.asComposePath()
}
