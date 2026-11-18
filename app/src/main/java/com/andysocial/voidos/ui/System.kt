package com.andysocial.voidos.ui

import androidx.compose.ui.graphics.Brush

import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.widthIn

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
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

/** Settings pages, gathered into four groups so the list reads at a glance. */
private enum class Tab(val label: String, val g: G, val group: Int, val accent: Color) {
    // each page has its own colour, drawn from the logo's six, the way a phone's settings do
    WIFI("Wi-Fi", G.WIFI, 0, V.Teal), BT("Bluetooth", G.BT, 0, Color(0xFF5A8CF0)), NET("Network", G.GLOBE, 0, V.Green),
    DISPLAY("Display & sound", G.SUN, 1, V.Orange), QUIET("Do not disturb", G.MOON, 1, V.Purple), BATTERY("Battery", G.BATTERY, 1, V.Green),
    HUB("Home & lock screen", G.FACE, 2, V.Coral), TIME("Date & time", G.TIMER, 2, V.Yellow), VOID("Lock mode", G.LOCK, 2, V.Purple),
    STORAGE("Storage", G.APPS, 3, Color(0xFF8E8E98)), ROOT("Boot & root", G.POWER, 3, V.Coral), ANDROID("Android settings", G.SYSTEM, 3, Color(0xFF8E8E98)),
    ABOUT("About", G.INFO, 3, V.Teal),
}

private val groups = listOf("Connections", "Screen", "VoidOS", "Tablet")

/**
 * Settings, laid out like a tablet settings app: a grouped list of pages on the
 * left and the open page on the right. Upright, the list fills the screen and a
 * page opens over it, with a back link to return.
 */
@Composable
fun SystemScreen(store: Store, act: MainActivity) {
    val portrait = LocalPortrait.current
    var tab by rememberSaveable { mutableStateOf(Tab.WIFI) }
    var open by rememberSaveable { mutableStateOf(false) }   // upright only: a page is open over the list
    val owner = Policy.isOwner(act)

    val page: @Composable (Modifier) -> Unit = { m ->
        Column(m.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Column(Modifier.widthIn(max = 720.dp)) {
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
                    Tab.ROOT -> RootPane(store)
                    Tab.VOID -> VoidPane(store, act)
                    Tab.ABOUT -> AboutPane(act)
                }
            }
        }
    }
    val list: @Composable (Modifier) -> Unit = { m ->
        Column(m.verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth().pane(Oct).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).glass(RoundedCornerShape(12.dp), 0.9f), contentAlignment = Alignment.Center) {
                    VoidMark(Modifier.size(26.dp), mono = V.Text)
                }
                Gap(w = 14.dp)
                Column(Modifier.weight(1f)) {
                    VText("VoidOS ${BuildConfig.VERSION_NAME}", size = 16.sp, font = SansMedium, color = V.Text)
                    VText(if (owner) "Running the tablet (device owner)" else "Not device owner: some switches are limited", size = 13.sp, color = if (owner) V.Muted else V.Coral, maxLines = 2)
                }
            }
            groups.forEachIndexed { gi, g ->
                Label(g, Modifier.padding(start = 16.dp, top = 22.dp, bottom = 8.dp))
                Column(Modifier.fillMaxWidth().pane(Oct)) {
                    val pages = Tab.entries.filter { it.group == gi }
                    pages.forEachIndexed { i, t ->
                        if (i > 0) Box(Modifier.padding(start = 62.dp).fillMaxWidth().height(1.dp).background(V.White.copy(alpha = 0.07f)))
                        NavItem(t, sel = !portrait && t == tab) { tab = t; open = true }
                    }
                }
            }
            Gap(h = 24.dp)
        }
    }

    if (portrait) {
        BackHandler(enabled = open) { open = false }
        run {
            val showing = open
            if (!showing) list(Modifier.fillMaxSize())
            else Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.clip(Pill).clickable { open = false }.padding(start = 2.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph(G.BACK, V.Teal, Modifier.size(20.dp))
                    Gap(w = 6.dp)
                    VText("All settings", size = 16.sp, color = V.Teal)
                }
                Gap(h = 10.dp)
                page(Modifier.weight(1f).fillMaxWidth())
            }
        }
    } else {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            list(Modifier.width(300.dp).fillMaxHeight())
            page(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** One row in the settings list: a quiet icon tile, the page name, a chevron. */
@Composable
private fun NavItem(t: Tab, sel: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (sel) Brush.horizontalGradient(listOf(t.accent.copy(alpha = 0.26f), t.accent.copy(alpha = 0.06f))) else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // a lit glass tile in the page's colour, like the icons in an iPad's settings
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(tileBrush(t.accent))
                .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Color.Transparent)), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Glyph(t.g, Color.White, Modifier.size(18.dp))
        }
        Gap(w = 16.dp)
        VText(t.label, size = 16.sp, color = V.Text, maxLines = 1, modifier = Modifier.weight(1f))
        VText("\u203A", size = 22.sp, color = V.Tick, font = SansLight)
    }
}

