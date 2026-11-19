package com.andysocial.voidos.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.Brush

import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.activity.compose.BackHandler
import com.andysocial.voidos.Radio
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
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
import com.andysocial.voidos.Stealth
import com.andysocial.voidos.Su
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Settings pages, gathered into four groups so the list reads at a glance. */
private enum class Tab(val label: String, val g: G, val group: Int, val accent: Color) {
    // each page has its own colour, drawn from the logo's six, the way a phone's settings do
    WIFI("Wi-Fi", G.WIFI, 0, V.Teal), BT("Bluetooth", G.BT, 0, Color(0xFF5A8CF0)), NET("Network", G.GLOBE, 0, V.Green),
    DISPLAY("Display & sound", G.SUN, 1, V.Orange), NOTIFY("Notifications", G.BELL, 1, V.Coral), QUIET("Do not disturb", G.MOON, 1, V.Purple), BATTERY("Battery", G.BATTERY, 1, V.Green),
    HUB("Home & lock screen", G.FACE, 2, V.Coral), TIME("Date & time", G.TIMER, 2, V.Yellow), VOID("Lock mode", G.LOCK, 2, V.Purple),
    STORAGE("Storage", G.APPS, 3, Color(0xFF8E8E98)), ANDROID("System", G.SYSTEM, 3, Color(0xFF8E8E98)),
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
                    Tab.BATTERY -> { BatteryPane(); Gap(h = 24.dp); BatteryUseList() }
                    Tab.NOTIFY -> NotificationsPane(store)
                    Tab.NET -> { NetworkPane(); Gap(h = 24.dp); NetworkExtras() }
                    Tab.STORAGE -> { StoragePane(store); Gap(h = 24.dp); StorageExtras(); Gap(h = 24.dp); AppsExtras(store) }
                    Tab.ANDROID -> SystemSettingsPane(store, act)
                    Tab.VOID -> VoidPane(store, act)
                    Tab.ABOUT -> AboutTabletPane(store, act)
                }
            }
        }
    }
    val list: @Composable (Modifier) -> Unit = { m ->
        Column(m.verticalScroll(rememberScrollState())) {
            groups.forEachIndexed { gi, _ ->
                Gap(h = if (gi == 0) 0.dp else 16.dp)
                Column(Modifier.fillMaxWidth().pane(Oct)) {
                    val pages = Tab.entries.filter { it.group == gi }
                    pages.forEachIndexed { i, t ->
                        if (i > 0) Spacer(Modifier.height(2.dp))
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
                    Gap(w = 8.dp)
                    VText("All settings", size = Type.headline, color = V.Teal)
                }
                Gap(h = 8.dp)
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
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // a lit glass tile in the page's colour, like the icons in an iPad's settings
        Box(
            Modifier.size(32.dp).droplet(RoundedCornerShape(Radius.chip), t.accent, 2.2f),
            contentAlignment = Alignment.Center,
        ) {
            Glyph(t.g, Color.White, Modifier.size(18.dp))
        }
        Gap(w = 16.dp)
        VText(t.label, size = Type.headline, color = V.Text, maxLines = 1, modifier = Modifier.weight(1f))
        VText("\u203A", size = Type.title2, color = V.Tick, font = SansLight)
    }
}

/**
 * The top of a settings page. Pages carry no titles (the list already says where you
 * are), so this only shows [status]: a live value worth seeing, such as the network
 * you're on. [title] names the page in code only.
 */
@Composable
internal fun Head(@Suppress("UNUSED_PARAMETER") title: String, status: String? = null) {
    if (status != null) { VText(status, size = Type.body, color = V.Muted, maxLines = 1); Gap(h = 8.dp) }
}

/** A small label between groups of rows on one settings page. */
@Composable
internal fun SetSection(text: String) {
    Gap(h = 16.dp)
    Label(text)
    Gap(h = 4.dp)
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
        val wscope = rememberCoroutineScope()
        VToggle(enabled, enabled = true) { on ->
            enabled = on
            wscope.launch {
                val ok = withContext(Dispatchers.IO) { Radio.setWifi(ctx, on) }
                if (!ok) msg = if (owner) "Wi-Fi didn't switch. Try again." else "Wi-Fi didn't switch."
                enabled = wm.isWifiEnabled
            }
        }
    }
    msg?.let { VText(it, size = Type.caption, color = V.Coral, font = Sans); Gap(h = 8.dp) }

    joining?.let { net ->
        VPanel(Modifier.fillMaxWidth(), accent = V.Teal, padding = 14.dp) {
            VText("Join ${net.SSID}", size = Type.body, color = V.Teal)
            Gap(h = 8.dp)
            if (isSecured(net)) VField(pass, { pass = it }, Modifier.fillMaxWidth(), placeholder = "Password", password = true)
            Gap(h = 8.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VButton("Connect", accent = V.Teal, filled = true) {
                    msg = connect(wm, net, pass)
                    joining = null; pass = ""
                }
                VButton("Cancel", accent = V.Muted) { joining = null; pass = "" }
            }
        }
        Gap(h = 12.dp)
    }

    if (enabled && results.isEmpty()) VText("Scanning", size = Type.caption, color = V.Muted)
    results.forEach { r ->
        val isCur = r.SSID == current
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp).then(if (isCur) Modifier.droplet(OctS, V.Teal, 0.6f) else Modifier.pane(OctS)).clickable(enabled = !isCur) { joining = r; msg = null }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val bars = WifiManager.calculateSignalLevel(r.level, 4)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                for (i in 0..3) Box(Modifier.size(5.dp, (6 + i * 4).dp).background(if (i <= bars) V.Teal else V.Line))
            }
            Gap(w = 16.dp)
            VText(r.SSID, size = Type.body, color = V.Text, font = Sans, modifier = Modifier.weight(1f), maxLines = 1)
            VText(if (isCur) "CONNECTED" else if (isSecured(r)) "LOCKED" else "OPEN", size = Type.caption, color = if (isCur) V.Teal else V.Muted)
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
                r.capabilities.contains("EAP") -> return "Work/school (EAP) Wi-Fi isn't supported here."
                else -> allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            }
        }
        val existing = wm.configuredNetworks?.firstOrNull { it.SSID == conf.SSID }
        existing?.let { wm.removeNetwork(it.networkId) }
        val id = wm.addNetwork(conf)
        if (id == -1) return "Couldn't add the network."
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

    Head("Screen")
    SettingRow("Brightness") {}
    VSlider(bright, Modifier.fillMaxWidth(), accent = V.Teal) {
        bright = it
        if (auto) { auto = false; sys(Settings.System.SCREEN_BRIGHTNESS_MODE, "0") }
        sys(Settings.System.SCREEN_BRIGHTNESS, (8 + it * 247).toInt().toString())
        // instant feedback on this window too
        act.window.attributes = act.window.attributes.apply { screenBrightness = (0.03f + it * 0.97f) }
    }
    SettingRow("Auto brightness") {
        VToggle(auto, V.Teal, enabled = owner) {
            auto = it; sys(Settings.System.SCREEN_BRIGHTNESS_MODE, if (it) "1" else "0")
            act.window.attributes = act.window.attributes.apply { screenBrightness = -1f }
        }
    }
    SettingRow("Screen off after") {}
    GlassSegments(
        listOf("30000" to "30 s", "60000" to "1 min", "300000" to "5 min", "600000" to "10 min", "1800000" to "30 min"),
        timeout.toString(), Modifier.widthIn(max = 620.dp), enabled = owner,
    ) { k -> if (sys(Settings.System.SCREEN_OFF_TIMEOUT, k)) timeout = k.toInt() }
    Gap(h = 16.dp)
    SettingRow("Rotation") {}
    GlassSegments(
        listOf("auto" to "Auto", "land" to "Landscape", "port" to "Portrait"), store.rotation, Modifier.widthIn(max = 520.dp),
    ) { k -> store.setRotation(k); act.applyRotation() }
    Gap(h = 24.dp)
    SetSection("Sound")
    listOf(
        Triple("Media", AudioManager.STREAM_MUSIC, V.Teal),
        Triple("Alarms + timer", AudioManager.STREAM_ALARM, V.Coral),
        Triple("Notifications", AudioManager.STREAM_NOTIFICATION, V.Teal),
        Triple("Touch sounds", AudioManager.STREAM_SYSTEM, V.Muted),
    ).forEach { (label, stream, col) ->
        val max = remember { am.getStreamMaxVolume(stream).coerceAtLeast(1) }
        var v by remember { mutableFloatStateOf(am.getStreamVolume(stream).toFloat() / max) }
        SettingRow(label) { VText("${(v * 100).toInt()}%", size = Type.caption, color = col) }
        VSlider(v, Modifier.fillMaxWidth(), accent = col) {
            v = it
            runCatching { am.setStreamVolume(stream, (it * max).toInt(), 0) }
        }
    }
    SoundExtras(store)
    Gap(h = 24.dp)
    ScreenExtras(store)
}

