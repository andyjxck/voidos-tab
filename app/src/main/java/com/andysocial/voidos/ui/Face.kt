package com.andysocial.voidos.ui

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import com.andysocial.voidos.FaceWidget
import com.andysocial.voidos.KTimer
import com.andysocial.voidos.KTimers
import com.andysocial.voidos.Now
import com.andysocial.voidos.R
import com.andysocial.voidos.Store
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The home face: the time and your timers, and nothing else unless you put
 * it there. The octagon clock sits dead centre with a field of small squares
 * around it. Running timers show as small pills above the time. Widgets
 * (weather, sound, the list, any note) are optional: add them with +, hold
 * one to drag it anywhere, and they stay where you leave them.
 */
@Composable
fun FaceScreen(store: Store, onOpen: (Module) -> Unit) {
    val now = rememberNow(1000)
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { WeatherRepo.refresh(store) }
    val ctx = LocalContext.current
    val favs = remember(store.favs) { store.favs.mapNotNull { pkg -> AppsRepo.find(ctx, pkg) } }

    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val minute = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.MINUTE)
            SquareField(Modifier.fillMaxSize(), V.Lap[minute % 6])
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Dial(store, now, onOpen, Modifier.fillMaxSize())
            }
            WidgetLayer(store, now, editing, onOpen, maxWidth, maxHeight)
            // the one control on the face: add, move and remove widgets
            Box(Modifier.align(Alignment.TopEnd)) {
                FaceEditButton(editing) { editing = !editing }
            }
            if (editing) WidgetMenu(store, Modifier.align(Alignment.TopEnd).padding(top = 60.dp)) { editing = false }
        }
        // favourites, only when you've starred some
        if (favs.isNotEmpty()) {
            Row(
                Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp).clip(RoundedCornerShape(50))
                    .background(V.Panel).border(1.dp, V.Line, RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) { favs.take(6).forEach { app -> AppIcon(app, 42.dp) { launchApp(ctx, store, app.pkg) } } }
        }
    }
}

/** Distance from the centre to an octagon (flat top, apothem [a]) along angle [t]. */
internal fun octRadius(a: Float, t: Double): Float {
    val step = PI / 4
    val d = ((t % step) + step) % step                // 0..45 degrees past an edge's middle
    val off = if (d > step / 2) step - d else d       // to the nearest edge middle
    return (a / cos(off)).toFloat()
}

/**
 * Small rounded squares on a grid around the clock: brightest near the dial,
 * fading out towards the edges, a few picked out in the six watch colours.
 */
@Composable
private fun SquareField(modifier: Modifier, lap: Color) {
    Canvas(modifier) {
        val pitch = 30.dp.toPx()
        val sq = 9.dp.toPx()
        val c = Offset(size.width / 2, size.height / 2)
        val dial = size.minDimension / 2 * 0.98f
        val far = hypot(size.width / 2, size.height / 2)
        val cols = (size.width / pitch).toInt() + 1
        val rows = (size.height / pitch).toInt() + 1
        val ox = (size.width - (cols - 1) * pitch) / 2
        val oy = (size.height - (rows - 1) * pitch) / 2
        for (r in 0 until rows) for (k in 0 until cols) {
            val p = Offset(ox + k * pitch, oy + r * pitch)
            val d = hypot(p.x - c.x, p.y - c.y)
            if (d < dial) continue
            val fade = (1f - (d - dial) / (far - dial + 1f)).coerceIn(0f, 1f)
            // a fixed scatter of coloured squares; the rest are plain
            val h = (r * 73 + k * 151) % 23
            val col = if (h == 0) V.Lap[(r + k) % 6] else if (h == 7) lap else V.Line
            val a = (if (h == 0 || h == 7) 0.55f else 0.7f) * fade * fade
            if (a < 0.03f) continue
            drawRoundRect(col.copy(alpha = a), Offset(p.x - sq / 2, p.y - sq / 2), Size(sq, sq), CornerRadius(sq * 0.28f))
        }
    }
}

