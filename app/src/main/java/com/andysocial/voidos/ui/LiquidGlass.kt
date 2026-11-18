package com.andysocial.voidos.ui

import androidx.compose.ui.graphics.addOutline
import android.graphics.BlurMaskFilter
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Liquid glass. Android 10 has no backdrop blur, so the glass is drawn from
 * what we know is behind it: the aurora. Every panel paints its own copy of the
 * aurora, magnified around the panel's centre and brighter (the lens), then a
 * thin smoked body, a soft inner glow that soaks up the colour of the light
 * around it (the thickness), glints of light where the rounded corners bend it
 * (specular), a curved gloss across the top, and a pool of coloured light along
 * the bottom where thick glass gathers it. No outlines anywhere: the edge of a
 * panel is only light.
 */

/** The light behind the glass. One for the screens; the overlay over other apps has its own. */
class Room {
    /** Aurora time, moved on a few times a second. Glass reads it while drawing. */
    val clock = mutableLongStateOf(System.currentTimeMillis())

    /** A fixed mood (the lock screen), else glass follows the pager. */
    val mood = mutableStateOf<Mood?>(null)

    /** How strong the aurora is (the lock screen runs it brighter). */
    val intensity = mutableFloatStateOf(1f)

    /** Over another app there is no aurora behind: the glass carries its own light instead. */
    var detached = false

    fun moodNow(): Mood = mood.value ?: Ambience.moodAt(Ambience.pos.floatValue)
}

object Rooms {
    val main = Room()
    val lock = Room()
    val overlay = Room().apply { detached = true }
}

val LocalRoom = staticCompositionLocalOf { Rooms.main }

/**
 * The aurora itself, in a room of [w] x [h]: three huge soft lights on slow loops.
 * Shared by the backdrop and by every piece of glass, so what the glass shows is
 * exactly the light behind it. With [base] it also paints the dark room and the
 * vignette; glass leaves those out and lets the real backdrop show through.
 */
fun DrawScope.paintAurora(w: Float, h: Float, m: Mood, t: Long, pagerPos: Float, intensity: Float, base: Boolean) {
    if (w <= 0f || h <= 0f) return
    val k = (phase(t, 60_000L) * 2 * PI).toFloat()
    val r = max(w, h)
    if (base) drawRect(Brush.verticalGradient(listOf(Color(0xFF0F0E17), V.Bg, Color(0xFF08080B)), startY = 0f, endY = h), size = Size(w, h))
    val par = -(pagerPos % 7f) * w * 0.05f
    fun blob(c: Color, cx: Float, cy: Float, rad: Float, a: Float) {
        val ctr = Offset(cx, cy)
        drawCircle(
            Brush.radialGradient(
                0f to c.copy(alpha = (a * intensity).coerceAtMost(1f)),
                0.42f to c.copy(alpha = (a * 0.42f * intensity).coerceAtMost(1f)),
                1f to Color.Transparent,
                center = ctr, radius = rad,
            ),
            rad, ctr,
        )
    }
    blob(m.a, w * (0.16f + 0.07f * sin(k)) + par, h * (0.04f + 0.06f * cos(k)), r * 0.64f, 0.22f)
    blob(m.b, w * (0.90f + 0.05f * cos(2 * k)) + par * 1.6f, h * (0.94f + 0.05f * sin(2 * k)), r * 0.60f, 0.19f)
    blob(m.c, w * (0.60f + 0.14f * sin(3 * k + 1f)) + par * 0.6f, h * (0.42f + 0.10f * cos(2 * k + 2f)), r * 0.36f, 0.075f)
    if (base) drawRect(
        Brush.radialGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.42f), center = Offset(w / 2, h / 2), radius = r * 0.8f),
        size = Size(w, h),
    )
}

/** How a piece of glass is cut. */
class GlassLook(
    /** How smoked the body is: 0 clear, 1 dark. */
    val smoke: Float = 0.8f,
    /** How far it floats off the page: the shadow under it. */
    val depth: Float = 1f,
    /** How strongly the corners and the gloss catch the light. */
    val shine: Float = 1f,
    /** How much the glass magnifies the light behind it. */
    val magnify: Float = 1.12f,
    /** A colour the glass is stained with (a chosen segment, a lit switch), if any. */
    val stain: Color? = null,
    val stainAlpha: Float = 0.3f,
    /** How much the edges show (glints and the light soaking in): 0 for rimless glass. */
    val edge: Float = 1f,
)

