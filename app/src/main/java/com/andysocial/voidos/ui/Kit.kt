package com.andysocial.voidos.ui

import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.lerp
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
import androidx.compose.ui.graphics.Brush
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

/** Panel corners: the old octagon chamfer. */
val Oct: androidx.compose.ui.graphics.Shape = Radius.Panel
/** Card and control corners, the same chamfer, smaller. */
val OctS: androidx.compose.ui.graphics.Shape = Radius.Card
/** Fully round ends. */
val Pill = RoundedCornerShape(50)

@Composable
fun VText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 14.sp,
    color: Color = V.Text,
    font: FontFamily = Sans,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
    spacing: TextUnit = 0.sp,
    lineHeight: TextUnit = TextUnit.Unspecified,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = size, fontFamily = font, textAlign = align, letterSpacing = spacing, lineHeight = lineHeight, fontFeatureSettings = "tnum"),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Small grey uppercase label, the watch's "STEPS" style. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = V.Muted) =
    VText(text.uppercase(), modifier, size = Type.caption, color = if (color == V.Muted || color == V.Tick) color else calm(color), font = SansMedium, spacing = 1.sp)

@Composable
fun VPanel(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    padding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    // a quiet glass card: lighter at the top, no rim, no coloured frame
    Column(
        modifier
            .pane(Oct)
            .padding(padding),
        content = content,
    )
}

fun Modifier.tap(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = MutableInteractionSource(), indication = null, onClick = onClick,
)

/** Pill button. Filled = the main action, in the accent; otherwise a quiet grey pill. */
@Composable
fun VButton(
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = V.Teal,
    filled: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = calm(accent)
    val warn = c == V.Coral
    Box(
        modifier
            .then(
                when {
                    !enabled -> Modifier.clip(BarShape).background(V.Panel2.copy(alpha = 0.4f))
                    filled -> Modifier.droplet(BarShape, c, 2.4f)
                    else -> Modifier.glass(BarShape, 0.8f)
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        VText(
            label, size = Type.body, font = SansMedium, maxLines = 1,
            color = when {
                !enabled -> V.Tick
                filled -> V.Bg
                warn -> V.Coral
                else -> V.Text
            },
        )
    }
}

/** On/off switch: a glass capsule that fills with coloured glass, and a glossy bead that slides. */
@Composable
fun VToggle(checked: Boolean, accent: Color = V.Teal, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val t by animateFloatAsState(if (checked) 1f else 0f, Motion.pop(), label = "tog")
    val c = calm(accent)
    Canvas(
        Modifier
            .size(54.dp, 32.dp)
            .clip(Pill)
            .clickable(enabled = enabled) { onChange(!checked) },
    ) { drawToggle(t, if (enabled) c else V.Tick, enabled) }
}

internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawToggle(t: Float, c: Color, enabled: Boolean) {
    val w = size.width; val h = size.height
    val cr = CornerRadius(h / 2)
    drawRoundRect(Color.White.copy(alpha = 0.10f), cornerRadius = cr)
    drawRoundRect(Brush.verticalGradient(listOf(lerp(c, Color.White, 0.25f), c, lerp(c, Color.Black, 0.15f))), cornerRadius = cr, alpha = t)
    // gloss over the top half
    drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent), endY = h * 0.55f), cornerRadius = cr)
    val r = h / 2 - 3.dp.toPx()
    val cx = h / 2 + (w - h) * t
    val ctr = Offset(cx, h / 2)
    drawCircle(Color.Black.copy(alpha = 0.28f), r + 1.dp.toPx(), ctr + Offset(0f, 1.5f.dp.toPx()))
    drawBead(ctr, r, if (enabled) Color.White else V.Muted)
}

/** A glossy glass bead: lit from the top left, with a bright specular dot. */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBead(ctr: Offset, r: Float, c: Color) {
    drawCircle(Brush.radialGradient(listOf(c, lerp(c, Color(0xFFB8C2CC), 0.35f)), center = ctr - Offset(r * 0.3f, r * 0.4f), radius = r * 1.5f), r, ctr)
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.95f), Color.Transparent), center = ctr - Offset(r * 0.35f, r * 0.42f), radius = r * 0.55f), r * 0.55f, ctr - Offset(r * 0.35f, r * 0.42f))
}

