package com.andysocial.voidos

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.provider.Settings

/**
 * Wi-Fi and Bluetooth for the control centre. Android 10 refuses most of this
 * to ordinary apps, so each call tries the device-owner route first and then
 * root (Magisk), and reports whether the tablet actually changed. Blocking:
 * call off the main thread.
 */
object Radio {

    // ---------------------------------------------------------------- Wi-Fi

    enum class Sec { OPEN, WEP, WPA, SAE }

    data class Net(
        val ssid: String,
        /** 0..4 bars. */
        val bars: Int,
        val sec: Sec,
        val connected: Boolean = false,
        /** Saved on the tablet, so it joins without a password. */
        val saved: Boolean = false,
    ) {
        val locked get() = sec != Sec.OPEN
    }

    private fun wm(ctx: Context) = ctx.applicationContext.getSystemService(WifiManager::class.java)

    fun wifiOn(ctx: Context) = runCatching { wm(ctx).isWifiEnabled }.getOrDefault(false)

    /**
     * Switches Wi-Fi. Device owner first (Android 10 lets the owner do it),
     * then root `svc wifi`. Waits up to 4 s for the switch to take, so a
     * "didn't work" message only shows when it really didn't.
     */
    @Suppress("DEPRECATION")
    fun setWifi(ctx: Context, on: Boolean): Boolean {
        if (wifiOn(ctx) == on) return true
        val asked = runCatching { wm(ctx).setWifiEnabled(on) }.getOrDefault(false)
        if (asked && settle { wifiOn(ctx) == on }) return true
        Su.ok("svc wifi " + if (on) "enable" else "disable")
        return settle { wifiOn(ctx) == on }
    }

    /** Lets VoidOS see nearby networks: location permission (device owner grants it) and location on. */
    fun prepareScan(ctx: Context) {
        val pkg = ctx.packageName
        if (Policy.isOwner(ctx)) runCatching {
            Policy.dpm(ctx).setPermissionGrantState(Policy.admin(ctx), pkg, Manifest.permission.ACCESS_FINE_LOCATION,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        }
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            Su.ok("pm grant $pkg android.permission.ACCESS_FINE_LOCATION")
        val mode = runCatching { Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.LOCATION_MODE) }.getOrDefault(0)
        if (mode == Settings.Secure.LOCATION_MODE_OFF) {
            val ok = Policy.isOwner(ctx) && runCatching {
                Policy.dpm(ctx).setSecureSetting(Policy.admin(ctx), Settings.Secure.LOCATION_MODE,
                    Settings.Secure.LOCATION_MODE_HIGH_ACCURACY.toString())
            }.isSuccess
            if (!ok) Su.ok("settings put secure location_mode 3")
        }
    }

    @Suppress("DEPRECATION")
    fun scan(ctx: Context) { runCatching { wm(ctx).startScan() } }

    /** The connected network's name, or null. */
    @Suppress("DEPRECATION")
    fun connectedSsid(ctx: Context): String? = runCatching {
        val info = wm(ctx).connectionInfo ?: return null
        if (info.networkId == -1) null else clean(info.ssid).takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
    }.getOrNull()

    /** Nearby networks, strongest first, the connected one on top, one row per name. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun networks(ctx: Context): List<Net> {
        val w = wm(ctx)
        val results = runCatching { w.scanResults }.getOrNull().orEmpty()
        val saved = savedSsids(ctx)
        val now = connectedSsid(ctx)
        return merge(results.map { Triple(clean(it.SSID), it.level, secOf(it.capabilities)) }, saved, now)
    }

    /** Pure part of [networks]: dedupe by name, strongest signal wins, connected first. Unit-tested. */
    fun merge(raw: List<Triple<String, Int, Sec>>, saved: Set<String>, connected: String?): List<Net> =
        raw.filter { it.first.isNotBlank() }
            .groupBy { it.first }
            .map { (ssid, rows) ->
                val best = rows.maxByOrNull { it.second }!!
                Net(ssid, bars(best.second), best.third, connected = ssid == connected, saved = ssid in saved)
            }
            .let { list ->
                if (connected != null && list.none { it.connected }) list + Net(connected, 3, Sec.WPA, connected = true, saved = true) else list
            }
            .sortedWith(compareByDescending<Net> { it.connected }.thenByDescending { it.bars }.thenBy { it.ssid.lowercase() })