/** A page's title and a line on what it's for. */
@Composable
internal fun Head(title: String, sub: String? = null) {
    VText(sentence(title), size = 28.sp, font = SansLight, color = V.White, spacing = (-0.3).sp)
    if (sub != null) { Gap(h = 6.dp); VText(sub, size = 14.sp, color = V.Muted, lineHeight = 20.sp) }
    Gap(h = 20.dp)
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
            VText("Join ${net.SSID}", size = 14.sp, color = V.Teal)
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

    if (enabled && results.isEmpty()) VText("Scanning", size = 12.sp, color = V.Muted)
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
            VText(if (isCur) "CONNECTED" else if (isSecured(r)) "LOCKED" else "OPEN", size = 10.sp, color = if (isCur) V.Teal else V.Muted)
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
    VSlider(bright, Modifier.fillMaxWidth(), accent = V.Teal) {
        bright = it
        if (auto) { auto = false; sys(Settings.System.SCREEN_BRIGHTNESS_MODE, "0") }
        sys(Settings.System.SCREEN_BRIGHTNESS, (8 + it * 247).toInt().toString())
        // instant feedback on this window too
        act.window.attributes = act.window.attributes.apply { screenBrightness = (0.03f + it * 0.97f) }
    }
    SettingRow("Auto brightness", "Let the light sensor decide.") {
        VToggle(auto, V.Teal, enabled = owner) {
            auto = it; sys(Settings.System.SCREEN_BRIGHTNESS_MODE, if (it) "1" else "0")
            act.window.attributes = act.window.attributes.apply { screenBrightness = -1f }
        }
    }
    SettingRow("Screen off after", "When the tablet isn't charging. Desk mode keeps it on while charging.") {}
    GlassSegments(
        listOf("30000" to "30 s", "60000" to "1 min", "300000" to "5 min", "600000" to "10 min", "1800000" to "30 min"),
        timeout.toString(), Modifier.widthIn(max = 620.dp), enabled = owner,
    ) { k -> if (sys(Settings.System.SCREEN_OFF_TIMEOUT, k)) timeout = k.toInt() }
    Gap(h = 18.dp)
    SettingRow("Rotation", "Follow the tablet, or pin it. Every VoidOS screen has a layout for both.") {}
    GlassSegments(
        listOf("auto" to "Auto", "land" to "Landscape", "port" to "Portrait"), store.rotation, Modifier.widthIn(max = 520.dp),
    ) { k -> store.setRotation(k); act.applyRotation() }
    Gap(h = 24.dp)
    Head("Sound")
    listOf(
        Triple("Media", AudioManager.STREAM_MUSIC, V.Teal),
        Triple("Alarms + timer", AudioManager.STREAM_ALARM, V.Coral),
        Triple("Notifications", AudioManager.STREAM_NOTIFICATION, V.Teal),
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
        VText(state, size = 10.sp, color = col)
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
    SettingRow("Lock mode", "Pins VoidOS: Home and Recents can't leave it, and only allowed apps open.") {
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
        VToggle(store.deskMode, V.Teal, enabled = owner) { toggle("deskMode", it) }
    }
    SettingRow("Hide Lenovo extras", if (bloat.isEmpty()) "No Lenovo or partner apps found to hide." else "Hides ${bloat.size}: ${bloat.joinToString(", ")}. Nothing is deleted; turning this off brings them back.") {
        VToggle(store.hideLenovo, V.Teal, enabled = owner) { toggle("hideLenovo", it) }
    }
    SettingRow("24-hour clock") { VToggle(store.use24h) { store.set("use24h", it) } }
    SettingRow("Ambient clock after", "Dim drifting clock when nobody touches the screen.") {}
    GlassSegments(
        listOf("0" to "Never", "1" to "1 min", "5" to "5 min", "15" to "15 min"), store.ambientMinutes.toString(), Modifier.widthIn(max = 520.dp),
    ) { store.setAmbient(it.toInt()) }
    Gap(h = 18.dp)
    SettingRow("Lock screen PIN", if (store.hasPin) "Asked for after the swipe when PIN to unlock is on." else "Not set yet.") {
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

    VText("VoidOS", size = 40.sp, font = SansLight, color = V.White)
    VText("Tablet edition \u00b7 ${BuildConfig.VERSION_NAME}", size = 12.sp, color = V.Teal)
    Gap(h = 6.dp)
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
    ).let { rows ->
        GlassGroup {
            rows.forEach { (k, v) ->
                // the Device line is also the hidden way out: no ripple, no count, no hint
                val door = if (k == "Device") Modifier.pointerInput(Unit) { detectTapGestures { EscapeDoor.tap() } }.testTag("about-device") else Modifier
                Row(door.fillMaxWidth().padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    VText(k, size = 15.sp, color = V.Muted, modifier = Modifier.width(150.dp))
                    VText(v, size = 15.sp, color = V.Text, maxLines = 1, modifier = Modifier.weight(1f))
                }
                if (k != rows.last().first) Box(Modifier.padding(start = 18.dp).fillMaxWidth().height(1.dp).background(V.White.copy(alpha = 0.07f)))
            }
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
