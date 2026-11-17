package com.andysocial.voidos.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.res.ResourcesCompat
import com.andysocial.voidos.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The start animation, played once per power-on (tap to skip).
 *
 *  0.0-0.4 s  a teal scan line crosses the black screen
 *  0.3-1.5 s  the mark's seven sides draw in, one lap colour at a time
 *  1.4-1.9 s  the white core pops in with a short glow
 *  1.5-2.5 s  sixty second-ticks sweep round the mark, VOIDOS types in
 *  2.4-3.2 s  "starting hub" bar fills
 *  3.2-3.6 s  fade to the hub
 *
 * tools/bootanim.py draws the same sequence frame by frame for the real
 * Android boot animation, so the two match.
 */
@Composable
fun BootAnim(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val t = remember { Animatable(0f) }
    val kenney = remember { ResourcesCompat.getFont(ctx, R.font.kenney_future) }
    LaunchedEffect(Unit) {
        t.animateTo(3.6f, tween(3600, easing = LinearEasing))
        onDone()
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(V.Bg).pointerInput(Unit) { detectTapGestures { onDone() } }) {
        Canvas(Modifier.fillMaxSize()) {
            val s = t.value
            val fade = if (s > 3.2f) 1f - ((s - 3.2f) / 0.4f).coerceIn(0f, 1f) else 1f
            val w = size.width; val h = size.height
            val unit = minOf(w, h)
            val c = Offset(w / 2, h / 2 - unit * 0.08f)
            val r = unit * 0.16f

            // scan line
            if (s < 0.5f) {
                val y = h * (s / 0.45f).coerceIn(0f, 1f)
                drawLine(V.Teal.copy(alpha = 0.7f * (1f - s / 0.5f)), Offset(0f, y), Offset(w, y), unit * 0.004f)
            }

            // second ticks sweeping round
            val tickP = ((s - 1.5f) / 1.0f).coerceIn(0f, 1f)
            if (tickP > 0f) {
                val n = (60 * tickP).toInt()
                for (i in 0 until n) {
                    val a = -PI / 2 + i * 2 * PI / 60
                    val r0 = r * 1.32f; val r1 = r * (if (i % 5 == 0) 1.48f else 1.40f)
                    val col = V.Lap[(i / 10) % 6].copy(alpha = 0.85f * fade)
                    drawLine(col, Offset(c.x + (r0 * cos(a)).toFloat(), c.y + (r0 * sin(a)).toFloat()),
                        Offset(c.x + (r1 * cos(a)).toFloat(), c.y + (r1 * sin(a)).toFloat()), unit * 0.005f)
                }
            }

            // the mark
            val prog = ((s - 0.3f) / 1.2f).coerceIn(0f, 1f)
            val coreT = ((s - 1.4f) / 0.5f).coerceIn(0f, 1f)
            val pop = if (coreT <= 0f) 0f else (1f + 0.35f * sin(coreT * PI).toFloat()) * coreT.coerceAtMost(1f)
            val glow = sin((coreT * PI).toFloat()).coerceAtLeast(0f)
            if (fade < 1f) {
                drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, w, h), androidx.compose.ui.graphics.Paint().apply { alpha = fade })
            }
            drawVoidMark(c, r, prog, pop, glow)
            if (fade < 1f) drawContext.canvas.restore()

            // wordmark types in
            val word = "VOIDOS"
            val typed = (((s - 1.6f) / 0.6f).coerceIn(0f, 1f) * word.length).toInt()
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true; typeface = kenney; textAlign = android.graphics.Paint.Align.CENTER
                color = android.graphics.Color.WHITE; textSize = unit * 0.075f; letterSpacing = 0.25f; alpha = (255 * fade).toInt()
            }
            val wy = c.y + r * 1.48f + unit * 0.12f
            if (typed > 0) drawContext.canvas.nativeCanvas.drawText(word.substring(0, typed), w / 2, wy, paint)
            val subA = ((s - 2.2f) / 0.3f).coerceIn(0f, 1f) * fade
            if (subA > 0f) {
                paint.textSize = unit * 0.024f; paint.letterSpacing = 0.5f
                paint.color = android.graphics.Color.rgb(70, 216, 224); paint.alpha = (255 * subA).toInt()
                drawContext.canvas.nativeCanvas.drawText("TAB EDITION", w / 2, wy + unit * 0.05f, paint)
            }

            // starting bar
            val barP = ((s - 2.4f) / 0.8f).coerceIn(0f, 1f)
            if (s > 2.3f) {
                val bw = unit * 0.36f; val bh = unit * 0.008f
                val bx = w / 2 - bw / 2; val by = wy + unit * 0.11f
                drawRect(V.Line.copy(alpha = fade), Offset(bx, by), Size(bw, bh))
                val segs = 6
                for (i in 0 until segs) {
                    val x0 = bx + bw * i / segs; val x1 = bx + bw * minOf(barP, (i + 1f) / segs)
                    if (x1 > x0) drawRect(V.Lap[i].copy(alpha = fade), Offset(x0, by), Size(x1 - x0, bh))
                }
                paint.textSize = unit * 0.018f; paint.letterSpacing = 0.3f; paint.color = android.graphics.Color.rgb(138, 148, 168)
                paint.alpha = (255 * fade).toInt()
                drawContext.canvas.nativeCanvas.drawText(if (barP < 1f) "STARTING HUB" else "READY", w / 2, by + unit * 0.045f, paint)
            }
            if (s < 0.3f) drawRect(V.Teal.copy(alpha = 0f), Offset.Zero, Size(w, h), style = Stroke(1f))
        }
    }
}
