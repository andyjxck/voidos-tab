package com.andysocial.voidos

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.os.UserManager
import android.view.Surface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Power-on: BootCover stands in for Android's "starting" screen while storage is locked
 * and keeps the boot animation up (by not drawing) until VoidOS can open, with limits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BootCoverTest {
    private val app get() = VoidApp.instance
    private val um get() = app.getSystemService(UserManager::class.java)
    private val km get() = app.getSystemService(KeyguardManager::class.java)

    @After fun unlock() { shadowOf(um).setUserUnlocked(true) }

    private fun nextStarted(): Intent? = shadowOf(app).nextStartedActivity

    /** Created and resumed, before Robolectric runs any frames (a held window
     *  asks for a new frame every vsync, which Robolectric's clock follows). */
    private fun started(): BootCover =
        Robolectric.buildActivity(BootCover::class.java).create().start().postCreate(null).resume().get()

    @Test fun manifestMakesItADirectBootHomeBelowTheHub() {
        val pm = app.packageManager
        val info = pm.getActivityInfo(ComponentName(app, BootCover::class.java), 0)
        assertTrue("BootCover must run before unlock", info.directBootAware)
        val hub = pm.getActivityInfo(ComponentName(app, MainActivity::class.java), 0)
        assertFalse("the hub needs unlocked storage", hub.directBootAware)
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(app.packageName)
        val found = pm.queryIntentActivities(home, PackageManager.GET_RESOLVED_FILTER)
        val cover = found.first { it.activityInfo.name.endsWith(".BootCover") }
        val main = found.first { it.activityInfo.name.endsWith(".MainActivity") }
        assertTrue("BootCover sits below the hub", cover.priority < main.priority)
        // and above Settings' FallbackHome (priority -1000)
        assertTrue(cover.priority > -1000)
    }

    @Test fun lockedItHoldsThenOpensTheHubOnUnlock() {
        shadowOf(um).setUserUnlocked(false)
        shadowOf(km).setIsDeviceSecure(false)
        val a = started()
        assertTrue("keeps the boot animation up while locked", a.holding)
        assertFalse(a.isFinishing)
        shadowOf(um).setUserUnlocked(true)
        app.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
        shadowOf(Looper.getMainLooper()).idle()
        val i = nextStarted()
        assertNotNull("opens the hub", i)
        assertEquals(MainActivity::class.java.name, i!!.component!!.className)
        assertTrue(i.hasCategory(Intent.CATEGORY_HOME))
        assertTrue(a.isFinishing)
        assertFalse(a.holding)
    }

    @Test fun alreadyUnlockedGoesStraightToTheHub() {
        shadowOf(um).setUserUnlocked(true)
        val a = Robolectric.buildActivity(BootCover::class.java).setup().get()
        assertTrue(a.isFinishing)
        assertEquals(MainActivity::class.java.name, nextStarted()!!.component!!.className)
    }

    @Test fun androidPinMeansItDrawsStraightAway() {
        shadowOf(um).setUserUnlocked(false)
        shadowOf(km).setIsDeviceSecure(true)
        val a = Robolectric.buildActivity(BootCover::class.java).setup().get()
        assertFalse("Android's own unlock screen must be able to show", a.holding)
        assertFalse(a.isFinishing)
        shadowOf(km).setIsDeviceSecure(false)
    }

    @Test fun limitsDrawAfterTwentySecondsAndStepAsideAfterNinety() {
        shadowOf(um).setUserUnlocked(false)
        shadowOf(km).setIsDeviceSecure(false)
        val a = started()
        assertTrue(a.holding)
        shadowOf(Looper.getMainLooper()).idleFor(BootCover.HOLD_MS + 10, TimeUnit.MILLISECONDS)
        assertFalse("draws the still logo after the hold limit", a.holding)
        assertFalse(a.isFinishing)
        shadowOf(Looper.getMainLooper()).idleFor(BootCover.GIVE_UP_MS, TimeUnit.MILLISECONDS)
        assertTrue("steps aside for Android's own screen", a.isFinishing)
        assertNull("does not open the hub while locked", nextStarted())
        val cn = ComponentName(app, BootCover::class.java)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, app.packageManager.getComponentEnabledSetting(cn))
        BootCover.reenable(app)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, app.packageManager.getComponentEnabledSetting(cn))
    }

    @Test fun orientationPicksTheMatchingAnimations() {
        assertEquals(0, BootOrient.version(Surface.ROTATION_0))
        assertEquals(90, BootOrient.version(Surface.ROTATION_90))
        assertEquals(0, BootOrient.version(Surface.ROTATION_180))
        assertEquals(270, BootOrient.version(Surface.ROTATION_270))
        assertEquals("sh /data/adb/modules/voidos_boot/orient.sh 90", BootOrient.command(90, false))
        assertEquals("sh /data/adb/modules/voidos_boot/orient.sh 270 boot", BootOrient.command(270, true))
    }

    @Test fun stillPicturesShipInBothShapes() {
        val res = app.resources
        val port = android.graphics.BitmapFactory.decodeResource(res, R.drawable.boot_still_port)
        val land = android.graphics.BitmapFactory.decodeResource(res, R.drawable.boot_still_land)
        assertNotNull(port); assertNotNull(land)
    }

    @Test fun shutdownReceiverIsRegistered() {
        val f = File("src/main/AndroidManifest.xml").readText()
        assertTrue(f.contains(".BootOrient\$ShutdownReceiver"))
        assertTrue(f.contains("android.intent.action.ACTION_SHUTDOWN"))
    }
}
