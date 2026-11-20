package com.andysocial.voidos

import com.andysocial.voidos.ui.HONEY_FILL_H
import com.andysocial.voidos.ui.HONEY_FILL_W
import com.andysocial.voidos.ui.POWER_FADE_MS
import com.andysocial.voidos.ui.POWER_SHOW_MS
import com.andysocial.voidos.ui.honeyCells
import com.andysocial.voidos.ui.honeyEdgeScale
import com.andysocial.voidos.ui.honeyLayout
import com.andysocial.voidos.ui.honeySpan
import com.andysocial.voidos.ui.powerOffAlpha
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** 1.20.1: softer taps and vibration, a honeycomb that fills the page, one smooth shutdown. */
class SofterFeedbackTest {

    // ---- 1. softer sound and haptics

    @Test fun tapSoundIsQuietAndSoft() {
        assertTrue(TapSound.VOLUME <= 0.16f)
        val bytes = File("src/main/res/raw/tap.wav").readBytes()
        val pcm = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val s = ShortArray(pcm.remaining()).also { pcm.get(it) }
        val peak = s.maxOf { abs(it.toInt()) }
        // well under half of full scale (the old sample peaked near full scale)
        assertTrue("peak $peak", peak < 16000)
        // short: under 40 ms at 44.1 kHz
        assertTrue("${s.size} samples", s.size < 44100 * 40 / 1000)
        // no hard click at the start: it ramps up over a few ms instead of starting at its loudest
        val firstMs = s.take(44).maxOf { abs(it.toInt()) }
        assertTrue("starts at $firstMs of $peak", firstMs < peak * 0.3)
    }

    @Test fun vibrationsAreShortAndLight() {
        Haptics.Kind.entries.forEach { k ->
            assertTrue("$k ${k.ms} ms", k.ms in 1..Haptics.MAX_MS)
            assertTrue("$k ${k.fixedMs} ms", k.fixedMs in 1..Haptics.MAX_MS)
            assertTrue("$k strength ${k.amplitude}", k.amplitude in 1..Haptics.MAX_AMPLITUDE)
        }
        assertTrue(Haptics.Kind.TICK.ms <= Haptics.Kind.PRESS.ms)
        assertTrue(Haptics.Kind.TICK.amplitude < Haptics.Kind.PRESS.amplitude)
    }

    @Test fun everyVibrationGoesThroughHaptics() {
        val src = File("src/main/java").walkTopDown().filter { it.extension == "kt" && it.name != "Haptics.kt" && it.name != "AlarmService.kt" }  // an alarm ringing is meant to buzz
        src.forEach { f ->
            val t = f.readText()
            assertFalse("${f.name} still uses Android's own haptic patterns", t.contains("performHapticFeedback"))
            assertFalse("${f.name} vibrates on its own", t.contains("VibrationEffect") || t.contains(".vibrate("))
        }
    }

