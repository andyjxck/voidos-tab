package com.andysocial.voidos

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    /** Outlives any one screen, so a close that's under way finishes after the switcher goes. */
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Closes [pkg], from the wheel or the switcher. It's marked closed first (saved), so it leaves
     * both lists at once and stays out of the switcher until it's opened again, even though
     * Android's usage records still say it was used. Then the app itself is stopped when it may
     * be (never VoidOS, the system UI or the keyboard). True when it was force-stopped with root.
     */
    suspend fun close(ctx: Context, store: Store, pkg: String): Boolean {
        if (pkg.isBlank() || pkg == ctx.packageName) return false
        store.markClosed(pkg)
        if (!closable(ctx, pkg)) return false
        return withContext(Dispatchers.IO) { stop(ctx, pkg) }
    }

    /** A card closed in the switcher: it also leaves the book if it's the app half. */
    suspend fun closeFromSwitcher(ctx: Context, store: Store, pkg: String) {
        if (com.andysocial.voidos.ui.Book.state.value.app?.key == pkg) runCatching { com.andysocial.voidos.ui.Book.closeApp(ctx, store) }
        close(ctx, store, pkg)
    }

    /**
     * Close all: every app in [pkgs] (the switcher's cards and the wheel's list) is marked closed
     * and the moment is saved, so nothing used before it comes back after reopening the
     * switcher, going home or a restart. The apps are then stopped in the background.
     */
    fun closeAll(ctx: Context, store: Store, pkgs: List<String>, scope: CoroutineScope) {
        val gone = pkgs.filter { it.isNotBlank() && it != ctx.packageName }.distinct()
        store.closeAll(gone)
        val bookApp = com.andysocial.voidos.ui.Book.state.value.app?.key
        if (bookApp != null && bookApp in gone) scope.launch { runCatching { com.andysocial.voidos.ui.Book.closeApp(ctx, store) } }
        bg.launch { gone.filter { closable(ctx, it) }.forEach { runCatching { stop(ctx, it) } } }
    }

    /** Ends [pkg]: force-stop with root, else Android's background-process kill. Blocks. */
    private fun stop(ctx: Context, pkg: String): Boolean {
        val root = runCatching { Su.ok(command(pkg), timeoutS = 8) }.getOrDefault(false)
        if (!root) runCatching {
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg)
        }
        EdgeSwipe.log("closed $pkg (${if (root) "force-stop" else "background only, no root"})")
        return root
    }

    private fun keyboard(ctx: Context): String? = runCatching {
        android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
    }.getOrNull()
}
