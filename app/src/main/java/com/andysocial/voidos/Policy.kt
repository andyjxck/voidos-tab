package com.andysocial.voidos

import android.Manifest
import android.app.WallpaperManager
import android.app.admin.DevicePolicyManager
import android.app.admin.SystemUpdatePolicy
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.UserManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.provider.Settings
import android.util.Log
import androidx.core.content.res.ResourcesCompat

/**
 * Everything VoidOS does with DevicePolicyManager lives here. Every call is
 * wrapped so a refusal from the system can never crash the home screen.
 */
object Policy {
    private const val TAG = "VoidOS"

    fun dpm(c: Context): DevicePolicyManager = c.getSystemService(DevicePolicyManager::class.java)
    fun admin(c: Context) = ComponentName(c, VoidAdmin::class.java)
    fun isOwner(c: Context): Boolean = runCatching { dpm(c).isDeviceOwnerApp(c.packageName) }.getOrDefault(false)

    /**
     * Change the system time zone. As device owner on Android 11+ VoidOS asks
     * the device policy manager; otherwise it tries AlarmManager, which only a
     * system app (a flashed/priv-app VoidOS) is allowed to do. False if neither works.
     */
    @android.annotation.SuppressLint("MissingPermission")
    /** Grants VoidOS one of its own runtime permissions: as device owner, else through root. */
    fun grantSelf(c: Context, perm: String): Boolean {
        val owner = runCatching { dpm(c).setPermissionGrantState(admin(c), c.packageName, perm, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) }.getOrDefault(false)
        return owner || Su.ok("pm grant ${c.packageName} $perm")
    }

    fun setTimeZone(c: Context, zone: String): Boolean {
        if (isOwner(c) && android.os.Build.VERSION.SDK_INT >= 30 &&
            runCatching { dpm(c).setTimeZone(admin(c), zone) }.getOrDefault(false)) return true
        return runCatching { c.getSystemService(android.app.AlarmManager::class.java).setTimeZone(zone); true }.getOrDefault(false)
    }

    const val SET_OWNER_CMD = "adb shell dpm set-device-owner com.andysocial.voidos/.VoidAdmin"

