package com.andysocial.voidos.ui

import android.content.Context
import com.andysocial.voidos.Stroke
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The drawing tools' logic for Notes, kept free of the UI so it can be tested:
 * the undo/redo history, brush sizes on a continuous scale, shapes, smoothing,
 * colour maths for the colour wheel, and the remembered tool settings.
 *
 * Ink points are fractions of the page's width (x and y both), as they always
 * were, so saved notes draw exactly as before.
 */

// line styles beyond SOLID, DOTTED, DASHED (Notes.kt); stored in Stroke.style
internal const val DASH_DOT = 3
internal const val LONG_DASH = 4
internal const val SPARSE_DOTS = 5
internal val LineStyles = listOf(SOLID, DOTTED, SPARSE_DOTS, DASHED, LONG_DASH, DASH_DOT)
internal fun lineStyleName(s: Int) = when (s) {
    DOTTED -> "Dotted"; SPARSE_DOTS -> "Spaced dots"; DASHED -> "Dashed"; LONG_DASH -> "Long dash"; DASH_DOT -> "Dash dot"; else -> "Solid"
}

// the three tools
internal const val TOOL_PEN = 0
internal const val TOOL_HIGHLIGHTER = 1
internal const val TOOL_ERASER = 2

// shapes the pen can draw
internal const val SHAPE_FREE = 0
internal const val SHAPE_LINE = 1
internal const val SHAPE_ARROW = 2
internal const val SHAPE_RECT = 3
internal const val SHAPE_ELLIPSE = 4
internal val Shapes = listOf(SHAPE_FREE, SHAPE_LINE, SHAPE_ARROW, SHAPE_RECT, SHAPE_ELLIPSE)
internal fun shapeName(s: Int) = when (s) {
    SHAPE_LINE -> "Straight line"; SHAPE_ARROW -> "Arrow"; SHAPE_RECT -> "Rectangle"; SHAPE_ELLIPSE -> "Ellipse"; else -> "Freehand"
}

// paper behind a note
internal const val PAPER_PLAIN = 0
internal const val PAPER_LINED = 1
internal const val PAPER_GRID = 2
internal const val PAPER_DOTS = 3
internal val Papers = listOf(PAPER_PLAIN, PAPER_LINED, PAPER_GRID, PAPER_DOTS)
internal fun paperName(p: Int) = when (p) {
    PAPER_LINED -> "Lined paper"; PAPER_GRID -> "Grid paper"; PAPER_DOTS -> "Dotted paper"; else -> "Plain paper"
}

/* ---------------- sizes ---------------- */

/** Thinnest and thickest pen, as a fraction of the page width. Any size between. */
internal const val MIN_PEN = 0.001f
internal const val MAX_PEN = 0.08f
internal const val MIN_ERASER = 0.005f
internal const val MAX_ERASER = 0.08f

/** Slider position (0..1) to a size: squared, so the thin end has room for fine control. */
internal fun sizeFromSlider(t: Float, lo: Float = MIN_PEN, hi: Float = MAX_PEN): Float {
    val c = t.coerceIn(0f, 1f)
    return lo + (hi - lo) * c * c
}

internal fun sliderFromSize(s: Float, lo: Float = MIN_PEN, hi: Float = MAX_PEN): Float =
    sqrt(((s - lo) / (hi - lo)).coerceIn(0f, 1f))

/* ---------------- history ---------------- */

/** One change to a drawing that can be undone and redone. */
internal sealed class InkEdit {
    /** A stroke drawn (always added at the end). */
    data class Add(val stroke: Stroke) : InkEdit()
    /** Strokes rubbed out, with the places they had, in ascending order. */
    data class Erase(val removed: List<Pair<Int, Stroke>>) : InkEdit()
    /** The page cleared: everything that was on it. */
    data class Clear(val all: List<Stroke>) : InkEdit()
    /** Several edits as one step (one sweep of the eraser). */
    data class Batch(val edits: List<InkEdit>) : InkEdit()
}

internal fun InkEdit.applyTo(ink: MutableList<Stroke>) {
    when (this) {
        is InkEdit.Add -> ink.add(stroke)
        is InkEdit.Erase -> removed.asReversed().forEach { (i, _) -> if (i in ink.indices) ink.removeAt(i) }
        is InkEdit.Clear -> ink.clear()
        is InkEdit.Batch -> edits.forEach { it.applyTo(ink) }
    }
}