/**
 * A panel of liquid glass in [shape]. Content inside is clipped to it. This is
 * what [glass] and [pane] are made of.
 */
fun Modifier.liquidGlass(shape: Shape, look: GlassLook = GlassLook()): Modifier = composed {
    val room = LocalRoom.current
    val at = remember { mutableStateOf(Offset.Zero) }
    val roomSize = remember { mutableStateOf(Size.Zero) }
    this
        .onGloballyPositioned { c ->
            val p = c.positionInRoot()
            if (p != at.value) at.value = p
            var root = c
            while (true) root = root.parentLayoutCoordinates ?: break
            val s = Size(root.size.width.toFloat(), root.size.height.toFloat())
            if (s != roomSize.value) roomSize.value = s
        }
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = Path().apply { addOutline(outline) }
            val dp = density
            val shadow = if (look.depth > 0f) android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.Black.copy(alpha = (0.34f * look.depth).coerceAtMost(0.6f)).toArgb()
                maskFilter = BlurMaskFilter(16f * dp * look.depth.coerceIn(0.4f, 1.4f), BlurMaskFilter.Blur.NORMAL)
            } else null
            val rr: RoundRect? = (outline as? Outline.Rounded)?.roundRect
            val minSide = min(size.width, size.height)
            val glintLen = min(minSide * 0.9f, 84f * dp).coerceAtLeast(1f)
            val w = size.width; val h = size.height
            // glints where the corners bend the light: bright top left, a softer echo bottom right
            val glintTL = Brush.linearGradient(
                0f to Color.White.copy(alpha = (0.70f * look.shine).coerceAtMost(1f)),
                0.22f to Color.White.copy(alpha = (0.30f * look.shine).coerceAtMost(1f)),
                0.40f to Color.Transparent,
                start = Offset(0f, 0f), end = Offset(glintLen, glintLen),
            )
            val glintBR = Brush.linearGradient(
                0f to Color.White.copy(alpha = (0.26f * look.shine).coerceAtMost(1f)),
                0.4f to Color.Transparent,
                start = Offset(w, h), end = Offset(w - glintLen, h - glintLen),
            )
            val haloTL = Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.10f * look.shine),
                0.7f to Color.Transparent,
                start = Offset(0f, 0f), end = Offset(glintLen * 1.3f, glintLen * 1.3f),
            )
            val smoke = look.smoke.coerceIn(0f, 1f)
            val body = Brush.verticalGradient(
                listOf(Color(0xFF2A2932).copy(alpha = 0.50f * smoke), Color(0xFF121217).copy(alpha = 0.62f * smoke)),
            )
            val glossH = h * 0.62f
            onDrawBehind {
                if (w <= 0f || h <= 0f) return@onDrawBehind
                // the shadow it casts, a little below, soft
                if (shadow != null) drawIntoCanvas { cv ->
                    val nc = cv.nativeCanvas
                    val dy = 7f * dp * look.depth
                    nc.save(); nc.translate(0f, dy)
                    if (rr != null) {
                        val inset = 3f * dp
                        nc.drawRoundRect(inset, inset, w - inset, h - inset, rr.topLeftCornerRadius.x, rr.topLeftCornerRadius.y, shadow)
                    } else nc.drawPath(path.asAndroidPathCompat(), shadow)
                    nc.restore()
                }
                clipPath(path) {
                    val m = room.moodNow()
                    val tint = m.a
                    // 1. the lens: the light behind, magnified and brighter
                    val rs = roomSize.value
                    if (rs.width > 0f && !room.detached) {
                        val o = at.value
                        val cx = w / 2; val cy = h / 2
                        val mag = look.magnify
                        withTransform({
                            translate(cx, cy)
                            scale(mag, mag, Offset.Zero)
                            translate(-o.x - cx, -o.y - cy)
                        }) {
                            paintAurora(rs.width, rs.height, m, room.clock.longValue, Ambience.pos.floatValue, room.intensity.floatValue * 1.65f, base = false)
                        }
                    } else {
                        // nothing of ours behind (over another app): the glass holds its own light
                        drawRect(Brush.radialGradient(listOf(m.a.copy(alpha = 0.30f), Color.Transparent), center = Offset(w * 0.15f, 0f), radius = max(w, h) * 0.9f))
                        drawRect(Brush.radialGradient(listOf(m.b.copy(alpha = 0.24f), Color.Transparent), center = Offset(w * 0.9f, h), radius = max(w, h) * 0.8f))
                    }
                    // 2. the body: smoked just enough to read on
                    drawRect(body)
                    look.stain?.let { s ->
                        drawRect(Brush.verticalGradient(listOf(s.copy(alpha = look.stainAlpha), s.copy(alpha = look.stainAlpha * 0.45f))))
                    }
                    // 3. thickness: light soaking in from every edge, in the colour around it
                    val rim = lerp(tint, Color.White, 0.35f)
                    val e = look.edge
                    if (e > 0f) {
                        drawPath(path, rim.copy(alpha = 0.035f * e), style = Stroke(36f * dp))
                        drawPath(path, rim.copy(alpha = 0.04f * e), style = Stroke(18f * dp))
                        drawPath(path, rim.copy(alpha = 0.05f * e), style = Stroke(8f * dp))
                    }
                    // 4. thick glass gathers light along its bottom
                    drawRect(
                        Brush.radialGradient(listOf(tint.copy(alpha = 0.20f * look.shine), Color.Transparent), center = Offset(w * 0.55f, h * 1.08f), radius = max(w * 0.55f, h * 0.6f)),
                    )
                    // 5. the gloss: a curved sheen over the top
                    drawOval(
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.115f * look.shine), Color.White.copy(alpha = 0.02f * look.shine), Color.Transparent), startY = -h * 0.05f, endY = glossH),
                        topLeft = Offset(-w * 0.25f, -glossH * 0.62f), size = Size(w * 1.5f, glossH * 1.25f),
                    )
                    // 6. specular: the corners catch the light
                    if (e > 0f) {
                        drawPath(path, haloTL, style = Stroke(10f * dp), alpha = e)
                        drawPath(path, glintTL, style = Stroke(4.5f * dp), alpha = 0.35f * e)
                        drawPath(path, glintTL, style = Stroke(1.8f * dp), alpha = e)
                        drawPath(path, glintBR, style = Stroke(3f * dp), alpha = 0.6f * e)
                    }
                }
            }
        }
        .clip(shape)
}