/** Kenney, as an Android typeface, for text centred by its ink rather than its line box. */
@Composable
private fun rememberKenney(): Typeface {
    val ctx = LocalContext.current
    return remember { runCatching { ResourcesCompat.getFont(ctx, R.font.kenney_future) }.getOrNull() ?: Typeface.DEFAULT_BOLD }
}

/**
 * Text sized to its ink and drawn dead centre in it. Kenney's digits sit left
 * of their advance and its line box adds space above, so ordinary text drifts
 * left and down; this doesn't.
 */
@Composable
private fun InkText(text: String, sizePx: Float, color: Color, face: Typeface, spacingEm: Float = 0f, modifier: Modifier = Modifier) {
    val paint = remember(face, sizePx, spacingEm) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = sizePx; letterSpacing = spacingEm }
    }
    // measure every digit the same, so the time doesn't shuffle sideways as it changes
    val probe = text.map { if (it.isDigit()) '0' else it }.joinToString("")
    val b = remember(probe, paint) { Rect().also { paint.getTextBounds(probe, 0, probe.length, it) } }
    val ink = remember(text, paint) { Rect().also { paint.getTextBounds(text, 0, text.length, it) } }
    val d = LocalDensity.current
    Canvas(
        modifier.size(with(d) { b.width().toFloat().toDp() }, with(d) { b.height().toFloat().toDp() })
            .semantics { contentDescription = text },
    ) {
        paint.color = color.toArgb()
        // centre the real ink horizontally; keep the baseline steady vertically
        val x = size.width / 2 - ink.exactCenterX()
        val y = size.height / 2 - b.exactCenterY()
        drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
    }
}

/**
 * The octagon clock: sixty second ticks run round an octagon and fill in the
 * minute's lap colour, with a bead on the second. Inside, centred on the
 * dial: the date, running timers as pills, the time, then the seconds.
 */
@Composable
private fun Dial(store: Store, now: Long, onOpen: (Module) -> Unit, modifier: Modifier) {
    val cal = Calendar.getInstance().apply { timeInMillis = now }
    val sec = cal.get(Calendar.SECOND)
    val minute = cal.get(Calendar.MINUTE)
    val fmt = if (store.use24h) "HH:mm" else "h:mm"
    val timers by KTimers.list
    val running = timers.filter { it.running }.sortedBy { it.endAt }
    val face = rememberKenney()
    val d = LocalDensity.current
    SquareFit(modifier) { side ->
        Box(Modifier.size(side).testTag("dial"), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2, size.height / 2)
                val R = size.minDimension / 2 * 0.96f
                val apo = R * cos(PI / 8).toFloat()           // so the corners touch R
                val cur = V.Lap[minute % 6]; val prev = V.Lap[(minute + 5) % 6]
                for (i in 0 until 60) {
                    val t = i / 60.0 * 2 * PI - PI / 2
                    val quarter = i % 15 == 0
                    val major = i % 5 == 0
                    val outer = octRadius(apo, t)
                    val len = R * when { quarter -> 0.13f; major -> 0.10f; else -> 0.065f }
                    val col = if (i <= sec) cur else prev.copy(alpha = 0.30f)
                    val dx = cos(t).toFloat(); val dy = sin(t).toFloat()
                    drawLine(col, Offset(c.x + dx * (outer - len), c.y + dy * (outer - len)),
                        Offset(c.x + dx * outer, c.y + dy * outer),
                        R * when { quarter -> 0.026f; major -> 0.02f; else -> 0.012f }, StrokeCap.Round)
                }
                drawOct(c, R * 0.76f, V.Line, width = R * 0.008f)
                drawOct(c, R * 0.72f, V.Panel, fill = true)
                drawOct(c, R * 0.72f, cur.copy(alpha = 0.05f), fill = true)
                drawOct(c, R * 0.72f, cur.copy(alpha = 0.35f), width = R * 0.006f)
                val t = sec / 60.0 * 2 * PI - PI / 2
                val br = octRadius(apo, t) - R * 0.17f
                drawCircle(cur.copy(alpha = 0.25f), R * 0.045f, Offset(c.x + cos(t).toFloat() * br, c.y + sin(t).toFloat() * br))
                drawCircle(cur, R * 0.022f, Offset(c.x + cos(t).toFloat() * br, c.y + sin(t).toFloat() * br))
            }
            val px = with(d) { side.toPx() } / 520f   // the design is drawn for a 520 px dial
            val gap = with(d) { (12 * px).toDp() }
            CentredStack(
                gap = gap,
                above = {
                    InkText(SimpleDateFormat("EEE, MMM d", Locale.UK).format(Date(now)).uppercase(), 19 * px * 1.25f, V.Text, face, 0.1f)
                    if (running.isNotEmpty()) TimerPills(running, now, px, onOpen)
                },
                centre = { InkText(SimpleDateFormat(fmt, Locale.UK).format(Date(now)), 112 * px * 1.25f, V.White, face, modifier = Modifier.testTag("time")) },
                below = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!store.use24h) {
                            InkText(if (cal.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM", 18 * px * 1.25f, V.Muted, face, 0.1f)
                            Gap(w = with(d) { (14 * px).toDp() })
                        }
                        InkText("%02d".format(sec), 22 * px * 1.25f, V.Lap[minute % 6], face)
                    }
                },
            )
        }
    }
}

