package com.andysocial.voidos

import com.andysocial.voidos.ui.DASH_DOT
import com.andysocial.voidos.ui.DASHED
import com.andysocial.voidos.ui.DOTTED
import com.andysocial.voidos.ui.HIGHLIGHTER_ALPHA
import com.andysocial.voidos.ui.InkEdit
import com.andysocial.voidos.ui.InkHistory
import com.andysocial.voidos.ui.InkPrefs
import com.andysocial.voidos.ui.LONG_DASH
import com.andysocial.voidos.ui.LineStyles
import com.andysocial.voidos.ui.MAX_PEN
import com.andysocial.voidos.ui.MIN_PEN
import com.andysocial.voidos.ui.PAPER_GRID
import com.andysocial.voidos.ui.PAPER_PLAIN
import com.andysocial.voidos.ui.SHAPE_ARROW
import com.andysocial.voidos.ui.SHAPE_ELLIPSE
import com.andysocial.voidos.ui.SHAPE_LINE
import com.andysocial.voidos.ui.SHAPE_RECT
import com.andysocial.voidos.ui.SOLID
import com.andysocial.voidos.ui.SPARSE_DOTS
import com.andysocial.voidos.ui.TOOL_HIGHLIGHTER
import com.andysocial.voidos.ui.TOOL_PEN
import com.andysocial.voidos.ui.applyTo
import com.andysocial.voidos.ui.argbToHsv
import com.andysocial.voidos.ui.clampToPage
import com.andysocial.voidos.ui.effectFor
import com.andysocial.voidos.ui.eraseIndexed
import com.andysocial.voidos.ui.hex
import com.andysocial.voidos.ui.hsvToArgb
import com.andysocial.voidos.ui.inkArgb
import com.andysocial.voidos.ui.pushRecent
import com.andysocial.voidos.ui.shapePoints
import com.andysocial.voidos.ui.sizeFromSlider
import com.andysocial.voidos.ui.sliderFromSize
import com.andysocial.voidos.ui.smoothPoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The Notes drawing tools: undo and redo, sizes, shapes, the page edge, colours and remembered settings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotesInkTest {

    private fun s(x: Float, y: Float, argb: Int = -1) = Stroke(argb, 0.01f, SOLID, listOf(x, y, x + 0.1f, y))

    @Test fun undoRedoStrokes() {
        val ink = mutableListOf<Stroke>()
        val h = InkHistory()
        val a = s(0.1f, 0.1f); val b = s(0.1f, 0.3f)
        ink.add(a); h.record(InkEdit.Add(a))
        ink.add(b); h.record(InkEdit.Add(b))
        assertTrue(h.undo(ink)); assertEquals(listOf(a), ink)
        assertTrue(h.canRedo)
        assertTrue(h.redo(ink)); assertEquals(listOf(a, b), ink)
        assertFalse("nothing left to redo", h.redo(ink))
        // a new stroke after an undo drops the redo step
        h.undo(ink)
        val c = s(0.5f, 0.5f); ink.add(c); h.record(InkEdit.Add(c))
        assertFalse(h.canRedo)
        assertEquals(listOf(a, c), ink)
    }

    @Test fun undoAClearBringsEverythingBack() {
        val ink = mutableListOf(s(0.1f, 0.1f), s(0.2f, 0.2f), s(0.3f, 0.3f))
        val before = ink.toList()
        val h = InkHistory()
        val e = InkEdit.Clear(ink.toList()); e.applyTo(ink); h.record(e)
        assertTrue(ink.isEmpty())
        assertTrue("undo is still possible on an empty page", h.canUndo(ink))
        h.undo(ink); assertEquals(before, ink)
        h.redo(ink); assertTrue(ink.isEmpty())
        h.undo(ink); assertEquals(before, ink)
    }

    @Test fun undoAnEraserSweepPutsStrokesBackInPlace() {
        val a = s(0.1f, 0.1f); val b = s(0.1f, 0.5f); val c = s(0.1f, 0.9f)
        val ink = mutableListOf(a, b, c)
        val h = InkHistory()
        // one sweep rubs out a then c
        val sweep = listOfNotNull(eraseIndexed(ink, 0.15f, 0.1f, 0.02f), eraseIndexed(ink, 0.15f, 0.9f, 0.02f))
        assertEquals(listOf(b), ink)
        assertNull("nothing there", eraseIndexed(ink, 0.9f, 0.2f, 0.02f))
        h.record(InkEdit.Batch(sweep))
        h.undo(ink); assertEquals("same strokes, same order", listOf(a, b, c), ink)
        h.redo(ink); assertEquals(listOf(b), ink)
    }

    @Test fun undoOnAJustOpenedNoteTakesBackTheLastStroke() {
        val a = s(0.1f, 0.1f); val b = s(0.2f, 0.2f)
        val ink = mutableListOf(a, b)
        val h = InkHistory()
        assertTrue(h.canUndo(ink)); assertFalse(h.canRedo)
        h.undo(ink); assertEquals(listOf(a), ink)
        h.redo(ink); assertEquals(listOf(a, b), ink)
        assertFalse(InkHistory().canUndo(emptyList()))
    }

    @Test fun anySizeBetweenTheEnds() {
        assertEquals(MIN_PEN, sizeFromSlider(0f), 1e-6f)
        assertEquals(MAX_PEN, sizeFromSlider(1f), 1e-6f)
        var last = 0f
        for (i in 0..100) { val v = sizeFromSlider(i / 100f); assertTrue("grows smoothly", v > last || i == 0); last = v }
        listOf(0.0013f, 0.0057f, 0.0123f, 0.05f).forEach { assertEquals(it, sizeFromSlider(sliderFromSize(it)), 1e-5f) }
        // the old four sizes are all still reachable
        listOf(0.003f, 0.006f, 0.012f, 0.024f).forEach { assertTrue(it in MIN_PEN..MAX_PEN) }
    }

    @Test fun shapes() {
        assertEquals(listOf(0.1f, 0.1f, 0.5f, 0.3f), shapePoints(SHAPE_LINE, 0.1f, 0.1f, 0.5f, 0.3f))
        val rect = shapePoints(SHAPE_RECT, 0.1f, 0.1f, 0.5f, 0.3f)
        assertEquals(10, rect.size); assertEquals(rect.take(2), rect.takeLast(2))
        val arrow = shapePoints(SHAPE_ARROW, 0.1f, 0.1f, 0.5f, 0.1f)
        assertEquals(10, arrow.size)
        assertTrue("head points back from the tip", arrow[4] < 0.5f && arrow[8] < 0.5f)
        val e = shapePoints(SHAPE_ELLIPSE, 0.1f, 0.1f, 0.5f, 0.3f)
        assertEquals(130, e.size)
        for (i in e.indices step 2) { assertTrue(e[i] in 0.0999f..0.5001f); assertTrue(e[i + 1] in 0.0999f..0.3001f) }
    }

    @Test fun smoothingKeepsTheEnds() {
        val pts = listOf(0f, 0f, 0.1f, 0.2f, 0.2f, 0f, 0.3f, 0.2f)
        val sm = smoothPoints(pts)
        assertTrue(sm.size > pts.size)
        assertEquals(listOf(0f, 0f), sm.take(2)); assertEquals(listOf(0.3f, 0.2f), sm.takeLast(2))
        assertEquals("a dot stays a dot", listOf(0.1f, 0.1f), smoothPoints(listOf(0.1f, 0.1f)))
    }

    @Test fun pointsStayOnThePage() {
        assertEquals(0f to 0f, clampToPage(-0.2f, -0.5f, 1.4f))
        assertEquals(1f to 1.4f, clampToPage(1.3f, 2f, 1.4f))
        assertEquals(0.5f to 0.7f, clampToPage(0.5f, 0.7f, 1.4f))
    }

    @Test fun colourWheelMaths() {
        assertEquals("#FF0000", hex(hsvToArgb(0f, 1f, 1f)))
        assertEquals("#00FF00", hex(hsvToArgb(120f, 1f, 1f)))
        assertEquals("#0000FF", hex(hsvToArgb(240f, 1f, 1f)))
        assertEquals("#FFFFFF", hex(hsvToArgb(77f, 0f, 1f)))
        listOf(0xFF3DDBC4.toInt(), 0xFF8A5CF6.toInt(), 0xFF123456.toInt(), 0xFFFFA500.toInt()).forEach { c ->
            val hsv = argbToHsv(c)
            assertEquals("round trip ${hex(c)}", hex(c), hex(hsvToArgb(hsv[0], hsv[1], hsv[2])))
        }
        // a pen at half opacity, and the highlighter always see-through
        assertEquals(128, inkArgb(TOOL_PEN, 0xFFFF0000.toInt(), 0.5f) ushr 24)
        assertEquals(HIGHLIGHTER_ALPHA * 255f, (inkArgb(TOOL_HIGHLIGHTER, 0xFFFF0000.toInt(), 1f) ushr 24).toFloat(), 1f)
    }

    @Test fun recentColours() {
        var r = emptyList<Int>()
        r = pushRecent(r, 0xFF0000); r = pushRecent(r, 0x00FF00); r = pushRecent(r, 0xFF0000)
        assertEquals(listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt()), r)
        repeat(20) { r = pushRecent(r, it * 1000) }
        assertEquals(8, r.size)
    }

    @Test fun everyLineStyleHasItsLook() {
        assertEquals(listOf(SOLID, DOTTED, SPARSE_DOTS, DASHED, LONG_DASH, DASH_DOT), LineStyles)
        assertNull(effectFor(SOLID, 4f))
        LineStyles.drop(1).forEach { assertNotNull("style $it", effectFor(it, 4f)) }
    }

    @Test fun penSettingsAreRemembered() {
        val app = RuntimeEnvironment.getApplication()
        InkPrefs(app).apply { rgb = 0xFF123456.toInt(); opacity = 0.4f; penSize = 0.0123f; style = DASH_DOT; shape = SHAPE_ARROW; smooth = true; recent = listOf(0xFF123456.toInt(), -1); setPaper(99L, PAPER_GRID) }
        InkPrefs(app).apply {
            assertEquals(0xFF123456.toInt(), rgb); assertEquals(0.4f, opacity, 1e-6f); assertEquals(0.0123f, penSize, 1e-6f)
            assertEquals(DASH_DOT, style); assertEquals(SHAPE_ARROW, shape); assertTrue(smooth)
            assertEquals(listOf(0xFF123456.toInt(), -1), recent)
            assertEquals(PAPER_GRID, paper(99L)); assertEquals(PAPER_PLAIN, paper(100L))
        }
    }

    @Test fun oldNotesLoadUnchanged() {
        // strokes saved before this update (sizes from the old four, styles 0-2) come back exactly
        val store = Store(RuntimeEnvironment.getApplication())
        val old = listOf(Stroke(-1, 0.003f, SOLID, listOf(0.1f, 0.1f, 0.2f, 0.2f)), Stroke(0xFF3DDBC4.toInt(), 0.024f, DASHED, listOf(0.3f, 0.3f, 0.4f, 0.5f)))
        store.saveNote(Note(5151L, "old", 0, 1L, old))
        assertEquals(old, Store(RuntimeEnvironment.getApplication()).notes.first { it.id == 5151L }.ink)
        store.deleteNote(5151L)
    }
}