    @Test fun everyWindowTurnsComposesOwnBuzzOff() {
        // Compose plays the strong long-press pattern on a long click; every VoidOS window swaps it for nothing
        File("src/main/java").walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            val t = f.readText()
            Regex("setContent \\{([^\\n]*\\n?){0,6}").findAll(t).forEach { m ->
                assertTrue("${f.name}: a window without QuietHaptics: ${m.value.take(120)}", m.value.contains("QuietHaptics"))
            }
        }
    }

    // ---- 2. the honeycomb fills the page

    private val pages = listOf(
        "portrait" to (1200f to 1720f), "landscape" to (1920f to 1000f),
        "portrait hdpi" to (1066f to 1560f), "landscape hdpi" to (1706f to 900f),
    )
    private val minCell = 126f; private val maxCell = 294f   // 84 and 196 dp at 1.5x

    @Test fun combFillsThePageEitherWayRound() {
        pages.forEach { (name, wh) ->
            val (w, h) = wh
            var last = Float.MAX_VALUE
            for (n in 1..40) {
                val lay = honeyLayout(n, w, h, minCell, maxCell)
                assertEquals("$name n=$n", n, lay.cells.size)
                assertEquals("$name n=$n", n, lay.rows.sum())
                val (sw, sh) = honeySpan(lay.cells)
                val cw = sw * lay.cell; val ch = sh * lay.cell
                // never past the sides; past the top and bottom only when it pans
                assertTrue("$name n=$n width $cw of $w", cw <= w * HONEY_FILL_W + 1f)
                if (lay.overflow == 0f) assertTrue("$name n=$n height $ch of $h", ch <= h * HONEY_FILL_H + 1f)
                // fills the page one way or the other, unless the icons are already as big as they go
                val fill = maxOf(cw / (w * HONEY_FILL_W), ch / (h * HONEY_FILL_H))
                assertTrue("$name n=$n fills ${(fill * 100).toInt()}% rows=${lay.rows}", lay.cell >= maxCell - 0.5f || fill >= 0.97f)
                // centred
                val cx = (lay.cells.maxOf { it.x } + lay.cells.minOf { it.x }) / 2f
                val cy = (lay.cells.maxOf { it.y } + lay.cells.minOf { it.y }) / 2f
                assertEquals("$name n=$n", 0f, cx, 0.01f); assertEquals("$name n=$n", 0f, cy, 0.01f)
                // no overlaps
                for (i in lay.cells.indices) for (j in i + 1 until lay.cells.size)
                    assertTrue("$name n=$n overlap", hypot(lay.cells[i].x - lay.cells[j].x, lay.cells[i].y - lay.cells[j].y) >= 0.99f)
                // more apps never get noticeably bigger icons (a neater shape may win by a few percent)
                assertTrue("$name n=$n cell ${lay.cell} > $last", lay.cell <= last * 1.05f)
                last = lay.cell
                assertTrue(lay.cell >= minCell - 0.5f || lay.overflow > 0f)
            }
        }
    }

    @Test fun landscapeGetsWideRowsAndPortraitTallOnes() {
        val wide = honeyLayout(20, 1920f, 1000f, minCell, maxCell)
        val tall = honeyLayout(20, 1200f, 1720f, minCell, maxCell)
        assertTrue("landscape rows ${wide.rows}", wide.rows.size < tall.rows.size)
        assertTrue("landscape ${wide.rows} vs portrait ${tall.rows}", wide.rows.max() > tall.rows.max())
        // 12 apps in landscape are clearly bigger than the old fixed size (140 at most 1.5x, about 210)
        assertTrue(honeyLayout(12, 1920f, 1000f, minCell, maxCell).cell > 210f)
    }

    @Test fun tooManyAppsPanUpAndDown() {
        val lay = honeyLayout(120, 1200f, 900f, minCell, maxCell)
        assertTrue(lay.overflow > 0f)
        assertTrue(honeySpan(lay.cells).first * lay.cell <= 1200f * HONEY_FILL_W + 1f)
        assertEquals(1f, honeyEdgeScale(0f, 450f, 126f), 0f)
        assertEquals(0f, honeyEdgeScale(450f + 126f, 450f, 126f), 0f)
    }

    @Test fun everyRowPacksIntoHexagons() {
        listOf(listOf(4, 3, 4), listOf(5, 5), listOf(3, 3, 2), listOf(6, 5, 6, 5, 1)).forEach { rows ->
            val cells = honeyCells(rows)
            assertEquals(rows.sum(), cells.size)
            for (i in cells.indices) for (j in i + 1 until cells.size)
                assertTrue("$rows overlap", hypot(cells[i].x - cells[j].x, cells[i].y - cells[j].y) >= 0.99f)
            // rows sit sqrt(3)/2 apart and neighbouring rows are offset by half a cell
            val ys = cells.map { it.y }.distinct().sorted()
            ys.zipWithNext().forEach { (a, b) -> assertEquals(sqrt(3f) / 2f, b - a, 0.001f) }
        }
    }

    // ---- 3. one smooth shutdown

    @Test fun poweringOffFadesToDarkBeforeShutdownStarts() {
        assertEquals(0f, powerOffAlpha(0f), 0f)
        assertEquals(1f, powerOffAlpha(600f), 0f)
        assertEquals(0f, powerOffAlpha((POWER_SHOW_MS + POWER_FADE_MS).toFloat()), 0f)
        // shutdown (and its turn to portrait) only starts once the words have gone
        assertTrue(VoidKeysService.POWER_HANDOFF_MS >= POWER_SHOW_MS + POWER_FADE_MS + 100)
        // and not so long that it feels stuck
        assertTrue(VoidKeysService.POWER_HANDOFF_MS <= 2000)
        // the cover's colour is the shutdown animation's background (void_balloon BG 11, 11, 13)
        assertEquals(0xFF0B0B0D.toInt(), VoidKeysService.POWER_BG)
    }

    @Test fun theCoverIsOpaqueAndDoesntAnimateTheTurn() {
        val t = File("src/main/java/com/andysocial/voidos/VoidKeys.kt").readText()
        val body = t.substringAfter("fun goingDown(restart: Boolean)").substringBefore("private var goingDownView")
        assertTrue(body.contains("PixelFormat.OPAQUE"))
        assertTrue(body.contains("ROTATION_ANIMATION_JUMPCUT"))
        assertTrue(body.contains("BootOrient.pin("))
        assertTrue(body.contains("POWER_HANDOFF_MS"))
    }

    @Test fun nextBootUsesTheWayTheTabletWasHeld() {
        // shutdown has already turned the screen to portrait (0) when Android's broadcast comes
        assertEquals(90, bootRotation(pinned = 90, settled = 270, now = 0))
        assertEquals(270, bootRotation(pinned = -1, settled = 270, now = 0))
        assertEquals(0, bootRotation(pinned = -1, settled = -1, now = 0))
    }
}
