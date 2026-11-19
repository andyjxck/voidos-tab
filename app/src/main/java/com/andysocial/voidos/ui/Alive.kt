package com.andysocial.voidos.ui

import androidx.compose.ui.graphics.CompositingStrategy

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.isActive
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/*
 * What makes VoidOS feel alive: a slow aurora behind every screen that takes
 * its colours from the screen you're on and drifts as you swipe, glass that
 * picks up that light, page turns with depth, and a clock whose digits roll.
 * Everything here is drawn, not blurred: Android 10 can't blur behind a view.
 */

/** A screen's light: three colours the backdrop glows with. */
data class Mood(val a: Color, val b: Color, val c: Color)

object Ambience {
    /** Where the pager is: the page index plus the swipe fraction. The backdrop follows it. */
    val pos = mutableFloatStateOf(0f)

    /** Off: the backdrop holds still (Settings > Home & lock screen). */
    val motion = mutableStateOf(true)

    /**
     * True while an edge wheel is open. The backdrop and the glass hold still meanwhile, so the
     * page behind can sit in one cached layer and the GPU only has the wheel to draw.
     */
    val held = mutableStateOf(false)

    /** Whether the moving parts should take their next step: motion on, and no wheel open. */
    val moving: Boolean get() = motion.value && !held.value

    /** One mood per screen, in the pager's order: Home, Tools, Camera, Utilities, Weather, Notes, Music, Apps, Settings. */
    val moods = listOf(
        Mood(V.Teal, V.Purple, V.Coral),
        Mood(V.Coral, V.Purple, V.Orange),
        Mood(V.Orange, V.Coral, V.Yellow),
        Mood(V.Green, V.Teal, Color(0xFF5AA9F0)),
        Mood(Color(0xFF5AA9F0), V.Teal, V.Purple),
        Mood(V.Yellow, V.Coral, V.Purple),
        Mood(V.Purple, V.Coral, V.Teal),
        Mood(V.Teal, V.Green, V.Purple),
        Mood(V.Purple, Color(0xFF6E86F2), V.Teal),
    )
    val LOCK = Mood(V.Purple, V.Teal, V.Coral)

    fun moodAt(p: Float): Mood {
        val i = floor(p).toInt().coerceIn(0, moods.lastIndex)
        val j = (i + 1).coerceAtMost(moods.lastIndex)
        val f = (p - i).coerceIn(0f, 1f)
        val m = moods[i]; val n = moods[j]
        return Mood(lerp(m.a, n.a, f), lerp(m.b, n.b, f), lerp(m.c, n.c, f))
    }

    /** The current screen's lead colour. Read it inside a draw block so a change only redraws. */
    fun tint(): Color = moodAt(pos.floatValue).a
}

/** A clock for drawing: ticks about 30 times a second while it's on screen. Read it in draw blocks. */
@Composable
fun rememberTicker(periodMs: Long = 33L): State<Long> {
    val s = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (isActive) {
            delay(periodMs)
            if (Ambience.moving) s.longValue = System.currentTimeMillis()
        }
    }
    return s
}

/** 0..1 through a loop of [ms] milliseconds. */
fun phase(t: Long, ms: Long): Float = (t % ms).toFloat() / ms

/**
 * The room behind the screens: near-black with three huge soft lights that
 * drift on slow loops. With no [mood] it follows the pager, so swiping from
 * Home to Tools slides the light from teal to coral.
 */
@Composable
fun Aurora(modifier: Modifier = Modifier, mood: Mood? = null, intensity: Float = 1f) {
    val tick = rememberTicker(40L)
    val room = LocalRoom.current
    // the glass reads the same light; it moves on a little less often, which the eye can't tell
    LaunchedEffect(room, mood, intensity) {
        room.mood.value = mood
        room.intensity.floatValue = intensity
        while (isActive) {
            delay(120L)
            if (Ambience.moving) room.clock.longValue = System.currentTimeMillis()
        }
    }
    Canvas(modifier.fillMaxSize()) {
        val p = if (mood == null) Ambience.pos.floatValue else 0f
        paintAurora(size.width, size.height, mood ?: Ambience.moodAt(p), tick.value, p, intensity, base = true)
    }
}

