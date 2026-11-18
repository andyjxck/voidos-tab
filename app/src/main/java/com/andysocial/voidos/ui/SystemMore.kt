package com.andysocial.voidos.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andysocial.voidos.MainActivity
import com.andysocial.voidos.Policy
import com.andysocial.voidos.Store
import com.andysocial.voidos.VoidListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.NetworkInterface
import java.util.Locale
import java.util.TimeZone

@Composable
private fun InfoRow(k: String, v: String, col: Color = V.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(k, Modifier.width(170.dp))
        VText(v, size = Type.body, color = col, font = Sans, maxLines = 2, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Choices(options: List<Pair<String, String>>, selected: String, accent: Color = V.Teal, enabled: Boolean = true, onPick: (String) -> Unit) {
    GlassSegments(options, selected, Modifier.widthIn(max = (options.size * 150).dp), enabled = enabled, accent = accent, onPick = onPick)
}

private fun open(ctx: Context, action: String, data: android.net.Uri? = null): Boolean = runCatching {
    (ctx as? MainActivity)?.visitAndroid()
    ctx.startActivity(Intent(action).apply { if (data != null) setData(data) }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
}.getOrDefault(false)

// ------------------------------------------------------------------ Hub + lock

@Composable
fun HubPane(store: Store, act: MainActivity) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(store.hubName) }
    val access = remember { mutableStateOf(VoidListener.enabled(ctx)) }
    Head("Home & lock screen")
    SettingRow("Tablet name") {}
    Row(verticalAlignment = Alignment.CenterVertically) {
        VField(name, { name = it.take(16) }, Modifier.weight(1f), placeholder = "Home", onDone = { store.setHubName(name.ifBlank { "Home" }) })
        Gap(w = 8.dp)
        VButton("Save") { store.setHubName(name.ifBlank { "Home" }) }
    }
    SettingRow("Swipe between screens") {
        VToggle(store.swipeScreens) { store.set("swipeScreens", it) }
    }
    SettingRow("Edge wheels in other apps", if (com.andysocial.voidos.EdgeOverlay.allowed.value) null else "Needs permission") {
        VToggle(store.edgeInApps) { store.set("edgeInApps", it); com.andysocial.voidos.EdgeOverlay.sync(ctx, store) }
    }
    SettingRow("Tap sound") {
        VToggle(store.tapSound) { store.set("tapSound", it); if (it) com.andysocial.voidos.TapSound.play() }
    }
    SettingRow("Living background") {
        VToggle(store.motion) { store.set("motion", it) }
    }
    SettingRow("App icons") {}
    GlassSegments(listOf("glass" to "Glass", "void" to "Plain", "letter" to "Letters", "colour" to "Original"), store.iconStyle, Modifier.widthIn(max = 450.dp)) { store.setIconStyle(it) }
    SettingRow("Home widgets") {
        VButton("Clear all", accent = V.Muted, enabled = store.widgets.isNotEmpty()) { store.saveWidgets(emptyList()) }
    }
    SettingRow("Fahrenheit") { VToggle(store.tempF) { store.set("tempF", it) } }
    SettingRow("Timer alarm sound") { VToggle(store.timerSound, V.Orange) { store.set("timerSound", it) } }
    Gap(h = 16.dp)
    SetSection("Lock screen")
    SettingRow("VoidOS lock screen") {
        VToggle(store.voidLock) { store.set("voidLock", it) }
    }
    PinSettings(store)
    SettingRow("Notifications on the lock screen") { VToggle(store.lockNotices) { store.set("lockNotices", it) } }
    SettingRow("Show it now") { VButton("Lock", accent = V.Teal) { LockState.locked.value = true } }
    Gap(h = 16.dp)
    SetSection("Media and notifications")
    SettingRow("Access") {
        if (access.value) VText("On", size = Type.body, color = V.Muted)
        else VButton("Allow", accent = V.Purple) { Thread { com.andysocial.voidos.Stealth.grant(ctx) }.start() }
    }
    if (act.isFinishing) Unit
}

// ------------------------------------------------------------------ VoidOS PIN

private enum class PinStep { CHECK_OFF, CHECK_CHANGE, NEW, AGAIN }

/**
 * The VoidOS PIN: one code for the lock screen and for leaving VoidOS. Turning
 * it off or changing it asks for the current one first.
 */
@Composable
private fun PinSettings(store: Store) {
    var step by remember { mutableStateOf<PinStep?>(null) }
    var first by remember { mutableStateOf<String?>(null) }
    var len by remember { mutableStateOf(4) }
    var err by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<String?>(null) }
    SettingRow("Ask for PIN to unlock") {
        VToggle(store.lockNeedsPin, V.Coral) { on ->
            done = null
            if (on) store.set("lockNeedsPin", true) else { step = PinStep.CHECK_OFF; err = null }
        }
    }
    SettingRow("Change PIN", done) {
        VButton(if (step != null) "Cancel" else "Change", accent = V.Muted) {
            done = null; err = null; first = null
            step = if (step != null) null else PinStep.CHECK_CHANGE
        }
    }
    val s = step ?: return
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when (s) {
                PinStep.CHECK_OFF, PinStep.CHECK_CHANGE -> GlassPinPad(
                    title = "Enter your current PIN",
                    length = store.pinLength(),
                    check = { store.checkPin(it) },
                    keySize = 64.dp,
                ) {
                    if (s == PinStep.CHECK_OFF) { store.set("lockNeedsPin", false); step = null; done = null }
                    else step = PinStep.NEW
                }
                PinStep.NEW -> {
                    GlassSegments(listOf("4" to "4 digits", "6" to "6 digits"), len.toString(), Modifier.widthIn(max = 320.dp)) { len = it.toInt() }
                    Gap(h = 16.dp)
                    key(len) {
                        GlassPinPad(title = "Choose a new PIN", length = len, error = err, keySize = 64.dp) { p ->
                            first = p; err = null; step = PinStep.AGAIN
                        }
                    }
                }
                PinStep.AGAIN -> GlassPinPad(title = "Enter it again", length = len, error = err, keySize = 64.dp) { p ->
                    if (p == first) { store.setPin(p); step = null; first = null; done = "Changed. Use your new PIN to unlock and to leave VoidOS." }
                    else { first = null; err = "They didn't match. Choose again."; step = PinStep.NEW }
                }
            }
        }
    }
}

