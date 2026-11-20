package com.andysocial.voidos.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke as PenStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.andysocial.voidos.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The Notes drawing tools: one glass pill of icons that are all the same size
 * and sit on one line, and the panels that open above it for colour, size and
 * the brush (line style, shape, smoothing and paper).
 */

/** Which panel is open above the pill. */
internal enum class InkPanel { NONE, COLOUR, SIZE, BRUSH }

private val Cell = 40.dp
private val PanelShape = Chamfer(Radius.panel)

/** One tool in the pill: an icon in a 40 dp round cell, lit teal when on. */
@Composable
internal fun InkTool(
    @DrawableRes icon: Int, desc: String, on: Boolean = false, enabled: Boolean = true, warn: Boolean = false,
    onClick: () -> Unit,
) {
    val c = when { !enabled -> V.Tick; warn -> V.Coral; on -> V.Teal; else -> V.Text.copy(alpha = 0.86f) }
    Box(
        Modifier.size(Cell).clip(CircleShape)
            .then(
                when {
                    warn && enabled -> Modifier.droplet(CircleShape, V.Coral)
                    on -> Modifier.droplet(CircleShape, V.Teal)
                    else -> Modifier
                },
            )
            .semantics { contentDescription = desc; role = Role.Button; selected = on }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Image(painterResource(icon), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(c)) }
}

/** A cell that shows a live value (the colour, the size) instead of an icon. */
@Composable
private fun InkValueCell(desc: String, state: String, on: Boolean, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier.size(Cell).clip(CircleShape)
            .then(if (on) Modifier.droplet(CircleShape, V.Teal) else Modifier)
            .semantics { contentDescription = desc; stateDescription = state; role = Role.Button; selected = on }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** A gap between groups: no line, just room. */
@Composable
private fun InkGap() = Box(Modifier.width(10.dp).height(Cell))

/**
 * The pill: undo, redo | pen, highlighter, eraser | colour, size, brush | clear.
 * Colour, size and brush open their panels above it.
 */
@Composable
internal fun InkBar(
    tool: Int, rgb: Int, opacity: Float, size: Float, sizeLabel: String, panel: InkPanel, canUndo: Boolean, canRedo: Boolean,
    canClear: Boolean, clearArmed: Boolean,
    onTool: (Int) -> Unit, onPanel: (InkPanel) -> Unit, onUndo: () -> Unit, onRedo: () -> Unit, onClear: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier.widthIn(max = 640.dp).liquidGlass(Pill, GlassLook(smoke = 1.1f, depth = 1.2f))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        InkTool(R.drawable.vi_ink_undo, "Undo", enabled = canUndo, onClick = onUndo)
        InkTool(R.drawable.vi_ink_redo, "Redo", enabled = canRedo, onClick = onRedo)
        InkGap()
        InkTool(R.drawable.vi_ink_pen, "Pen", on = tool == TOOL_PEN) { onTool(TOOL_PEN) }
        InkTool(R.drawable.vi_ink_highlighter, "Highlighter", on = tool == TOOL_HIGHLIGHTER) { onTool(TOOL_HIGHLIGHTER) }
        InkTool(R.drawable.vi_ink_eraser, "Eraser", on = tool == TOOL_ERASER) { onTool(TOOL_ERASER) }
        InkGap()
        // the colour: a glass bead of the current ink
        InkValueCell("Colour", hex(rgb) + if (opacity < 0.995f) ", ${(opacity * 100).roundToInt()}% opacity" else "", panel == InkPanel.COLOUR,
            { onPanel(if (panel == InkPanel.COLOUR) InkPanel.NONE else InkPanel.COLOUR) }) {
            val c = Color(rgb or (0xFF shl 24))
            Box(
                Modifier.size(24.dp).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(androidx.compose.ui.graphics.lerp(c, Color.White, 0.4f), c), center = Offset(18f, 14f))),
            )
        }
        // the size: a dot as thick as the line, within the cell
        InkValueCell("Size", sizeLabel, panel == InkPanel.SIZE, { onPanel(if (panel == InkPanel.SIZE) InkPanel.NONE else InkPanel.SIZE) }) {
            val t = sliderFromSize(size, if (tool == TOOL_ERASER) MIN_ERASER else MIN_PEN, if (tool == TOOL_ERASER) MAX_ERASER else MAX_PEN)
            val d = (4f + 18f * t).dp
            Box(Modifier.size(d).clip(CircleShape).background(if (panel == InkPanel.SIZE) V.Teal else V.Text.copy(alpha = 0.86f)))
        }
        InkTool(R.drawable.vi_ink_brush, "Brush options", on = panel == InkPanel.BRUSH) {
            onPanel(if (panel == InkPanel.BRUSH) InkPanel.NONE else InkPanel.BRUSH)
        }
        InkGap()
        InkTool(R.drawable.vi_ink_clear, if (clearArmed) "Clear?" else "Clear page", enabled = canClear, warn = clearArmed, onClick = onClear)
    }
}

