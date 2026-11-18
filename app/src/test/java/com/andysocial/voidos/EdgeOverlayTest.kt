package com.andysocial.voidos

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.andysocial.voidos.ui.EdgeSwipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings

/**
 * The edge wheels over other apps: two invisible strips on the side edges while another
 * app is in front, and a wheel window that opens from a swipe inwards on one of them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h600dp-xlarge-land-tvdpi", sdk = [33])
class EdgeOverlayTest {

    private val app get() = VoidApp.instance

    private var svcRef: EdgeOverlayService? = null
    private fun titled(prefix: String): List<View> {
        val s = svcRef ?: return emptyList()
        return when {
            prefix.startsWith("VoidOS edge wheel") -> if (s.wheelOpen) listOf(View(app)) else emptyList()
            prefix == "VoidOS edge left" -> s.strips.filter { (it.layoutParams as WindowManager.LayoutParams).title.toString() == "VoidOS edge left" }
            else -> s.strips.filter { (it.layoutParams as WindowManager.LayoutParams).title.toString().startsWith(prefix) }
        }
    }

    @Before
    fun allow() {
        ShadowSettings.setCanDrawOverlays(true)
    }

    @Test fun stripsOnlyWhileAnotherAppIsInFront() {
        val svc = Robolectric.buildService(EdgeOverlayService::class.java).create().startCommand(0, 1).get().also { svcRef = it }
        val strips = titled("VoidOS edge ")
        assertEquals("two edge strips", 2, strips.size)
        // VoidOS itself in front: it handles its own edges, so the strips stand down
        EdgeOverlay.hostShown(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(strips.all { it.visibility == View.GONE })
        // another app in front: the strips are live
        EdgeOverlay.hostShown(false)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(strips.all { it.visibility == View.VISIBLE })
        // they are narrow: only the very edge is VoidOS's, the rest of the screen goes to the app
        val lp = strips.first().layoutParams as WindowManager.LayoutParams
        val dens = app.resources.displayMetrics.density
        assertTrue("strip ${lp.width}px wide", lp.width <= (16 * dens).toInt())
        assertTrue("not focusable, so typing in the app is untouched", lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, lp.type)
        svc.onDestroy()
        assertEquals("strips removed when the service stops", 0, svc.strips.size)
    }

    @Test fun swipeInFromAStripOpensTheWheelWindow() {
        val svc = Robolectric.buildService(EdgeOverlayService::class.java).create().startCommand(0, 1).get().also { svcRef = it }
        EdgeOverlay.hostShown(false)
        shadowOf(Looper.getMainLooper()).idle()
        val left = titled("VoidOS edge left").single()
        val t0 = SystemClock.uptimeMillis()
        fun ev(a: Int, x: Float, dt: Long) = MotionEvent.obtain(t0, t0 + dt, a, x, 300f, 0).also { left.dispatchTouchEvent(it); it.recycle() }
        // a tap on the edge does nothing
        ev(MotionEvent.ACTION_DOWN, 2f, 0); ev(MotionEvent.ACTION_UP, 2f, 60)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("a tap opens nothing", 0, titled("VoidOS edge wheel").size)
        // a swipe inwards opens the wheel window over the app
        ev(MotionEvent.ACTION_DOWN, 2f, 100)
        ev(MotionEvent.ACTION_MOVE, 20f, 120)
        ev(MotionEvent.ACTION_MOVE, 60f, 140)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("wheel window open; log: ${EdgeSwipe.last.value}", 1, titled("VoidOS edge wheel").size)
        // while it's open the strips step aside
        assertTrue(svc.strips.all { it.visibility == View.GONE })
        svc.onDestroy()
        assertEquals(0, titled("VoidOS edge wheel").size)
    }

    @Test fun noPermissionNoStrips() {
        ShadowSettings.setCanDrawOverlays(false)
        assertTrue(!EdgeOverlay.canDraw(app))
        assertTrue(!EdgeOverlay.allowed.value)
    }
}
