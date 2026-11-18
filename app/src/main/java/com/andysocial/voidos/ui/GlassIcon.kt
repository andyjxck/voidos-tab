package com.andysocial.voidos.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as ACanvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Glass app icons (1.14). Every app becomes the same thing: a round drop of
 * tinted glass, like the wheel drops and the control centre. The app keeps its
 * identity: the drop is tinted with the app's own colour (the background layer
 * of a modern icon, or the strongest colour of an old one) and the app's own
 * symbol sits inside it at full colour. On top: one soft highlight and a glow
 * at the bottom edge. No rim, no square, no border.
 */
object GlassIcons {
    class Art(val symbol: ImageBitmap, val tint: Color, val adaptive: Boolean)

    private val cache = object : android.util.LruCache<String, Art>(160) {}

    suspend fun get(ctx: Context, pkg: String): Art? {
        cache.get(pkg)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { build(ctx.packageManager.getApplicationIcon(pkg)) }.getOrNull()?.also { cache.put(pkg, it) }
        }
    }

    fun build(d: Drawable): Art {
        if (d is AdaptiveIconDrawable && d.foreground != null) {
            // the foreground is 108 units with the visible part in the middle 72: keep that middle
            val full = 162
            val fg = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888)
            ACanvas(fg).also { c -> d.foreground.setBounds(0, 0, full, full); d.foreground.draw(c) }
            val inset = full / 6
            val sym = Bitmap.createBitmap(fg, inset, inset, full - 2 * inset, full - 2 * inset)
            val bgCol = d.background?.let { bg ->
                val b = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
                ACanvas(b).also { c -> bg.setBounds(0, 0, 48, 48); bg.draw(c) }
                averageColour(b)
            }
            val tint = chooseTint(bgCol, dominantColour(sym))
            return Art(sym.asImageBitmap(), tint, true)
        }
        val raw = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        ACanvas(raw).also { c -> d.setBounds(0, 0, 128, 128); d.draw(c) }
        val even = evenIcon(raw)
        return Art(even.asImageBitmap(), chooseTint(null, dominantColour(even)), false)
    }

    /** Pure: the tint for the drop. A white or black background says nothing, so the symbol's colour wins. */
    fun chooseTint(bg: Int?, fg: Int?): Color {
        fun usable(c: Int?) = c != null && saturation(c) > 0.18f && lightness(c) in 0.12f..0.9f
        val pick = when {
            usable(bg) -> bg!!
            usable(fg) -> fg!!
            else -> 0xFF7A7F8A.toInt()
        }
        return Color(pick)
    }

    /** Pure: the mean colour of the opaque pixels. */
    fun averageColour(b: Bitmap): Int? {
        val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        var r = 0L; var g = 0L; var bl = 0L; var n = 0L
        for (p in px) if ((p ushr 24) > 200) { r += (p shr 16) and 255; g += (p shr 8) and 255; bl += p and 255; n++ }
        if (n == 0L) return null
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (bl / n).toInt()
    }

    /** Pure: the strongest colour in a picture: saturated pixels weigh most, grey ones not at all. */
    fun dominantColour(b: Bitmap): Int? {
        val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        var r = 0.0; var g = 0.0; var bl = 0.0; var w = 0.0
        for (p in px) {
            if ((p ushr 24) < 160) continue
            val s = saturation(p)
            if (s < 0.2f) continue
            val k = s * s
            r += ((p shr 16) and 255) * k; g += ((p shr 8) and 255) * k; bl += (p and 255) * k; w += k
        }
        if (w == 0.0) return null
        return (0xFF shl 24) or ((r / w).roundToInt() shl 16) or ((g / w).roundToInt() shl 8) or (bl / w).roundToInt()
    }

    private fun saturation(c: Int): Float {
        val r = ((c shr 16) and 255) / 255f; val g = ((c shr 8) and 255) / 255f; val b = (c and 255) / 255f
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
        return if (mx == 0f) 0f else (mx - mn) / mx
    }

    private fun lightness(c: Int): Float {
        val r = ((c shr 16) and 255) / 255f; val g = ((c shr 8) and 255) / 255f; val b = (c and 255) / 255f
        return (maxOf(r, g, b) + minOf(r, g, b)) / 2f
    }
}

/** One app as a glass drop. */
@Composable
fun GlassAppIcon(pkg: String, size: Dp) {
    val ctx = LocalContext.current
    var art by remember(pkg) { mutableStateOf<GlassIcons.Art?>(null) }
    LaunchedEffect(pkg) { art = GlassIcons.get(ctx, pkg) }
    GlassDrop(art?.tint ?: V.Panel2, Modifier.size(size)) { s ->
        art?.let { a ->
            val k = if (a.adaptive) 0.86f else 0.6f
            val side = (s * k).roundToInt()
            val off = ((s - side) / 2f).roundToInt()
            drawImage(a.symbol, dstOffset = IntOffset(off, off), dstSize = IntSize(side, side))
        }
    }
}

/**
 * The drop itself, also used for VoidOS's own tiles: a round body of tinted
 * glass with depth (lighter at the top, deeper at the edge), the content, then
 * the highlight and the bottom glow drawn over it.
 */
@Composable
fun GlassDrop(tint: Color, modifier: Modifier, content: androidx.compose.ui.graphics.drawscope.DrawScope.(Float) -> Unit) {
    Canvas(modifier) {
        val s = size.minDimension
        val c = Offset(size.width / 2, size.height / 2)
        val r = s / 2
        // a soft shadow under the drop so it sits above the background
        drawCircle(
            Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.38f), Color.Transparent), center = c + Offset(0f, r * 0.16f), radius = r * 1.12f),
            radius = r * 1.12f, center = c + Offset(0f, r * 0.16f),
        )
        // the body: the app's colour, see-through, deeper at the edge
        drawCircle(
            Brush.radialGradient(
                0f to lerpC(tint, Color.White, 0.18f).copy(alpha = 0.78f),
                0.65f to tint.copy(alpha = 0.62f),
                1f to lerpC(tint, Color.Black, 0.55f).copy(alpha = 0.82f),
                center = c - Offset(0f, r * 0.35f), radius = r * 1.45f,
            ),
            radius = r, center = c,
        )
        val clip = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
        clipPath(clip) {
            translate(c.x - s / 2, c.y - s / 2) { content(s) }
            // the highlight: a soft lens of light across the top
            drawOval(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.46f), Color.White.copy(alpha = 0f)), startY = c.y - r * 0.94f, endY = c.y - r * 0.05f),
                topLeft = Offset(c.x - r * 0.68f, c.y - r * 0.94f), size = Size(r * 1.36f, r * 0.86f),
            )
            // light caught at the bottom edge, as in a real drop
            drawOval(
                Brush.radialGradient(listOf(lerpC(tint, Color.White, 0.6f).copy(alpha = 0.4f), Color.Transparent), center = Offset(c.x, c.y + r * 0.95f), radius = r * 0.7f),
                topLeft = Offset(c.x - r * 0.7f, c.y + r * 0.45f), size = Size(r * 1.4f, r * 0.8f),
            )
        }
    }
}

private fun lerpC(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha + (b.alpha - a.alpha) * t,
)
