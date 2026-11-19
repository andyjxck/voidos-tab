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

/** The book's rules: up to two pages open side by side, the watch face filling any free half, back again on close. */
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

    @Test fun twoPagesStayOpenSideBySide() {
        // Weather open on the right, Notes dropped on the left: both stay, the face steps aside
        val s = BookState().drop(weather, toFirst = false).drop(notes, toFirst = true)
        assertEquals(BookState(notes, weather), s)
        assertTrue(s.full); assertFalse(s.faceShown)
        // and the other way round
        assertEquals(BookState(weather, notes), BookState().drop(notes, false).drop(weather, true))
        // a third one takes the half it's dropped on: still two, never three
        val t = s.drop(tools, toFirst = false)
        assertEquals(BookState(notes, tools), t)
        // closing one brings the watch face back into that half, the other stays
        val c = s.close(isFirst = true)
        assertEquals(BookState(second = weather), c)
        assertTrue(c.faceShown); assertFalse(c.full)
    }

    @Test fun droppingTheOtherHalfSwaps() {
        val s = BookState(notes, weather).drop(notes, toFirst = false)
        assertEquals(BookState(weather, notes), s)
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

    @Test fun oneAppBesideOneScreen() {
        // an app and a screen share the book, both open
        val withScreen = BookState().drop(notes, false).drop(yt, true)
        assertEquals(BookState(yt, notes), withScreen)
        assertTrue(withScreen.full)
        assertEquals(BookState(second = notes), withScreen.withoutApp())
        // Android has one split: a second app replaces the first, and the face takes the other half
        val s = BookState().drop(yt, false).drop(spotify, true)
        assertEquals(BookState(first = spotify), s)
        assertEquals(BookState(spotify, notes), BookState(yt, notes).drop(spotify, true))
    }

    @Test fun survivesSaving() {
        val s = BookState().drop(yt, true)
        assertEquals(s, BookState.decode(s.encode()))
        val two = BookState(notes, weather)
        assertEquals(two, BookState.decode(two.encode()))
        val mixed = BookState(notes, yt)
        assertEquals(mixed, BookState.decode(mixed.encode()))
        // two apps can't share one split: the first is kept
        assertEquals(BookState(first = yt), BookState.decode("a:com.google.android.youtube|a:com.spotify.music"))
        assertEquals(BookState(), BookState.decode("junk"))
        assertEquals(BookState(), BookState.decode("s:FACE|s:NOPE"))
    }

    @Test fun awayFiveSecondsStartsFresh() {
        assertFalse(BookState.awayLongEnough(null, 10_000))
        assertFalse(BookState.awayLongEnough(1_000, 5_999))
        assertTrue(BookState.awayLongEnough(1_000, 6_000))
        assertTrue(BookState.awayLongEnough(1_000, 60_000))
    }

    @Test fun splitPutsAppOnItsSide() {
        val first = splitCommand("a/.A", "me/.Main", appFirst = true)
        assertTrue(first.indexOf("--windowingMode 3 -n a/.A") in 0 until first.indexOf("--windowingMode 4 -n me/.Main"))
        val second = splitCommand("a/.A", "me/.Main", appFirst = false)
        assertTrue(second.indexOf("--windowingMode 3 -n me/.Main") in 0 until second.indexOf("--windowingMode 4 -n a/.A"))
    }
}
