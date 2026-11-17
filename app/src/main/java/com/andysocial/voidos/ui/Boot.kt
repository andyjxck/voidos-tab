package com.andysocial.voidos.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.res.ResourcesCompat
import com.andysocial.voidos.R
import kotlin.math.PI
import kotlin.math.sin

/**
 * The start animation, played once per power-on (tap to skip).
 *
 *  0.0-0.4 s  a teal scan line crosses the black screen
 *  0.3-1.3 s  the "void" wordmark wipes in from the left, root sign first
 *  1.3-1.8 s  the i's teal dot pops in with a short glow
 *  1.7-2.1 s  TAB EDITION fades in
 *  2.0-2.4 s  STARTING HUB fades in
 *  2.2-3.2 s  the dot slides into the green bar and back, then the purple
 *             one, like one round of the loading animation on the Android
 *             boot animation (tools/logo_loading_anim.py)
 *  3.2-3.6 s  fade to the hub
 *
 * tools/bootanim.py draws the same sequence frame by frame for the real
 * Android boot animation, with the same layout (tools/voidmark.py
 * lockup_geometry), so the two match.
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
    Box(Modifier.fillMaxSize().background(V.Bg).pointerInput(Unit) { detectTapGestures { onDone() } }) {
        Canvas(Modifier.fillMaxSize()) {
            val s = t.value
            val fade = if (s > 3.2f) 1f - ((s - 3.2f) / 0.4f).coerceIn(0f, 1f) else 1f
            val w = size.width; val h = size.height
            val unit = minOf(w, h)
            // same layout as tools/voidmark.py lockup_geometry
            val ww = unit * 0.70f
            val wh = ww / Mark.WORD_ASPECT
            val c = Offset(w / 2, h / 2 - unit * 0.06f)
            val subY = c.y + wh / 2 + unit * 0.085f

            // scan line
            if (s < 0.5f) {
                val y = h * (s / 0.45f).coerceIn(0f, 1f)
                drawLine(V.Teal.copy(alpha = 0.7f * (1f - s / 0.5f)), Offset(0f, y), Offset(w, y), unit * 0.004f)
            }

            // the wordmark
            val r0 = ((s - 0.3f) / 1.0f).coerceIn(0f, 1f)
            val reveal = 1f - (1f - r0) * (1f - r0) * (1f - r0)
            val dotT = ((s - 1.3f) / 0.5f).coerceIn(0f, 1f)
            val dot = if (dotT <= 0f) 0f else (1f + 0.35f * sin(dotT * PI).toFloat()) * dotT
            val glow = sin((dotT * PI).toFloat()).coerceAtLeast(0f)
            val sh = ((s - 2.2f) / 1.0f).coerceIn(0f, 1f)
            val shuttle = if (sh <= 0f || sh >= 1f) 0f else sin(sh * 2 * PI).toFloat()
            drawVoidWord(c, ww, reveal, dot, glow, alpha = fade, shuttle = shuttle)

            val paint = android.graphics.Paint().apply {
                isAntiAlias = true; typeface = kenney; textAlign = android.graphics.Paint.Align.CENTER
                color = android.graphics.Color.rgb(70, 216, 224)
            }
            val subA = ((s - 1.7f) / 0.4f).coerceIn(0f, 1f) * fade
            if (subA > 0f) {
                paint.textSize = unit * 0.030f; paint.letterSpacing = 0.5f; paint.alpha = (255 * subA).toInt()
                drawContext.canvas.nativeCanvas.drawText("TAB EDITION", w / 2, subY, paint)
            }

            // starting hub: the line fades in under TAB EDITION, as on the boot animation
            val hubA = ((s - 1.95f) / 0.4f).coerceIn(0f, 1f) * fade
            if (hubA > 0f) {
                paint.textSize = unit * 0.018f; paint.letterSpacing = 0.3f; paint.color = android.graphics.Color.rgb(138, 148, 168)
                paint.alpha = (255 * hubA).toInt()
                drawContext.canvas.nativeCanvas.drawText("STARTING HUB", w / 2, subY + unit * 0.10f, paint)
            }
        }
    }
}
