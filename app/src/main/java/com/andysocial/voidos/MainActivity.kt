package com.andysocial.voidos

import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.andysocial.voidos.ui.EdgeAction
import com.andysocial.voidos.ui.EdgeRouter
import com.andysocial.voidos.ui.EdgeSwipe
import com.andysocial.voidos.ui.Idle
import com.andysocial.voidos.ui.edgeExclusion
import com.andysocial.voidos.ui.VoidRoot

class MainActivity : ComponentActivity() {
    private val store get() = (application as VoidApp).store

    /** True while you're out in stock Settings via the escape screen. */
    private var visitingAndroid = false
    private var swallowTouch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TapSound.init(this)
        live = java.lang.ref.WeakReference(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        Policy.applyAll(this)
        excludeEdges()
        EdgeSwipe.debug.value = store.edgeDebug
        com.andysocial.voidos.ui.Ambience.motion.value = store.motion
        val dm = resources.displayMetrics
        val anim = try { android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) } catch (_: Throwable) { -1f }
        EdgeSwipe.log("start ${dm.widthPixels}x${dm.heightPixels} density=${dm.density} animScale=$anim version=${BuildConfig.VERSION_NAME}")
        EdgeOverlay.sync(this, store)
        intent?.getStringExtra(EXTRA_MODULE)?.let { com.andysocial.voidos.ui.Idle.openModule.value = it }
        if (intent?.getBooleanExtra(EXTRA_CONTROL, false) == true) com.andysocial.voidos.ui.EdgeSwipe.controlRequest.value = true
        if (intent?.getBooleanExtra(EXTRA_SWITCHER, false) == true) openSwitcher(intent)
        setContent {
            LaunchedEffect(Unit) {
                snapshotFlow { VoidApp.releaseSignal.value }.collect { if (it > 0) leaveLockTask() }
            }
            QuietHaptics { VoidRoot(store, this) }
        }
    }

    override fun onResume() {
        super.onResume()
        visitingAndroid = false
        applyRotation()
        KTimers.check(this)
        Idle.touch()
        applyBars()
        enforceLockTask()
        EdgeOverlay.hostShown(true)
        // the permission may have just been given from the Mac
        if (!EdgeOverlay.allowed.value) EdgeOverlay.sync(this, store)
    }

    override fun onPause() {
        super.onPause()
        // another app (or Android's own settings) is coming in front: the edge strips take over the wheels
        EdgeOverlay.hostShown(false)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) { applyBars(); window.decorView.postDelayed({ checkRestart() }, 1500) }
    }

    /** The no-bar module is in place but Android still has its navigation bar: ask for one restart. */
    fun checkRestart() {
        Stealth.restartNeeded.value = store.hideBars && store.setupDone && Stealth.noBarModule && Stealth.navBarPresent(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already home: jump back to the face
        if (intent.hasCategory(Intent.CATEGORY_HOME)) Idle.homePressed.value++
        // a screen picked on the wheel over another app
        intent.getStringExtra(EXTRA_MODULE)?.let { com.andysocial.voidos.ui.Idle.openModule.value = it }
        if (intent.getBooleanExtra(EXTRA_CONTROL, false)) com.andysocial.voidos.ui.EdgeSwipe.controlRequest.value = true
        if (intent.getBooleanExtra(EXTRA_SWITCHER, false)) openSwitcher(intent)
    }

    /** The switcher, opened from the bottom edge; over another app it also offers Back into that app. */
    private fun openSwitcher(i: Intent) {
        com.andysocial.voidos.ui.Switcher.fromApp.value = i.getBooleanExtra(EXTRA_FROM_APP, false)
        com.andysocial.voidos.ui.Switcher.shown.value = true
    }

    private var bottomDownY = -1f
    private var bottomDownX = 0f

    /**
     * Swipe up from the middle of the bottom edge: VoidOS's own app switcher. Taps and other
     * gestures there go on to the page as usual; only a clear push upwards is taken.
     */
    private fun routeBottom(ev: MotionEvent): Boolean {
        val d = resources.displayMetrics.density
        val h = window.decorView.height.toFloat()
        val w = window.decorView.width.toFloat()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                bottomDownY = if (h > 0 && ev.y > h - BOTTOM_DP * d && kotlin.math.abs(ev.x - w / 2) < w * BOTTOM_HALF_WIDTH) ev.y else -1f
                bottomDownX = ev.x
            }
            MotionEvent.ACTION_MOVE -> if (bottomDownY >= 0) {
                val up = bottomDownY - ev.y
                if (kotlin.math.abs(ev.x - bottomDownX) > 48 * d) bottomDownY = -1f
                else if (up > 36 * d) {
                    bottomDownY = -1f
                    com.andysocial.voidos.ui.Switcher.fromApp.value = false
                    com.andysocial.voidos.ui.Switcher.shown.value = true
                    super.dispatchTouchEvent(MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL })
                    swallowTouch = true
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> bottomDownY = -1f
        }
        return false
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // A touch while the ambient clock is showing only wakes the screen;
        // it never lands on whatever is underneath.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && Idle.ambient.value) {
            swallowTouch = true
        }
        Idle.touch()
        TapSound.onTouch(ev, !swallowTouch && VoidApp.instance.store.tapSound, android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat())
        if (swallowTouch) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallowTouch = false
            return true
        }
        if (routeEdge(ev)) return true
        if (routeBottom(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    // ------------------------------------------------------------ edge wheels

    private val edge by lazy { EdgeRouter(resources.displayMetrics.density) }

    /**
     * Swipes from the side edges open the wheels. This runs before the screen sees the
     * touch, so nothing on the page (the pager, a widget, a list) can take it first.
     * Returns true when the wheel has the event.
     */
    private fun routeEdge(ev: MotionEvent): Boolean {
        val sink = EdgeSwipe.sink
        val width = window.decorView.width.toFloat()
        val t = ev.eventTime
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val on = EdgeSwipe.enabled && sink != null
                val a = edge.down(ev.x, ev.y, width, t, on, EdgeSwipe.rightEnabled)
                EdgeSwipe.lastDown.value = ev.x to ev.y
                val d = minOf(ev.x, width - ev.x) / resources.displayMetrics.density
                if (d <= EdgeSwipe.SOFT_DP * 2 || !on) {
                    EdgeSwipe.log("down x=${ev.x.toInt()} y=${ev.y.toInt()} edge=${d.toInt()}dp win=${width.toInt()}x${window.decorView.height} enabled=${EdgeSwipe.enabled}${if (EdgeSwipe.offReason.isNotEmpty()) " (off: ${EdgeSwipe.offReason})" else ""} sink=${sink != null} -> $a")
                }
                if (a == EdgeAction.CONSUME) sink?.begin(edge.left, ev.x, ev.y, t)
                return a == EdgeAction.CONSUME
            }
            MotionEvent.ACTION_MOVE -> {
                val wasOurs = edge.owned
                return when (val a = edge.move(ev.x, ev.y, t)) {
                    EdgeAction.CLAIM -> {
                        EdgeSwipe.log("flick claimed at x=${ev.x.toInt()} after ${t - ev.downTime}ms")
                        // the screen already saw the press: take it back, then the wheel has it
                        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                        sink?.begin(edge.left, edge.startX, edge.startY, ev.downTime)
                        sink?.move(ev.x, ev.y, t)
                        true
                    }
                    EdgeAction.CONSUME -> { if (wasOurs) sink?.move(ev.x, ev.y, t); true }
                    EdgeAction.PASS -> false
                }
            }
            MotionEvent.ACTION_UP -> {
                val ours = edge.up() == EdgeAction.CONSUME
                if (ours) {
                    EdgeSwipe.log("up x=${ev.x.toInt()} y=${ev.y.toInt()} after ${t - ev.downTime}ms")
                    sink?.end(ev.x, ev.y, t)
                }
                return ours
            }
            MotionEvent.ACTION_CANCEL -> {
                val ours = edge.up(cancelled = true) == EdgeAction.CONSUME
                if (ours) { EdgeSwipe.log("cancelled by Android"); sink?.cancel() }
                return ours
            }
            else -> return edge.owned // extra fingers during a wheel gesture stay with the wheel
        }
    }

    /** Ask Android 10's gesture navigation to leave the middle of both side edges to VoidOS. */
    private fun excludeEdges() {
        val v = window.decorView
        v.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) {
                v.systemGestureExclusionRects = edgeExclusion(r - l, b - t, resources.displayMetrics.density)
                    .map { android.graphics.Rect(it[0], it[1], it[2], it[3]) }
            }
        }
    }

    // ------------------------------------------------------------ bars & lock

    fun applyBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (store.hideBars) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun inLockTask(): Boolean =
        getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

    fun enforceLockTask() {
        // split view (an app beside the clock) needs the kiosk lock off while it lasts
        val want = Policy.isOwner(this) && store.setupDone && store.kiosk && !visitingAndroid && store.splitApp.isEmpty()
        runCatching {
            if (want && !inLockTask()) startLockTask()
            else if (!want && inLockTask()) stopLockTask()
        }
    }

    fun leaveLockTask() {
        runCatching { if (inLockTask()) stopLockTask() }
        applyBars()
    }

    /**
     * About to open a stock Android screen (Settings, a Wi-Fi panel...): drop
     * lock mode until VoidOS is resumed again, so the screen can open.
     */
    fun visitAndroid() {
        visitingAndroid = true
        leaveLockTask()
    }

    /** Escape screen: step out to stock Android Settings. Home brings you back. */
    fun openAndroidSettings() {
        visitAndroid()
        // Hide Android switches Settings off: switch it back on for this trip first
        runCatching { Stealth.openSettingsDoors(this) }
        runCatching {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Follow the tablet, or pin landscape / portrait (System > Display). */
    fun applyRotation() {
        requestedOrientation = when (store.rotation) {
            "land" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "port" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }

    fun applyPolicies() {
        Policy.applyAll(this)
        applyBars()
        enforceLockTask()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        com.andysocial.voidos.ui.Idle.split.value = isInMultiWindowMode
        if (!isInMultiWindowMode && store.splitApp.isNotEmpty()) { store.setSplit(""); enforceLockTask() }
    }

    override fun onDestroy() {
        if (live?.get() === this) live = null
        super.onDestroy()
    }

    companion object {
        /** The running VoidOS screen, for the control centre over other apps (rotation, policies, brightness). */
        @Volatile var live: java.lang.ref.WeakReference<MainActivity>? = null
        /** Opens the control centre when VoidOS comes back (the fallback when it isn't running behind another app). */
        const val EXTRA_CONTROL = "com.andysocial.voidos.CONTROL"
        /** How tall the bottom-edge swipe zone is, and how much of the width (each side of the middle) it covers. */
        const val BOTTOM_DP = 24
        const val BOTTOM_HALF_WIDTH = 0.4f

        /** Open VoidOS's app switcher (from the bottom edge over another app). */
        const val EXTRA_SWITCHER = "com.andysocial.voidos.SWITCHER"
        const val EXTRA_FROM_APP = "com.andysocial.voidos.FROM_APP"
        /** The screen to open, when the wheel over another app brings VoidOS back. */
        const val EXTRA_MODULE = "com.andysocial.voidos.MODULE"
    }
}
