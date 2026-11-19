package com.andysocial.voidos

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.andysocial.voidos.ui.EdgeSwipe

/**
 * Closing an app completely from the app wheel: hold its slot, then swipe down.
 *
 * With root (the Magisk module) it's `am force-stop`, which ends every process of the app
 * and takes its activities and tasks with it, the same as Settings' Force stop. Without
 * root Android only lets VoidOS end an app's background processes, so that's the fallback,
 * and the app may keep running if it's on screen. Either way it leaves the wheel's list.
 *
 * VoidOS itself, Android's system UI and the keyboard are never closed this way.
 */
object AppKill {
    private val PROTECTED = setOf("android", "com.android.systemui", "com.android.phone", "com.android.settings")

    /** Whether [pkg] may be closed from the wheel. */
    fun closable(ctx: Context, pkg: String): Boolean =
        pkg.isNotBlank() && pkg != ctx.packageName && pkg !in PROTECTED && pkg != keyboard(ctx) &&
            Regex("[A-Za-z0-9_.]+").matches(pkg)

    /** The shell command that closes [pkg] completely. */
    fun command(pkg: String) = "am force-stop $pkg"

    /** Closes [pkg]. True when it was force-stopped with root, false when only its background processes could go. */
    suspend fun close(ctx: Context, store: Store, pkg: String): Boolean {
        if (!closable(ctx, pkg)) return false
        store.forgetRecent(pkg)
        return withContext(Dispatchers.IO) {
            val root = runCatching { Su.ok(command(pkg), timeoutS = 8) }.getOrDefault(false)
            if (!root) runCatching {
                (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg)
            }
            EdgeSwipe.log("closed $pkg from the wheel (${if (root) "force-stop" else "background only, no root"})")
            root
        }
    }

    private fun keyboard(ctx: Context): String? = runCatching {
        android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
    }.getOrNull()
}
