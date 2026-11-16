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
