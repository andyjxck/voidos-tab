package com.andysocial.voidos.ui

import com.andysocial.voidos.Now
import com.andysocial.voidos.KTimers
import androidx.compose.animation.animateColorAsState
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andysocial.voidos.MainActivity
import com.andysocial.voidos.Store
import com.andysocial.voidos.VoidApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Idle {
    @Volatile var last = System.currentTimeMillis()
    val ambient = mutableStateOf(false)
    val homePressed = mutableIntStateOf(0)
    fun touch() {
        last = System.currentTimeMillis()
        if (ambient.value) ambient.value = false
    }
}

enum class Module(val label: String, val glyph: G, val accent: Color) {
    FACE("Face", G.FACE, V.Cyan),
    KITCHEN("Kitchen", G.KITCHEN, V.Orange),
    WEATHER("Sky", G.WEATHER, V.Sun),
    NOTES("Notes", G.NOTES, V.Yellow),
    MEDIA("Sound", G.MEDIA, V.Pink),
    APPS("Apps", G.APPS, V.Purple),
    SYSTEM("Settings", G.SYSTEM, V.Teal),
}

@Composable
fun VoidRoot(store: Store, act: MainActivity) {
    var booting by remember { mutableStateOf(!VoidApp.booted) }
    val locked by LockState.locked
    BoxWithConstraints(Modifier.fillMaxSize().background(V.Bg)) {
        val portrait = maxHeight > maxWidth
        CompositionLocalProvider(LocalPortrait provides portrait) {
            when {
                booting -> BootAnim { VoidApp.booted = true; booting = false }
                !store.setupDone -> Setup(store, act)
                else -> {
                    Shell(store, act)
                    if (locked && store.voidLock) LockScreen(store) { LockState.locked.value = false; Idle.touch() }
                }
            }
            if (!booting) TimerAlert()
        }
    }
}

@Composable
fun Shell(store: Store, act: MainActivity, start: Module = Module.FACE) {
    var module by rememberSaveable { mutableStateOf(start) }
    var escape by remember { mutableStateOf(false) }
    val ambient by Idle.ambient
    val home by Idle.homePressed
    val portrait = LocalPortrait.current

    LaunchedEffect(home) { if (home > 0) { module = Module.FACE; escape = false } }

    // ambient clock after N idle minutes
    LaunchedEffect(store.ambientMinutes) {
        while (isActive) {
            delay(5_000)
            val m = store.ambientMinutes
            if (m > 0 && !Idle.ambient.value && System.currentTimeMillis() - Idle.last > m * 60_000L) {
                Idle.ambient.value = true
            }
        }
    }

    BackHandler(enabled = true) { if (escape) escape = false else module = Module.FACE }

    val content: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            StatusStrip(store, module)
            Gap(h = 10.dp)
            AnimatedContent(
                targetState = module,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "module",
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { mod ->
                when (mod) {
                    Module.FACE -> FaceScreen(store, onOpen = { module = it })
                    Module.KITCHEN -> KitchenScreen(store)
                    Module.WEATHER -> WeatherScreen(store)
                    Module.NOTES -> NotesScreen(store)
                    Module.MEDIA -> MediaScreen()
                    Module.APPS -> AppsScreen(store)
                    Module.SYSTEM -> SystemScreen(store, act)
                }
            }
        }
    }

    if (portrait) {
        Column(Modifier.fillMaxSize()) {
            content(Modifier.weight(1f).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp))
            BottomRail(store, module, onSelect = { module = it }, onEscape = { escape = true })
        }
    } else {
        Row(Modifier.fillMaxSize()) {
            Rail(store, module, onSelect = { module = it }, onEscape = { escape = true })
            content(Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp, end = 24.dp, top = 14.dp, bottom = 18.dp))
        }
    }

    if (ambient) AmbientClock(store)
    if (escape) EscapeOverlay(store, act, onClose = { escape = false })
}

/** The VoidOS mark with the hidden three-second hold that opens the escape PIN. */
@Composable
private fun EscapeMark(size: androidx.compose.ui.unit.Dp, onEscape: () -> Unit) {
    var holding by remember { mutableStateOf(false) }
    val now = rememberNow(if (holding) 50 else 60_000)
    var since by remember { mutableStateOf(0L) }
    Box(
        Modifier.size(size).pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                val start = System.currentTimeMillis()
                since = start; holding = true
                val up = withTimeoutOrNull(3_000) { waitForUpOrCancellation() }
                holding = false
                if (up == null && System.currentTimeMillis() - start >= 2_900) onEscape()
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        // the mark wipes in while you hold, so you can tell the hold is working
        val p = if (holding) ((now - since) / 3000f).coerceIn(0f, 1f) else 1f
        VoidMark(Modifier.size(size * 0.72f), progress = p)
    }
}