/**
 * Lays out [centre] exactly in the middle, stacks [above] upwards from its top
 * and [below] downwards from its bottom, so the time is centred on the dial
 * whatever sits around it.
 */
@Composable
private fun CentredStack(gap: Dp, above: @Composable () -> Unit, centre: @Composable () -> Unit, below: @Composable () -> Unit) {
    Layout(
        contents = listOf(
            { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(gap)) { above() } },
            centre,
            { Column(horizontalAlignment = Alignment.CenterHorizontally) { below() } },
        ),
        modifier = Modifier.fillMaxSize(),
    ) { (a, c, b), cons ->
        val loose = cons.copy(minWidth = 0, minHeight = 0)
        val pa = a.first().measure(loose); val pc = c.first().measure(loose); val pb = b.first().measure(loose)
        val g = gap.roundToPx()
        layout(cons.maxWidth, cons.maxHeight) {
            val cx = cons.maxWidth / 2; val cy = cons.maxHeight / 2
            val top = cy - pc.height / 2
            pc.place(cx - pc.width / 2, top)
            pa.place(cx - pa.width / 2, top - g - pa.height)
            pb.place(cx - pb.width / 2, top + pc.height + g)
        }
    }
}

/** Running timers, smallest first: up to two pills, then "+n". Tap one to open Kitchen. */
@Composable
private fun TimerPills(running: List<KTimer>, now: Long, px: Float, onOpen: (Module) -> Unit) {
    val d = LocalDensity.current
    val fs = with(d) { (15 * px * 1.25f).toSp() }
    Row(horizontalArrangement = Arrangement.spacedBy(with(d) { (8 * px).toDp() }), verticalAlignment = Alignment.CenterVertically) {
        running.take(2).forEach { t ->
            val col = V.Lap[t.colour.mod(V.Lap.size)].let { if (t.remaining(now) < 60_000) V.Coral else it }
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.14f)).border(1.dp, col.copy(alpha = 0.7f), RoundedCornerShape(50))
                    .tap { onOpen(Module.KITCHEN) }.padding(horizontal = with(d) { (12 * px).toDp() }, vertical = with(d) { (5 * px).toDp() }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VText(t.label.uppercase().take(10), size = fs * 0.75f, color = col, spacing = 1.sp, maxLines = 1)
                Gap(w = 8.dp)
                VText(fmtDur(t.remaining(now)), size = fs, color = V.White, font = Jupi, maxLines = 1)
            }
        }
        if (running.size > 2) VText("+${running.size - 2}", size = fs * 0.85f, color = V.Muted, maxLines = 1)
    }
}

@Composable
private fun FaceEditButton(editing: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(4.dp).size(44.dp).clip(OctS).background(if (editing) V.Teal.copy(alpha = 0.2f) else V.Panel.copy(alpha = 0.7f))
            .border(1.dp, if (editing) V.Teal else V.Line, OctS).tap(onClick),
        contentAlignment = Alignment.Center,
    ) { Glyph(if (editing) G.CHECK else G.PLUS, if (editing) V.Teal else V.Muted, Modifier.size(22.dp)) }
}

