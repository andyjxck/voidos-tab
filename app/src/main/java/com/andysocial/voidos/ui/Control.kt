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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andysocial.voidos.MainActivity
import com.andysocial.voidos.Now
import com.andysocial.voidos.Policy
import com.andysocial.voidos.Store
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A frosted surface: a dark translucent fill that is lighter at the top, and a
 * hairline rim that catches the light along the top edge. Android 10 cannot blur
 * what is behind a view, so the depth comes from the gradient and the rim.
 */
fun Modifier.glass(shape: Shape, alpha: Float = 0.86f): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(Color(0xFF2A2A2E).copy(alpha = alpha), Color(0xFF151517).copy(alpha = alpha))))
    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f))), shape)

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
    onEscape: () -> Unit,
) {
    AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).tap(onClose))
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
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(30.dp), alpha = 0.95f)
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
                ControlBody(store, act, onClose, onSettings, onEscape)
                Spacer(Modifier.height(14.dp))
                Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 5.dp).clip(Pill).background(V.White.copy(alpha = 0.22f)))
            }
        }
    }
}

@Composable
private fun ControlBody(store: Store, act: MainActivity, onClose: () -> Unit, onSettings: () -> Unit, onEscape: () -> Unit) {
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

    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            VText(now.format(DateTimeFormatter.ofPattern(if (store.use24h) "HH:mm" else "h:mm")), size = 40.sp, font = SansLight, color = V.White)
            VText(now.format(DateTimeFormatter.ofPattern("EEEE d MMMM")), size = 14.sp, color = V.Muted)
        }
        val bat = rememberBattery()
        VText("${bat.pct}%" + if (bat.charging) " · charging" else "", size = 14.sp, color = V.Muted)
    }
    Spacer(Modifier.height(20.dp))

    val tiles: List<@Composable RowScope.() -> Unit> = listOf(
        {
            CcTile("Wi-Fi", if (wifi) "On" else "Off", G.WIFI, wifi) {
                if (Tablet.setWifi(ctx, !wifi)) wifi = !wifi else note = "Android didn't let VoidOS switch Wi-Fi. Open Settings > Wi-Fi."
            }
        },
        {
            CcTile("Bluetooth", if (bt) "On" else "Off", G.BT, bt) {
                if (Tablet.setBt(!bt)) bt = !bt else note = "Android didn't let VoidOS switch Bluetooth."
            }
        },
        {
            CcTile("Do not disturb", if (dnd) "Alarms only" else "Off", G.MOON, dnd) {
                if (!Tablet.dndAllowed(ctx)) {
                    note = "VoidOS needs Do Not Disturb access first: Settings > Do not disturb."
                } else if (Tablet.setDnd(ctx, !dnd)) dnd = !dnd
            }
        },
        {
            val pinned = store.rotation != "auto"
            CcTile("Rotation lock", when (store.rotation) { "port" -> "Portrait"; "land" -> "Landscape"; else -> "Off" }, G.ROTATE, pinned) {
                store.setRotation(if (pinned) "auto" else if (portrait) "port" else "land"); act.applyRotation()
            }
        },
        {
            CcTile("Stay awake", if (store.deskMode) "While charging" else "Off", G.LIGHT, store.deskMode) {
                store.set("deskMode", !store.deskMode); act.applyPolicies()
            }
        },
        {
            CcTile("Screen off", "Now", G.POWER, false) {
                onClose()
                if (!Tablet.screenOff(ctx)) note = "Android didn't let VoidOS turn the screen off."
            }
        },
    )
    val perRow = 3
    tiles.chunked(perRow).forEachIndexed { i, row ->
        if (i > 0) Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { row.forEach { it() } }
    }
    note?.let { Spacer(Modifier.height(10.dp)); VText(it, size = 13.sp, color = V.Coral) }

    Spacer(Modifier.height(18.dp))
    CcSlider(G.SUN, bright) { bright = it; auto = false; Tablet.setBrightness(act, it) }
    Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        VText("Auto brightness", Modifier.weight(1f), size = 14.sp, color = V.Muted)
        VToggle(auto) { if (Tablet.setAutoBrightness(act, it)) auto = it else note = "Android didn't let VoidOS change auto brightness." }
    }
    CcSlider(G.VOLUME, vol) { vol = it; Tablet.setVolume(ctx, it) }

    val playing by Now.playing
    playing?.let { p ->
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(V.White.copy(alpha = 0.06f)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                VText(p.title.ifBlank { p.appName }, size = 15.sp, font = SansMedium, color = V.Text, maxLines = 1)
                VText(p.artist.ifBlank { p.appName }, size = 13.sp, color = V.Muted, maxLines = 1)
            }
            CcRound(G.PREV) { Now.prev() }
            CcRound(if (p.playing) G.PAUSE else G.PLAY) { Now.playPause() }
            CcRound(G.NEXT) { Now.next() }
        }
    }

    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        VButton("Settings", Modifier.weight(1f)) { onClose(); onSettings() }
        if (store.voidLock) VButton("Lock", Modifier.weight(1f)) { onClose(); LockState.locked.value = true }
        VButton("Leave VoidOS", Modifier.weight(1f), accent = V.Coral) { onClose(); onEscape() }
    }
}

@Composable
private fun RowScope.CcTile(label: String, state: String, g: G, on: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(20.dp))
            .background(if (on) V.Teal else V.White.copy(alpha = 0.07f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Glyph(g, if (on) V.Bg else V.Text, Modifier.size(24.dp))
        Spacer(Modifier.height(12.dp))
        VText(label, size = 14.sp, font = SansMedium, color = if (on) V.Bg else V.Text, maxLines = 1)
        VText(state, size = 12.sp, color = if (on) V.Bg.copy(alpha = 0.7f) else V.Muted, maxLines = 1)
    }
}

/** A tall, thick slider like a glass bar: the fill is the level, the icon sits inside. */
@Composable
private fun CcSlider(g: G, value: Float, onChange: (Float) -> Unit) {
    Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(18.dp)).background(V.White.copy(alpha = 0.07f))) {
        Box(Modifier.fillMaxWidth(value.coerceIn(0.06f, 1f)).height(52.dp).background(V.White.copy(alpha = 0.88f)))
        Glyph(g, if (value > 0.1f) V.Bg else V.Text, Modifier.align(Alignment.CenterStart).padding(start = 16.dp).size(22.dp))
        VSliderTouch(Modifier.fillMaxSize(), onChange)
    }
}

@Composable
private fun CcRound(g: G, onClick: () -> Unit) {
    Box(Modifier.padding(start = 6.dp).size(42.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Glyph(g, V.Text, Modifier.size(22.dp))
    }
}

/** Settings deep links the control centre does not cover. */
fun openAndroidSettings(ctx: Context, action: String = Settings.ACTION_SETTINGS) {
    runCatching { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
