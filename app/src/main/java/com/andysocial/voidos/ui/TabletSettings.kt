package com.andysocial.voidos.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.andysocial.voidos.Device
import com.andysocial.voidos.MainActivity
import com.andysocial.voidos.Store
import com.andysocial.voidos.Su
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Every setting that used to need Android's Settings app, done by VoidOS itself (with root
 * where Android only lets the system do it). Hide Android switches the Settings app off, so
 * nothing here sends you there.
 */

private fun bg(block: () -> Unit) = Thread { runCatching(block) }.apply { isDaemon = true; start() }

/** Screen: text and screen size, dark apps, night light, colour filters, auto night mode. */
@Composable
fun ScreenExtras(store: Store) {
    val ctx = LocalContext.current
    var font by remember { mutableFloatStateOf(Device.fontScale(ctx)) }
    var dark by remember { mutableStateOf(Device.darkAppsOn(ctx)) }
    var grey by remember { mutableStateOf(Device.greyOn(ctx)) }
    var invert by remember { mutableStateOf(Device.invertOn(ctx)) }
    var magnify by remember { mutableStateOf(Device.magnifyOn(ctx)) }
    var warm by remember { mutableFloatStateOf(store.nightWarmth) }
    SetSection("Text and colour")
    SettingRow("Text size") {}
    GlassSegments(
        listOf("0.85" to "Small", "1.0" to "Default", "1.15" to "Large", "1.3" to "Largest"),
        listOf(0.85f, 1.0f, 1.15f, 1.3f).minByOrNull { kotlin.math.abs(it - font) }.toString(),
        Modifier.widthIn(max = 520.dp), height = 44.dp, textSize = 14f,
    ) { v -> font = v.toFloat(); bg { Device.setFontScale(font) } }
    Gap(h = Grid.s)
    SettingRow("Dark mode in other apps") { VToggle(dark) { dark = it; bg { Device.darkApps(it) } } }
    SettingRow("Night light warmth") {}
    VSlider(warm, Modifier.widthIn(max = 520.dp).fillMaxWidth(), accent = V.Orange) {
        warm = it; store.set("nightWarmth", it); bg { Device.warmth(it) }
    }
    SettingRow("Night light from sunset to sunrise") {
        VToggle(store.nightAuto, V.Orange) { store.set("nightAuto", it); bg { Device.nightSchedule(it) } }
    }
    SettingRow("Dim in a dark room", if (Sensors.has(ctx, android.hardware.Sensor.TYPE_LIGHT)) null else "No light sensor") {
        VToggle(store.autoNight) { store.set("autoNight", it); Sensors.sync(ctx, store) }
    }
    SettingRow("Greyscale") { VToggle(grey) { grey = it; bg { Device.grey(it) } } }
    SettingRow("Invert colours") { VToggle(invert) { invert = it; bg { Device.invert(it) } } }
    SettingRow("Triple-tap to zoom") { VToggle(magnify) { magnify = it; bg { Device.magnify(it) } } }
}

/** Sound: vibration (the volume sliders are above it on the same page). */
@Composable
fun SoundExtras(store: Store) {
    SettingRow("Vibrate with alarms") { VToggle(store.alarmVibrate) { store.set("alarmVibrate", it) } }
}

/** Network: private DNS, VPN apps, sharing Wi-Fi by QR code. */
@Composable
fun NetworkExtras() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var dns by remember { mutableStateOf(Device.privateDns(ctx)) }
    var host by remember { mutableStateOf(if (dns.contains('.')) dns else "dns.google") }
    SetSection("Private DNS")
    GlassSegments(
        listOf("off" to "Off", "auto" to "Automatic", "host" to "Provider"),
        when { dns == "off" -> "off"; dns == "auto" -> "auto"; else -> "host" },
        Modifier.widthIn(max = 520.dp), height = 44.dp, textSize = 14f,
    ) { m ->
        val v = if (m == "host") host else m
        dns = v; bg { Device.setPrivateDns(v) }
    }
    if (dns != "off" && dns != "auto") {
        Gap(h = Grid.s)
        VField(host, { host = it.trim() }, Modifier.widthIn(max = 520.dp).fillMaxWidth(), placeholder = "dns.google",
            onDone = { dns = host; bg { Device.setPrivateDns(host) } })
    }
    Gap(h = Grid.l)
    SetSection("VPN")
    val vpns = remember {
        ctx.packageManager.queryIntentServices(Intent("android.net.VpnService"), 0)
            .map { it.serviceInfo.packageName }.distinct()
            .map { it to (runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(it, 0)).toString() }.getOrDefault(it)) }
    }
    var always by remember { mutableStateOf(runCatching { android.provider.Settings.Secure.getString(ctx.contentResolver, "always_on_vpn_app") }.getOrNull()) }
    if (vpns.isEmpty()) SettingRow("No VPN apps installed") {}
    vpns.forEach { (pkg, label) ->
        SettingRow(label, if (always == pkg) "Always on" else null) {
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(Grid.s)) {
                VButton("Open", accent = V.Muted) { launchApp(ctx, (ctx.applicationContext as com.andysocial.voidos.VoidApp).store, pkg) }
                VButton(if (always == pkg) "Always on: off" else "Always on", accent = V.Teal) {
                    val on = always != pkg
                    always = if (on) pkg else null
                    bg { Su.run(if (on) "settings put secure always_on_vpn_app ${Su.q(pkg)}" else "settings delete secure always_on_vpn_app") }
                }
            }
        }
    }
    Gap(h = Grid.l)
    SetSection("Share Wi-Fi")
    var qr by remember { mutableStateOf<String?>(null) }
    var ssid by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        ssid = runCatching {
            @Suppress("DEPRECATION")
            ctx.applicationContext.getSystemService(WifiManager::class.java).connectionInfo?.ssid?.trim('"')
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }
    if (qr == null) {
        SettingRow(ssid ?: "Not on Wi-Fi") {
            if (ssid != null) VButton("Show QR code", accent = V.Teal) {
                val s = ssid!!
                scope.launch {
                    val pass = withContext(Dispatchers.IO) { Device.wifiPassword(s) }
                    qr = wifiQrText(s, pass, pass != null)
                }
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(Grid.s)) {
            QrImage(qr!!, Modifier.size(260.dp))
            VText("Scan with a phone camera to join \"${ssid ?: ""}\".", size = Type.label, color = V.Muted)
            VButton("Hide", accent = V.Muted) { qr = null }
        }
    }
}

