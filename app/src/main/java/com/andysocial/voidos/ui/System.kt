package com.andysocial.voidos.ui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andysocial.voidos.BuildConfig
import com.andysocial.voidos.MainActivity
import com.andysocial.voidos.Policy
import com.andysocial.voidos.Store
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private enum class Tab(val label: String, val g: G) {
    WIFI("Wi-Fi", G.WIFI), BT("Bluetooth", G.BT), DISPLAY("Screen + sound", G.SUN), HUB("Hub + lock", G.FACE),
    QUIET("Do not disturb", G.MOON), TIME("Date + time", G.TIMER), BATTERY("Battery", G.BATTERY), NET("Network", G.GLOBE),
    STORAGE("Storage + apps", G.APPS), ANDROID("Android settings", G.SYSTEM), ROOT("Boot + root", G.POWER),
    VOID("VoidOS rules", G.LOCK), ABOUT("About", G.INFO)
}

@Composable
fun SystemScreen(store: Store, act: MainActivity) {
    var tab by rememberSaveable { mutableStateOf(Tab.WIFI) }
    val owner = Policy.isOwner(act)
    val pane: @Composable (Modifier) -> Unit = { m ->
        VPanel(m) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                when (tab) {
                    Tab.WIFI -> WifiPane(act)
                    Tab.BT -> BtPane()
                    Tab.DISPLAY -> DisplayPane(store, act)
                    Tab.HUB -> HubPane(store, act)
                    Tab.QUIET -> QuietPane(act)
                    Tab.TIME -> TimePane(store, act)
                    Tab.BATTERY -> BatteryPane()
                    Tab.NET -> NetworkPane()
                    Tab.STORAGE -> StoragePane(store)
                    Tab.ANDROID -> AndroidPane(act)
                    Tab.ROOT -> RootPane()
                    Tab.VOID -> VoidPane(store, act)
                    Tab.ABOUT -> AboutPane(act)
                }
            }
        }
    }
    if (LocalPortrait.current) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tab.entries.forEach { t -> Chip(t.label, t == tab, V.Teal, glyph = t.g) { tab = t } }
            }
            Gap(h = 12.dp)
            pane(Modifier.weight(1f).fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.width(230.dp).fillMaxHeight()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tab.entries.forEach { t ->
                        val sel = t == tab
                        Row(
                            Modifier.fillMaxWidth().clip(OctS).background(if (sel) V.Teal.copy(alpha = 0.12f) else V.Panel)
                                .border(1.dp, if (sel) V.Teal else V.Line, OctS).clickable { tab = t }.padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Glyph(t.g, if (sel) V.Teal else V.Muted, Modifier.size(22.dp))
                            Gap(w = 12.dp)
                            VText(t.label.uppercase(), size = 11.sp, color = if (sel) V.Teal else V.Text, spacing = 1.sp, maxLines = 1)
                        }
                    }
                }
                Gap(h = 8.dp)
                VText(if (owner) "DEVICE OWNER" else "NOT DEVICE OWNER", size = 10.sp, color = if (owner) V.Green else V.Yellow, spacing = 1.sp)
            }
            pane(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
internal fun Head(title: String, sub: String? = null) {
    VText(title.uppercase(), size = 20.sp, color = V.White, spacing = 2.sp)
    if (sub != null) { Gap(h = 4.dp); VText(sub, size = 12.sp, color = V.Muted, font = FontFamily.Default, lineHeight = 17.sp) }
    Gap(h = 14.dp)
}

// ---------------------------------------------------------------------- Wi-Fi

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
@Composable
private fun WifiPane(act: MainActivity) {
    val ctx = LocalContext.current
    val wm = remember { ctx.applicationContext.getSystemService(WifiManager::class.java) }
    var enabled by remember { mutableStateOf(wm.isWifiEnabled) }
    var results by remember { mutableStateOf<List<ScanResult>>(emptyList()) }
    var current by remember { mutableStateOf("") }
    var joining by remember { mutableStateOf<ScanResult?>(null) }
    var pass by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    val owner = Policy.isOwner(act)

    fun refresh() {
        enabled = wm.isWifiEnabled
        current = wm.connectionInfo?.takeIf { it.networkId != -1 }?.ssid?.trim('"')?.takeIf { it != "<unknown ssid>" } ?: ""
        results = (runCatching { wm.scanResults }.getOrNull() ?: emptyList())
            .filter { !it.SSID.isNullOrBlank() }.groupBy { it.SSID }.map { (_, v) -> v.maxBy { it.level } }
            .sortedByDescending { it.level }
    }

    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = refresh() }
        val f = IntentFilter().apply {
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION); addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION); addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
        ctx.registerReceiver(r, f)
        onDispose { runCatching { ctx.unregisterReceiver(r) } }
    }
    LaunchedEffect(Unit) { while (isActive) { refresh(); runCatching { wm.startScan() }; delay(15_000) } }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Head("Wi-Fi", if (current.isNotEmpty()) "Connected to $current" else if (enabled) "Not connected" else "Off")
        }
        VToggle(enabled, enabled = true) { on ->
            val ok = runCatching { wm.setWifiEnabled(on) }.getOrDefault(false)
            if (!ok) msg = if (owner) "Android refused that." else "VoidOS needs to be device owner to switch Wi-Fi."
            enabled = wm.isWifiEnabled
        }
    }
    msg?.let { VText(it, size = 12.sp, color = V.Coral, font = FontFamily.Default); Gap(h = 8.dp) }

    joining?.let { net ->
        VPanel(Modifier.fillMaxWidth(), accent = V.Teal, padding = 14.dp) {
            VText("JOIN ${net.SSID.uppercase()}", size = 14.sp, color = V.Teal, spacing = 1.sp)
            Gap(h = 10.dp)
            if (isSecured(net)) VField(pass, { pass = it }, Modifier.fillMaxWidth(), placeholder = "Password", password = true)
            Gap(h = 10.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VButton("Connect", accent = V.Teal, filled = true) {
                    msg = connect(wm, net, pass)
                    joining = null; pass = ""
                }
                VButton("Cancel", accent = V.Muted) { joining = null; pass = "" }
            }
        }
        Gap(h = 12.dp)
    }

    if (enabled && results.isEmpty()) VText("SCANNING", size = 12.sp, color = V.Muted, spacing = 2.sp)
    results.forEach { r ->
        val isCur = r.SSID == current
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(OctS).background(if (isCur) V.Teal.copy(alpha = 0.10f) else V.Panel2)
                .border(1.dp, if (isCur) V.Teal else V.Line, OctS).clickable(enabled = !isCur) { joining = r; msg = null }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val bars = WifiManager.calculateSignalLevel(r.level, 4)
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                for (i in 0..3) Box(Modifier.size(5.dp, (6 + i * 4).dp).background(if (i <= bars) V.Teal else V.Line))
            }
            Gap(w = 14.dp)
            VText(r.SSID, size = 15.sp, color = V.Text, font = FontFamily.Default, modifier = Modifier.weight(1f), maxLines = 1)
            VText(if (isCur) "CONNECTED" else if (isSecured(r)) "LOCKED" else "OPEN", size = 10.sp, color = if (isCur) V.Teal else V.Muted, spacing = 1.sp)
        }
    }
}

