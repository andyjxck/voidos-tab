package com.andysocial.voidos.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andysocial.voidos.FaceWidget
import com.andysocial.voidos.Now
import com.andysocial.voidos.Store
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A widget's normal size on the face, before any pinch. */
internal fun widgetSize(kind: String): Pair<Dp, Dp> = when (kind) {
    "note" -> 200.dp to 170.dp
    "hrev", "hdl" -> 190.dp to 150.dp
    "hstatus" -> 230.dp to 150.dp
    "forecast" -> 230.dp to 150.dp
    "stopwatch", "focus" -> 190.dp to 110.dp
    else -> 150.dp to 150.dp
}

internal fun widgetSize(wd: FaceWidget): Pair<Dp, Dp> = widgetSize(wd.kind)

/** How a stretched widget is shaped next to its normal shape. */
internal enum class WShape { NORMAL, WIDE, TALL }

/**
 * How a widget fills its box. [s] scales every size inside it (text, icons,
 * gaps) with the box, by the smaller of the two stretches, so it can never
 * grow out of the box. [cw] x [ch] is the box in the widget's own design
 * units: never smaller than its normal size, with the extra room on the side
 * it was stretched, which [shape] uses to lay it out differently.
 */
internal data class WFit(val s: Float, val cw: Float, val ch: Float, val shape: WShape) {
    /** The room inside the 16 unit padding. */
    val iw get() = cw - 32f
    val ih get() = ch - 32f
}

internal fun widgetFit(kind: String, wDp: Float, hDp: Float): WFit {
    val (bw, bh) = widgetSize(kind)
    val s = min(wDp / bw.value, hDp / bh.value).coerceAtLeast(0.2f)
    val cw = wDp / s; val ch = hDp / s
    val r = (cw / ch) / (bw.value / bh.value)
    val shape = when { r >= 1.5f -> WShape.WIDE; r <= 1f / 1.5f -> WShape.TALL; else -> WShape.NORMAL }
    return WFit(s, cw, ch, shape)
}

/**
 * One line of text at the largest size up to [max] that fits the room it is
 * given, so a long value shrinks instead of being cut off.
 */
@Composable
internal fun FitText(
    text: String,
    max: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = V.White,
    font: FontFamily = SansLight,
    align: Alignment = Alignment.CenterStart,
    min: TextUnit = 5.sp,
    lines: Int = 1,
) {
    BoxWithConstraints(modifier, contentAlignment = align) {
        val m = rememberTextMeasurer()
        val wPx = constraints.maxWidth; val hPx = constraints.maxHeight
        val size = remember(text, max, wPx, hPx, font, m, lines) {
            var s = max.value
            while (s > min.value) {
                val st = TextStyle(fontSize = s.sp, fontFamily = font, fontFeatureSettings = "tnum")
                val fits = if (lines == 1) {
                    val r = m.measure(text, st, maxLines = 1, softWrap = false)
                    (wPx == Constraints.Infinity || r.size.width <= wPx * 0.97f - 1) && (hPx == Constraints.Infinity || r.size.height <= hPx)
                } else {
                    val r = m.measure(text, st, maxLines = lines, constraints = Constraints(maxWidth = if (wPx == Constraints.Infinity) Int.MAX_VALUE else wPx))
                    !r.hasVisualOverflow && (hPx == Constraints.Infinity || r.size.height <= hPx)
                }
                if (fits) break
                s *= 0.92f
            }
            s.coerceAtLeast(min.value).sp
        }
        VText(text, size = size, color = color, font = font, maxLines = lines, align = if (align == Alignment.Center || align == Alignment.TopCenter || align == Alignment.BottomCenter) TextAlign.Center else TextAlign.Start)
    }
}

/** As many lines of [unit] height as fit [room]. */
private fun linesIn(room: Float, unit: Float) = floor(room / unit).toInt().coerceAtLeast(0)

