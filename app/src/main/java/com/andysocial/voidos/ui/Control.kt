package com.andysocial.voidos.ui

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andysocial.voidos.MainActivity
import com.andysocial.voidos.Now
import com.andysocial.voidos.Policy
import com.andysocial.voidos.Store
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** The tablet switches the control centre drives. Each one fails quietly and reports what it managed. */
object Tablet {
    private fun dpmSet(ctx: Context, key: String, v: String) =
        Policy.isOwner(ctx) && runCatching { Policy.dpm(ctx).setSystemSetting(Policy.admin(ctx), key, v) }.isSuccess

    private fun sysSet(ctx: Context, key: String, v: Int): Boolean =
        dpmSet(ctx, key, v.toString()) ||
            (Settings.System.canWrite(ctx) && runCatching { Settings.System.putInt(ctx.contentResolver, key, v) }.getOrDefault(false))

    fun brightness(ctx: Context): Float =
        runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)

    fun autoBrightness(ctx: Context): Boolean =
        runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) == 1 }.getOrDefault(false)

    /** Sets the tablet's brightness. Falls back to this window only, when Android refuses the system setting. */
    fun setBrightness(act: MainActivity, v: Float) {
        val f = v.coerceIn(0f, 1f)
        if (autoBrightness(act)) sysSet(act, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
        val ok = sysSet(act, Settings.System.SCREEN_BRIGHTNESS, (4 + f * 251).toInt())
        act.window.attributes = act.window.attributes.apply { screenBrightness = if (ok) -1f else 0.02f + f * 0.98f }
    }

    fun setAutoBrightness(act: MainActivity, on: Boolean): Boolean {
        val ok = sysSet(act, Settings.System.SCREEN_BRIGHTNESS_MODE, if (on) 1 else 0)
        if (on) act.window.attributes = act.window.attributes.apply { screenBrightness = -1f }
        return ok
    }

    fun volume(ctx: Context): Float {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return 0f
        return am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }

    fun setVolume(ctx: Context, v: Float) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, (v.coerceIn(0f, 1f) * max).toInt(), 0) }
    }

    fun wifiOn(ctx: Context): Boolean =
        runCatching { ctx.applicationContext.getSystemService(WifiManager::class.java).isWifiEnabled }.getOrDefault(false)

    /** Android 10 lets only the device owner do this; returns false when it was refused. */
    @Suppress("DEPRECATION")
    fun setWifi(ctx: Context, on: Boolean): Boolean =
        runCatching { ctx.applicationContext.getSystemService(WifiManager::class.java).setWifiEnabled(on) }.getOrDefault(false)

    @Suppress("DEPRECATION")
    fun btOn(): Boolean = runCatching { BluetoothAdapter.getDefaultAdapter()?.isEnabled == true }.getOrDefault(false)

    @Suppress("DEPRECATION", "MissingPermission")
    fun setBt(on: Boolean): Boolean = runCatching {
        val a = BluetoothAdapter.getDefaultAdapter() ?: return false
        if (on) a.enable() else a.disable()
    }.getOrDefault(false)

    fun dndAllowed(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    fun dndOn(ctx: Context): Boolean =
        (ctx.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL) !=
            NotificationManager.INTERRUPTION_FILTER_ALL

    /** Do not disturb lets alarms and timers through. False when VoidOS lacks Do Not Disturb access. */
    fun setDnd(ctx: Context, on: Boolean): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return false
        if (!nm.isNotificationPolicyAccessGranted) return false
        return runCatching {
            nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_ALARMS else NotificationManager.INTERRUPTION_FILTER_ALL)
        }.isSuccess
    }

    /** Turns the screen off. Needs device owner (or admin). */
    fun screenOff(ctx: Context): Boolean = runCatching { Policy.dpm(ctx).lockNow() }.isSuccess
}

/**
 * The control centre: pulled down from the top edge (or opened by tapping the
 * status bar). Brightness, volume, the tablet's main switches, what's playing,
 * and the way out to Settings or to Android.
 */