private fun isSecured(r: ScanResult) = listOf("WEP", "PSK", "EAP", "SAE").any { r.capabilities.contains(it) }

/** Device owners are exempt from Android 10's ban on apps adding Wi-Fi networks. */
@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
private fun connect(wm: WifiManager, r: ScanResult, pass: String): String {
    return runCatching {
        val conf = WifiConfiguration().apply {
            SSID = "\"${r.SSID}\""
            when {
                r.capabilities.contains("SAE") && !r.capabilities.contains("PSK") -> {
                    allowedKeyManagement.set(8 /* SAE */); preSharedKey = "\"$pass\""
                }
                r.capabilities.contains("PSK") -> preSharedKey = "\"$pass\""
                r.capabilities.contains("WEP") -> {
                    wepKeys[0] = "\"$pass\""; wepTxKeyIndex = 0
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED)
                }
                r.capabilities.contains("EAP") -> return "Work/school (EAP) Wi-Fi needs Android settings."
                else -> allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            }
        }
        val existing = wm.configuredNetworks?.firstOrNull { it.SSID == conf.SSID }
        existing?.let { wm.removeNetwork(it.networkId) }
        val id = wm.addNetwork(conf)
        if (id == -1) return "Android refused the network. VoidOS must be device owner for this."
        wm.disconnect()
        wm.enableNetwork(id, true)
        wm.reconnect()
        "Connecting to ${r.SSID}"
    }.getOrElse { "Couldn't connect: ${it.message}" }
}