@Composable
internal fun WidgetBody(store: Store, wd: FaceWidget, now: Long, onOpen: (Module) -> Unit, lifted: Boolean) {
    val accent = V.Teal
    val shape = Radius.Card
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .liquidGlass(shape, GlassLook(smoke = if (lifted) 0.9f else 0.72f, depth = if (lifted) 1.6f else 0.9f, shine = if (lifted) 1.3f else 1f, stain = if (lifted) accent else null, stainAlpha = 0.14f))
            .tap {
                onOpen(when (wd.kind) {
                    "sky", "forecast" -> Module.WEATHER
                    "stopwatch", "alarm" -> Module.TOOLS; "battery", "focus" -> Module.SYSTEM
                    "hstatus", "hcrash", "hup" -> Module.APPS
                    "hrev", "hdl" -> { store.setHub(app = com.andysocial.voidos.hub.Hub.ALL, metric = if (wd.kind == "hrev") "REVENUE" else "DOWNLOADS"); Module.METRICS }
                    else -> Module.NOTES
                })
            },
    ) {
        val fit = widgetFit(wd.kind, maxWidth.value, maxHeight.value)
        val d = LocalDensity.current
        // every dp and sp inside is scaled with the box; the widget's size comes from the pinch, so the system font size is left out
        CompositionLocalProvider(LocalDensity provides Density(d.density * fit.s, 1f)) {
            Box(Modifier.fillMaxSize().padding(16.dp).testTag("wbody-${wd.kind}")) {
                WidgetContent(store, wd, now, fit)
            }
        }
    }
}

@Composable
private fun WidgetContent(store: Store, wd: FaceWidget, now: Long, fit: WFit) {
    when (wd.kind) {
        "sky" -> SkyWidget(store, now, fit)
        "battery" -> BatteryWidget(fit)
        "hrev", "hdl", "hcrash", "hup", "hstatus" -> com.andysocial.voidos.hub.HubWidget(wd.kind, fit.shape == WShape.WIDE, fit.iw, fit.ih)
        "stopwatch" -> StopwatchWidget(now, fit)
        "alarm" -> AlarmWidget(store, now, fit)
        "forecast" -> ForecastWidget(store, fit)
        "focus" -> FocusWidget(store, fit)
        else -> NoteWidget(store, wd, fit)
    }
}

@Composable
private fun Head(text: String, modifier: Modifier = Modifier, dot: Color? = null) {
    // 2.0.1: the old face's widget header, a coloured dot and the name in spaced small caps
    DotLabel(text, dot ?: V.Teal, modifier)
}

@Composable
private fun SkyWidget(store: Store, now: Long, fit: WFit) {
    val wx by WeatherRepo.state
    val off by WeatherRepo.offline
    val w = wx
    val sub = if (off && w != null) "Offline \u00b7 " + wxAge(w.fetched, now)
    else w?.let { "${t(it.days.firstOrNull()?.max ?: it.temp)} / ${t(it.days.firstOrNull()?.min ?: it.temp)}" } ?: ""
    val subColor = if (off) V.Coral else V.Muted
    val city = store.city.take(18)
    when (fit.shape) {
        WShape.WIDE -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (w != null) { WxIcon(w.code, w.isDay, Modifier.size(min(fit.ih * 0.8f, 64f).dp)); Gap(w = 12.dp) }
            FitText(if (w == null) "--" else t(w.temp), 40.sp, Modifier.weight(1f).fillMaxHeight(), color = if (w == null) V.Muted else V.White)
            Gap(w = 12.dp)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Head(city, dot = if (off) V.Coral else null)
                if (sub.isNotEmpty()) { Gap(h = 4.dp); FitText(sub, Type.label, color = subColor, font = Sans, align = Alignment.CenterEnd) }
            }
        }
        WShape.TALL -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Head(city, Modifier.align(Alignment.Start), dot = if (off) V.Coral else null)
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (w != null) { WxIcon(w.code, w.isDay, Modifier.size(min(fit.iw * 0.6f, 72f).dp)); Gap(h = 8.dp) }
                FitText(if (w == null) "--" else t(w.temp), 40.sp, Modifier.fillMaxWidth().height(56.dp), color = if (w == null) V.Muted else V.White, align = Alignment.Center)
            }
            if (sub.isNotEmpty()) FitText(sub, Type.caption, Modifier.fillMaxWidth(), color = subColor, font = Sans, align = Alignment.Center)
        }
        WShape.NORMAL -> Column(Modifier.fillMaxSize()) {
            Head(city, dot = if (off) V.Coral else null)
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (w != null) { WxIcon(w.code, w.isDay, Modifier.size(min(fit.iw * 0.34f, 52f).dp)); Gap(w = 8.dp) }
                FitText(if (w == null) "--" else t(w.temp), Type.display, Modifier.weight(1f), color = if (w == null) V.Muted else V.White)
            }
            if (sub.isNotEmpty()) FitText(sub, Type.caption, Modifier.fillMaxWidth(), color = subColor, font = Sans)
        }
    }
}