/**
 * The rail, in the logo's language: the six-colour bar runs down its edge,
 * the selected screen gets the logo's teal-style pill in its own colour, and
 * nothing is boxed in.
 */
@Composable
private fun LapEdge(modifier: Modifier, vertical: Boolean) {
    Canvas(modifier) {
        val n = V.Lap.size
        val gap = 6.dp.toPx()
        val len = (if (vertical) size.height else size.width)
        val seg = (len - gap * (n - 1)) / n
        V.Lap.forEachIndexed { i, c ->
            val a = i * (seg + gap)
            if (vertical) drawLine(c.copy(alpha = 0.55f), Offset(size.width / 2, a), Offset(size.width / 2, a + seg), size.width, StrokeCap.Round)
            else drawLine(c.copy(alpha = 0.55f), Offset(a, size.height / 2), Offset(a + seg, size.height / 2), size.height, StrokeCap.Round)
        }
    }
}

/** Landscape: the left rail. */
@Composable
fun Rail(store: Store, current: Module, onSelect: (Module) -> Unit, onEscape: () -> Unit) {
    Row(Modifier.width(96.dp).fillMaxHeight().background(V.Deep)) {
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EscapeMark(60.dp, onEscape)
            BoxWithConstraints(Modifier.weight(1f)) {
                // on a short screen (bigger display size) the labels go, so no item is squashed
                val labels = store.railLabels && maxHeight / Module.entries.size >= 54.dp
                Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                    Module.entries.forEach { m -> RailItem(m, m == current, labels, Modifier.fillMaxWidth(), vertical = true) { onSelect(m) } }
                }
            }
        }
        LapEdge(Modifier.width(3.dp).fillMaxHeight().padding(vertical = 18.dp), vertical = true)
    }
}

/** Portrait: the same rail laid along the bottom edge. */
@Composable
fun BottomRail(store: Store, current: Module, onSelect: (Module) -> Unit, onEscape: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(V.Deep)) {
        LapEdge(Modifier.fillMaxWidth().height(3.dp).padding(horizontal = 18.dp), vertical = false)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EscapeMark(52.dp, onEscape)
            BoxWithConstraints(Modifier.weight(1f)) {
                // narrow screens get a tighter label so KITCHEN still fits
                val tight = maxWidth / Module.entries.size < 68.dp
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Module.entries.forEach { m -> RailItem(m, m == current, store.railLabels, Modifier.weight(1f), tight, vertical = false) { onSelect(m) } }
                }
            }
        }
    }
}

@Composable
private fun RailItem(m: Module, sel: Boolean, labels: Boolean, modifier: Modifier, tight: Boolean = false, vertical: Boolean, onClick: () -> Unit) {
    // the selection fades in rather than jumping
    val tint by animateColorAsState(if (sel) m.accent else V.Muted.copy(alpha = 0.8f), tween(220), label = "railTint")
    val pill by animateColorAsState(if (sel) m.accent else Color.Transparent, tween(220), label = "railPill")
    val glow by animateColorAsState(if (sel) m.accent.copy(alpha = 0.10f) else Color.Transparent, tween(220), label = "railGlow")
    val badge = railBadge(m)
    Box(modifier.clickable(onClick = onClick)) {
        // the pill: on the outer edge in landscape, on top in portrait
        Canvas(Modifier.matchParentSize()) {
            val w = 4.dp.toPx()
            if (vertical) {
                val h = size.height * 0.5f
                drawLine(pill, Offset(w / 2, (size.height - h) / 2), Offset(w / 2, (size.height + h) / 2), w, StrokeCap.Round)
            } else {
                val l = size.width * 0.4f
                drawLine(pill, Offset((size.width - l) / 2, w / 2), Offset((size.width + l) / 2, w / 2), w, StrokeCap.Round)
            }
            drawCircle(glow, size.minDimension * 0.42f, Offset(size.width / 2, size.height / 2 - if (labels) 6.dp.toPx() else 0f))
        }
        Column(
            Modifier.fillMaxWidth().padding(vertical = if (labels) 7.dp else 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Glyph(m.glyph, tint, Modifier.size(if (sel) 27.dp else 24.dp))
            if (labels) {
                Gap(h = 5.dp)
                VText(m.label.uppercase(), size = if (tight) 7.sp else 8.sp, color = tint, spacing = if (tight) 0.2.sp else 1.sp, maxLines = 1)
            }
        }
        // a live badge: running timers on Kitchen (red while one rings), a dot on Sound while something plays
        if (badge != null) Box(
            Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 10.dp)
                .size(if (badge.first.isEmpty()) 8.dp else 16.dp).clip(RoundedCornerShape(50)).background(badge.second),
            contentAlignment = Alignment.Center,
        ) { if (badge.first.isNotEmpty()) VText(badge.first, size = 9.sp, color = V.Bg, maxLines = 1) }
    }
}