// ------------------------------------------------------------ screen + sound

@Composable
private fun DisplayPane(store: Store, act: MainActivity) {
    val ctx = LocalContext.current
    val owner = Policy.isOwner(act)
    val cr = ctx.contentResolver
    var bright by remember { mutableFloatStateOf(runCatching { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)) }
    var auto by remember { mutableStateOf(runCatching { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE) == 1 }.getOrDefault(false)) }
    var timeout by remember { mutableIntStateOf(runCatching { Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrDefault(60_000)) }
    val am = remember { ctx.getSystemService(AudioManager::class.java) }

    fun sys(key: String, v: String) = runCatching { Policy.dpm(ctx).setSystemSetting(Policy.admin(ctx), key, v) }.isSuccess

    Head("Screen", if (owner) null else "Brightness and timeout need VoidOS to be device owner.")
    SettingRow("Brightness") {}
    VSlider(bright, Modifier.fillMaxWidth(), accent = V.Sun) {
        bright = it
        if (auto) { auto = false; sys(Settings.System.SCREEN_BRIGHTNESS_MODE, "0") }
        sys(Settings.System.SCREEN_BRIGHTNESS, (8 + it * 247).toInt().toString())
        // instant feedback on this window too
        act.window.attributes = act.window.attributes.apply { screenBrightness = (0.03f + it * 0.97f) }
    }
    SettingRow("Auto brightness", "Let the light sensor decide.") {
        VToggle(auto, V.Sun, enabled = owner) {
            auto = it; sys(Settings.System.SCREEN_BRIGHTNESS_MODE, if (it) "1" else "0")
            act.window.attributes = act.window.attributes.apply { screenBrightness = -1f }
        }
    }
    SettingRow("Screen off after", "When the tablet isn't charging. Desk mode keeps it on while charging.") {}
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(30_000 to "30S", 60_000 to "1M", 300_000 to "5M", 600_000 to "10M", 1_800_000 to "30M").forEach { (ms, l) ->
            VButton(l, accent = if (timeout == ms) V.Sun else V.Muted, filled = timeout == ms, enabled = owner) {
                if (sys(Settings.System.SCREEN_OFF_TIMEOUT, ms.toString())) timeout = ms
            }
        }
    }
    Gap(h = 18.dp)
    SettingRow("Rotation", "Follow the tablet, or pin it. Every VoidOS screen has a layout for both.") {}
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("auto" to "AUTO", "land" to "LANDSCAPE", "port" to "PORTRAIT").forEach { (k, l) ->
            VButton(l, accent = if (store.rotation == k) V.Sun else V.Muted, filled = store.rotation == k) { store.setRotation(k); act.applyRotation() }
        }
    }
    Gap(h = 24.dp)
    Head("Sound")
    listOf(
        Triple("Media", AudioManager.STREAM_MUSIC, V.Pink),
        Triple("Alarms + timer", AudioManager.STREAM_ALARM, V.Coral),
        Triple("Notifications", AudioManager.STREAM_NOTIFICATION, V.Purple),
        Triple("Touch sounds", AudioManager.STREAM_SYSTEM, V.Muted),
    ).forEach { (label, stream, col) ->
        val max = remember { am.getStreamMaxVolume(stream).coerceAtLeast(1) }
        var v by remember { mutableFloatStateOf(am.getStreamVolume(stream).toFloat() / max) }
        SettingRow(label) { VText("${(v * 100).toInt()}%", size = 12.sp, color = col) }
        VSlider(v, Modifier.fillMaxWidth(), accent = col) {
            v = it
            runCatching { am.setStreamVolume(stream, (it * max).toInt(), 0) }
        }
    }
}

// ------------------------------------------------------------------ Bluetooth

