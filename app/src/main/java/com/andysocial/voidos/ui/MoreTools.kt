package com.andysocial.voidos.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.andysocial.voidos.Store
import com.andysocial.voidos.VoidApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sqrt

// ================================================================ stopwatch

/** The stopwatch keeps running while you're on other screens. */
object Stopwatch {
    val startedAt = mutableLongStateOf(0L)
    val banked = mutableLongStateOf(0L)
    val laps = mutableStateListOf<Long>()
    val running get() = startedAt.longValue != 0L
    fun elapsed(now: Long = System.currentTimeMillis()) = banked.longValue + if (running) now - startedAt.longValue else 0L
    fun start() { if (!running) startedAt.longValue = System.currentTimeMillis() }
    fun stop() { if (running) { banked.longValue = elapsed(); startedAt.longValue = 0L } }
    fun lap() { if (running) laps.add(0, elapsed()) }
    fun reset() { startedAt.longValue = 0L; banked.longValue = 0L; laps.clear() }
}

/** Minutes, seconds and hundredths: 1:02.34, with hours when it gets there. */
internal fun stopwatchText(ms: Long): String {
    val h = ms / 3_600_000; val m = ms / 60_000 % 60; val s = ms / 1000 % 60; val cs = ms / 10 % 100
    return if (h > 0) "%d:%02d:%02d.%02d".format(h, m, s, cs) else "%d:%02d.%02d".format(m, s, cs)
}

@Composable
fun StopwatchPane() {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Stopwatch.running) { while (Stopwatch.running && isActive) { withFrameMillis { now = System.currentTimeMillis() } } }
    val t = Stopwatch.elapsed(now)
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(0.6f))
        VText(stopwatchText(t), size = 84.sp, font = SansLight, color = V.White, spacing = (-1).sp)
        Gap(h = 28.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (Stopwatch.running) {
                VButton("Lap") { Stopwatch.lap() }
                VButton("Stop", filled = true, accent = V.Coral) { Stopwatch.stop() }
            } else {
                if (t > 0) VButton("Reset") { Stopwatch.reset() }
                VButton(if (t > 0) "Resume" else "Start", filled = true, accent = V.Teal) { Stopwatch.start() }
            }
        }
        Gap(h = 20.dp)
        Column(Modifier.weight(1f).widthIn(max = 420.dp).fillMaxWidth().verticalScroll(rememberScrollState())) {
            val laps = Stopwatch.laps
            laps.forEachIndexed { i, total ->
                val split = total - (laps.getOrNull(i + 1) ?: 0L)
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    VText("Lap ${laps.size - i}", size = Type.headline, color = V.Muted, modifier = Modifier.weight(1f))
                    VText(stopwatchText(split), size = Type.headline, color = V.Text)
                }
            }
        }
    }
}

// ================================================================ world clock

/** Common cities and their zones, so you can add one without a connection. */
internal val CITIES: List<Pair<String, String>> = listOf(
    "London" to "Europe/London", "Dublin" to "Europe/Dublin", "Paris" to "Europe/Paris", "Berlin" to "Europe/Berlin",
    "Madrid" to "Europe/Madrid", "Rome" to "Europe/Rome", "Amsterdam" to "Europe/Amsterdam", "Athens" to "Europe/Athens",
    "Istanbul" to "Europe/Istanbul", "Moscow" to "Europe/Moscow", "Dubai" to "Asia/Dubai", "Mumbai" to "Asia/Kolkata",
    "Bangkok" to "Asia/Bangkok", "Singapore" to "Asia/Singapore", "Hong Kong" to "Asia/Hong_Kong", "Shanghai" to "Asia/Shanghai",
    "Seoul" to "Asia/Seoul", "Tokyo" to "Asia/Tokyo", "Sydney" to "Australia/Sydney", "Melbourne" to "Australia/Melbourne",
    "Auckland" to "Pacific/Auckland", "Honolulu" to "Pacific/Honolulu", "Los Angeles" to "America/Los_Angeles",
    "Vancouver" to "America/Vancouver", "Denver" to "America/Denver", "Chicago" to "America/Chicago", "Mexico City" to "America/Mexico_City",
    "Toronto" to "America/Toronto", "New York" to "America/New_York", "Miami" to "America/New_York", "São Paulo" to "America/Sao_Paulo",
    "Buenos Aires" to "America/Argentina/Buenos_Aires", "Lagos" to "Africa/Lagos", "Cairo" to "Africa/Cairo",
    "Johannesburg" to "Africa/Johannesburg", "Nairobi" to "Africa/Nairobi", "Reykjavik" to "Atlantic/Reykjavik",
)

