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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andysocial.voidos.Note
import com.andysocial.voidos.Store
import com.andysocial.voidos.Stroke
import kotlinx.coroutines.delay
import kotlin.math.hypot

/** Pen colours: white, then the six watch colours. */
private val Inks: List<Color> get() = listOf(V.White) + V.Lap

/** Pen sizes as a fraction of the page width: fine, medium, bold, marker. */
internal val PenSizes = listOf(0.003f, 0.006f, 0.012f, 0.024f)

internal const val SOLID = 0
internal const val DOTTED = 1
internal const val DASHED = 2

/**
 * Notes, laid out like Notes on an iPad: in landscape the list sits on the left
 * and the open page fills the rest; upright, the list fills the screen and a
 * note opens full screen with a way back. The first line of a note is its
 * title. The pen turns the page into a drawing surface with a floating tool
 * capsule (seven colours, four sizes, solid, dotted or dashed lines, eraser,
 * undo, clear). The Home button puts the note on the face as a widget.
 * Everything saves as you go.
 */
@Composable
fun NotesScreen(store: Store) {
    val portrait = LocalPortrait.current
    var openId by remember { mutableLongStateOf(if (portrait) 0L else store.notes.firstOrNull()?.id ?: 0L) }
    var drawing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val exists = store.notes.any { it.id == openId }

    fun create(sketch: Boolean) {
        val id = System.currentTimeMillis()
        store.saveNote(Note(id, "", 0, id)); openId = id; drawing = sketch; query = ""
    }

    if (portrait) {
        if (exists) NotePage(store, openId, drawing, { drawing = it }, Modifier.fillMaxSize(),
            onBack = { openId = 0L; drawing = false }, onDeleted = { openId = 0L; drawing = false })
        else NoteList(store, query, { query = it }, null, { openId = it; drawing = false }, ::create, Modifier.fillMaxSize())
    } else {
        Row(Modifier.fillMaxSize()) {
            NoteList(store, query, { query = it }, openId, { openId = it; drawing = false }, ::create, Modifier.width(330.dp).fillMaxHeight())
            Gap(w = 22.dp)
            Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 8.dp).background(Color.White.copy(alpha = 0.06f)))
            Gap(w = 22.dp)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (exists) NotePage(store, openId, drawing, { drawing = it }, Modifier.fillMaxSize(), onBack = null,
                    onDeleted = { openId = store.notes.firstOrNull()?.id ?: 0L; drawing = false })
                else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Glyph(G.NOTES, V.Tick, Modifier.size(44.dp))
                    Gap(h = 12.dp)
                    VText("No note open", size = 17.sp, color = V.Muted)
                }
            }
        }
    }
}

/** The list: title, count, search, then notes grouped by day, newest first. */
@Composable
private fun NoteList(
    store: Store, query: String, onQuery: (String) -> Unit, selected: Long?,
    onOpen: (Long) -> Unit, onNew: (Boolean) -> Unit, modifier: Modifier,
) {
    val portrait = LocalPortrait.current
    val shown = store.notes
        .filter { query.isBlank() || it.text.contains(query.trim(), ignoreCase = true) }
        .sortedByDescending { it.updated }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PageTitle("Notes", portrait)
                VText(
                    when (val n = store.notes.size) { 0 -> "No notes"; 1 -> "1 note"; else -> "$n notes" },
                    size = 13.sp, color = V.Muted,
                )
            }
            RoundIcon(G.PEN, "Sketch") { onNew(true) }
            Gap(w = 10.dp)
            RoundIcon(G.COMPOSE, "New note", active = true) { onNew(false) }
        }
        Gap(h = 14.dp)
        SearchPill(query, onQuery)
        Gap(h = 6.dp)
        if (shown.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                VText(if (query.isBlank()) "No notes yet" else "No matches", size = 17.sp, color = V.Muted)
                if (query.isBlank()) {
                    Gap(h = 6.dp)
                    VText("Tap the pencil to write one, or the pen to sketch.", size = 13.sp, color = V.Tick, align = TextAlign.Center)
                }
            }
            return@Column
        }
        val groups = shown.groupBy { dayBucket(it.updated) }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            groups.forEach { (bucket, list) ->
                item(key = "h-$bucket") {
                    VText(bucket, size = 15.sp, font = SansMedium, color = V.Text, modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp))
                }
                itemsIndexed(list, key = { _, n -> n.id }) { i, n ->
                    NoteRow(n, n.id == selected, first = i == 0, last = i == list.lastIndex) { onOpen(n.id) }
                }
            }
        }
    }
}