@SuppressLint("MissingPermission")
@Composable
private fun BtPane() {
    val ctx = LocalContext.current
    @Suppress("DEPRECATION")
    val bt = remember { BluetoothAdapter.getDefaultAdapter() }
    if (bt == null) { Head("Bluetooth", "This tablet reports no Bluetooth."); return }
    var on by remember { mutableStateOf(bt.isEnabled) }
    var bonded by remember { mutableStateOf(bt.bondedDevices?.toList() ?: emptyList()) }
    var found by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        @Suppress("DEPRECATION")
                        val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                        if (d.name != null && found.none { it.address == d.address } && bonded.none { it.address == d.address }) found = found + d
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanning = false
                    BluetoothAdapter.ACTION_STATE_CHANGED -> on = bt.isEnabled
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        bonded = bt.bondedDevices?.toList() ?: emptyList()
                        found = found.filter { f -> bonded.none { it.address == f.address } }
                    }
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED); addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ctx.registerReceiver(r, f)
        onDispose { runCatching { bt.cancelDiscovery(); ctx.unregisterReceiver(r) } }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Head("Bluetooth", if (on) "On / ${bt.name ?: "tablet"}" else "Off") }
        @Suppress("DEPRECATION")
        VToggle(on) { runCatching { if (it) bt.enable() else bt.disable() } }
    }
    if (!on) return
    Label("Paired")
    Gap(h = 6.dp)
    if (bonded.isEmpty()) VText("Nothing paired yet.", size = 13.sp, color = V.Muted, font = FontFamily.Default)
    bonded.forEach { d -> BtRow(d.name ?: d.address, "PAIRED", V.Teal) {} }
    Gap(h = 16.dp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label("Nearby", Modifier.weight(1f))
        VButton(if (scanning) "Scanning" else "Scan", accent = V.Teal, enabled = !scanning) {
            found = emptyList(); scanning = runCatching { bt.startDiscovery() }.getOrDefault(false)
        }
    }
    Gap(h = 6.dp)
    found.forEach { d -> BtRow(d.name ?: d.address, "TAP TO PAIR", V.Muted) { runCatching { bt.cancelDiscovery(); d.createBond() } } }
}

@Composable
private fun BtRow(name: String, state: String, col: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(OctS).background(V.Panel2).border(1.dp, V.Line, OctS).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(G.BT, col, Modifier.size(20.dp))
        Gap(w = 12.dp)
        VText(name, size = 15.sp, color = V.Text, font = FontFamily.Default, modifier = Modifier.weight(1f), maxLines = 1)
        VText(state, size = 10.sp, color = col, spacing = 1.sp)
    }
}

// --------------------------------------------------------------- VoidOS rules

