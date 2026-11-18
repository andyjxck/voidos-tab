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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.CornerRadius
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.graphics.Brush
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
    /** A screen picked on the wheel over another app: the shell opens it as VoidOS comes back. */
    val openModule = mutableStateOf<String?>(null)
    fun touch() {
        last = System.currentTimeMillis()
        if (ambient.value) ambient.value = false
    }
}

/** The screen the shell is on, for anything outside it that needs to know (and the tests). */
object ShellNow {
    val module = mutableStateOf(Module.FACE)
}

enum class Module(val label: String, val glyph: G, val accent: Color) {
    FACE("Home", G.FACE, V.Teal),
    TOOLS("Tools", G.TIMER, V.Teal),
    WEATHER("Weather", G.WEATHER, V.Teal),
    NOTES("Notes", G.NOTES, V.Teal),
    MEDIA("Music", G.MEDIA, V.Teal),
    APPS("Apps", G.APPS, V.Teal),
    SYSTEM("Settings", G.SYSTEM, V.Teal),
}

@Composable
fun VoidRoot(store: Store, act: MainActivity) {
    var booting by remember { mutableStateOf(!VoidApp.booted) }
    val locked by LockState.locked
    BoxWithConstraints(Modifier.fillMaxSize().background(V.Bg)) {
        val portrait = maxHeight > maxWidth
        Backdrop()
        CompositionLocalProvider(LocalPortrait provides portrait) {
            // the hub is drawn under the start animation, so the animation
            // opens out into it instead of cutting to it (ui/Boot.kt)
            when {
                !store.setupDone -> Setup(store, act)
                else -> {
                    Shell(store, act)
                    if (locked && store.voidLock) LockScreen(store) { LockState.locked.value = false; Idle.touch() }
                }
            }
            if (booting) BootAnim { VoidApp.booted = true; booting = false }
            else { TimerAlert(); AlarmOverlay() }
        }
    }
}

/**
 * The home shell. No sidebar: the screen is all content. Swipe sideways to move
 * between screens, pull the left edge for the tab wheel, the right edge for the
 * app wheel, and pull down from the top for the control centre.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Shell(store: Store, act: MainActivity, start: Module = Module.FACE) {
    val pages = Module.entries
    val pager = rememberPagerState(initialPage = pages.indexOf(start)) { pages.size }
    val scope = rememberCoroutineScope()
    val module = pages[pager.currentPage]
    SideEffect { ShellNow.module.value = module }
    // the backdrop's light follows the swipe, colour by colour
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage + pager.currentPageOffsetFraction }.collect { Ambience.pos.floatValue = it }
    }
    var escape by EscapeDoor.asked
    var control by remember { mutableStateOf(false) }
    val ambient by Idle.ambient
    val home by Idle.homePressed
    val portrait = LocalPortrait.current
    val ctx = LocalContext.current

    fun go(m: Module, animate: Boolean = true) {
        scope.launch {
            val i = pages.indexOf(m)
            if (animate && kotlin.math.abs(i - pager.currentPage) <= 1) pager.animateScrollToPage(i) else pager.scrollToPage(i)
        }
    }
    val wanted by Idle.openModule
    LaunchedEffect(wanted) {
        val k = wanted ?: return@LaunchedEffect
        Idle.openModule.value = null
        runCatching { Module.valueOf(k) }.getOrNull()?.let { go(it, animate = false) }
    }

    LaunchedEffect(home) { if (home > 0) { go(Module.FACE, animate = false); escape = false; control = false } }

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

    BackHandler(enabled = true) {
        when {
            escape -> escape = false
            control -> control = false
            else -> go(Module.FACE)
        }
    }

    val side = if (portrait) 22.dp else 40.dp
    Column(Modifier.fillMaxSize()) {
        StatusStrip(store, module, Modifier.padding(horizontal = side), onPull = { control = true })
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            userScrollEnabled = store.swipeScreens,
            beyondViewportPageCount = 0,
            key = { pages[it].name },
        ) { i ->
            val mod = pages[i]
            Column(Modifier.fillMaxSize().pageDepth(pager, i).padding(start = side, end = side, bottom = 6.dp)) {
                if (mod != Module.FACE) {
                    PageTitle(mod.label, portrait, Modifier.padding(top = 4.dp, bottom = 16.dp))
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (mod) {
                        Module.FACE -> FaceScreen(store, onOpen = { go(it) })
                        Module.TOOLS -> ToolsScreen(store)
                        Module.WEATHER -> WeatherScreen(store)
                        Module.NOTES -> NotesScreen(store)
                        Module.MEDIA -> MediaScreen()
                        Module.APPS -> AppsScreen(store)
                        Module.SYSTEM -> SystemScreen(store, act)
                    }
                }
            }
        }
        PageDots(pages.size, pager.currentPage + pager.currentPageOffsetFraction)
    }

    val apps = remember(store.recentApps, store.allowed) {
        store.recentApps.filter { blockReason(ctx, store, it) == null }.take(7)
    }
    val all = remember(apps) { AppsRepo.all(ctx) }
    EdgeWheels(
        tabs = pages.map { m -> WheelItem(m.name, m.label) { mod, c -> Glyph(m.glyph, c, mod) } },
        currentTab = module.name,
        onTab = { k -> go(Module.valueOf(k), animate = false) },
        apps = apps.mapNotNull { pkg ->
            val e = all.firstOrNull { it.pkg == pkg } ?: return@mapNotNull null
            WheelItem(pkg, e.label) { mod, _ -> Box(mod) { AppGlyph(e, store.iconStyle, 36.dp) } }
        },
        onApp = { pkg -> if (!launchApp(ctx, store, pkg)) store.forgetRecent(pkg) },
        onAllApps = { go(Module.APPS, animate = false) },
        // anything over the screens (lock, ringing timer, control centre, the way out, the ambient clock) keeps the edges
        enabled = edgeOff(escape, control, ambient, LockState.locked.value && store.voidLock).isEmpty(),
    )

    EdgeDebugOverlay()
    ControlCentre(control, store, act, onClose = { control = false }, onSettings = { go(Module.SYSTEM, animate = false) })
    if (ambient) AmbientClock(store)
    if (escape) EscapeOverlay(store, act, onClose = { escape = false })
}

/** Why the edges are off right now, for the debug overlay and the log; empty when they're on. */
private fun edgeOff(escape: Boolean, control: Boolean, ambient: Boolean, locked: Boolean): String {
    val why = buildList {
        if (escape) add("escape")
        if (control) add("control centre")
        if (ambient) add("ambient clock")
        if (locked) add("lock screen")
        if (KTimers.ringing.value.isNotEmpty()) add("timer ringing")
        if (com.andysocial.voidos.Alarms.ringing.value != null) add("alarm ringing")
    }.joinToString()
    EdgeSwipe.offReason = why
    return why
}

