package com.andysocial.voidos

import com.andysocial.voidos.ui.HONEY_COMPACT
import com.andysocial.voidos.ui.KGroup
import com.andysocial.voidos.ui.Module
import com.andysocial.voidos.ui.honeyCluster
import com.andysocial.voidos.ui.honeyFit
import com.andysocial.voidos.ui.honeyRows
import com.andysocial.voidos.ui.wheelClosePull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/** The second round of tablet feedback: quiet taps, own pages, honeycomb, the wheel's close, the camera. */
class FeedbackTwoTest {

    @Test fun tapsAreQuieter() {
        // it was 0.6 of the touch-sound volume
        assertTrue(TapSound.VOLUME <= 0.25f)
        assertTrue(TapSound.VOLUME > 0f)
    }

    @Test fun cameraAndUtilitiesHaveTheirOwnPages() {
        assertEquals(listOf(KGroup.TIME, KGroup.ORGANISE), KGroup.on(Module.TOOLS))
        assertEquals(listOf(KGroup.CAMERA), KGroup.on(Module.CAMERA))
        assertEquals(listOf(KGroup.UTILITIES), KGroup.on(Module.UTILITIES))
        // every tool still lives on exactly one page
        KGroup.entries.forEach { g -> assertTrue(g.page in listOf(Module.TOOLS, Module.CAMERA, Module.UTILITIES)) }
        // the pager has a mood for every page
        assertEquals(Module.entries.size, com.andysocial.voidos.ui.Ambience.moods.size)
    }

    @Test fun aFewAppsMakeATidyComb() {
        listOf(1.6f, 0.62f).forEach { aspect ->
            for (n in 1..HONEY_COMPACT) {
                val rows = honeyRows(n, aspect)
                assertEquals("n=$n rows=$rows", n, rows.sum())
                // neighbouring rows differ by one, so they pack into true hexagons
                rows.zipWithNext().forEach { (a, b) -> assertEquals("n=$n rows=$rows", 1, abs(a - b)) }
                val cells = honeyCluster(n, aspect)
                assertEquals(n, cells.size)
                // centred on the middle
                val cx = (cells.maxOf { it.x } + cells.minOf { it.x }) / 2f
                val cy = (cells.maxOf { it.y } + cells.minOf { it.y }) / 2f
                assertEquals(0f, cx, 0.01f); assertEquals(0f, cy, 0.01f)
                // no two cells overlap: neighbours are one cell apart
                for (i in cells.indices) for (j in i + 1 until cells.size)
                    assertTrue("n=$n overlap", hypot(cells[i].x - cells[j].x, cells[i].y - cells[j].y) >= 0.99f)
            }
        }
        // five apps: two over three, as a small comb, not a broken ring
        assertEquals(listOf(2, 3), honeyRows(5, 1.6f).sorted())
        assertEquals(listOf(2, 3, 2), honeyRows(7, 1.6f))
    }

    @Test fun aFewAppsAreDrawnLarger() {
        val five = honeyCluster(5, 1.6f)
        val k = honeyFit(five, 140f, 1600f, 900f)
        assertTrue("k=$k", k > 1f && k <= 1.5f)
        // never smaller than normal
        assertEquals(1f, honeyFit(honeyCluster(12, 1.6f), 140f, 300f, 300f), 0.001f)
    }

    @Test fun onlyAStraightPullDownCloses() {
        assertEquals(0f, wheelClosePull(-40f, 0f, 90f), 0f)     // up
        assertEquals(0f, wheelClosePull(40f, 80f, 90f), 0f)     // mostly sideways: that's a carry
        assertEquals(0.5f, wheelClosePull(45f, 10f, 90f), 0.001f)
        assertEquals(1f, wheelClosePull(200f, 20f, 90f), 0f)
    }

    @Test fun closingAnAppIsAForceStop() {
        assertEquals("am force-stop com.google.android.youtube", AppKill.command("com.google.android.youtube"))
    }

    @Test fun cameraIsLetGoOfWhenItsScreenLeaves() {
        val src = File("src/main/java/com/andysocial/voidos/ui")
        val tools = File(src, "CameraTools.kt").readText()
        val more = File(src, "MoreScreens.kt").readText()
        // nothing grabs the camera with a blanket unbindAll and then holds it for the activity's life
        assertFalse(tools.contains("unbindAll")); assertFalse(more.contains("unbindAll"))
        assertFalse(tools.contains("prov.bindToLifecycle")); assertFalse(more.contains("prov.bindToLifecycle"))
        // both camera screens hold it through CamHold, which lets go on dispose
        assertTrue(tools.contains("rememberCamHold(exec)")); assertTrue(more.contains("rememberCamHold()"))
        assertTrue(tools.contains("onDispose { hold.close()"))
    }
}
