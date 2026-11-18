package com.andysocial.voidos.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.os.SystemClock
import com.andysocial.voidos.Device
import com.andysocial.voidos.Policy
import com.andysocial.voidos.Store
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Workarounds that use the tablet's sensors:
 *  - wave to wake: a hand over the proximity sensor wakes the screen,
 *  - face down to sleep: lay the tablet on its face and the screen goes off,
 *  - pick up to wake: a lift (the accelerometer feels it) wakes the screen,
 *  - dim in a dark room: the light sensor turns night light and dark mode on when the room goes dark.
 * Only what's switched on listens, and only on sensors the tablet has.
 */
object Sensors {
    private var sm: SensorManager? = null
    private val listeners = mutableListOf<SensorEventListener>()

    fun has(ctx: Context, type: Int): Boolean =
        ctx.getSystemService(SensorManager::class.java)?.getDefaultSensor(type) != null

    /** Pure: is the tablet lying on its face (screen down) from an accelerometer reading? */
    fun faceDown(x: Float, y: Float, z: Float): Boolean = z < -8.5f && abs(x) < 2.5f && abs(y) < 2.5f

    /** Pure: is a light reading (lux) dark, with hysteresis so it doesn't flicker? */
    fun dark(lux: Float, wasDark: Boolean): Boolean = if (wasDark) lux < 12f else lux < 4f

    fun sync(ctx: Context, store: Store) {
        val app = ctx.applicationContext
        stop()
        val m = app.getSystemService(SensorManager::class.java) ?: return
        sm = m
        val pm = app.getSystemService(PowerManager::class.java)
        fun wake() = runCatching {
            @Suppress("DEPRECATION")
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "voidos:sensorwake").acquire(3000)
        }
        if (store.waveWake) m.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { s ->
            listen(m, s, object : Listener() {
                var lastWave = 0L
                override fun onSensorChanged(e: SensorEvent) {
                    val near = e.values[0] < s.maximumRange
                    if (near && !pm.isInteractive) wake()
                    // a wave over a ringing alarm snoozes it
                    val now = SystemClock.elapsedRealtime()
                    if (near && now - lastWave > 3000 && com.andysocial.voidos.Alarms.ringing.value != null) {
                        lastWave = now
                        runCatching {
                            app.sendBroadcast(android.content.Intent(app, com.andysocial.voidos.AlarmReceiver::class.java).setAction(com.andysocial.voidos.AlarmService.ACTION_SNOOZE).putExtra("id", 0))
                        }
                    }
                }
            })
        }
        if (store.faceDown || store.pickUp || store.faceDownQuiet) m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { s ->
            listen(m, s, object : Listener() {
                var downSince = 0L
                var slept = false
                var hushed = false
                val nm = app.getSystemService(android.app.NotificationManager::class.java)
                override fun onSensorChanged(e: SensorEvent) {
                    val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
                    val now = SystemClock.elapsedRealtime()
                    if ((store.faceDown || store.faceDownQuiet) && faceDown(x, y, z)) {
                        if (downSince == 0L) downSince = now
                        if (!slept && now - downSince > 1500) {
                            slept = true
                            if (store.faceDownQuiet && nm.currentInterruptionFilter == android.app.NotificationManager.INTERRUPTION_FILTER_ALL) {
                                hushed = runCatching { nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY); true }.getOrDefault(false)
                            }
                            if (store.faceDown && pm.isInteractive) runCatching { Policy.dpm(app).lockNow() }
                        }
                    } else {
                        downSince = 0L; slept = false
                        // picked up again: Do Not Disturb goes back off, only if face down turned it on
                        if (hushed) { hushed = false; runCatching { nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL) } }
                    }
                    val g = sqrt(x * x + y * y + z * z)
                    if (store.pickUp && !pm.isInteractive && abs(g - 9.81f) > 3.2f) wake()
                }
            })
        }
        if (store.autoNight) m.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { s ->
            listen(m, s, object : Listener() {
                var isDark: Boolean? = null
                override fun onSensorChanged(e: SensorEvent) {
                    val d = dark(e.values[0], isDark == true)
                    if (d != isDark) {
                        isDark = d
                        Thread { runCatching { Device.darkApps(d); com.andysocial.voidos.Su.run("settings put secure night_display_activated ${if (d) 1 else 0}") } }.start()
                    }
                }
            })
        }
    }

    private fun listen(m: SensorManager, s: Sensor, l: SensorEventListener) {
        m.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL)
        listeners += l
    }

    fun stop() {
        listeners.forEach { l -> runCatching { sm?.unregisterListener(l) } }
        listeners.clear()
    }

    private abstract class Listener : SensorEventListener {
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }
}
