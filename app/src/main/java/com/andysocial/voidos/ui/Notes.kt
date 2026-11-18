package com.andysocial.voidos.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as PenStroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andysocial.voidos.Note
import com.andysocial.voidos.Store
import com.andysocial.voidos.Stroke
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot

/** Pen colours: white, then the six watch colours. */
private val Inks: List<Color> get() = listOf(V.White) + V.Lap

/** Pen sizes as a fraction of the page width: fine, medium, bold, marker. */
internal val PenSizes = listOf(0.003f, 0.006f, 0.012f, 0.024f)

internal const val SOLID = 0
internal const val DOTTED = 1
internal const val DASHED = 2

/**
 * Notes: a list on the left, a page on the right that you can type on, draw
 * on, or both. Drawing has seven pen colours, four sizes, and solid, dotted
 * or dashed lines, plus undo and an eraser. Everything saves as you go.
 */
@Composable
fun NotesScreen(store: Store) {
    var openId by remember { mutableLongStateOf(store.notes.firstOrNull()?.id ?: 0L) }
    val open = store.notes.firstOrNull { it.id == openId }
    var text by remember(openId) { mutableStateOf(open?.text ?: "") }
    var colour by remember(openId) { mutableStateOf(open?.colour ?: 0) }
    val ink = remember(openId) { mutableStateListOf<Stroke>().also { it.addAll(open?.ink ?: emptyList()) } }
    var inkVersion by remember(openId) { mutableIntStateOf(0) }
    var drawing by remember { mutableStateOf(false) }

    // autosave 600 ms after typing or drawing stops
    LaunchedEffect(text, colour, openId, inkVersion) {
        delay(600)
        val o = store.notes.firstOrNull { it.id == openId } ?: return@LaunchedEffect
        if (text != o.text || colour != o.colour || ink.toList() != o.ink) {
            store.saveNote(Note(openId, text, colour, System.currentTimeMillis(), ink.toList()))
        }
    }

    Split(Modifier.fillMaxSize()) {
        Column(Modifier.side(300.dp, portraitWeight = 0.3f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VButton("New note", Modifier.weight(1f), filled = true) {
                    val id = System.currentTimeMillis()
                    store.saveNote(Note(id, "", 0, id)); openId = id; drawing = false
                }
                VButton("Sketch", Modifier.weight(1f)) {
                    val id = System.currentTimeMillis()
                    store.saveNote(Note(id, "", 0, id)); openId = id; drawing = true
                }
            }
            Gap(h = 12.dp)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                items(store.notes, key = { it.id }) { n ->
                    val sel = n.id == openId
                    val acc = V.Lap[n.colour % V.Lap.size]
                    Column(
                        Modifier.fillMaxWidth().clip(OctS).background(if (sel) V.Panel2 else V.Panel)
                            .border(1.dp, if (sel) acc else V.Line, OctS).clickable { openId = n.id }.padding(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(OctS).background(acc))
                            Gap(w = 8.dp)
                            Label(SimpleDateFormat("d MMM HH:mm", Locale.UK).format(Date(n.updated)), Modifier.weight(1f))
                            if (n.ink.isNotEmpty()) Glyph(G.NOTES, acc, Modifier.size(14.dp))
                        }
                        Gap(h = 6.dp)
                        VText(n.text.trim().ifEmpty { if (n.ink.isNotEmpty()) "Drawing" else "Empty note" }, size = 14.sp, color = V.Text,
                            font = FontFamily.Default, maxLines = 2, lineHeight = 18.sp)
                    }
                }
            }
        }
        VPanel(Modifier.main(0.7f), accent = if (open != null) V.Lap[colour % V.Lap.size] else null, padding = 14.dp) {
            if (open == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    VText("Pick a note or start a new one", size = 14.sp, color = V.Muted)
                }
            } else {
                var pen by remember { mutableIntStateOf(0) }
                var size by remember { mutableIntStateOf(1) }
                var style by remember { mutableIntStateOf(SOLID) }
                var eraser by remember { mutableStateOf(false) }
                // top row: Type / Draw, the note's colour, delete
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Segment(listOf("Type", "Draw"), if (drawing) 1 else 0, V.Lap[colour % V.Lap.size]) { drawing = it == 1 }
                    Gap(w = 14.dp)
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        V.Lap.forEachIndexed { i, c ->
                            Box(Modifier.padding(end = 8.dp).size(22.dp).clip(OctS).background(c.copy(alpha = if (i == colour) 1f else 0.3f)).clickable { colour = i })
                        }
                    }
                    IconTap(G.TRASH, V.Coral, size = 44.dp) {
                        store.deleteNote(openId)
                        openId = store.notes.firstOrNull()?.id ?: 0L
                    }
                }
                if (drawing) {
                    Gap(h = 10.dp)
                    PenBar(pen, size, style, eraser, ink.isNotEmpty(),
                        onPen = { pen = it; eraser = false }, onSize = { size = it }, onStyle = { style = it; eraser = false },
                        onEraser = { eraser = !eraser },
                        onUndo = { if (ink.isNotEmpty()) { ink.removeAt(ink.lastIndex); inkVersion++ } },
                        onClear = { ink.clear(); inkVersion++ })
                }
                Gap(h = 10.dp)
                // the page: typed text underneath, ink on top
                Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(10.dp)).background(V.Bg.copy(alpha = 0.35f))) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        enabled = !drawing,
                        textStyle = TextStyle(color = V.Text, fontSize = 20.sp, lineHeight = 30.sp),
                        cursorBrush = SolidColor(V.Teal),
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                    )
                    InkPage(ink, drawing, Inks[pen].toArgb(), PenSizes[size], style, eraser, Modifier.fillMaxSize()) { inkVersion++ }
                }
                Gap(h = 6.dp)
                val words = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
                Label("$words words${if (ink.isNotEmpty()) " / ${ink.size} strokes" else ""} / saved on the tablet only")
            }
        }
    }
}

