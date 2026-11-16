package com.andysocial.voidos.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
    val now = rememberNow(1000)
    val bat = rememberBattery()
    val wx by WeatherRepo.state
    LaunchedEffect(Unit) { WeatherRepo.refresh(store) }

    val cal = Calendar.getInstance().apply { timeInMillis = now }
    val sec = cal.get(Calendar.SECOND)
    val minute = cal.get(Calendar.MINUTE)
    val fmt = if (store.use24h) "HH:mm" else "h:mm"

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(Modifier.weight(1.35f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxHeight().aspectRatio(1f)) {
                val c = Offset(size.width / 2, size.height / 2)
                val R = size.minDimension / 2 * 0.96f
                val cur = V.Lap[minute % 6]; val prev = V.Lap[(minute + 5) % 6]
                for (i in 0 until 60) {
                    val a = i / 60.0 * 2 * PI - PI / 2
                    val major = i % 5 == 0
                    val r1 = R * (if (major) 0.86f else 0.90f)
                    val col = if (i <= sec) cur else prev.copy(alpha = 0.35f)
                    drawLine(col, Offset(c.x + cos(a).toFloat() * r1, c.y + sin(a).toFloat() * r1),
                        Offset(c.x + cos(a).toFloat() * R, c.y + sin(a).toFloat() * R), if (major) 7f else 4f, StrokeCap.Round)
                }
                drawOct(c, R * 0.78f, V.Line, width = 2.5f)
                drawOct(c, R * 0.74f, V.Panel, fill = true)
                // seconds marker riding the octagon
                val a = sec / 60.0 * 2 * PI - PI / 2
                drawCircle(V.Cyan, 7f, Offset(c.x + cos(a).toFloat() * R * 0.80f, c.y + sin(a).toFloat() * R * 0.80f))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                VText(SimpleDateFormat("EEE, MMM d", Locale.UK).format(Date(now)).uppercase(), size = 20.sp, color = V.Text, spacing = 2.sp)
                VText(SimpleDateFormat(fmt, Locale.UK).format(Date(now)), size = 108.sp, color = V.White)
                VText("%02d".format(sec), size = 22.sp, color = V.Lap[minute % 6], font = Jupi)
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // sky
            val w = wx
            FaceTile(Modifier.weight(1f), "Sky / ${store.city}", w?.let { wxColour(it.code, it.isDay) } ?: V.Muted, onClick = { onOpen(Module.WEATHER) }) {
                if (w == null) VText("--", size = 34.sp, color = V.Muted)
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    Glyph(wxGlyph(w.code, w.isDay), wxColour(w.code, w.isDay), Modifier.size(54.dp), 2.6f)
                    Gap(w = 16.dp)
                    Column {
                        VText(t(w.temp), size = 40.sp, color = V.White, font = Jupi)
                        VText("${wxWords(w.code).uppercase()}  /  ${t(w.days.firstOrNull()?.max ?: w.temp)} ${t(w.days.firstOrNull()?.min ?: w.temp)}", size = 12.sp, color = V.Muted, maxLines = 1)
                    }
                }
            }
            // day progress, the watch's stats ring idea
            FaceTile(Modifier.weight(1f), "Day", V.Teal) {
                val mins = cal.get(Calendar.HOUR_OF_DAY) * 60 + minute
                val pct = mins / 1440f
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VText("${(pct * 100).toInt()}%", size = 40.sp, color = V.Teal, font = Jupi)
                    Gap(w = 16.dp)
                    Canvas(Modifier.weight(1f).size(0.dp, 22.dp)) {
                        val n = 24
                        for (i in 0 until n) {
                            val x = size.width * (i + 0.5f) / n
                            val on = i < cal.get(Calendar.HOUR_OF_DAY)
                            drawLine(if (on) V.Teal else V.Line, Offset(x, 0f), Offset(x, size.height), 6f, StrokeCap.Round)
                        }
                    }
                }
            }
            // latest note
            val note = store.notes.firstOrNull()
            FaceTile(Modifier.weight(1f), "Notes", V.Yellow, onClick = { onOpen(Module.NOTES) }) {
                VText(note?.text ?: "Nothing written yet. Tap to start a note.", size = 15.sp, color = if (note == null) V.Muted else V.Text,
                    font = FontFamily.Default, maxLines = 3, lineHeight = 20.sp)
            }
            // power
            FaceTile(Modifier.weight(0.8f), "Power", if (bat.charging) V.Green else V.Text) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VText(if (bat.pct >= 0) "${bat.pct}%" else "--", size = 30.sp, color = V.White, font = Jupi)
                    Gap(w = 14.dp)
                    VText(
                        when {
                            bat.charging && store.deskMode -> "CHARGING / DESK MODE, SCREEN STAYS ON"
                            bat.charging -> "CHARGING"
                            else -> "ON BATTERY"
                        }, size = 11.sp, color = V.Muted, spacing = 1.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun FaceTile(
    modifier: Modifier, label: String, accent: androidx.compose.ui.graphics.Color, onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    VPanel(modifier.fillMaxWidth().let { if (onClick != null) it.tap(onClick) else it }, padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(10.dp)) { drawOct(Offset(size.width / 2, size.height / 2), size.width / 2, accent, fill = true) }
            Gap(w = 8.dp)
            Label(label)
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.CenterStart) { content() }
    }
}

