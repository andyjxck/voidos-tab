package com.andysocial.voidos.ui

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
import androidx.compose.runtime.Composable
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
    WEATHER("Sky", G.WEATHER, V.Sun),
    NOTES("Notes", G.NOTES, V.Yellow),
    MEDIA("Sound", G.MEDIA, V.Pink),
    FOCUS("Focus", G.FOCUS, V.Coral),
    HOUSE("House", G.HOUSE, V.Green),
    APPS("Apps", G.APPS, V.Purple),
    SYSTEM("System", G.SYSTEM, V.Teal),
}

@Composable
fun VoidRoot(store: Store, act: MainActivity) {
    var booting by remember { mutableStateOf(!VoidApp.booted) }
    Box(Modifier.fillMaxSize().background(V.Bg)) {
        when {
            booting -> BootAnim { VoidApp.booted = true; booting = false }
            !store.setupDone -> Setup(store, act)
            else -> Shell(store, act)
        }
    }
}

@Composable
fun Shell(store: Store, act: MainActivity) {
    var module by rememberSaveable { mutableStateOf(Module.FACE) }
    var escape by remember { mutableStateOf(false) }
    val ambient by Idle.ambient
    val home by Idle.homePressed

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

    Row(Modifier.fillMaxSize()) {
        Rail(module, onSelect = { module = it }, onEscape = { escape = true })
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp, end = 24.dp, top = 14.dp, bottom = 18.dp)) {
            StatusStrip(store, module)
            Gap(h = 10.dp)
            AnimatedContent(
                targetState = module,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "module",
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { m ->
                when (m) {
                    Module.FACE -> FaceScreen(store, onOpen = { module = it })
                    Module.WEATHER -> WeatherScreen(store)
                    Module.NOTES -> NotesScreen(store)
                    Module.MEDIA -> MediaScreen()
                    Module.FOCUS -> FocusScreen()
                    Module.HOUSE -> HouseScreen()
                    Module.APPS -> AppsScreen(store)
                    Module.SYSTEM -> SystemScreen(store, act)
                }
            }
        }
    }

    if (ambient) AmbientClock(store)
    if (escape) EscapeOverlay(store, act, onClose = { escape = false })
}

/** Left rail. Holding the VoidOS mark for three seconds opens the escape PIN. */
@Composable
fun Rail(current: Module, onSelect: (Module) -> Unit, onEscape: () -> Unit) {
    Column(
        Modifier.width(104.dp).fillMaxHeight().background(V.Deep).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // the mark + hidden 3-second hold
        Box(
            Modifier.size(64.dp).pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val start = System.currentTimeMillis()
                    val up = withTimeoutOrNull(3_000) { waitForUpOrCancellation() }
                    if (up == null && System.currentTimeMillis() - start >= 2_900) onEscape()
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(46.dp)) {
                val c = Offset(size.width / 2, size.height / 2)
                drawOct(c, size.width / 2, V.Line, width = 2.5f)
                drawArc(V.Coral, -60f, 300f, false, Offset(size.width * 0.26f, size.height * 0.26f), Size(size.width * 0.48f, size.height * 0.48f), style = Stroke(5f, cap = StrokeCap.Round))
                drawCircle(V.White, 3.5f, c)
            }
        }
        Gap(h = 14.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
            Module.entries.forEach { m ->
                val sel = m == current
                Column(
                    Modifier
                        .width(84.dp)
                        .clip(OctS)
                        .background(if (sel) m.accent.copy(alpha = 0.14f) else Color.Transparent)
                        .border(1.dp, if (sel) m.accent else Color.Transparent, OctS)
                        .clickable { onSelect(m) }
                        .padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Glyph(m.glyph, if (sel) m.accent else V.Muted, Modifier.size(26.dp))
                    Gap(h = 4.dp)
                    VText(m.label.uppercase(), size = 9.sp, color = if (sel) m.accent else V.Muted, spacing = 1.sp)
                }
            }
        }
    }
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
        Column(Modifier.padding(start = (120 + dx).dp, top = (90 + dy).dp).align(Alignment.TopStart), horizontalAlignment = Alignment.CenterHorizontally) {
            VText(SimpleDateFormat(fmt, Locale.UK).format(Date(now)), size = 120.sp, color = V.Text.copy(alpha = 0.55f), font = Kenney)
            Gap(h = 6.dp)
            VText(SimpleDateFormat("EEE d MMM", Locale.UK).format(Date(now)).uppercase(), size = 18.sp, color = V.Muted.copy(alpha = 0.6f), spacing = 3.sp)
            Gap(h = 18.dp)
            Canvas(Modifier.size(300.dp, 6.dp)) {
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