/** Two or three options in one pill: the chosen one filled. */
@Composable
private fun Segment(options: List<String>, sel: Int, accent: Color, onSelect: (Int) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(V.Panel2).border(1.dp, V.Line, RoundedCornerShape(50)).padding(3.dp)) {
        options.forEachIndexed { i, o ->
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(if (i == sel) accent.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable { onSelect(i) }.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { VText(sentence(o), size = 11.sp, color = if (i == sel) accent else V.Muted, maxLines = 1) }
        }
    }
}

/** The drawing tools: colour, size, line style, eraser, undo, clear. */
@Composable
private fun PenBar(
    pen: Int, penSize: Int, style: Int, eraser: Boolean, hasInk: Boolean,
    onPen: (Int) -> Unit, onSize: (Int) -> Unit, onStyle: (Int) -> Unit, onEraser: () -> Unit, onUndo: () -> Unit, onClear: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).clip(RoundedCornerShape(14.dp)).background(V.Panel2).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Inks.forEachIndexed { i, c ->
            val on = i == pen && !eraser
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(50)).border(if (on) 2.dp else 0.dp, if (on) V.White else Color.Transparent, RoundedCornerShape(50))
                    .padding(4.dp).clip(RoundedCornerShape(50)).background(c).clickable { onPen(i) },
            )
        }
        Divider()
        PenSizes.indices.forEach { i ->
            val on = i == penSize
            Box(
                Modifier.size(34.dp).clip(OctS).background(if (on) V.Teal.copy(alpha = 0.2f) else Color.Transparent).clickable { onSize(i) },
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size((4 + i * 5).dp).clip(RoundedCornerShape(50)).background(if (on) V.Teal else V.Muted)) }
        }
        Divider()
        listOf(SOLID, DOTTED, DASHED).forEach { s ->
            val on = s == style && !eraser
            Box(
                Modifier.size(width = 46.dp, height = 34.dp).clip(OctS).background(if (on) V.Teal.copy(alpha = 0.2f) else Color.Transparent).clickable { onStyle(s) },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(width = 30.dp, height = 6.dp)) {
                    drawLine(if (on) V.Teal else V.Muted, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 4.dp.toPx(), StrokeCap.Round,
                        effectFor(s, 4.dp.toPx()))
                }
            }
        }
        Divider()
        Tool("Eraser", eraser, V.Coral, onEraser)
        Tool("Undo", false, V.Text, onUndo, enabled = hasInk)
        Tool("Clear", false, V.Coral, onClear, enabled = hasInk)
    }
}

@Composable
private fun Divider() = Box(Modifier.width(1.dp).height(26.dp).background(V.Line))

@Composable
private fun Tool(label: String, on: Boolean, accent: Color, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        Modifier.clip(OctS).background(if (on) accent.copy(alpha = 0.2f) else Color.Transparent).border(1.dp, if (on) accent else V.Line, OctS)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    ) { VText(sentence(label), size = 11.sp, color = if (enabled) accent else V.Tick, maxLines = 1) }
}

/** Dotted: round dots one pen-width apart. Dashed: dashes three widths long. */
internal fun effectFor(style: Int, widthPx: Float): PathEffect? = when (style) {
    DOTTED -> PathEffect.dashPathEffect(floatArrayOf(0.01f, widthPx * 2.2f))
    DASHED -> PathEffect.dashPathEffect(floatArrayOf(widthPx * 3f, widthPx * 2.5f))
    else -> null
}