/** A panel floating above the pill. */
@Composable
internal fun InkCard(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.widthIn(max = 340.dp).liquidGlass(PanelShape, GlassLook(smoke = 1.15f, depth = 1.3f))
            // taps inside the card stay inside
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) { content() }
}

/* ---------------- colour ---------------- */

/** The colour panel: a hue and saturation wheel, brightness, opacity, recent colours and a picker for colours on the page. */
@Composable
internal fun ColourPanel(
    rgb: Int, opacity: Float, recent: List<Int>, picking: Boolean,
    onColour: (Int) -> Unit, onOpacity: (Float) -> Unit, onPick: () -> Unit, onDone: () -> Unit,
) {
    val hsv = remember(rgb) { argbToHsv(rgb) }
    val portrait = LocalPortrait.current
    val wheel = if (portrait) 200.dp else 168.dp
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ColourWheel(hsv[0], hsv[1], hsv[2], wheel) { h, s -> onColour(hsvToArgb(h, s, if (hsv[2] < 0.05f) 1f else hsv[2])) }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(Color(withAlpha(rgb, opacity))),
            )
            VText(hex(rgb), size = Type.caption, color = V.Muted, align = TextAlign.Center)
            InkTool(R.drawable.vi_ink_eyedropper, if (picking) "Picking a colour from the page" else "Pick a colour from the page", on = picking) { onPick() }
        }
    }
    InkSlider(
        "Brightness", hsv[2], "${(hsv[2] * 100).roundToInt()}%",
        Brush.horizontalGradient(listOf(Color.Black, Color(hsvToArgb(hsv[0], hsv[1], 1f)))),
    ) { onColour(hsvToArgb(hsv[0], hsv[1], it)) }
    InkSlider(
        "Opacity", opacity, "${(opacity * 100).roundToInt()}%",
        Brush.horizontalGradient(listOf(Color(withAlpha(rgb, 0.05f)), Color(rgb or (0xFF shl 24)))),
    ) { onOpacity(it.coerceIn(0.05f, 1f)) }
    // white and black first, then the colours used last
    val chips = (recent + listOf(0xFFFFFFFF.toInt(), 0xFF101014.toInt()) + V.Lap.map { it.toArgb() }).distinct().take(10)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        chips.forEach { c ->
            val on = (c and 0xFFFFFF) == (rgb and 0xFFFFFF)
            Box(
                Modifier.size(26.dp).clip(CircleShape)
                    .then(if (on) Modifier.droplet(CircleShape, V.Teal) else Modifier)
                    .semantics { contentDescription = "Colour ${hex(c)}"; selected = on }
                    .clickable { onColour(c or (0xFF shl 24)) },
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(if (on) 16.dp else 22.dp).clip(CircleShape).background(Color(c or (0xFF shl 24)))) }
        }
    }
}

/** A disc of every hue around and every saturation out from the middle. Drag or tap anywhere on it. */
@Composable
private fun ColourWheel(h: Float, s: Float, v: Float, size: Dp, onPick: (Float, Float) -> Unit) {
    fun pick(o: Offset, w: Float) {
        val c = w / 2
        val dx = o.x - c; val dy = o.y - c
        val hue = ((Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()) + 360f) % 360f
        onPick(hue, (hypot(dx, dy) / c).coerceIn(0f, 1f))
    }
    Canvas(
        Modifier.size(size)
            .semantics { contentDescription = "Colour wheel"; stateDescription = "Hue ${h.roundToInt()} degrees, saturation ${(s * 100).roundToInt()}%" }
            .pointerInput(Unit) { detectTapGestures { pick(it, this.size.width.toFloat()) } }
            .pointerInput(Unit) {
                detectDragGestures(onDragStart = { pick(it, this.size.width.toFloat()) }) { ch, _ -> ch.consume(); pick(ch.position, this.size.width.toFloat()) }
            },
    ) {
        val r = this.size.minDimension / 2
        val centre = Offset(r, r)
        drawCircle(Brush.sweepGradient(List(13) { Color(hsvToArgb(it * 30f, 1f, 1f)) }, centre), r, centre)
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), centre, r), r, centre)
        // the brightness dims the whole wheel, so it shows what you'll get
        if (v < 1f) drawCircle(Color.Black.copy(alpha = (1f - v) * 0.85f), r, centre)
        val a = Math.toRadians(h.toDouble())
        val k = Offset(centre.x + (cos(a) * s * r).toFloat(), centre.y + (sin(a) * s * r).toFloat())
        drawCircle(Color.Black.copy(alpha = 0.35f), 13.dp.toPx(), k)
        drawCircle(Color(hsvToArgb(h, s, v)), 10.dp.toPx(), k)
        drawCircle(Color.White, 11.dp.toPx(), k, style = PenStroke(2.5.dp.toPx()))
    }
}

