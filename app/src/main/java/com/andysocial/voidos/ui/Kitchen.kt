package com.andysocial.voidos.ui

import androidx.compose.ui.input.pointer.pointerInput

import androidx.compose.ui.platform.testTag

import androidx.compose.ui.semantics.contentDescription

import androidx.compose.ui.semantics.semantics

import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.lerp

import androidx.compose.ui.graphics.drawscope.rotate

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andysocial.voidos.KTimer
import com.andysocial.voidos.KTimers
import com.andysocial.voidos.Store
import com.andysocial.voidos.VoidApp
import com.andysocial.voidos.Share
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

enum class KTab(val label: String, val glyph: G) {
    TIMERS("Timers", G.TIMER), STOPWATCH("Stopwatch", G.STOPWATCH), ALARMS("Alarms", G.BELL), WORLD("World", G.WORLD),
    CALENDAR("Calendar", G.CALENDAR), CONVERT("Convert", G.CONVERT), LIST("List", G.LIST), MEMO("Memos", G.MIC),
    LEVEL("Level", G.LEVEL), METER("Sound", G.METER), SCAN("QR scan", G.QR), MIRROR("Mirror", G.MIRROR),
    ZOOM("Magnifier", G.ZOOM), TORCH("Torch", G.TORCH),
    FILES("Files", G.FOLDER), DOCSCAN("Doc scan", G.DOC), CALC("Calculator", G.CALC),
}

/** The four groups on the Tools screen, each with its tools in order. */
enum class KGroup(val label: String, val tabs: List<KTab>) {
    TIME("Time", listOf(KTab.TIMERS, KTab.STOPWATCH, KTab.ALARMS, KTab.WORLD)),
    ORGANISE("Organise", listOf(KTab.CALENDAR, KTab.LIST, KTab.MEMO, KTab.FILES)),
    CAMERA("Camera", listOf(KTab.SCAN, KTab.DOCSCAN, KTab.ZOOM, KTab.MIRROR)),
    UTILITIES("Utilities", listOf(KTab.CALC, KTab.CONVERT, KTab.LEVEL, KTab.METER, KTab.TORCH));

    /** The page this group lives on: Time and Organise on Tools, Camera and Utilities each on their own. */
    val page: Module get() = when (this) {
        TIME, ORGANISE -> Module.TOOLS
        CAMERA -> Module.CAMERA
        UTILITIES -> Module.UTILITIES
    }

    companion object {
        fun of(t: KTab): KGroup = entries.firstOrNull { t in it.tabs } ?: UTILITIES
        /** The groups shown on one page. */
        fun on(page: Module): List<KGroup> = entries.filter { it.page == page }
    }
}

/** One tool's name in the row under the group bar: no pill, no border; the chosen one is bright. */
@Composable
private fun ToolName(t: KTab, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(Pill).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(t.glyph, if (on) V.Teal else V.Muted, Modifier.size(16.dp))
        Gap(w = 6.dp)
        VText(t.label, size = Type.body, font = if (on) SansMedium else Sans, color = if (on) V.White else V.Text.copy(alpha = 0.6f), maxLines = 1)
    }
}

/** The tools that open over the whole screen, edge to edge. */
val FULL_TOOLS = setOf(KTab.MIRROR, KTab.TORCH)

/** The tool open over the whole screen right now, if any. */
val fullTool = mutableStateOf<KTab?>(null)

/** The mirror or the torch, edge to edge over everything, with one glass button to close it. */
@Composable
fun FullToolOverlay() {
    val t = fullTool.value ?: return
    Box(
        Modifier.fillMaxSize().background(Color.Black).testTag("full_tool")
            // nothing under it moves while it's open
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
    ) {
        when (t) {
            KTab.MIRROR -> CameraPane(CamMode.MIRROR, edge = true)
            else -> LightPane(edge = true)
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(20.dp).size(52.dp).glass(CircleShape, 0.7f)
                .clickable { fullTool.value = null }.semantics { contentDescription = "Close ${t.label}" },
            contentAlignment = Alignment.Center,
        ) { Glyph(G.CLOSE, V.White, Modifier.size(24.dp)) }
    }
}

/** A tool another screen asked to open (the control centre's Cast, Files...). */
val pendingTool = mutableStateOf<KTab?>(null)