private fun DrawScope.drawInk(ink: List<Stroke>) {
    val w = size.width
    ink.forEach { st ->
        val px = st.width * w
        val c = Color(st.argb)
        if (st.pts.size == 2) { drawCircle(c, px / 2, Offset(st.pts[0] * w, st.pts[1] * w)); return@forEach }
        val path = Path()
        var i = 0
        while (i + 1 < st.pts.size) {
            val x = st.pts[i] * w; val y = st.pts[i + 1] * w
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            i += 2
        }
        drawPath(path, c, style = PenStroke(px, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effectFor(st.style, px)))
    }
}

/** A note's drawing, scaled to fit, for the face widget. */
@Composable
fun InkPreview(ink: List<Stroke>, modifier: Modifier) {
    Canvas(modifier) {
        // fit the drawing's bounds into the box
        val xs = ink.flatMap { s -> s.pts.filterIndexed { i, _ -> i % 2 == 0 } }
        val ys = ink.flatMap { s -> s.pts.filterIndexed { i, _ -> i % 2 == 1 } }
        if (xs.isEmpty()) return@Canvas
        val minX = xs.min(); val minY = ys.min()
        val bw = (xs.max() - minX).coerceAtLeast(0.01f); val bh = (ys.max() - minY).coerceAtLeast(0.01f)
        val k = minOf(size.width / bw, size.height / bh) * 0.9f
        val ox = (size.width - bw * k) / 2; val oy = (size.height - bh * k) / 2
        ink.forEach { st ->
            val px = (st.width * k).coerceIn(1.5f, 10f)
            val c = Color(st.argb)
            val path = Path()
            var i = 0
            while (i + 1 < st.pts.size) {
                val x = ox + (st.pts[i] - minX) * k; val y = oy + (st.pts[i + 1] - minY) * k
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                i += 2
            }
            if (st.pts.size == 2) drawCircle(c, px / 2, Offset(ox + (st.pts[0] - minX) * k, oy + (st.pts[1] - minY) * k))
            else drawPath(path, c, style = PenStroke(px, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effectFor(st.style, px)))
        }
    }
}

/** Removes every stroke that passes within [r] (page-width fractions) of ([x], [y]). True if any went. */
internal fun eraseAt(ink: MutableList<Stroke>, x: Float, y: Float, r: Float): Boolean =
    ink.removeAll { st -> strokeDistance(st.pts, x, y) < r + st.width / 2 }

/** Shortest distance from ([x], [y]) to a stroke's line segments (or its one dot). */
internal fun strokeDistance(pts: List<Float>, x: Float, y: Float): Float {
    if (pts.size < 2) return Float.MAX_VALUE
    if (pts.size < 4) return hypot(pts[0] - x, pts[1] - y)
    var best = Float.MAX_VALUE
    var i = 0
    while (i + 3 < pts.size) {
        val ax = pts[i]; val ay = pts[i + 1]; val bx = pts[i + 2]; val by = pts[i + 3]
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 == 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0f, 1f)
        best = minOf(best, hypot(ax + t * dx - x, ay + t * dy - y))
        i += 2
    }
    return best
}

/** The drawing layer. Only takes touches while drawing, so typing still works underneath. */
@Composable
private fun InkPage(
    ink: MutableList<Stroke>, active: Boolean, argb: Int, width: Float, style: Int, eraser: Boolean,
    modifier: Modifier, onChange: () -> Unit,
) {
    val live = remember { mutableStateListOf<Float>() }
    val input = if (!active) Modifier else Modifier
        .pointerInput(argb, width, style, eraser) {
            detectTapGestures { p ->
                val w = size.width.toFloat()
                if (eraser) { if (eraseAt(ink, p.x / w, p.y / w, 0.02f)) onChange() }
                else { ink.add(Stroke(argb, width, SOLID, listOf(p.x / w, p.y / w))); onChange() }
            }
        }
        .pointerInput(argb, width, style, eraser) {
            detectDragGestures(
                onDragStart = { p -> val w = size.width.toFloat(); live.clear(); if (eraser) eraseAt(ink, p.x / w, p.y / w, 0.02f) else { live.add(p.x / w); live.add(p.y / w) } },
                onDragEnd = { if (!eraser && live.size >= 2) ink.add(Stroke(argb, width, style, live.toList())); live.clear(); onChange() },
                onDragCancel = { live.clear() },
            ) { ch, _ ->
                ch.consume()
                val w = size.width.toFloat()
                val p = ch.position
                if (eraser) eraseAt(ink, p.x / w, p.y / w, 0.02f)
                else {
                    // skip points closer than half a pen width: smaller files, smoother lines
                    val n = live.size
                    if (n < 2 || hypot(p.x / w - live[n - 2], p.y / w - live[n - 1]) > width * 0.5f) { live.add(p.x / w); live.add(p.y / w) }
                }
            }
        }
    Canvas(modifier.then(input)) {
        drawInk(ink)
        if (live.size >= 2) drawInk(listOf(Stroke(argb, width, style, live.toList())))
    }
}