@Composable
fun ControlCentre(
    open: Boolean,
    store: Store,
    act: MainActivity,
    onClose: () -> Unit,
    onSettings: () -> Unit,
) {
    AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Black.copy(alpha = 0.45f)))).tap(onClose))
    }
    AnimatedVisibility(
        open,
        enter = slideInVertically(spring(dampingRatio = 0.82f, stiffness = 420f)) { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), contentAlignment = Alignment.TopCenter) {
            var pulled by remember { mutableFloatStateOf(0f) }
            Column(
                Modifier
                    .widthIn(max = if (LocalPortrait.current) 600.dp else 760.dp)
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(30.dp), alpha = 0.95f)
                    .ccAurora()
                    .tap { }
                    .pointerInput(Unit) {
                        // flick it back up to close
                        detectVerticalDragGestures(onDragEnd = { if (pulled < -60f) onClose(); pulled = 0f }) { ch, dy ->
                            pulled += dy
                            if (pulled < -60f) ch.consume()
                        }
                    }
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
            ) {
                ControlBody(store, act, onClose, onSettings)
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/*
 * The control centre's body, built like an iPad's: a clock, one glass block of
 * round switches, two tall sliders you drag up and down, what's playing, and
 * one glass bar of actions along the bottom. There is no way out of VoidOS
 * here on purpose.
 */
@Composable
private fun ControlBody(store: Store, act: MainActivity, onClose: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val portrait = LocalPortrait.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { now = LocalDateTime.now(); delay(1000); tick++ } }

    var bright by remember { mutableFloatStateOf(Tablet.brightness(ctx)) }
    var auto by remember { mutableStateOf(Tablet.autoBrightness(ctx)) }
    var vol by remember { mutableFloatStateOf(Tablet.volume(ctx)) }
    var wifi by remember { mutableStateOf(Tablet.wifiOn(ctx)) }
    var bt by remember { mutableStateOf(Tablet.btOn()) }
    var dnd by remember { mutableStateOf(Tablet.dndOn(ctx)) }
    var note by remember { mutableStateOf<String?>(null) }
    // Wi-Fi and Bluetooth take a moment to switch: re-read them for a few seconds after a tap
    LaunchedEffect(tick / 2) { wifi = Tablet.wifiOn(ctx); bt = Tablet.btOn(); dnd = Tablet.dndOn(ctx) }
    LaunchedEffect(note) { if (note != null) { delay(4000); note = null } }

    // ---- header: a greeting, a rolling clock with the logo line under it, the battery ----
    val ticker = rememberTicker()
    val thin = rememberFace("sans-serif-thin")
    val dens = androidx.compose.ui.platform.LocalDensity.current
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            VText(greeting(System.currentTimeMillis()), size = 13.sp, color = lerp(V.Teal, Color.White, 0.3f), spacing = 0.5.sp)
            Spacer(Modifier.height(2.dp))
            RollingText(now.format(DateTimeFormatter.ofPattern(if (store.use24h) "HH:mm" else "h:mm")), with(dens) { 54.sp.toPx() }, thin, tick = ticker)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                VText(now.format(DateTimeFormatter.ofPattern("EEEE d MMMM")), size = 14.sp, color = V.Muted)
            }
        }
        val bat = rememberBattery()
        BatteryPill(bat.pct, bat.charging)
    }
    Spacer(Modifier.height(20.dp))

    val pinned = store.rotation != "auto"
    val switches: @Composable (Modifier) -> Unit = { m ->
        Column(m.pane(RoundedCornerShape(26.dp)).padding(vertical = 16.dp, horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                CcBubble("Wi-Fi", if (wifi) "On" else "Off", G.WIFI, wifi, V.Teal) {
                    if (Tablet.setWifi(ctx, !wifi)) wifi = !wifi else note = "Android didn't let VoidOS switch Wi-Fi. Open Settings > Wi-Fi."
                }
                CcBubble("Bluetooth", if (bt) "On" else "Off", G.BT, bt, Color(0xFF5A8CF0)) {
                    if (Tablet.setBt(!bt)) bt = !bt else note = "Android didn't let VoidOS switch Bluetooth."
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                CcBubble("Do not disturb", if (dnd) "Alarms only" else "Off", G.MOON, dnd, V.Purple) {
                    if (!Tablet.dndAllowed(ctx)) note = "VoidOS needs Do Not Disturb access first: Settings > Do not disturb."
                    else if (Tablet.setDnd(ctx, !dnd)) dnd = !dnd
                }
                CcBubble("Rotation lock", when (store.rotation) { "port" -> "Portrait"; "land" -> "Landscape"; else -> "Off" }, G.ROTATE, pinned, V.Coral) {
                    store.setRotation(if (pinned) "auto" else if (portrait) "port" else "land"); act.applyRotation()
                }
            }
        }
    }
    val sliders: @Composable (Modifier) -> Unit = { m ->
        Row(m, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                CcTall(G.SUN, bright, Modifier.weight(1f), listOf(Color(0xFFFFF4D6), Color(0xFFFFD27A))) { bright = it; auto = false; Tablet.setBrightness(act, it) }
                Spacer(Modifier.height(8.dp))
                AutoChip(auto) { if (Tablet.setAutoBrightness(act, it)) auto = it else note = "Android didn't let VoidOS change auto brightness." }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                CcTall(G.VOLUME, vol, Modifier.weight(1f), listOf(lerp(V.Teal, Color.White, 0.55f), V.Teal)) { vol = it; Tablet.setVolume(ctx, it) }
                Spacer(Modifier.height(8.dp))
                VText("Volume", size = 12.sp, color = V.Muted, modifier = Modifier.padding(vertical = 7.dp))
            }
        }
    }

    // ---- switches and sliders side by side, in both orientations ----
    Row(Modifier.fillMaxWidth().height(if (portrait) 236.dp else 244.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        switches(Modifier.weight(if (portrait) 1.25f else 1.1f).fillMaxHeight())
        sliders(Modifier.weight(if (portrait) 0.85f else 0.62f).fillMaxHeight())
        if (!portrait) NowPlayingCard(Modifier.weight(1.05f).fillMaxHeight())
    }
    if (portrait) { Spacer(Modifier.height(14.dp)); NowPlayingCard(Modifier.fillMaxWidth().height(96.dp)) }
    note?.let { Spacer(Modifier.height(10.dp)); VText(it, size = 13.sp, color = V.Coral) }

    // ---- one glass bar of actions ----
    Spacer(Modifier.height(16.dp))
    GlassActions(
        buildList {
            add(GlassAction("Stay awake", G.LIGHT, active = store.deskMode) { store.set("deskMode", !store.deskMode); act.applyPolicies() })
            add(GlassAction("Screen off", G.POWER) { onClose(); if (!Tablet.screenOff(ctx)) note = "Android didn't let VoidOS turn the screen off." })
            if (store.voidLock) add(GlassAction("Lock", G.LOCK) { onClose(); LockState.locked.value = true })
            add(GlassAction("Settings", G.SYSTEM) { onClose(); onSettings() })
        },
        height = 72.dp, stacked = true,
    )
}

