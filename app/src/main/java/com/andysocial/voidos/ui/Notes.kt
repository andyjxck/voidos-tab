package com.andysocial.voidos.ui

import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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

internal const val SOLID = 0
internal const val DOTTED = 1
internal const val DASHED = 2

/**
 * Notes, laid out like Notes on an iPad: in landscape the list sits on the left
 * and the open page fills the rest; upright, the list fills the screen and a
 * note opens full screen with a way back. The first line of a note is its
 * title. The pen turns the page into a drawing surface with a floating tool
 * pill (undo, redo, pen, highlighter, eraser, colour wheel, any size, brush
 * menu with line styles, shapes, smoothing and paper, clear; NotesTools.kt). A note goes on Home, and gets its colour, from Home's own "Add to face" menu.
 * Everything saves as you go.
 */
@Composable
fun NotesScreen(store: Store) {
    val portrait = LocalPortrait.current
    val appFilter = NotesNav.app.value
    var openId by remember { mutableLongStateOf(if (portrait) 0L else store.notes.firstOrNull()?.id ?: 0L) }
    var drawing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val exists = store.notes.any { it.id == openId }

    fun create(sketch: Boolean) {
        val id = System.currentTimeMillis()
        NotesNav.newApp = appFilter.takeIf { it != NotesNav.ALL } ?: ""
        store.saveNote(Note(id, "", 0, id, app = appFilter.takeIf { it != NotesNav.ALL } ?: "")); openId = id; drawing = sketch; query = ""
    }

    if (portrait) {
        if (exists) NotePage(store, openId, drawing, { drawing = it }, Modifier.fillMaxSize(),
            onBack = { openId = 0L; drawing = false }, onDeleted = { openId = 0L; drawing = false })
        else NoteList(store, query, { query = it }, null, { openId = it; drawing = false }, ::create, Modifier.fillMaxSize())
    } else {
        Row(Modifier.fillMaxSize()) {
            NoteList(store, query, { query = it }, openId, { openId = it; drawing = false }, ::create, Modifier.width(330.dp).fillMaxHeight())
            Gap(w = 28.dp)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (exists) NotePage(store, openId, drawing, { drawing = it }, Modifier.fillMaxSize(), onBack = null,
                    onDeleted = { openId = store.notes.firstOrNull()?.id ?: 0L; drawing = false })
                else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Glyph(G.NOTES, V.Tick, Modifier.size(44.dp))
                    Gap(h = 12.dp)
                    VText("No note open", size = Type.headline, color = V.Muted)
                }
            }
        }
    }
}

/**
 * 2.0: which app's notes are showing. ALL shows every note grouped by app; "" is General
 * (notes not tied to an app); anything else is a hub app id. The status board's "Notes" button
 * sets this before switching to Notes.
 */
object NotesNav {
    const val ALL = "__all"
    val app = mutableStateOf(ALL)
    /** The app a note made right now belongs to. */
    var newApp = ""

    /** The hub's apps and sites, then any app a note still names that the hub no longer lists. */
    fun apps(store: Store): List<Pair<String, String>> {
        val hub = com.andysocial.voidos.hub.HubRepo.summary?.apps.orEmpty().map { it.id to it.name }
        val extra = store.notes.map { it.app }.filter { a -> a.isNotEmpty() && hub.none { it.first == a } }.distinct()
            .map { it to it.substringAfter(':').replaceFirstChar { c -> c.titlecase() } }
        return hub + extra
    }
}