// --------------------------------------------------------------- do not disturb

@Composable
fun QuietPane(act: MainActivity) {
    val ctx = LocalContext.current
    val nm = remember { ctx.getSystemService(NotificationManager::class.java) }
    var granted by remember { mutableStateOf(nm.isNotificationPolicyAccessGranted) }
    var filter by remember { mutableIntStateOf(nm.currentInterruptionFilter) }
    Head("Do not disturb", if (granted) null else "Needs access")
    if (!granted) {
        GlassActions(
            listOf(
                GlassAction("Allow") { Thread { com.andysocial.voidos.Stealth.grant(ctx); granted = nm.isNotificationPolicyAccessGranted }.start() },
                GlassAction("I've done it") { granted = nm.isNotificationPolicyAccessGranted; filter = nm.currentInterruptionFilter },
            ),
            Modifier.widthIn(max = 420.dp),
        )
        return
    }
    GlassGroup {
        listOf(
            Triple(NotificationManager.INTERRUPTION_FILTER_ALL, "Everything", "Normal. All notifications make a sound."),
            Triple(NotificationManager.INTERRUPTION_FILTER_PRIORITY, "Priority only", "Alarms, timers and starred contacts only."),
            Triple(NotificationManager.INTERRUPTION_FILTER_ALARMS, "Alarms only", "Timers and alarms still ring. Everything else is silent."),
            Triple(NotificationManager.INTERRUPTION_FILTER_NONE, "Total silence", "Nothing makes a sound, timers included."),
        ).forEach { (f, title, detail) ->
            Item(title, detail, if (f == NotificationManager.INTERRUPTION_FILTER_ALL) G.BELL else G.MOON, selected = filter == f, onClick = {
                runCatching { nm.setInterruptionFilter(f) }; filter = nm.currentInterruptionFilter
            })
        }
    }
    if (act.isFinishing) Unit
}

