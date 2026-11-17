package com.andysocial.voidos.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The VoidOS logo: "void" written as a square root. The root sign is the V,
 * "oid" sits under its bar, the bar is broken into the six lap colours with
 * the i's dot as the teal pill, and the bar drops into the d's stem.
 *
 * Two forms:
 *  - the wordmark, the whole word, for the start animation and welcome screens
 *  - the mark, the root sign and the o under its line, for small square
 *    places: the rail, the lock screen, the launcher icon
 *
 * The shapes come from [LogoData], which tools/logo_build.py generates from
 * the approved drawing. tools/voidmark.py draws the same shapes for the
 * Android boot animation and the power-on logo, so they all match.
 */
object Mark {
    private class Piece(val colour: Int, val inMark: Boolean, val isDot: Boolean, val path: Path)

    private val pieces: List<Piece> by lazy {
        LogoData.shapes.map { (c, flags, d) ->
            val p = PathParser().parsePathString(d).toPath().apply { fillType = PathFillType.EvenOdd }
            Piece(c, flags and 1 != 0, flags and 2 != 0, p)
        }
    }

    const val WORD_ASPECT = LogoData.WORD_W / LogoData.WORD_H

    fun colour(i: Int): Color = if (i < 0) V.White else V.Lap[i]

    /**
     * Draws the pieces with drawing unit (0,0) at [o], [s] px per unit.
     * [revealX] wipes in from the left (drawing units), [dot] scales the i's dot.
     */
    internal fun DrawScope.drawPieces(o: Offset, s: Float, onlyMark: Boolean, revealX: Float, dot: Float, alpha: Float, mono: Color?, dotDx: Float = 0f) {
        if (revealX <= 0f) return
        translate(o.x, o.y) {
            clipRect(left = -1f, top = -size.height, right = revealX * s, bottom = size.height * 2) {
                scale(s, s, pivot = Offset.Zero) {
                    for (p in pieces) {
                        if (onlyMark && !p.inMark) continue
                        val col = (mono ?: colour(p.colour)).let { it.copy(alpha = it.alpha * alpha) }
                        if (p.isDot) {
                            if (dot <= 0f) continue
                            translate(dotDx, 0f) {
                                scale(dot, dot, pivot = Offset(LogoData.DOT_CX, LogoData.DOT_CY)) { drawPath(p.path, col) }
                            }
                        } else {
                            drawPath(p.path, col)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The wordmark centred on [c], [width] px wide. [reveal] 0..1 wipes it in
 * from the left, [dot] scales the i's dot, [glow] 0..1 haloes the dot, and
 * [shuttle] -1..1 slides the dot into the purple bar (-1) or the green bar (1),
 * as in the loading animation.
 */
fun DrawScope.drawVoidWord(c: Offset, width: Float, reveal: Float = 1f, dot: Float = 1f, glow: Float = 0f, alpha: Float = 1f, mono: Color? = null, shuttle: Float = 0f) {
    val s = width / LogoData.WORD_W
    val o = Offset(c.x - width / 2, c.y - LogoData.WORD_H * s / 2)
    val dotDx = if (shuttle < 0f) shuttle * LogoData.DOT_REACH_L else shuttle * LogoData.DOT_REACH_R
    if (glow > 0f && dot > 0f) {
        val dc = Offset(o.x + (LogoData.DOT_CX + dotDx) * s, o.y + LogoData.DOT_CY * s)
        for (i in 4 downTo 1) drawCircle(V.Teal.copy(alpha = 0.06f * glow * alpha), s * 11f * (0.6f + i * 0.45f) * dot, dc)
    }
    with(Mark) { drawPieces(o, s, false, LogoData.WORD_W * reveal.coerceIn(0f, 1f), dot, alpha, mono, dotDx) }
}

/**
 * The mark (the root sign and the o) fitted in a 2[r] square centred on [c].
 * [progress] 0..1 wipes it in from the left.
 */
fun DrawScope.drawVoidMark(c: Offset, r: Float, progress: Float = 1f, alpha: Float = 1f, mono: Color? = null) {
    val mw = LogoData.MARK_X1 - LogoData.MARK_X0
    val mh = LogoData.MARK_Y1 - LogoData.MARK_Y0
    val s = 2 * r * 0.96f / maxOf(mw, mh)
    val o = Offset(c.x - (LogoData.MARK_X0 + mw / 2) * s, c.y - (LogoData.MARK_Y0 + mh / 2) * s)
    with(Mark) { drawPieces(o, s, true, LogoData.MARK_X0 + mw * progress.coerceIn(0f, 1f), 1f, alpha, mono) }
}

/** The mark on its own, sized by the modifier. */
@Composable
fun VoidMark(modifier: Modifier, progress: Float = 1f, mono: Color? = null) {
    Canvas(modifier) { drawVoidMark(Offset(size.width / 2, size.height / 2), minOf(size.width, size.height) / 2, progress, 1f, mono) }
}

/** The wordmark on its own, [width] wide. */
@Composable
fun VoidWord(width: Int, reveal: Float = 1f, dot: Float = 1f) {
    Canvas(Modifier.width(width.dp).aspectRatio(Mark.WORD_ASPECT)) {
        drawVoidWord(Offset(size.width / 2, size.height / 2), size.width, reveal, dot)
    }
}

/** The wordmark over TAB EDITION, for welcome screens. */
@Composable
fun VoidLockup(wordWidth: Int = 260, sub: String? = "TAB EDITION", subSize: TextUnit = 12.sp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        VoidWord(wordWidth)
        if (sub != null) {
            Gap(h = (wordWidth / 12).dp)
            VText(sub, size = subSize, color = V.Teal, spacing = (subSize.value / 2).sp)
        }
    }
}