/** A glass slider with a coloured track. */
@Composable
internal fun InkSlider(label: String, value: Float, readout: String, track: Brush?, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            VText(label, size = Type.label, color = V.Muted, modifier = Modifier.weight(1f))
            VText(readout, size = Type.label, color = V.Text)
        }
        Canvas(
            Modifier.fillMaxWidth().height(30.dp)
                .semantics { contentDescription = label; stateDescription = readout }
                .pointerInput(Unit) { detectTapGestures { onChange((it.x / this.size.width).coerceIn(0f, 1f)) } }
                .pointerInput(Unit) {
                    detectDragGestures(onDragStart = { onChange((it.x / this.size.width).coerceIn(0f, 1f)) }) { ch, _ ->
                        ch.consume(); onChange((ch.position.x / this.size.width).coerceIn(0f, 1f))
                    }
                },
        ) {
            val h = 12.dp.toPx(); val y = this.size.height / 2; val kr = 12.dp.toPx()
            val x0 = kr; val x1 = this.size.width - kr
            drawRoundRect(Color.White.copy(alpha = 0.10f), Offset(x0 - h / 2, y - h / 2), androidx.compose.ui.geometry.Size(x1 - x0 + h, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
            drawRoundRect(track ?: Brush.horizontalGradient(listOf(V.Teal.copy(alpha = 0.5f), V.Teal)), Offset(x0 - h / 2, y - h / 2),
                androidx.compose.ui.geometry.Size(if (track == null) (x1 - x0) * value + h else x1 - x0 + h, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
            val kx = x0 + (x1 - x0) * value.coerceIn(0f, 1f)
            drawCircle(Color.Black.copy(alpha = 0.3f), kr + 1.dp.toPx(), Offset(kx, y + 1.dp.toPx()))
            drawCircle(Color.White, kr, Offset(kx, y))
            drawCircle(Color.White.copy(alpha = 0.6f), kr * 0.45f, Offset(kx - kr * 0.25f, y - kr * 0.3f))
        }
    }
}

/* ---------------- size ---------------- */

/** The size panel: any size from a hairline to a marker, with a live line to show it. */
@Composable
internal fun SizePanel(
    tool: Int, size: Float, rgb: Int, opacity: Float, style: Int, pageWidthDp: Float, onSize: (Float) -> Unit,
) {
    val lo = if (tool == TOOL_ERASER) MIN_ERASER else MIN_PEN
    val hi = if (tool == TOOL_ERASER) MAX_ERASER else MAX_PEN
    val pt = size * pageWidthDp
    // the line as it will draw, at its real thickness
    Canvas(Modifier.fillMaxWidth().height(64.dp).semantics { contentDescription = "Line preview" }) {
        val px = (size * pageWidthDp).dp.toPx()
        val y = this.size.height / 2
        if (tool == TOOL_ERASER) {
            drawCircle(Color.White.copy(alpha = 0.7f), px, Offset(this.size.width / 2, y), style = PenStroke(1.5.dp.toPx()))
        } else {
            val c = Color(inkArgb(tool, rgb, opacity))
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(px, y + 10.dp.toPx())
                cubicTo(this@Canvas.size.width * 0.3f, y - 26.dp.toPx(), this@Canvas.size.width * 0.6f, y + 26.dp.toPx(), this@Canvas.size.width - px, y - 6.dp.toPx())
            }
            drawPath(path, c, style = PenStroke(px, cap = StrokeCap.Round, pathEffect = effectFor(style, px)))
        }
    }
    InkSlider(
        if (tool == TOOL_ERASER) "Eraser size" else "Size", sliderFromSize(size, lo, hi), "%.1f".format(pt), null,
    ) { onSize(sizeFromSlider(it, lo, hi)) }
    // fine steps either side of the slider
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        InkTool(R.drawable.vi_ink_thinner, "Thinner", enabled = size > lo) { onSize((size - 0.1f / pageWidthDp.coerceAtLeast(1f)).coerceIn(lo, hi)) }
        VText("%.1f".format(pt), size = Type.title2, color = V.Text)
        InkTool(R.drawable.vi_ink_thicker, "Thicker", enabled = size < hi) { onSize((size + 0.1f / pageWidthDp.coerceAtLeast(1f)).coerceIn(lo, hi)) }
    }
}

