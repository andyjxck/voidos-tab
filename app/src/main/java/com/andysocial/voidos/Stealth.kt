package com.andysocial.voidos

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.provider.Settings

/**
 * Hide Android. With "Hide Android" on (Settings > Lock mode), VoidOS uses root to:
 *  - keep the navigation and status bars hidden in every app (Android 10's immersive policy),
 *  - switch off the stock Recents screen, so VoidOS's own app switcher is the only one,
 *  - switch off the entry points into Android's Settings app (its launcher icon and the
 *    main Settings screen), so every setting lives in VoidOS,
 *  - give VoidOS notification access, Do Not Disturb access and usage access, so nothing
 *    ever sends you to Android to allow them.
 * Turning it off, the hidden exit and Release undo every part. The hidden exit
 * (20 taps on the device name in About, then the PIN) always switches Settings back on
 * before it opens it, so you can't be locked out.
 */
object Stealth {
    private const val SETTINGS = "com.android.settings"
    private const val K_OFF = "stealthDisabled"

    /** The stock Recents activity Android 10 uses (the launcher's, on most tablets). */
    fun recentsComponent(): String? = runCatching {
        val r = Resources.getSystem()
        val id = r.getIdentifier("config_recentsComponentName", "string", "android")
        if (id == 0) null else r.getString(id).trim().takeIf { it.contains('/') }
    }.getOrNull()

    /** The doors into Android's Settings app: its launcher icon and the main Settings screen. */
    fun settingsEntries(c: Context): List<String> {
        val pm = c.packageManager
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_ALL
        val probes = listOf(
            Intent(Settings.ACTION_SETTINGS),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(SETTINGS),
        )
        return probes.flatMap { i -> runCatching { pm.queryIntentActivities(i, flags) }.getOrDefault(emptyList()) }
            .map { it.activityInfo }
            .filter { it.packageName == SETTINGS }
            .map { "${it.packageName}/${it.name}" }
            .distinct()
    }

    /** The shell commands that hide (on) or bring back (off) Android. Pure, so tests can read them. */
    fun commands(on: Boolean, entries: List<String>, recents: String?, pkg: String): List<String> {
        val parts = (entries + listOfNotNull(recents?.takeIf { !it.startsWith("$pkg/") })).distinct()
        return if (on) {
            listOf("settings put global policy_control immersive.full=*") +
                parts.map { "pm disable ${Su.q(it)}" }
        } else {
            listOf("settings delete global policy_control") +
                parts.map { "pm enable ${Su.q(it)}" }
        }
    }

    /** One-off grants, so VoidOS never has to send you to Android to allow something. */
    fun grantCommands(pkg: String): List<String> = listOf(
        "cmd notification allow_listener $pkg/$pkg.VoidListener",
        "cmd notification allow_dnd $pkg",
        "appops set $pkg GET_USAGE_STATS allow",
        "appops set $pkg WRITE_SETTINGS allow",
        "appops set $pkg SYSTEM_ALERT_WINDOW allow",
        "appops set $pkg PROJECT_MEDIA allow",
        "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS",
        "pm grant $pkg android.permission.READ_CALENDAR",
    )

    @Volatile private var busy = false
    @Volatile private var granted = false

    /** Hide or bring back Android. Blocks on root: call off the main thread. */
    fun apply(c: Context, on: Boolean) {
        if (busy) return
        busy = true
        try {
            val p = c.getSharedPreferences("voidos", Context.MODE_PRIVATE)
            // remember what we switched off, so "off" re-enables exactly that even once it no longer resolves
            val remembered = p.getStringSet(K_OFF, emptySet())!!.toList()
            val entries = (settingsEntries(c) + remembered).distinct()
            val pm = c.packageManager
            // only touch what is not already the way we want it: pm disable restarts the app it belongs to
            val pending = (entries + listOfNotNull(recentsComponent())).distinct().filter { flat ->
                val cn = android.content.ComponentName.unflattenFromString(flat) ?: return@filter false
                val st = runCatching { pm.getComponentEnabledSetting(cn) }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
                val off = st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                off != on
            }
            val policy = runCatching { Settings.Global.getString(c.contentResolver, "policy_control") }.getOrNull().orEmpty()
            val cmds = commands(on, pending.filter { it in entries }, pending.firstOrNull { it !in entries }, c.packageName)
                .filter { !it.startsWith("settings ") || (on != policy.startsWith("immersive.full=*")) } +
                (if (on && !granted) grantCommands(c.packageName) else emptyList())
            if (cmds.isNotEmpty()) Su.run(cmds.joinToString("; "), timeoutS = 25)
            if (on) granted = true
            p.edit().putStringSet(K_OFF, if (on) entries.toSet() else emptySet()).apply()
        } finally { busy = false }
    }

    /** Only the grants, for when Hide Android is off but an Allow button was pressed. */
    fun grant(c: Context): Boolean = Su.ok(grantCommands(c.packageName).joinToString("; "), timeoutS = 20)

    /** The hidden exit: Settings must open, so switch its doors back on first. Blocks briefly. */
    fun openSettingsDoors(c: Context) {
        val p = c.getSharedPreferences("voidos", Context.MODE_PRIVATE)
        val entries = p.getStringSet(K_OFF, emptySet())!!.filter { it.startsWith("$SETTINGS/") }
        if (entries.isNotEmpty()) Su.run(entries.joinToString("; ") { "pm enable ${Su.q(it)}" }, timeoutS = 10)
    }

    /** Hide Android in the background, the way the store says. */
    fun sync(c: Context, store: Store) {
        val app = c.applicationContext
        Thread { runCatching { apply(app, store.hideBars && store.setupDone) } }.apply { isDaemon = true; start() }
    }
}
