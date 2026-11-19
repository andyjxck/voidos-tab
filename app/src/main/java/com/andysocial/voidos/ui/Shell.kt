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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
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
    /** True while VoidOS shares the screen with an app (split view). */
    val split = mutableStateOf(false)
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
    CAMERA("Camera", G.CAMERA, V.Teal),
    UTILITIES("Utilities", G.LEVEL, V.Teal),
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
        Backdrop(Modifier.stillWhileHeld())
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
            if (!booting && store.setupDone) {
                UsbSheet(Modifier.align(Alignment.BottomCenter))
                ToastHost(Modifier.align(Alignment.BottomCenter))
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

    // the book closes back to the watch face only if you've been away from Home for five seconds
    LaunchedEffect(module) {
        val hadApp = Book.state.value.app != null
        if (Book.onHome(ctx, module == Module.FACE, System.currentTimeMillis()) && hadApp) Book.closeApp(ctx, store)
    }

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
            // on Home, Back closes the half of the book opened last
            module == Module.FACE && Book.back(ctx) -> Unit
            else -> go(Module.FACE)
        }
    }

    val side = if (portrait) 22.dp else 40.dp
    // while a wheel is open the page holds still in one cached texture, so the GPU only redraws the wheel
    // there's no back button to put the keyboard away: a tap on anything that isn't a control does it,
    // and so does turning the page
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(pager.currentPage) { focus.clearFocus() }
    Column(
        Modifier.fillMaxSize().stillWhileHeld()
            // under the switcher's solid backdrop, Home is hidden from TalkBack too
            .then(if (Switcher.shown.value) Modifier.clearAndSetSemantics { } else Modifier)
            .pointerInput(Unit) { detectTapGestures(onTap = { focus.clearFocus() }) },
    ) {
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
                // no page titles: the content starts at the top
                if (mod != Module.FACE) Gap(h = 8.dp)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (mod) {
                        // Home is the watch face, split like a book when a screen or an app is dropped on it
                        Module.FACE -> BookHome(store, face = { FaceScreen(store, onOpen = { go(it) }) }) { m -> BookScreen(store, act, m) }
                        Module.TOOLS -> ToolsScreen(store, Module.TOOLS)
                        Module.CAMERA -> ToolsScreen(store, Module.CAMERA)
                        Module.UTILITIES -> ToolsScreen(store, Module.UTILITIES)
                        Module.WEATHER -> WeatherScreen(store)
                        Module.NOTES -> NotesScreen(store)
                        Module.SYSTEM -> SystemScreen(store, act)
                        Module.MEDIA -> MediaScreen()
                        Module.APPS -> AppsScreen(store)
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
    // the side wheel shows the screens you picked, then a pencil at its end to change them:
    // slide past the last screen onto it, or tap it
    var editWheel by remember { mutableStateOf(false) }
    val tabItems = remember(pages, store.wheelHidden) {
        pages.filter { it.name !in store.wheelHidden }.map { m -> WheelItem(m.name, m.label) { mod, c -> Glyph(m.glyph, c, mod) } } +
            WheelItem(WHEEL_EDIT, "Edit") { mod, c -> Glyph(G.PEN, c.copy(alpha = 0.7f), mod) }
    }
    val appItems = remember(apps, all, store.iconStyle) {
        apps.mapNotNull { pkg ->
            val e = all.firstOrNull { it.pkg == pkg } ?: return@mapNotNull null
            WheelItem(pkg, e.label) { mod, _ -> Box(mod) { AppGlyph(e, store.iconStyle, 36.dp) } }
        }
    }
    EdgeWheels(
        tabs = tabItems,
        currentTab = module.name,
        onTab = { k -> if (k == WHEEL_EDIT) editWheel = true else go(Module.valueOf(k), animate = false) },
        apps = appItems,
        onApp = { pkg -> if (!launchApp(ctx, store, pkg)) store.forgetRecent(pkg) },
        onAllApps = { go(Module.APPS, animate = false) },
        // pull a slot out past the wheel and drop it on a half of the screen: it opens there, beside the watch face
        onDrop = { key, isApp, first -> dropOnBook(ctx, store, act, scope, key, isApp, first); go(Module.FACE, animate = false) },
        canDrop = { key, isApp -> isApp || runCatching { bookable(Module.valueOf(key)) }.getOrDefault(false) },
        // hold an app's slot, swipe down: the app is closed completely, and leaves the book if it's in it
        onCloseApp = { pkg ->
            scope.launch {
                if (Book.state.value.app?.key == pkg) Book.closeApp(ctx, store)
                com.andysocial.voidos.AppKill.close(ctx, store, pkg)
            }
        },
        canClose = { pkg -> com.andysocial.voidos.AppKill.closable(ctx, pkg) },
        // anything over the screens (lock, ringing timer, control centre, the way out, the ambient clock) keeps the edges
        enabled = edgeOff(escape, control, ambient, LockState.locked.value && store.voidLock).isEmpty() && fullTool.value == null,
    )
    FullToolOverlay()

    if (editWheel) WheelPagesEditor(store, pages) { editWheel = false }
    EdgeDebugOverlay()
    val ccReq by EdgeSwipe.controlRequest
    LaunchedEffect(ccReq) { if (ccReq) { EdgeSwipe.controlRequest.value = false; control = true } }
    ControlCentre(control, store, act, onClose = { control = false }, onSettings = { go(Module.SYSTEM, animate = false) })
    AppSwitcher(store)
    if (ambient) AmbientClock(store)
    if (escape) EscapeOverlay(store, act, onClose = { escape = false })
}

/** The key of the pencil at the end of the side wheel. */
const val WHEEL_EDIT = "__edit"

/** Picks which screens the side wheel shows. Home always stays, so the wheel is never empty. */
@Composable
private fun WheelPagesEditor(store: Store, pages: List<Module>, onDone: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) { detectTapGestures(onTap = { onDone() }) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth(0.86f).glass(RoundedCornerShape(32.dp))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(horizontal = 22.dp, vertical = 18.dp),
        ) {
            VText("Side wheel", size = Type.title, font = SansMedium, color = V.White)
            Gap(h = 10.dp)
            pages.forEach { m ->
                val shown = m.name !in store.wheelHidden
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .tap { if (m != Module.FACE) store.setWheelShown(m.name, !shown) }.padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph(m.glyph, if (shown) V.Teal else V.Muted, Modifier.size(24.dp))
                    Gap(w = 14.dp)
                    VText(m.label, size = Type.body, color = if (shown) V.White else V.Muted, modifier = Modifier.weight(1f))
                    VToggle(shown, enabled = m != Module.FACE) { store.setWheelShown(m.name, it) }
                }
            }
            Gap(h = 12.dp)
            GlassActions(listOf(GlassAction("Done", onClick = onDone)), height = 46.dp)
        }
    }
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
        Row(Modifier.fillMaxSize().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (module != Module.FACE) {
                val fmt = if (store.use24h) "HH:mm" else "h:mm"
                VText(SimpleDateFormat(fmt, Locale.UK).format(Date(now)), size = Type.body, font = SansMedium, color = V.Text)
            }
            Spacer(Modifier.weight(1f))
            Glyph(G.WIFI, if (wifiOn) V.Text else V.Tick, Modifier.size(17.dp))
            Gap(w = 16.dp)
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
        VText(if (b.pct >= 0) "${b.pct}%" else "--", size = Type.label, color = V.Muted)
        Gap(w = 8.dp)
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

/**
 * Dim desk clock (standby). It drifts a little every minute so nothing burns in. The face is
 * digital or analogue (Settings > Standby clock), and while the tablet charges a soft ring
 * glows round the edge of the screen, breathing slowly.
 */
@Composable
fun AmbientClock(store: Store, battery: Battery = rememberBattery()) {
    val now = rememberNow(1000)
    val minute = now / 60_000
    val dx = ((minute * 37) % 120 - 60).toInt()
    val dy = ((minute * 53) % 90 - 45).toInt()
    val fmt = if (store.use24h) "HH:mm" else "h:mm"
    val portrait = LocalPortrait.current
    Box(Modifier.fillMaxSize().background(Color.Black).semantics { contentDescription = "Standby clock" }, contentAlignment = Alignment.Center) {
        if (battery.charging) ChargingRing(battery.pct, Modifier.fillMaxSize())
        Column(Modifier.offset((dx * 0.6f).dp, (dy * 0.8f).dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (store.ambientFace == "analog") {
                AnalogueClock(now, Modifier.size(if (portrait) 300.dp else 280.dp))
                Gap(h = Grid.l)
            } else {
                VText(SimpleDateFormat(fmt, Locale.UK).format(Date(now)), size = if (portrait) 120.sp else 150.sp, color = V.Text.copy(alpha = 0.5f), font = SansThin)
            }
            VText(SimpleDateFormat("EEEE d MMMM", Locale.UK).format(Date(now)), size = Type.title, color = V.Muted.copy(alpha = 0.6f))
            if (battery.charging) {
                Gap(h = Grid.s)
                VText(if (battery.pct >= 100) "Charged" else if (battery.pct >= 0) "Charging  \u00b7  ${battery.pct}%" else "Charging",
                    size = Type.label, color = V.Green.copy(alpha = 0.7f), modifier = Modifier.semantics { contentDescription = "Charging" })
            }
            // the notification light the tablet doesn't have: a slow teal glow while something new waits
            val waiting = com.andysocial.voidos.VoidListener.notices.value
            if (store.notifyLight && waiting.isNotEmpty()) {
                val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "notify").animateFloat(
                    0.25f, 0.9f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1800), androidx.compose.animation.core.RepeatMode.Reverse), label = "glow",
                )
                Gap(h = Grid.xl)
                Box(Modifier.size(14.dp).graphicsLayer { alpha = pulse }.background(
                    androidx.compose.ui.graphics.Brush.radialGradient(listOf(V.Teal, V.Teal.copy(alpha = 0f))), CircleShape,
                ))
                Gap(h = Grid.s)
                VText(if (waiting.size == 1) waiting.first().appName else "${waiting.size} new", size = Type.label, color = V.Muted.copy(alpha = 0.6f))
            }
        }
    }
}