private fun Path.asAndroidPathCompat(): android.graphics.Path = this.asAndroidPath()

/** Floating glass: bars, buttons, sheets. */
fun Modifier.glass(shape: Shape, alpha: Float = 0.86f): Modifier =
    liquidGlass(shape, GlassLook(smoke = alpha, depth = 1f, shine = 1f))

/** Glass that sits on the page: cards and lists. Floats less than [glass]. */
fun Modifier.pane(shape: Shape = Oct, lift: Float = 1f): Modifier =
    liquidGlass(shape, GlassLook(smoke = 0.78f * lift, depth = 0.55f, shine = 0.9f))

/** A drop of stained glass: the chosen segment, a lit switch. Brighter and more magnifying. */
fun Modifier.droplet(shape: Shape, stain: Color, strength: Float = 1f): Modifier =
    liquidGlass(shape, GlassLook(smoke = 0.25f, depth = 0.35f, shine = 1.25f, magnify = 1.35f, stain = stain, stainAlpha = 0.34f * strength))

/**
 * The glass look without the lens, for things drawn in a Canvas: the smoked body,
 * the inner glow, the gloss and the bottom light. Kept for callers that paint
 * glass by hand.
 */
fun DrawScope.glassFill(alpha: Float, lift: Float) {
    if (size.width <= 0f || size.height <= 0f) return
    drawRect(Brush.verticalGradient(listOf(Color(0xFF2A2932).copy(alpha = 0.55f * alpha * lift), Color(0xFF121217).copy(alpha = 0.66f * alpha * lift))))
    val tint = Ambience.tint()
    drawRect(Brush.radialGradient(listOf(tint.copy(alpha = 0.18f * lift), Color.Transparent), center = Offset(size.width * 0.55f, size.height * 1.08f), radius = max(size.width * 0.55f, size.height * 0.6f)))
    val gh = size.height * 0.62f
    drawOval(
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.11f * lift), Color.Transparent), startY = 0f, endY = gh),
        topLeft = Offset(-size.width * 0.25f, -gh * 0.62f), size = Size(size.width * 1.5f, gh * 1.25f),
    )
}
