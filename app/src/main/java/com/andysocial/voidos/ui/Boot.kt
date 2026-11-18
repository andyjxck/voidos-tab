package com.andysocial.voidos.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * The start animation, played once per power-on (tap to skip). It carries on
 * from the Android boot animation (root/bootanimation.zip, drawn by
 * tools/void_balloon.py) without a cut:
 *
 *  - the boot animation ends on the still "void" wordmark, the same picture
 *    the power-on logo shows, and this opens on that exact frame (same place,
 *    size and background, tools/voidmark.py lockup_geometry)
 *  - it holds that frame until the boot animation has really gone
 *    (init.svc.bootanim), so nothing plays out of sight underneath it
 *  - then the wordmark balloons up (0.25-1.05 s) and opens out into the hub,
 *    which shows through as the wordmark grows and fades (1.05-1.65 s)
 *
 * The hub is already drawn under this (VoidRoot), so the end is a fade, not a
 * cut.
 */
@Composable
fun BootAnim(onDone: () -> Unit) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        BootAnimWait.untilGone()
        t.animateTo(BOOT_END, tween((BOOT_END * 1000).toInt(), easing = LinearEasing))
        onDone()
    }
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDone() } }) {
        Canvas(Modifier.fillMaxSize()) {
            val s = t.value
            val w = size.width; val h = size.height
            val unit = minOf(w, h)
            // same layout as tools/voidmark.py lockup_geometry
            val ww = unit * 0.70f
            val wh = ww / Mark.WORD_ASPECT
            val c = Offset(w / 2, h / 2 - unit * 0.06f)
            // the wordmark's own box centre (drawVoidWord shifts it left by WORD_OPTICAL_X):
            // the boot animation swells around this point
            val m = Offset(c.x - ww * WORD_OPTICAL_X, c.y)

            val open = ((s - 1.05f) / 0.6f).coerceIn(0f, 1f)          // opening into the hub
            val openE = open * open * (3 - 2 * open)
            val air = bootAir(s) * (1f - openE)
            val wordA = 1f - openE
            // the void behind the wordmark lifts off the hub as it opens
            drawRect(V.Bg.copy(alpha = 1f - openE))
            if (air > 0f) {
                // the soft teal light inside the balloon (tools/void_balloon.py glow)
                for (i in 6 downTo 1) {
                    val rx = ww / 2 * (0.35f + 0.13f * i); val ry = wh / 2 * (0.55f + 0.22f * i)
                    drawOval(V.Teal.copy(alpha = 0.022f * air.coerceAtMost(1f) * (7 - i) / 6f * wordA),
                        Offset(m.x - rx, m.y - ry), Size(rx * 2, ry * 2))
                }
            }
            // swells from the middle like the boot animation, then grows as it opens
            val grow = 1f + 0.9f * openE
            val sx = (1f + 0.11f * air) * grow
            val sy = (1f + 0.16f * air) * grow
            if (wordA > 0f) {
                withTransform({ scale(sx, sy, pivot = m) }) {
                    drawVoidWord(c, ww, alpha = wordA)
                }
            }
        }
    }
}

private const val BOOT_END = 1.65f

/** Air in the balloon at time s: none on the still frame, then a damped
 *  spring onto 1, as tools/void_balloon.py air_intro. */
internal fun bootAir(s: Float): Float {
    val t = ((s - 0.25f) / 0.8f).coerceIn(0f, 1f)
    if (t <= 0f) return 0f
    val x = t * 2.6f
    return (1 - exp(-2.0f * x) * (cos(3.6f * x) + (2.0f / 3.6f) * sin(3.6f * x)))
}

/** Waits until the Android boot animation has finished and left the screen. */
object BootAnimWait {
    /** Longest wait: the boot animation's lava lamp loop can run 3.2 s past
     *  the hub's first frame, then 0.9 s to drain back onto the still logo;
     *  anything past this means the property cannot be read, so go anyway. */
    private const val MAX_MS = 12_000L

    suspend fun untilGone() = withContext(Dispatchers.IO) {
        var waited = 0L
        while (waited < MAX_MS) {
            val st = prop("init.svc.bootanim")
            // "running" while it plays; "stopped" (or empty, if this Android
            // never ran one or the property cannot be read) once it is gone
            if (st != "running") break
            delay(50); waited += 50
        }
        // the boot animation's surface goes a frame or two after the process
        delay(if (waited > 0) 120 else 0)
    }

    private fun prop(key: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
    }.getOrDefault("")
}
