package com.andysocial.voidos

import android.app.Application
import androidx.compose.runtime.mutableStateOf

class VoidApp : Application() {
    lateinit var store: Store
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(this)
        // play the start animation once per power-on, not every time Android
        // restarts the app to free memory
        val bootId = (System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()) / 60_000
        val prefs = getSharedPreferences("voidos_boot", MODE_PRIVATE)
        val last = prefs.getLong("bootId", -1)
        booted = last != -1L && kotlin.math.abs(last - bootId) <= 1
        prefs.edit().putLong("bootId", bootId).apply()
        KTimers.load(this)
        // the VoidOS lock screen comes up every time the screen goes off
        val f = android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF)
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                if (!store.voidLock || !store.setupDone) return
                com.andysocial.voidos.ui.LockState.locked.value = true
                // bring the hub to the front so the lock screen is what wakes up
                runCatching {
                    startActivity(android.content.Intent(c, MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
            }
        }, f)
    }

    companion object {
        lateinit var instance: VoidApp
            private set

        /** The start animation plays once per process start (i.e. after a reboot). */
        var booted = false

        /** Set by the adb UNLOCK broadcast or the escape screen: drop lock task now. */
        val releaseSignal = mutableStateOf(0)
    }
}
