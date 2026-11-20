package com.andysocial.voidos

import com.andysocial.voidos.ui.DrawerLayout
import com.andysocial.voidos.ui.drawerReveal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 1.20.0: "I closed all my apps and the app switcher brings them all back." The switcher and the
 * right edge drawer show OpenApps.pick, so these check that a closed app stays gone through every
 * close path, a reopen of the switcher, going home and a restart, and comes back only when opened.
 */
class OpenAppsTest {
    private val launchable = setOf("spotify", "whatsapp", "chrome", "netflix", "voidos", "launcher3")
    private val home = setOf("voidos", "launcher3", "com.android.systemui")
    private val boot = 1_000L

    private fun pick(system: Map<String, Long>, opened: Map<String, Long> = emptyMap(), closed: Map<String, Long> = emptyMap(), cleared: Long = 0L, bootAt: Long = boot) =
        OpenApps.pick(system, opened, launchable, home, closed, cleared, bootAt)

    @Test fun openAppsNewestFirstNeverHome() {
        val sys = mapOf("spotify" to 2_000L, "whatsapp" to 3_000L, "voidos" to 4_000L, "launcher3" to 4_500L, "com.android.systemui" to 4_600L, "chrome" to 2_500L)
        assertEquals(listOf("whatsapp", "chrome", "spotify"), pick(sys))
    }

    @Test fun closeAllStaysClosedAfterReopeningTheSwitcher() {
        val sys = mapOf("spotify" to 2_000L, "whatsapp" to 3_000L, "chrome" to 2_500L)
        // Close all at 5000; Android still has its records of all three when the switcher reads again
        assertEquals(emptyList<String>(), pick(sys, cleared = 5_000L))
        // ... and again after going home (VoidOS at the front adds nothing)
        assertEquals(emptyList<String>(), pick(sys + ("voidos" to 6_000L), cleared = 5_000L))
    }

    @Test fun closingOneCardOrHoldAndPullKeepsItGone() {
        val sys = mapOf("spotify" to 2_000L, "whatsapp" to 3_000L)
        // a card's close and hold-and-pull-down on the drawer both record a close time (Store.closeRecent)
        assertEquals(listOf("spotify"), pick(sys, closed = mapOf("whatsapp" to 3_500L)))
    }

    @Test fun openedAgainComesBack() {
        // closed at 3500, opened again from Android's records at 4000, or from VoidOS at 4100
        assertEquals(listOf("whatsapp"), pick(mapOf("whatsapp" to 4_000L), closed = mapOf("whatsapp" to 3_500L)))
        assertEquals(listOf("whatsapp"), pick(emptyMap(), opened = mapOf("whatsapp" to 4_100L), cleared = 3_500L))
        // opened before the Close all: still gone
        assertEquals(emptyList<String>(), pick(emptyMap(), opened = mapOf("whatsapp" to 3_000L), cleared = 3_500L))
    }

    @Test fun nothingFromBeforeARestart() {
        val sys = mapOf("spotify" to 2_000L, "whatsapp" to 3_000L)
        // the tablet restarted at 3500: nothing is open any more
        assertEquals(emptyList<String>(), pick(sys, bootAt = 3_500L))
        assertEquals(listOf("whatsapp"), pick(sys + ("whatsapp" to 4_000L), bootAt = 3_500L))
    }

    @Test fun uninstalledAppsNeverShow() {
        assertEquals(emptyList<String>(), pick(mapOf("gone.app" to 2_000L)))
    }

    @Test fun dropAndClearTakeEffectAtOnce() {
        OpenApps.set(listOf("a", "b", "c"))
        OpenApps.drop("b")
        assertEquals(listOf("a", "c"), OpenApps.list.value)
        OpenApps.clear()
        assertTrue(OpenApps.list.value.isEmpty())
    }

    @Test fun isOpenNeedsATime() {
        assertFalse(OpenApps.isOpen(null, null, null, 0L, 0L))
        assertTrue(OpenApps.isOpen(5L, null, null, 0L, 0L))
        assertFalse(OpenApps.isOpen(5L, null, 5L, 0L, 0L))
    }
}

