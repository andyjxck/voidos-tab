package com.andysocial.voidos.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import kotlin.math.cos
import kotlin.math.sin

val Oct = CutCornerShape(14.dp)
val OctS = CutCornerShape(8.dp)

@Composable
fun VText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 14.sp,
    color: Color = V.Text,
    font: FontFamily = Kenney,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
    spacing: TextUnit = 0.sp,
    lineHeight: TextUnit = TextUnit.Unspecified,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = size, fontFamily = font, textAlign = align, letterSpacing = spacing, lineHeight = lineHeight),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Small grey uppercase label, the watch's "STEPS" style. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = V.Muted) =
    VText(text.uppercase(), modifier, size = 11.sp, color = color, spacing = 1.5.sp)

@Composable
fun VPanel(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(Oct)
            .background(V.Panel)
            .border(1.dp, accent?.copy(alpha = 0.55f) ?: V.Line, Oct)
            .padding(padding),
        content = content,
    )
}

fun Modifier.tap(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = MutableInteractionSource(), indication = null, onClick = onClick,
)

@Composable
fun VButton(
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = V.Teal,
    filled: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = if (enabled) accent else V.Tick
    Box(
        modifier
            .clip(OctS)
            .background(if (filled) c else c.copy(alpha = 0.08f))
            .border(1.5.dp, c, OctS)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        VText(label.uppercase(), size = 13.sp, color = if (filled) V.Bg else c, spacing = 1.sp, maxLines = 1)
    }
}

/** Octagonal on/off switch. */
@Composable
fun VToggle(checked: Boolean, accent: Color = V.Teal, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val t by animateFloatAsState(if (checked) 1f else 0f, label = "tog")
    val c = if (enabled) accent else V.Tick
    Box(
        Modifier
            .size(64.dp, 32.dp)
            .clip(OctS)
            .background(if (checked) c.copy(alpha = 0.22f) else V.Deep)
            .border(1.5.dp, if (checked) c else V.Tick, OctS)
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .padding(start = (32 * t).dp)
                .size(24.dp)
                .clip(CutCornerShape(6.dp))
                .background(if (checked) c else V.Tick)
        )
    }
}

/** A setting row: title + optional detail on the left, control on the right. */
@Composable
fun SettingRow(title: String, detail: String? = null, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            VText(title.uppercase(), size = 14.sp, color = V.Text, spacing = 0.5.sp)
            if (detail != null) {
                Spacer(Modifier.height(4.dp))
                VText(detail, size = 12.sp, color = V.Muted, font = FontFamily.Default, lineHeight = 16.sp)
            }
        }
        control()
    }
}