fun openTool(t: KTab) {
    pendingTool.value = t
    val page = KGroup.of(t).page
    Idle.openModule.value = page.name
    // from over another app: bring VoidOS to the front as well
    val app = VoidApp.instance
    runCatching {
        app.startActivity(
            android.content.Intent(app, com.andysocial.voidos.MainActivity::class.java)
                .putExtra(com.andysocial.voidos.MainActivity.EXTRA_MODULE, page.name)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }
}

/**
 * A tools page. Tools has Time and Organise, picked with one glass bar; Camera and
 * Utilities are pages of their own, so they only need the quiet row of tool names.
 */
@Composable
fun ToolsScreen(store: Store, page: Module = Module.TOOLS) {
    val groups = remember(page) { KGroup.on(page).ifEmpty { KGroup.on(Module.TOOLS) } }
    val mine = remember(groups) { groups.flatMap { it.tabs }.toSet() }
    var tab by rememberSaveable(page) { mutableStateOf(groups.first().tabs.first()) }
    val wantTool by pendingTool
    // a tool asked for from elsewhere opens on the page it lives on, wherever else Tools is showing
    LaunchedEffect(wantTool) { wantTool?.takeIf { it in mine }?.let { tab = it; pendingTool.value = null } }
    // the last tool used in each group, so switching group comes back to where you were
    val lastIn = remember { mutableStateMapOf<KGroup, KTab>() }
    val group = KGroup.of(tab)
    LaunchedEffect(tab) { lastIn[group] = tab }
    // the mirror and the torch take the whole screen; closing them comes back to the tool before
    var before by rememberSaveable(page) { mutableStateOf(groups.first().tabs.first()) }
    val full by fullTool
    LaunchedEffect(tab) { if (tab in FULL_TOOLS) fullTool.value = tab else before = tab }
    LaunchedEffect(full) { if (full == null && tab in FULL_TOOLS) tab = before }
    Column(Modifier.fillMaxSize()) {
        if (groups.size > 1) {
            // more than one group on the page: one glass bar picks the group, a quiet row of names picks the tool
            GlassSegments(
                groups.map { it.name to it.label }, group.name,
                Modifier.align(Alignment.CenterHorizontally).widthIn(max = (groups.size * 140).dp), height = 46.dp,
            ) { k -> val g = KGroup.valueOf(k); tab = lastIn[g] ?: g.tabs.first() }
            Gap(h = 10.dp)
        }
        Row(
            Modifier.align(Alignment.CenterHorizontally).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            group.tabs.forEach { t -> ToolName(t, t == tab) { tab = t } }
        }
        Gap(h = 16.dp)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                KTab.TIMERS -> TimersPane()
                KTab.ALARMS -> AlarmsPane()
                KTab.CONVERT -> ConvertPane()
                KTab.CALC -> CalculatorPane()
                KTab.LIST -> ShoppingPane(store)
                KTab.SCAN -> CameraPane(CamMode.SCAN)
                KTab.MIRROR, KTab.TORCH -> Box(Modifier.fillMaxSize())
                KTab.ZOOM -> CameraPane(CamMode.ZOOM)
                KTab.FILES -> FilesPane(store) { }
                KTab.DOCSCAN -> DocScanPane(store)
                else -> MoreToolsPane(tab, store)
            }
        }
    }
}

/** A segmented control: one glass bar, the chosen segment under a sliding lens. */
@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    GlassSegments(
        options.mapIndexed { i, o -> i.toString() to o }, selected.toString(),
        modifier.widthIn(max = (options.size * 130).dp), height = 46.dp,
    ) { onSelect(it.toInt()) }
}

/** A quiet selectable pill. */
@Composable
fun Chip(text: String, selected: Boolean, accent: Color, modifier: Modifier = Modifier, glyph: G? = null, onClick: () -> Unit) {
    Row(
        modifier.then(
            if (selected) Modifier.droplet(BarShape, V.Teal)
            else Modifier.glass(BarShape, 0.7f),
        ).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (glyph != null) { Glyph(glyph, if (selected) V.Teal else V.Muted, Modifier.size(18.dp)); Gap(w = 8.dp) }
        VText(sentence(text), size = Type.body, color = if (selected) V.White else V.Text.copy(alpha = 0.75f), maxLines = 1)
    }
}