/** The list: title, count, search, app chips, then notes grouped by app (All) or by day, newest first. */
@Composable
private fun NoteList(
    store: Store, query: String, onQuery: (String) -> Unit, selected: Long?,
    onOpen: (Long) -> Unit, onNew: (Boolean) -> Unit, modifier: Modifier,
) {
    val portrait = LocalPortrait.current
    val appFilter = NotesNav.app.value
    val apps = NotesNav.apps(store)
    val shown = store.notes
        .filter { appFilter == NotesNav.ALL || it.app == appFilter }
        .filter { query.isBlank() || it.text.contains(query.trim(), ignoreCase = true) }
        .sortedByDescending { it.updated }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            RoundIcon(G.PEN, "Sketch") { onNew(true) }
            Gap(w = 8.dp)
            RoundIcon(G.COMPOSE, "New note", active = true) { onNew(false) }
        }
        Gap(h = 16.dp)
        SearchPill(query, onQuery)
        Gap(h = 10.dp)
        // 2.0: notes are organised by app. A chip per app (from the hub), plus General for the rest.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            (listOf(NotesNav.ALL to "All", "" to "General") + apps).forEach { (id, name) ->
                val n = if (id == NotesNav.ALL) store.notes.size else store.notes.count { it.app == id }
                Chip(if (n > 0) "$name  $n" else name, appFilter == id, V.Yellow, Modifier.padding(end = 6.dp)) { NotesNav.app.value = id }
            }
        }
        Gap(h = 4.dp)
        if (shown.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                VText(if (query.isBlank()) "No notes yet" else "No matches", size = Type.headline, color = V.Muted)
                if (query.isBlank()) {
                    Gap(h = 8.dp)
                    VText("Tap the pencil to write one, or the pen to sketch.", size = Type.label, color = V.Tick, align = TextAlign.Center)
                }
            }
            return@Column
        }
        val names = apps.toMap()
        val groups = if (appFilter == NotesNav.ALL) shown.groupBy { names[it.app] ?: if (it.app.isEmpty()) "General" else it.app.substringAfter(':') }
            else shown.groupBy { dayBucket(it.updated) }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            groups.forEach { (bucket, list) ->
                item(key = "h-$bucket") {
                    VText(bucket, size = Type.body, font = SansMedium, color = V.Text, modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp))
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
            .background(GroupFill)
            .background(Brush.horizontalGradient(listOf(V.Yellow.copy(alpha = if (selected) 0.20f else 0f), V.Coral.copy(alpha = if (selected) 0.06f else 0f))))
            .clickable(onClick = onClick),
    ) {
        if (!first) Spacer(Modifier.height(4.dp))
        Row(Modifier.padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                VText(title, size = Type.headline, font = SansMedium, color = V.White, maxLines = 2)
                Gap(h = 4.dp)
                Row {
                    VText(shortWhen(n.updated), size = Type.label, color = V.Text.copy(alpha = 0.75f), maxLines = 1)
                    Gap(w = 8.dp)
                    VText(rest, size = Type.label, color = V.Muted, maxLines = 1)
                }
            }
            if (n.ink.isNotEmpty()) {
                Gap(w = 8.dp)
                InkPreview(n.ink, Modifier.size(42.dp).clip(Radius.Chip).background(Color.White.copy(alpha = 0.05f)).padding(4.dp))
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
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val prefs = remember { InkPrefs(ctx) }
    var text by remember(id) { mutableStateOf(open.text) }
    // the colour is set from Home's "Add to face" menu, so always read the saved one
    val colour = open.colour
    val ink = remember(id) { mutableStateListOf<Stroke>().also { it.addAll(open.ink) } }
    var inkVersion by remember(id) { mutableIntStateOf(0) }
    val history = remember(id) { InkHistory() }
    // the pen: remembered between notes and restarts
    var tool by remember { mutableIntStateOf(TOOL_PEN) }
    var rgb by remember { mutableIntStateOf(prefs.rgb) }
    var opacity by remember { mutableFloatStateOf(prefs.opacity) }
    var penSize by remember { mutableFloatStateOf(prefs.penSize) }
    var hlSize by remember { mutableFloatStateOf(prefs.highlighterSize) }
    var eraserSize by remember { mutableFloatStateOf(prefs.eraserSize) }
    var style by remember { mutableIntStateOf(prefs.style.takeIf { it in LineStyles } ?: SOLID) }
    var shape by remember { mutableIntStateOf(prefs.shape.takeIf { it in Shapes } ?: SHAPE_FREE) }
    var smooth by remember { mutableStateOf(prefs.smooth) }
    var recent by remember { mutableStateOf(prefs.recent) }
    var paper by remember(id) { mutableIntStateOf(prefs.paper(id)) }
    var panel by remember { mutableStateOf(InkPanel.NONE) }
    var picking by remember { mutableStateOf(false) }
    var clearArmed by remember(id) { mutableStateOf(false) }
    var pageWidthDp by remember { mutableFloatStateOf(600f) }

    // autosave 600 ms after typing or drawing stops
    LaunchedEffect(text, colour, id, inkVersion) {
        delay(600)
        val o = store.notes.firstOrNull { it.id == id } ?: return@LaunchedEffect
        if (text != o.text || colour != o.colour || ink.toList() != o.ink) {
            store.saveNote(Note(id, text, colour, System.currentTimeMillis(), ink.toList(), store.notes.firstOrNull { it.id == id }?.app ?: NotesNav.newApp))
        }
    }
    // Clear needs a second tap within a moment, and can still be undone
    LaunchedEffect(clearArmed) { if (clearArmed) { delay(2500); clearArmed = false } }
    LaunchedEffect(drawing) { if (!drawing) { panel = InkPanel.NONE; picking = false; clearArmed = false } }

    fun edited(e: InkEdit?) { if (e != null) history.record(e); clearArmed = false; inkVersion++ }
    val size = when (tool) { TOOL_ERASER -> eraserSize; TOOL_HIGHLIGHTER -> hlSize; else -> penSize }
    fun setSize(v: Float) = when (tool) {
        TOOL_ERASER -> { eraserSize = v; prefs.eraserSize = v }
        TOOL_HIGHLIGHTER -> { hlSize = v; prefs.highlighterSize = v }
        else -> { penSize = v; prefs.penSize = v }
    }
    fun setColour(c: Int) {
        rgb = c or (0xFF shl 24); prefs.rgb = rgb
        if (tool == TOOL_ERASER) tool = TOOL_PEN
    }
    fun keepRecent() { recent = pushRecent(recent, rgb); prefs.recent = recent }
    fun openPanel(p: InkPanel) { if (panel == InkPanel.COLOUR && p != InkPanel.COLOUR) keepRecent(); panel = p; picking = false }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) BackLink("Notes", onBack)
            Spacer(Modifier.weight(1f))
            // Home and its colour live in Home's own "Add to face" menu, so they're not repeated here
            RoundIcon(G.PEN, if (drawing) "Done drawing" else "Draw", active = drawing) { setDrawing(!drawing) }
            Gap(w = 8.dp)
            RoundIcon(G.TRASH, "Delete note", tint = V.Coral) {
                store.widgets.filter { it.kind == "note" && it.ref == id }.forEach { store.removeWidget(it.id) }
                store.deleteNote(id); onDeleted()
            }
        }
        Gap(h = 8.dp)
        val words = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        VText(
            longWhen(open.updated) + if (words > 0) "  \u00b7  $words word${if (words == 1) "" else "s"}" else "",
            size = Type.caption, color = V.Muted, align = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Gap(h = 8.dp)
        // the page: a soft glass sheet, and nothing draws outside it
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(PageShape).background(Color.White.copy(alpha = 0.035f))
                .onSizeChanged { pageWidthDp = with(density) { it.width.toDp().value } },
        ) {
            val lineStep = with(density) { 28.sp.toPx() }
            val top = with(density) { 10.dp.toPx() }
            if (paper != PAPER_PLAIN) Canvas(Modifier.fillMaxSize()) { drawPaper(paper, lineStep, top) }
            if (text.isEmpty() && !drawing) VText(if (ink.isEmpty()) "Title" else "", size = Type.large, font = SansMedium, color = V.Tick, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                enabled = !drawing,
                textStyle = TextStyle(color = V.Text, fontSize = 18.sp, lineHeight = 28.sp, fontFamily = Sans),
                visualTransformation = TitleFirstLine,
                cursorBrush = SolidColor(V.Teal),
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
            )
            InkPage(
                ink, drawing, inkArgb(tool, rgb, opacity), size, if (tool == TOOL_HIGHLIGHTER) SOLID else style, tool, shape, smooth, picking,
                Modifier.fillMaxSize(),
                onEdit = { edited(it) },
                onPicked = { c -> picking = false; if (c != null) { setColour(c); keepRecent() }; panel = InkPanel.COLOUR },
            )
            if (drawing) {
                val v = inkVersion // history isn't state: read the version so the buttons follow it
                if (panel != InkPanel.NONE) {
                    // tap anywhere on the page to put the panel away
                    Box(Modifier.fillMaxSize().semantics { contentDescription = "Close panel" }.clickable(remember { MutableInteractionSource() }, indication = null) { openPanel(InkPanel.NONE) })
                    InkCard(Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 66.dp)) {
                        when (panel) {
                            InkPanel.COLOUR -> ColourPanel(rgb, opacity, recent, picking,
                                onColour = { setColour(it) }, onOpacity = { opacity = it; prefs.opacity = it },
                                onPick = { keepRecent(); picking = true; panel = InkPanel.NONE }, onDone = { openPanel(InkPanel.NONE) })
                            InkPanel.SIZE -> SizePanel(tool, size, rgb, opacity, style, pageWidthDp) { setSize(it) }
                            InkPanel.BRUSH -> BrushPanel(style, shape, smooth, paper,
                                onStyle = { style = it; prefs.style = it; if (tool == TOOL_ERASER) tool = TOOL_PEN },
                                onShape = { shape = it; prefs.shape = it; if (tool == TOOL_ERASER) tool = TOOL_PEN },
                                onSmooth = { smooth = it; prefs.smooth = it },
                                onPaper = { paper = it; prefs.setPaper(id, it) })
                            InkPanel.NONE -> {}
                        }
                    }
                }
                InkBar(
                    tool, rgb, opacity, size, "%.1f".format(size * pageWidthDp), panel,
                    canUndo = v >= 0 && history.canUndo(ink), canRedo = history.canRedo, canClear = ink.isNotEmpty(), clearArmed = clearArmed,
                    onTool = { tool = it; picking = false; clearArmed = false },
                    onPanel = { openPanel(it) },
                    onUndo = { if (history.undo(ink)) { clearArmed = false; inkVersion++ } },
                    onRedo = { if (history.redo(ink)) { clearArmed = false; inkVersion++ } },
                    onClear = {
                        if (!clearArmed) clearArmed = true
                        else { val e = InkEdit.Clear(ink.toList()); e.applyTo(ink); edited(e) }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp, start = 8.dp, end = 8.dp),
                )
            }
        }
    }
}

