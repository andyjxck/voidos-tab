package com.andysocial.voidos

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.provider.CalendarContract
import androidx.compose.runtime.mutableStateOf

/**
 * The smaller pieces from the feature list that need the system: battery use per
 * app, the notification sound, the next calendar event, FM radio, Dolby, the
 * assistant, USB and screenshots. Everything that blocks runs off the
 * main thread.
 */
object Extras {
    // ------------------------------------------------------------ battery use
    data class Use(val pkg: String, val label: String, val mah: Double)

    /** Battery use per app since the last full charge, from Android's battery stats (needs root). */
    fun batteryUse(ctx: Context): List<Use> {
        val out = Su.run("dumpsys batterystats --charged", 30)
        if (!out.ok) return emptyList()
        val pm = ctx.packageManager
        return parseUse(out.text).mapNotNull { (uid, mah) ->
            val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: return@mapNotNull null
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            Use(pkg, label, mah)
        }.groupBy { it.pkg }.map { (_, v) -> v.first().copy(mah = v.sumOf { it.mah }) }.sortedByDescending { it.mah }
    }

    /** Pure: the "Uid u0a123: 45.6" lines of the Estimated power use section, as (uid, mAh). */
    fun parseUse(text: String): List<Pair<Int, Double>> {
        val sec = text.substringAfter("Estimated power use (mAh):", "")
        if (sec.isEmpty()) return emptyList()
        val re = Regex("""^\s+Uid (u\d+a(\d+)|\d+): ([\d.]+)""")
        val outList = mutableListOf<Pair<Int, Double>>()
        for (l in sec.lines().drop(1)) {
            if (l.isNotBlank() && !l.startsWith("  ")) break
            val m = re.find(l) ?: continue
            val uid = m.groupValues[2].toIntOrNull()?.let { 10000 + it } ?: m.groupValues[1].toIntOrNull() ?: continue
            val mah = m.groupValues[3].toDoubleOrNull() ?: continue
            if (uid >= 10000 && mah > 0.05) outList += uid to mah
        }
        return outList
    }

    // ------------------------------------------------------------ notification sound
    data class Sound(val title: String, val uri: Uri?)

    /** VoidOS's own notification sounds (res/raw/notif_*, made by tools/void_sounds.py) and their names. */
    val voidSounds = listOf(
        R.raw.notif_void_drop to "Void Drop", R.raw.notif_void_bubble to "Void Bubble", R.raw.notif_void_orbit to "Void Orbit",
        R.raw.notif_void_glint to "Void Glint", R.raw.notif_void_pulse to "Void Pulse", R.raw.notif_void_rise to "Void Rise",
    )