// ------------------------------------------------------------------ Bluetooth

@SuppressLint("MissingPermission")
@Composable
private fun BtPane() {
    val ctx = LocalContext.current
    @Suppress("DEPRECATION")
    val bt = remember { BluetoothAdapter.getDefaultAdapter() }
    if (bt == null) { Head("Bluetooth", "No Bluetooth"); return }
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
        val bscope = rememberCoroutineScope()
        VToggle(on) { want -> bscope.launch { withContext(Dispatchers.IO) { Radio.setBt(want) } } }
    }
    if (!on) return
    Label("Paired")
    Gap(h = 8.dp)
    if (bonded.isEmpty()) VText("Nothing paired yet.", size = Type.label, color = V.Muted, font = Sans)
    bonded.forEach { d -> BtRow(d.name ?: d.address, "PAIRED", V.Teal) {} }
    Gap(h = 16.dp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label("Nearby", Modifier.weight(1f))
        VButton(if (scanning) "Scanning" else "Scan", accent = V.Teal, enabled = !scanning) {
            found = emptyList(); scanning = runCatching { bt.startDiscovery() }.getOrDefault(false)
        }
    }
    Gap(h = 8.dp)
    found.forEach { d -> BtRow(d.name ?: d.address, "TAP TO PAIR", V.Muted) { runCatching { bt.cancelDiscovery(); d.createBond() } } }
}

