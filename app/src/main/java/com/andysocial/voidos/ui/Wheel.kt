package com.andysocial.voidos.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One slot on a wheel. */
class WheelItem(val key: String, val label: String, val icon: @Composable (Modifier, Color) -> Unit)

enum class Edge { LEFT, RIGHT }

/** The wheel's shape, in dp. Its centre sits [inset] past the screen edge, so you see a C-shaped band. */
internal object Geo {
    const val inset = 72f
    const val rIn = 134f
    const val rItem = 188f
    const val rOut = 244f
    const val zone = 22f
    const val hub = 26f
    /** The selection drop's radius. */
    const val bead = 31f
}

/** The angle between slots, in radians, for a wheel of [n] slots. */
internal fun wheelStep(n: Int): Float = Math.toRadians(minOf(20.0, 112.0 / (n - 1).coerceAtLeast(1))).toFloat()

/** The first slot's angle; the slots sit symmetrically about the horizontal. */
internal fun wheelStart(n: Int): Float = -wheelStep(n) * (n - 1) / 2f

/** A wheel angle in radians as a canvas angle in degrees; the right wheel is the left one mirrored. */
/** Keeps a wheel angle between its first and last slot, so it can't be dragged past either end. */
internal fun wheelClamp(a: Float, first: Float, last: Float): Float = a.coerceIn(min(first, last), max(first, last))

internal fun wheelDeg(e: Edge, a: Float): Float =
    Math.toDegrees((if (e == Edge.LEFT) a else (Math.PI - a).toFloat()).toDouble()).toFloat()

/**
 * The parts of an open wheel that hold still while it's open, painted once into a picture
 * (off the main thread, before you need it) so a frame only moves cheap things about:
 * the band of glass with its shadow, the light it bends, its body, rims, gloss and pooled
 * colour; plus the two thin rims the moving glint is painted onto.
 */
internal class WheelArt(
    val key: List<Any>,
    /** The band, its shadow and everything inside it. */
    val band: ImageBitmap,
    /** Where the wheel's centre falls in [band]. */
    val cx: Float,
    val cy: Float,
    /** The inner 6dp and 1.1dp of the band's edge, around a centre at 0,0: where the glint shines. */
    val rimSoft: android.graphics.Path,
    val rimHard: android.graphics.Path,
)

/** The cache key for a wheel's picture: everything the picture depends on. */
internal fun wheelArtKey(e: Edge, n: Int, w: Int, h: Int, density: Float, m: Mood, tint: Color, detached: Boolean, intensity: Float): List<Any> =
    listOf(e, n, w, h, density, m.a.toArgb(), m.b.toArgb(), m.c.toArgb(), tint.toArgb(), detached, (intensity * 100).roundToInt())

/**
 * Paints a wheel's still parts. [clock] freezes the light behind at one moment: the wheel is
 * open a second or two, and the backdrop holds still meanwhile anyway (see [Ambience.held]).
 * Safe to call off the main thread.
 */
internal fun buildWheelArt(
    e: Edge, n: Int, w: Int, h: Int, d: Density, m: Mood, tint: Color, detached: Boolean,
    intensity: Float, clock: Long, pos: Float,
): WheelArt {
    fun px(v: Float) = v * d.density
    val step = wheelStep(n)
    val start = wheelStart(n)
    val pad = step / 2 + Math.toRadians(3.0).toFloat()
    val a0 = start - pad
    val sweep = step * (n - 1) + 2 * pad
    val rIn = px(Geo.rIn)
    val rOut = px(Geo.rOut)
    val rMid = (rIn + rOut) / 2
    val thick = rOut - rIn
    val startDeg = if (e == Edge.LEFT) wheelDeg(e, a0) else wheelDeg(e, a0 + sweep)
    val sweepDeg = Math.toDegrees(sweep.toDouble()).toFloat()

    // only the part of the band that can be on screen, plus room for its shadow
    val margin = px(40f)
    val bw = (rOut + margin - px(Geo.inset)).roundToInt().coerceAtLeast(1)
    val bh = (2 * (rOut + margin)).roundToInt().coerceAtLeast(1)
    val cx = if (e == Edge.LEFT) -px(Geo.inset) else bw + px(Geo.inset)
    val cy = bh / 2f
    // where the centre would be on screen for a wheel opened half way down; the light behind is lined up with that
    val screenC = Offset(if (e == Edge.LEFT) -px(Geo.inset) else w + px(Geo.inset), h / 2f)

    // THE 1.4.0-1.7.0 WHEEL BUG lived near here: a bare close() inside Path().apply { } called the
    // wheel's own close(), so the wheel shut itself on its first frame. Keep path calls qualified.
    val local = wheelBand(Offset.Zero, rMid, thick, startDeg, sweepDeg)
    val c = Offset(cx, cy)
    val band = wheelBand(c, rMid, thick, startDeg, sweepDeg)
    val bandA = band.asAndroidPath()
    val img = ImageBitmap(bw, bh)
    CanvasDrawScope().draw(d, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(img), Size(bw.toFloat(), bh.toFloat())) {
        // 1. depth: the band floats, casting a soft shadow out from the edge
        drawIntoCanvas { cv ->
            val sh = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.Black.copy(alpha = 0.55f).toArgb()
                maskFilter = android.graphics.BlurMaskFilter(px(22f), android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            cv.nativeCanvas.save()
            cv.nativeCanvas.translate(if (e == Edge.LEFT) px(6f) else -px(6f), px(10f))
            cv.nativeCanvas.drawPath(bandA, sh)
            cv.nativeCanvas.restore()
        }
        clipPath(band) {
            // 2. the lens: the light behind, bent and magnified through thick glass
            if (!detached) {
                withTransform({ translate(cx - screenC.x, cy - screenC.y); scale(1.32f, 1.32f, screenC) }) {
                    paintAurora(w.toFloat(), h.toFloat(), m, clock, pos, intensity * 2.1f, base = false)
                }
            } else {
                drawRect(Brush.radialGradient(listOf(m.a.copy(alpha = 0.42f), Color.Transparent), center = c + Offset(0f, -rOut), radius = rOut * 1.3f))
                drawRect(Brush.radialGradient(listOf(m.b.copy(alpha = 0.36f), Color.Transparent), center = c + Offset(0f, rOut), radius = rOut * 1.2f))
            }
            // 3. the body: smoked glass, clearer in the middle of the band, denser at its edges
            drawCircle(
                Brush.radialGradient(
                    0f to Color(0xFF15151A).copy(alpha = 0.55f),
                    (rIn / rOut) to Color(0xFF1B1B21).copy(alpha = 0.62f),
                    ((rIn + thick * 0.5f) / rOut) to Color(0xFF26252D).copy(alpha = 0.34f),
                    1f to Color(0xFF101014).copy(alpha = 0.66f),
                    center = c, radius = rOut,
                ),
                rOut, c,
            )
            // 4. thickness: light soaking in from every edge, in the colour of the room
            val rim = lerp(tint, Color.White, 0.4f)
            drawPath(band, rim.copy(alpha = 0.05f), style = Stroke(px(30f)))
            drawPath(band, rim.copy(alpha = 0.06f), style = Stroke(px(14f)))
            drawPath(band, rim.copy(alpha = 0.08f), style = Stroke(px(5f)))
            // 5. gloss: a broad sheen across the upper half of the band
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), center = c + Offset(if (e == Edge.LEFT) rMid * 0.55f else -rMid * 0.55f, -rMid * 0.8f), radius = rOut * 0.75f),
                rOut, c,
            )
            // 6. thick glass pools coloured light along its lower inner curve
            drawCircle(
                Brush.radialGradient(listOf(tint.copy(alpha = 0.30f), Color.Transparent), center = c + Offset(if (e == Edge.LEFT) rIn * 0.4f else -rIn * 0.4f, rIn * 0.95f), radius = rOut * 0.55f),
                rOut, c,
            )
        }
    }
    return WheelArt(
        wheelArtKey(e, n, w, h, d.density, m, tint, detached, intensity), img, cx, cy,
        innerRim(local, px(6f)), innerRim(local, px(1.1f)),
    )
}

