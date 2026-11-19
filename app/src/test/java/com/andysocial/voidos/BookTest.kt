package com.andysocial.voidos

import com.andysocial.voidos.ui.BookState
import com.andysocial.voidos.ui.Module
import com.andysocial.voidos.ui.Pane
import com.andysocial.voidos.ui.splitCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The book's rules: one page and Home, the watch face pushed to the other half, back again on close. */
class BookTest {
    private val notes = Pane.screen(Module.NOTES)
    private val weather = Pane.screen(Module.WEATHER)
    private val tools = Pane.screen(Module.TOOLS)
    private val yt = Pane.app("com.google.android.youtube")
    private val spotify = Pane.app("com.spotify.music")

    @Test fun dropPushesFaceToOtherSide() {
        val right = BookState().drop(notes, toFirst = false)
        assertEquals(true, right.faceFirst) // Notes on the right, face on the left
        val left = BookState().drop(notes, toFirst = true)
        assertEquals(false, left.faceFirst) // Notes on the left, face on the right
    }

    @Test fun secondDropReplacesHomeAlwaysStays() {
        val s = BookState().drop(notes, toFirst = false).drop(weather, toFirst = true)
        assertEquals(weather, s.first); assertNull(s.second)
        assertTrue(s.faceShown); assertEquals(false, s.faceFirst)
        // a third one only replaces what's open, never adds a page
        val t = s.drop(tools, toFirst = false)
        assertEquals(BookState(second = tools), t)
        assertTrue(t.faceShown)
    }

    @Test fun closingBringsFaceBack() {
        val one = BookState().drop(notes, false)
        val none = one.close(isFirst = false)
        assertFalse(none.isOpen); assertNull(none.faceFirst); assertTrue(none.faceShown)
    }

    @Test fun draggingAcrossMoves() {
        val moved = BookState().drop(notes, false).drop(notes, true)
        assertEquals(notes, moved.first); assertNull(moved.second)
        val again = moved.drop(notes, true)
        assertEquals(moved, again)
    }

    @Test fun onlyOneAppAtATime() {
        val s = BookState().drop(yt, false).drop(spotify, true)
        assertEquals(spotify, s.first); assertNull(s.second)
        // an app dropped beside an open screen replaces it: the app and Home, nothing else
        val withScreen = BookState().drop(notes, false).drop(yt, true)
        assertEquals(BookState(first = yt), withScreen)
        assertEquals(BookState(), withScreen.withoutApp())
    }

    @Test fun survivesSaving() {
        val s = BookState().drop(yt, true)
        assertEquals(s, BookState.decode(s.encode()))
        assertEquals(BookState(second = notes), BookState.decode(BookState().drop(notes, false).encode()))
        // an older build's two-page book comes back as one page and Home
        assertEquals(BookState(first = notes), BookState.decode("s:NOTES|s:WEATHER"))
        assertEquals(BookState(second = yt), BookState.decode("s:NOTES|a:com.google.android.youtube"))
        assertEquals(BookState(), BookState.decode("junk"))
        assertEquals(BookState(), BookState.decode("s:FACE|s:NOPE"))
    }

    @Test fun splitPutsAppOnItsSide() {
        val first = splitCommand("a/.A", "me/.Main", appFirst = true)
        assertTrue(first.indexOf("--windowingMode 3 -n a/.A") in 0 until first.indexOf("--windowingMode 4 -n me/.Main"))
        val second = splitCommand("a/.A", "me/.Main", appFirst = false)
        assertTrue(second.indexOf("--windowingMode 3 -n me/.Main") in 0 until second.indexOf("--windowingMode 4 -n a/.A"))
    }
}