/** The widget picker: what you can put on the face, and a tick for what's already there. */
@Composable
private fun WidgetMenu(store: Store, modifier: Modifier, onDone: () -> Unit) {
    VPanel(modifier.width(260.dp), padding = 14.dp) {
        Label("Add to face")
        Gap(h = 4.dp)
        VText("Hold a widget to move it.", size = 12.sp, color = V.Muted, font = FontFamily.Default)
        Gap(h = 10.dp)
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            fun on(kind: String, ref: Long = 0L) = store.widgets.any { it.kind == kind && it.ref == ref }
            @Composable fun row(label: String, g: G, accent: Color, kind: String, ref: Long = 0L) {
                val has = on(kind, ref)
                Row(
                    Modifier.fillMaxWidth().clip(OctS).background(if (has) accent.copy(alpha = 0.12f) else V.Panel2)
                        .tap { if (has) store.widgets.filter { it.kind == kind && it.ref == ref }.forEach { store.removeWidget(it.id) } else store.addWidget(kind, ref) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph(g, accent, Modifier.size(20.dp)); Gap(w = 10.dp)
                    VText(label, size = 13.sp, color = V.Text, font = FontFamily.Default, maxLines = 1, modifier = Modifier.weight(1f))
                    if (has) Glyph(G.CHECK, accent, Modifier.size(18.dp))
                }
            }
            row("Sky", G.WEATHER, V.Sun, "sky")
            row("Sound", G.MEDIA, V.Purple, "sound")
            row("Shopping list", G.LIST, V.Green, "list")
            store.notes.take(8).forEach { n ->
                row(n.text.lineSequence().firstOrNull()?.ifBlank { null } ?: if (n.ink.isNotEmpty()) "Drawing" else "Empty note", G.NOTES, V.Yellow, "note", n.id)
            }
        }
        Gap(h = 10.dp)
        VButton("Done", Modifier.fillMaxWidth(), accent = V.Teal, filled = true, onClick = onDone)
    }
}

/** Every widget, at its saved place. Hold to drag; tap to open its screen. */
@Composable
private fun WidgetLayer(store: Store, now: Long, editing: Boolean, onOpen: (Module) -> Unit, w: Dp, h: Dp) {
    val d = LocalDensity.current
    val wiggle by rememberInfiniteTransition(label = "wig").animateFloat(-1f, 1f, infiniteRepeatable(tween(260), RepeatMode.Reverse), label = "wig")
    store.widgets.forEach { wd ->
        val size = widgetSize(wd)
        val freeX = with(d) { (w - size.first).toPx() }.coerceAtLeast(0f)
        val freeY = with(d) { (h - size.second).toPx() }.coerceAtLeast(0f)
        var drag by remember(wd.id) { mutableStateOf<Offset?>(null) }
        val base = Offset(wd.x * freeX, wd.y * freeY)
        val pos = drag ?: base
        Box(
            Modifier.offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
                .size(size.first, size.second)
                .pointerInput(wd.id, freeX, freeY) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { drag = Offset(wd.x * freeX, wd.y * freeY) },
                        onDragEnd = {
                            drag?.let { p -> store.moveWidget(wd.id, if (freeX > 0) p.x / freeX else 0f, if (freeY > 0) p.y / freeY else 0f) }
                            drag = null
                        },
                        onDragCancel = { drag = null },
                    ) { ch, amt ->
                        ch.consume()
                        val p = (drag ?: base) + amt
                        drag = Offset(p.x.coerceIn(0f, freeX), p.y.coerceIn(0f, freeY))
                    }
                },
        ) {
            Box(Modifier.fillMaxSize().offset(x = if (editing) (wiggle * 1.2f).dp else 0.dp)) {
                WidgetBody(store, wd, now, onOpen, lifted = drag != null)
            }
            if (editing) Box(
                Modifier.align(Alignment.TopEnd).offset(6.dp, (-6).dp).size(28.dp).clip(RoundedCornerShape(50))
                    .background(V.Coral).tap { store.removeWidget(wd.id) },
                contentAlignment = Alignment.Center,
            ) { Glyph(G.CLOSE, V.Bg, Modifier.size(14.dp)) }
        }
    }
}

