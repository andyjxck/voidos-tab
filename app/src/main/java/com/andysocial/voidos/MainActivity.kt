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
import com.andysocial.voidos.ui.Idle
import com.andysocial.voidos.ui.VoidRoot

class MainActivity : ComponentActivity() {
    private val store get() = (application as VoidApp).store

    /** True while you're out in stock Settings via the escape screen. */
    private var visitingAndroid = false
    private var swallowTouch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        Policy.applyAll(this)
        setContent {
            LaunchedEffect(Unit) {
                snapshotFlow { VoidApp.releaseSignal.value }.collect { if (it > 0) leaveLockTask() }
            }
            VoidRoot(store, this)
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
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyBars()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already home: jump back to the face
        if (intent.hasCategory(Intent.CATEGORY_HOME)) Idle.homePressed.value++
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // A touch while the ambient clock is showing only wakes the screen;
        // it never lands on whatever is underneath.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && Idle.ambient.value) {
            swallowTouch = true
        }
        Idle.touch()
        if (swallowTouch) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallowTouch = false
            return true
        }
        return super.dispatchTouchEvent(ev)
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
        val want = Policy.isOwner(this) && store.setupDone && store.kiosk && !visitingAndroid
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
}