@Composable
private fun BatteryWidget(fit: WFit) {
    val b = rememberBattery()
    val pct = if (b.pct < 0) "--" else "${b.pct}%"
    val head = if (b.charging) "Charging" else "Battery"
    @Composable fun Ring(sizeDp: Float, text: Boolean) {
        Box(Modifier.size(sizeDp.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = (sizeDp * 0.095f).dp.toPx()
                val inset = sw / 2f
                val arc = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw)
                val tl = androidx.compose.ui.geometry.Offset(inset, inset)
                drawArc(Color.White.copy(alpha = 0.10f), -90f, 360f, false, tl, arc, style = Stroke(sw))
                drawArc(if (b.pct in 0..15) V.Coral else V.Teal, -90f, 3.6f * b.pct.coerceAtLeast(0), false, tl, arc, style = Stroke(sw, cap = StrokeCap.Round))
            }
            if (text) FitText(pct, (sizeDp * 0.26f).sp, Modifier.size((sizeDp * 0.66f).dp, (sizeDp * 0.4f).dp), color = V.White, font = Sans, align = Alignment.Center)
        }
    }
    if (fit.shape == WShape.WIDE) Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Ring(fit.ih, false)
        Gap(w = 16.dp)
        Column(Modifier.weight(1f)) {
            Label(head)
            FitText(pct, 40.sp, Modifier.fillMaxWidth().height(min(56f, fit.ih - 20f).dp))
        }
    }
    else Column(Modifier.fillMaxSize()) {
        Label(head)
        Gap(h = 4.dp)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Ring(min(fit.iw, fit.ih - 22f).coerceAtLeast(20f) * 0.92f, true)
        }
    }
}

@Composable
private fun StopwatchWidget(now: Long, fit: WFit) {
    val text = stopwatchText(Stopwatch.elapsed(now)).substringBefore('.')
    val toggle = { if (Stopwatch.running) Stopwatch.stop() else Stopwatch.start() }
    if (fit.shape == WShape.TALL) Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.align(Alignment.Start)) { Head("Stopwatch") }
        FitText(text, 40.sp, Modifier.weight(1f).fillMaxWidth(), align = Alignment.Center)
        IconTap(if (Stopwatch.running) G.PAUSE else G.PLAY, V.White, size = 52.dp) { toggle() }
    }
    else Column(Modifier.fillMaxSize()) {
        Head("Stopwatch")
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FitText(text, if (fit.shape == WShape.WIDE) 40.sp else Type.large, Modifier.weight(1f).fillMaxHeight())
            Gap(w = 8.dp)
            IconTap(if (Stopwatch.running) G.PAUSE else G.PLAY, V.White, size = min(44f, fit.ih - 18f).coerceAtLeast(28f).dp) { toggle() }
        }
    }
}

@Composable
private fun AlarmWidget(store: Store, now: Long, fit: WFit) {
    val list by com.andysocial.voidos.Alarms.list
    val next = com.andysocial.voidos.AlarmMath.soonest(list, now, java.time.ZoneId.systemDefault())
    val time = next?.let { com.andysocial.voidos.AlarmMath.clock(it.first.hour, it.first.minute, store.use24h) }
    val until = next?.let { com.andysocial.voidos.AlarmMath.untilLabel(it.second - now) }
    if (fit.shape == WShape.WIDE) Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        FitText(time ?: "None set", if (time == null) Type.headline else 44.sp, Modifier.weight(1f).fillMaxHeight(), color = if (time == null) V.Muted else V.White, font = if (time == null) Sans else SansLight)
        Gap(w = 12.dp)
        Column(Modifier.weight(0.8f), horizontalAlignment = Alignment.End) {
            Head("Alarm", dot = V.Amber)
            if (until != null) { Gap(h = 4.dp); FitText(until, Type.label, color = V.Muted, font = Sans, align = Alignment.CenterEnd) }
        }
    }
    else Column(Modifier.fillMaxSize(), horizontalAlignment = if (fit.shape == WShape.TALL) Alignment.CenterHorizontally else Alignment.Start) {
        Box(Modifier.align(Alignment.Start)) { Head("Alarm", dot = V.Amber) }
        val a = if (fit.shape == WShape.TALL) Alignment.Center else Alignment.CenterStart
        FitText(time ?: "None set", if (time == null) Type.body else 36.sp, Modifier.weight(1f).fillMaxWidth(), color = if (time == null) V.Muted else V.White, font = if (time == null) Sans else SansLight, align = a)
        if (until != null) FitText(until, Type.caption, Modifier.fillMaxWidth(), color = V.Muted, font = Sans, align = a)
    }
}

