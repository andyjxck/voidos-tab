package com.andysocial.voidos.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import com.andysocial.voidos.KTimers
import com.andysocial.voidos.Now
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andysocial.voidos.Store
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The home face. The big octagon is the PineTime digital face scaled up: a
 * 60-tick lap ring that fills with the seconds, changing colour every minute
 * through the six watch colours, with the time in Kenney inside.
 */
@Composable
fun FaceScreen(store: Store, onOpen: (Module) -> Unit) {
    val portrait = LocalPortrait.current
    val now = rememberNow(1000)
    LaunchedEffect(Unit) { WeatherRepo.refresh(store) }
    if (portrait) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Dial(store, now, Modifier.weight(1.05f).fillMaxWidth())
            FaceTiles(store, now, onOpen, Modifier.weight(1f).fillMaxWidth(), columns = 2)
        }
    } else {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Dial(store, now, Modifier.weight(1.25f).fillMaxHeight())
            FaceTiles(store, now, onOpen, Modifier.weight(1f).fillMaxHeight(), columns = 2)
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
 * The octagon clock: sixty second ticks run round an octagon (not a circle)
 * and fill in the minute's lap colour; the four quarter ticks are longer. A
 * lap-coloured bead marks the second. Under the time: AM/PM on a 12-hour
 * clock, and the next kitchen timer to finish while any are running.
 */
@Composable
private fun Dial(store: Store, now: Long, modifier: Modifier) {
    val cal = Calendar.getInstance().apply { timeInMillis = now }
    val sec = cal.get(Calendar.SECOND)
    val minute = cal.get(Calendar.MINUTE)
    val fmt = if (store.use24h) "HH:mm" else "h:mm"
    val timers by KTimers.list
    val next = timers.filter { it.running }.minByOrNull { it.remaining() }
    SquareFit(modifier) { side ->
        Box(Modifier.size(side), contentAlignment = Alignment.Center) {
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
                // faint lap colour wash in the dial
                drawOct(c, R * 0.72f, cur.copy(alpha = 0.05f), fill = true)
                drawOct(c, R * 0.72f, cur.copy(alpha = 0.35f), width = R * 0.006f)
                // the bead rides just inside the ticks, on the same octagon
                val t = sec / 60.0 * 2 * PI - PI / 2
                val br = octRadius(apo, t) - R * 0.17f
                drawCircle(cur.copy(alpha = 0.25f), R * 0.045f, Offset(c.x + cos(t).toFloat() * br, c.y + sin(t).toFloat() * br))
                drawCircle(cur, R * 0.022f, Offset(c.x + cos(t).toFloat() * br, c.y + sin(t).toFloat() * br))
            }
            val k = side.value / 520f
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                VText(SimpleDateFormat("EEE, MMM d", Locale.UK).format(Date(now)).uppercase(), size = (20 * k).sp, color = V.Text, spacing = (2 * k).sp, maxLines = 1)
                Row(verticalAlignment = Alignment.Bottom) {
                    VText(SimpleDateFormat(fmt, Locale.UK).format(Date(now)), size = (108 * k).sp, color = V.White, maxLines = 1)
                    if (!store.use24h) VText(
                        if (cal.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM", size = (18 * k).sp, color = V.Muted,
                        modifier = Modifier.padding(start = (6 * k).dp, bottom = (22 * k).dp), maxLines = 1,
                    )
                }
                VText("%02d".format(sec), size = (22 * k).sp, color = V.Lap[minute % 6], font = Jupi, maxLines = 1)
                if (next != null) {
                    Gap(h = (8 * k).dp)
                    VText("${next.label.uppercase()}  ${fmtDur(next.remaining())}", size = (15 * k).sp, color = V.Orange, spacing = (1.5f * k).sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun FaceTiles(store: Store, now: Long, onOpen: (Module) -> Unit, modifier: Modifier, columns: Int) {
    val ctx = LocalContext.current
    val bat = rememberBattery()
    val wx by WeatherRepo.state
    val playing by Now.playing
    val timers = KTimers.list.value
    val cal = Calendar.getInstance().apply { timeInMillis = now }

    val tiles = mutableListOf<@Composable (Modifier) -> Unit>()
    // sky
    tiles += { m ->
        val w = wx
        FaceTile(m, "Sky / ${store.city}", w?.let { wxColour(it.code, it.isDay) } ?: V.Muted, onClick = { onOpen(Module.WEATHER) }) {
            if (w == null) VText("--", size = 30.sp, color = V.Muted)
            else Row(verticalAlignment = Alignment.CenterVertically) {
                Glyph(wxGlyph(w.code, w.isDay), wxColour(w.code, w.isDay), Modifier.size(44.dp))
                Gap(w = 12.dp)
                Column {
                    VText(t(w.temp), size = 32.sp, color = V.White, font = Jupi)
                    VText("${t(w.days.firstOrNull()?.max ?: w.temp)} / ${t(w.days.firstOrNull()?.min ?: w.temp)}", size = 11.sp, color = V.Muted, maxLines = 1)
                }
            }
        }
    }
    // timers
    tiles += { m ->
        val next = timers.filter { it.running }.minByOrNull { it.endAt }
        FaceTile(m, "Timers", V.Orange, onClick = { onOpen(Module.KITCHEN) }) {
            if (next == null) Row(verticalAlignment = Alignment.CenterVertically) {
                Glyph(G.TIMER, V.Tick, Modifier.size(36.dp)); Gap(w = 10.dp)
                VText(if (timers.isEmpty()) "TAP TO START" else "${timers.size} PAUSED", size = 12.sp, color = V.Muted, spacing = 1.sp)
            } else Column {
                VText(fmtDur(next.remaining(now)), size = 32.sp, color = V.White, font = Jupi)
                val more = timers.count { it.running } - 1
                VText(next.label.uppercase() + if (more > 0) "  +$more MORE" else "", size = 11.sp, color = V.Orange, maxLines = 1, spacing = 1.sp)
            }
        }
    }
    // now playing
    tiles += { m ->
        val p = playing
        FaceTile(m, if (p != null) "Playing / ${p.appName}" else "Sound", V.Purple, onClick = { onOpen(Module.MEDIA) }) {
            if (p == null || p.title.isBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                Glyph(G.MEDIA, V.Tick, Modifier.size(36.dp)); Gap(w = 10.dp)
                VText("NOTHING PLAYING", size = 12.sp, color = V.Muted, spacing = 1.sp)
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    VText(p.title, size = 15.sp, color = V.White, font = FontFamily.Default, maxLines = 1)
                    VText(p.artist, size = 12.sp, color = V.Muted, font = FontFamily.Default, maxLines = 1)
                }
                IconTap(if (p.playing) G.PAUSE else G.PLAY, V.Purple, 44.dp) { Now.playPause() }
            }
        }
    }
    // shopping
    tiles += { m ->
        val left = store.shopping.filter { !it.done }
        FaceTile(m, "List", V.Green, onClick = { onOpen(Module.KITCHEN) }) {
            if (left.isEmpty()) VText("NOTHING TO BUY", size = 12.sp, color = V.Muted, spacing = 1.sp)
            else Column {
                VText("${left.size}", size = 32.sp, color = V.White, font = Jupi)
                VText(left.take(3).joinToString(", ") { it.text }, size = 12.sp, color = V.Muted, font = FontFamily.Default, maxLines = 1)
            }
        }
    }
    // notes
    tiles += { m ->
        val note = store.notes.firstOrNull()
        FaceTile(m, "Notes", V.Yellow, onClick = { onOpen(Module.NOTES) }) {
            VText(note?.text ?: "Tap to write a note.", size = 14.sp, color = if (note == null) V.Muted else V.Text,
                font = FontFamily.Default, maxLines = 2, lineHeight = 19.sp)
        }
    }
    // day + power
    tiles += { m ->
        FaceTile(m, if (bat.charging) "Charging" else "Power", if (bat.charging) V.Green else V.Teal) {
            val mins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    VText(if (bat.pct >= 0) "${bat.pct}%" else "--", size = 30.sp, color = V.White, font = Jupi)
                    Gap(w = 10.dp)
                    VText("DAY ${(mins / 14.4f).toInt()}%", size = 11.sp, color = V.Teal, spacing = 1.sp)
                }
                Gap(h = 6.dp)
                Canvas(Modifier.fillMaxWidth().size(0.dp, 10.dp)) {
                    val n = 24
                    for (i in 0 until n) {
                        val x = size.width * (i + 0.5f) / n
                        drawLine(if (i < cal.get(Calendar.HOUR_OF_DAY)) V.Teal else V.Line, Offset(x, 0f), Offset(x, size.height), 5f, StrokeCap.Round)
                    }
                }
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { tile -> tile(Modifier.weight(1f).fillMaxHeight()) }
            }
        }
        // favourite apps dock
        val favs = remember(store.favs) { store.favs.mapNotNull { pkg -> AppsRepo.find(ctx, pkg) } }
        if (favs.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().clip(Oct).background(V.Panel).border(1.dp, V.Line, Oct).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly) {
                favs.take(6).forEach { app -> AppIcon(app, 46.dp) { launchApp(ctx, store, app.pkg) } }
            }
        }
    }
}

@Composable
private fun FaceTile(
    modifier: Modifier, label: String, accent: androidx.compose.ui.graphics.Color, onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    VPanel(modifier.fillMaxWidth().let { if (onClick != null) it.tap(onClick) else it }, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(10.dp)) { drawOct(Offset(size.width / 2, size.height / 2), size.width / 2, accent, fill = true) }
            Gap(w = 8.dp)
            Label(label)
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.CenterStart) { content() }
    }
}