/** The strip [width] wide just inside a closed shape's edge. */
private fun innerRim(shape: Path, width: Float): android.graphics.Path {
    val src = shape.asAndroidPath()
    val stroke = android.graphics.Path()
    android.graphics.Paint().apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = width * 2 }.getFillPath(src, stroke)
    val out = android.graphics.Path()
    out.op(stroke, src, android.graphics.Path.Op.INTERSECT)
    return out
}

/**
 * The moving parts of the wheel, set up once: the glint's three sweeps of light (turned to
 * the finger by rotating the shader, never rebuilt), and the selection drop and its shadow
 * as two small pictures that are only moved, turned and stretched. Nothing is allocated per frame.
 */
internal class WheelFx {
    private val m = android.graphics.Matrix()
    private fun sweep(a: Float, spread: Float): android.graphics.Paint {
        val colors = IntArray(37)
        val stops = FloatArray(37)
        for (k in 0..36) {
            val d = k * 10f
            val diff = ((d + 540f) % 360f) - 180f
            val x = (1f - abs(diff) / spread).coerceIn(0f, 1f)
            colors[k] = android.graphics.Color.argb((a * x * x * 255f).roundToInt(), 255, 255, 255)
            stops[k] = k / 36f
        }
        return android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.SweepGradient(0f, 0f, colors, stops)
        }
    }
    private val soft = sweep(0.16f, 70f)
    private val hard = sweep(0.72f, 50f)
    private val back = sweep(0.40f, 45f)
    private val sprite = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)

    /** The rims catch the light; the brightest point sits at [peak] degrees, a fainter one at [peak2]. Canvas origin at the wheel's centre. */
    fun glint(cv: android.graphics.Canvas, art: WheelArt, peak: Float, peak2: Float) {
        m.setRotate(peak); soft.shader.setLocalMatrix(m); hard.shader.setLocalMatrix(m)
        cv.drawPath(art.rimSoft, soft)
        cv.drawPath(art.rimHard, hard)
        m.setRotate(peak2); back.shader.setLocalMatrix(m)
        cv.drawPath(art.rimHard, back)
    }

    private var beadKey = 0L
    private var bead: android.graphics.Bitmap? = null
    private var shadow: android.graphics.Bitmap? = null
    private var dens = 1f
    /** Pictures are painted this much larger than they're shown, so stretching never blurs them. */
    private val over = 1.5f

    /** The drop of glass at [b], turned to [tangent] degrees and stretched by [stretch] along it. */
    fun bead(cv: android.graphics.Canvas, d: Density, tint: Color, b: Offset, tangent: Float, stretch: Float) {
        val key = (tint.toArgb().toLong() shl 8) xor (d.density * 100).toLong()
        if (bead == null || key != beadKey) { paintBead(d, tint); beadKey = key }
        val sh = shadow ?: return
        val bd = bead ?: return
        cv.drawBitmap(sh, b.x - sh.width / 2f, b.y + 6f * dens - sh.height / 2f, sprite)
        m.setTranslate(-bd.width / 2f, -bd.height / 2f)
        m.postScale((1f + stretch) / over, (1f - stretch * 0.35f) / over)
        m.postRotate(tangent)
        m.postTranslate(b.x, b.y)
        cv.drawBitmap(bd, m, sprite)
    }

    /** One slot's own drop of clear glass at [b], scaled by [scale]; [lit] brightens it under the finger. */
    fun drop(cv: android.graphics.Canvas, d: Density, b: Offset, scale: Float, lit: Float) {
        val key = (Color.White.toArgb().toLong() shl 8) xor (d.density * 100).toLong()
        if (bead == null || key != beadKey) { paintBead(d, Color.White); beadKey = key }
        val sh = shadow ?: return
        val bd = bead ?: return
        sprite.alpha = (150 + 105 * lit).toInt().coerceIn(0, 255)
        m.setTranslate(-sh.width / 2f, -sh.height / 2f); m.postScale(scale, scale); m.postTranslate(b.x, b.y + 5f * dens * scale)
        cv.drawBitmap(sh, m, sprite)
        m.setTranslate(-bd.width / 2f, -bd.height / 2f)
        m.postScale(scale / over, scale / over)
        m.postTranslate(b.x, b.y)
        cv.drawBitmap(bd, m, sprite)
        sprite.alpha = 255
    }

    private fun paintBead(d: Density, hl: Color) {
        dens = d.density
        val r = Geo.bead * dens
        // the shadow: drawn as is, so it always falls straight down
        val blur = 10f * dens
        val ss = (2 * (r * 0.95f + blur * 1.6f)).roundToInt()
        shadow = android.graphics.Bitmap.createBitmap(ss, ss, android.graphics.Bitmap.Config.ARGB_8888).also { bm ->
            android.graphics.Canvas(bm).drawCircle(ss / 2f, ss / 2f, r * 0.95f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.Black.copy(alpha = 0.45f).toArgb()
                maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
            })
        }
        val R = r * over
        val bs = (2 * (R + 2 * dens * over)).roundToInt()
        val img = ImageBitmap(bs, bs)
        val big = Density(d.density * over, d.fontScale)
        CanvasDrawScope().draw(big, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(img), Size(bs.toFloat(), bs.toFloat())) {
            val b = Offset(bs / 2f, bs / 2f)
            // stained, clear glass: the colour of the screen, brighter at the bottom where the light gathers
            drawCircle(Brush.verticalGradient(listOf(lerp(hl, Color.White, 0.25f).copy(alpha = 0.30f), hl.copy(alpha = 0.55f)), startY = b.y - R, endY = b.y + R), R, b)
            drawCircle(Brush.radialGradient(listOf(Color.Transparent, Color.Transparent, Color.White.copy(alpha = 0.22f)), center = b, radius = R), R, b)
            // the rim bends the light into a bright crescent at the top left and a softer one opposite
            drawCircle(
                Brush.sweepGradient(
                    0f to Color.White.copy(alpha = 0.08f), 0.55f to Color.White.copy(alpha = 0.10f), 0.64f to Color.White.copy(alpha = 0.95f),
                    0.75f to Color.White.copy(alpha = 0.12f), 0.92f to Color.White.copy(alpha = 0.05f), 1f to Color.White.copy(alpha = 0.08f),
                    center = b,
                ),
                R - 1.2.dp.toPx(), b, style = Stroke(2.4.dp.toPx()),
            )
            drawCircle(
                Brush.sweepGradient(0f to Color.Transparent, 0.12f to Color.White.copy(alpha = 0.45f), 0.25f to Color.Transparent, 1f to Color.Transparent, center = b),
                R - 1.2.dp.toPx(), b, style = Stroke(2.dp.toPx()),
            )
            // the gloss: a soft oval highlight over the top
            drawOval(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.0f)), startY = b.y - R * 0.9f, endY = b.y - R * 0.05f),
                topLeft = Offset(b.x - R * 0.62f, b.y - R * 0.9f), size = Size(R * 1.24f, R * 0.8f),
            )
        }
        bead = img.asAndroidBitmap()
    }
}