/** Drag/tap slider, 0..1. */
@Composable
fun VSlider(value: Float, modifier: Modifier = Modifier, accent: Color = V.Teal, onChange: (Float) -> Unit) {
    val cb by rememberUpdatedState(onChange)
    Canvas(
        modifier
            .height(36.dp)
            .pointerInput(Unit) {
                detectTapGestures { o -> cb((o.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    cb((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }
    ) {
        val y = size.height / 2
        drawLine(V.Line, Offset(0f, y), Offset(size.width, y), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
        val x = size.width * value.coerceIn(0f, 1f)
        drawLine(accent, Offset(0f, y), Offset(x, y), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
        // ticks like the dial
        for (i in 0..10) {
            val tx = size.width * i / 10f
            drawLine(if (tx <= x) accent.copy(alpha = 0.6f) else V.Tick, Offset(tx, y + 9.dp.toPx()), Offset(tx, y + 13.dp.toPx()), 1.5f)
        }
        drawOct(Offset(x, y), 11.dp.toPx(), V.White, fill = true)
    }
}

@Composable
fun VField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    password: Boolean = false,
    size: TextUnit = 16.sp,
    font: FontFamily = FontFamily.Default,
) {
    Box(
        modifier
            .clip(OctS)
            .background(V.Deep)
            .border(1.dp, V.Line, OctS)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        if (value.isEmpty()) VText(placeholder, size = size, color = V.Tick, font = font)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            textStyle = TextStyle(color = V.Text, fontSize = size, fontFamily = font),
            cursorBrush = SolidColor(V.Teal),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ------------------------------------------------------------------ drawing

fun octPath(c: Offset, r: Float): Path {
    val k = r * 0.4142f
    return Path().apply {
        moveTo(c.x - k, c.y - r); lineTo(c.x + k, c.y - r); lineTo(c.x + r, c.y - k); lineTo(c.x + r, c.y + k)
        lineTo(c.x + k, c.y + r); lineTo(c.x - k, c.y + r); lineTo(c.x - r, c.y + k); lineTo(c.x - r, c.y - k); close()
    }
}

fun DrawScope.drawOct(c: Offset, r: Float, color: Color, fill: Boolean = false, width: Float = 2f) {
    if (fill) drawPath(octPath(c, r), color) else drawPath(octPath(c, r), color, style = Stroke(width))
}

/** Hand-drawn VoidOS glyphs. No icon packs: they're all lines and octagons. */
enum class G { FACE, WEATHER, NOTES, MEDIA, FOCUS, HOUSE, APPS, SYSTEM, WIFI, BT, SUN, MOON, CLOUD, RAIN, SNOW, STORM, FOG, PLAY, PAUSE, NEXT, PREV, PLUS, BACK, TRASH, LOCK }

@Composable
fun Glyph(g: G, color: Color, modifier: Modifier = Modifier.size(28.dp), weight: Float = 2.2f) {
    Canvas(modifier) { drawGlyph(g, color, weight.dp.toPx()) }
}

fun DrawScope.drawGlyph(g: G, color: Color, sw: Float) {
    val w = size.width; val h = size.height; val c = Offset(w / 2, h / 2); val r = minOf(w, h) / 2
    val st = Stroke(sw, cap = StrokeCap.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(color, Offset(w * x1, h * y1), Offset(w * x2, h * y2), sw, StrokeCap.Round)
    fun cloud(dy: Float = 0f) {
        drawCircle(color, r * 0.30f, Offset(w * 0.38f, h * (0.55f + dy)), style = st)
        drawCircle(color, r * 0.38f, Offset(w * 0.58f, h * (0.47f + dy)), style = st)
        line(0.24f, 0.70f + dy, 0.82f, 0.70f + dy)
    }
    when (g) {
        G.FACE -> {
            drawOct(c, r * 0.92f, color, width = sw)
            line(0.5f, 0.5f, 0.5f, 0.24f); line(0.5f, 0.5f, 0.68f, 0.6f)
        }
        G.WEATHER -> {
            drawCircle(color, r * 0.26f, Offset(w * 0.36f, h * 0.36f), style = st)
            cloud(0.08f)
        }
        G.NOTES -> {
            drawPath(Path().apply {
                moveTo(w * 0.22f, h * 0.12f); lineTo(w * 0.64f, h * 0.12f); lineTo(w * 0.80f, h * 0.28f)
                lineTo(w * 0.80f, h * 0.88f); lineTo(w * 0.22f, h * 0.88f); close()
            }, color, style = st)
            line(0.34f, 0.42f, 0.68f, 0.42f); line(0.34f, 0.57f, 0.68f, 0.57f); line(0.34f, 0.72f, 0.54f, 0.72f)
        }
        G.MEDIA, G.PLAY -> drawPath(Path().apply {
            moveTo(w * 0.30f, h * 0.18f); lineTo(w * 0.80f, h * 0.5f); lineTo(w * 0.30f, h * 0.82f); close()
        }, color, style = if (g == G.PLAY) androidx.compose.ui.graphics.drawscope.Fill else st)
        G.PAUSE -> {
            drawRect(color, Offset(w * 0.26f, h * 0.2f), Size(w * 0.16f, h * 0.6f))
            drawRect(color, Offset(w * 0.58f, h * 0.2f), Size(w * 0.16f, h * 0.6f))
        }
        G.NEXT -> { line(0.25f, 0.2f, 0.62f, 0.5f); line(0.62f, 0.5f, 0.25f, 0.8f); line(0.75f, 0.2f, 0.75f, 0.8f) }
        G.PREV -> { line(0.75f, 0.2f, 0.38f, 0.5f); line(0.38f, 0.5f, 0.75f, 0.8f); line(0.25f, 0.2f, 0.25f, 0.8f) }
        G.FOCUS -> {
            drawOct(c, r * 0.92f, color.copy(alpha = 0.35f), width = sw)
            drawArc(color, -90f, 250f, false, Offset(w * 0.2f, h * 0.2f), Size(w * 0.6f, h * 0.6f), style = st)
        }
        G.HOUSE -> {
            drawPath(Path().apply {
                moveTo(w * 0.14f, h * 0.48f); lineTo(w * 0.5f, h * 0.16f); lineTo(w * 0.86f, h * 0.48f)
                moveTo(w * 0.24f, h * 0.40f); lineTo(w * 0.24f, h * 0.86f); lineTo(w * 0.76f, h * 0.86f); lineTo(w * 0.76f, h * 0.40f)
            }, color, style = st)
            line(0.44f, 0.86f, 0.44f, 0.64f); line(0.44f, 0.64f, 0.56f, 0.64f); line(0.56f, 0.64f, 0.56f, 0.86f)
        }
        G.APPS -> for (i in 0..2) for (j in 0..2) drawOct(Offset(w * (0.22f + 0.28f * i), h * (0.22f + 0.28f * j)), r * 0.12f, color, fill = true)
        G.SYSTEM -> {
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                drawLine(color, Offset(c.x + cos(a).toFloat() * r * 0.55f, c.y + sin(a).toFloat() * r * 0.55f),
                    Offset(c.x + cos(a).toFloat() * r * 0.88f, c.y + sin(a).toFloat() * r * 0.88f), sw, StrokeCap.Round)
            }
            drawOct(c, r * 0.42f, color, width = sw)
        }
        G.WIFI -> {
            for (i in 1..3) {
                val rr = r * 0.32f * i
                drawArc(color, 225f, 90f, false, Offset(c.x - rr, h * 0.82f - rr), Size(rr * 2, rr * 2), style = st)
            }
            drawCircle(color, sw, Offset(c.x, h * 0.80f))
        }
        G.BT -> {
            line(0.30f, 0.30f, 0.70f, 0.66f); line(0.70f, 0.66f, 0.50f, 0.84f); line(0.50f, 0.84f, 0.50f, 0.16f)
            line(0.50f, 0.16f, 0.70f, 0.34f); line(0.70f, 0.34f, 0.30f, 0.70f)
        }
        G.SUN -> {
            drawCircle(color, r * 0.34f, c, style = st)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                drawLine(color, Offset(c.x + cos(a).toFloat() * r * 0.56f, c.y + sin(a).toFloat() * r * 0.56f),
                    Offset(c.x + cos(a).toFloat() * r * 0.82f, c.y + sin(a).toFloat() * r * 0.82f), sw, StrokeCap.Round)
            }
        }
        G.MOON -> {
            drawArc(color, 60f, 250f, false, Offset(w * 0.18f, h * 0.18f), Size(w * 0.64f, h * 0.64f), style = st)
            drawArc(color, 120f, 150f, false, Offset(w * 0.40f, h * 0.10f), Size(w * 0.44f, h * 0.50f), style = st)
        }
        G.CLOUD -> cloud()
        G.FOG -> { line(0.18f, 0.35f, 0.82f, 0.35f); line(0.26f, 0.52f, 0.74f, 0.52f); line(0.18f, 0.69f, 0.82f, 0.69f) }
        G.RAIN -> { cloud(-0.12f); line(0.36f, 0.72f, 0.30f, 0.88f); line(0.52f, 0.72f, 0.46f, 0.88f); line(0.68f, 0.72f, 0.62f, 0.88f) }
        G.SNOW -> { cloud(-0.12f); for (x in listOf(0.33f, 0.5f, 0.67f)) drawCircle(color, sw * 0.9f, Offset(w * x, h * 0.82f)) }
        G.STORM -> { cloud(-0.12f); line(0.54f, 0.64f, 0.44f, 0.78f); line(0.44f, 0.78f, 0.58f, 0.78f); line(0.58f, 0.78f, 0.48f, 0.94f) }
        G.PLUS -> { line(0.5f, 0.2f, 0.5f, 0.8f); line(0.2f, 0.5f, 0.8f, 0.5f) }
        G.BACK -> { line(0.62f, 0.2f, 0.32f, 0.5f); line(0.32f, 0.5f, 0.62f, 0.8f) }
        G.TRASH -> {
            line(0.2f, 0.26f, 0.8f, 0.26f); line(0.4f, 0.26f, 0.42f, 0.14f); line(0.42f, 0.14f, 0.58f, 0.14f); line(0.58f, 0.14f, 0.6f, 0.26f)
            drawPath(Path().apply { moveTo(w * 0.28f, h * 0.30f); lineTo(w * 0.34f, h * 0.86f); lineTo(w * 0.66f, h * 0.86f); lineTo(w * 0.72f, h * 0.30f) }, color, style = st)
        }
        G.LOCK -> {
            drawArc(color, 180f, 180f, false, Offset(w * 0.3f, h * 0.14f), Size(w * 0.4f, h * 0.4f), style = st)
            line(0.3f, 0.34f, 0.3f, 0.44f); line(0.7f, 0.34f, 0.7f, 0.44f)
            drawOct(Offset(c.x, h * 0.66f), r * 0.36f, color, width = sw)
        }
    }
}

@Composable
fun IconTap(g: G, color: Color = V.Text, size: Dp = 52.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(OctS).background(V.Panel2).border(1.dp, V.Line, OctS).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Glyph(g, color, Modifier.size(size * 0.5f)) }
}

@Composable
fun Gap(w: Dp = 0.dp, h: Dp = 0.dp) = Spacer(Modifier.width(w).height(h))

@Composable
fun Centered(modifier: Modifier, content: @Composable BoxScope.() -> Unit) =
    Box(modifier, contentAlignment = Alignment.Center, content = content)

