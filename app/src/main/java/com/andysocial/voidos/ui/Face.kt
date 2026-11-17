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

/** The octagon clock: sixty second ticks fill in the minute's lap colour. */
@Composable
private fun Dial(store: Store, now: Long, modifier: Modifier) {
    val cal = Calendar.getInstance().apply { timeInMillis = now }
    val sec = cal.get(Calendar.SECOND)
    val minute = cal.get(Calendar.MINUTE)
    val fmt = if (store.use24h) "HH:mm" else "h:mm"
    SquareFit(modifier) { side ->
        Box(Modifier.size(side), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2, size.height / 2)
                val R = size.minDimension / 2 * 0.96f
                val cur = V.Lap[minute % 6]; val prev = V.Lap[(minute + 5) % 6]
                for (i in 0 until 60) {
                    val a = i / 60.0 * 2 * PI - PI / 2
                    val major = i % 5 == 0
                    val r1 = R * (if (major) 0.86f else 0.90f)
                    val col = if (i <= sec) cur else prev.copy(alpha = 0.35f)
                    drawLine(col, Offset(c.x + cos(a).toFloat() * r1, c.y + sin(a).toFloat() * r1),
                        Offset(c.x + cos(a).toFloat() * R, c.y + sin(a).toFloat() * R), R * (if (major) 0.022f else 0.013f), StrokeCap.Round)
                }
                drawOct(c, R * 0.78f, V.Line, width = R * 0.008f)
                drawOct(c, R * 0.74f, V.Panel, fill = true)
                // faint lap colour wash in the dial
                drawOct(c, R * 0.74f, cur.copy(alpha = 0.04f), fill = true)
                val a = sec / 60.0 * 2 * PI - PI / 2
                drawCircle(V.Cyan, R * 0.022f, Offset(c.x + cos(a).toFloat() * R * 0.80f, c.y + sin(a).toFloat() * R * 0.80f))
            }
            val k = side.value / 520f
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                VText(SimpleDateFormat("EEE, MMM d", Locale.UK).format(Date(now)).uppercase(), size = (20 * k).sp, color = V.Text, spacing = (2 * k).sp)
                VText(SimpleDateFormat(fmt, Locale.UK).format(Date(now)), size = (108 * k).sp, color = V.White)
                VText("%02d".format(sec), size = (22 * k).sp, color = V.Lap[minute % 6], font = Jupi)
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

