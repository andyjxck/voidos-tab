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

/** The book's rules: two halves at most, the watch face pushed to the other half, back again on close. */
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

    @Test fun secondDropOnFaceReplacesFaceTwoAtMost() {
        val s = BookState().drop(notes, toFirst = false).drop(weather, toFirst = true)
        assertEquals(weather, s.first); assertEquals(notes, s.second)
        assertFalse(s.faceShown)
        // a third one only replaces the half it's dropped on
        val t = s.drop(tools, toFirst = true)
        assertEquals(tools, t.first); assertEquals(notes, t.second)
    }

    @Test fun closingBringsFaceBack() {
        val s = BookState().drop(notes, false).drop(weather, true)
        val one = s.close(isFirst = true)
        assertEquals(true, one.faceFirst)
        val none = one.close(isFirst = false)
        assertFalse(none.isOpen); assertNull(none.faceFirst); assertTrue(none.faceShown)
    }

    @Test fun draggingAcrossMovesOrSwaps() {
        val moved = BookState().drop(notes, false).drop(notes, true)
        assertEquals(notes, moved.first); assertNull(moved.second)
        val swapped = BookState().drop(notes, false).drop(weather, true).drop(notes, true)
        assertEquals(notes, swapped.first); assertEquals(weather, swapped.second)
    }

    @Test fun onlyOneAppAtATime() {
        val s = BookState().drop(yt, false).drop(spotify, true)
        assertEquals(spotify, s.first); assertNull(s.second)
        assertEquals(true, BookState().drop(notes, false).drop(yt, true).appFirst)
        assertEquals(BookState(second = notes), BookState().drop(notes, false).drop(yt, true).withoutApp())
    }

    @Test fun survivesSaving() {
        val s = BookState().drop(notes, false).drop(yt, true)
        assertEquals(s, BookState.decode(s.encode()))
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