/** What a rail item's badge shows, or null: (text, colour); empty text draws a dot. */
@Composable
private fun railBadge(m: Module): Pair<String, Color>? = when (m) {
    Module.KITCHEN -> {
        val timers by KTimers.list
        val ringing by KTimers.ringing
        val running = timers.count { it.running }
        when {
            ringing.isNotEmpty() -> "!" to V.Coral
            running > 0 -> running.toString() to m.accent
            else -> null
        }
    }
    Module.MEDIA -> {
        val local by Player.playing
        val other by Now.playing
        if (local || other?.playing == true) "" to m.accent else null
    }
    else -> null
}

// --------------------------------------------------------------- status strip

data class Battery(val pct: Int, val charging: Boolean)

@Composable
fun rememberBattery(): Battery {
    val ctx = LocalContext.current
    var b by remember { mutableStateOf(Battery(-1, false)) }
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                b = Battery(if (lvl >= 0) lvl * 100 / sc else -1, st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL)
            }
        }
        ctx.registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { runCatching { ctx.unregisterReceiver(r) } }
    }
    return b
}

@Composable
fun rememberNow(periodMs: Long = 1000): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (isActive) {
            now = System.currentTimeMillis()
            delay(periodMs - (now % periodMs))
        }
    }
    return now
}

@Composable
fun StatusStrip(store: Store, module: Module) {
    val ctx = LocalContext.current
    val now = rememberNow(10_000)
    val bat = rememberBattery()
    val wifiOn = remember(now) {
        runCatching {
            val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            wm.isWifiEnabled && wm.connectionInfo?.networkId != -1
        }.getOrDefault(false)
    }
    Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        VText(module.label.uppercase(), size = 13.sp, color = module.accent, spacing = 3.sp)
        Spacer(Modifier.weight(1f))
        if (module != Module.FACE) {
            val fmt = if (store.use24h) "HH:mm" else "h:mm"
            VText(SimpleDateFormat(fmt, Locale.UK).format(Date(now)), size = 13.sp, color = V.Text, spacing = 1.sp)
            Gap(w = 18.dp)
        }
        Glyph(G.WIFI, if (wifiOn) V.Teal else V.Tick, Modifier.size(18.dp))
        Gap(w = 16.dp)
        BatteryGlyph(bat)
    }
}

@Composable
fun BatteryGlyph(b: Battery) {
    val col = when {
        b.charging -> V.Green
        b.pct in 0..15 -> V.Coral
        else -> V.Text
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(30.dp, 15.dp)) {
            drawRect(col, Offset(0f, 0f), Size(size.width - 4f, size.height), style = Stroke(2f))
            drawRect(col, Offset(size.width - 3f, size.height * 0.3f), Size(3f, size.height * 0.4f))
            val f = (b.pct.coerceIn(0, 100) / 100f)
            drawRect(col, Offset(3f, 3f), Size((size.width - 10f) * f, size.height - 6f))
        }
        Gap(w = 6.dp)
        VText(if (b.pct >= 0) "${b.pct}%" else "--", size = 12.sp, color = col)
    }
}

// -------------------------------------------------------------- ambient clock

/** Dim desk clock. It drifts a little every minute so nothing burns in. */
@Composable
fun AmbientClock(store: Store) {
    val now = rememberNow(1000)
    val minute = now / 60_000
    val dx = ((minute * 37) % 120 - 60).toInt()
    val dy = ((minute * 53) % 90 - 45).toInt()
    val fmt = if (store.use24h) "HH:mm" else "h:mm"
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(Modifier.offset((dx * 0.6f).dp, (dy * 0.8f).dp), horizontalAlignment = Alignment.CenterHorizontally) {
            VText(SimpleDateFormat(fmt, Locale.UK).format(Date(now)), size = if (LocalPortrait.current) 96.sp else 120.sp, color = V.Text.copy(alpha = 0.55f), font = Kenney)
            Gap(h = 6.dp)
            VText(SimpleDateFormat("EEE d MMM", Locale.UK).format(Date(now)).uppercase(), size = 18.sp, color = V.Muted.copy(alpha = 0.6f), spacing = 3.sp)
            Gap(h = 18.dp)
            Canvas(Modifier.size(260.dp, 6.dp)) {
                val sec = (now / 1000 % 60).toInt()
                for (i in 0 until 60) {
                    val x = size.width * i / 60f
                    val col = if (i <= sec) V.Lap[((minute) % 6).toInt()].copy(alpha = 0.5f) else V.Line.copy(alpha = 0.5f)
                    drawLine(col, Offset(x, 0f), Offset(x, size.height), 2f)
                }
            }
        }
    }
}