/** "+5 hrs", "−8 hrs", "Same time": how far [zone] is from here right now. */
internal fun zoneDiff(zone: String, nowMs: Long = System.currentTimeMillis()): String {
    val here = java.util.TimeZone.getDefault().getOffset(nowMs)
    val there = runCatching { java.util.TimeZone.getTimeZone(ZoneId.of(zone)).getOffset(nowMs) }.getOrDefault(here)
    val mins = (there - here) / 60_000
    if (mins == 0) return "Same time"
    val h = abs(mins) / 60; val m = abs(mins) % 60
    val sign = if (mins > 0) "+" else "\u2212"
    return sign + (if (m == 0) "$h hr" + (if (h == 1) "" else "s") else "$h:%02d".format(m))
}

@Composable
fun WorldClockPane(store: Store) {
    val now by rememberTicker(1000)
    var adding by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    val fmt = remember(store.use24h) { DateTimeFormatter.ofPattern(if (store.use24h) "HH:mm" else "h:mm a", Locale.UK) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        store.worldClocks.forEachIndexed { i, (name, zone) ->
            val t = runCatching { ZonedDateTime.now(ZoneId.of(zone)) }.getOrNull() ?: return@forEachIndexed
            val day = when (t.toLocalDate().compareTo(java.time.LocalDate.now())) { 0 -> "Today"; in 1..9 -> "Tomorrow"; else -> "Yesterday" }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    VText(name, size = Type.title2, color = V.White, maxLines = 1)
                    VText("$day, ${zoneDiff(zone, now)}", size = Type.body, color = V.Muted, maxLines = 1)
                }
                VText(t.format(fmt).lowercase(Locale.UK).let { if (store.use24h) it else it.replace(" ", "\u2009") }, size = 44.sp, font = SansLight, color = V.White)
                Spacer(Modifier.size(12.dp))
                Box(Modifier.size(36.dp).tap { store.removeWorldClock(i) }, contentAlignment = Alignment.Center) { Glyph(G.CLOSE, V.Muted, Modifier.size(16.dp)) }
            }
        }
        Gap(h = 16.dp)
        if (!adding) VButton("Add city") { adding = true }
        else {
            VField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = "Search a city")
            Gap(h = 8.dp)
            val local = CITIES.filter { q.isBlank() || it.first.contains(q.trim(), true) }.take(6)
            var online by remember { mutableStateOf<List<WeatherRepo.Place>>(emptyList()) }
            LaunchedEffect(q) { online = if (q.trim().length < 3) emptyList() else { delay(300); WeatherRepo.search(q.trim(), 6).filter { it.tz != null } } }
            (local.map { Triple(it.first, "", it.second) } + online.filter { o -> local.none { it.first == o.name } }.map { Triple(it.name, it.where, it.tz!!) })
                .forEach { (name, where, zone) ->
                    Row(
                        Modifier.fillMaxWidth().tap { store.addWorldClock(name, zone); adding = false; q = "" }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Glyph(G.WORLD, V.Muted, Modifier.size(18.dp)); Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            VText(name, size = Type.headline, color = V.Text)
                            if (where.isNotBlank()) VText(where, size = Type.label, color = V.Muted, maxLines = 1)
                        }
                        VText(zoneDiff(zone, now), size = Type.body, color = V.Muted)
                    }
                }
        }
    }
}

// ================================================================ spirit level

/** A bubble level from the accelerometer: lay the tablet flat or stand it on an edge. */
@Composable
fun LevelPane() {
    val ctx = LocalContext.current
    var x by remember { mutableFloatStateOf(0f) }
    var y by remember { mutableFloatStateOf(0f) }
    var z by remember { mutableFloatStateOf(9.8f) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val s = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { x += (e.values[0] - x) * 0.2f; y += (e.values[1] - y) * 0.2f; z += (e.values[2] - z) * 0.2f }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (s != null) sm.registerListener(l, s, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm?.unregisterListener(l) }
    }
    val flat = abs(z) > 7f
    val pitch = Math.toDegrees(atan2(y.toDouble(), z.toDouble())).toFloat()
    val roll = Math.toDegrees(atan2(x.toDouble(), z.toDouble())).toFloat()
    val edge = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
    val angle = if (flat) maxOf(abs(pitch), abs(roll)) else abs(((edge + 45 + 360) % 90) - 45)
    val level = angle < 0.6f
    val dial: @Composable (Dp) -> Unit = { side -> Box(Modifier.size(side)) {
            Canvas(Modifier.fillMaxSize()) {
                val c = center; val r = size.minDimension / 2
                drawCircle(Color.White.copy(alpha = 0.10f), r, c, style = Stroke(2.dp.toPx()))
                drawCircle(Color.White.copy(alpha = 0.18f), r * 0.18f, c, style = Stroke(2.dp.toPx()))
                val bx = if (flat) (-roll / 20f).coerceIn(-1f, 1f) else 0f
                val by = if (flat) (pitch / 20f).coerceIn(-1f, 1f) else 0f
                drawCircle(if (level) V.Teal else V.Purple, r * 0.15f, c + Offset(bx * r * 0.8f, by * r * 0.8f))
            }
        } }
    val reading: @Composable () -> Unit = { VText("%.1f°".format(angle), size = 56.sp, font = SansLight, color = if (level) V.Teal else V.White, maxLines = 1) }
    // the 300 dp dial plus the reading needs about 410 dp down; in half the book there's less,
    // so the dial shrinks to fit, or sits beside the reading when the space is wide and short
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val stacked = minOf(300.dp, maxWidth, maxHeight - 110.dp)
        val besideSide = minOf(300.dp, maxHeight - 16.dp, maxWidth - 200.dp)
        if (stacked >= 200.dp || maxWidth < 360.dp) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            dial(stacked.coerceAtLeast(120.dp)); Gap(h = 24.dp); reading()
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            dial(besideSide); Gap(w = 28.dp); reading()
        }
    }
}