fun fmtDur(ms: Long): String {
    val s = (ms + 999) / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) String.format(Locale.UK, "%d:%02d:%02d", h, m, sec) else String.format(Locale.UK, "%d:%02d", m, sec)
}

// ------------------------------------------------------------------- timers

/** One-tap lengths, in minutes. Plain durations: name a timer yourself if you want to. */
internal val quickMinutes = listOf(1, 3, 5, 10, 15, 20, 30, 45, 60)

@Composable
fun TimersPane() {
    val ctx = LocalContext.current
    val now = rememberNow(250)
    remember(now) { KTimers.check(ctx); 0 }
    var mins by rememberSaveable { mutableIntStateOf(5) }
    var secs by rememberSaveable { mutableIntStateOf(0) }
    var label by rememberSaveable { mutableStateOf("") }
    val timers = KTimers.list.value
    val total = (mins * 60L + secs) * 1000
    val start = { if (total > 0) { KTimers.add(ctx, label.trim().ifEmpty { "Timer" }, total); label = "" } }

    // Half the book lying sideways is too narrow for the 300 dp side card plus the timers:
    // it gets the upright layout, the quick lengths on top and the timers under them.
    BoxWithConstraints(Modifier.fillMaxSize()) {
    CompositionLocalProvider(LocalPortrait provides (LocalPortrait.current || maxWidth < 640.dp)) {
    Split(Modifier.fillMaxSize()) {
        if (portrait) Column(Modifier.side(0.dp, 0f)) {
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                QuickBar(quickMinutes, keyWidth = 84.dp) { m -> KTimers.add(ctx, "$m min", m * 60_000L) }
            }
            Gap(h = 16.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Stepper(mins, "min", { mins = (mins + it).coerceIn(0, 240) }, 1)
                Gap(w = 8.dp)
                Stepper(secs, "sec", { secs = (secs + it).coerceIn(0, 50) }, 10)
                Gap(w = 12.dp)
                VButton("Start", Modifier.weight(1f), filled = true, enabled = total > 0) { start() }
            }
        } else Column(Modifier.side(300.dp, 0f).verticalScroll(rememberScrollState())) {
            // the new-timer card
            VPanel(Modifier.fillMaxWidth()) {
                VText(fmtDur(total), size = 60.sp, font = SansThin, color = V.White, modifier = Modifier.align(Alignment.CenterHorizontally))
                Gap(h = 12.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Stepper(mins, "min", { mins = (mins + it).coerceIn(0, 240) }, 1)
                    Stepper(secs, "sec", { secs = (secs + it).coerceIn(0, 50) }, 10)
                }
                Gap(h = 16.dp)
                VField(label, { label = it }, Modifier.fillMaxWidth(), placeholder = "Label (optional)", onDone = start)
                Gap(h = 12.dp)
                VButton("Start", Modifier.fillMaxWidth(), filled = true, enabled = total > 0) { start() }
            }
            Gap(h = 20.dp)
            Label("Quick start", Modifier.padding(start = 4.dp))
            Gap(h = 8.dp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                quickMinutes.chunked(3).forEach { row ->
                    QuickBar(row, Modifier.fillMaxWidth()) { m -> KTimers.add(ctx, "$m min", m * 60_000L) }
                }
            }
        }
        // running timers: as big as the space allows, readable across the room
        BoxWithConstraints(Modifier.main()) {
            if (timers.isEmpty()) {
                Centered(Modifier.fillMaxSize()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        VText("No timers", size = Type.title2, font = SansMedium, color = V.Text)
                        Gap(h = 8.dp)
                        VText("Pick a length to start one.", size = Type.body, color = V.Muted, align = TextAlign.Center)
                    }
                }
            } else {
                val gap = 14.dp
                // never narrower than 170 dp a card, or its buttons get crushed: more rows instead (it scrolls)
                val fit = ((maxWidth + gap) / (170.dp + gap)).toInt().coerceAtLeast(1)
                val cols = timerGrid(timers.size, maxWidth.value, maxHeight.value).first.coerceAtMost(fit)
                val rows = (timers.size + cols - 1) / cols
                val cellW = (maxWidth - gap * (cols - 1)) / cols
                val cellH = ((maxHeight - gap * (rows - 1)) / rows).coerceAtLeast(200.dp)
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(gap)) {
                    timers.chunked(cols).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            row.forEach { t -> Box(Modifier.size(cellW, cellH)) { TimerCard(t, now) } }
                        }
                    }
                }
            }
        }
    }
    }
    }
}