    /** dBm to 0..4 bars, the way Android's own status bar does it. */
    fun bars(level: Int): Int = when {
        level >= -55 -> 4
        level >= -66 -> 3
        level >= -77 -> 2
        level >= -88 -> 1
        else -> 0
    }

    fun secOf(caps: String): Sec = when {
        caps.contains("SAE") -> Sec.SAE
        caps.contains("PSK") || caps.contains("EAP") || caps.contains("WPA") -> Sec.WPA
        caps.contains("WEP") -> Sec.WEP
        else -> Sec.OPEN
    }

    fun clean(ssid: String?): String = ssid.orEmpty().removeSurrounding("\"")

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun configs(ctx: Context): List<WifiConfiguration> = runCatching { wm(ctx).configuredNetworks }.getOrNull().orEmpty()

    private fun savedSsids(ctx: Context) = configs(ctx).map { clean(it.SSID) }.toSet()

    /** Builds the Wi-Fi config for a network. Pure apart from Android's data class. */
    @Suppress("DEPRECATION")
    fun config(ssid: String, sec: Sec, password: String): WifiConfiguration = WifiConfiguration().apply {
        SSID = "\"" + ssid + "\""
        when (sec) {
            Sec.OPEN -> allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            Sec.WEP -> {
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
                allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED)
                wepKeys[0] = if (password.length in listOf(10, 26, 58) && password.all { it.isLetterOrDigit() }) password else "\"" + password + "\""
                wepTxKeyIndex = 0
            }
            Sec.WPA, Sec.SAE -> {
                preSharedKey = "\"" + password + "\""
                allowedKeyManagement.set(if (sec == Sec.SAE) WifiConfiguration.KeyMgmt.SAE else WifiConfiguration.KeyMgmt.WPA_PSK)
            }
        }
    }

    /**
     * Joins a network. A saved one joins as it is; otherwise it is added
     * (allowed for the device owner on Android 10) with [password]. Waits up
     * to 15 s to see the tablet connect. Returns null on success, else what to tell the user.
     */
    @Suppress("DEPRECATION")
    fun join(ctx: Context, net: Net, password: String): String? {
        val w = wm(ctx)
        if (!wifiOn(ctx) && !setWifi(ctx, true)) return "Couldn't turn Wi-Fi on."
        val existing = configs(ctx).firstOrNull { clean(it.SSID) == net.ssid }
        var id = if (existing != null && password.isEmpty()) existing.networkId else -1
        if (id == -1) {
            if (existing != null) runCatching { w.removeNetwork(existing.networkId) }
            id = runCatching { w.addNetwork(config(net.ssid, net.sec, password)) }.getOrDefault(-1)
        }
        if (id == -1) {
            // root fallback: hand the network straight to wpa_supplicant
            if (!rootJoin(net, password)) return "Android wouldn't let VoidOS add this network."
            return if (settle(15_000) { connectedSsid(ctx) == net.ssid }) null
            else if (net.locked) "Couldn't join. Check the password." else "Couldn't join this network."
        }
        runCatching { w.enableNetwork(id, true) }
        runCatching { w.reconnect() }
        return if (settle(15_000) { connectedSsid(ctx) == net.ssid }) null
        else if (net.locked) "Couldn't join. Check the password." else "Couldn't join this network."
    }

    /** The wpa_cli lines that add and select a network as root. Pure, unit-tested. */
    fun wpaScript(net: Net, password: String): String {
        val c = "wpa_cli -i wlan0"
        val ssid = Su.q("\"" + net.ssid + "\"")
        val auth = when (net.sec) {
            Sec.OPEN -> "$c set_network \$n key_mgmt NONE"
            Sec.WEP -> "$c set_network \$n key_mgmt NONE && $c set_network \$n wep_key0 " + Su.q("\"" + password + "\"")
            Sec.WPA -> "$c set_network \$n psk " + Su.q("\"" + password + "\"")
            Sec.SAE -> "$c set_network \$n key_mgmt SAE && $c set_network \$n sae_password " + Su.q("\"" + password + "\"")
        }
        return "n=\$($c add_network | tail -n 1) && $c set_network \$n ssid $ssid && $auth && $c select_network \$n && $c save_config"
    }

    private fun rootJoin(net: Net, password: String): Boolean = Su.ok(wpaScript(net, password), 15)

    /** Forgets a saved network. Device owner first, then root. */
    @Suppress("DEPRECATION")
    fun forget(ctx: Context, ssid: String): Boolean {
        val w = wm(ctx)
        val cfgs = configs(ctx).filter { clean(it.SSID) == ssid }
        var ok = cfgs.isNotEmpty() && cfgs.all { runCatching { w.removeNetwork(it.networkId) }.getOrDefault(false) }
        if (!ok) {
            val list = Su.run("wpa_cli -i wlan0 list_networks").text
            list.lines().drop(1).map { it.split('\t') }.filter { it.size >= 2 && it[1] == ssid }.forEach {
                Su.ok("wpa_cli -i wlan0 remove_network ${it[0]} && wpa_cli -i wlan0 save_config")
            }
            ok = savedSsids(ctx).none { it == ssid }
        }
        if (ok) runCatching { w.saveConfiguration() }
        return ok
    }

    // ------------------------------------------------------------ Bluetooth

    enum class Kind { HEADPHONES, SPEAKER, KEYBOARD, MOUSE, PHONE, COMPUTER, WATCH, OTHER }

    data class Dev(val name: String, val address: String, val kind: Kind, val paired: Boolean, val connected: Boolean = false)

    @Suppress("DEPRECATION")
    private fun ba(): BluetoothAdapter? = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()

    fun btOn() = runCatching { ba()?.isEnabled == true }.getOrDefault(false)

    /** Switches Bluetooth: the public call (allowed with BLUETOOTH_ADMIN on Android 10), then root. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun setBt(on: Boolean): Boolean {
        if (btOn() == on) return true
        val a = ba() ?: return false
        val asked = runCatching { if (on) a.enable() else a.disable() }.getOrDefault(false)
        if (asked && settle(6000) { btOn() == on }) return true
        // Android 10's bluetooth_manager: 6 = enable(callingPackage), 8 = disable(callingPackage, persist)
        Su.ok(if (on) "service call bluetooth_manager 6 s16 com.android.shell" else "service call bluetooth_manager 8 s16 com.android.shell i32 1")
        return settle(6000) { btOn() == on }
    }

    fun kindOf(major: Int, device: Int): Kind = when (major) {
        BluetoothClass.Device.Major.AUDIO_VIDEO -> when (device) {
            BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER, BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
            BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO, BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> Kind.SPEAKER
            else -> Kind.HEADPHONES
        }
        BluetoothClass.Device.Major.PERIPHERAL -> if (device and 0x40 != 0) Kind.KEYBOARD else Kind.MOUSE
        BluetoothClass.Device.Major.PHONE -> Kind.PHONE
        BluetoothClass.Device.Major.COMPUTER -> Kind.COMPUTER
        BluetoothClass.Device.Major.WEARABLE -> Kind.WATCH
        else -> Kind.OTHER
    }

    @SuppressLint("MissingPermission")
    fun dev(d: BluetoothDevice, connected: Set<String>): Dev {
        val c = runCatching { d.bluetoothClass }.getOrNull()
        return Dev(
            name = runCatching { d.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: d.address,
            address = d.address,
            kind = if (c == null) Kind.OTHER else kindOf(c.majorDeviceClass, c.deviceClass),
            paired = runCatching { d.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false),
            connected = d.address in connected,
        )
    }

    /** Paired devices, connected first. [connected] comes from [connectedAddresses]. */
    @SuppressLint("MissingPermission")
    fun paired(connected: Set<String>): List<Dev> =
        runCatching { ba()?.bondedDevices.orEmpty().map { dev(it, connected) } }.getOrDefault(emptyList())
            .sortedWith(compareByDescending<Dev> { it.connected }.thenBy { it.name.lowercase() })

    /** Addresses connected over any profile, via the hidden isConnected() (fine for apps on Android 10). */
    @SuppressLint("MissingPermission")
    fun connectedAddresses(): Set<String> = runCatching {
        val m = BluetoothDevice::class.java.getMethod("isConnected")
        ba()?.bondedDevices.orEmpty().filter { runCatching { m.invoke(it) as Boolean }.getOrDefault(false) }.map { it.address }.toSet()
    }.getOrDefault(emptySet())

    @SuppressLint("MissingPermission")
    fun discover(start: Boolean) {
        val a = ba() ?: return
        runCatching { if (start) { a.cancelDiscovery(); a.startDiscovery() } else a.cancelDiscovery() }
    }

    /** Pairs with a nearby device (Android shows its own pairing code prompt if the device needs one). */
    @SuppressLint("MissingPermission")
    fun pair(address: String): Boolean = runCatching {
        ba()?.cancelDiscovery()
        ba()?.getRemoteDevice(address)?.createBond() == true
    }.getOrDefault(false)

    /**
     * Connects or disconnects a paired device on its audio and input profiles.
     * Uses the profiles' hidden connect()/disconnect(); they are allowed with
     * BLUETOOTH_ADMIN on Android 10. Calls [done] with whether it worked.
     */
    @SuppressLint("MissingPermission")
    fun link(ctx: Context, address: String, connect: Boolean, done: (Boolean) -> Unit) {
        val a = ba() ?: return done(false)
        val d = runCatching { a.getRemoteDevice(address) }.getOrNull() ?: return done(false)
        val profiles = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET, 4 /* HID_HOST */)
        var left = profiles.size
        var any = false
        profiles.forEach { prof ->
            val ok = runCatching {
                a.getProfileProxy(ctx.applicationContext, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        val r = runCatching {
                            proxy.javaClass.getMethod(if (connect) "connect" else "disconnect", BluetoothDevice::class.java).invoke(proxy, d) as Boolean
                        }.getOrDefault(false)
                        runCatching { a.closeProfileProxy(profile, proxy) }
                        synchronized(this@Radio) { any = any || r; left--; if (left == 0) done(any) }
                    }
                    override fun onServiceDisconnected(profile: Int) {}
                }, prof)
            }.getOrDefault(false)
            if (!ok) synchronized(this) { left--; if (left == 0) done(any) }
        }
    }

    /** Unpairs a device (hidden removeBond(), allowed with BLUETOOTH_ADMIN). */
    fun unpair(address: String): Boolean = runCatching {
        val d = ba()?.getRemoteDevice(address) ?: return false
        d.javaClass.getMethod("removeBond").invoke(d) as Boolean
    }.getOrDefault(false)

    // ---------------------------------------------------------------- shared

    /** Polls [ok] every 150 ms for up to [ms]. Tests make it instant. */
    @Volatile var settleMs: Long? = null

    private fun settle(ms: Long = 4000, ok: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + (settleMs ?: ms)
        while (true) {
            if (ok()) return true
            if (System.currentTimeMillis() >= end) return false
            Thread.sleep(150)
        }
    }

    @Suppress("unused")
    private fun ScanResult.ssidOrNull() = clean(SSID).takeIf { it.isNotBlank() }
}
