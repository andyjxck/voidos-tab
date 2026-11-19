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
 *  - wave to wake: a hand over the proximity sensor wakes the screen. The sensor only listens
 *    while the screen is off (or an alarm rings), so its glow on the bezel is dark in use,
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

    /**
     * Pure: should the proximity sensor be listening? Only while wave to wake is on and the screen
     * is off (its glow on the bezel stays dark while you use the tablet), or while an alarm rings,
     * so a wave can still snooze it. VoidOS's lit standby clock counts as the screen being on.
     */
    fun proximityWanted(waveWake: Boolean, screenOn: Boolean, alarmRinging: Boolean): Boolean =
        waveWake && (!screenOn || alarmRinging)

    /**
     * Pure: does this proximity reading wake the screen? Only a wave: near after far, with the
     * screen off. The first reading after the sensor starts doesn't count, so turning the screen
     * off face down, or with a cover closed, doesn't light it straight back up; nor does the hand
     * leaving the power button in the first moment after the screen goes off.
     */
    fun waveWakes(prevNear: Boolean?, near: Boolean, screenOn: Boolean, sinceOffMs: Long): Boolean =
        near && prevNear == false && !screenOn && sinceOffMs >= WAVE_GRACE_MS

    const val WAVE_GRACE_MS = 800L

    /** The proximity sensor to use: its wake-up version when the tablet has one, so a wave reaches VoidOS while the tablet sleeps. */
    fun proximitySensor(m: SensorManager): Sensor? {
        val all = runCatching { m.getSensorList(Sensor.TYPE_PROXIMITY) }.getOrNull().orEmpty()
        return all.firstOrNull { it.isWakeUpSensor } ?: all.firstOrNull() ?: m.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    }

    // wave to wake: the screen on/off receiver, and the proximity listener it turns on and off
    private var appCtx: Context? = null
    private var waveOn = false
    private var screenRx: android.content.BroadcastReceiver? = null
    private var proxListener: SensorEventListener? = null
    private var proxSensor: Sensor? = null
    private var awake: PowerManager.WakeLock? = null
    private var offAt = 0L

    /** True while the proximity sensor is registered (for the tests and the debug log). */
    val proximityListening: Boolean get() = proxListener != null

    fun sync(ctx: Context, store: Store) {
        val app = ctx.applicationContext
        stop()
        val m = app.getSystemService(SensorManager::class.java) ?: return
        sm = m
        appCtx = app
        val pm = app.getSystemService(PowerManager::class.java)
        fun wake() = runCatching {
            @Suppress("DEPRECATION")
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "voidos:sensorwake").acquire(3000)
        }
        if (store.waveWake && proximitySensor(m) != null) {
            waveOn = true
            if (!pm.isInteractive) offAt = SystemClock.elapsedRealtime() - WAVE_GRACE_MS
            // Android only tells apps the screen turned off or on through a receiver registered while running
            val rx = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context, i: android.content.Intent) {
                    if (i.action == android.content.Intent.ACTION_SCREEN_OFF) offAt = SystemClock.elapsedRealtime()
                    refresh()
                }
            }
            screenRx = rx
            val f = android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_SCREEN_OFF)
                addAction(android.content.Intent.ACTION_SCREEN_ON)
            }
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED)
                else app.registerReceiver(rx, f)
            }
            refresh()
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

    /**
     * Starts or stops the proximity sensor to match the screen: on as the screen goes off, off as
     * soon as it comes back on. Called by the screen receiver and when an alarm starts or stops.
     */
    @Synchronized
    fun refresh() {
        val app = appCtx ?: return
        val m = sm ?: return
        val pm = app.getSystemService(PowerManager::class.java) ?: return
        val want = proximityWanted(waveOn, pm.isInteractive, com.andysocial.voidos.Alarms.ringing.value != null)
        if (want && proxListener == null) startProximity(app, m, pm)
        else if (!want && proxListener != null) stopProximity()
    }

    private fun startProximity(app: Context, m: SensorManager, pm: PowerManager) {
        val s = proximitySensor(m) ?: return
        val l = object : Listener() {
            var lastWave = 0L
            var prevNear: Boolean? = null
            override fun onSensorChanged(e: SensorEvent) {
                val near = e.values[0] < s.maximumRange
                if (waveWakes(prevNear, near, pm.isInteractive, SystemClock.elapsedRealtime() - offAt)) wakeScreen(app, pm)
                prevNear = near
                // a wave over a ringing alarm snoozes it
                val now = SystemClock.elapsedRealtime()
                if (near && now - lastWave > 3000 && com.andysocial.voidos.Alarms.ringing.value != null) {
                    lastWave = now
                    runCatching {
                        app.sendBroadcast(android.content.Intent(app, com.andysocial.voidos.AlarmReceiver::class.java).setAction(com.andysocial.voidos.AlarmService.ACTION_SNOOZE).putExtra("id", 0))
                    }
                }
            }
        }
        if (!m.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL)) return
        proxListener = l; proxSensor = s
        // no wake-up proximity sensor: the tablet's processor would sleep through the wave, so hold
        // it awake while the screen is off (and keep VoidOS out of Doze so that hold counts)
        if (!s.isWakeUpSensor) runCatching {
            awake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voidos:wavewatch").apply { setReferenceCounted(false); acquire(12 * 60 * 60_000L) }
            val pkg = app.packageName
            Thread { runCatching { com.andysocial.voidos.Su.run("dumpsys deviceidle whitelist +$pkg") } }.apply { isDaemon = true; start() }
        }
    }

    private fun stopProximity() {
        proxListener?.let { l -> runCatching { sm?.unregisterListener(l) } }
        proxListener = null; proxSensor = null
        awake?.let { runCatching { if (it.isHeld) it.release() } }
        awake = null
    }

    /** Lights the screen: a screen wake lock does it; if the screen is still dark a moment later, root presses the wake key. */
    private fun wakeScreen(app: Context, pm: PowerManager) {
        runCatching {
            @Suppress("DEPRECATION")
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "voidos:wavewake").acquire(3000)
        }
        Thread {
            runCatching {
                Thread.sleep(400)
                if (!pm.isInteractive) com.andysocial.voidos.Su.run("input keyevent KEYCODE_WAKEUP")
            }
        }.apply { isDaemon = true; start() }
    }

    private fun listen(m: SensorManager, s: Sensor, l: SensorEventListener) {
        m.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL)
        listeners += l
    }

    fun stop() {
        listeners.forEach { l -> runCatching { sm?.unregisterListener(l) } }
        listeners.clear()
        stopProximity()
        screenRx?.let { rx -> runCatching { appCtx?.unregisterReceiver(rx) } }
        screenRx = null
        waveOn = false
    }

    private abstract class Listener : SensorEventListener {
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }
}