/**
 * What changes frame to frame while a wheel is open. It's read only in draw, layout and
 * layer blocks, so a moving finger never recomposes anything.
 */
private class WheelMotion {
    /** The selection drop's angle, in radians. */
    val bead: MutableFloatState = mutableFloatStateOf(0f)
    /** How far the drop is stretched by its speed, 0 to 0.45. */
    val stretch: MutableFloatState = mutableFloatStateOf(0f)
    /** The highlighted slot, or -1. */
    val sel: MutableIntState = mutableIntStateOf(-1)
    /** Bumped whenever the drop has somewhere new to go, to wake its physics. */
    val wake: MutableIntState = mutableIntStateOf(0)
    var vel = 0f
    var finger = 0f
    var dragging = false
}

/**
 * The two edge wheels. Put a finger on the left edge and the tab wheel opens
 * under it; slide down, up or out and a drop of glass follows your finger round
 * the band; let go and VoidOS moves to the screen under it. The right edge does
 * the same for the apps you have opened from VoidOS. A tap on an edge leaves the
 * wheel open so you can tap a slot instead.
 *
 * Built to hold 60 frames a second on a slow tablet: the still parts are pictures
 * painted before you need them, the page behind sits in one cached layer while the
 * wheel is open, and a frame only turns a shader and moves two small pictures.
 */
/** How long an app slot is held still before a swipe down can close the app. */
internal const val WHEEL_HOLD_MS = 420L
/** How far down (dp) a held slot is pulled to close its app. */
internal const val WHEEL_CLOSE_DP = 90f

/**
 * How far a held slot has been pulled towards closing, 0..1: only downward pull counts,
 * and only while it's mostly down rather than sideways (so pulling it out to carry it
 * to a half of the screen never closes anything).
 */
internal fun wheelClosePull(dy: Float, dx: Float, full: Float): Float {
    if (dy <= 0f || full <= 0f) return 0f
    if (abs(dx) > dy) return 0f
    return (dy / full).coerceIn(0f, 1f)
}