/** Small dots under the screens, so swiping sideways explains itself. */
@Composable
private fun PageDots(n: Int, pos: Float) {
    // round dots only, no pill: the one you're on is a small lit bead of glass that glides between them
    // tapping the dots opens the tab wheel, pinned: a way round that needs no edge swipe
    Canvas(
        Modifier.fillMaxWidth().clickable(interactionSource = null, indication = null) { EdgeSwipe.request.value = true }
            .padding(top = 4.dp, bottom = 12.dp).height(10.dp)
            .semantics { contentDescription = "Screens" }
    ) {
        val d = 6.dp.toPx(); val gap = 9.dp.toPx(); val step = d + gap
        val total = n * d + (n - 1) * gap
        val x0 = size.width / 2 - total / 2
        val cy = size.height / 2
        repeat(n) { i -> drawCircle(Color.White.copy(alpha = 0.16f), d / 2, Offset(x0 + i * step + d / 2, cy)) }
        val cx = x0 + pos.coerceIn(0f, (n - 1).toFloat()) * step + d / 2
        val c = androidx.compose.ui.graphics.lerp(Color.White, Ambience.moodAt(pos).a, 0.35f)
        val r = 4.6f.dp.toPx()
        drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.45f), Color.Transparent), center = Offset(cx, cy), radius = r * 3f), r * 3f, Offset(cx, cy))
        drawBead(Offset(cx, cy), r, c)
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

/**
 * The thin status line along the top. Tap it, or pull down from it, for the
 * control centre. The grabber in the middle says it can be pulled.
 */
@Composable
fun StatusStrip(store: Store, module: Module, modifier: Modifier = Modifier, onPull: () -> Unit) {
    val ctx = LocalContext.current
    val now = rememberNow(10_000)
    val bat = rememberBattery()
    val pull by rememberUpdatedState(onPull)
    val wifiOn = remember(now) {
        runCatching {
            val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            wm.isWifiEnabled && wm.connectionInfo?.networkId != -1
        }.getOrDefault(false)
    }
    Box(
        Modifier.fillMaxWidth().height(44.dp)
            .pointerInput(Unit) {
                var dy = 0f
                detectVerticalDragGestures(onDragStart = { dy = 0f }) { ch, d ->
                    dy += d; ch.consume()
                    if (dy > 36f) { dy = -10_000f; pull() }
                }
            }
            .tap { pull() }
            .then(modifier),
    ) {
        Row(Modifier.fillMaxSize().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (module != Module.FACE) {
                val fmt = if (store.use24h) "HH:mm" else "h:mm"
                VText(SimpleDateFormat(fmt, Locale.UK).format(Date(now)), size = 15.sp, font = SansMedium, color = V.Text)
            }
            Spacer(Modifier.weight(1f))
            Glyph(G.WIFI, if (wifiOn) V.Text else V.Tick, Modifier.size(17.dp))
            Gap(w = 14.dp)
            BatteryGlyph(bat)
        }
    }
}

@Composable
fun BatteryGlyph(b: Battery) {
    val col = when {
        b.pct in 0..15 && !b.charging -> V.Coral
        else -> V.Text
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        VText(if (b.pct >= 0) "${b.pct}%" else "--", size = 13.sp, color = V.Muted)
        Gap(w = 6.dp)
        Canvas(Modifier.size(26.dp, 13.dp)) {
            val r = CornerRadius(3.5.dp.toPx())
            drawRoundRect(col.copy(alpha = 0.45f), Offset(0f, 0f), Size(size.width - 3.dp.toPx(), size.height), r, style = Stroke(1.2.dp.toPx()))
            drawRoundRect(col.copy(alpha = 0.45f), Offset(size.width - 2.dp.toPx(), size.height * 0.32f), Size(2.dp.toPx(), size.height * 0.36f), CornerRadius(1.dp.toPx()))
            val f = (b.pct.coerceIn(0, 100) / 100f)
            val inset = 2.2.dp.toPx()
            drawRoundRect(if (b.charging) V.Green else col, Offset(inset, inset), Size((size.width - 3.dp.toPx() - 2 * inset) * f, size.height - 2 * inset), CornerRadius(2.dp.toPx()))
        }
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
            VText(SimpleDateFormat(fmt, Locale.UK).format(Date(now)), size = if (LocalPortrait.current) 120.sp else 150.sp, color = V.Text.copy(alpha = 0.5f), font = SansThin)
            VText(SimpleDateFormat("EEEE d MMMM", Locale.UK).format(Date(now)), size = 20.sp, color = V.Muted.copy(alpha = 0.6f))
        }
    }
}