// ------------------------------------------------------------------ date + time

private val zones = listOf(
    "Europe/London", "Europe/Dublin", "Europe/Paris", "Europe/Madrid", "Europe/Berlin", "Europe/Athens",
    "America/New_York", "America/Chicago", "America/Los_Angeles", "Asia/Dubai", "Asia/Tokyo", "Australia/Sydney",
)

@Composable
fun TimePane(store: Store, act: MainActivity) {
    val ctx = LocalContext.current
    val owner = Policy.isOwner(act)
    val now = rememberNow(1000)
    var zone by remember { mutableStateOf(TimeZone.getDefault().id) }
    var autoTime by remember { mutableStateOf(runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.AUTO_TIME) == 1 }.getOrDefault(true)) }
    var msg by remember { mutableStateOf<String?>(null) }
    Head("Date + time", java.text.SimpleDateFormat(if (store.use24h) "EEEE d MMMM, HH:mm" else "EEEE d MMMM, h:mm a", Locale.UK).format(java.util.Date(now)))
    SettingRow("Clock") {}
    GlassSegments(listOf("12" to "12-hour", "24" to "24-hour"), if (store.use24h) "24" else "12", Modifier.widthIn(max = 360.dp)) { store.set("use24h", it == "24") }
    Gap(h = 8.dp)
    SettingRow("Set time automatically") {
        VToggle(autoTime, enabled = owner) { on ->
            runCatching { Policy.dpm(ctx).setAutoTimeRequired(Policy.admin(ctx), on) }
            runCatching { Policy.dpm(ctx).setGlobalSetting(Policy.admin(ctx), Settings.Global.AUTO_TIME, if (on) "1" else "0") }
            autoTime = runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.AUTO_TIME) == 1 }.getOrDefault(on)
        }
    }
    SettingRow("Time zone", zone) {}
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.widthIn(max = 620.dp)) {
        zones.chunked(3).forEach { row ->
            GlassSegments(row.map { it to it.substringAfter('/').replace('_', ' ') }, zone, height = 46.dp, textSize = 14f) { z ->
                val ok = Policy.setTimeZone(ctx, z)
                if (ok) { zone = z; msg = null } else msg = "Couldn't change the time zone."
            }
        }
    }
    msg?.let { Gap(h = 8.dp); VText(it, size = Type.caption, color = V.Coral, font = Sans) }
}

// ---------------------------------------------------------------------- battery