@Composable
private fun NoteRow(n: Note, selected: Boolean, first: Boolean, last: Boolean, onClick: () -> Unit) {
    val lines = n.text.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
    val title = lines.firstOrNull() ?: if (n.ink.isNotEmpty()) "Sketch" else "New note"
    val rest = lines.drop(1).joinToString(" ").ifEmpty { if (n.ink.isNotEmpty()) "Drawing" else "No additional text" }
    val shape = groupShape(first, last)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) V.Teal.copy(alpha = 0.16f) else GroupFill)
            .clickable(onClick = onClick),
    ) {
        if (!first) Box(Modifier.padding(start = 18.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.06f)))
        Row(Modifier.padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                VText(title, size = 16.sp, font = SansMedium, color = V.White, maxLines = 1)
                Gap(h = 3.dp)
                Row {
                    VText(shortWhen(n.updated), size = 13.sp, color = V.Text.copy(alpha = 0.75f), maxLines = 1)
                    Gap(w = 8.dp)
                    VText(rest, size = 13.sp, color = V.Muted, maxLines = 1)
                }
            }
            if (n.ink.isNotEmpty()) {
                Gap(w = 10.dp)
                InkPreview(n.ink, Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.05f)).padding(4.dp))
            }
        }
    }
}

/** First line as a title: larger and heavier, the rest as body text. */
private object TitleFirstLine : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val end = text.text.indexOf('\n').let { if (it < 0) text.length else it }
        val styled = AnnotatedString.Builder(text).apply {
            if (end > 0) addStyle(SpanStyle(fontSize = 28.sp, fontFamily = SansMedium, color = V.White), 0, end)
        }.toAnnotatedString()
        return TransformedText(styled, OffsetMapping.Identity)
    }
}

/** One note, open: a slim toolbar, the date, then the page with ink on top. */
@Composable
private fun NotePage(
    store: Store, id: Long, drawing: Boolean, setDrawing: (Boolean) -> Unit, modifier: Modifier,
    onBack: (() -> Unit)?, onDeleted: () -> Unit,
) {
    val open = store.notes.firstOrNull { it.id == id } ?: return
    var text by remember(id) { mutableStateOf(open.text) }
    var colour by remember(id) { mutableIntStateOf(open.colour) }
    val ink = remember(id) { mutableStateListOf<Stroke>().also { it.addAll(open.ink) } }
    var inkVersion by remember(id) { mutableIntStateOf(0) }
    var pen by remember { mutableIntStateOf(0) }
    var penSize by remember { mutableIntStateOf(1) }
    var style by remember { mutableIntStateOf(SOLID) }
    var eraser by remember { mutableStateOf(false) }
    val onHome = store.widgets.any { it.kind == "note" && it.ref == id }

    // autosave 600 ms after typing or drawing stops
    LaunchedEffect(text, colour, id, inkVersion) {
        delay(600)
        val o = store.notes.firstOrNull { it.id == id } ?: return@LaunchedEffect
        if (text != o.text || colour != o.colour || ink.toList() != o.ink) {
            store.saveNote(Note(id, text, colour, System.currentTimeMillis(), ink.toList()))
        }
    }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) BackLink("Notes", onBack)
            Spacer(Modifier.weight(1f))
            // the note's colour: only used for its widget on Home, so it's one small dot that cycles
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.07f))
                    .semantics { contentDescription = "Widget colour" }
                    .clickable { colour = (colour + 1) % V.Lap.size },
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(14.dp).clip(CircleShape).background(V.Lap[colour % V.Lap.size])) }
            Gap(w = 10.dp)
            RoundIcon(G.FACE, if (onHome) "Remove from Home" else "Show on Home", active = onHome) {
                if (onHome) store.widgets.filter { it.kind == "note" && it.ref == id }.forEach { store.removeWidget(it.id) }
                else store.addWidget("note", id)
            }
            Gap(w = 10.dp)
            RoundIcon(G.PEN, if (drawing) "Done drawing" else "Draw", active = drawing) { setDrawing(!drawing); eraser = false }
            Gap(w = 10.dp)
            RoundIcon(G.TRASH, "Delete note", tint = V.Coral) {
                store.widgets.filter { it.kind == "note" && it.ref == id }.forEach { store.removeWidget(it.id) }
                store.deleteNote(id); onDeleted()
            }
        }
        Gap(h = 8.dp)
        val words = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        VText(
            longWhen(open.updated) + if (words > 0) "  \u00b7  $words word${if (words == 1) "" else "s"}" else "",
            size = 12.sp, color = V.Muted, align = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Gap(h = 10.dp)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (text.isEmpty() && !drawing) VText(if (ink.isEmpty()) "Title" else "", size = 28.sp, font = SansMedium, color = V.Tick, modifier = Modifier.padding(horizontal = 4.dp))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                enabled = !drawing,
                textStyle = TextStyle(color = V.Text, fontSize = 18.sp, lineHeight = 28.sp, fontFamily = Sans),
                visualTransformation = TitleFirstLine,
                cursorBrush = SolidColor(V.Teal),
                modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
            )
            InkPage(ink, drawing, Inks[pen].toArgb(), PenSizes[penSize], style, eraser, Modifier.fillMaxSize()) { inkVersion++ }
            if (drawing) {
                PenCapsule(
                    pen, penSize, style, eraser, ink.isNotEmpty(),
                    onPen = { pen = it; eraser = false }, onSize = { penSize = it }, onStyle = { style = it; eraser = false },
                    onEraser = { eraser = !eraser },
                    onUndo = { if (ink.isNotEmpty()) { ink.removeAt(ink.lastIndex); inkVersion++ } },
                    onClear = { ink.clear(); inkVersion++ },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                )
            }
        }
    }
}