/** Storage cards and USB drives: eject them, and choose what USB does with a computer. */
@Composable
fun StorageExtras() {
    val ctx = LocalContext.current
    var vols by remember { mutableStateOf(Device.volumes(ctx)) }
    var usb by remember { mutableStateOf("none") }
    SetSection("Cards and drives")
    if (vols.isEmpty()) SettingRow("No microSD card or USB drive") {}
    vols.forEach { v ->
        SettingRow(v.name, "${fmtGb(v.free)} free of ${fmtGb(v.total)}") {
            VButton("Eject", accent = V.Muted) { v.id?.let { id -> bg { Device.eject(id) }; vols = vols - v } }
        }
    }
    Gap(h = Grid.m)
    SettingRow("USB with a computer") {}
    GlassSegments(
        listOf("none" to "Charge only", "mtp" to "Files", "ptp" to "Photos"), usb,
        Modifier.widthIn(max = 520.dp), height = 44.dp, textSize = 14f,
    ) { m -> usb = m; bg { Device.usbMode(m) } }
}

private fun fmtGb(b: Long) = if (b >= 1L shl 30) "%.1f GB".format(b / (1L shl 30).toDouble()) else "${b / (1L shl 20)} MB"

/** Apps: stop, clear or remove any app, without Android's app screens. */
@Composable
fun AppsExtras(store: Store) {
    val ctx = LocalContext.current
    val all = remember { AppsRepo.all(ctx).sortedBy { it.label.lowercase() } }
    var open by remember { mutableStateOf<String?>(null) }
    SetSection("Apps")
    all.forEach { e ->
        SettingRow(e.label) {
            VButton(if (open == e.pkg) "Done" else "Manage", accent = V.Muted) { open = if (open == e.pkg) null else e.pkg }
        }
        if (open == e.pkg) {
            GlassActions(
                listOf(
                    GlassAction("Stop") { bg { Su.run("am force-stop ${Su.q(e.pkg)}") } },
                    GlassAction("Clear data", warn = true) { bg { Su.run("pm clear ${Su.q(e.pkg)}") } },
                    GlassAction("Remove", warn = true) { bg { Su.run("pm uninstall --user 0 ${Su.q(e.pkg)}") }; open = null },
                ),
                Modifier.widthIn(max = 520.dp), height = 44.dp,
            )
            Gap(h = Grid.s)
        }
    }
    if (store.setupDone) Unit
}

/** Keyboard, sensors and power. */
@Composable
fun TabletExtras(store: Store, act: MainActivity) {
    val ctx = LocalContext.current
    val imes = remember {
        runCatching {
            ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java).enabledInputMethodList
                .map { it.id to it.loadLabel(ctx.packageManager).toString() }
        }.getOrDefault(emptyList())
    }
    var ime by remember { mutableStateOf(runCatching { android.provider.Settings.Secure.getString(ctx.contentResolver, "default_input_method") }.getOrNull()) }
    if (imes.size > 1) {
        SetSection("Keyboard")
        GlassSegments(imes, ime, Modifier.widthIn(max = 520.dp), height = 44.dp, textSize = 14f) { id ->
            ime = id; bg { Su.run("ime set ${Su.q(id)}") }
        }
        Gap(h = Grid.l)
    }
    SetSection("Sensors")
    SettingRow("Wave to wake", if (Sensors.has(ctx, android.hardware.Sensor.TYPE_PROXIMITY)) null else "No proximity sensor") {
        VToggle(store.waveWake) { store.set("waveWake", it); Sensors.sync(ctx, store) }
    }
    SettingRow("Face down to sleep") { VToggle(store.faceDown) { store.set("faceDown", it); Sensors.sync(ctx, store) } }
    SettingRow("Pick up to wake") { VToggle(store.pickUp) { store.set("pickUp", it); Sensors.sync(ctx, store) } }
    Gap(h = Grid.l)
    SetSection("Power")
    GlassActions(
        listOf(
            GlassAction("Restart") { bg { Device.reboot("") } },
            GlassAction("Power off", warn = true) { bg { Su.run("reboot -p") } },
            GlassAction("Recovery", warn = true) { bg { Device.reboot("recovery") } },
        ),
        Modifier.widthIn(max = 520.dp), height = 44.dp,
    )
    if (act.isFinishing) Unit
}

/** The page that used to list Android's settings: now every one of them lives here. */
@Composable
fun SystemSettingsPane(store: Store, act: MainActivity) {
    Head("System")
    TabletExtras(store, act); Gap(h = Grid.xl)
    AppsExtras(store)
}

@Suppress("unused")
private fun Context.noop() = Unit
