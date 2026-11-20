package com.andysocial.voidos

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.andysocial.voidos.ui.AlarmRingScreen

/**
 * The ringing alarm, full screen. Shows over Android's lock screen and turns
 * the screen on by itself, so it works with the tablet asleep. Closes itself
 * as soon as nothing is ringing (stopped here, from the notification, or
 * from inside VoidOS).
 */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        // Back doesn't silence an alarm: Snooze or Stop does
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() {} })
        if (Alarms.ringing.value == null) { finish(); return }
        setContent {
            LaunchedEffect(Unit) { snapshotFlow { Alarms.ringing.value }.collect { if (it == null) finish() } }
            Alarms.ringing.value?.let { r ->
                QuietHaptics { AlarmRingScreen(r, onSnooze = { AlarmService.snooze(this) }, onStop = { AlarmService.dismiss(this) }) }
            }
        }
    }
}
