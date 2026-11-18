package com.andysocial.voidos

import android.content.Context
import android.provider.Settings
import java.io.File

/**
 * The tablet switches VoidOS drives itself, mostly through root (VoidOS is device owner
 * and rooted, so it can set what the system settings app would). Every call blocks, so
 * run them off the main thread. Reads are plain settings reads and are cheap.
 */
object Device {
    private fun g(ctx: Context, k: String) = runCatching { Settings.Global.getInt(ctx.contentResolver, k, 0) }.getOrDefault(0)
    private fun s(ctx: Context, k: String) = runCatching { Settings.Secure.getInt(ctx.contentResolver, k, 0) }.getOrDefault(0)

    // ---- connections
    fun airplaneOn(ctx: Context) = g(ctx, Settings.Global.AIRPLANE_MODE_ON) == 1
    fun airplane(on: Boolean) = Su.ok(
        "settings put global airplane_mode_on ${if (on) 1 else 0}; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $on >/dev/null",
    )

    @Suppress("DEPRECATION")
    fun locationOn(ctx: Context) = s(ctx, Settings.Secure.LOCATION_MODE) != Settings.Secure.LOCATION_MODE_OFF
    /** Location on or off; VoidOS stops forcing it on while you keep it off ([Store.locationOff]). */
    fun location(on: Boolean) = Su.ok("settings put secure location_mode ${if (on) 3 else 0}")

    /** Private DNS: "off", "opportunistic" (automatic) or a hostname such as dns.adguard.com. */
    fun privateDns(ctx: Context): String = when (runCatching { Settings.Global.getString(ctx.contentResolver, "private_dns_mode") }.getOrNull()) {
        "off" -> "off"
        "hostname" -> runCatching { Settings.Global.getString(ctx.contentResolver, "private_dns_specifier") }.getOrNull() ?: "off"
        else -> "opportunistic"
    }
    fun setPrivateDns(v: String) = when (v) {
        "off", "opportunistic" -> Su.ok("settings put global private_dns_mode $v")
        else -> Su.ok("settings put global private_dns_specifier ${Su.q(v)}; settings put global private_dns_mode hostname")
    }

    /** The saved password for [ssid], read from the system's Wi-Fi store; null if open or unreadable. */
    fun wifiPassword(ssid: String): String? {
        val xml = Su.run("cat /data/misc/wifi/WifiConfigStore.xml").takeIf { it.ok }?.text ?: return null
        return wifiPasswordIn(xml, ssid)
    }

    internal fun wifiPasswordIn(xml: String, ssid: String): String? {
        val blocks = xml.split("<Network>").drop(1)
        for (b in blocks) {
            val name = Regex("<string name=\"SSID\">&quot;(.*?)&quot;</string>").find(b)?.groupValues?.get(1) ?: continue
            if (unescape(name) != ssid) continue
            val psk = Regex("<string name=\"PreSharedKey\">&quot;(.*?)&quot;</string>").find(b)?.groupValues?.get(1) ?: return null
            return unescape(psk)
        }
        return null
    }
    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&apos;", "'").replace("&quot;", "\"")