@Composable
private fun ForecastWidget(store: Store, fit: WFit) {
    val wx = WeatherRepo.state.value
    val all = wx?.days.orEmpty()
    fun dayName(i: Int) = if (i == 0) "Today" else java.time.LocalDate.now().plusDays(i.toLong()).format(java.time.format.DateTimeFormatter.ofPattern("EEE", java.util.Locale.UK))
    Column(Modifier.fillMaxSize()) {
        Label(store.city.take(18))
        if (all.isEmpty()) { Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { VText("--", size = Type.title2, color = V.Muted) }; return@Column }
        if (fit.shape == WShape.TALL) {
            Gap(h = 8.dp)
            val rowH = 34f
            val n = min(all.size, linesIn(fit.ih - 24f, rowH).coerceAtLeast(1))
            all.take(n).forEachIndexed { i, d ->
                Row(Modifier.fillMaxWidth().height(rowH.dp), verticalAlignment = Alignment.CenterVertically) {
                    FitText(dayName(i), Type.label, Modifier.weight(1f), color = V.Muted, font = Sans, min = 7.sp)
                    WxIcon(d.code, true, Modifier.size(24.dp))
                    Gap(w = 8.dp)
                    FitText(t(d.max), Type.body, Modifier.weight(0.7f), color = V.White, font = Sans, align = Alignment.CenterEnd)
                }
            }
        } else {
            val n = min(all.size, if (fit.shape == WShape.WIDE) (fit.iw / 52f).toInt().coerceIn(4, 7) else 4)
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                all.take(n).forEachIndexed { i, d ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        FitText(dayName(i), Type.caption, Modifier.fillMaxWidth(), color = V.Muted, font = Sans, align = Alignment.Center)
                        WxIcon(d.code, true, Modifier.size(32.dp))
                        FitText(t(d.max), Type.body, Modifier.fillMaxWidth(), color = V.White, font = Sans, align = Alignment.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusWidget(store: Store, fit: WFit) {
    val fm = store.focusModes.firstOrNull { it.name == store.focusOn }
    val g = if (fm != null) focusGlyph(fm.glyph) else G.FOCUS_MODE
    val c = if (fm != null) V.Purple else V.Muted
    val name = fm?.name ?: "Off"
    if (fit.shape == WShape.TALL) Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.align(Alignment.Start)) { Head("Focus") }
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Glyph(g, c, Modifier.size(min(fit.iw * 0.45f, 56f).dp))
            Gap(h = 8.dp)
            FitText(name, Type.title, Modifier.fillMaxWidth(), color = V.White, font = Sans, align = Alignment.Center)
        }
    }
    else Column(Modifier.fillMaxSize()) {
        Head("Focus")
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Glyph(g, c, Modifier.size(28.dp))
            Gap(w = 8.dp)
            FitText(name, if (fit.shape == WShape.WIDE) Type.title2 else Type.title, Modifier.weight(1f), color = V.White, font = Sans)
        }
    }
}

@Composable
private fun NoteWidget(store: Store, wd: FaceWidget, fit: WFit) {
    val n = store.notes.firstOrNull { it.id == wd.ref }
    val bg = n?.let { V.Lap[it.colour.mod(V.Lap.size)] } ?: V.Yellow
    Column(Modifier.fillMaxSize()) {
        Head("Note", dot = bg)
        Gap(h = 8.dp)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val line = 18f
            val lines = max(1, linesIn(maxHeight.value, line))
            if (n != null && n.ink.isNotEmpty()) InkPreview(n.ink, Modifier.fillMaxSize())
            if (n != null && n.text.isNotBlank()) VText(n.text, size = Type.body, color = V.Text, font = Sans, maxLines = lines, lineHeight = line.sp)
            if (n == null) VText("Note deleted", size = Type.label, color = V.Muted, font = Sans, maxLines = 1)
        }
    }
}