    private inline fun safe(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.w(TAG, "$what refused: ${t.message}") }
    }

    /** Packages that stay usable even in lock mode (settings is only reachable via the escape PIN). */
    private val systemHelpers = listOf("com.android.settings", "com.android.bluetooth", "com.android.packageinstaller", "com.google.android.packageinstaller")

    fun applyAll(c: Context) {
        val store = (c.applicationContext as VoidApp).store
        Stealth.sync(c, store)
        // VoidOS's volume sliders, power menu and PIN pad: the accessibility service, switched on through root
        val sys = store.setupDone && (store.voidVolume || store.voidPower || store.voidKeyguard)
        Thread { runCatching { VoidKeysService.sync(c.applicationContext, sys) } }.apply { isDaemon = true; start() }
        // 2.0.1: the VoidOS keyboard is the only keyboard
        Thread { runCatching { com.andysocial.voidos.keyboard.VoidKeyboardService.makeDefault(c.applicationContext) } }.apply { isDaemon = true; start() }
        if (!isOwner(c)) return
        safe("keyboard") {
            dpm(c).setPermittedInputMethods(admin(c), listOf(c.packageName))
            dpm(c).setSecureSetting(admin(c), android.provider.Settings.Secure.DEFAULT_INPUT_METHOD, com.andysocial.voidos.keyboard.VoidKeyboardService.ID)
        }
        val d = dpm(c)
        val a = admin(c)
        val pkg = c.packageName

        // VoidOS is always the home screen
        safe("home") {
            val f = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT)
            }
            d.addPersistentPreferredActivity(a, f, ComponentName(c, MainActivity::class.java))
        }

        // grant our own runtime permissions (Wi-Fi/Bluetooth scans, local music)
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
        ).forEach { perm ->
            safe("grant $perm") { d.setPermissionGrantState(a, pkg, perm, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) }
        }

        safe("keyguard") { d.setKeyguardDisabled(a, store.noLockScreen) }
        safe("statusbar") { d.setStatusBarDisabled(a, store.hideBars) }

        // updates: Android lets a device owner postpone, not block forever
        safe("updates") {
            d.setSystemUpdatePolicy(a, if (store.blockUpdates) SystemUpdatePolicy.createPostponeInstallPolicy() else null)
        }

        // desk mode: keep the screen on while it's charging (AC, USB, wireless)
        safe("stayon") { d.setGlobalSetting(a, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, if (store.deskMode) "7" else "0") }

        // lock mode whitelist: VoidOS, the apps you allowed, and system helpers
        safe("locktask") {
            val pm = c.packageManager
            val helpers = systemHelpers.filter { isInstalled(pm, it) }
            d.setLockTaskPackages(a, (listOf(pkg) + store.allowed + helpers).distinct().toTypedArray())
            d.setLockTaskFeatures(
                a,
                DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
                    DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                    DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD or
                    (if (store.hideBars) 0 else DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO)
            )
        }

        // no Guest mode and no extra users: VoidOS dropped its own guest mode, and these stop
        // Android's (the guest entry in the user switcher, Settings > Multiple users) too
        noGuestRestrictions.forEach { r -> safe("restrict $r") { d.addUserRestriction(a, r) } }

        // location on, so Wi-Fi and Bluetooth scans return results on Android 10
        safe("location") { d.setSecureSetting(a, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_HIGH_ACCURACY.toString()) }

        applyHiding(c)

        if (!store.wallpaperDone) {
            Thread {
                safe("wallpaper") {
                    setVoidWallpaper(c)
                    store.set("wallpaperDone", true)
                }
            }.start()
        }
    }

    private fun isInstalled(pm: PackageManager, p: String) =
        runCatching { pm.getPackageInfo(p, PackageManager.MATCH_UNINSTALLED_PACKAGES); true }.getOrDefault(false)

    // ------------------------------------------------------------------ hiding

    /** Android's own ways into a guest or another user, closed while VoidOS runs the tablet. */
    internal val noGuestRestrictions = listOf(UserManager.DISALLOW_ADD_USER, UserManager.DISALLOW_USER_SWITCH)

    /**
     * Kids Space was taken out of VoidOS, so Google's Kids Space app (and Lenovo's kids mode,
     * where a tablet has it) is kept hidden: it can't be opened or allowed back in Manage apps.
     */
    internal val removedKidsApps = setOf("com.google.android.apps.kids.home", "com.lenovo.kidsmode", "com.tblenovo.kidsmode")

    private val bloatPrefixes = listOf("com.lenovo.", "com.tblenovo.", "com.zui.")
    private val knownBloat = setOf(
        "com.netflix.mediaclient", "com.netflix.partner.activation", "com.amazon.mShop.android.shopping",
        "com.amazon.kindle", "com.facebook.katana", "com.facebook.appmanager", "com.facebook.services",
        "com.facebook.system", "com.booking", "com.microsoft.office.officehubrow", "com.dolby.daxappui",
    )
    /** Never hidden, whatever their name: the system needs these to stay recoverable. */
    private fun isProtected(p: String) =
        p == "com.andysocial.voidos" ||
            listOf("launcher", "setupwizard", "systemui", "settings", "provision", "inputmethod", "keyboard", "packageinstaller", "permissioncontroller", "bluetooth", "wifi", "telephony", "media")
                .any { p.contains(it, ignoreCase = true) }

    /** Lenovo's own updater, matched on whole name parts so e.g. "rotation" never counts as "ota". */
    private fun isUpdater(p: String) =
        bloatPrefixes.any { p.startsWith(it) } &&
            p.lowercase().split('.').drop(2).any { it == "ota" || it == "fota" || it.contains("update") }

    /** What "Hide Lenovo extras" would hide on this tablet: launchable Lenovo/partner apps only. */
    fun bloatCandidates(c: Context): List<String> {
        val pm = c.packageManager
        val launchable = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_UNINSTALLED_PACKAGES
        ).map { it.activityInfo.packageName }.toSet()
        return launchable.filter { p ->
            !isProtected(p) && (p in knownBloat || bloatPrefixes.any { p.startsWith(it) })
        }.sorted()
    }

    fun updaterCandidates(c: Context): List<String> =
        c.packageManager.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
            .map { it.packageName }.filter { isUpdater(it) && !isProtected(it) }.sorted()

    private fun applyHiding(c: Context) {
        val store = (c.applicationContext as VoidApp).store
        val d = dpm(c)
        val a = admin(c)
        val want = buildSet {
            if (store.hideLenovo) addAll(bloatCandidates(c).filter { it !in store.allowed })
            if (store.blockUpdates) addAll(updaterCandidates(c))
            addAll(removedKidsApps.filter { isInstalled(c.packageManager, it) })
        }
        val before = store.hiddenByUs()
        // un-hide what we no longer want hidden
        (before - want).forEach { p -> safe("unhide $p") { d.setApplicationHidden(a, p, false) } }
        val now = mutableSetOf<String>()
        want.forEach { p ->
            safe("hide $p") { if (d.setApplicationHidden(a, p, true)) now += p }
        }
        store.setHiddenByUs(now)
    }

    // ----------------------------------------------------------------- release

    /**
     * Hands the tablet back to stock Android: un-hides everything, drops every
     * policy, then removes VoidOS as device owner. Returns false if VoidOS
     * wasn't device owner to begin with.
     */
    fun release(c: Context): Boolean {
        if (!isOwner(c)) return false
        val d = dpm(c)
        val a = admin(c)
        val store = (c.applicationContext as VoidApp).store
        Thread { runCatching { Stealth.apply(c.applicationContext, false) } }.start()
        safe("locktask clear") { d.setLockTaskPackages(a, arrayOf()) }
        // un-hide anything hidden, ours or not
        val all = c.packageManager.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
        all.forEach { ai ->
            safe("unhide ${ai.packageName}") {
                if (d.isApplicationHidden(a, ai.packageName)) d.setApplicationHidden(a, ai.packageName, false)
            }
        }
        store.setHiddenByUs(emptySet())
        safe("home clear") { d.clearPackagePersistentPreferredActivities(a, c.packageName) }
        safe("keyguard") { d.setKeyguardDisabled(a, false) }
        safe("statusbar") { d.setStatusBarDisabled(a, false) }
        safe("updates") { d.setSystemUpdatePolicy(a, null) }
        safe("stayon") { d.setGlobalSetting(a, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "0") }
        noGuestRestrictions.forEach { r -> safe("unrestrict $r") { d.clearUserRestriction(a, r) } }
        store.set("kiosk", false)
        var ok = false
        safe("clear owner") {
            @Suppress("DEPRECATION")
            d.clearDeviceOwnerApp(c.packageName)
            ok = true
        }
        return ok
    }

    // --------------------------------------------------------------- wallpaper

    private fun setVoidWallpaper(c: Context) {
        val w = 1280; val h = 800
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val cv = Canvas(bmp)
        cv.drawColor(Color.rgb(8, 10, 18))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.rgb(42, 51, 68) }
        val cx = w / 2f; val cy = h / 2f; val r = 230f; val k = r * 0.4142f
        val oct = Path().apply {
            moveTo(cx - k, cy - r); lineTo(cx + k, cy - r); lineTo(cx + r, cy - k); lineTo(cx + r, cy + k)
            lineTo(cx + k, cy + r); lineTo(cx - k, cy + r); lineTo(cx - r, cy + k); lineTo(cx - r, cy - k); close()
        }
        cv.drawPath(oct, p)
        val tf: Typeface? = runCatching { ResourcesCompat.getFont(c, R.font.kenney_future) }.getOrNull()
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(70, 216, 224); textSize = 64f; textAlign = Paint.Align.CENTER; typeface = tf
        }
        cv.drawText("VOIDOS", cx, cy + 22f, t)
        val wm = WallpaperManager.getInstance(c)
        wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
    }
}