/** A setting row: title + optional detail on the left, control on the right. */
@Composable
fun SettingRow(title: String, detail: String? = null, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // lean rows: the name and its control. [detail] shows only as a short live status ("PIN changed"), never an explanation
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            VText(sentence(title), size = Type.headline, color = V.Text, maxLines = 1)
            if (detail != null && detail.length <= 40) {
                Spacer(Modifier.height(2.dp))
                VText(detail, size = Type.label, color = V.Muted, maxLines = 1)
            }
        }
        control()
    }
}

/** "WI-FI NETWORKS" -> "Wi-fi networks": old call sites passed shouty titles. */
fun sentence(s: String): String =
    if (s.any { it.isLowerCase() }) s else s.lowercase().replaceFirstChar { it.uppercase() }

/** Drag/tap slider, 0..1. */
@Composable
fun VSlider(value: Float, modifier: Modifier = Modifier, accent: Color = V.Teal, onChange: (Float) -> Unit) {
    val cb by rememberUpdatedState(onChange)
    val c = calm(accent)
    Canvas(
        modifier
            .height(40.dp)
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
        val r = 12.dp.toPx()
        val x = r + (size.width - 2 * r) * value.coerceIn(0f, 1f)
        // a glass channel, filled with coloured glass up to the bead
        val th = 10.dp.toPx()
        val cr = CornerRadius(th / 2)
        drawRoundRect(Color.White.copy(alpha = 0.075f), Offset(0f, y - th / 2), Size(size.width, th), cr)
        drawRoundRect(Brush.verticalGradient(listOf(lerp(c, Color.White, 0.3f), c), startY = y - th / 2, endY = y + th / 2), Offset(0f, y - th / 2), Size(x, th), cr)
        drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent), startY = y - th / 2, endY = y), Offset(0f, y - th / 2), Size(size.width, th / 2), cr)
        drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.35f), Color.Transparent), center = Offset(x, y), radius = r * 2.2f), r * 2.2f, Offset(x, y))
        drawCircle(Color.Black.copy(alpha = 0.32f), r + 1.5.dp.toPx(), Offset(x, y + 1.5.dp.toPx()))
        drawBead(Offset(x, y), r, V.White)
    }
}