@Composable
fun EdgeWheels(
    tabs: List<WheelItem>,
    currentTab: String,
    onTab: (String) -> Unit,
    apps: List<WheelItem>,
    onApp: (String) -> Unit,
    onAllApps: () -> Unit,
    enabled: Boolean = true,
    /** True in the window over other apps: it takes its touches from the edge strips there, not from MainActivity. */
    overlay: Boolean = false,
    /** Called once the wheel has finished closing. */
    onClosed: () -> Unit = {},
    /**
     * Drag a slot out past the wheel and let go on one half of the screen: the book on Home
     * (ui/Book.kt). [app] is true for the app wheel, [first] for the left (portrait: top) half.
     * Null switches dragging out off.
     */
    onDrop: ((key: String, app: Boolean, first: Boolean) -> Unit)? = null,
    /** Whether a slot may be dragged out at all. */
    canDrop: (key: String, app: Boolean) -> Boolean = { _, _ -> true },
    /**
     * Hold an app's slot, then swipe down: the app is closed completely (AppKill). Null
     * switches it off. Only on the app wheel, and only for slots [canClose] allows.
     */
    onCloseApp: ((key: String) -> Unit)? = null,
    canClose: (key: String) -> Boolean = { true },
) {
    var side by remember { mutableStateOf<Edge?>(null) }
    val anchor = remember { mutableFloatStateOf(0f) }
    var sticky by remember { mutableStateOf(false) }
    val appear = remember { Animatable(0f) }
    val mo = remember { WheelMotion() }
    val fx = remember { WheelFx() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val view = LocalView.current
    val room = LocalRoom.current
    val px = { d: Float -> with(density) { d.dp.toPx() } }

    val cbTab by rememberUpdatedState(onTab)
    val cbApp by rememberUpdatedState(onApp)
    val cbAll by rememberUpdatedState(onAllApps)
    val cbClosed by rememberUpdatedState(onClosed)
    val tabsNow by rememberUpdatedState(tabs)
    val appsNow by rememberUpdatedState(apps)
    val cbDrop by rememberUpdatedState(onDrop)
    val cbCan by rememberUpdatedState(canDrop)
    val cbClose by rememberUpdatedState(onCloseApp)
    val cbCanClose by rememberUpdatedState(canClose)
    // an app slot held still long enough to close it with a swipe down, and how far down it's pulled (0..1)
    var closing by remember { mutableStateOf<String?>(null) }
    val closePull = remember { mutableFloatStateOf(0f) }
    // a slot being carried out of the wheel to a half of the screen, and where the finger is
    var carry by remember { mutableStateOf<WheelItem?>(null) }
    var carryApp by remember { mutableStateOf(false) }
    val carryAt = remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        // per edge, so a gesture that opens one wheel and moves in the same instant uses that wheel's slots
        fun itemsOf(e: Edge?) = if (e == Edge.RIGHT) appsNow else tabsNow
        fun nOf(e: Edge?) = itemsOf(e).size.coerceAtLeast(1)
        fun stepOf(e: Edge?) = wheelStep(nOf(e))
        fun startOf(e: Edge?) = wheelStart(nOf(e))
        fun slotAngle(i: Int, e: Edge?) = startOf(e) + i * stepOf(e)
        fun centre(e: Edge) = Offset(if (e == Edge.LEFT) -px(Geo.inset) else w + px(Geo.inset), anchor.floatValue)
        /** Where a point on the wheel at [a] radians and radius [r] lands on screen. */
        fun at(e: Edge, a: Float, r: Float): Offset {
            val c = centre(e)
            val dx = r * cos(a)
            return Offset(if (e == Edge.LEFT) c.x + dx else c.x - dx, c.y + r * sin(a))
        }
        fun angleAt(e: Edge, p: Offset): Float {
            val c = centre(e)
            return atan2(p.y - c.y, if (e == Edge.LEFT) p.x - c.x else c.x - p.x)
        }
        fun indexAt(e: Edge, p: Offset): Int =
            ((angleAt(e, p) - startOf(e)) / stepOf(e)).roundToInt().coerceIn(0, nOf(e) - 1)
        fun hubAt(e: Edge): Offset = Offset(if (e == Edge.LEFT) px(Geo.hub + 8f) else w - px(Geo.hub + 8f), anchor.floatValue)

        // ---- the pictures: painted ahead, off the main thread, so a wheel opens on its first frame
        val arts = remember { arrayOfNulls<WheelArt>(2) }
        fun moodNow(): Mood = room.mood.value ?: Ambience.moodAt(Ambience.pos.floatValue.roundToInt().toFloat())
        fun keyOf(e: Edge): List<Any> {
            val m = moodNow()
            return wheelArtKey(e, nOf(e), w.toInt(), h.toInt(), density.density, m, m.a, room.detached, room.intensity.floatValue)
        }
        fun build(e: Edge): WheelArt {
            val m = moodNow()
            return buildWheelArt(e, nOf(e), w.toInt(), h.toInt(), density, m, m.a, room.detached, room.intensity.floatValue, room.clock.longValue, Ambience.pos.floatValue.roundToInt().toFloat())
        }
        fun artFor(e: Edge): WheelArt {
            val k = keyOf(e)
            arts[e.ordinal]?.let { if (it.key == k) return it }
            return build(e).also { arts[e.ordinal] = it }
        }
        val page by remember { derivedStateOf { Ambience.pos.floatValue.roundToInt() } }
        if (enabled && w > 0f && h > 0f) {
            LaunchedEffect(w, h, tabs.size, apps.size, page, room.mood.value) {
                delay(350)
                for (e in Edge.entries) {
                    if (side != null) return@LaunchedEffect
                    val k = keyOf(e)
                    if (arts[e.ordinal]?.key == k) continue
                    val m = moodNow(); val n = nOf(e); val det = room.detached; val inten = room.intensity.floatValue
                    val clk = room.clock.longValue; val p = Ambience.pos.floatValue.roundToInt().toFloat()
                    val a = runCatching {
                        withContext(Dispatchers.Default) { buildWheelArt(e, n, w.toInt(), h.toInt(), density, m, m.a, det, inten, clk, p) }
                    }.getOrNull() ?: continue
                    if (a.key == keyOf(e)) arts[e.ordinal] = a
                }
            }
        }

        fun openAt(e: Edge, y: Float) {
            val list = itemsOf(e)
            val reach = px(Geo.rOut) * sin(Math.toRadians(62.0)).toFloat() + px(8f)
            anchor.floatValue = if (h > 2 * reach) y.coerceIn(reach, h - reach) else h / 2
            side = e; sticky = false
            val cur = if (e == Edge.LEFT) list.indexOfFirst { it.key == currentTab }.takeIf { it >= 0 } else null
            mo.sel.intValue = cur ?: -1
            mo.bead.floatValue = slotAngle(cur ?: ((list.size - 1) / 2), e)
            mo.vel = 0f; mo.stretch.floatValue = 0f; mo.dragging = false
            if (!overlay) Ambience.held.value = true
            scope.launch { appear.snapTo(0f); appear.animateTo(1f, Motion.press()) }
        }
        fun closeWheel() {
            EdgeSwipe.log("wheel closed")
            mo.dragging = false
            scope.launch {
                appear.animateTo(0f, Motion.quick())
                side = null; mo.sel.intValue = -1; sticky = false
                if (!overlay) Ambience.held.value = false
                cbClosed()
            }
        }
        /** Left (portrait: top) half of the screen. */
        fun firstHalf(p: Offset) = if (h > w) p.y < h / 2f else p.x < w / 2f
        /**
         * Pulling a slot out past the wheel picks it up; pulling it back over the wheel puts it
         * back. True while something is being carried.
         */
        fun carryCheck(e: Edge, p: Offset): Boolean {
            val c = centre(e)
            val d = hypot(p.x - c.x, p.y - c.y)
            if (carry == null) {
                if (overlay || cbDrop == null || d < px(Geo.rOut + 40f)) return false
                val it = itemsOf(e).getOrNull(mo.sel.intValue) ?: return false
                if (!cbCan(it.key, e == Edge.RIGHT)) return false
                carry = it; carryApp = e == Edge.RIGHT
                mo.dragging = false
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                EdgeSwipe.log("carrying ${it.label} out of the wheel")
            } else if (d < px(Geo.rOut)) {
                carry = null
                EdgeSwipe.log("put back on the wheel")
                return false
            }
            carryAt.value = p
            return true
        }
        fun pick(e: Edge, i: Int) {
            val key = itemsOf(e).getOrNull(i)?.key ?: return
            closeWheel()
            if (e == Edge.LEFT) cbTab(key) else cbApp(key)
        }
        /** Let go of a carried slot: it opens in the half under the finger. */
        fun dropCarry(p: Offset) {
            val it = carry ?: return
            val first = firstHalf(p)
            val app = carryApp
            carry = null
            EdgeSwipe.log("dropped ${it.label} on the ${if (first) "first" else "second"} half")
            closeWheel()
            cbDrop?.invoke(it.key, app, first)
        }

        // ---- the open wheel: a lens of liquid glass
        val open = side
        if (open != null) {
            val items = itemsOf(open)
            val n = nOf(open)
            val step = stepOf(open)
            val art = remember(open, n, w, h) { artFor(open) }
            val c0 = centre(open)
            val origin = TransformOrigin(c0.x / w, c0.y / h)
            fun fade() = (appear.value * 2f).coerceIn(0f, 1f)
            // the wheel melts away while a slot is carried out of it
            val carried = androidx.compose.animation.core.animateFloatAsState(if (carry != null) 1f else 0f, Motion.quick(), label = "carried")

            // the drop's physics: it tracks a held finger within a frame, and settles on its slot with a little wobble
            LaunchedEffect(open) {
                var last = 0L
                var seen = -1
                while (isActive) {
                    var settled = false
                    withFrameNanos { now ->
                        val dt = if (last == 0L) 1f / 60f else ((now - last) / 1e9f).coerceIn(0f, 1f / 20f)
                        last = now
                        val s = mo.sel.intValue
                        var x = mo.bead.floatValue
                        val target = when {
                            // the first and last icons are hard stops: the highlight never goes past them
                            mo.dragging -> wheelClamp(mo.finger, slotAngle(0, open), slotAngle(n - 1, open))
                            s >= 0 -> slotAngle(s, open)
                            else -> x
                        }
                        val k = if (mo.dragging) 3200f else 620f
                        val damp = 2f * (if (mo.dragging) 1f else 0.62f) * sqrt(k)
                        var v = mo.vel
                        var rem = dt
                        while (rem > 0f) {
                            val hs = min(rem, 0.004f)
                            v += (-k * (x - target) - damp * v) * hs
                            x += v * hs
                            rem -= hs
                        }
                        // a fast flick can't spring it out past the ends either
                        val lo = slotAngle(0, open); val hi = slotAngle(n - 1, open)
                        if (x < lo) { x = lo; if (v < 0f) v = 0f }
                        if (x > hi) { x = hi; if (v > 0f) v = 0f }
                        if (!mo.dragging && abs(x - target) < 0.0005f && abs(v) < 0.01f) { x = target; v = 0f; settled = true }
                        mo.vel = v
                        if (mo.bead.floatValue != x) mo.bead.floatValue = x
                        val st = (abs(v) * 0.06f).coerceAtMost(0.45f)
                        if (abs(mo.stretch.floatValue - st) > 0.002f || (st == 0f && mo.stretch.floatValue != 0f)) mo.stretch.floatValue = st
                    }
                    if (settled) {
                        last = 0L
                        seen = mo.wake.intValue
                        snapshotFlow { mo.wake.intValue }.first { it != seen }
                    }
                }
            }

            // the scrim falls off from the edge the wheel comes from, so the page stays faintly there
            Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = fade(); compositingStrategy = CompositingStrategy.ModulateAlpha }) {
                val c = centre(open)
                drawRect(Color.Black.copy(alpha = 0.22f))
                drawRect(
                    Brush.radialGradient(
                        0f to Color.Black.copy(alpha = 0.55f), 0.55f to Color.Black.copy(alpha = 0.30f), 1f to Color.Transparent,
                        center = c, radius = max(w, h) * 0.75f,
                    ),
                )
            }
            // the wheel balloons out from its centre past the edge; a layer property, so opening redraws nothing
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val g = 0.8f + 0.2f * appear.value
                    scaleX = g; scaleY = g
                    alpha = fade() * (1f - carried.value)
                    transformOrigin = origin
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
            ) {
                // the still glass, painted ahead
                Canvas(Modifier.fillMaxSize()) {
                    val c = centre(open)
                    drawImage(art.band, Offset(c.x - art.cx, c.y - art.cy))
                }
                // the moving light: the glint that follows your finger, and the drop of glass
                Canvas(Modifier.fillMaxSize()) {
                    val c = centre(open)
                    val b = mo.bead.floatValue
                    val cv = drawContext.canvas.nativeCanvas
                    cv.save()
                    cv.translate(c.x, c.y)
                    fx.glint(cv, art, wheelDeg(open, b - 0.55f), wheelDeg(open, b + 1.1f))
                    cv.restore()
                    // every slot is its own drop of clear glass, Apple style; the one under your finger swells.
                    // No separate blob travels between them.
                    items.indices.forEach { i ->
                        val a = slotAngle(i, open)
                        val near = if (mo.sel.intValue >= 0) (1f - abs(a - b) / step).coerceIn(0f, 1f) else 0f
                        fx.drop(cv, density, at(open, a, px(Geo.rItem)), 0.78f + 0.40f * near * near, near)
                    }
                }
                // slot icons: each sits in its own drop and grows with it
                items.forEachIndexed { i, it ->
                    val p = at(open, slotAngle(i, open), px(Geo.rItem))
                    val a = slotAngle(i, open)
                    Box(
                        Modifier.offset { IntOffset((p.x - px(20f)).roundToInt(), (p.y - px(20f)).roundToInt()) }
                            .size(40.dp)
                            .graphicsLayer {
                                val near = if (mo.sel.intValue >= 0) (1f - abs(a - mo.bead.floatValue) / step).coerceIn(0f, 1f) else 0f
                                val mag = (0.78f + 0.40f * near * near) / 0.78f
                                scaleX = mag; scaleY = mag
                                alpha = 0.75f + 0.25f * near
                                // held to close: it follows the pull down and fades as it goes
                                if (closing == it.key) {
                                    val pull = closePull.floatValue
                                    translationY = pull * px(WHEEL_CLOSE_DP) * 0.6f
                                    alpha *= 1f - 0.55f * pull
                                    val shrink = 1f - 0.25f * pull
                                    scaleX = mag * shrink; scaleY = mag * shrink
                                }
                            }
                            .semantics { contentDescription = it.label },
                        contentAlignment = Alignment.Center,
                    ) { it.icon(Modifier.size(if (open == Edge.RIGHT) 36.dp else 26.dp), V.White) }
                }
                // the chosen slot's name, floating just outside the band in its own drop of glass
                WheelLabel(mo, items) { a -> at(open, a, px(Geo.rOut + 22f)) to (open == Edge.LEFT) }
                // holding an app's slot: a hint under it, coral once the pull is far enough to close
                val holding = closing
                val hi = if (holding != null) items.indexOfFirst { it.key == holding } else -1
                if (hi >= 0) {
                    val hp = at(open, slotAngle(hi, open), px(Geo.rItem))
                    val ready = closePull.floatValue >= 1f
                    Box(
                        Modifier.offset { IntOffset((hp.x - px(150f)).roundToInt(), (hp.y + px(34f)).roundToInt()) }
                            .size(width = 130.dp, height = 34.dp)
                            .liquidGlass(androidx.compose.foundation.shape.RoundedCornerShape(17.dp), GlassLook(smoke = 0.8f, depth = 1f, shine = 1.1f), still = true)
                            .semantics { contentDescription = if (ready) "Let go to close" else "Swipe down to close" },
                        contentAlignment = Alignment.Center,
                    ) {
                        VText(if (ready) "Let go to close" else "Swipe down to close", size = Type.label, color = if (ready) V.Coral else V.Text, maxLines = 1)
                    }
                }
                // the hub: a glass orb with the VoidOS mark on the tab wheel, the app grid on the other
                val hubC = hubAt(open)
                Box(
                    Modifier.offset { IntOffset((hubC.x - px(Geo.hub)).roundToInt(), (hubC.y - px(Geo.hub)).roundToInt()) }
                        .size((Geo.hub * 2).dp)
                        .liquidGlass(CircleShape, GlassLook(smoke = 0.55f, depth = 1.2f, shine = 1.4f, magnify = 1.5f), still = true),
                    contentAlignment = Alignment.Center,
                ) {
                    if (open == Edge.LEFT) VoidMark(Modifier.size(30.dp), mono = V.Text)
                    else Glyph(G.APPS, V.Text, Modifier.size(22.dp))
                }
            }
            if (sticky) {
                val hubC = hubAt(open)
                // wheel left open by a tap: tap a slot, tap the hub, or tap anywhere else to close
                Box(
                    Modifier.fillMaxSize().pointerInput(open, n) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            val onHub = hypot(down.position.x - hubC.x, down.position.y - hubC.y) < px(Geo.hub + 10f)
                            val c0 = centre(open)
                            val onRing = hypot(down.position.x - c0.x, down.position.y - c0.y) in px(Geo.rIn - 10f)..px(Geo.rOut + 10f)
                            val from = if (onRing && !onHub) indexAt(open, down.position) else -1
                            // an app slot held still for a moment can be swiped down to close the app
                            val holdKey = if (from >= 0 && open == Edge.RIGHT && cbClose != null)
                                items.getOrNull(from)?.key?.takeIf { cbCanClose(it) } else null
                            var armed = false
                            var up = down
                            while (true) {
                                val ev = if (holdKey != null && !armed && carry == null)
                                    withTimeoutOrNull(WHEEL_HOLD_MS - (up.uptimeMillis - down.uptimeMillis).coerceAtLeast(0L)) { awaitPointerEvent() }
                                else awaitPointerEvent()
                                if (ev == null) {
                                    // held still long enough: armed
                                    armed = true; closing = holdKey; closePull.floatValue = 0f; mo.sel.intValue = from
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    EdgeSwipe.log("holding ${items.getOrNull(from)?.label}: swipe down to close")
                                    continue
                                }
                                val ch = ev.changes.first()
                                ch.consume()
                                up = ch
                                val moved = hypot(ch.position.x - down.position.x, ch.position.y - down.position.y)
                                if (armed) {
                                    closePull.floatValue = wheelClosePull(ch.position.y - down.position.y, ch.position.x - down.position.x, px(WHEEL_CLOSE_DP))
                                } else if (from >= 0) {
                                    // moved before the hold: it's a carry or a tap, as before
                                    if (carry == null) mo.sel.intValue = from
                                    if (holdKey == null || moved > px(14f) || ch.uptimeMillis - down.uptimeMillis >= WHEEL_HOLD_MS) carryCheck(open, ch.position)
                                }
                                if (!ch.pressed) break
                            }
                            val p = up.position
                            if (armed) {
                                val key = closing
                                val go = closePull.floatValue >= 1f
                                closing = null; closePull.floatValue = 0f
                                if (go && key != null) { closeWheel(); cbClose?.invoke(key) }
                                return@awaitEachGesture
                            }
                            if (carry != null) { dropCarry(p); return@awaitEachGesture }
                            when {
                                onHub -> { closeWheel(); if (open == Edge.RIGHT) cbAll() }
                                hypot(p.x - centre(open).x, p.y - centre(open).y) in px(Geo.rIn - 10f)..px(Geo.rOut + 10f) -> pick(open, indexAt(open, p))
                                else -> closeWheel()
                            }
                        }
                    }
                )
            }
        }

        // ---- a slot carried out of the wheel: the half it will open in lights up as a sheet of
        // glass, the other half shows where the watch face goes, and the slot rides under the finger
        val held = carry
        if (held != null) {
            val p = carryAt.value
            val port = h > w
            val first = firstHalf(p)
            val sw by androidx.compose.animation.core.animateFloatAsState(if (first) 0f else 1f, Motion.press(), label = "carrySide")
            val halfW = if (port) w else w / 2f
            val halfH = if (port) h / 2f else h
            fun halfAt(f: Float) = if (port) IntOffset(0, (f * halfH).roundToInt()) else IntOffset((f * halfW).roundToInt(), 0)
            val dw = with(density) { halfW.toDp() }
            val dh = with(density) { halfH.toDp() }
            // where the watch face will sit
            Box(
                Modifier.offset { halfAt(1f - sw) }.size(dw, dh).padding(Grid.l),
                contentAlignment = Alignment.Center,
            ) { Glyph(G.FACE, V.Text.copy(alpha = 0.35f), Modifier.size(56.dp)) }
            // where it will open
            Box(
                Modifier.offset { halfAt(sw) }.size(dw, dh).padding(Grid.l)
                    .liquidGlass(androidx.compose.foundation.shape.RoundedCornerShape(Radius.panel), GlassLook(smoke = 0.45f, depth = 1.2f, shine = 1.3f), still = true)
                    .semantics { contentDescription = "Drop ${held.label} here" },
            )
            Box(
                Modifier.offset { IntOffset((p.x - px(36f)).roundToInt(), (p.y - px(36f)).roundToInt()) }
                    .size(72.dp)
                    .liquidGlass(CircleShape, GlassLook(smoke = 0.6f, depth = 1.3f, shine = 1.5f, magnify = 1.3f), still = true),
                contentAlignment = Alignment.Center,
            ) { held.icon(Modifier.size(if (carryApp) 44.dp else 30.dp), V.White) }
        }

        // ---- the edges: MainActivity routes edge touches here before the page sees them (see EdgeSwipe)
        val g = remember { Gesture() }
        val sink = run {
            object : EdgeSwipe.Sink {
                override fun begin(left: Boolean, x: Float, y: Float, t: Long) {
                    val e = if (left) Edge.LEFT else Edge.RIGHT
                    g.sx = x - g.ox; g.sy = y - g.oy
                    g.lx = g.sx; g.ly = g.sy
                    g.moved = false
                    g.beganAt = t; g.selAt = t
                    openAt(e, g.sy)
                    EdgeSwipe.log("wheel ${e.name.lowercase()} opened at y=${anchor.floatValue.toInt()} in ${w.toInt()}x${h.toInt()} (${itemsOf(e).size} slots)")
                }
                override fun move(x: Float, y: Float, t: Long) {
                    val e = side ?: return
                    g.lx = x - g.ox; g.ly = y - g.oy
                    if (!g.moved && hypot(g.lx - g.sx, g.ly - g.sy) > px(14f)) g.moved = true
                    if (g.moved && carryCheck(e, Offset(g.lx, g.ly))) return
                    if (g.moved) {
                        val p = Offset(g.lx, g.ly)
                        mo.finger = wheelClamp(angleAt(e, p), slotAngle(0, e), slotAngle(nOf(e) - 1, e))
                        mo.dragging = true
                        val i = indexAt(e, p)
                        if (i != mo.sel.intValue) {
                            if (mo.sel.intValue >= 0) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            g.selAt = t
                            mo.sel.intValue = i
                        }
                        mo.wake.intValue++
                    }
                }
                override fun end(x: Float, y: Float, t: Long) {
                    // nothing open: over another app, give the screen straight back
                    val e = side ?: run { if (overlay) cbClosed(); return }
                    move(x, y, t)
                    if (carry != null) { dropCarry(Offset(g.lx, g.ly)); return }
                    mo.dragging = false
                    mo.wake.intValue++
                    val hub = hubAt(e)
                    val onHub = hypot(g.lx - hub.x, g.ly - hub.y) < px(Geo.hub)
                    val list = itemsOf(e)
                    val s = mo.sel.intValue
                    when {
                        // a quick swipe in, or a tap on the edge: the wheel stays open to tap
                        !releasePicks(g.moved, g.beganAt, g.selAt, t) -> { sticky = true; EdgeSwipe.log("wheel pinned open (${t - g.beganAt}ms, tap a slot)") }
                        onHub -> closeWheel()
                        s >= 0 && list.isNotEmpty() -> { EdgeSwipe.log("wheel picked ${list.getOrNull(s)?.label}"); pick(e, s) }
                        else -> closeWheel()
                    }
                }
                override fun cancel() { carry = null; if (side != null && !sticky) closeWheel() else if (side == null && overlay) cbClosed() }
            }
        }
        // the page dots and the edge handles open a wheel pinned, no swipe needed
        val req by (if (overlay) EdgeSwipe.overlayRequest else EdgeSwipe.request)
        LaunchedEffect(req) {
            val r = req ?: return@LaunchedEffect
            if (overlay) EdgeSwipe.overlayRequest.value = null else EdgeSwipe.request.value = null
            if (!enabled || side != null) return@LaunchedEffect
            openAt(if (r) Edge.LEFT else Edge.RIGHT, h / 2)
            sticky = true
            EdgeSwipe.log("wheel ${if (r) "left" else "right"} opened by tap")
        }
        // a pinned-open wheel takes its taps itself (the overlay above), so the edges stand down meanwhile
        SideEffect {
            if (overlay) {
                EdgeSwipe.overlaySink = sink
            } else {
                EdgeSwipe.sink = sink
                EdgeSwipe.enabled = enabled && !(side != null && sticky)
                EdgeSwipe.rightEnabled = appsNow.isNotEmpty()
            }
        }
        DisposableEffect(Unit) {
            onDispose {
                if (overlay) { if (EdgeSwipe.overlaySink === sink) EdgeSwipe.overlaySink = null }
                else { if (EdgeSwipe.sink === sink) EdgeSwipe.sink = null; EdgeSwipe.enabled = false; Ambience.held.value = false }
            }
        }
        if (!enabled && side != null) LaunchedEffect(Unit) { closeWheel() }
        Box(Modifier.fillMaxSize().onGloballyPositioned { val o = it.positionInWindow(); g.ox = o.x; g.oy = o.y })

        // no handles drawn on the edges: the edges are simply where the wheels live.
        // Tapping the page dots still opens the tab wheel without a swipe.
    }
}

