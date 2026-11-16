package com.andysocial.voidos.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.andysocial.voidos.BuildConfig
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The VoidOS start sequence (a real boot animation needs root, so this plays
 * inside the app, once per power-on):
 *  0.00-0.18  a single white point appears in the void
 *  0.12-0.42  it opens into an octagon
 *  0.30-0.72  the lap ring sweeps round in the six watch colours
 *  0.55-0.85  V O I D O S types in, one letter at a time
 *  0.88-1.00  everything collapses back to the point and the shell appears
 */
@Composable
fun BootAnim(onDone: () -> Unit) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        t.animateTo(1f, tween(2600, easing = LinearEasing))
        onDone()
    }
    val measurer = rememberTextMeasurer()
    Box(Modifier.fillMaxSize().background(V.Bg)) {
        Canvas(Modifier.fillMaxSize()) {
            val p = t.value
            fun seg(a: Float, b: Float) = ((p - a) / (b - a)).coerceIn(0f, 1f)
            val ease = { x: Float -> 1f - (1f - x) * (1f - x) * (1f - x) }
            val c = Offset(size.width / 2, size.height / 2 - 20f)
            val out = seg(0.88f, 1f)
            val shrink = 1f - ease(out)
            val R = size.height * 0.30f * shrink

            // point
            val dot = ease(seg(0f, 0.18f))
            drawCircle(V.White, 6f * dot + 2f * (1 - shrink), c)

            // octagon opening
            val o = ease(seg(0.12f, 0.42f))
            if (o > 0f && shrink > 0f) {
                drawOct(c, R * o, V.Line, width = 3f)
                drawOct(c, R * o * 0.78f, V.Teal.copy(alpha = 0.6f * o * shrink), width = 2f)
            }

            // lap ring: 60 ticks, coloured in six bands
            val ring = seg(0.30f, 0.72f)
            val ticks = (60 * ring).toInt()
            for (i in 0 until ticks) {
                val a = (i / 60f) * 2 * PI - PI / 2
                val col = V.Lap[(i / 10) % 6]
                val r1 = R * 1.12f; val r2 = R * (if (i % 5 == 0) 1.26f else 1.20f)
                drawLine(
                    col.copy(alpha = shrink),
                    Offset(c.x + cos(a).toFloat() * r1, c.y + sin(a).toFloat() * r1),
                    Offset(c.x + cos(a).toFloat() * r2, c.y + sin(a).toFloat() * r2),
                    strokeWidth = if (i % 5 == 0) 5f else 3f, cap = StrokeCap.Round,
                )
            }
            // sweeping seconds hand while the ring fills
            if (ring in 0.001f..0.999f) {
                val a = ring * 2 * PI - PI / 2
                drawLine(V.Cyan, c, Offset(c.x + cos(a).toFloat() * R * 0.95f, c.y + sin(a).toFloat() * R * 0.95f), 3f, StrokeCap.Round)
            }
            // gap ring (the "void")
            val g = seg(0.42f, 0.70f)
            if (g > 0f) drawArc(
                V.Coral.copy(alpha = shrink), -60f, 300f * ease(g), false,
                Offset(c.x - R * 0.32f, c.y - R * 0.32f), Size(R * 0.64f, R * 0.64f), style = Stroke(8f, cap = StrokeCap.Round),
            )

            // wordmark
            val word = "VOIDOS"
            val n = (word.length * seg(0.55f, 0.82f)).toInt()
            if (n > 0 && shrink > 0.05f) {
                val layout = measurer.measure(word.substring(0, n), TextStyle(fontFamily = Kenney, fontSize = 44.sp, color = V.White.copy(alpha = shrink), letterSpacing = 10.sp))
                val full = measurer.measure(word, TextStyle(fontFamily = Kenney, fontSize = 44.sp, letterSpacing = 10.sp))
                drawText(layout, topLeft = Offset(c.x - full.size.width / 2f, c.y + R * 1.38f))
                if (n == word.length) {
                    val sub = measurer.measure("TAB EDITION  /  ${BuildConfig.VERSION_NAME}", TextStyle(fontFamily = Kenney, fontSize = 13.sp, color = V.Muted.copy(alpha = shrink), letterSpacing = 3.sp))
                    drawText(sub, topLeft = Offset(c.x - sub.size.width / 2f, c.y + R * 1.38f + full.size.height + 8f))
                }
            }
        }
    }
}