// ================================================================ sound meter

/** How loud the room is, in rough decibels from the microphone. */
@Composable
fun SoundMeterPane() {
    val ctx = LocalContext.current
    var allowed by remember { mutableStateOf(micAllowed(ctx)) }
    var db by remember { mutableFloatStateOf(0f) }
    var peak by remember { mutableFloatStateOf(0f) }
    if (!allowed) { MicAsk { allowed = micAllowed(ctx) }; return }
    DisposableEffect(Unit) {
        val rec = runCatching {
            @Suppress("DEPRECATION")
            MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(File(ctx.cacheDir, "meter.3gp").path)
                prepare(); start()
            }
        }.getOrNull()
        val t = Thread {
            while (!Thread.currentThread().isInterrupted) {
                val a = runCatching { rec?.maxAmplitude ?: 0 }.getOrDefault(0)
                if (a > 0) { val v = (20 * log10(a.toDouble()) + 4).toFloat().coerceIn(20f, 110f); db += (v - db) * 0.4f; if (db > peak) peak = db }
                try { Thread.sleep(100) } catch (_: InterruptedException) { return@Thread }
            }
        }.apply { isDaemon = true; start() }
        onDispose { t.interrupt(); runCatching { rec?.stop() }; rec?.release() }
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = 14.dp.toPx()
                drawArc(Color.White.copy(alpha = 0.08f), 150f, 240f, false, style = Stroke(sw, cap = StrokeCap.Round))
                val f = ((db - 20f) / 90f).coerceIn(0f, 1f)
                drawArc(if (db > 85) V.Coral else V.Teal, 150f, 240f * f, false, style = Stroke(sw, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                VText("%.0f".format(db), size = 72.sp, font = SansLight, color = V.White)
                VText("dB", size = Type.headline, color = V.Muted)
            }
        }
        VText("Peak %.0f dB".format(peak), size = Type.body, color = V.Muted)
    }
}

private fun micAllowed(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** VoidOS grants itself the microphone as device owner; if that fails, it says so. */
@Composable
private fun MicAsk(onDone: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.andysocial.voidos.Policy.grantSelf(ctx, Manifest.permission.RECORD_AUDIO) }
        onDone()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { VText("Needs the microphone", size = Type.headline, color = V.Muted) }
}

// ================================================================ voice memos