/** An invisible 0..1 touch strip: tap or drag sideways. Draw the level yourself underneath. */
@Composable
fun VSliderTouch(modifier: Modifier, onChange: (Float) -> Unit) {
    val cb by rememberUpdatedState(onChange)
    Box(
        modifier
            .pointerInput(Unit) { detectTapGestures { o -> cb((o.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    cb((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }
    )
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
    font: FontFamily = Sans,
    onDone: (() -> Unit)? = null,
) {
    // no back button on the tablet: Done always puts the keyboard away
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    Box(
        modifier
            .clip(OctS)
            .background(V.Panel2)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        if (value.isEmpty()) VText(placeholder, size = size, color = V.Muted, font = font)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            textStyle = TextStyle(color = V.Text, fontSize = size, fontFamily = font),
            cursorBrush = SolidColor(V.Teal),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = androidx.compose.ui.text.input.ImeAction.Done)
                else if (singleLine) KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done) else KeyboardOptions.Default,
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone?.invoke(); focus.clearFocus() }),
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

/** A dot or ring (named from the old octagon style; now a plain circle). */
fun DrawScope.drawOct(c: Offset, r: Float, color: Color, fill: Boolean = false, width: Float = 2f) {
    if (fill) drawCircle(color, r, c) else drawCircle(color, r, c, style = Stroke(width))
}

/**
 * The VoidOS icon set: vector drawables in res/drawable/vi_*.xml, generated by
 * tools/icons.py (calm line icons, one stroke weight).
 */
enum class G(@DrawableRes val res: Int) {
    FACE(R.drawable.vi_face), WEATHER(R.drawable.vi_weather), NOTES(R.drawable.vi_notes), MEDIA(R.drawable.vi_media),
    FOCUS(R.drawable.vi_focus), HOUSE(R.drawable.vi_house), APPS(R.drawable.vi_apps), SYSTEM(R.drawable.vi_system),
    KITCHEN(R.drawable.vi_kitchen), TIMER(R.drawable.vi_timer), LIST(R.drawable.vi_list), CONVERT(R.drawable.vi_convert), CALC(R.drawable.vi_calc),
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
    PEN(R.drawable.vi_pen), COMPOSE(R.drawable.vi_compose), FOLDER(R.drawable.vi_folder),
    WIFI_OFF(R.drawable.vi_wifi_off), PLANE(R.drawable.vi_plane), PIN(R.drawable.vi_pin), CAST(R.drawable.vi_cast), QR(R.drawable.vi_qr),
    SHIELD(R.drawable.vi_shield), SEND(R.drawable.vi_send), STOPWATCH(R.drawable.vi_stopwatch), LEVEL(R.drawable.vi_level), MIC(R.drawable.vi_mic),
    METER(R.drawable.vi_meter), TORCH(R.drawable.vi_torch), SCREENSHOT(R.drawable.vi_screenshot), RECORD(R.drawable.vi_record), LEAF(R.drawable.vi_leaf),
    CONTRAST(R.drawable.vi_contrast), EYE(R.drawable.vi_eye), TEXT(R.drawable.vi_text), USB(R.drawable.vi_usb), SD(R.drawable.vi_sd),
    WORLD(R.drawable.vi_world), RADIO(R.drawable.vi_radio), CALENDAR(R.drawable.vi_calendar), ASSISTANT(R.drawable.vi_assistant),
    SPLIT(R.drawable.vi_split), SCAN(R.drawable.vi_scan), MIRROR(R.drawable.vi_mirror), ZOOM(R.drawable.vi_zoom), DOC(R.drawable.vi_doc),
    WAVE(R.drawable.vi_wave), RING(R.drawable.vi_ring), RESTART(R.drawable.vi_restart), FILES(R.drawable.vi_files), VIBRATE(R.drawable.vi_vibrate),
    FOCUS_MODE(R.drawable.vi_focus_mode), WORK(R.drawable.vi_work), BED(R.drawable.vi_bed), BOOK(R.drawable.vi_book), GAME(R.drawable.vi_game),
    DOWNLOAD(R.drawable.vi_download), MONEY(R.drawable.vi_money), BUG(R.drawable.vi_bug), CHART(R.drawable.vi_chart),
    ADS(R.drawable.vi_ads), REVIEW(R.drawable.vi_review), UPTIME(R.drawable.vi_uptime), CODE(R.drawable.vi_code),
    CLOUD_UP(R.drawable.vi_cloud_up), MAIL(R.drawable.vi_mail), KEY(R.drawable.vi_key), HUB(R.drawable.vi_hub),
    DNS(R.drawable.vi_dns), WEB(R.drawable.vi_web), STORE(R.drawable.vi_store), REFRESH(R.drawable.vi_refresh),
    RECEIPT(R.drawable.vi_receipt), EDIT_LIST(R.drawable.vi_edit_list),
}

@Suppress("UNUSED_PARAMETER")
@Composable
fun Glyph(g: G, color: Color, modifier: Modifier = Modifier.size(28.dp), weight: Float = 2.2f) {
    Image(painterResource(g.res), contentDescription = null, modifier = modifier, colorFilter = ColorFilter.tint(color))
}

@Composable
fun IconTap(g: G, color: Color = V.Text, size: Dp = 52.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).glass(OctS, 0.8f).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Glyph(g, color, Modifier.size(size * 0.5f)) }
}

@Composable
fun Gap(w: Dp = 0.dp, h: Dp = 0.dp) = Spacer(Modifier.width(w).height(h))

@Composable
fun Centered(modifier: Modifier, content: @Composable BoxScope.() -> Unit) =
    Box(modifier, contentAlignment = Alignment.Center, content = content)