/**
 * Columns and rows for [n] timers in a [w] x [h] box: the layout that gives
 * each timer the biggest square-ish cell. One timer fills the space.
 */
internal fun timerGrid(n: Int, w: Float, h: Float): Pair<Int, Int> {
    var best = 1 to n; var bestSide = 0f
    for (c in 1..n) {
        val r = (n + c - 1) / c
        val side = minOf(w / c, h / r)
        if (side > bestSide + 0.5f) { bestSide = side; best = c to r }
    }
    return best
}

/** Quick-start lengths as one glass capsule: no dividers, each length its own soft key. */
@Composable
private fun QuickBar(mins: List<Int>, modifier: Modifier = Modifier, keyWidth: Dp? = null, onPick: (Int) -> Unit) {
    Row(modifier.height(58.dp).glass(BarShape, 0.8f)) {
        mins.forEachIndexed { i, m ->
            QuickKey(m, (if (keyWidth != null) Modifier.width(keyWidth) else Modifier.weight(1f)).fillMaxHeight()) { onPick(m) }
        }
    }
}

@Composable
private fun QuickKey(mins: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            VText(if (mins >= 60) "${mins / 60}" else "$mins", size = Type.title, font = SansLight, color = V.White, maxLines = 1)
            Gap(w = 4.dp)
            VText(if (mins >= 60) "hr" else "min", size = Type.label, color = V.Muted, maxLines = 1, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

/** A value with round minus and plus buttons either side. */
@Composable
private fun Stepper(value: Int, unit: String, onChange: (Int) -> Unit, step: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundKey("\u2212") { onChange(-step) }
        Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            VText(String.format(Locale.UK, "%02d", value), size = Type.large, color = V.White, font = SansLight, maxLines = 1)
            VText(unit, size = Type.caption, color = V.Muted)
        }
        RoundKey("+") { onChange(step) }
    }
}

@Composable
private fun RoundKey(text: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(V.Panel2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { VText(text, size = Type.title, color = V.Text, font = SansLight) }
}

/** A small grey pill key. */
@Composable
fun SmallKey(text: String, accent: Color = V.Muted, tight: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.height(36.dp).widthIn(min = if (tight) 44.dp else 0.dp).glass(Pill, 0.7f).clickable(onClick = onClick).padding(horizontal = if (tight) 10.dp else 16.dp),
        contentAlignment = Alignment.Center,
    ) { VText(text.lowercase(), size = Type.label, color = V.Text) }
}