private val PageShape = Chamfer(Radius.panel)

/** Dotted: round dots one pen-width apart. Dashed: dashes three widths long. */
internal fun effectFor(style: Int, widthPx: Float): PathEffect? = when (style) {
    DOTTED -> PathEffect.dashPathEffect(floatArrayOf(0.01f, widthPx * 2.2f))
    DASHED -> PathEffect.dashPathEffect(floatArrayOf(widthPx * 3f, widthPx * 2.5f))
    SPARSE_DOTS -> PathEffect.dashPathEffect(floatArrayOf(0.01f, widthPx * 4.5f))
    LONG_DASH -> PathEffect.dashPathEffect(floatArrayOf(widthPx * 7f, widthPx * 3f))
    DASH_DOT -> PathEffect.dashPathEffect(floatArrayOf(widthPx * 4f, widthPx * 2.2f, 0.01f, widthPx * 2.2f))
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

/**
 * The drawing layer. Only takes touches while drawing, so typing still works
 * underneath. Ink is clipped to the page and every point is kept on it, so a
 * line dragged past the edge runs along the edge instead of off the note.
 */
@Composable
private fun InkPage(
    ink: MutableList<Stroke>, active: Boolean, argb: Int, width: Float, style: Int, tool: Int, shape: Int, smooth: Boolean,
    picking: Boolean, modifier: Modifier, onEdit: (InkEdit?) -> Unit, onPicked: (Int?) -> Unit,
) {
    val live = remember { mutableStateListOf<Float>() }
    var cursor by remember { mutableStateOf<Offset?>(null) }
    val eraser = tool == TOOL_ERASER
    val input = if (!active) Modifier else Modifier
        .pointerInput(argb, width, style, tool, shape, smooth, picking) {
            detectTapGestures { p ->
                val w = size.width.toFloat()
                val (x, y) = clampToPage(p.x / w, p.y / w, size.height / w)
                when {
                    picking -> onPicked(ink.filter { strokeDistance(it.pts, x, y) < 0.03f + it.width / 2 }
                        .minByOrNull { strokeDistance(it.pts, x, y) }?.argb)
                    eraser -> eraseIndexed(ink, x, y, width)?.let { onEdit(it) }
                    else -> { val s = Stroke(argb, width, SOLID, listOf(x, y)); ink.add(s); onEdit(InkEdit.Add(s)) }
                }
            }
        }
        .pointerInput(argb, width, style, tool, shape, smooth, picking) {
            if (picking) return@pointerInput
            val erased = ArrayList<InkEdit>()
            var x0 = 0f; var y0 = 0f
            detectDragGestures(
                onDragStart = { p ->
                    val w = size.width.toFloat()
                    val (x, y) = clampToPage(p.x / w, p.y / w, size.height / w)
                    live.clear(); erased.clear(); x0 = x; y0 = y
                    if (eraser) { cursor = p; eraseIndexed(ink, x, y, width)?.let { erased.add(it) } }
                    else { live.add(x); live.add(y) }
                },
                onDragEnd = {
                    cursor = null
                    if (eraser) { if (erased.isNotEmpty()) onEdit(InkEdit.Batch(erased.toList())) }
                    else if (live.size >= 2) {
                        val pts = if (shape == SHAPE_FREE && smooth) smoothPoints(live.toList()) else live.toList()
                        val s = Stroke(argb, width, style, pts); ink.add(s); onEdit(InkEdit.Add(s))
                    }
                    live.clear(); erased.clear()
                },
                onDragCancel = {
                    cursor = null; live.clear()
                    if (erased.isNotEmpty()) onEdit(InkEdit.Batch(erased.toList())); erased.clear()
                },
            ) { ch, _ ->
                ch.consume()
                val w = size.width.toFloat()
                val (x, y) = clampToPage(ch.position.x / w, ch.position.y / w, size.height / w)
                when {
                    eraser -> { cursor = Offset(x * w, y * w); eraseIndexed(ink, x, y, width)?.let { erased.add(it) } }
                    shape != SHAPE_FREE -> { live.clear(); live.addAll(shapePoints(shape, x0, y0, x, y)) }
                    else -> {
                        // skip points closer than half a pen width: smaller files, smoother lines
                        val n = live.size
                        if (n < 2 || hypot(x - live[n - 2], y - live[n - 1]) > width * 0.5f) { live.add(x); live.add(y) }
                    }
                }
            }
        }
    Canvas(modifier.clipToBounds().then(input)) {
        drawInk(ink)
        if (live.size >= 2) drawInk(listOf(Stroke(argb, width, style, live.toList())))
        cursor?.let { c ->
            drawCircle(Color.Black.copy(alpha = 0.35f), width * size.width, c, style = PenStroke(3.dp.toPx()))
            drawCircle(Color.White.copy(alpha = 0.8f), width * size.width, c, style = PenStroke(1.5.dp.toPx()))
        }
    }
}