/** The control centre's own slow light, purple, teal and coral, drifting inside the glass. */
@Composable
private fun Modifier.ccAurora(): Modifier {
    val tick = rememberTicker(50L)
    return drawBehind {
        val k = (phase(tick.value, 30_000L) * 2 * Math.PI).toFloat()
        val r = maxOf(size.width, size.height)
        fun blob(c: Color, x: Float, y: Float, rad: Float, a: Float) {
            val o = androidx.compose.ui.geometry.Offset(x, y)
            drawCircle(Brush.radialGradient(listOf(c.copy(alpha = a), Color.Transparent), center = o, radius = rad), rad, o)
        }
        blob(V.Purple, size.width * (0.12f + 0.06f * kotlin.math.sin(k)), size.height * 0.05f, r * 0.55f, 0.20f)
        blob(V.Teal, size.width * (0.92f + 0.04f * kotlin.math.cos(k)), size.height * (0.85f + 0.06f * kotlin.math.sin(2 * k)), r * 0.5f, 0.16f)
        blob(V.Coral, size.width * (0.55f + 0.15f * kotlin.math.sin(k + 2f)), size.height * 0.55f, r * 0.28f, 0.07f)
    }
}

/** A round switch with its name under it, the way iPad control centres do it. Teal when on. */
@Composable
private fun CcBubble(label: String, state: String, g: G, on: Boolean, accent: Color, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Column(
        Modifier.width(96.dp).clickable(interactionSource = src, indication = null, onClick = onClick).semantics { selected = on },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // each switch has its own colour when on, with a glow that spills round it
        val lit by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 300f), label = "lit")
        Box(
            Modifier.size(58.dp)
                .drawBehind {
                    if (lit > 0f) drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.45f * lit), Color.Transparent)), size.minDimension * 0.95f)
                }
                .graphicsLayer { val sc = 0.94f + 0.06f * lit; scaleX = sc; scaleY = sc }
                .liquidGlass(CircleShape, GlassLook(smoke = 0.7f - 0.45f * lit, depth = 0.7f, shine = 1f + 0.35f * lit, magnify = 1.12f + 0.25f * lit, stain = accent, stainAlpha = 0.78f * lit)),
            contentAlignment = Alignment.Center,
        ) { Glyph(g, lerp(V.Text, Color.White, lit), Modifier.size(24.dp)) }
        Spacer(Modifier.height(7.dp))
        VText(label, size = 13.sp, color = V.Text, maxLines = 1)
        VText(state, size = 11.sp, color = if (on) lerp(accent, Color.White, 0.25f) else V.Muted, maxLines = 1)
    }
}

