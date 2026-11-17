package com.andysocial.voidos.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The VoidOS mark.
 *
 * An octagon frame built from seven thick sides in the six lap-ring colours
 * (coral, purple, teal, green, yellow, orange, back to coral). The eighth side,
 * top right, is left open: that gap is the "void". Inside sits a dark octagon
 * with a small white core, the same white dot as the watch hands' hub.
 *
 * The same geometry is drawn by tools/bootanim.py for the boot animation and
 * by the launcher icon, so keep them in step.
 */
object Mark {
    /** Sides are drawn in this order, clockwise from the right-hand vertical. */
    val order = listOf(7, 0, 1, 2, 3, 4, 5)
    val colours get() = listOf(V.Coral, V.Purple, V.Teal, V.Green, V.Yellow, V.Orange, V.Coral)
    const val THICK = 0.17f   // ring thickness as a fraction of the radius
    const val INNER = 0.60f   // dark inner octagon
    const val CORE = 0.17f    // white core

    fun vertex(c: Offset, r: Float, i: Int): Offset {
        val a = Math.toRadians(22.5 + 45.0 * (i % 8))
        return Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
    }
}

private fun lerp(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

/**
 * Draws the mark. [progress] 0..1 builds the ring side by side, [core] 0..1
 * grows the white core, [glow] 0..1 adds a soft halo behind the core.
 */
fun DrawScope.drawVoidMark(c: Offset, r: Float, progress: Float = 1f, core: Float = 1f, glow: Float = 0f, mono: Color? = null) {
    val ri = r * (1f - Mark.THICK)
    val n = Mark.order.size
    Mark.order.forEachIndexed { k, side ->
        val t = (progress * n - k).coerceIn(0f, 1f)
        if (t <= 0f) return@forEachIndexed
        val o0 = Mark.vertex(c, r, side); val o1 = Mark.vertex(c, r, side + 1)
        val i0 = Mark.vertex(c, ri, side); val i1 = Mark.vertex(c, ri, side + 1)
        val p = Path().apply {
            moveTo(o0.x, o0.y)
            val oe = lerp(o0, o1, t); lineTo(oe.x, oe.y)
            val ie = lerp(i0, i1, t); lineTo(ie.x, ie.y)
            lineTo(i0.x, i0.y); close()
        }
        drawPath(p, mono ?: Mark.colours[k])
    }
    // inner dark octagon
    drawPath(octPath(c, r * Mark.INNER), V.Panel)
    drawPath(octPath(c, r * Mark.INNER), V.Line, style = androidx.compose.ui.graphics.drawscope.Stroke(r * 0.025f))
    if (glow > 0f) {
        for (i in 4 downTo 1) drawCircle(V.Teal.copy(alpha = 0.06f * glow), r * Mark.CORE * (1f + i * 0.55f), c)
    }
    if (core > 0f) drawPath(octPath(c, r * Mark.CORE * core), mono ?: V.White)
}

/** The mark on its own, sized by the modifier. */
@Composable
fun VoidMark(modifier: Modifier, progress: Float = 1f, core: Float = 1f, glow: Float = 0f, mono: Color? = null) {
    Canvas(modifier) { drawVoidMark(Offset(size.width / 2, size.height / 2), minOf(size.width, size.height) / 2, progress, core, glow, mono) }
}

/** Mark above the VOIDOS wordmark, for welcome screens. */
@Composable
fun VoidLockup(markSize: Int = 120, wordSize: TextUnit = 40.sp, sub: String? = "TAB EDITION", progress: Float = 1f, core: Float = 1f) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        VoidMark(Modifier.size(markSize.dp), progress, core)
        Gap(h = (markSize / 6).dp)
        VText("VOIDOS", size = wordSize, color = V.White, spacing = (wordSize.value / 4).sp)
        if (sub != null) {
            Gap(h = 4.dp)
            VText(sub, size = (wordSize.value * 0.3f).sp, color = V.Teal, spacing = (wordSize.value / 8).sp)
        }
    }
}

