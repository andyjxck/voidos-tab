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
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(k, Modifier.width(170.dp))
        VText(v, size = 14.sp, color = col, font = FontFamily.Default, maxLines = 2, modifier = Modifier.weight(1f))
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
    Head("Home & lock screen", "How VoidOS looks and moves day to day.")
    SettingRow("Tablet name", "Shown on the lock screen.") {}
    Row(verticalAlignment = Alignment.CenterVertically) {
        VField(name, { name = it.take(16) }, Modifier.weight(1f), placeholder = "Home", onDone = { store.setHubName(name.ifBlank { "Home" }) })
        Gap(w = 8.dp)
        VButton("Save") { store.setHubName(name.ifBlank { "Home" }) }
    }
    SettingRow("Swipe between screens", "Swipe left or right on a screen to move to the next one. The edge wheels work either way.") {
        VToggle(store.swipeScreens) { store.set("swipeScreens", it) }
    }
    SettingRow("Living background", "The slow coloured light behind every screen. Off holds it still.") {
        VToggle(store.motion) { store.set("motion", it) }
    }
    SettingRow("Edge touch debug", "Shows where the edge wheels listen and what the last touch did. For checking the wheels.") {
        VToggle(store.edgeDebug, V.Coral) { store.set("edgeDebug", it) }
    }
    SettingRow("App icons", "Void: VoidOS line icons for apps it knows, the rest greyed. Letters: initials. Colour: each app's own icon.") {}
    GlassSegments(listOf("void" to "Void", "letter" to "Letters", "colour" to "Colour"), store.iconStyle, Modifier.widthIn(max = 450.dp)) { store.setIconStyle(it) }
    SettingRow("Home widgets", "Tap + on Home to add the weather, music, the list or a note. Hold one to drag it, pinch to stretch or turn it.") {
        VButton("Clear all", accent = V.Muted, enabled = store.widgets.isNotEmpty()) { store.saveWidgets(emptyList()) }
    }
    SettingRow("Fahrenheit", "Weather in °F instead of °C.") { VToggle(store.tempF) { store.set("tempF", it) } }
    SettingRow("Timer alarm sound", "Loops until you press Stop. Uses the Alarms volume.") { VToggle(store.timerSound, V.Orange) { store.set("timerSound", it) } }
    Gap(h = 18.dp)
    Head("Lock screen", "The VoidOS lock screen shows whenever the screen turns off and on again. Android's own lock screen is switched off by \"No lock screen\" in VoidOS rules.")
    SettingRow("VoidOS lock screen", "Clock, weather, what's playing, timers and notifications. Swipe up to open.") {
        VToggle(store.voidLock) { store.set("voidLock", it) }
    }
    SettingRow("PIN to unlock", if (store.hasPin) "Asks for your lock screen PIN after the swipe." else "Set a lock screen PIN first (VoidOS rules).") {
        VToggle(store.lockNeedsPin, V.Coral, enabled = store.hasPin) { store.set("lockNeedsPin", it) }
    }
    SettingRow("Notifications on the lock screen") { VToggle(store.lockNotices) { store.set("lockNotices", it) } }
    SettingRow("Show it now") { VButton("Lock", accent = V.Teal) { LockState.locked.value = true } }
    Gap(h = 18.dp)
    Head("Media + notifications access", "Lets VoidOS show and control what's playing in YouTube, Spotify and other apps, and show notifications on the lock screen.")
    SettingRow("Access", if (access.value) "On" else "Off. Tap Open, find VoidOS, switch it on. Or from the Mac: ${VoidListener.ADB_ALLOW}") {
        VButton("Open", accent = V.Purple) { open(ctx, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
    }
    if (act.isFinishing) Unit
}

// --------------------------------------------------------------- do not disturb

@Composable
fun QuietPane(act: MainActivity) {
    val ctx = LocalContext.current
    val nm = remember { ctx.getSystemService(NotificationManager::class.java) }
    var granted by remember { mutableStateOf(nm.isNotificationPolicyAccessGranted) }
    var filter by remember { mutableIntStateOf(nm.currentInterruptionFilter) }
    Head("Do not disturb", if (granted) "Choose what can make a sound." else "VoidOS needs Do Not Disturb access. Tap Allow, find VoidOS, switch it on, then come back.")
    if (!granted) {
        GlassActions(
            listOf(
                GlassAction("Allow") { open(ctx, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) },
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
    Head("Date + time", java.text.SimpleDateFormat("EEEE d MMMM yyyy, HH:mm:ss", Locale.UK).format(java.util.Date(now)))
    SettingRow("24-hour clock") { VToggle(store.use24h) { store.set("use24h", it) } }
    SettingRow("Set time automatically", if (owner) "From the network." else "Needs device owner.") {
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
                if (ok) { zone = z; msg = null } else msg = "Android didn't allow VoidOS to change the time zone. Use Android settings > Date & time."
            }
        }
    }
    msg?.let { Gap(h = 8.dp); VText(it, size = 12.sp, color = V.Coral, font = FontFamily.Default) }
    Gap(h = 12.dp)
    VButton("Android date + time settings", accent = V.Muted) { open(ctx, Settings.ACTION_DATE_SETTINGS) }
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
        Box(Modifier.weight(1f).height(26.dp).clip(OctS).background(V.Panel2).border(1.dp, V.Line, OctS)) {
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
    Gap(h = 10.dp)
    VText("For a hub that lives on the charger: Desk mode (VoidOS rules) keeps the screen on while charging.", size = 12.sp, color = V.Muted, font = FontFamily.Default)
    Gap(h = 10.dp)
    VButton("Android battery settings", accent = V.Muted) { open(ctx, Intent.ACTION_POWER_USAGE_SUMMARY) }
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
    Head("Network", "Handy when setting up smart lights later: they need to be on this same network.")
    info.forEach { (k, v) -> InfoRow(k, v) }
    Gap(h = 10.dp)
    VButton("Android network settings", accent = V.Muted) { open(ctx, Settings.ACTION_WIRELESS_SETTINGS) }
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
            Gap(w = 10.dp)
            VText("free of ${gb(d.totalBytes)}", size = 12.sp, color = V.Muted)
        }
        Gap(h = 8.dp)
        Box(Modifier.fillMaxWidth().height(16.dp).clip(OctS).background(V.Panel2)) {
            Box(Modifier.fillMaxWidth(frac).height(16.dp).background(if (frac > 0.9f) V.Coral else V.Teal))
        }
    }
    Gap(h = 14.dp)
    InfoRow("Apps you can open", "${apps.size}")
    InfoRow("Allowed in lock mode", "${store.allowed.size}")
    InfoRow("Starred", "${store.favs.size}")
    InfoRow("Shopping list", "${store.shopping.size} items")
    InfoRow("Notes", "${store.notes.size}")
    Gap(h = 10.dp)
    GlassActions(
        listOf(
            GlassAction("Storage") { open(ctx, Settings.ACTION_INTERNAL_STORAGE_SETTINGS) },
            GlassAction("Manage apps") { open(ctx, Settings.ACTION_APPLICATION_SETTINGS) },
            GlassAction("Play Store") { runQuickPkg(ctx, store, "com.android.vending") },
        ),
        Modifier.widthIn(max = 560.dp),
    )
}

private fun runQuickPkg(ctx: Context, store: Store, pkg: String) { launchApp(ctx, store, pkg) }

// ------------------------------------------------------------- Android settings

@Composable
fun AndroidPane(act: MainActivity) {
    val ctx = LocalContext.current
    Head("Android settings", "Straight to the right page of Android's own settings. Press Home to come back to VoidOS.")
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

/** What the tablet shows at each stage, and who draws it. Pure, so it can be unit-tested. */
object BootChain {
    data class Stage(val name: String, val value: String, val ok: Boolean)

    fun stages(bootModule: String?, androidLockSecure: Boolean, androidLockOff: Boolean, voidLock: Boolean, owner: Boolean): List<Stage> = listOf(
        Stage("Power-on logo", "Logo partition: flash a VoidOS logo", false),
        Stage("Boot animation", if (bootModule != null) "VoidOS" else "Android (no module)", bootModule != null),
        Stage("Lock screen", when {
            androidLockSecure -> "Android: remove the Android PIN"
            voidLock && androidLockOff -> "VoidOS"
            voidLock -> "VoidOS, Android's behind it"
            androidLockOff -> "None"
            else -> "Android"
        }, voidLock && androidLockOff && !androidLockSecure),
        Stage("Home screen", if (owner) "VoidOS (device owner)" else "VoidOS if set as Home", owner),
    )
}

@Composable
fun RootPane(store: Store? = null) {
    val ctx = LocalContext.current
    val bl = RootInfo.prop("ro.boot.verifiedbootstate")
    val locked = RootInfo.prop("ro.boot.flash.locked")
    val mod = RootInfo.moduleVersion()
    val sysMod = RootInfo.systemModuleVersion()
    val su = RootInfo.hasSu()
    val owner = com.andysocial.voidos.Policy.isOwner(ctx)
    Head("Boot & root", "VoidOS draws the lock and home screens itself. The boot animation and VoidOS as a system app need root (Magisk modules); the power-on logo needs the logo partition flashed. root/README.md in the voidos-tab repo has every step. Unlocking the bootloader wipes the tablet.")
    Label("What the tablet shows")
    Gap(h = 6.dp)
    BootChain.stages(
        bootModule = mod,
        androidLockSecure = RootInfo.androidLockSecure(ctx),
        androidLockOff = owner && (store?.noLockScreen ?: true),
        voidLock = store?.voidLock ?: true,
        owner = owner,
    ).forEach { InfoRow(it.name, it.value, if (it.ok) V.Green else V.Yellow) }
    Gap(h = 12.dp)
    Label("Firmware and root")
    Gap(h = 6.dp)
    InfoRow("Firmware build", Build.DISPLAY)
    InfoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    InfoRow("Security patch", Build.VERSION.SECURITY_PATCH)
    InfoRow("Bootloader", when { locked == "0" || bl == "orange" -> "Unlocked"; locked == "1" -> "Locked"; else -> "Unknown" },
        if (locked == "0" || bl == "orange") V.Green else V.Yellow)
    InfoRow("Root (Magisk)", if (su) "Yes" else "No", if (su) V.Green else V.Muted)
    InfoRow("VoidOS boot module", mod ?: "Not installed", if (mod != null) V.Green else V.Muted)
    val bs = RootInfo.bootAnimSize()
    InfoRow("Boot animation file", if (bs > 0) "%.1f MB".format(bs / 1e6) else "Not visible")
    InfoRow("VoidOS system module", sysMod ?: "Not installed", if (sysMod != null) V.Green else V.Muted)
    val sys = RootInfo.isSystemApp(ctx)
    InfoRow("VoidOS is a system app", if (sys) "Yes" else "No", if (sys) V.Green else V.Muted)
    InfoRow("Time zone control", if (RootInfo.canSetTimeZone(ctx)) "Yes" else if (Build.VERSION.SDK_INT >= 30 && owner) "Via device owner" else "No")
    Gap(h = 12.dp)
    Label("Read the firmware build from the Mac")
    Gap(h = 6.dp)
    VText("./adb shell getprop ro.build.display.id", size = 12.sp, color = V.Teal, font = FontFamily.Monospace)
}