/** The edge drawers' rows: one height, evenly stacked, inside the rail, clear of the top and bottom bands, at every size. */
class DrawerLayoutTest {
    @Test fun rowsFitEverySizeAndCount() {
        val dens = 2f
        val sizes = listOf(800f to 1280f, 1280f to 800f, 1200f to 1920f, 1920f to 1200f, 600f to 960f)
        for ((w, h) in sizes) for (n in 0..16) for (scale in listOf(1f, 1.15f, 1.3f)) {
            val l = DrawerLayout(n, h, 56f * dens, 56f * dens, 44f * dens, 60f * dens * scale, 8f * dens)
            val tag = "${w}x$h n=$n scale=$scale"
            assertTrue("$tag rail top clear of the island", l.railTop >= 56f * dens - 0.5f)
            assertTrue("$tag rail bottom clear of the switcher edge", l.railTop + l.railH <= h - 56f * dens + 0.5f)
            assertTrue("$tag row never shorter than the minimum", l.row >= 44f * dens - 0.01f)
            if (n == 0) continue
            // every row reachable: at full scroll the last one is fully inside the rail
            assertTrue("$tag first row visible", l.visible(0, 0f))
            assertTrue("$tag last row reachable", l.visible(n - 1, l.overflow))
            // finger on a row's centre picks that row
            for (i in 0 until n) {
                val s = if (l.visible(i, 0f)) 0f else l.overflow
                assertEquals("$tag row $i", i, l.indexAt(l.centre(i, s), s))
            }
            // evenly stacked
            for (i in 1 until n) assertEquals(l.row, l.centre(i) - l.centre(i - 1), 0.01f)
            // centred when it fits
            if (l.overflow == 0f) assertEquals("$tag centred", (56f * dens) + (l.room - l.railH) / 2f, l.railTop, 0.5f)
        }
    }

    @Test fun scrollFollowsTheFinger() {
        val l = DrawerLayout(20, 800f, 144f, 144f, 96f, 120f, 16f)
        assertTrue(l.overflow > 0f)
        assertEquals(0f, l.scrollFor(l.railTop), 0.01f)
        assertEquals(l.overflow, l.scrollFor(l.railTop + l.railH), 0.01f)
    }

    @Test fun revealFollowsTheFinger() {
        assertEquals(0f, drawerReveal(-5f, 100f), 0f)
        assertEquals(0.5f, drawerReveal(50f, 100f), 1e-6f)
        assertEquals(1f, drawerReveal(150f, 100f), 0f)
        assertTrue(abs(drawerReveal(10f, 0f)) < 1e-6f)
    }
}

/** 1.20.0: a hold on power goes straight to VoidOS's menu, and there's always a way back to Android's. */
class PowerHoldTest {
    @Test fun takeOverHandsTheHoldToVoidOS() {
        val c = PowerHold.takeOverCommands()
        assertTrue(c.contains("settings put global power_button_long_press 5"))
        assertTrue(c.any { it.startsWith("settings put secure assistant com.andysocial.voidos/") })
    }

    @Test fun giveBackRestoresAndroidsDialogAndTheOldAssistant() {
        assertTrue(PowerHold.giveBackCommands(null).contains("settings put global power_button_long_press 1"))
        assertTrue(PowerHold.giveBackCommands(null).contains("settings delete secure assistant"))
        assertTrue(PowerHold.giveBackCommands("com.google/x.Assist").contains("settings put secure assistant com.google/x.Assist"))
        // never "restores" VoidOS as the assistant
        assertTrue(PowerHold.giveBackCommands(PowerHold.ASSISTANT).contains("settings delete secure assistant"))
    }

    @Test fun onlyAHoldWithTheMenuOnOpensItOnce() {
        assertTrue(PowerHold.isHold("assist", true, 10_000L, 0L))
        assertFalse(PowerHold.isHold("assist", false, 10_000L, 0L))
        assertFalse(PowerHold.isHold("homekey", true, 10_000L, 0L))
        assertFalse(PowerHold.isHold("recentapps", true, 10_000L, 0L))
        // the signal and the assistant screen both arrive for one hold: only the first opens the menu
        assertFalse(PowerHold.isHold("assist", true, 10_300L, 10_000L))
    }
}