@Composable
fun VoiceMemoPane() {
    val ctx = LocalContext.current
    var allowed by remember { mutableStateOf(micAllowed(ctx)) }
    if (!allowed) { MicAsk { allowed = micAllowed(ctx) }; return }
    val dir = remember { File(ctx.filesDir, "memos").apply { mkdirs() } }
    var files by remember { mutableStateOf(dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()) }
    var rec by remember { mutableStateOf<MediaRecorder?>(null) }
    var started by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf<File?>(null) }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    val now by rememberTicker(500)
    DisposableEffect(Unit) { onDispose { runCatching { rec?.stop() }; rec?.release(); player[0]?.release() } }
    fun stopRec() {
        runCatching { rec?.stop() }; rec?.release(); rec = null
        files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Gap(h = 12.dp)
        Box(
            Modifier.size(96.dp).droplet(androidx.compose.foundation.shape.CircleShape, if (rec != null) V.Coral else V.Purple, 1.4f).tap {
                if (rec != null) stopRec() else {
                    val f = File(dir, "Memo " + java.text.SimpleDateFormat("d MMM HH.mm.ss", Locale.UK).format(java.util.Date()) + ".m4a")
                    rec = runCatching {
                        @Suppress("DEPRECATION")
                        MediaRecorder().apply {
                            setAudioSource(MediaRecorder.AudioSource.MIC); setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                            setAudioEncoder(MediaRecorder.AudioEncoder.AAC); setAudioSamplingRate(44100); setAudioEncodingBitRate(96000)
                            setOutputFile(f.path); prepare(); start()
                        }
                    }.getOrNull()
                    started = System.currentTimeMillis()
                }
            },
            contentAlignment = Alignment.Center,
        ) { Glyph(if (rec != null) G.RECORD else G.MIC, Color.White, Modifier.size(36.dp)) }
        Gap(h = 12.dp)
        VText(if (rec != null) stopwatchText(now - started).substringBefore('.') else "Tap to record", size = Type.title, color = if (rec != null) V.White else V.Muted)
        Gap(h = 20.dp)
        Column(Modifier.weight(1f).widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState())) {
            files.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp).glass(androidx.compose.foundation.shape.CircleShape, 0.8f).tap {
                            player[0]?.release(); player[0] = null
                            if (playing == f) playing = null else {
                                player[0] = runCatching { MediaPlayer().apply { setDataSource(f.path); prepare(); setOnCompletionListener { playing = null }; start() } }.getOrNull()
                                playing = f
                            }
                        },
                        contentAlignment = Alignment.Center,
                    ) { Glyph(if (playing == f) G.PAUSE else G.PLAY, V.Text, Modifier.size(18.dp)) }
                    Spacer(Modifier.size(14.dp))
                    VText(f.nameWithoutExtension, size = Type.headline, color = V.Text, modifier = Modifier.weight(1f), maxLines = 1)
                    Box(Modifier.size(40.dp).tap { if (playing == f) { player[0]?.release(); player[0] = null; playing = null }; f.delete(); files = files - f }, contentAlignment = Alignment.Center) {
                        Glyph(G.TRASH, V.Muted, Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

// ================================================================ calendar

/** A month at a glance; swipe the arrows for other months. Today is a teal drop. */
@Composable
fun CalendarPane(store: Store) {
    var offset by remember { mutableStateOf(0) }
    val today = java.time.LocalDate.now()
    val month = today.withDayOfMonth(1).plusMonths(offset.toLong())
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).tap { offset-- }, contentAlignment = Alignment.Center) { Glyph(G.BACK, V.Text, Modifier.size(20.dp)) }
            VText(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK)), size = Type.title2, color = V.White, align = TextAlign.Center, modifier = Modifier.weight(1f).tap { offset = 0 })
            Box(Modifier.size(44.dp).tap { offset++ }, contentAlignment = Alignment.Center) { Glyph(G.NEXT, V.Text, Modifier.size(20.dp)) }
        }
        Gap(h = 4.dp)
        NextEventLine(store)
        Gap(h = 8.dp)
        BoxWithConstraints(Modifier.widthIn(max = 520.dp).fillMaxWidth().weight(1f)) {
            val cell = min(maxWidth.value / 7f, maxHeight.value / 6.6f).dp
            Column {
                // letters and numbers scale with the cell, so a short half of the book doesn't cut them
                val dayText = minOf(Type.title.value, cell.value * 0.42f).sp
                val headText = minOf(Type.label.value, cell.value * 0.34f).sp
                Row { listOf("M", "T", "W", "T", "F", "S", "S").forEach { VText(it, size = headText, color = V.Muted, align = TextAlign.Center, modifier = Modifier.size(cell, cell * 0.6f).wrapContentHeight()) } }
                val lead = month.dayOfWeek.value - 1
                val days = month.lengthOfMonth()
                for (w in 0 until (lead + days + 6) / 7) {
                    Row {
                        for (d in 0 until 7) {
                            val n = w * 7 + d - lead + 1
                            Box(Modifier.size(cell), contentAlignment = Alignment.Center) {
                                if (n in 1..days) {
                                    val isToday = month.withDayOfMonth(n) == today
                                    if (isToday) Box(Modifier.size(cell * 0.8f).droplet(androidx.compose.foundation.shape.CircleShape, V.Teal, 1f))
                                    VText("$n", size = dayText, color = if (isToday) Color.White else if (d >= 5) V.Muted else V.Text)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Tools tabs beyond the first four, for [ToolsScreen]. */
@Composable
internal fun MoreToolsPane(which: KTab, store: Store = VoidApp.instance.store) {
    when (which) {
        KTab.STOPWATCH -> StopwatchPane()
        KTab.WORLD -> WorldClockPane(store)
        KTab.LEVEL -> LevelPane()
        KTab.METER -> SoundMeterPane()
        KTab.MEMO -> VoiceMemoPane()
        KTab.CALENDAR -> CalendarPane(store)
        else -> {}
    }
}