/**
 * The charging glow: a rounded ring just inside the screen's edge, green, with a soft halo
 * that breathes in and out. It stays dim so it's fine on a bedside table at night.
 */
@Composable
internal fun ChargingRing(pct: Int, modifier: Modifier) {
    val breathe by androidx.compose.animation.core.rememberInfiniteTransition(label = "charge").animateFloat(
        0.45f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(2600), androidx.compose.animation.core.RepeatMode.Reverse), label = "ring",
    )
    val full = pct >= 100
    val c = if (full) V.Teal else V.Green
    Canvas(modifier.semantics { contentDescription = "Charging ring" }) {
        val inset = 10.dp.toPx()
        val r = 34.dp.toPx()
        val tl = Offset(inset, inset)
        val sz = Size(size.width - inset * 2, size.height - inset * 2)
        // the halo: wide, faint strokes under the line, fading outwards
        listOf(28.dp to 0.05f, 18.dp to 0.08f, 10.dp to 0.13f).forEach { (w, a) ->
            drawRoundRect(c.copy(alpha = a * breathe), tl, sz, CornerRadius(r), style = Stroke(w.toPx()))
        }
        // the ring itself
        drawRoundRect(c.copy(alpha = 0.55f * breathe + 0.15f), tl, sz, CornerRadius(r), style = Stroke(2.5.dp.toPx()))
    }
}