internal fun InkEdit.revertOn(ink: MutableList<Stroke>) {
    when (this) {
        is InkEdit.Add -> {
            val i = if (ink.isNotEmpty() && ink.last() == stroke) ink.lastIndex else ink.lastIndexOf(stroke)
            if (i >= 0) ink.removeAt(i)
        }
        is InkEdit.Erase -> removed.forEach { (i, s) -> ink.add(i.coerceIn(0, ink.size), s) }
        is InkEdit.Clear -> ink.addAll(0, all)
        is InkEdit.Batch -> edits.asReversed().forEach { it.revertOn(ink) }
    }
}

/**
 * Undo and redo for one open note. Drawing something new clears the redo
 * steps. Undo with no history yet (a note just opened) takes back the last
 * stroke on the page, as Undo always did, and Redo puts it back.
 */
internal class InkHistory(private val limit: Int = 300) {
    private val undos = ArrayList<InkEdit>()
    private val redos = ArrayList<InkEdit>()

    fun canUndo(ink: List<Stroke>) = undos.isNotEmpty() || ink.isNotEmpty()
    val canRedo get() = redos.isNotEmpty()

    fun record(e: InkEdit) {
        undos.add(e)
        if (undos.size > limit) undos.removeAt(0)
        redos.clear()
    }

    fun undo(ink: MutableList<Stroke>): Boolean {
        val e = if (undos.isNotEmpty()) undos.removeAt(undos.lastIndex) else ink.lastOrNull()?.let { InkEdit.Add(it) } ?: return false
        e.revertOn(ink)
        redos.add(e)
        return true
    }

    fun redo(ink: MutableList<Stroke>): Boolean {
        if (redos.isEmpty()) return false
        val e = redos.removeAt(redos.lastIndex)
        e.applyTo(ink)
        undos.add(e)
        return true
    }
}

/** Rubs out every stroke within [r] of ([x], [y]) and says which went and where they were. */
internal fun eraseIndexed(ink: MutableList<Stroke>, x: Float, y: Float, r: Float): InkEdit.Erase? {
    val hit = ink.indices.filter { strokeDistance(ink[it].pts, x, y) < r + ink[it].width / 2 }
    if (hit.isEmpty()) return null
    val e = InkEdit.Erase(hit.map { it to ink[it] })
    e.applyTo(ink)
    return e
}

/* ---------------- shapes and smoothing ---------------- */

/** The points of a shape dragged from ([x0], [y0]) to ([x1], [y1]), as one stroke. */
internal fun shapePoints(shape: Int, x0: Float, y0: Float, x1: Float, y1: Float): List<Float> {
    val dx = x1 - x0; val dy = y1 - y0
    val len = hypot(dx, dy)
    if (len < 1e-4f) return listOf(x0, y0)
    return when (shape) {
        SHAPE_LINE -> listOf(x0, y0, x1, y1)
        SHAPE_ARROW -> {
            val head = (len * 0.22f).coerceIn(0.012f, 0.05f)
            val a = atan2(dy, dx)
            val spread = Math.toRadians(28.0).toFloat()
            val hx1 = x1 - head * cos(a - spread); val hy1 = y1 - head * sin(a - spread)
            val hx2 = x1 - head * cos(a + spread); val hy2 = y1 - head * sin(a + spread)
            listOf(x0, y0, x1, y1, hx1, hy1, x1, y1, hx2, hy2)
        }
        SHAPE_RECT -> listOf(x0, y0, x1, y0, x1, y1, x0, y1, x0, y0)
        SHAPE_ELLIPSE -> {
            val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2
            val rx = abs(dx) / 2; val ry = abs(dy) / 2
            val n = 64
            List((n + 1) * 2) { k ->
                val t = (k / 2) * (2 * Math.PI / n)
                if (k % 2 == 0) (cx + rx * cos(t)).toFloat() else (cy + ry * sin(t)).toFloat()
            }
        }
        else -> listOf(x0, y0, x1, y1)
    }
}

/** Chaikin smoothing: rounds off the corners of a hand-drawn line, keeping both ends. */
internal fun smoothPoints(pts: List<Float>, passes: Int = 2): List<Float> {
    var p = pts
    repeat(passes) {
        if (p.size < 6) return p
        val out = ArrayList<Float>(p.size * 2)
        out.add(p[0]); out.add(p[1])
        var i = 0
        while (i + 3 < p.size) {
            val ax = p[i]; val ay = p[i + 1]; val bx = p[i + 2]; val by = p[i + 3]
            out.add(0.75f * ax + 0.25f * bx); out.add(0.75f * ay + 0.25f * by)
            out.add(0.25f * ax + 0.75f * bx); out.add(0.25f * ay + 0.75f * by)
            i += 2
        }
        out.add(p[p.size - 2]); out.add(p[p.size - 1])
        p = out
    }
    return p
}