/** One finger's path along an edge; plain fields, since nothing on screen reads them. */
private class Gesture {
    var ox = 0f; var oy = 0f
    var sx = 0f; var sy = 0f
    var lx = 0f; var ly = 0f
    var moved = false
    var beganAt = 0L
    var selAt = 0L
}

/**
 * The highlighted slot's name in a pill of glass just outside the band. It follows the drop
 * through a layer that's only moved, and recomposes only when the name changes.
 */
@Composable
private fun WheelLabel(mo: WheelMotion, items: List<WheelItem>, spot: (Float) -> Pair<Offset, Boolean>) {
    val i = mo.sel.intValue
    val label = items.getOrNull(i)?.label ?: return
    Box(
        Modifier.layout { m, cs ->
            val pl = m.measure(cs.copy(minWidth = 0, minHeight = 0))
            layout(cs.maxWidth, cs.maxHeight) {
                val (p, left) = spot(mo.bead.floatValue)
                val x = if (left) p.x else p.x - pl.width
                pl.placeWithLayer(x.roundToInt(), (p.y - pl.height / 2).roundToInt())
            }
        },
    ) {
        Box(Modifier.glass(Pill, 0.75f).padding(horizontal = 20.dp, vertical = 10.dp)) {
            VText(label, size = 18.sp, font = SansMedium, color = V.White, maxLines = 1)
        }
    }
}

