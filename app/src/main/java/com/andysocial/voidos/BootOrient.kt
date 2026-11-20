package com.andysocial.voidos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Surface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Keeps the boot and shutdown animations the way round the tablet is held.
 *
 * Android 10's boot animation always draws in the panel's own portrait and
 * can't read the sensor, so the VoidOS boot module carries a landscape copy
 * of each animation for either way round (orient/90 and orient/270, Display
 * rotation) and a script, orient.sh, that copies the matching pair over the
 * ones Magisk shows at /system/media and /product/media.
 *
 * orient.sh writes into the same files rather than replacing them: Magisk
 * mounts each module file over the system one, so a write into that file
 * shows at /system/media straight away (the shutdown animation, which plays
 * in this same power-on) and the next boot mounts it again (the boot
 * animation). When the tablet turns, VoidOS waits for it to settle and then
 * runs the script; when Android shuts down it runs it once more for the boot
 * animation only (the shutdown animation is already playing by then).
 */
object BootOrient {
    private const val TAG = "VoidOrient"
    const val MODULE = "/data/adb/modules/voidos_boot"
    private const val SETTLE_MS = 2_500L
    private const val RETRY_MS = 5_000L

    private val h = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var last = -1

    /** 0, 90 or 270: the version for this Display rotation. Upside down
     *  (180) has no version of its own, so it gets the portrait one. */
    fun version(rotation: Int): Int = when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    /** The shell command orient.sh is run with (what the tests check). */
    fun command(version: Int, bootOnly: Boolean): String =
        "sh $MODULE/orient.sh $version" + if (bootOnly) " boot" else ""

    fun watch(c: Context) {
        val app = c.applicationContext
        val dm = app.getSystemService(DisplayManager::class.java) ?: return
        // once VoidOS has started shutting down, the turn to portrait shutdown makes is not the way the tablet is held
        val settle = Runnable { if (pinned >= 0) return@Runnable; val r = rotation(app); settled = r; sync(app, r, false) }
        dm.registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) {}
            override fun onDisplayRemoved(id: Int) {}
            override fun onDisplayChanged(id: Int) {
                if (id != Display.DEFAULT_DISPLAY) return
                h.removeCallbacks(settle)
                h.postDelayed(settle, SETTLE_MS)
            }
        }, h)
        h.postDelayed(settle, SETTLE_MS)
    }

    fun rotation(c: Context): Int = runCatching {
        c.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).rotation
    }.getOrDefault(Surface.ROTATION_0)

    fun sync(c: Context, rotation: Int, bootOnly: Boolean, wait: Boolean = false) {
        val v = version(rotation)
        if (!bootOnly && v == last) return
        // (/data/adb can't be read without root: orient.sh's exit code says
        // whether the module is there)
        val job = Runnable {
            val code = su(command(v, bootOnly), if (bootOnly) 6 else 30)
            if (code == 0 && !bootOnly) last = v
            // 4: an animation is still playing (VoidOS starts under the boot
            // animation's last loop), so try again shortly
            if (code == 4 && !bootOnly) h.postDelayed({ sync(c, rotation(c), false) }, RETRY_MS)
            Log.i(TAG, "orient.sh $v${if (bootOnly) " boot" else ""}: exit $code")
        }
        if (wait) runCatching { io.submit(job).get(8, TimeUnit.SECONDS) } else io.execute(job)
    }

    private fun su(cmd: String, timeoutS: Long): Int = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().use { it.readText() }
        if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) { p.destroy(); -2 } else p.exitValue()
    }.getOrDefault(-1)

    /**
     * The rotation the tablet was held at when VoidOS started shutting down, or -1. By the time
     * Android's shutdown broadcast arrives, MediaTek's shutdown code has already turned the screen
     * to portrait for its animation, so asking then would always say portrait.
     */
    @Volatile var pinned = -1
        private set
    /** The rotation the screen last settled at (before any turn shutdown makes), or -1. */
    @Volatile var settled = -1
        private set

    fun pin(rotation: Int) { pinned = rotation }

    /** Android is shutting down: the next boot plays the way the tablet was held. */
    class ShutdownReceiver : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != Intent.ACTION_SHUTDOWN) return
            val pr = goAsync()
            Thread {
                try { sync(c, bootRotation(pinned, settled, rotation(c)), bootOnly = true, wait = true) } finally { pr.finish() }
            }.start()
        }
    }
}

/** Pure: which rotation the next boot should play in: as held when VoidOS began shutting down, else as last settled. */
internal fun bootRotation(pinned: Int, settled: Int, now: Int): Int = when {
    pinned >= 0 -> pinned
    settled >= 0 -> settled
    else -> now
}
