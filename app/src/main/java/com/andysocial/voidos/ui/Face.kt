package com.andysocial.voidos.ui

import androidx.compose.foundation.shape.CircleShape

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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableStateMapOf
import com.andysocial.voidos.ClockPlace
import com.andysocial.voidos.WIDGET_MIN_SCALE
import com.andysocial.voidos.WIDGET_MAX_SCALE
import com.andysocial.voidos.CLOCK_MIN_SCALE
import com.andysocial.voidos.CLOCK_MAX_SCALE
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
 * it there. The date sits over a large, thin clock in the middle of the
 * screen; running timers show as small pills underneath. Widgets
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
            val d = LocalDensity.current
            val faceW = with(d) { maxWidth.toPx() }
            val faceH = with(d) { maxHeight.toPx() }
            // what is being moved right now, live; saved when the fingers lift
            val live = remember { mutableStateMapOf<Long, FaceBox>() }
            val clockBase = clockPx(faceW, faceH)
            val clockBox = live[CLOCK_TARGET] ?: clockBoxOf(store.clock, faceW, faceH, clockBase)
            val clockNow = if (live[CLOCK_TARGET] != null) placeOf(clockBox, faceW, faceH, clockBase) else store.clock
            Box(
                Modifier.fillMaxSize().faceGestures(
                    enabled = true, faceW = faceW, faceH = faceH,
                    targets = {
                        val ws = store.widgets.map { wd ->
                            val (bw, bh) = widgetSize(wd).let { (a, b) -> with(d) { a.toPx() to b.toPx() } }
                            FaceTarget(
                                wd.id, live[wd.id] ?: widgetBoxOf(wd, bw, bh, faceW, faceH), false,
                                bw * WIDGET_MIN_SCALE, minOf(bw * WIDGET_MAX_SCALE, faceW * 0.95f),
                                bh * WIDGET_MIN_SCALE, minOf(bh * WIDGET_MAX_SCALE, faceH * 0.95f),
                            )
                        }
                        val cb = clockBoxOf(store.clock, faceW, faceH, clockBase)
                        listOf(
                            FaceTarget(
                                CLOCK_TARGET, cb, true,
                                cb.w / store.clock.scale * CLOCK_MIN_SCALE, cb.w / store.clock.scale * CLOCK_MAX_SCALE,
                                cb.h / store.clock.scale * CLOCK_MIN_SCALE, cb.h / store.clock.scale * CLOCK_MAX_SCALE,
                            ),
                        ) + ws
                    },
                    onChange = { id, b -> live[id] = b },
                    onEnd = { id, b ->
                        if (id == CLOCK_TARGET) store.placeClock(placeOf(b, faceW, faceH, clockBase))
                        else store.widgets.firstOrNull { it.id == id }?.let { wd ->
                            val (bw, bh) = widgetSize(wd).let { (a, c) -> with(d) { a.toPx() to c.toPx() } }
                            val left = b.cx - b.w / 2f; val top = b.cy - b.h / 2f
                            store.placeWidget(
                                id, if (faceW > b.w) left / (faceW - b.w) else 0f, if (faceH > b.h) top / (faceH - b.h) else 0f,
                                b.w / bw, b.h / bh, b.rot,
                            )
                        }
                        live.remove(id)
                    },
                ),
            ) {
                Dial(
                    store, now, onOpen, clockNow.scale,
                    Modifier.fillMaxSize().graphicsLayer {
                        translationX = (clockNow.cx - 0.5f) * faceW
                        translationY = (clockNow.cy - 0.5f) * faceH
                        rotationZ = clockNow.rot
                    },
                )
                // a faint outline while the clock is picked up
                if (live[CLOCK_TARGET] != null) Box(
                    Modifier.offset { IntOffset((clockBox.cx - clockBox.w / 2f).roundToInt(), (clockBox.cy - clockBox.h / 2f).roundToInt()) }
                        .size(with(d) { clockBox.w.toDp() }, with(d) { clockBox.h.toDp() })
                        .graphicsLayer { rotationZ = clockBox.rot }
                        .border(1.dp, V.Teal.copy(alpha = 0.45f), RoundedCornerShape(28.dp)),
                )
                WidgetLayer(store, now, editing, onOpen, faceW, faceH, live)
            }
            // the one control on the face: add, move and remove widgets
            Box(Modifier.align(Alignment.TopEnd)) {
                FaceEditButton(editing) { editing = !editing }
            }
            if (editing) WidgetMenu(store, Modifier.align(Alignment.TopEnd).padding(top = 60.dp)) { editing = false }
        }
        // favourites, only when you've starred some
        if (favs.isNotEmpty()) {
            Row(
                Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp)
                    .glass(RoundedCornerShape(30.dp), 0.7f).padding(horizontal = 18.dp, vertical = 8.dp),
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

/** The system's thin and regular sans as Android typefaces, for text centred by its ink rather than its line box. */
@Composable
private fun rememberFace(name: String): Typeface = remember(name) { Typeface.create(name, Typeface.NORMAL) }

/**
 * Text sized to its ink and drawn dead centre in it. SansMedium's digits sit left
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
 * The clock: the date, then the time as large as the space allows in a thin
 * face, then any running timers. Nothing around it.
 */
@Composable
private fun Dial(store: Store, now: Long, onOpen: (Module) -> Unit, scale: Float, modifier: Modifier) {
    val fmt = if (store.use24h) "HH:mm" else "h:mm"
    val timers by KTimers.list
    val running = timers.filter { it.running }.sortedBy { it.endAt }
    val thin = rememberFace("sans-serif-thin")
    val regular = rememberFace("sans-serif")
    val d = LocalDensity.current
    BoxWithConstraints(modifier.testTag("dial")) {
        val w = with(d) { maxWidth.toPx() }
        val h = with(d) { maxHeight.toPx() }
        // the time takes about two thirds of the width, or less when the screen is short
        val timePx = clockPx(w, h) * scale
        val gap = with(d) { (timePx * 0.10f).toDp() }
        CentredStack(
            gap = gap,
            above = {
                InkText(SimpleDateFormat("EEEE d MMMM", Locale.UK).format(Date(now)), timePx * 0.115f, V.Text.copy(alpha = 0.78f), regular, 0.01f)
            },
            centre = {
                // no AM/PM: the time alone, as big as it can be
                InkText(SimpleDateFormat(fmt, Locale.UK).format(Date(now)), timePx, V.White, thin, -0.02f, modifier = Modifier.testTag("time"))
            },
            below = {
                if (running.isNotEmpty()) TimerPills(running, now, timePx / 400f, onOpen)
                NextAlarm(now, store.use24h, timePx / 400f, top = running.isNotEmpty(), onOpen)
            },
        )
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

/** The next alarm, when one rings in the next 24 hours: a quiet bell and its time. Tap to open Tools. */
@Composable
private fun NextAlarm(now: Long, use24h: Boolean, px: Float, top: Boolean, onOpen: (Module) -> Unit) {
    val next = com.andysocial.voidos.AlarmMath.soonest(com.andysocial.voidos.Alarms.list.value, now, com.andysocial.voidos.Alarms.zone) ?: return
    if (next.second - now > 24 * 3_600_000L) return
    val d = LocalDensity.current
    val fs = with(d) { (15 * px * 1.1f).toSp() }
    Row(
        Modifier.padding(top = if (top) 10.dp else 0.dp).clip(RoundedCornerShape(50)).tap { onOpen(Module.TOOLS) }
            .padding(horizontal = 12.dp, vertical = 6.dp).testTag("next_alarm"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(G.BELL, V.Muted, Modifier.size(with(d) { (16 * px).toDp() }))
        Gap(w = 8.dp)
        VText(SimpleDateFormat(if (use24h) "HH:mm" else "h:mm a", Locale.UK).format(Date(next.second)), size = fs, color = V.Muted, maxLines = 1)
    }
}

/** Running timers, soonest first: up to two pills, then "+n". Tap one to open Tools. */
@Composable
private fun TimerPills(running: List<KTimer>, now: Long, px: Float, onOpen: (Module) -> Unit) {
    val d = LocalDensity.current
    val fs = with(d) { (15 * px * 1.25f).toSp() }
    Row(horizontalArrangement = Arrangement.spacedBy(with(d) { (8 * px).toDp() }), verticalAlignment = Alignment.CenterVertically) {
        running.take(2).forEach { t ->
            val soon = t.remaining(now) < 60_000
            Row(
                Modifier.glass(RoundedCornerShape(50), 0.8f)
                    .tap { onOpen(Module.TOOLS) }.padding(horizontal = with(d) { (16 * px).toDp() }, vertical = with(d) { (7 * px).toDp() }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(if (soon) V.Coral else V.Teal))
                Gap(w = 10.dp)
                VText(t.label.take(14), size = fs * 0.85f, color = V.Muted, maxLines = 1)
                Gap(w = 10.dp)
                VText(fmtDur(t.remaining(now)), size = fs, color = V.White, font = SansLight, maxLines = 1)
            }
        }
        if (running.size > 2) VText("+${running.size - 2}", size = fs * 0.85f, color = V.Muted, maxLines = 1)
    }
}

@Composable
private fun FaceEditButton(editing: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(4.dp).size(42.dp).clip(CircleShape).background(if (editing) V.Teal else V.Panel.copy(alpha = 0.7f)).tap(onClick),
        contentAlignment = Alignment.Center,
    ) { Glyph(if (editing) G.CHECK else G.PLUS, if (editing) V.Bg else V.Muted, Modifier.size(20.dp)) }
}

/** The widget picker: what you can put on the face, and a tick for what's already there. */
@Composable
private fun WidgetMenu(store: Store, modifier: Modifier, onDone: () -> Unit) {
    VPanel(modifier.width(260.dp), padding = 14.dp) {
        Label("Add to face")
        Gap(h = 4.dp)
        VText("Hold a widget or the clock to move it. Pinch to stretch it, twist to turn it.", size = 12.sp, color = V.Muted, lineHeight = 17.sp)
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
            row("Weather", G.WEATHER, V.Teal, "sky")
            row("Music", G.MEDIA, V.Teal, "sound")
            row("List", G.LIST, V.Teal, "list")
            store.notes.take(8).forEach { n ->
                row(n.text.lineSequence().firstOrNull()?.ifBlank { null } ?: if (n.ink.isNotEmpty()) "Drawing" else "Empty note", G.NOTES, V.Yellow, "note", n.id)
            }
        }
        Gap(h = 10.dp)
        GlassActions(
            listOf(
                GlassAction("Centre clock", enabled = store.clock != ClockPlace()) { store.resetClock() },
                GlassAction("Done", onClick = onDone),
            ),
            height = 46.dp,
        )
    }
}

/** The time's size at the clock's normal scale: about two thirds of the width, less when the screen is short. */
internal fun clockPx(faceW: Float, faceH: Float) = minOf(faceW * 0.30f, faceH * 0.36f)

/** The box you can grab the clock by: the date, the time and the timer pills. */
internal fun clockBoxOf(c: ClockPlace, faceW: Float, faceH: Float, basePx: Float) =
    FaceBox(c.cx * faceW, c.cy * faceH, basePx * 2.6f * c.scale, basePx * 1.55f * c.scale, c.rot)

/** Back from a live clock box to the place that gets saved. */
internal fun placeOf(b: FaceBox, faceW: Float, faceH: Float, basePx: Float) =
    ClockPlace(b.cx / faceW, b.cy / faceH, b.w / (basePx * 2.6f), b.rot)

/** A saved widget as a box on the face, in pixels. */
internal fun widgetBoxOf(wd: FaceWidget, baseW: Float, baseH: Float, faceW: Float, faceH: Float): FaceBox {
    val w = baseW * wd.sw; val h = baseH * wd.sh
    val left = wd.x * (faceW - w).coerceAtLeast(0f); val top = wd.y * (faceH - h).coerceAtLeast(0f)
    return FaceBox(left + w / 2f, top + h / 2f, w, h, wd.rot)
}

/** Every widget at its saved place, size and turn. Hold to drag, pinch to stretch and turn, tap to open its screen. */
@Composable
private fun WidgetLayer(store: Store, now: Long, editing: Boolean, onOpen: (Module) -> Unit, faceW: Float, faceH: Float, live: Map<Long, FaceBox>) {
    val d = LocalDensity.current
    val wiggle by rememberInfiniteTransition(label = "wig").animateFloat(-1f, 1f, infiniteRepeatable(tween(260), RepeatMode.Reverse), label = "wig")
    store.widgets.forEach { wd ->
        val (bw, bh) = widgetSize(wd).let { (a, b) -> with(d) { a.toPx() to b.toPx() } }
        val held = live[wd.id]
        val box = held ?: widgetBoxOf(wd, bw, bh, faceW, faceH)
        Box(
            Modifier.offset { IntOffset((box.cx - box.w / 2f).roundToInt(), (box.cy - box.h / 2f).roundToInt()) }
                .size(with(d) { box.w.toDp() }, with(d) { box.h.toDp() })
                .graphicsLayer { rotationZ = box.rot }
                .testTag("widget-${wd.kind}"),
        ) {
            Box(Modifier.fillMaxSize().offset(x = if (editing && held == null) (wiggle * 1.2f).dp else 0.dp)) {
                WidgetBody(store, wd, now, onOpen, lifted = held != null)
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
    val accent = V.Teal
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxSize().glass(shape, if (lifted) 0.95f else 0.72f)
            .then(if (lifted) Modifier.border(1.5.dp, accent.copy(alpha = 0.7f), shape) else Modifier)
            .tap {
                onOpen(when (wd.kind) { "sky" -> Module.WEATHER; "sound" -> Module.MEDIA; "list" -> Module.TOOLS; else -> Module.NOTES })
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
                        VText(t(w.temp), size = 34.sp, color = V.White, font = SansLight, maxLines = 1)
                    }
                }
                VText(
                    if (off && w != null) "Offline \u00b7 " + wxAge(w.fetched, now)
                    else w?.let { "${t(it.days.firstOrNull()?.max ?: it.temp)} / ${t(it.days.firstOrNull()?.min ?: it.temp)}" } ?: "",
                    size = 10.sp, color = if (off) V.Coral else V.Muted, maxLines = 1,
                )
            }
            "sound" -> {
                val playing by Now.playing
                val p = playing
                Label(if (p != null) p.appName.take(18) else "Sound")
                Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (p == null || p.title.isBlank()) VText("Nothing playing", size = 11.sp, color = V.Muted, modifier = Modifier.weight(1f))
                    else Column(Modifier.weight(1f)) {
                        VText(p.title, size = 15.sp, color = V.White, font = FontFamily.Default, maxLines = 1)
                        VText(p.artist, size = 12.sp, color = V.Muted, font = FontFamily.Default, maxLines = 1)
                    }
                    if (p != null) IconTap(if (p.playing) G.PAUSE else G.PLAY, V.White, 44.dp) { Now.playPause() }
                }
            }
            "list" -> {
                val left = store.shopping.filter { !it.done }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("List", Modifier.weight(1f))
                    VText("${left.size}", size = 18.sp, color = V.White, font = SansLight)
                }
                Gap(h = 6.dp)
                if (left.isEmpty()) VText("All bought", size = 11.sp, color = V.Muted)
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