/** A running timer: a ring that empties as time runs out, the time inside it. */
@Composable
private fun TimerCard(t: KTimer, now: Long) {
    val ctx = LocalContext.current
    val left = t.remaining(now)
    val col = if (t.rang) V.Coral else V.Coral
    val frac = if (t.totalMs > 0) (left.toFloat() / t.totalMs).coerceIn(0f, 1f) else 0f
    BoxWithConstraints(Modifier.fillMaxSize().pane(Oct)) {
        val ringSide = minOf(maxWidth - 32.dp, maxHeight - 120.dp).coerceAtLeast(90.dp)
        val digits = fmtDur(left).length
        val big = (ringSide.value * (if (digits > 5) 0.17f else 0.24f)).coerceIn(22f, 150f).sp
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            VText(t.label, size = Type.body, font = SansMedium, color = V.Text, modifier = Modifier.weight(1f), maxLines = 1)
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { KTimers.remove(ctx, t.id) }, contentAlignment = Alignment.Center) {
                Glyph(G.CLOSE, V.Muted, Modifier.size(16.dp))
            }
        }
        Box(Modifier.align(Alignment.Center).size(ringSide), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = (size.minDimension * 0.035f).coerceIn(4.dp.toPx(), 9.dp.toPx())
                val inset = sw / 2
                val arcSize = Size(size.width - sw, size.height - sw)
                drawArc(V.White.copy(alpha = 0.07f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(sw))
                // the ring runs coral into purple, with a soft glow under it and a bright bead at its head
                val grad = if (t.rang) Brush.sweepGradient(listOf(V.Coral, V.Coral)) else Brush.sweepGradient(listOf(V.Purple, V.Coral, V.Orange, V.Purple))
                drawArc(col.copy(alpha = 0.16f), -90f, 360f * frac, false, Offset(inset, inset), arcSize, style = Stroke(sw * 3.2f, cap = StrokeCap.Round))
                rotate(-90f) { drawArc(grad, 0f, 360f * frac, false, Offset(inset, inset), arcSize, style = Stroke(sw, cap = StrokeCap.Round)) }
                if (frac > 0f) {
                    val ang = Math.toRadians((-90f + 360f * frac).toDouble())
                    val rr = arcSize.width / 2
                    val head = Offset(size.width / 2 + rr * kotlin.math.cos(ang).toFloat(), size.height / 2 + rr * kotlin.math.sin(ang).toFloat())
                    drawCircle(Color.White.copy(alpha = 0.35f), sw * 1.6f, head)
                    drawCircle(Color.White, sw * 0.62f, head)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                VText(if (t.rang) "Done" else fmtDur(left), size = big, color = if (t.rang) V.Coral else if (t.running) V.White else V.Muted, font = SansThin, maxLines = 1)
                if (!t.running && !t.rang) VText("Paused", size = Type.label, color = V.Muted)
            }
        }
        // a narrow card (three timers in half the book) has no room for "+1 min" either side of play
        val tight = maxWidth < 250.dp
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallKey(if (tight) "+1" else "+1 min", tight = tight) { KTimers.addTime(ctx, t.id, 60_000) }
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(if (t.running) Brush.verticalGradient(listOf(V.Panel2, V.Panel2)) else Brush.verticalGradient(listOf(lerp(V.Coral, Color.White, 0.2f), V.Coral))).clickable { KTimers.toggle(ctx, t.id) },
                contentAlignment = Alignment.Center,
            ) { Glyph(if (t.running) G.PAUSE else G.PLAY, if (t.running) V.Text else V.Bg, Modifier.size(20.dp)) }
            SmallKey(if (tight) "+5" else "+5 min", tight = tight) { KTimers.addTime(ctx, t.id, 300_000) }
        }
    }
}