/** A tall glass slider: drag up and down. The light fill is the level; the icon sits at its foot. */
@Composable
private fun CcTall(g: G, value: Float, modifier: Modifier, fill: List<Color>, onChange: (Float) -> Unit) {
    val cb by rememberUpdatedState(onChange)
    val v = value.coerceIn(0f, 1f)
    Box(
        modifier.fillMaxWidth().widthIn(max = 92.dp).glass(RoundedCornerShape(26.dp), 0.85f)
            .pointerInput(Unit) { detectTapGestures { o -> cb((1f - o.y / size.height).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectVerticalDragGestures { ch, _ -> ch.consume(); cb((1f - ch.position.y / size.height).coerceIn(0f, 1f)) }
            },
    ) {
        val shown by animateFloatAsState(v.coerceAtLeast(0.08f), spring(dampingRatio = 0.85f, stiffness = 900f), label = "level")
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(shown)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(Brush.verticalGradient(fill))
                // the top of the liquid catches the light: a soft glow that fades down, not a line
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.08f), Color.Transparent), endY = 90f)),
        )
        Glyph(g, if (v > 0.16f) V.Bg else V.Text, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).size(24.dp))
    }
}

/** Auto brightness as a small glass chip under its slider. */
@Composable
private fun AutoChip(on: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        Modifier.then(if (on) Modifier.droplet(Pill, V.Teal) else Modifier.glass(Pill, 0.55f))
            .semantics { contentDescription = "Auto brightness"; selected = on }
            .clickable { onChange(!on) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) { VText("Auto", size = 12.sp, color = if (on) V.Teal else V.Muted) }
}

@Composable
private fun BatteryPill(pct: Int, charging: Boolean) {
    Row(
        Modifier.glass(Pill, 0.55f)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp, 11.dp).clip(RoundedCornerShape(4.dp)).background(V.Text.copy(alpha = 0.22f)).padding(2.dp)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth((pct / 100f).coerceIn(0.05f, 1f)).clip(RoundedCornerShape(1.dp)).background(if (pct <= 15 && !charging) V.Coral else V.Text))
        }
        Spacer(Modifier.width(8.dp))
        VText("$pct%" + if (charging) " charging" else "", size = 13.sp, color = V.Text)
    }
}

/** What's playing, in its own glass card; a quiet card when nothing is. */
@Composable
private fun NowPlayingCard(modifier: Modifier) {
    val playing by Now.playing
    val p = playing
    Column(modifier.pane(RoundedCornerShape(26.dp)).padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
        if (p == null) {
            Glyph(G.MEDIA, V.Tick, Modifier.size(22.dp))
            VText("Nothing playing", size = 14.sp, color = V.Muted)
        } else {
            Column {
                VText(p.title.ifBlank { p.appName }, size = 15.sp, font = SansMedium, color = V.Text, maxLines = 2)
                VText(p.artist.ifBlank { p.appName }, size = 13.sp, color = V.Muted, maxLines = 1)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                CcRound(G.PREV) { Now.prev() }
                CcRound(if (p.playing) G.PAUSE else G.PLAY) { Now.playPause() }
                CcRound(G.NEXT) { Now.next() }
            }
        }
    }
}

@Composable
private fun CcRound(g: G, onClick: () -> Unit) {
    Box(Modifier.size(42.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Glyph(g, V.Text, Modifier.size(22.dp))
    }
}

/** Settings deep links the control centre does not cover. */
fun openAndroidSettings(ctx: Context, action: String = Settings.ACTION_SETTINGS) {
    runCatching { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