    /** The text a phone camera reads to join a network: WIFI:T:WPA;S:name;P:password;; */
    fun wifiQrText(ssid: String, pass: String?): String {
        fun esc(v: String) = v.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"")
        return if (pass.isNullOrEmpty()) "WIFI:T:nopass;S:${esc(ssid)};;" else "WIFI:T:WPA;S:${esc(ssid)};P:${esc(pass)};;"
    }

    // ---- power
    fun saverOn(ctx: Context) = g(ctx, "low_power") == 1
    fun saver(on: Boolean) = Su.ok("settings put global low_power ${if (on) 1 else 0}")
    fun reboot(kind: String) = Su.ok(
        when (kind) { "off" -> "svc power shutdown || reboot -p"; "recovery" -> "reboot recovery"; else -> "svc power reboot || reboot" },
        timeoutS = 20,
    )

    // ---- display
    fun greyOn(ctx: Context) = s(ctx, "accessibility_display_daltonizer_enabled") == 1 && s(ctx, "accessibility_display_daltonizer") == 0
    fun grey(on: Boolean) = Su.ok(
        if (on) "settings put secure accessibility_display_daltonizer 0; settings put secure accessibility_display_daltonizer_enabled 1"
        else "settings put secure accessibility_display_daltonizer_enabled 0",
    )
    fun invertOn(ctx: Context) = s(ctx, "accessibility_display_inversion_enabled") == 1
    fun invert(on: Boolean) = Su.ok("settings put secure accessibility_display_inversion_enabled ${if (on) 1 else 0}")
    fun magnifyOn(ctx: Context) = s(ctx, "accessibility_display_magnification_enabled") == 1
    fun magnify(on: Boolean) = Su.ok("settings put secure accessibility_display_magnification_enabled ${if (on) 1 else 0}")
    fun darkApps(on: Boolean) = Su.ok("cmd uimode night ${if (on) "yes" else "no"}")
    fun darkAppsOn(ctx: Context) = (ctx.getSystemService(android.app.UiModeManager::class.java)?.nightMode ?: 1) == android.app.UiModeManager.MODE_NIGHT_YES
    fun fontScale(ctx: Context) = runCatching { Settings.System.getFloat(ctx.contentResolver, Settings.System.FONT_SCALE, 1f) }.getOrDefault(1f)
    fun setFontScale(f: Float) = Su.ok("settings put system font_scale $f")
    /** Display size: the screen's density, or null for the tablet's own. */
    fun setDensity(dpi: Int?) = Su.ok(if (dpi == null) "wm density reset" else "wm density $dpi")
    /** Night Light warmth, 0 (mild) to 1 (warmest): the colour temperature from 4800K down to 2600K. */
    fun warmth(f: Float) = Su.ok("settings put secure night_display_color_temperature ${(4800 - f.coerceIn(0f, 1f) * 2200).toInt()}")
    fun nightSchedule(sunset: Boolean) = Su.ok("settings put secure night_display_auto_mode ${if (sunset) 2 else 0}")

    // ---- capture
    /** Saves a screenshot to Pictures/Screenshots and returns its path. */
    fun screenshot(): String? {
        val f = "/sdcard/Pictures/Screenshots/VoidOS_${stamp()}.png"
        val ok = Su.ok("mkdir -p /sdcard/Pictures/Screenshots && screencap -p $f && am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$f >/dev/null")
        return if (ok) f else null
    }
    @Volatile var recording: String? = null
        private set
    /** Starts recording the screen to Movies (Android 10 caps one file at 3 minutes). */
    fun startRecording(): Boolean {
        val f = "/sdcard/Movies/VoidOS_${stamp()}.mp4"
        val ok = Su.ok("mkdir -p /sdcard/Movies && (nohup screenrecord --bit-rate 6000000 $f >/dev/null 2>&1 &)")
        if (ok) recording = f
        return ok
    }
    fun stopRecording(): String? {
        val f = recording ?: return null
        Su.ok("pkill -INT screenrecord; sleep 1; am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$f >/dev/null")
        recording = null
        return f
    }
    private fun stamp() = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.UK).format(java.util.Date())

    // ---- storage
    /** Mounted removable volumes (microSD, USB drives): name, path, free and total bytes. */
    data class Vol(val name: String, val path: File, val free: Long, val total: Long, val id: String?)
    fun volumes(ctx: Context): List<Vol> {
        val sm = ctx.getSystemService(android.os.storage.StorageManager::class.java) ?: return emptyList()
        return sm.storageVolumes.filter { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED }.mapNotNull { v ->
            val p = v.uuid?.let { File("/storage/$it") } ?: return@mapNotNull null
            Vol(v.getDescription(ctx), p, p.usableSpace, p.totalSpace, v.uuid)
        }
    }
    fun eject(id: String) = Su.ok("for v in $(sm list-volumes public | grep ${Su.q(id)} | cut -d' ' -f1); do sm unmount \$v; done", timeoutS = 20)
    /** USB mode for a computer: "mtp" (files), "ptp" (photos) or "none" (charge only). */
    fun usbMode(mode: String) = Su.ok("svc usb setFunctions ${if (mode == "none") "" else mode}")
}