/** Full-screen alert while a timer is ringing. Shows over every screen. */
@Composable
fun TimerAlert() {
    val ctx = LocalContext.current
    val ringing = KTimers.ringing.value
    if (ringing.isEmpty()) return
    val now = rememberNow(500)
    val flash = (now / 500) % 2 == 0L
    Box(Modifier.fillMaxSize().background(V.Bg.copy(alpha = 0.96f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Glyph(G.BELL, if (flash) V.Coral else V.Text, Modifier.size(72.dp))
            Gap(h = 20.dp)
            VText(ringing.joinToString(" + ") { it.label }, size = 40.sp, color = V.White, font = SansMedium, align = TextAlign.Center)
            Gap(h = 8.dp)
            VText("Time's up", size = Type.title, color = V.Muted)
            Gap(h = 32.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                VButton("Stop", Modifier.width(200.dp), accent = V.Coral, filled = true) { KTimers.silence(ctx) }
                VButton("+1 minute", Modifier.width(200.dp)) { ringing.forEach { KTimers.addTime(ctx, it.id, 60_000) } }
            }
        }
    }
}

// ------------------------------------------------------------------ convert

internal data class Unit_(val name: String, val short: String, val toBase: (Double) -> Double, val fromBase: (Double) -> Double)

private fun lin(name: String, short: String, factor: Double) = Unit_(name, short, { it * factor }, { it / factor })

internal val gasMarks = listOf(1 to 140.0, 2 to 150.0, 3 to 170.0, 4 to 180.0, 5 to 190.0, 6 to 200.0, 7 to 220.0, 8 to 230.0, 9 to 240.0)

internal val categories: List<Pair<String, List<Unit_>>> = listOf(
    "Weight" to listOf(lin("Grams", "g", 1.0), lin("Kilograms", "kg", 1000.0), lin("Ounces", "oz", 28.349523125), lin("Pounds", "lb", 453.59237), lin("Stone", "st", 6350.29318)),
    "Volume" to listOf(
        lin("Millilitres", "ml", 1.0), lin("Litres", "l", 1000.0), lin("Teaspoons", "tsp", 5.0), lin("Tablespoons", "tbsp", 15.0),
        lin("Cups (US)", "cup", 236.5882365), lin("Fluid oz (UK)", "fl oz", 28.4130625), lin("Pints (UK)", "pt", 568.26125),
    ),
    "Temperature" to listOf(
        Unit_("Celsius", "°C", { it }, { it }),
        Unit_("Fan oven", "°C fan", { it + 20 }, { it - 20 }),
        Unit_("Fahrenheit", "°F", { (it - 32) * 5 / 9 }, { it * 9 / 5 + 32 }),
        Unit_("Gas mark", "gas", { g -> gasMarks.minByOrNull { abs(it.first - g) }!!.second }, { c -> if (c < 130 || c > 250) Double.NaN else gasMarks.minByOrNull { abs(it.second - c) }!!.first.toDouble() }),
    ),
    "Length" to listOf(lin("Centimetres", "cm", 1.0), lin("Millimetres", "mm", 0.1), lin("Inches", "in", 2.54), lin("Feet", "ft", 30.48)),
)

/** Grams in one US cup, for the cups-to-grams helper. */
internal val cupGrams = listOf("Plain flour" to 125.0, "Caster sugar" to 200.0, "Brown sugar" to 220.0, "Icing sugar" to 120.0, "Butter" to 227.0, "Rice (raw)" to 185.0, "Oats" to 90.0, "Milk" to 240.0, "Water" to 237.0, "Honey" to 340.0)

internal fun fmtNum(v: Double): String {
    if (v.isNaN() || v.isInfinite()) return "-"
    val a = abs(v)
    return when {
        a >= 100 -> v.roundToLong().toString()
        a >= 10 -> String.format(Locale.UK, "%.1f", v).removeSuffix(".0")
        else -> String.format(Locale.UK, "%.2f", v).trimEnd('0').trimEnd('.')
    }
}

@Composable
fun ConvertPane() {
    var cat by rememberSaveable { mutableIntStateOf(0) }
    var from by rememberSaveable { mutableIntStateOf(0) }
    var entry by rememberSaveable { mutableStateOf("100") }
    val cups = false
    val units = categories[cat].second
    val value = entry.toDoubleOrNull() ?: 0.0

    // Half the book lying sideways is short and narrow: stacked, the keypad was squashed to
    // slivers. There it goes side by side instead, keypad on the left, answers on the right.
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val sideBySide = LocalPortrait.current && maxHeight < 620.dp
    val sideW = if (sideBySide) maxWidth * 0.55f else 330.dp
    // too short for the kinds, the number and the keys in one panel (half the book either way up)
    val keysOnly = sideBySide || (!LocalPortrait.current && maxHeight < 380.dp)
    CompositionLocalProvider(LocalPortrait provides (LocalPortrait.current && !sideBySide)) {
    Split(Modifier.fillMaxSize()) {
        val chips: @Composable () -> Unit = {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEachIndexed { i, (n, _) -> Chip(n, cat == i, V.Teal) { cat = i; from = 0; entry = if (n == "Temperature") "180" else "100" } }
            }
        }
        val readout: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.Bottom) {
                VText(entry.ifEmpty { "0" }, size = 46.sp, color = V.White, font = SansLight, maxLines = 1, modifier = Modifier.weight(1f))
                VText(units[from].short, size = Type.title, color = V.Muted)
            }
        }
        VPanel(Modifier.side(sideW, 1.1f)) {
            // when it's short (half the book) the panel is only the keys, so they keep their
            // height: the number and the kinds move over to the answers
            if (!keysOnly) { chips(); Gap(h = 12.dp); readout(); Gap(h = 8.dp) }
            NumPad(Modifier.weight(1f).fillMaxWidth()) { k ->
                entry = when (k) {
                    "<" -> entry.dropLast(1)
                    "." -> if (entry.contains('.')) entry else (entry.ifEmpty { "0" } + ".")
                    "C" -> ""
                    else -> if (entry == "0") k else (entry + k).take(8)
                }
            }
        }
        Column(Modifier.main()) {
            if (cups) {
                Label("${fmtNum(value)} US cup${if (value == 1.0) "" else "s"} weighs about")
                Gap(h = 8.dp)
                LazyVerticalGrid(GridCells.Adaptive(190.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(cupGrams) { (n, g) -> ResultTile(n, "${fmtNum(g * value)} g", V.White) }
                }
            } else {
                if (keysOnly) { readout(); Gap(h = 10.dp); chips(); Gap(h = 12.dp) }
                Label("Convert from")
                Gap(h = 8.dp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    units.forEachIndexed { i, u -> Chip(u.short, i == from, V.Teal) { from = i } }
                }
                Gap(h = 16.dp)
                val base = units[from].toBase(value)
                LazyVerticalGrid(GridCells.Adaptive(190.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(units.filterIndexed { i, _ -> i != from }) { u ->
                        val out = u.fromBase(base)
                        ResultTile(u.name, if (u.short == "gas") (if (out.isNaN()) "Off the scale" else "Gas ${out.toInt()}") else "${fmtNum(out)} ${u.short}", V.White)
                    }
                }
            }
        }
    }
    }
    }
}

