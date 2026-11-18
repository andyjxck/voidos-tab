package com.andysocial.voidos

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.andysocial.voidos.ui.LocalPortrait
import com.andysocial.voidos.ui.Module
import com.andysocial.voidos.ui.Shell
import com.andysocial.voidos.ui.V
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A rough profile of the edge wheel on the JVM: open it, slide a finger round it for
 * 60 frames, and time each frame (touch, recomposition, layout and a software draw of
 * the whole window). Software drawing on a PC is not the tablet's GPU, so the numbers
 * only compare one build with another; the tablet's own figure comes from
 * `adb shell dumpsys gfxinfo com.andysocial.voidos`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h600dp-xlarge-land-tvdpi", sdk = [33])
class WheelPerfTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private var base = 0L
    private var t = 0L
    private fun ev(action: Int, x: Float, y: Float) {
        if (action == android.view.MotionEvent.ACTION_DOWN) { base = android.os.SystemClock.uptimeMillis(); t = base } else t += 16
        rule.runOnUiThread {
            val e = android.view.MotionEvent.obtain(base, t, action, x, y, 0)
            rule.activity.dispatchTouchEvent(e)
            e.recycle()
        }
    }

    @Test fun slideRoundTheWheel() {
        val act = rule.activity
        rule.mainClock.autoAdvance = false
        val store = VoidApp.instance.store
        store.set("setupDone", true)
        com.andysocial.voidos.ui.LockState.locked.value = false
        rule.runOnUiThread {
            val view = act.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as androidx.compose.ui.platform.ComposeView
            view.setContent {
                BoxWithConstraints(Modifier.fillMaxSize().background(V.Bg)) {
                    CompositionLocalProvider(LocalPortrait provides (maxHeight > maxWidth)) { Shell(store, act, Module.FACE) }
                }
            }
        }
        rule.mainClock.advanceTimeBy(2000); rule.waitForIdle()
        val root = act.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        val gif = File("build/wheelgif").apply { deleteRecursively(); mkdirs() }
        var shot = 0
        var record = false
        var n = 0
        fun frame(): Double {
            val a = System.nanoTime()
            rule.mainClock.advanceTimeBy(16)
            rule.runOnUiThread { root.draw(cv) }
            val ms = (System.nanoTime() - a) / 1e6
            if (record && n++ % 2 == 0) {
                val small = Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
                File(gif, "f%03d.png".format(shot++)).outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            return ms
        }
        val h = root.height.toFloat()
        val out = StringBuilder()
        repeat(2) { round ->
            record = round == 0
            if (record) repeat(6) { frame() }
            val a = System.nanoTime()
            ev(android.view.MotionEvent.ACTION_DOWN, 4f, h / 2)
            ev(android.view.MotionEvent.ACTION_MOVE, 40f, h / 2)
            frame()
            val open = (System.nanoTime() - a) / 1e6
            if (record) repeat(14) { frame() }
            val times = (0 until 60).map { k ->
                val y = h / 2 + (h * 0.3f) * kotlin.math.sin(k / 60.0 * 2 * Math.PI).toFloat()
                ev(android.view.MotionEvent.ACTION_MOVE, 60f, y)
                frame()
            }.sorted()
            ev(android.view.MotionEvent.ACTION_UP, 60f, h / 2)
            repeat(30) { frame() }
            out.append("round ${round + 1}: open ${"%.1f".format(open)} ms, slide avg ${"%.1f".format(times.average())} ms, median ${"%.1f".format(times[30])} ms, 90th ${"%.1f".format(times[54])} ms\n")
        }
        File("build/wheel-perf.txt").writeText(out.toString())
        println(out)
        assertTrue(out.isNotEmpty())
    }
}