/* ---------------- brush ---------------- */

/** The brush menu: line style, shape, smoothing and this note's paper. */
@Composable
internal fun BrushPanel(
    style: Int, shape: Int, smooth: Boolean, paper: Int,
    onStyle: (Int) -> Unit, onShape: (Int) -> Unit, onSmooth: (Boolean) -> Unit, onPaper: (Int) -> Unit,
) {
    BrushRow("Line") {
        LineStyles.forEach { s ->
            InkChoice(lineStyleName(s), s == style, { onStyle(s) }) { c ->
                Canvas(Modifier.size(width = 26.dp, height = 8.dp)) {
                    val w = 3.dp.toPx()
                    drawLine(c, Offset(w, this.size.height / 2), Offset(this.size.width - w, this.size.height / 2), w, StrokeCap.Round, effectFor(s, w))
                }
            }
        }
    }
    BrushRow("Shape") {
        listOf(
            SHAPE_FREE to R.drawable.vi_ink_free, SHAPE_LINE to R.drawable.vi_ink_line, SHAPE_ARROW to R.drawable.vi_ink_arrow,
            SHAPE_RECT to R.drawable.vi_ink_rect, SHAPE_ELLIPSE to R.drawable.vi_ink_ellipse,
        ).forEach { (s, icon) -> InkTool(icon, shapeName(s), on = s == shape) { onShape(s) } }
    }
    BrushRow("Paper") {
        listOf(PAPER_PLAIN to R.drawable.vi_ink_plain, PAPER_LINED to R.drawable.vi_ink_lined, PAPER_GRID to R.drawable.vi_ink_grid, PAPER_DOTS to R.drawable.vi_ink_dots)
            .forEach { (p, icon) -> InkTool(icon, paperName(p), on = p == paper) { onPaper(p) } }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        VText("Smooth lines", size = Type.body, color = V.Text, modifier = Modifier.weight(1f))
        InkTool(R.drawable.vi_ink_smooth, if (smooth) "Smooth lines on" else "Smooth lines off", on = smooth) { onSmooth(!smooth) }
    }
}

@Composable
private fun BrushRow(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        VText(label, size = Type.label, color = V.Muted)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

@Composable
private fun InkChoice(desc: String, on: Boolean, onClick: () -> Unit, content: @Composable (Color) -> Unit) {
    val c = if (on) V.Teal else V.Text.copy(alpha = 0.86f)
    Box(
        Modifier.size(width = 46.dp, height = Cell).clip(Pill)
            .then(if (on) Modifier.droplet(Pill, V.Teal) else Modifier)
            .semantics { contentDescription = desc; role = Role.Button; selected = on }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content(c) }
}

/* ---------------- paper ---------------- */

/** Lines, a grid or dots behind the page; lined paper follows the text's 28 sp lines. */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPaper(paper: Int, lineStepPx: Float, topPx: Float) {
    if (paper == PAPER_PLAIN) return
    val c = Color.White.copy(alpha = if (paper == PAPER_DOTS) 0.16f else 0.07f)
    val w = size.width; val h = size.height
    when (paper) {
        PAPER_LINED -> {
            var y = topPx + lineStepPx
            while (y < h) { drawLine(c, Offset(0f, y), Offset(w, y), 1.dp.toPx()); y += lineStepPx }
        }
        PAPER_GRID -> {
            val step = lineStepPx
            var y = topPx + step
            while (y < h) { drawLine(c, Offset(0f, y), Offset(w, y), 1.dp.toPx()); y += step }
            var x = step
            while (x < w) { drawLine(c, Offset(x, 0f), Offset(x, h), 1.dp.toPx()); x += step }
        }
        PAPER_DOTS -> {
            val step = lineStepPx
            var y = topPx + step
            while (y < h) {
                var x = step
                while (x < w) { drawCircle(c, 1.4.dp.toPx(), Offset(x, y)); x += step }
                y += step
            }
        }
    }
}

/** Points to dp: what the size readout shows. */
internal fun inkPt(size: Float, pageWidthDp: Float) = min(999f, size * pageWidthDp)