/** A dim analogue face: twelve marks, hour and minute hands, and a thin second hand. */
@Composable
internal fun AnalogueClock(now: Long, modifier: Modifier) {
    val cal = remember { java.util.Calendar.getInstance() }
    cal.timeInMillis = now
    val h = cal.get(java.util.Calendar.HOUR) + cal.get(java.util.Calendar.MINUTE) / 60f
    val m = cal.get(java.util.Calendar.MINUTE) + cal.get(java.util.Calendar.SECOND) / 60f
    val sec = cal.get(java.util.Calendar.SECOND).toFloat()
    val label = SimpleDateFormat("h:mm a", Locale.UK).format(Date(now))
    Canvas(modifier.semantics { contentDescription = "Analogue clock, $label" }) {
        val c = Offset(size.width / 2, size.height / 2)
        val rad = minOf(size.width, size.height) / 2
        val dim = V.Text.copy(alpha = 0.5f)
        drawCircle(V.Text.copy(alpha = 0.12f), rad - 1.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0 - 90)
            val major = i % 5 == 0
            val outer = rad - 8.dp.toPx()
            val inner = outer - (if (major) 16.dp else 6.dp).toPx()
            drawLine(
                if (major) dim else V.Text.copy(alpha = 0.18f),
                Offset(c.x + (inner * kotlin.math.cos(a)).toFloat(), c.y + (inner * kotlin.math.sin(a)).toFloat()),
                Offset(c.x + (outer * kotlin.math.cos(a)).toFloat(), c.y + (outer * kotlin.math.sin(a)).toFloat()),
                (if (major) 3.dp else 1.5.dp).toPx(), StrokeCap.Round,
            )
        }
        fun hand(turn: Float, len: Float, w: Float, col: Color, tail: Float = 0f) {
            val a = Math.toRadians(turn * 360.0 - 90)
            val dx = kotlin.math.cos(a).toFloat(); val dy = kotlin.math.sin(a).toFloat()
            drawLine(col, Offset(c.x - dx * tail, c.y - dy * tail), Offset(c.x + dx * len, c.y + dy * len), w, StrokeCap.Round)
        }
        hand(h / 12f, rad * 0.5f, 7.dp.toPx(), dim)
        hand(m / 60f, rad * 0.74f, 4.5.dp.toPx(), dim)
        hand(sec / 60f, rad * 0.82f, 1.5.dp.toPx(), V.Teal.copy(alpha = 0.6f), tail = rad * 0.14f)
        drawCircle(V.Teal.copy(alpha = 0.7f), 5.dp.toPx(), c)
    }
}