@Composable
fun BatteryPane() {
    val ctx = LocalContext.current
    val now = rememberNow(5000)
    val i = remember(now / 5000) { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
    val bm = remember { ctx.getSystemService(BatteryManager::class.java) }
    val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val pct = if (level >= 0) level * 100 / scale.coerceAtLeast(1) else -1
    val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val plug = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
    val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
    val volt = (i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000f
    val health = i?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0
    val currentMa = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrDefault(0).let { if (kotlin.math.abs(it) > 20_000) it / 1000 else it }
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    Head("Battery", if (charging) "Charging" else "On battery")
    Row(verticalAlignment = Alignment.CenterVertically) {
        VText(if (pct >= 0) "$pct%" else "--", size = 64.sp, color = V.White, font = SansLight)
        Gap(w = 20.dp)
        Box(Modifier.weight(1f).height(26.dp).pane(Pill)) {
            Box(Modifier.fillMaxWidth((pct.coerceIn(0, 100)) / 100f).height(26.dp).background(if (pct < 20) V.Coral else if (charging) V.Green else V.Teal))
        }
    }
    Gap(h = 12.dp)
    InfoRow("Plugged in", when (plug) { BatteryManager.BATTERY_PLUGGED_AC -> "Charger"; BatteryManager.BATTERY_PLUGGED_USB -> "USB"; BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"; else -> "No" })
    InfoRow("Temperature", "%.1f °C".format(temp), if (temp > 42) V.Coral else V.Text)
    InfoRow("Voltage", "%.2f V".format(volt))
    if (currentMa != 0) InfoRow("Current", "$currentMa mA")
    InfoRow("Health", when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"; BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"; BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"; BatteryManager.BATTERY_HEALTH_COLD -> "Cold"; else -> "Unknown"
    })
    Gap(h = 8.dp)
    VButton("Battery usage", accent = V.Muted) { open(ctx, Intent.ACTION_POWER_USAGE_SUMMARY) }
}

// ---------------------------------------------------------------------- network

@Suppress("DEPRECATION")
@Composable
fun NetworkPane() {
    val ctx = LocalContext.current
    val info by produceState<List<Pair<String, String>>>(emptyList()) {
        while (isActive) {
            value = withContext(Dispatchers.IO) {
                val out = mutableListOf<Pair<String, String>>()
                val cm = ctx.getSystemService(ConnectivityManager::class.java)
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                out += "Online" to if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) "Yes" else if (caps != null) "Connected, no internet confirmed" else "No"
                val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
                val wi = wm.connectionInfo
                if (wi != null && wi.networkId != -1) {
                    out += "Wi-Fi" to wi.ssid.trim('"')
                    out += "Signal" to "${WifiManager.calculateSignalLevel(wi.rssi, 5)}/4  (${wi.rssi} dBm)"
                    out += "Link speed" to "${wi.linkSpeed} Mbps"
                    out += "Band" to if (wi.frequency > 4900) "5 GHz" else "2.4 GHz"
                }
                val lp = cm.getLinkProperties(cm.activeNetwork)
                lp?.linkAddresses?.map { it.address }?.filterIsInstance<java.net.Inet4Address>()?.firstOrNull()?.let { out += "IP address" to it.hostAddress.orEmpty() }
                lp?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress?.let { out += "Router" to it }
                lp?.dnsServers?.takeIf { it.isNotEmpty() }?.let { out += "DNS" to it.joinToString(", ") { a -> a.hostAddress.orEmpty() } }
                runCatching { NetworkInterface.getByName("wlan0")?.hardwareAddress }.getOrNull()?.let { mac ->
                    out += "Wi-Fi MAC" to mac.joinToString(":") { "%02X".format(it) }
                }
                out
            }
            delay(4000)
        }
    }
    Head("Network")
    info.forEach { (k, v) -> InfoRow(k, v) }
}

// ---------------------------------------------------------------- storage + apps

@Composable
fun StoragePane(store: Store) {
    val ctx = LocalContext.current
    val disk = remember { runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull() }
    val gb = { b: Long -> "%.1f GB".format(b / 1_073_741_824.0) }
    val apps = remember { AppsRepo.all(ctx) }
    Head("Storage + apps")
    disk?.let { d ->
        val used = d.totalBytes - d.availableBytes
        val frac = used.toFloat() / d.totalBytes.coerceAtLeast(1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            VText(gb(d.availableBytes), size = 40.sp, color = V.White, font = SansLight)
            Gap(w = 8.dp)
            VText("free of ${gb(d.totalBytes)}", size = Type.caption, color = V.Muted)
        }
        Gap(h = 8.dp)
        Box(Modifier.fillMaxWidth().height(16.dp).clip(OctS).background(V.Panel2)) {
            Box(Modifier.fillMaxWidth(frac).height(16.dp).background(if (frac > 0.9f) V.Coral else V.Teal))
        }
    }
    Gap(h = 16.dp)
    InfoRow("Apps you can open", "${apps.size}")
    InfoRow("Allowed in lock mode", "${store.allowed.size}")
    InfoRow("Starred", "${store.favs.size}")
    InfoRow("Shopping list", "${store.shopping.size} items")
    InfoRow("Notes", "${store.notes.size}")
    Gap(h = 8.dp)
    GlassActions(
        listOf(
            GlassAction("Play Store") { runQuickPkg(ctx, store, "com.android.vending") },
        ),
        Modifier.widthIn(max = 560.dp),
    )
}

private fun runQuickPkg(ctx: Context, store: Store, pkg: String) { launchApp(ctx, store, pkg) }

// ------------------------------------------------------------- system settings

@Composable
fun AndroidPane(act: MainActivity) {
    val ctx = LocalContext.current
    Head("System settings")
    listOf(
        Settings.ACTION_SETTINGS to "All settings",
        Settings.ACTION_WIFI_SETTINGS to "Wi-Fi",
        Settings.ACTION_BLUETOOTH_SETTINGS to "Bluetooth",
        Settings.ACTION_DISPLAY_SETTINGS to "Display",
        Settings.ACTION_SOUND_SETTINGS to "Sound",
        Settings.ACTION_APP_NOTIFICATION_SETTINGS to "Notifications",
        Settings.ACTION_LOCATION_SOURCE_SETTINGS to "Location",
        Settings.ACTION_SECURITY_SETTINGS to "Security",
        Settings.ACTION_SYNC_SETTINGS to "Accounts",
        Settings.ACTION_LOCALE_SETTINGS to "Language",
        Settings.ACTION_INPUT_METHOD_SETTINGS to "Keyboard",
        Settings.ACTION_ACCESSIBILITY_SETTINGS to "Accessibility",
        Settings.ACTION_PRIVACY_SETTINGS to "Privacy",
        Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS to "Developer options",
        Settings.ACTION_DEVICE_INFO_SETTINGS to "About tablet",
        Settings.ACTION_HOME_SETTINGS to "Home app",
    ).let { rows ->
        GlassGroup {
            rows.forEach { (action, label) -> Item(label, chevron = true, onClick = { if (!open(ctx, action)) open(ctx, Settings.ACTION_SETTINGS) }) }
        }
    }
    if (act.isFinishing) Unit
}

// ------------------------------------------------------------------ boot + root

object RootInfo {
    fun prop(k: String): String = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("get", String::class.java).invoke(null, k) as String
    }.getOrDefault("")

    fun hasSu() = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su").any { File(it).exists() }
    private fun marker(name: String): String? = runCatching { File("/system/etc/$name").readText().trim() }.getOrNull()
    /** The VoidOS boot-animation Magisk module drops this marker into /system/etc. */
    fun moduleVersion(): String? = marker("voidos_boot.txt")
    /** The VoidOS system-app Magisk module (VoidOS in /system/priv-app). */
    fun systemModuleVersion(): String? = marker("voidos_system.txt")
    fun bootAnimSize(): Long = listOf("/system/media/bootanimation.zip", "/product/media/bootanimation.zip", "/oem/media/bootanimation.zip")
        .map { File(it) }.firstOrNull { it.exists() }?.length() ?: -1

    /** Installed as part of the system (priv-app), directly or as an update of the system copy. */
    fun isSystemApp(c: Context): Boolean = (c.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
    fun canSetTimeZone(c: Context): Boolean =
        c.checkSelfPermission(android.Manifest.permission.SET_TIME_ZONE) == android.content.pm.PackageManager.PERMISSION_GRANTED
    /** True when Android's own lock (a PIN, pattern or password set in Android Settings) is in force. */
    fun androidLockSecure(c: Context): Boolean =
        runCatching { c.getSystemService(android.app.KeyguardManager::class.java).isKeyguardSecure }.getOrDefault(false)
}


