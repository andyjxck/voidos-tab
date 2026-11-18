package com.andysocial.voidos

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver

/**
 * The home screen Android shows at power-on while the tablet's storage is
 * still locked, in place of Settings' "FallbackHome" (the black screen with
 * the starting message that showed after the boot animation).
 *
 * Android keeps its boot animation on screen until the home screen has drawn
 * (WindowManager waits while a visible window has not drawn yet: Android 10
 * DisplayContent.checkWaitingForWindows). So this screen simply does not draw:
 * the lava lamp keeps looping until the storage unlocks, then VoidOS's own hub
 * opens and draws its first frame, the still logo, and only then does the boot
 * animation finish its loop, drain back onto the still logo and hand over to
 * the start animation in ui/Boot.kt.
 *
 * It never hides anything that needs the person:
 * - If Android has its own PIN, pattern or password, it draws straight away
 *   (the still logo) so Android's unlock screen can show.
 * - After [HOLD_MS] it draws the still logo whatever happens, so system error
 *   messages behind it can be seen (WindowManager's own limit is 30 s).
 * - After [GIVE_UP_MS] still locked, it turns itself off and closes, and
 *   Android's own starting screen takes over. VoidOS turns it back on once
 *   the tablet has unlocked.
 * Recovery, fastboot and the "Optimising apps" upgrade screen are drawn by
 * Android before any home screen starts, so nothing here touches them.
 */
class BootCover : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private var hold = false
    private var done = false
    private var rx: BroadcastReceiver? = null
    private var still: StillView? = null

    /** True while it is keeping the boot animation on screen (for tests). */
    internal val holding get() = hold && !done

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (unlocked(this)) { goHome("unlocked"); return }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        val v = StillView(this)
        still = v
        setContentView(v)
        val secure = runCatching { applicationContext.getSystemService(KeyguardManager::class.java).isDeviceSecure }.getOrDefault(true)
        hold = !secure
        if (hold) {
            v.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (hold) return false          // not drawn yet: the boot animation stays
                    v.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
            })
            h.postDelayed({ release("hold limit") }, HOLD_MS)
        }
        rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { goHome("user unlocked") }
        }
        registerReceiver(rx, IntentFilter(Intent.ACTION_USER_UNLOCKED))
        h.postDelayed({ giveUp() }, GIVE_UP_MS)
        // it may have unlocked between the first check and the receiver
        if (unlocked(this)) goHome("unlocked")
        Log.i(TAG, "holding the boot animation: hold=$hold, up ${SystemClock.elapsedRealtime()} ms")
    }

    private fun release(why: String) {
        if (!hold) return
        Log.i(TAG, "drawing the still logo: $why")
        hold = false
        still?.invalidate()
    }

    private fun goHome(why: String) {
        if (done) return
        done = true
        Log.i(TAG, "opening VoidOS: $why")
        runCatching {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClass(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "could not open VoidOS", it) }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    /** Still locked after all this time: step aside for Android's own screen. */
    private fun giveUp() {
        if (done || unlocked(this)) return
        Log.w(TAG, "still locked after ${GIVE_UP_MS / 1000} s: handing over to Android's starting screen")
        done = true
        setEnabled(this, false)
        finish()
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        rx?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    override fun onBackPressed() {}

    /** The still logo, exactly the last frame of the boot animation
     *  (tools/void_balloon.py writes both pictures). */
    private class StillView(c: Context) : View(c) {
        private val p = Paint(Paint.FILTER_BITMAP_FLAG)
        private var bmp: Bitmap? = null
        private var land: Boolean? = null
        private val dst = Rect()

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(BG)
            val l = width > height
            if (bmp == null || land != l) {
                land = l
                bmp = runCatching {
                    BitmapFactory.decodeResource(resources, if (l) R.drawable.boot_still_land else R.drawable.boot_still_port)
                }.getOrNull()
            }
            val b = bmp ?: return
            // fill the screen keeping its shape (the pictures are the panel's size)
            val s = maxOf(width.toFloat() / b.width, height.toFloat() / b.height)
            val w = (b.width * s).toInt(); val hh = (b.height * s).toInt()
            dst.set((width - w) / 2, (height - hh) / 2, (width + w) / 2, (height + hh) / 2)
            canvas.drawBitmap(b, null, dst, p)
        }

        override fun onConfigurationChanged(newConfig: Configuration?) {
            super.onConfigurationChanged(newConfig)
            invalidate()
        }
    }

    companion object {
        private const val TAG = "VoidBoot"
        /** Longest the boot animation is kept up past Android's own start. */
        const val HOLD_MS = 20_000L
        /** Longest this screen waits for the unlock before stepping aside. */
        const val GIVE_UP_MS = 90_000L
        private const val BG = 0xFF0B0B0D.toInt()

        fun unlocked(c: Context): Boolean =
            runCatching { c.applicationContext.getSystemService(UserManager::class.java).isUserUnlocked }.getOrDefault(true)

        private fun setEnabled(c: Context, on: Boolean) = runCatching {
            c.packageManager.setComponentEnabledSetting(ComponentName(c, BootCover::class.java),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP)
        }

        /** Turns it back on after a power-on where it had to step aside. */
        fun reenable(c: Context) {
            val st = runCatching { c.packageManager.getComponentEnabledSetting(ComponentName(c, BootCover::class.java)) }
                .getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            if (st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) setEnabled(c, true)
        }
    }
}