/** One of VoidOS's screens, drawn in a half of the book on Home. */
@Composable
private fun BookScreen(store: Store, act: MainActivity, m: Module) {
    when (m) {
        Module.FACE -> Unit
        Module.TOOLS -> ToolsScreen(store, Module.TOOLS)
        Module.CAMERA -> ToolsScreen(store, Module.CAMERA)
        Module.UTILITIES -> ToolsScreen(store, Module.UTILITIES)
        Module.WEATHER -> WeatherScreen(store)
        Module.NOTES -> NotesScreen(store)
        Module.SYSTEM -> SystemScreen(store, act)
        Module.MEDIA -> MediaScreen()
        Module.APPS -> AppsScreen(store)
    }
}

/**
 * A wheel slot dropped on a half of Home. The book holds the one thing dropped and Home: a new
 * drop replaces what was open. While Android's split is up, VoidOS only has its own half to
 * give: an app replaces the app in the other half, and a screen goes into VoidOS's half,
 * which ends the split, so the screen and Home are left.
 */
private fun dropOnBook(ctx: android.content.Context, store: Store, act: MainActivity, scope: kotlinx.coroutines.CoroutineScope, key: String, isApp: Boolean, first: Boolean) {
    val before = Book.state.value
    val side = if (Idle.split.value && before.appFirst != null) (if (isApp) before.appFirst!! else !before.appFirst!!) else first
    val pane = if (isApp) Pane.app(key) else Pane.screen(runCatching { Module.valueOf(key) }.getOrNull() ?: return)
    Book.drop(ctx, pane, side)
    val after = Book.state.value
    scope.launch {
        when {
            isApp -> if (!Book.openApp(ctx, store, key, side)) {
                Book.set(ctx, Book.state.value.withoutApp())
                store.setSplit("")
                act.enforceLockTask()
                toast.value = "That app can't share the screen."
            }
            // a screen dropped over the app's half ends Android's split
            before.app != null && after.app == null -> Book.closeApp(ctx, store)
        }
    }
}