@Composable
private fun BtRow(name: String, state: String, col: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).pane(OctS).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(G.BT, col, Modifier.size(20.dp))
        Gap(w = 12.dp)
        VText(name, size = Type.body, color = V.Text, font = Sans, modifier = Modifier.weight(1f), maxLines = 1)
        VText(state, size = Type.caption, color = col)
    }
}

// --------------------------------------------------------------- VoidOS rules

@Composable
private fun VoidPane(store: Store, act: MainActivity) {
    val owner = Policy.isOwner(act)
    val bloat = remember { Policy.bloatCandidates(act) }

    fun toggle(key: String, v: Boolean) { store.set(key, v); act.applyPolicies() }

    Head("VoidOS rules")
    // once lock mode is on it can't be switched off here: only the hidden escape (taps + PIN) can
    if (!(store.kiosk && owner)) SettingRow("Lock mode") {
        VToggle(store.kiosk, V.Coral, enabled = owner) { toggle("kiosk", it) }
    }
    SettingRow("Hide Android") {
        VToggle(store.hideBars, enabled = true) { toggle("hideBars", it) }
    }
    if (Stealth.restartNeeded.value) SettingRow("Restart to remove the bottom bar") {
        VButton("Restart", accent = V.Teal, filled = true) { Thread { Su.run("svc power reboot || reboot") }.start() }
    }
    SettingRow("No lock screen") {
        VToggle(store.noLockScreen, enabled = owner) { toggle("noLockScreen", it) }
    }
    SettingRow("Hold off system updates") {
        VToggle(store.blockUpdates, enabled = owner) { toggle("blockUpdates", it) }
    }
    SettingRow("Desk mode") {
        VToggle(store.deskMode, V.Teal, enabled = owner) { toggle("deskMode", it) }
    }
    SettingRow("Hide extra apps", if (bloat.isEmpty()) null else "${bloat.size} hidden") {
        VToggle(store.hideLenovo, V.Teal, enabled = owner) { toggle("hideLenovo", it) }
    }
    SettingRow("Ambient clock after") {}
    GlassSegments(
        listOf("0" to "Never", "1" to "1 min", "5" to "5 min", "15" to "15 min"), store.ambientMinutes.toString(), Modifier.widthIn(max = 520.dp),
    ) { store.setAmbient(it.toInt()) }
    Gap(h = 16.dp)
    SettingRow("Standby clock") {}
    GlassSegments(
        listOf("digital" to "Digital", "analog" to "Analogue"), store.ambientFace, Modifier.widthIn(max = 520.dp),
    ) { store.chooseAmbientFace(it) }
    Gap(h = 16.dp)

}