    /**
     * Puts the Void sounds in the tablet's Notifications folder, where every app's sound picker
     * finds them. Returns true when it added any (so the list is read again once they're scanned).
     */
    fun installVoidSounds(ctx: Context): Boolean {
        val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_NOTIFICATIONS), "Void")
        val added = mutableListOf<String>()
        runCatching {
            dir.mkdirs()
            voidSounds.forEach { (res, name) ->
                val f = java.io.File(dir, "$name.ogg")
                if (!f.exists()) {
                    ctx.resources.openRawResource(res).use { inp -> f.outputStream().use { inp.copyTo(it) } }
                    added += f.absolutePath
                }
            }
        }
        if (added.isEmpty()) return false
        val latch = java.util.concurrent.CountDownLatch(added.size)
        android.media.MediaScannerConnection.scanFile(ctx, added.toTypedArray(), Array(added.size) { "audio/ogg" }) { _, _ -> latch.countDown() }
        runCatching { latch.await(4, java.util.concurrent.TimeUnit.SECONDS) }
        return true
    }

    fun notificationSounds(ctx: Context): List<Sound> {
        installVoidSounds(ctx)
        val rm = RingtoneManager(ctx).apply { setType(RingtoneManager.TYPE_NOTIFICATION) }
        val list = mutableListOf<Sound>()
        runCatching {
            val c = rm.cursor
            while (c.moveToNext()) list += Sound(c.getString(RingtoneManager.TITLE_COLUMN_INDEX), rm.getRingtoneUri(c.position))
        }
        // the Void sounds first, then the tablet's own
        val names = voidSounds.map { it.second }
        return listOf(Sound("None", null)) + list.filter { it.title in names }.sortedBy { names.indexOf(it.title) } + list.filter { it.title !in names }
    }

    fun currentNotificationSound(ctx: Context): Uri? =
        runCatching { RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_NOTIFICATION) }.getOrNull()

    fun setNotificationSound(uri: Uri?): Boolean =
        if (uri == null) Su.ok("settings delete system notification_sound; settings put system notification_sound ''")
        else Su.ok("settings put system notification_sound ${Su.q(uri.toString())}")

    // ------------------------------------------------------------ calendar
    data class Event(val title: String, val begin: Long, val allDay: Boolean)

    /** The next event in the next 14 days from the tablet's calendars, or null. */
    fun nextEvent(ctx: Context, now: Long = System.currentTimeMillis()): Event? = runCatching {
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, now); ContentUris.appendId(b, now + 14L * 86_400_000)
        ctx.contentResolver.query(
            b.build(), arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.END),
            null, null, "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val end = c.getLong(3)
                if (end < now) continue
                return Event(c.getString(0) ?: "Event", c.getLong(1), c.getInt(2) == 1)
            }
            null
        }
    }.getOrNull()

    // ------------------------------------------------------------ FM radio, Dolby, assistant
    val fmPackages = listOf("com.android.fmradio", "com.mediatek.fmradio", "com.lenovo.fmradio", "com.caf.fmradio", "com.mediatek.FMRadio", "com.android.FMRadio")
    val dolbyPackages = listOf("com.dolby.daxappui", "com.dolby.dax2appUI", "com.dolby.ds1appUI", "com.lenovo.dolby")

    fun installed(ctx: Context, pkg: String) = runCatching { ctx.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS); true }.getOrDefault(false)
    fun fmPackage(ctx: Context) = fmPackages.firstOrNull { installed(ctx, it) }
    fun dolbyPackage(ctx: Context) = dolbyPackages.firstOrNull { installed(ctx, it) }

    /**
     * Opens a system app VoidOS may have hidden (FM radio, Dolby): it's shown again first, then
     * started with VoidOS's lock-task rules allowing it.
     */
    fun openSystemApp(ctx: Context, store: Store, pkg: String): Boolean {
        runCatching {
            val d = Policy.dpm(ctx); val a = Policy.admin(ctx)
            if (Policy.isOwner(ctx) && d.isApplicationHidden(a, pkg)) d.setApplicationHidden(a, pkg, false)
        }
        Thread { Su.run("pm enable $pkg") }.start()
        if (pkg !in store.allowed) store.setAllowed(pkg, true)
        (ctx as? MainActivity)?.applyPolicies()
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    fun assistantIntent(ctx: Context): Intent? = listOf(Intent(Intent.ACTION_VOICE_COMMAND), Intent(Intent.ACTION_ASSIST))
        .firstOrNull { it.resolveActivity(ctx.packageManager) != null }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ------------------------------------------------------------ USB
    /** true while a computer is connected over USB (not just a charger); the USB sheet watches it. */
    val usbComputer = mutableStateOf(false)
    /** The USB sheet was dismissed for this connection. */
    val usbDismissed = mutableStateOf(false)

    /** Watches Android's USB state; a new connection to a computer brings the USB sheet up. */
    fun watchUsb(ctx: Context) {
        ctx.registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val connected = i.getBooleanExtra("connected", false)
                val configured = i.getBooleanExtra("configured", false) || i.getBooleanExtra("host_connected", false)
                val pc = connected && configured
                if (pc && !usbComputer.value) usbDismissed.value = false
                usbComputer.value = pc
            }
        }, android.content.IntentFilter("android.hardware.usb.action.USB_STATE"))
    }
}