/** Keeps a point on the page: x within its width, y within its height (both in page widths). */
internal fun clampToPage(x: Float, y: Float, maxY: Float): Pair<Float, Float> =
    x.coerceIn(0f, 1f) to y.coerceIn(0f, max(0f, maxY))

/* ---------------- colour ---------------- */

/** HSV (hue 0..360, saturation and value 0..1) to an opaque ARGB int. */
internal fun hsvToArgb(h: Float, s: Float, v: Float): Int {
    val hh = ((h % 360f) + 360f) % 360f / 60f
    val c = v * s
    val x = c * (1 - abs(hh % 2 - 1))
    val (r, g, b) = when (hh.toInt()) {
        0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
    }
    val m = v - c
    fun ch(f: Float) = ((f + m) * 255f).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
}

/** ARGB to HSV (alpha ignored). */
internal fun argbToHsv(argb: Int): FloatArray {
    val r = (argb shr 16 and 0xFF) / 255f; val g = (argb shr 8 and 0xFF) / 255f; val b = (argb and 0xFF) / 255f
    val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
    val h = when {
        d == 0f -> 0f
        mx == r -> 60f * (((g - b) / d) % 6f)
        mx == g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }.let { if (it < 0) it + 360f else it }
    return floatArrayOf(h, if (mx == 0f) 0f else d / mx, mx)
}

/** An opaque colour with [alpha] (0..1) applied. */
internal fun withAlpha(rgb: Int, alpha: Float): Int =
    ((alpha.coerceIn(0f, 1f) * 255f).roundToInt() shl 24) or (rgb and 0xFFFFFF)

internal fun hex(rgb: Int) = "#%06X".format(rgb and 0xFFFFFF)

/** Puts [rgb] first in the recent colours, once, keeping [max] of them. */
internal fun pushRecent(recent: List<Int>, rgb: Int, max: Int = 8): List<Int> {
    val c = rgb or (0xFF shl 24)
    return (listOf(c) + recent.filter { it != c }).take(max)
}

/** How see-through the highlighter is at full opacity. */
internal const val HIGHLIGHTER_ALPHA = 0.38f

/** The ARGB a stroke is saved with for this tool, colour and opacity. */
internal fun inkArgb(tool: Int, rgb: Int, opacity: Float): Int =
    withAlpha(rgb, if (tool == TOOL_HIGHLIGHTER) min(opacity, 1f) * HIGHLIGHTER_ALPHA else opacity)

/* ---------------- remembered settings ---------------- */

/** The pen settings, kept between notes and restarts, and each note's paper. */
internal class InkPrefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("voidos_notes_ink", Context.MODE_PRIVATE)

    var rgb: Int get() = p.getInt("rgb", 0xFFFFFFFF.toInt()); set(v) = p.edit().putInt("rgb", v).apply()
    var opacity: Float get() = p.getFloat("opacity", 1f); set(v) = p.edit().putFloat("opacity", v).apply()
    var penSize: Float get() = p.getFloat("penSize", 0.006f); set(v) = p.edit().putFloat("penSize", v).apply()
    var highlighterSize: Float get() = p.getFloat("hlSize", 0.03f); set(v) = p.edit().putFloat("hlSize", v).apply()
    var eraserSize: Float get() = p.getFloat("eraserSize", 0.02f); set(v) = p.edit().putFloat("eraserSize", v).apply()
    var style: Int get() = p.getInt("style", SOLID); set(v) = p.edit().putInt("style", v).apply()
    var shape: Int get() = p.getInt("shape", SHAPE_FREE); set(v) = p.edit().putInt("shape", v).apply()
    var smooth: Boolean get() = p.getBoolean("smooth", false); set(v) = p.edit().putBoolean("smooth", v).apply()
    var recent: List<Int>
        get() = p.getString("recent", "")!!.split(',').mapNotNull { it.toLongOrNull()?.toInt() }
        set(v) = p.edit().putString("recent", v.joinToString(",") { (it.toLong() and 0xFFFFFFFFL).toString() }).apply()

    fun paper(noteId: Long): Int = p.getInt("paper_$noteId", PAPER_PLAIN)
    fun setPaper(noteId: Long, paper: Int) = p.edit().putInt("paper_$noteId", paper).apply()
}