/**
 * The logo's six colours as one short hairline with a glint of light that
 * runs along it every few seconds. The VoidOS signature under a clock.
 */
@Composable
fun SignatureLine(modifier: Modifier, tick: State<Long>) {
    Canvas(modifier) {
        if (size.width <= 0f) return@Canvas
        val n = V.Lap.size
        val seg = size.width / n
        val cr = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(Brush.horizontalGradient(V.Lap.map { it.copy(alpha = 0.9f) }), cornerRadius = cr)
        val g = phase(tick.value, 6_000L)
        // the glint crosses in the first 40% of the loop, then rests
        if (g < 0.4f) {
            val x = -seg + (size.width + 2 * seg) * (g / 0.4f)
            drawRoundRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.85f), Color.Transparent), startX = x - seg, endX = x + seg),
                cornerRadius = cr,
            )
        }
    }
}

/**
 * Text drawn by its ink, like the old clock, but alive: when a character
 * changes it rolls up and the new one springs in from below; a tinted light
 * glides across the digits; and the colon breathes. Sized to the widest digits,
 * so the time never shuffles sideways.
 */
@Composable
fun RollingText(
    text: String,
    sizePx: Float,
    face: Typeface,
    modifier: Modifier = Modifier,
    spacingEm: Float = -0.02f,
    tick: State<Long>? = null,
    breathe: Boolean = true,
) {
    val paint = remember(face, sizePx, spacingEm) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = sizePx; letterSpacing = spacingEm }
    }
    val probe = text.map { if (it.isDigit()) '0' else it }.joinToString("")
    val b = remember(probe, paint) { Rect().also { paint.getTextBounds(probe, 0, probe.length, it) } }
    var shown by remember { mutableStateOf(text) }
    var old by remember { mutableStateOf(text) }
    val roll = remember { Animatable(1f) }
    LaunchedEffect(text) {
        if (text != shown) {
            old = shown; shown = text
            roll.snapTo(0f)
            roll.animateTo(1f, Motion.pop())
        }
    }
    val d = LocalDensity.current
    Canvas(
        modifier.size(with(d) { b.width().toFloat().toDp() }, with(d) { (b.height().toFloat()).toDp() })
            .semantics { contentDescription = text },
    ) {
        val t = tick?.value ?: 0L
        val w = size.width
        // a light, tinted by the screen, glides across every 9 seconds
        val g = phase(t, 9_000L)
        val base = Color(0xFFF6F6FA)
        val glow = lerp(Color.White, Ambience.tint(), 0.55f)
        val bx = -0.4f * w + 1.8f * w * g
        paint.shader = android.graphics.LinearGradient(
            bx - w * 0.25f, 0f, bx + w * 0.25f, size.height,
            intArrayOf(base.toArgb(), glow.toArgb(), base.toArgb()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        // start the widest-digit text so its ink begins at the left edge, like InkText
        val x0 = -b.left.toFloat()
        val y = size.height / 2 - b.exactCenterY()
        val p = roll.value
        val lift = size.height * 0.9f
        val nc = drawContext.canvas.nativeCanvas
        nc.save()
        nc.clipRect(-w * 0.05f, -size.height * 0.08f, w * 1.05f, size.height * 1.08f)
        val colonA = if (breathe) 0.55f + 0.45f * ((cos(phase(t, 2_000L) * 2 * PI) + 1) / 2).toFloat() else 1f
        fun drawAt(s: String, i: Int, dy: Float, a: Float) {
            val cx = x0 + paint.measureText(probe, 0, i)
            val isColon = s[i] == ':'
            paint.alpha = (255 * a * (if (isColon) colonA else 1f)).toInt().coerceIn(0, 255)
            nc.drawText(s, i, i + 1, cx, y + dy, paint)
        }
        if (old.length == shown.length) {
            for (i in shown.indices) {
                if (p < 1f && old[i] != shown[i]) {
                    drawAt(old, i, -lift * p, 1f - p.coerceIn(0f, 1f))
                    drawAt(shown, i, lift * (1f - p), p.coerceIn(0f, 1f))
                } else drawAt(shown, i, 0f, 1f)
            }
        } else {
            // 9:59 to 10:00 changes the width: the whole time rolls instead
            val ow = paint.measureText(old)
            paint.alpha = (255 * (1f - p).coerceIn(0f, 1f)).toInt()
            if (p < 1f) nc.drawText(old, w / 2 - ow / 2, y - lift * p, paint)
            paint.alpha = (255 * p.coerceIn(0f, 1f)).toInt()
            nc.drawText(shown, w / 2 - paint.measureText(shown) / 2, y + lift * (1f - p), paint)
        }
        nc.restore()
        paint.shader = null
        paint.alpha = 255
    }
}

/** A soft light behind something (a clock), breathing slowly in the screen's colours. */
fun Modifier.breathingGlow(tick: State<Long>, mood: Mood? = null, strength: Float = 1f): Modifier = drawBehind {
    if (size.width <= 0f || size.height <= 0f) return@drawBehind
    val m = mood ?: Ambience.moodAt(Ambience.pos.floatValue)
    val br = 0.75f + 0.25f * sin(phase(tick.value, 7_000L) * 2 * PI).toFloat()
    val r = max(size.width, size.height) * 0.75f
    val c = Offset(size.width / 2, size.height / 2)
    drawCircle(Brush.radialGradient(listOf(m.a.copy(alpha = 0.16f * br * strength), Color.Transparent), center = c - Offset(r * 0.2f, 0f), radius = r), r, c - Offset(r * 0.2f, 0f))
    drawCircle(Brush.radialGradient(listOf(m.b.copy(alpha = 0.13f * (1.75f - br) * strength), Color.Transparent), center = c + Offset(r * 0.25f, r * 0.1f), radius = r * 0.85f), r * 0.85f, c + Offset(r * 0.25f, r * 0.1f))
}

/**
 * Depth for a page in the pager: the page you're leaving sinks back (smaller,
 * dimmer, a little slower) while the next one rises to meet you.
 */
fun Modifier.pageDepth(pager: PagerState, index: Int): Modifier = graphicsLayer {
    val off = (pager.currentPage - index) + pager.currentPageOffsetFraction
    val a = abs(off).coerceIn(0f, 1f)
    alpha = 1f - a * 0.65f
    val s = 1f - a * 0.07f
    scaleX = s; scaleY = s
    translationX = off * size.width * 0.28f
}

/**
 * Things you can press give way under your finger and spring back, like the
 * iPad. Reads presses without taking them, so clicks and long presses still work.
 */
fun Modifier.squish(scaleTo: Float = 0.92f): Modifier = composed {
    val press = remember { Animatable(1f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    this
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                scope.launch { press.animateTo(scaleTo, Motion.press()) }
                waitForUpOrCancellation()
                scope.launch { press.animateTo(1f, Motion.release()) }
            }
        }
        .graphicsLayer { scaleX = press.value; scaleY = press.value }
}

/** "Good morning" and friends, for the lock screen and the control centre. */
fun greeting(now: Long): String {
    val h = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
    return when (h) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        in 18..22 -> "Good evening"
        else -> "Late night"
    }
}

/** Glass tile behind a Settings icon: the colour of its page, lit from the top. */
fun tileBrush(accent: Color): Brush = Brush.verticalGradient(listOf(lerp(accent, Color.White, 0.22f), lerp(accent, Color.Black, 0.18f)))

@Suppress("unused")
private val keep = Size.Zero

/**
 * While an edge wheel is open ([Ambience.held]) this part of the screen is drawn once into an
 * offscreen texture and reused for every frame, instead of replaying all its glass each frame.
 */
fun Modifier.stillWhileHeld(): Modifier = this.graphicsLayer {
    compositingStrategy = if (Ambience.held.value) CompositingStrategy.Offscreen else CompositingStrategy.Auto
}