private fun widgetSize(wd: FaceWidget): Pair<Dp, Dp> = when (wd.kind) {
    "note" -> 200.dp to 170.dp
    "sound" -> 230.dp to 120.dp
    else -> 150.dp to 150.dp
}

@Composable
private fun WidgetBody(store: Store, wd: FaceWidget, now: Long, onOpen: (Module) -> Unit, lifted: Boolean) {
    val accent = when (wd.kind) { "sky" -> V.Sun; "sound" -> V.Purple; "list" -> V.Green; else -> V.Yellow }
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxSize().clip(shape).background(V.Panel.copy(alpha = if (lifted) 1f else 0.92f))
            .border(if (lifted) 2.dp else 1.dp, if (lifted) accent else V.Line, shape)
            .tap {
                onOpen(when (wd.kind) { "sky" -> Module.WEATHER; "sound" -> Module.MEDIA; "list" -> Module.KITCHEN; else -> Module.NOTES })
            }
            .padding(14.dp),
    ) {
        when (wd.kind) {
            "sky" -> {
                val wx by WeatherRepo.state
                val off by WeatherRepo.offline
                val w = wx
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(store.city.take(14))
                    if (off) { Gap(w = 6.dp); Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(V.Coral)) }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (w == null) VText("--", size = 30.sp, color = V.Muted)
                    else Column {
                        Glyph(wxGlyph(w.code, w.isDay), wxColour(w.code, w.isDay), Modifier.size(40.dp))
                        Gap(h = 4.dp)
                        VText(t(w.temp), size = 34.sp, color = V.White, font = Jupi, maxLines = 1)
                    }
                }
                VText(
                    if (off && w != null) "OFFLINE " + wxAge(w.fetched, now).uppercase()
                    else w?.let { "${t(it.days.firstOrNull()?.max ?: it.temp)} / ${t(it.days.firstOrNull()?.min ?: it.temp)}" } ?: "",
                    size = 10.sp, color = if (off) V.Coral else V.Muted, spacing = 1.sp, maxLines = 1,
                )
            }
            "sound" -> {
                val playing by Now.playing
                val p = playing
                Label(if (p != null) p.appName.take(18) else "Sound")
                Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (p == null || p.title.isBlank()) VText("NOTHING PLAYING", size = 11.sp, color = V.Muted, spacing = 1.sp, modifier = Modifier.weight(1f))
                    else Column(Modifier.weight(1f)) {
                        VText(p.title, size = 15.sp, color = V.White, font = FontFamily.Default, maxLines = 1)
                        VText(p.artist, size = 12.sp, color = V.Muted, font = FontFamily.Default, maxLines = 1)
                    }
                    if (p != null) IconTap(if (p.playing) G.PAUSE else G.PLAY, V.Purple, 44.dp) { Now.playPause() }
                }
            }
            "list" -> {
                val left = store.shopping.filter { !it.done }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("List", Modifier.weight(1f))
                    VText("${left.size}", size = 18.sp, color = V.Green, font = Jupi)
                }
                Gap(h = 6.dp)
                if (left.isEmpty()) VText("ALL BOUGHT", size = 11.sp, color = V.Muted, spacing = 1.sp)
                left.take(4).forEach { VText(it.text, size = 13.sp, color = V.Text, font = FontFamily.Default, maxLines = 1, lineHeight = 17.sp) }
            }
            else -> {
                val n = store.notes.firstOrNull { it.id == wd.ref }
                val bg = n?.let { V.Lap[it.colour.mod(V.Lap.size)] } ?: V.Yellow
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(bg)); Gap(w = 6.dp); Label("Note")
                }
                Gap(h = 6.dp)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (n != null && n.ink.isNotEmpty()) InkPreview(n.ink, Modifier.fillMaxSize())
                    if (n != null && n.text.isNotBlank()) VText(n.text, size = 14.sp, color = V.Text, font = FontFamily.Default, maxLines = 5, lineHeight = 18.sp)
                    if (n == null) VText("Note deleted", size = 13.sp, color = V.Muted, font = FontFamily.Default)
                }
            }
        }
    }
}
