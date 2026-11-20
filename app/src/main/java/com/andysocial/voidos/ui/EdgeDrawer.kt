package com.andysocial.voidos.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 1.20.0: the edge drawers that replace the curved side wheel.
 *
 * Same idea as the wheel: drag in from an edge towards the middle. But each drawer is a slim
 * vertical rail of liquid glass, with one row per entry, icon and name:
 *  - LEFT edge: one row for each VoidOS screen, to jump between them.
 *  - RIGHT edge: only the apps open right now (OpenApps, the same list as the app switcher).
 *    No recents, no history, no extras. With nothing open it says so.
 *
 * Kept from the wheel: lift your finger after opening before you can drag a row out to a half
 * of Home (the book split); hold an app's row and pull down to close the app; tap to open or
 * switch. The bottom edge is still the app switcher's.
 */
object Drawer {
    /** The rail's width, the same for both drawers. */
    const val WIDTH_DP = 224f
    /** Gap between the rail and the screen edge. */
    const val EDGE_GAP_DP = 12f
    /** Every icon sits in a tile this size, with [ICON_RADIUS_DP] corners, on both drawers. */
    const val TILE_DP = 40f
    const val ICON_RADIUS_DP = 12f
    /** A row is never shorter than this, nor (at normal text size) taller than [ROW_MAX_DP]. */
    const val ROW_MIN_DP = 44f
    const val ROW_MAX_DP = 60f
    /** Clear of the island and clock at the top, and of the switcher's bottom edge. */
    const val TOP_DP = 56f
    const val BOTTOM_DP = 56f
    /** Padding inside the rail, above the first row and below the last. */
    const val PAD_DP = 8f
    /** How far past the rail a row has to be pulled before it's picked up to drop on Home. */
    const val CARRY_DP = 40f
    /** The rail slides out with the finger and is fully out once the finger is this far in. */
    const val FULL_DP = 56f
    /** Held still this long on an app row, then pulled down this far: the app closes. */
    const val HOLD_MS = 450L
    const val CLOSE_DP = 90f
}

/**
 * Where a drawer's rows go, in px. Pure, so the tests check it at every size.
 *
 * [n] rows share the room between [top] and [bottom]; each is between [minRow] and [maxRow]
 * tall, and the rail is centred in the room. When even [minRow] rows don't fit, the rail fills
 * the room and the rows scroll under it: [scroll] is how far down, 0..[overflow].
 */
internal class DrawerLayout(val n: Int, h: Float, top: Float, bottom: Float, minRow0: Float, maxRow0: Float, pad0: Float) {
    private val minRow = kotlin.math.ceil(minRow0)
    private val maxRow = kotlin.math.floor(maxRow0).coerceAtLeast(minRow)
    val pad = kotlin.math.round(pad0)
    val room = (h - top - bottom).coerceAtLeast(minRow + 2 * pad)
    // whole pixels, so the rows stack edge to edge with no seams or overlaps
    val row = kotlin.math.floor(if (n <= 0) maxRow else ((room - 2 * pad) / n).coerceIn(minRow, maxRow))
    val content = n.coerceAtLeast(1) * row + 2 * pad
    val railH = content.coerceAtMost(room)
    val railTop = kotlin.math.floor(top + (room - railH) / 2f)
    val overflow = (content - railH).coerceAtLeast(0f)
    fun centre(i: Int, scroll: Float = 0f) = railTop + pad + row * (i + 0.5f) - scroll
    fun indexAt(y: Float, scroll: Float = 0f): Int =
        if (n <= 0) -1 else ((y - railTop - pad + scroll) / row).toInt().coerceIn(0, n - 1)
    fun onRail(y: Float) = y in railTop..(railTop + railH)
    /** Dragging along an overflowing rail scrolls it in step, so every row can be reached. */
    fun scrollFor(y: Float) = if (overflow <= 0f) 0f else (((y - railTop) / railH).coerceIn(0f, 1f) * overflow)
    /** Whether row [i] is fully inside the rail at [scroll]. */
    fun visible(i: Int, scroll: Float) = centre(i, scroll) - row / 2 >= railTop + pad - 0.5f && centre(i, scroll) + row / 2 <= railTop + railH - pad + 0.5f
}