/** The drawing tools in one floating glass capsule: undo, colours, sizes, line styles, eraser, clear. */
@Composable
private fun PenCapsule(
    pen: Int, penSize: Int, style: Int, eraser: Boolean, hasInk: Boolean,
    onPen: (Int) -> Unit, onSize: (Int) -> Unit, onStyle: (Int) -> Unit, onEraser: () -> Unit, onUndo: () -> Unit, onClear: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier.widthIn(max = 820.dp).clip(Pill)
            .background(Brush.verticalGradient(listOf(Color(0xFF2A2A2F).copy(alpha = 0.94f), Color(0xFF1A1A1E).copy(alpha = 0.94f))))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f))), Pill)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PenTool("Undo", false, hasInk, onUndo) { c -> VText("\u21b6", size = 22.sp, color = c) }
        Hair()
        Inks.forEachIndexed { i, c ->
            val on = i == pen && !eraser
            Box(
                Modifier.size(36.dp).clip(CircleShape).semantics { contentDescription = "Colour ${i + 1}" }.clickable { onPen(i) },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(if (on) 28.dp else 22.dp).clip(CircleShape).border(2.dp, if (on) V.White else Color.Transparent, CircleShape).padding(if (on) 4.dp else 0.dp).clip(CircleShape).background(c))
            }
        }
        Hair()
        PenSizes.indices.forEach { i ->
            val on = i == penSize
            PenTool("Size ${i + 1}", on, true, { onSize(i) }) { c -> Box(Modifier.size((4 + i * 4).dp).clip(CircleShape).background(c)) }
        }
        Hair()
        listOf(SOLID to "Solid", DOTTED to "Dotted", DASHED to "Dashed").forEach { (s, name) ->
            PenTool(name, s == style && !eraser, true, { onStyle(s) }, wide = true) { c ->
                Canvas(Modifier.size(width = 26.dp, height = 6.dp)) {
                    drawLine(c, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 3.dp.toPx(), StrokeCap.Round, effectFor(s, 3.dp.toPx()))
                }
            }
        }
        Hair()
        TextTool("Eraser", eraser, true, onEraser)
        TextTool("Clear", false, hasInk, onClear, warn = true)
    }
}

@Composable
private fun Hair() = Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(22.dp).background(Color.White.copy(alpha = 0.10f)))

@Composable
private fun PenTool(desc: String, on: Boolean, enabled: Boolean, onClick: () -> Unit, wide: Boolean = false, content: @Composable (Color) -> Unit) {
    val c = when { !enabled -> V.Tick; on -> V.Teal; else -> V.Text.copy(alpha = 0.8f) }
    Box(
        Modifier.size(width = if (wide) 44.dp else 36.dp, height = 36.dp).clip(Pill)
            .background(if (on) V.Teal.copy(alpha = 0.18f) else Color.Transparent)
            .semantics { contentDescription = desc }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content(c) }
}

@Composable
private fun TextTool(label: String, on: Boolean, enabled: Boolean, onClick: () -> Unit, warn: Boolean = false) {
    val c = when { !enabled -> V.Tick; warn -> V.Coral; on -> V.Teal; else -> V.Text }
    Box(
        Modifier.height(36.dp).clip(Pill).background(if (on) V.Teal.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { VText(label, size = 14.sp, color = c, maxLines = 1) }
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