/** Settings > About > Edge touch debug: the edge zones, the last touch and the router's last decision. */
@Composable
fun EdgeDebugOverlay() {
    if (!EdgeSwipe.debug.value) return
    val d = LocalDensity.current
    val last by EdgeSwipe.last
    val down by EdgeSwipe.lastDown
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val hard = with(d) { EdgeSwipe.HARD_DP.dp.toPx() }
            val soft = with(d) { EdgeSwipe.SOFT_DP.dp.toPx() }
            listOf(0f to hard, size.width - hard to size.width).forEach { (a, b) -> drawRect(V.Teal.copy(alpha = 0.22f), Offset(a, 0f), androidx.compose.ui.geometry.Size(b - a, size.height)) }
            listOf(hard to soft, size.width - soft to size.width - hard).forEach { (a, b) -> drawRect(V.Coral.copy(alpha = 0.14f), Offset(a, 0f), androidx.compose.ui.geometry.Size(b - a, size.height)) }
            down?.let { (x, y) -> drawCircle(V.Yellow, 14f, Offset(x, y) - origin) }
        }
        VText(
            "EDGE DEBUG  $last", size = Type.label, color = V.White,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** The wheel's band as one closed shape: a thick arc with fully round ends, so it's a capsule bent around the edge. */
internal fun wheelBand(c: Offset, rMid: Float, thick: Float, startDeg: Float, sweepDeg: Float): Path {
    val arc = android.graphics.Path()
    arc.addArc(c.x - rMid, c.y - rMid, c.x + rMid, c.y + rMid, startDeg, sweepDeg)
    val p = android.graphics.Paint().apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = thick
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    val out = android.graphics.Path()
    p.getFillPath(arc, out)
    return out.asComposePath()
}