/** How far a drawer is out (0..1) with the finger [dx] px in from the edge. */
internal fun drawerReveal(dx: Float, width: Float): Float = if (width <= 0f) 0f else (dx / width).coerceIn(0f, 1f)

@Composable
fun EdgeDrawers(
    tabs: List<WheelItem>,
    currentTab: String,
    onTab: (String) -> Unit,
    apps: List<WheelItem>,
    onApp: (String) -> Unit,
    /** 2.0: the right drawer's chosen row (the hub app), marked like the left drawer's. */
    currentApp: String? = null,
    /** 2.0: the drawers' names, read aloud and shown when a drawer is empty. */
    leftName: String = "Metrics drawer",
    rightName: String = "Apps drawer",
    emptyText: String = "No apps yet",
    enabled: Boolean = true,
    /** True in the window over other apps: it takes its touches from the edge strips there, not from MainActivity. */
    overlay: Boolean = false,
    /** Called once a drawer has finished closing. */
    onClosed: () -> Unit = {},
    /** Called when a drawer opens, so the open-apps list can refresh. */
    onOpen: (Edge) -> Unit = {},
    /**
     * Drag a row out past the rail and let go on one half of the screen: the book on Home
     * (ui/Book.kt). [app] is true for the app drawer, [first] for the left (portrait: top) half.
     * Null switches dragging out off.
     */
    onDrop: ((key: String, app: Boolean, first: Boolean) -> Unit)? = null,
    canDrop: (key: String, app: Boolean) -> Boolean = { _, _ -> true },
    /** Hold an app's row, then pull down: the app is closed completely (AppKill). Null switches it off. */
    onCloseApp: ((key: String) -> Unit)? = null,
    canClose: (key: String) -> Boolean = { true },
    /** 2.0.1: the one word beside a held row (Close for apps, Hide for hub apps), coral once the pull is far enough. */
    closeLabel: String = "Close",
) {
    var side by remember { mutableStateOf<Edge?>(null) }
    var sticky by remember { mutableStateOf(false) }
    // false from the moment a drawer opens until the finger that opened it has lifted (see wheelMayCarry)
    var lifted by remember { mutableStateOf(false) }
    val reveal = remember { Animatable(0f) }
    val sel = remember { mutableIntStateOf(-1) }
    val scroll = remember { mutableFloatStateOf(0f) }
    var closing by remember { mutableStateOf<String?>(null) }
    val closePull = remember { mutableFloatStateOf(0f) }
    var carry by remember { mutableStateOf<WheelItem?>(null) }
    var carryApp by remember { mutableStateOf(false) }
    val carryAt = remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val view = LocalView.current
    val px = { d: Float -> with(density) { d.dp.toPx() } }
    val cbTab by rememberUpdatedState(onTab)
    val cbApp by rememberUpdatedState(onApp)
    val cbClosed by rememberUpdatedState(onClosed)
    val cbOpen by rememberUpdatedState(onOpen)
    val tabsNow by rememberUpdatedState(tabs)
    val appsNow by rememberUpdatedState(apps)
    val cbDrop by rememberUpdatedState(onDrop)
    val cbCan by rememberUpdatedState(canDrop)
    val cbClose by rememberUpdatedState(onCloseApp)
    val cbCanClose by rememberUpdatedState(canClose)
    // large text: rows grow with it (up to a point), and the names shrink to fit if they must
    val grow = density.fontScale.coerceIn(1f, 1.35f)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val railW = px(Drawer.WIDTH_DP).coerceAtMost(w * 0.6f)
        val gap = px(Drawer.EDGE_GAP_DP)
        fun itemsOf(e: Edge?) = if (e == Edge.RIGHT) appsNow else tabsNow
        fun layoutOf(e: Edge?) = DrawerLayout(
            itemsOf(e).size, h, px(Drawer.TOP_DP), px(Drawer.BOTTOM_DP),
            px(Drawer.ROW_MIN_DP), px(Drawer.ROW_MAX_DP * grow), px(Drawer.PAD_DP),
        )
        /** Distance from the drawer's own edge. */
        fun inFrom(e: Edge, x: Float) = if (e == Edge.LEFT) x else w - x
        fun onRailX(e: Edge, x: Float) = inFrom(e, x) <= gap + railW + px(8f)

        fun openAt(e: Edge) {
            side = e; sticky = false; lifted = false
            val list = itemsOf(e)
            sel.intValue = if (e == Edge.LEFT) list.indexOfFirst { it.key == currentTab } else list.indexOfFirst { it.key == currentApp }
            scroll.floatValue = 0f
            if (!overlay) Ambience.held.value = true
            cbOpen(e)
        }
        fun closeDrawer() {
            EdgeSwipe.log("drawer closed")
            scope.launch {
                reveal.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 900f))
                side = null; sel.intValue = -1; sticky = false; closing = null; closePull.floatValue = 0f
                if (!overlay) Ambience.held.value = false
                cbClosed()
            }
        }
        fun settleOpen() { scope.launch { reveal.animateTo(1f, Motion.release()) } }
        fun firstHalf(p: Offset) = if (h > w) p.y < h / 2f else p.x < w / 2f
        /** Pulling a row out past the rail picks it up; pulling it back over the rail puts it back. */
        fun carryCheck(e: Edge, p: Offset): Boolean {
            val d = inFrom(e, p.x)
            if (carry == null) {
                if (!wheelMayCarry(lifted, overlay, cbDrop != null) || d < gap + railW + px(Drawer.CARRY_DP)) return false
                val it = itemsOf(e).getOrNull(sel.intValue) ?: return false
                if (!cbCan(it.key, e == Edge.RIGHT)) return false
                carry = it; carryApp = e == Edge.RIGHT
                com.andysocial.voidos.Haptics.press(view)
                EdgeSwipe.log("carrying ${it.label} out of the drawer")
            } else if (d < gap + railW) {
                carry = null
                EdgeSwipe.log("put back in the drawer")
                return false
            }
            carryAt.value = p
            return true
        }
        fun pick(e: Edge, i: Int) {
            val key = itemsOf(e).getOrNull(i)?.key ?: return
            closeDrawer()
            if (e == Edge.LEFT) cbTab(key) else cbApp(key)
        }
        fun dropCarry(p: Offset) {
            val it = carry ?: return
            val first = firstHalf(p)
            val app = carryApp
            carry = null
            EdgeSwipe.log("dropped ${it.label} on the ${if (first) "first" else "second"} half")
            closeDrawer()
            cbDrop?.invoke(it.key, app, first)
        }

        val open = side
        if (open != null) {
            val items = itemsOf(open)
            val lay = layoutOf(open)
            val carried by animateFloatAsState(if (carry != null) 1f else 0f, Motion.quick(), label = "carried")
            // the highlight glides to the row under the finger on a spring
            val hiY = remember(open) { Animatable(lay.centre(sel.intValue.coerceAtLeast(0))) }
            val target = if (sel.intValue >= 0) lay.centre(sel.intValue, scroll.floatValue) else null
            LaunchedEffect(target) { if (target != null) hiY.animateTo(target, Motion.press()) }
            // the scrim deepens from the drawer's edge
            Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = reveal.value.coerceIn(0f, 1f) }) {
                val from = if (open == Edge.LEFT) 0f else size.width
                val to = if (open == Edge.LEFT) size.width * 0.75f else size.width * 0.25f
                drawRect(Color.Black.copy(alpha = 0.18f))
                drawRect(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.5f), 1f to Color.Transparent, startX = from, endX = to))
            }
            val railWdp = with(density) { railW.toDp() }
            val railHdp = with(density) { lay.railH.toDp() }
            val rowDp = with(density) { lay.row.toDp() }
            Box(
                Modifier
                    .offset {
                        val shown = gap + railW
                        val x = if (open == Edge.LEFT) gap - shown * (1f - reveal.value) else w - shown * reveal.value
                        IntOffset(x.roundToInt(), lay.railTop.roundToInt())
                    }
                    .size(railWdp, railHdp)
                    .graphicsLayer { alpha = (reveal.value * 3f).coerceIn(0f, 1f) * (1f - 0.85f * carried) }
                    .liquidGlass(Radius.Panel, GlassLook(smoke = 0.74f, depth = 1f, shine = 1.1f, magnify = 1.05f), still = true)
                    .semantics { contentDescription = if (open == Edge.LEFT) leftName else rightName },
            ) {
                if (items.isEmpty()) {
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = Grid.l),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        FitText(emptyText, Type.body, Modifier.fillMaxWidth().height(22.dp), color = V.Text, font = SansMedium, align = Alignment.Center)
                    }
                } else {
                    // the highlight under the finger: a lighter sheet of glass, inset on the grid
                    if (sel.intValue >= 0) {
                        Box(
                            Modifier
                                .offset { IntOffset(px(Drawer.PAD_DP).roundToInt(), (hiY.value - lay.railTop - lay.row / 2f).roundToInt()) }
                                .size(railWdp - (Drawer.PAD_DP * 2).dp, rowDp)
                                .background(V.White.copy(alpha = 0.12f), Radius.Control),
                        )
                    }
                    items.forEachIndexed { i, it ->
                        val y = lay.centre(i, scroll.floatValue) - lay.railTop - lay.row / 2f
                        if (y + lay.row < 0f || y > lay.railH) return@forEachIndexed
                        val current = if (open == Edge.LEFT) it.key == currentTab else (currentApp != null && it.key == currentApp)
                        val hot = i == sel.intValue
                        Row(
                            Modifier
                                .offset { IntOffset(0, y.roundToInt()) }
                                .size(railWdp, rowDp)
                                .padding(horizontal = (Drawer.PAD_DP + 8f).dp)
                                .graphicsLayer {
                                    if (closing == it.key) {
                                        val pull = closePull.floatValue
                                        translationY = pull * px(Drawer.CLOSE_DP) * 0.5f
                                        alpha = 1f - 0.6f * pull
                                    }
                                }
                                .semantics { contentDescription = it.label },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(Drawer.TILE_DP.dp)
                                    .background(if (open == Edge.LEFT) V.White.copy(alpha = if (current) 0.16f else 0.07f) else Color.Transparent, Chamfer(Drawer.ICON_RADIUS_DP.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                it.icon(Modifier.size(if (open == Edge.RIGHT) Drawer.TILE_DP.dp else 22.dp), if (current || hot) V.White else V.Text)
                            }
                            Spacer(Modifier.width(Grid.m))
                            FitText(
                                it.label, Type.body, Modifier.weight(1f).height(rowDp - 8.dp),
                                color = if (current || hot) V.White else V.Text, font = SansMedium, min = 10.sp,
                            )
                            // the screen you're on: a small teal dot, nothing louder
                            if (current) {
                                Spacer(Modifier.width(Grid.s))
                                Box(Modifier.size(6.dp).background(V.Teal, CircleShape))
                            }
                        }
                    }
                }
            }
            // holding an app's row: a hint beside it, coral once the pull is far enough to close
            val holding = closing
            val hi = if (holding != null) items.indexOfFirst { it.key == holding } else -1
            if (hi >= 0) {
                val ready = closePull.floatValue >= 1f
                val hy = lay.centre(hi, scroll.floatValue)
                Box(
                    Modifier.offset {
                        val x = if (open == Edge.LEFT) gap + railW + px(Grid.m.value) else w - gap - railW - px(Grid.m.value) - px(96f)
                        IntOffset(x.roundToInt(), (hy - px(17f)).roundToInt())
                    }
                        .size(width = 96.dp, height = 34.dp)
                        .liquidGlass(Chamfer(Radius.control), GlassLook(smoke = 0.8f, depth = 1f, shine = 1.1f), still = true)
                        .semantics { contentDescription = closeLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    FitText(closeLabel.uppercase(), Type.label, Modifier.padding(horizontal = Grid.m).fillMaxWidth().height(20.dp), color = if (ready) V.Coral else V.Text, font = SansMedium, align = Alignment.Center)
                }
            }
            if (sticky) {
                // left open by a tap or a quick swipe: tap a row, hold an app row and pull down, or tap anywhere else
                Box(
                    Modifier.fillMaxSize().pointerInput(open, items.size) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            val l = layoutOf(open)
                            val onRail = onRailX(open, down.position.x) && l.onRail(down.position.y) && items.isNotEmpty()
                            val from = if (onRail) l.indexAt(down.position.y, scroll.floatValue) else -1
                            val holdKey = if (from >= 0 && open == Edge.RIGHT && cbClose != null) items.getOrNull(from)?.key?.takeIf { cbCanClose(it) } else null
                            if (from >= 0) sel.intValue = from
                            var armed = false
                            var scrolled = false
                            val scroll0 = scroll.floatValue
                            var up = down
                            while (true) {
                                val ev = if (holdKey != null && !armed && carry == null && !scrolled)
                                    withTimeoutOrNull(Drawer.HOLD_MS - (up.uptimeMillis - down.uptimeMillis).coerceAtLeast(0L)) { awaitPointerEvent() }
                                else awaitPointerEvent()
                                if (ev == null) {
                                    armed = true; closing = holdKey; closePull.floatValue = 0f
                                    com.andysocial.voidos.Haptics.press(view)
                                    EdgeSwipe.log("holding ${items.getOrNull(from)?.label}: pull down to close")
                                    continue
                                }
                                val ch = ev.changes.first()
                                ch.consume()
                                up = ch
                                val dx = ch.position.x - down.position.x
                                val dy = ch.position.y - down.position.y
                                if (armed) {
                                    closePull.floatValue = wheelClosePull(dy, dx, px(Drawer.CLOSE_DP))
                                } else if (from >= 0) {
                                    if (carry == null && onRailX(open, ch.position.x) && abs(dy) > px(14f) && abs(dy) > abs(dx) && l.overflow > 0f) {
                                        // an overflowing rail scrolls under a vertical drag
                                        scrolled = true
                                        scroll.floatValue = (scroll0 - dy).coerceIn(0f, l.overflow)
                                    } else if (!scrolled) carryCheck(open, ch.position)
                                }
                                if (!ch.pressed) break
                            }
                            val p = up.position
                            if (armed) {
                                val key = closing
                                val go = closePull.floatValue >= 1f
                                closing = null; closePull.floatValue = 0f
                                if (go && key != null) { closeDrawer(); cbClose?.invoke(key) }
                                return@awaitEachGesture
                            }
                            if (carry != null) { dropCarry(p); return@awaitEachGesture }
                            if (scrolled) return@awaitEachGesture
                            if (onRailX(open, p.x) && l.onRail(p.y) && items.isNotEmpty() && hypot(p.x - down.position.x, p.y - down.position.y) < px(24f)) pick(open, l.indexAt(p.y, scroll.floatValue))
                            else if (!onRailX(open, p.x) || !l.onRail(p.y)) closeDrawer()
                        }
                    },
                )
            }
        }
        // a row carried out of the drawer: the half it will open in lights up, the other shows the watch face
        val held = carry
        if (held != null) {
            val p = carryAt.value
            val port = h > w
            val first = firstHalf(p)
            val sw by animateFloatAsState(if (first) 0f else 1f, Motion.press(), label = "carrySide")
            val halfW = if (port) w else w / 2f
            val halfH = if (port) h / 2f else h
            fun halfAt(f: Float) = if (port) IntOffset(0, (f * halfH).roundToInt()) else IntOffset((f * halfW).roundToInt(), 0)
            val dw = with(density) { halfW.toDp() }
            val dh = with(density) { halfH.toDp() }
            Box(Modifier.offset { halfAt(1f - sw) }.size(dw, dh).padding(Grid.l), contentAlignment = Alignment.Center) {
                Glyph(G.FACE, V.Text.copy(alpha = 0.35f), Modifier.size(56.dp))
            }
            Box(
                Modifier.offset { halfAt(sw) }.size(dw, dh).padding(Grid.l)
                    .liquidGlass(Radius.Panel, GlassLook(smoke = 0.45f, depth = 1.2f, shine = 1.3f), still = true)
                    .semantics { contentDescription = "Drop ${held.label} here" },
            )
            Box(
                Modifier.offset { IntOffset((p.x - px(32f)).roundToInt(), (p.y - px(32f)).roundToInt()) }
                    .size(64.dp)
                    .liquidGlass(Chamfer(Radius.card), GlassLook(smoke = 0.6f, depth = 1.3f, shine = 1.5f, magnify = 1.3f), still = true),
                contentAlignment = Alignment.Center,
            ) { held.icon(Modifier.size(if (carryApp) Drawer.TILE_DP.dp else 26.dp), V.White) }
        }
        // ---- the edges: MainActivity (or the overlay's strips) route edge touches here (see EdgeSwipe)
        val g = remember { DrawerGesture() }
        val sink = run {
            object : EdgeSwipe.Sink {
                override fun begin(left: Boolean, x: Float, y: Float, t: Long) {
                    val e = if (left) Edge.LEFT else Edge.RIGHT
                    g.sx = x - g.ox; g.sy = y - g.oy; g.lx = g.sx; g.ly = g.sy
                    g.moved = false; g.beganAt = t; g.selAt = t
                    openAt(e)
                    scope.launch { reveal.snapTo(drawerReveal(inFrom(e, g.sx), px(Drawer.FULL_DP)).coerceAtLeast(0.08f)) }
                    EdgeSwipe.log("drawer ${e.name.lowercase()} opened in ${w.toInt()}x${h.toInt()} (${itemsOf(e).size} rows)")
                }
                override fun move(x: Float, y: Float, t: Long) {
                    val e = side ?: return
                    g.lx = x - g.ox; g.ly = y - g.oy
                    if (!g.moved && hypot(g.lx - g.sx, g.ly - g.sy) > px(14f)) g.moved = true
                    if (g.moved && carryCheck(e, Offset(g.lx, g.ly))) return
                    // the rail follows the finger out, then holds
                    val want = maxOf(reveal.targetValue, drawerReveal(inFrom(e, g.lx), px(Drawer.FULL_DP)))
                    if (want != reveal.targetValue) scope.launch { reveal.animateTo(want, spring(dampingRatio = 0.85f, stiffness = 1400f)) }
                    if (g.moved && itemsOf(e).isNotEmpty()) {
                        val l = layoutOf(e)
                        scroll.floatValue = l.scrollFor(g.ly)
                        val i = l.indexAt(g.ly, scroll.floatValue)
                        if (i != sel.intValue) {
                            if (sel.intValue >= 0) com.andysocial.voidos.Haptics.tick(view)
                            g.selAt = t
                            sel.intValue = i
                        }
                    }
                }
                override fun end(x: Float, y: Float, t: Long) {
                    val e = side ?: run { if (overlay) cbClosed(); return }
                    move(x, y, t)
                    lifted = true
                    if (carry != null) { dropCarry(Offset(g.lx, g.ly)); return }
                    val list = itemsOf(e)
                    val s = sel.intValue
                    when {
                        // a quick swipe in, or a tap on the edge: the drawer stays open to tap
                        !releasePicks(g.moved, g.beganAt, g.selAt, t) -> { sticky = true; settleOpen(); EdgeSwipe.log("drawer pinned open (${t - g.beganAt}ms)") }
                        s >= 0 && list.isNotEmpty() -> { EdgeSwipe.log("drawer picked ${list.getOrNull(s)?.label}"); pick(e, s) }
                        else -> closeDrawer()
                    }
                }
                override fun cancel() { carry = null; if (side != null && !sticky) closeDrawer() else if (side == null && overlay) cbClosed() }
            }
        }
        // the page dots open the screens drawer pinned, no swipe needed
        val req by (if (overlay) EdgeSwipe.overlayRequest else EdgeSwipe.request)
        LaunchedEffect(req) {
            val r = req ?: return@LaunchedEffect
            if (overlay) EdgeSwipe.overlayRequest.value = null else EdgeSwipe.request.value = null
            if (!enabled || side != null) return@LaunchedEffect
            openAt(if (r) Edge.LEFT else Edge.RIGHT)
            sticky = true; lifted = true
            settleOpen()
            EdgeSwipe.log("drawer ${if (r) "left" else "right"} opened by tap")
        }
        SideEffect {
            if (overlay) {
                EdgeSwipe.overlaySink = sink
            } else {
                EdgeSwipe.sink = sink
                EdgeSwipe.enabled = enabled && !(side != null && sticky)
                // the app drawer opens even with nothing open, to say so
                EdgeSwipe.rightEnabled = true
            }
        }
        DisposableEffect(Unit) {
            onDispose {
                if (overlay) { if (EdgeSwipe.overlaySink === sink) EdgeSwipe.overlaySink = null }
                else { if (EdgeSwipe.sink === sink) EdgeSwipe.sink = null; EdgeSwipe.enabled = false; Ambience.held.value = false }
            }
        }
        if (!enabled && side != null) LaunchedEffect(Unit) { closeDrawer() }
        Box(Modifier.fillMaxSize().onGloballyPositioned { val o = it.positionInWindow(); g.ox = o.x; g.oy = o.y })
    }
}

private class DrawerGesture {
    var ox = 0f; var oy = 0f
    var sx = 0f; var sy = 0f
    var lx = 0f; var ly = 0f
    var moved = false
    var beganAt = 0L
    var selAt = 0L
}