@Composable
private fun ResultTile(name: String, value: String, accent: Color) {
    Column(Modifier.fillMaxWidth().clip(Oct).background(V.Panel).padding(horizontal = 16.dp, vertical = 16.dp)) {
        VText(name, size = Type.label, color = V.Muted)
        Gap(h = 4.dp)
        VText(value, size = Type.large, color = accent, font = SansLight, maxLines = 1)
    }
}

@Composable
fun NumPad(modifier: Modifier, onKey: (String) -> Unit) {
    val keys = listOf("7", "8", "9", "4", "5", "6", "1", "2", "3", ".", "0", "<")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.chunked(3).forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier.weight(1f).fillMaxSize().clip(OctS).background(V.Panel2)
                            .clickable { onKey(k) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (k == "<") Glyph(G.BACK, V.Muted, Modifier.size(20.dp)) else VText(k, size = Type.title2, color = V.Text, font = SansLight)
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------- shopping list

@Composable
fun ShoppingPane(store: Store) {
    var text by remember { mutableStateOf("") }
    val items = store.shopping
    val add = { store.addItem(text); text = "" }
    Column(Modifier.fillMaxSize().widthIn(max = 760.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VField(text, { text = it }, Modifier.weight(1f), placeholder = "Add an item", onDone = add)
            Gap(w = 8.dp)
            VButton("Add", filled = true, enabled = text.isNotBlank()) { add() }
        }
        Gap(h = 16.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            VText("${items.count { !it.done }} to get", size = Type.body, color = V.Muted, modifier = Modifier.weight(1f))
            if (items.any { it.done }) VButton("Clear ticked") { store.clearDone() }
        }
        Gap(h = 8.dp)
        if (items.isEmpty()) {
            Centered(Modifier.fillMaxSize()) { VText("Nothing on the list", size = Type.headline, color = V.Muted) }
        } else {
            val groups = items.filter { !it.done }.groupBy { it.aisle }.toSortedMap() + (if (items.any { it.done }) mapOf("Ticked" to items.filter { it.done }) else emptyMap())
            LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
                groups.forEach { (aisle, list) ->
                    item(key = "h_$aisle") { Label(aisle, Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp)) }
                    item(key = "g_$aisle") {
                        Column(Modifier.fillMaxWidth().clip(Oct).background(V.Panel)) {
                            list.forEachIndexed { i, it2 ->
                                if (i > 0) Spacer(Modifier.height(2.dp))
                                Row(
                                    Modifier.fillMaxWidth().clickable { store.toggleItem(it2.id) }.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        Modifier.size(24.dp).then(if (it2.done) Modifier.droplet(CircleShape, V.Teal, 1.6f) else Modifier.pane(CircleShape)),
                                        contentAlignment = Alignment.Center,
                                    ) { if (it2.done) Glyph(G.CHECK, V.Bg, Modifier.size(15.dp)) }
                                    Gap(w = 16.dp)
                                    VText(it2.text, size = Type.headline, color = if (it2.done) V.Muted else V.Text, modifier = Modifier.weight(1f))
                                    Box(Modifier.size(44.dp).clip(CircleShape).clickable { store.removeItem(it2.id) }, contentAlignment = Alignment.Center) {
                                        Glyph(G.CLOSE, V.Tick, Modifier.size(15.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

val CutCorner6 = androidx.compose.foundation.shape.RoundedCornerShape(Radius.chip)

