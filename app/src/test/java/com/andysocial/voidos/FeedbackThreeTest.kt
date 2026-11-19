package com.andysocial.voidos

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import android.os.PowerManager
import com.andysocial.voidos.ui.Sensors
import com.andysocial.voidos.ui.wheelMayCarry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager
import java.time.Duration

/** The third round: the wheel needs a lift before a split, and wave to wake only listens with the screen off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FeedbackThreeTest {
    private val app: Context get() = VoidApp.instance
    private val store get() = VoidApp.instance.store
    private lateinit var sm: SensorManager
    private lateinit var pm: PowerManager

    @Before fun setUp() {
        sm = app.getSystemService(SensorManager::class.java)
        pm = app.getSystemService(PowerManager::class.java)
        Sensors.stop()
        listOf("waveWake", "faceDown", "pickUp", "faceDownQuiet", "autoNight").forEach { store.set(it, false) }
        Alarms.ringing.value = null
        ShadowPowerManager.clearWakeLocks()
    }

    @After fun tearDown() {
        Sensors.stop()
        store.set("waveWake", false); store.set("faceDown", false)
    }

    private fun proximity(wakeUp: Boolean): Sensor = ShadowSensor.newInstance(Sensor.TYPE_PROXIMITY).also {
        val sh = shadowOf(it)
        sh.setMaximumRange(5f)
        sh.setWakeUpFlag(wakeUp)
        shadowOf(sm).addSensor(it)
    }

    private fun screen(on: Boolean) {
        shadowOf(pm).setIsInteractive(on)
        app.sendBroadcast(Intent(if (on) Intent.ACTION_SCREEN_ON else Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun later(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun reading(cm: Float) {
        val e = ShadowSensorManager.createSensorEvent(1, Sensor.TYPE_PROXIMITY)
        e.values[0] = cm
        shadowOf(sm).sendSensorEventToListeners(e)
    }

    private fun woke(): Boolean = ShadowPowerManager.getLatestWakeLock()?.let { shadowOf(it).tag == "voidos:wavewake" } == true
    private fun proxListeners() = shadowOf(sm).listeners.size

    // ---- the wheel

    @Test fun theTouchThatOpensTheWheelNeverCarries() {
        assertFalse("opening touch, finger still down", wheelMayCarry(liftedSinceOpen = false, overlay = false, dropOn = true))
        assertTrue("a new touch after lifting", wheelMayCarry(liftedSinceOpen = true, overlay = false, dropOn = true))
        assertFalse("over other apps there's no book", wheelMayCarry(liftedSinceOpen = true, overlay = true, dropOn = true))
        assertFalse("dragging out switched off", wheelMayCarry(liftedSinceOpen = true, overlay = false, dropOn = false))
    }

    // ---- wave to wake

    @Test fun proximityOnlyWantedWithTheScreenOff() {
        assertFalse(Sensors.proximityWanted(waveWake = true, screenOn = true, alarmRinging = false))
        assertTrue(Sensors.proximityWanted(waveWake = true, screenOn = false, alarmRinging = false))
        assertTrue("a ringing alarm can still be waved to snooze", Sensors.proximityWanted(waveWake = true, screenOn = true, alarmRinging = true))
        assertFalse(Sensors.proximityWanted(waveWake = false, screenOn = false, alarmRinging = true))
    }

    @Test fun onlyAWaveWakes() {
        assertTrue(Sensors.waveWakes(prevNear = false, near = true, screenOn = false, sinceOffMs = 5_000))
        assertFalse("first reading after the sensor starts", Sensors.waveWakes(prevNear = null, near = true, screenOn = false, sinceOffMs = 5_000))
        assertFalse("hand moving away", Sensors.waveWakes(prevNear = true, near = false, screenOn = false, sinceOffMs = 5_000))
        assertFalse("screen already on", Sensors.waveWakes(prevNear = false, near = true, screenOn = true, sinceOffMs = 5_000))
        assertFalse("right after the screen went off", Sensors.waveWakes(prevNear = false, near = true, screenOn = false, sinceOffMs = 100))
    }

    @Test fun picksTheWakeUpProximitySensor() {
        val plain = proximity(wakeUp = false)
        val wakeUp = proximity(wakeUp = true)
        assertEquals(wakeUp, Sensors.proximitySensor(sm))
        assertTrue(plain !== Sensors.proximitySensor(sm))
    }

    @Test fun sensorListensOnlyWhileTheScreenIsOffAndAWaveWakesIt() {
        proximity(wakeUp = true)
        shadowOf(pm).setIsInteractive(true)
        store.set("waveWake", true)
        Sensors.sync(app, store)
        // screen on (VoidOS's lit standby clock included): the sensor and its bezel light stay off
        assertFalse("screen on: not listening", Sensors.proximityListening)
        assertEquals(0, proxListeners())
        // screen off: it starts listening
        screen(on = false)
        assertTrue("screen off: listening", Sensors.proximityListening)
        assertEquals(1, proxListeners())
        // the first reading is near (in a case, or face down): no wake
        later(1_000)
        reading(0f)
        assertFalse("first near reading doesn't wake", woke())
        // a wave: far, then near, with the screen off -> the screen wakes
        reading(5f)
        reading(0f)
        assertTrue("a wave over the sensor wakes the screen", woke())
        // the screen comes on: the sensor stops straight away
        screen(on = true)
        assertFalse("screen back on: not listening", Sensors.proximityListening)
        assertEquals(0, proxListeners())
        // and off again: listening again, and a wave still wakes
        ShadowPowerManager.clearWakeLocks()
        screen(on = false)
        assertTrue(Sensors.proximityListening)
        later(1_000)
        reading(5f); reading(0f)
        assertTrue("wakes again the next time", woke())
    }

    @Test fun aWaveTheMomentTheScreenGoesOffDoesNotWake() {
        proximity(wakeUp = true)
        shadowOf(pm).setIsInteractive(true)
        store.set("waveWake", true)
        Sensors.sync(app, store)
        screen(on = false)
        reading(5f); reading(0f) // the hand leaving the power button
        assertFalse(woke())
    }

    @Test fun withoutAWakeUpSensorTheProcessorIsHeldAwakeOnlyWhileTheScreenIsOff() {
        proximity(wakeUp = false)
        shadowOf(pm).setIsInteractive(true)
        store.set("waveWake", true)
        Sensors.sync(app, store)
        assertTrue("no hold with the screen on", ShadowPowerManager.getLatestWakeLock()?.let { shadowOf(it).tag == "voidos:wavewatch" } != true)
        screen(on = false)
        val hold = ShadowPowerManager.getLatestWakeLock()
        assertTrue("held awake while off", hold != null && shadowOf(hold).tag == "voidos:wavewatch" && hold.isHeld)
        later(1_000)
        reading(5f); reading(0f)
        assertTrue(woke())
        screen(on = true)
        assertFalse("let go once the screen is on", hold!!.isHeld)
        assertFalse(Sensors.proximityListening)
    }

    @Test fun waveToWakeOffNeverListens() {
        proximity(wakeUp = true)
        store.set("waveWake", false)
        Sensors.sync(app, store)
        screen(on = false)
        assertFalse(Sensors.proximityListening)
        assertEquals(0, proxListeners())
    }

    @Test fun aRingingAlarmTurnsItOnSoAWaveSnoozes() {
        proximity(wakeUp = true)
        shadowOf(pm).setIsInteractive(true)
        store.set("waveWake", true)
        Sensors.sync(app, store)
        assertFalse(Sensors.proximityListening)
        Alarms.ringing.value = Ringing(Alarm(7, 7, 0), System.currentTimeMillis())
        Sensors.refresh()
        assertTrue("listening while it rings", Sensors.proximityListening)
        Alarms.dismiss()
        assertFalse("off again once dismissed", Sensors.proximityListening)
    }

    @Test fun theOtherSensorsAreUntouched() {
        val acc = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER)
        shadowOf(sm).addSensor(acc)
        proximity(wakeUp = true)
        shadowOf(pm).setIsInteractive(true)
        store.set("waveWake", true); store.set("faceDown", true)
        Sensors.sync(app, store)
        // face down still listens with the screen on; only the proximity sensor waits for the screen to go off
        assertEquals(1, proxListeners())
        assertFalse(Sensors.proximityListening)
        screen(on = false)
        assertEquals(2, proxListeners())
        screen(on = true)
        assertEquals(1, proxListeners())
    }
}