@Composable
private fun VoidPane(store: Store, act: MainActivity) {
    val owner = Policy.isOwner(act)
    var changingPin by remember { mutableStateOf(false) }
    var pinStep by remember { mutableStateOf<String?>(null) }
    var pinErr by remember { mutableStateOf<String?>(null) }
    val bloat = remember { Policy.bloatCandidates(act) }

    fun toggle(key: String, v: Boolean) { store.set(key, v); act.applyPolicies() }

    Head("VoidOS rules", if (owner) "Changes apply straight away." else "Most of these need device owner. Run: ${Policy.SET_OWNER_CMD}")
    SettingRow("Lock mode", "Pins VoidOS: Home and Recents can't leave it, and only allowed apps open. Your PIN escape hatch always works.") {
        VToggle(store.kiosk, V.Coral, enabled = owner) { toggle("kiosk", it) }
    }
    SettingRow("Hide Android's bars", "Status and navigation bars stay hidden. A swipe from the edge shows them for a moment; the notification shade stays shut.") {
        VToggle(store.hideBars, enabled = true) { toggle("hideBars", it) }
    }
    SettingRow("No lock screen", "Power button wakes straight into VoidOS.") {
        VToggle(store.noLockScreen, enabled = owner) { toggle("noLockScreen", it) }
    }
    SettingRow("Hold off system updates", "Sets Android's postpone policy (Android caps this at 30 days per update) and hides Lenovo's updater app if it can find it.") {
        VToggle(store.blockUpdates, enabled = owner) { toggle("blockUpdates", it) }
    }
    SettingRow("Desk mode", "Screen stays on while charging. The ambient clock takes over when idle.") {
        VToggle(store.deskMode, V.Green, enabled = owner) { toggle("deskMode", it) }
    }
    SettingRow("Hide Lenovo extras", if (bloat.isEmpty()) "No Lenovo or partner apps found to hide." else "Hides ${bloat.size}: ${bloat.joinToString(", ")}. Nothing is deleted; turning this off brings them back.") {
        VToggle(store.hideLenovo, V.Purple, enabled = owner) { toggle("hideLenovo", it) }
    }
    SettingRow("24-hour clock") { VToggle(store.use24h) { store.set("use24h", it) } }
    SettingRow("Ambient clock after", "Dim drifting clock when nobody touches the screen.") {}
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0 to "NEVER", 1 to "1M", 5 to "5M", 15 to "15M").forEach { (m, l) ->
            VButton(l, accent = if (store.ambientMinutes == m) V.Teal else V.Muted, filled = store.ambientMinutes == m) { store.setAmbient(m) }
        }
    }
    Gap(h = 18.dp)
    SettingRow("Escape PIN", "Hold the VoidOS mark for 3 seconds, then enter it.") {
        VButton(if (changingPin) "Cancel" else "Change", accent = V.Muted) { changingPin = !changingPin; pinStep = null; pinErr = null }
    }
    if (changingPin) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PinPad(
                title = if (pinStep == null) "New PIN" else "Once more",
                subtitle = "Four digits",
                error = pinErr,
                onPin = { p ->
                    if (pinStep == null) { pinStep = p; pinErr = null }
                    else if (p == pinStep) { store.setPin(p); changingPin = false; pinStep = null }
                    else { pinStep = null; pinErr = "They didn't match." }
                },
            )
        }
    }
}

// ---------------------------------------------------------------------- About

@Composable
private fun AboutPane(act: MainActivity) {
    val ctx = LocalContext.current
    val mem = remember {
        val mi = ActivityManager.MemoryInfo(); ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi); mi
    }
    val disk = remember { runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull() }
    val gb = { b: Long -> "%.1f GB".format(b / 1_073_741_824.0) }
    val up = SystemClock.elapsedRealtime() / 60_000

    VText("VOIDOS", size = 40.sp, color = V.White, spacing = 8.sp)
    VText("TAB EDITION  /  ${BuildConfig.VERSION_NAME}", size = 12.sp, color = V.Teal, spacing = 3.sp)
    Gap(h = 6.dp)
    VText("Sister of VoidOS for PineTime. Same octagon, same lap colours, same Kenney type.", size = 12.sp, color = V.Muted, font = FontFamily.Default)
    Gap(h = 18.dp)
    listOf(
        "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "Chip" to (Build.HARDWARE.ifBlank { Build.BOARD }),
        "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "Build" to Build.DISPLAY,
        "Memory" to "${gb(mem.availMem)} free of ${gb(mem.totalMem)}",
        "Storage" to (disk?.let { "${gb(it.availableBytes)} free of ${gb(it.totalBytes)}" } ?: "--"),
        "Up for" to "${up / 60} h ${up % 60} min",
        "Device owner" to if (Policy.isOwner(act)) "Yes" else "No",
    ).forEach { (k, v) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Label(k, Modifier.width(150.dp))
            VText(v, size = 14.sp, color = V.Text, font = FontFamily.Default, maxLines = 1)
        }
    }
    Gap(h = 16.dp)
    Label("From your Mac, to hand the tablet back")
    Gap(h = 6.dp)
    listOf(
        "adb shell am broadcast -a com.andysocial.voidos.action.UNLOCK -n com.andysocial.voidos/.ReleaseReceiver",
        "adb shell dpm remove-active-admin com.andysocial.voidos/.VoidAdmin",
        "adb uninstall com.andysocial.voidos",
    ).forEach {
        VText(it, size = 12.sp, color = V.Teal, font = FontFamily.Monospace, lineHeight = 18.sp)
        Gap(h = 4.dp)
    }
}
