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
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import com.andysocial.voidos.R
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
    onDone: (() -> Unit)? = null,
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
            keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password)
                else if (onDone != null) KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done) else KeyboardOptions.Default,
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone?.invoke() }),
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
/**
 * The VoidOS icon set: vector drawables in res/drawable/vi_*.xml, generated by
 * tools/icons.py (chamfered corners, octagons, one stroke weight, a 35% second
 * tone). Tinting keeps the second tone because it lives in the path alpha.
 */
enum class G(@DrawableRes val res: Int) {
    FACE(R.drawable.vi_face), WEATHER(R.drawable.vi_weather), NOTES(R.drawable.vi_notes), MEDIA(R.drawable.vi_media),
    FOCUS(R.drawable.vi_focus), HOUSE(R.drawable.vi_house), APPS(R.drawable.vi_apps), SYSTEM(R.drawable.vi_system),
    KITCHEN(R.drawable.vi_kitchen), TIMER(R.drawable.vi_timer), LIST(R.drawable.vi_list), CONVERT(R.drawable.vi_convert),
    WIFI(R.drawable.vi_wifi), BT(R.drawable.vi_bt), SUN(R.drawable.vi_sun), MOON(R.drawable.vi_moon), CLOUD(R.drawable.vi_cloud),
    RAIN(R.drawable.vi_rain), SNOW(R.drawable.vi_snow), STORM(R.drawable.vi_storm), FOG(R.drawable.vi_fog),
    PLAY(R.drawable.vi_play), PAUSE(R.drawable.vi_pause), NEXT(R.drawable.vi_next), PREV(R.drawable.vi_prev),
    PLUS(R.drawable.vi_plus), MINUS(R.drawable.vi_minus), BACK(R.drawable.vi_back), CHEV(R.drawable.vi_chev),
    TRASH(R.drawable.vi_trash), LOCK(R.drawable.vi_lock), CHECK(R.drawable.vi_check), CLOSE(R.drawable.vi_close),
    SEARCH(R.drawable.vi_search), BELL(R.drawable.vi_bell), VOLUME(R.drawable.vi_volume), BATTERY(R.drawable.vi_battery),
    DISPLAY(R.drawable.vi_display), INFO(R.drawable.vi_info), ROTATE(R.drawable.vi_rotate), LIGHT(R.drawable.vi_light),
    MESSAGE(R.drawable.vi_message), VIDEO(R.drawable.vi_video), POWER(R.drawable.vi_power), GLOBE(R.drawable.vi_globe),
    CAMERA(R.drawable.vi_camera), STAR(R.drawable.vi_star), STAR_OFF(R.drawable.vi_star_off), USER(R.drawable.vi_user),
    SHUFFLE(R.drawable.vi_shuffle), PHONE(R.drawable.vi_phone), CUTLERY(R.drawable.vi_cutlery),
}

@Suppress("UNUSED_PARAMETER")
@Composable
fun Glyph(g: G, color: Color, modifier: Modifier = Modifier.size(28.dp), weight: Float = 2.2f) {
    Image(painterResource(g.res), contentDescription = null, modifier = modifier, colorFilter = ColorFilter.tint(color))
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

