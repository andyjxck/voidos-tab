package com.andysocial.voidos

import com.andysocial.voidos.ui.Sensors
import com.andysocial.voidos.ui.Switcher
import com.andysocial.voidos.ui.wifiQrText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StealthTest {
    private val settings = listOf("com.android.settings/.Settings", "com.android.settings/.LauncherSettings")
    private val recents = "com.android.launcher3/com.android.quickstep.RecentsActivity"

    @Test fun hidingSwitchesOffSettingsAndRecentsAndBars() {
        val on = Stealth.commands(true, settings, recents, "com.andysocial.voidos")
        assertTrue(on.first().contains("immersive.full=*"))
        assertTrue(on.any { it.startsWith("pm disable") && it.contains("Settings") })
        assertTrue(on.any { it.startsWith("pm disable") && it.contains("RecentsActivity") })
    }

    @Test fun showingUndoesEveryPart() {
        val on = Stealth.commands(true, settings, recents, "com.andysocial.voidos")
        val off = Stealth.commands(false, settings, recents, "com.andysocial.voidos")
        assertEquals(on.size, off.size)
        assertTrue(off.first().contains("delete global policy_control"))
        on.drop(1).forEach { c -> assertTrue(off.contains(c.replace("pm disable", "pm enable"))) }
    }

    @Test fun neverSwitchesOffVoidOS() {
        val on = Stealth.commands(true, emptyList(), "com.andysocial.voidos/.MainActivity", "com.andysocial.voidos")
        assertFalse(on.any { it.contains("pm disable") })
    }

    @Test fun switcherOrder() {
        val o = Switcher.order(listOf("a", "self", "b", "x"), listOf("c", "a"), setOf("a", "b", "c", "self"), "self")
        assertEquals(listOf("a", "b", "c"), o)
    }

    @Test fun sensorsMath() {
        assertTrue(Sensors.faceDown(0.3f, -0.4f, -9.7f))
        assertFalse(Sensors.faceDown(0.3f, -0.4f, 9.7f))
        assertTrue(Sensors.dark(2f, false)); assertFalse(Sensors.dark(8f, false)); assertTrue(Sensors.dark(8f, true))
    }

    @Test fun wifiQr() {
        assertEquals("WIFI:T:WPA;S:Andy\\;s;P:pa\\:ss;;", wifiQrText("Andy;s", "pa:ss", true))
        assertEquals("WIFI:T:nopass;S:Cafe;;", wifiQrText("Cafe", null, false))
    }
}
