package com.andysocial.voidos.ui

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.wifi.WifiManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andysocial.voidos.MainActivity
import com.andysocial.voidos.Now
import com.andysocial.voidos.Radio
import com.andysocial.voidos.Store
import com.andysocial.voidos.FocusMode
import com.andysocial.voidos.VoidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min

/** The parts of the control centre that open up when held. */
enum class CcPart { CONNECT, WIFI, BT, DND, ROTATE, BRIGHT, VOLUME, MEDIA, NOTICES, POWER }

/**
 * Where the control centre reads the radios from. Tests swap these for fixed
 * rows; [live] off stops scanning and broadcast receivers there.
 */
object CcSource {
    var wifiOn: (Context) -> Boolean = { Radio.wifiOn(it) }
    var ssid: (Context) -> String? = { Radio.connectedSsid(it) }
    var networks: (Context) -> List<Radio.Net> = { Radio.networks(it) }
    var btOn: () -> Boolean = { Radio.btOn() }
    var devices: () -> List<Radio.Dev> = { Radio.paired(Radio.connectedAddresses()) }
    var live = true

    fun reset() {
        wifiOn = { Radio.wifiOn(it) }; ssid = { Radio.connectedSsid(it) }; networks = { Radio.networks(it) }
        btOn = { Radio.btOn() }; devices = { Radio.paired(Radio.connectedAddresses()) }; live = true
    }
}

private val BtBlue = Color(0xFF5A8CF0)

/** Control-centre glass: glossy and magnifying, with no rim or outline. */
private fun Modifier.ccGlass(shape: Shape, smoke: Float = 0.62f, stain: Color? = null, lit: Float = 0f): Modifier =
    liquidGlass(shape, GlassLook(smoke = smoke - 0.4f * lit, depth = 0.45f, shine = 0.95f + 0.35f * lit, magnify = 1.16f + 0.22f * lit,
        stain = stain, stainAlpha = 0.8f * lit, edge = 0f))

/** Everything the control centre shows, read and written off the main thread where Android is slow. */
private class CcState(val ctx: Context, val act: MainActivity, val scope: CoroutineScope) {
    var bright by mutableFloatStateOf(Tablet.brightness(ctx))
    var auto by mutableStateOf(Tablet.autoBrightness(ctx))
    var vol by mutableFloatStateOf(Tablet.volume(ctx))
    var wifi by mutableStateOf(CcSource.wifiOn(ctx))
    var ssid by mutableStateOf(CcSource.ssid(ctx))
    var bt by mutableStateOf(CcSource.btOn())
    var dnd by mutableIntStateOf(Tablet.dndMode(ctx))
    var night by mutableStateOf(Tablet.nightLight(ctx))
    var note by mutableStateOf<String?>(null)
    /** A switch is mid-change: don't let the refresh flick it back. */
    var holdUntil = 0L

    fun refresh() {
        if (System.currentTimeMillis() < holdUntil) return
        wifi = CcSource.wifiOn(ctx); ssid = CcSource.ssid(ctx); bt = CcSource.btOn(); dnd = Tablet.dndMode(ctx)
    }

    private fun hold() { holdUntil = System.currentTimeMillis() + 7000 }

    fun switchWifi(on: Boolean) {
        wifi = on; hold()
        scope.launch {
            val ok = withContext(Dispatchers.IO) { Tablet.setWifi(ctx, on) }
            holdUntil = 0; refresh()
            if (!ok) note = "Wi-Fi didn't switch. Try again."
        }
    }

    fun switchBt(on: Boolean) {
        bt = on; hold()
        scope.launch {
            val ok = withContext(Dispatchers.IO) { Tablet.setBt(on) }
            holdUntil = 0; refresh()
            if (!ok) note = "Bluetooth didn't switch. Try again."
        }
    }

    fun switchDnd(mode: Int) {
        val was = dnd
        dnd = mode; hold()
        scope.launch {
            val ok = withContext(Dispatchers.IO) { Tablet.setDndMode(ctx, mode) }
            holdUntil = 0
            if (!ok) { dnd = was; note = "Focus didn't switch. Try again." } else refresh()
        }
    }

    fun switchNight(on: Boolean) {
        night = on
        scope.launch {
            val ok = withContext(Dispatchers.IO) { Tablet.setNightLight(ctx, on) }
            night = Tablet.nightLight(ctx).let { if (ok) on else it }
            if (!ok) note = "Night Light didn't switch. Try again."
        }
    }
}

/**
 * The control centre, built like an iPad's: pulled down from the top edge (or
 * opened by tapping the status line). Modules float on the dimmed screen with
 * no box round them. Hold a module to open it up: Wi-Fi lists networks to
 * join, Bluetooth lists devices, and so on. There is no way out of VoidOS here.
 */
@Composable
fun ControlCentre(
    open: Boolean,
    store: Store,
    act: MainActivity,
    onClose: () -> Unit,
    onSettings: () -> Unit,
    startExpanded: CcPart? = null,
) {
    var expanded by remember(open, startExpanded) { mutableStateOf(if (open) startExpanded else null) }
    BackHandler(enabled = open) { if (expanded != null) expanded = null else onClose() }
    val closeOrBack = { if (expanded != null) expanded = null else onClose() }

    AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.66f)).ccAurora().tap(closeOrBack))
    }
    AnimatedVisibility(
        open,
        enter = slideInVertically(Motion.springOffset) { -it / 8 } + fadeIn(),
        exit = slideOutVertically { -it / 8 } + fadeOut(),
    ) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val st = remember { CcState(ctx, act, scope) }
        LaunchedEffect(Unit) { while (isActive) { st.refresh(); delay(2000) } }
        LaunchedEffect(st.note) { if (st.note != null) { delay(5000); st.note = null } }

        val dim by animateFloatAsState(if (expanded != null) 1f else 0f, Motion.fade(), label = "dim")
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val w = maxWidth
            val h = maxHeight
            var pulled by remember { mutableFloatStateOf(0f) }
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { alpha = 1f - 0.8f * dim; val s = 1f - 0.05f * dim; scaleX = s; scaleY = s }
                    .then(if (expanded != null) Modifier.clearAndSetSemantics { } else Modifier)
                    .tap(closeOrBack)
                    .pointerInput(Unit) {
                        // flick up to close
                        detectVerticalDragGestures(onDragEnd = { if (pulled < -80f) onClose(); pulled = 0f }) { ch, dy ->
                            pulled += dy; if (pulled < -40f) ch.consume()
                        }
                    },
                contentAlignment = Alignment.TopCenter,
            ) {
                CcGrid(st, store, act, w, h, onClose, onSettings) { expanded = it }
            }
            // while a module is open, the grid behind it is out of reach: a tap there just closes the module
            if (expanded != null) Box(Modifier.fillMaxSize().clearAndSetSemantics { }.tap { expanded = null })

            // the held module, opened up in the middle of the screen
            var shown by remember { mutableStateOf<CcPart?>(null) }
            if (expanded != null) shown = expanded
            AnimatedVisibility(
                expanded != null,
                modifier = Modifier.align(Alignment.Center),
                enter = scaleIn(Motion.pop(), initialScale = 0.82f) + fadeIn(Motion.quick()),
                exit = scaleOut(Motion.quick(), targetScale = 0.88f) + fadeOut(Motion.quick()),
            ) {
                shown?.let { part ->
                    Column(
                        Modifier
                            .widthIn(max = 460.dp)
                            .width(min(w - 32.dp, 460.dp))
                            .heightIn(max = h * 0.88f)
                            .ccGlass(RoundedCornerShape(36.dp), smoke = 0.74f)
                            .tap { }
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                    ) {
                        when (part) {
                            CcPart.CONNECT -> ConnectPanel(st, store, act) { expanded = it }
                            CcPart.WIFI -> WifiPanel(st)
                            CcPart.BT -> BtPanel(st)
                            CcPart.DND -> DndPanel(st)
                            CcPart.ROTATE -> RotatePanel(store, act)
                            CcPart.BRIGHT -> BrightPanel(st)
                            CcPart.VOLUME -> VolumePanel(st)
                            CcPart.MEDIA -> MediaPanel()
                            CcPart.NOTICES -> NoticesPanel { expanded = null; onClose() }
                            CcPart.POWER -> PowerPanel()
                        }
                    }
                }
            }

            // short messages float at the bottom, in glass
            st.note?.let { msg ->
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp, start = 24.dp, end = 24.dp)
                    .widthIn(max = 520.dp).ccGlass(Pill, smoke = 0.8f).padding(horizontal = 20.dp, vertical = 12.dp)) {
                    VText(msg, size = Type.label, color = lerp(V.Coral, Color.White, 0.25f))
                }
            }
        }
    }
}

// ------------------------------------------------------------------- grid

@Composable
private fun CcGrid(
    st: CcState, store: Store, act: MainActivity, w: Dp, h: Dp,
    onClose: () -> Unit, onSettings: () -> Unit, expand: (CcPart) -> Unit,
) {
    val portrait = h > w
    val gap = 14.dp
    Column(
        Modifier.padding(top = 16.dp).width(if (portrait) min(w - 40.dp, 560.dp) else min(w - 56.dp, 880.dp)),
    ) {
        CcHeader(store)
        Spacer(Modifier.height(18.dp))
        val areaW = if (portrait) min(w - 40.dp, 560.dp) else min(w - 56.dp, 880.dp)
        if (portrait) {
            val side = min((areaW - gap) / 2, 230.dp)
            val bubble = ccBubbleSize(side)
            Row(Modifier.fillMaxWidth().height(side), horizontalArrangement = Arrangement.spacedBy(gap)) {
                ConnectModule(st, store, act, bubble, Modifier.weight(1f).fillMaxHeight(), expand)
                MediaModule(Modifier.weight(1f).fillMaxHeight()) { expand(CcPart.MEDIA) }
            }
            Spacer(Modifier.height(gap))
            Row(Modifier.fillMaxWidth().height(side), horizontalArrangement = Arrangement.spacedBy(gap)) {
                RoundButtons(store, act, onClose, onSettings, bubble, cols = 3, expand = expand, modifier = Modifier.weight(1f).fillMaxHeight())
                BrightSlider(st, Modifier.width((side - gap) / 2).fillMaxHeight()) { expand(CcPart.BRIGHT) }
                VolumeSlider(st, Modifier.width((side - gap) / 2).fillMaxHeight()) { expand(CcPart.VOLUME) }
            }
            Spacer(Modifier.height(gap))
            QuickToggles(onClose, bubble, Modifier.fillMaxWidth())
        } else {
            val rowH = max(200.dp, min(h - 270.dp, 330.dp))
            // the Wi-Fi square's height sets one bubble size for every switch in the control centre
            val bubble = ccBubbleSize((rowH - gap) * (1.15f / 2f))
            Row(Modifier.fillMaxWidth().height(rowH), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Column(Modifier.weight(1.3f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    ConnectModule(st, store, act, bubble, Modifier.fillMaxWidth().weight(1.15f), expand)
                    MediaModule(Modifier.fillMaxWidth().weight(0.85f)) { expand(CcPart.MEDIA) }
                }
                BrightSlider(st, Modifier.width(96.dp).fillMaxHeight()) { expand(CcPart.BRIGHT) }
                VolumeSlider(st, Modifier.width(96.dp).fillMaxHeight()) { expand(CcPart.VOLUME) }
                RoundButtons(store, act, onClose, onSettings, bubble, cols = 2, expand = expand, modifier = Modifier.weight(1f).fillMaxHeight())
            }
            Spacer(Modifier.height(gap))
            QuickToggles(onClose, bubble, Modifier.fillMaxWidth())
        }
    }
}

/** One size for every round switch: what fits two to a side in a glass square [panel] tall. */
internal fun ccBubbleSize(panel: Dp): Dp = ((panel - CcPad * 2) / 2 - 14.dp).coerceIn(44.dp, 68.dp)

/** Padding inside every control-centre glass panel. */
internal val CcPad = 12.dp

/**
 * A glass panel of round switches, the same treatment as the Wi-Fi square: one rounded pane,
 * bubbles all one size, laid out [cols] across and spaced evenly both ways.
 */
@Composable
private fun CcCluster(items: List<CcItem>, cols: Int, bubble: Dp, modifier: Modifier) {
    Column(
        modifier.ccGlass(RoundedCornerShape(Radius.panel)).padding(CcPad),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        items.chunked(cols.coerceAtLeast(1)).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { CcBubble(it.g, it.label, it.on, it.accent, bubble, it.tap, it.hold ?: it.tap) }
                // keep a short last row in the same columns as the rows above it
                repeat(cols - row.size) { Spacer(Modifier.size(bubble)) }
            }
        }
    }
}

/** One round switch in a [CcCluster]. */
internal class CcItem(val g: G, val label: String, val on: Boolean, val accent: Color, val hold: (() -> Unit)? = null, val tap: () -> Unit)

@Composable
private fun CcHeader(store: Store) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (isActive) { now = LocalDateTime.now(); delay(1000) } }
    val ticker = rememberTicker()
    val thin = rememberFace("sans-serif-thin")
    val dens = LocalDensity.current
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            VText(greeting(System.currentTimeMillis()), size = Type.label, color = lerp(V.Teal, Color.White, 0.3f), spacing = 0.5.sp)
            RollingText(now.format(DateTimeFormatter.ofPattern(if (store.use24h) "HH:mm" else "h:mm")), with(dens) { 50.sp.toPx() }, thin, tick = ticker)
            VText(now.format(DateTimeFormatter.ofPattern("EEEE d MMMM")), size = Type.body, color = V.Muted)
        }
        val bat = rememberBattery()
        if (bat.pct >= 0) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            Box(Modifier.size(24.dp, 12.dp).clip(RoundedCornerShape(Radius.chip)).background(V.Text.copy(alpha = 0.22f)).padding(2.dp)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth((bat.pct / 100f).coerceIn(0.05f, 1f)).clip(RoundedCornerShape(Radius.chip))
                    .background(if (bat.pct <= 15 && !bat.charging) V.Coral else V.Text))
            }
            Spacer(Modifier.width(8.dp))
            VText("${bat.pct}%" + if (bat.charging) " charging" else "", size = Type.label, color = V.Text)
        }
    }
}

/** Tap and hold on one thing: the hold opens it up. */
private fun Modifier.tapOrHold(onTap: (() -> Unit)?, onHold: () -> Unit): Modifier = this.pointerInput(onTap, onHold) {
    detectTapGestures(onTap = { onTap?.invoke() }, onLongPress = { onHold() })
}

/** Wi-Fi, Bluetooth, Do Not Disturb and rotation lock: four round switches in one glass square. */
@Composable
private fun ConnectModule(st: CcState, store: Store, act: MainActivity, bubble: Dp, modifier: Modifier, expand: (CcPart) -> Unit) {
    val pinned = store.rotation != "auto"
    val portrait = LocalPortrait.current
    BoxWithConstraints(
        modifier.ccGlass(RoundedCornerShape(Radius.panel)).tapOrHold(null) { expand(CcPart.CONNECT) }.padding(CcPad),
        contentAlignment = Alignment.Center,
    ) {
        val d = min(min(maxWidth, maxHeight) / 2 - 6.dp, bubble)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                CcBubble(G.WIFI, "Wi-Fi", st.wifi, V.Teal, d, { st.switchWifi(!st.wifi) }) { expand(CcPart.WIFI) }
                CcBubble(G.BT, "Bluetooth", st.bt, BtBlue, d, { st.switchBt(!st.bt) }) { expand(CcPart.BT) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                val dndOn = st.dnd != NotificationManager.INTERRUPTION_FILTER_ALL
                // Focus: a tap opens the list of Focus modes; the one that's on shows its own icon
                val fm = store.focusModes.firstOrNull { it.name == store.focusOn }
                CcBubble(if (dndOn && fm != null) focusGlyph(fm.glyph) else G.FOCUS_MODE, if (dndOn && fm != null) fm.name else "Focus", dndOn, V.Purple, d, { expand(CcPart.DND) }) { expand(CcPart.DND) }
                CcBubble(G.ROTATE, "Rotation lock", pinned, V.Coral, d, {
                    store.setRotation(if (pinned) "auto" else if (portrait) "port" else "land"); act.applyRotation()
                }) { expand(CcPart.ROTATE) }
            }
        }
    }
}

/** A round glass switch. Stained with its own colour and glowing when on. */
@Composable
private fun CcBubble(g: G, label: String, on: Boolean, accent: Color, d: Dp, onTap: () -> Unit, onHold: () -> Unit) {
    val lit by animateFloatAsState(if (on) 1f else 0f, Motion.pop(), label = "lit")
    Box(
        Modifier.size(d)
            .semantics { contentDescription = label; selected = on }
            .drawBehind { if (lit > 0f) drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.42f * lit), Color.Transparent)), size.minDimension * 0.92f) }
            .graphicsLayer { val s = 0.95f + 0.05f * lit; scaleX = s; scaleY = s }
            .ccGlass(CircleShape, smoke = 0.72f, stain = accent, lit = lit)
            .tapOrHold(onTap, onHold),
        contentAlignment = Alignment.Center,
    ) { Glyph(g, lerp(V.Text, Color.White, lit), Modifier.size(d * 0.4f)) }
}

/** A tall glass slider: drag up and down; hold to open it up. The light fill is the level. */
@Composable
private fun CcTall(g: G, value: Float, modifier: Modifier, fill: List<Color>, label: String, onHold: (() -> Unit)?, onChange: (Float) -> Unit) {
    val cb by rememberUpdatedState(onChange)
    val hold by rememberUpdatedState(onHold)
    val v = value.coerceIn(0f, 1f)
    Box(
        modifier.semantics { contentDescription = label }
            .ccGlass(RoundedCornerShape(Radius.panel))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { o -> cb((1f - o.y / size.height).coerceIn(0f, 1f)) }, onLongPress = { hold?.invoke() })
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures { ch, _ -> ch.consume(); cb((1f - ch.position.y / size.height).coerceIn(0f, 1f)) }
            },
    ) {
        val shown by animateFloatAsState(v.coerceAtLeast(0.07f), Motion.press(), label = "level")
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(shown)
                .background(Brush.verticalGradient(fill))
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.06f), Color.Transparent), endY = 90f)),
        )
        Glyph(g, if (v > 0.16f) V.Bg else V.Text, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).size(26.dp))
    }
}

private val SunFill = listOf(Color(0xFFFFF4D6), Color(0xFFFFD27A))
private val VolFill = listOf(lerp(V.Teal, Color.White, 0.55f), V.Teal)

@Composable
private fun BrightSlider(st: CcState, modifier: Modifier, onHold: () -> Unit) =
    CcTall(G.SUN, st.bright, modifier, SunFill, "Brightness", onHold) { st.bright = it; st.auto = false; Tablet.setBrightness(st.act, it) }

@Composable
private fun VolumeSlider(st: CcState, modifier: Modifier, onHold: () -> Unit) =
    CcTall(G.VOLUME, st.vol, modifier, VolFill, "Volume", onHold) { st.vol = it; Tablet.setVolume(st.ctx, it) }

/** What's playing, small: title, artist and play/pause. Hold for the full player. */
@Composable
private fun MediaModule(modifier: Modifier, onHold: () -> Unit) {
    val p by Now.playing
    val ctx = LocalContext.current
    Column(
        modifier.ccGlass(RoundedCornerShape(Radius.panel)).tapOrHold({ p?.let { Now.open(ctx) } }, onHold).padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        val cur = p
        if (cur == null) {
            Glyph(G.MEDIA, V.Muted, Modifier.size(24.dp))
            VText("Not playing", size = Type.body, color = V.Muted)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                cur.art?.let { Image(it.asImageBitmap(), null, Modifier.size(40.dp).clip(RoundedCornerShape(Radius.chip)), contentScale = ContentScale.Crop); Spacer(Modifier.width(10.dp)) }
                Column(Modifier.weight(1f)) {
                    VText(cur.title.ifBlank { cur.appName }, size = Type.body, font = SansMedium, color = V.Text, maxLines = 1)
                    VText(cur.artist.ifBlank { cur.appName }, size = Type.label, color = V.Muted, maxLines = 1)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconTap(G.PREV, 22.dp) { Now.prev() }
                IconTap(if (cur.playing) G.PAUSE else G.PLAY, 30.dp) { Now.playPause() }
                IconTap(G.NEXT, 22.dp) { Now.next() }
            }
        }
    }
}

@Composable
private fun IconTap(g: G, size: Dp, color: Color = V.Text, onClick: () -> Unit) {
    Box(Modifier.size(size + 22.dp).clip(CircleShape).tap(onClick), contentAlignment = Alignment.Center) { Glyph(g, color, Modifier.size(size)) }
}

/** Stay awake, notifications, assistant, settings and power: round switches in one glass panel, like Wi-Fi's. */
@Composable
private fun RoundButtons(store: Store, act: MainActivity, onClose: () -> Unit, onSettings: () -> Unit, bubble: Dp, cols: Int, modifier: Modifier, expand: (CcPart) -> Unit = {}) {
    val ctx = LocalContext.current
    val items = buildList {
        add(CcItem(G.LIGHT, "Stay awake", store.deskMode, V.Sun) { store.set("deskMode", !store.deskMode); act.applyPolicies() })
        add(CcItem(G.BELL, "Notifications", com.andysocial.voidos.VoidListener.notices.value.isNotEmpty(), V.Teal) { expand(CcPart.NOTICES) })
        com.andysocial.voidos.Extras.assistantIntent(ctx)?.let { ai ->
            add(CcItem(G.ASSISTANT, "Assistant", false, V.Purple) {
                onClose()
                ai.resolveActivity(ctx.packageManager)?.packageName?.let { p -> if (p !in store.allowed) store.setAllowed(p, true); act.applyPolicies() }
                runCatching { ctx.startActivity(ai) }
            })
        }
        add(CcItem(G.SYSTEM, "Settings", false, V.Text) { onClose(); onSettings() })
        add(CcItem(G.POWER, "Power", false, V.Text) { expand(CcPart.POWER) })
    }
    CcCluster(items, cols, bubble, modifier)
}

/** The control centre's own slow light, drifting behind the modules. */
@Composable
private fun Modifier.ccAurora(): Modifier {
    val tick = rememberTicker(50L)
    return drawBehind {
        val k = (phase(tick.value, 30_000L) * 2 * Math.PI).toFloat()
        val r = maxOf(size.width, size.height)
        fun blob(c: Color, x: Float, y: Float, rad: Float, a: Float) {
            val o = Offset(x, y)
            drawCircle(Brush.radialGradient(listOf(c.copy(alpha = a), Color.Transparent), center = o, radius = rad), rad, o)
        }
        blob(V.Purple, size.width * (0.15f + 0.06f * kotlin.math.sin(k)), size.height * 0.1f, r * 0.5f, 0.22f)
        blob(V.Teal, size.width * (0.9f + 0.04f * kotlin.math.cos(k)), size.height * (0.8f + 0.06f * kotlin.math.sin(2 * k)), r * 0.45f, 0.18f)
        blob(V.Coral, size.width * (0.55f + 0.15f * kotlin.math.sin(k + 2f)), size.height * 0.5f, r * 0.25f, 0.07f)
    }
}

// ------------------------------------------------------ opened-up modules

/** The opened-up module's title row: a big round switch, the name and its state. */
@Composable
private fun PanelHead(g: G, title: String, state: String, on: Boolean, accent: Color, onToggle: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val lit by animateFloatAsState(if (on) 1f else 0f, label = "lit")
        Box(
            Modifier.size(52.dp).semantics { contentDescription = title; selected = on }
                .ccGlass(CircleShape, smoke = 0.72f, stain = accent, lit = lit)
                .then(if (onToggle != null) Modifier.tap(onToggle) else Modifier),
            contentAlignment = Alignment.Center,
        ) { Glyph(g, if (on) Color.White else V.Text, Modifier.size(22.dp)) }
        Spacer(Modifier.width(14.dp))
        Column {
            VText(title, size = Type.title, font = SansMedium, color = V.Text)
            VText(state, size = Type.label, color = if (on) lerp(accent, Color.White, 0.3f) else V.Muted, maxLines = 1)
        }
    }
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun Section(text: String) {
    VText(text, size = Type.caption, color = V.Muted, spacing = 0.6.sp, modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 8.dp))
}

/** One row in an opened-up module: soft glass when chosen, bare otherwise. No lines between rows. */
@Composable
private fun PanelRow(
    chosen: Boolean = false,
    accent: Color = V.Teal,
    onClick: (() -> Unit)?,
    lead: @Composable () -> Unit,
    title: String,
    sub: String? = null,
    trail: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .then(if (chosen) Modifier.ccGlass(RoundedCornerShape(Radius.card), smoke = 0.55f, stain = accent, lit = 0.35f) else Modifier.clip(RoundedCornerShape(Radius.card)))
            .then(if (onClick != null) Modifier.tap(onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { lead() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            VText(title, size = Type.body, color = V.Text, maxLines = 1)
            sub?.let { VText(it, size = Type.caption, color = V.Muted, maxLines = 2) }
        }
        trail()
    }
}

@Composable
private fun SmallPill(text: String, color: Color = V.Text, onClick: () -> Unit) {
    Box(Modifier.ccGlass(Pill, smoke = 0.6f).tap(onClick).padding(horizontal = 16.dp, vertical = 8.dp)) {
        VText(text, size = Type.label, color = color, maxLines = 1)
    }
}

/** Wi-Fi signal as three arcs and a dot; [bars] 0..4 of them lit. */
@Composable
fun WifiBars(bars: Int, color: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height * 0.86f)
        val dim = color.copy(alpha = 0.25f)
        drawCircle(if (bars >= 1) color else dim, size.minDimension * 0.08f, c)
        for (i in 1..3) {
            val r = size.minDimension * (0.24f + 0.25f * i)
            drawArc(
                if (bars >= i + 1) color else dim, startAngle = 225f, sweepAngle = 90f, useCenter = false,
                topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2),
                style = Stroke(size.minDimension * 0.1f, cap = StrokeCap.Round),
            )
        }
    }
}

/** A glass text field, for Wi-Fi passwords. */
@Composable
private fun GlassField(value: String, onChange: (String) -> Unit, placeholder: String, hidden: Boolean, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.ccGlass(Pill, smoke = 0.82f).padding(horizontal = 16.dp, vertical = 13.dp)) {
        if (value.isEmpty()) VText(placeholder, size = Type.body, color = V.Muted)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = V.Text, fontSize = 15.sp, fontFamily = Sans),
            cursorBrush = SolidColor(V.Teal),
            visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onDone() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ColumnScope.ConnectPanel(st: CcState, store: Store, act: MainActivity, go: (CcPart) -> Unit) {
    val pinned = store.rotation != "auto"
    val portrait = LocalPortrait.current
    val dndOn = st.dnd != NotificationManager.INTERRUPTION_FILTER_ALL
    VText("Connections", size = Type.title, font = SansMedium, color = V.Text, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
    @Composable
    fun line(g: G, title: String, sub: String, on: Boolean, accent: Color, toggle: () -> Unit, part: CcPart) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).tap { go(part) }.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val lit by animateFloatAsState(if (on) 1f else 0f, label = "lit")
            Box(Modifier.size(50.dp).semantics { contentDescription = title; selected = on }.ccGlass(CircleShape, smoke = 0.72f, stain = accent, lit = lit).tap(toggle),
                contentAlignment = Alignment.Center) { Glyph(g, if (on) Color.White else V.Text, Modifier.size(21.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                VText(title, size = Type.body, color = V.Text)
                VText(sub, size = Type.caption, color = if (on) lerp(accent, Color.White, 0.3f) else V.Muted, maxLines = 1)
            }
            Glyph(G.CHEV, V.Muted, Modifier.size(16.dp))
        }
    }
    line(G.WIFI, "Wi-Fi", if (!st.wifi) "Off" else st.ssid ?: "Not connected", st.wifi, V.Teal, { st.switchWifi(!st.wifi) }, CcPart.WIFI)
    line(G.BT, "Bluetooth", if (st.bt) "On" else "Off", st.bt, BtBlue, { st.switchBt(!st.bt) }, CcPart.BT)
    line(G.FOCUS_MODE, "Focus", if (dndOn && store.focusOn.isNotEmpty()) store.focusOn else "Off", dndOn, V.Purple, { go(CcPart.DND) }, CcPart.DND)
    line(G.ROTATE, "Rotation lock", rotLabel(store.rotation), pinned, V.Coral, {
        store.setRotation(if (pinned) "auto" else if (portrait) "port" else "land"); act.applyRotation()
    }, CcPart.ROTATE)
}

private fun dndLabel(mode: Int) = when (mode) {
    NotificationManager.INTERRUPTION_FILTER_ALARMS -> "Alarms only"
    NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "Priority only"
    NotificationManager.INTERRUPTION_FILTER_NONE -> "Total silence"
    else -> "Off"
}

private fun rotLabel(r: String) = when (r) { "port" -> "Locked to portrait"; "land" -> "Locked to landscape"; else -> "Off" }

/** Hold Wi-Fi: nearby networks with their signal and a lock when they need a password. Tap one to join. */
@Composable
private fun ColumnScope.WifiPanel(st: CcState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var nets by remember { mutableStateOf(if (CcSource.live) emptyList() else CcSource.networks(ctx)) }
    var scanning by remember { mutableStateOf(CcSource.live) }
    var open by remember { mutableStateOf<String?>(null) }   // the row showing a password box or details
    var pass by remember { mutableStateOf("") }
    var hidden by remember { mutableStateOf(true) }
    var joining by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun reload() = scope.launch { nets = withContext(Dispatchers.IO) { CcSource.networks(ctx) }; if (nets.isNotEmpty()) scanning = false }
    if (CcSource.live) {
        DisposableEffect(Unit) {
            val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { reload() } }
            val f = IntentFilter().apply {
                addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION); addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION); addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            }
            ctx.registerReceiver(r, f)
            onDispose { runCatching { ctx.unregisterReceiver(r) } }
        }
        LaunchedEffect(st.wifi) {
            if (!st.wifi) return@LaunchedEffect
            withContext(Dispatchers.IO) { Radio.prepareScan(ctx) }
            while (isActive) { withContext(Dispatchers.IO) { Radio.scan(ctx) }; reload(); delay(5000); scanning = false }
        }
    }

    PanelHead(G.WIFI, "Wi-Fi", if (!st.wifi) "Off" else st.ssid?.let { "Connected to $it" } ?: "Not connected", st.wifi, V.Teal) { st.switchWifi(!st.wifi) }
    if (!st.wifi) { VText("Wi-Fi is off. Tap the button to turn it on.", size = Type.body, color = V.Muted, modifier = Modifier.padding(start = 4.dp)); return }

    Section(if (scanning && nets.isEmpty()) "Looking for networks…" else "Networks")
    if (!scanning && nets.isEmpty()) VText("No networks found nearby.", size = Type.body, color = V.Muted, modifier = Modifier.padding(start = 4.dp))
    nets.forEach { n ->
        val isOpen = open == n.ssid
        PanelRow(
            chosen = n.connected,
            onClick = {
                err = null
                when {
                    n.connected || joining != null -> open = if (isOpen) null else n.ssid
                    n.saved || !n.locked -> {
                        joining = n.ssid
                        scope.launch {
                            val e = withContext(Dispatchers.IO) { Radio.join(ctx, n, "") }
                            joining = null; st.refresh(); reload()
                            if (e != null) { err = n.ssid to e; if (n.locked) open = n.ssid }
                        }
                    }
                    else -> { open = if (isOpen) null else n.ssid; pass = "" }
                }
            },
            lead = { WifiBars(n.bars, if (n.connected) V.Teal else V.Text) },
            title = n.ssid,
            sub = when { joining == n.ssid -> "Joining…"; n.connected -> "Connected"; n.saved -> "Saved"; else -> null },
            trail = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (n.locked) Glyph(G.LOCK, V.Muted, Modifier.size(15.dp))
                    if (n.connected) Glyph(G.CHECK, V.Teal, Modifier.size(18.dp))
                    if (n.saved && !n.connected) Box(Modifier.size(30.dp).clip(CircleShape).tap { open = if (isOpen) null else n.ssid }, contentAlignment = Alignment.Center) {
                        Glyph(G.INFO, V.Muted, Modifier.size(17.dp))
                    }
                }
            },
        )
        err?.takeIf { it.first == n.ssid }?.let { VText(it.second, size = Type.caption, color = V.Coral, modifier = Modifier.padding(start = 56.dp, bottom = 6.dp)) }
        if (isOpen) {
            Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 10.dp)) {
                if (n.saved || n.connected) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallPill("Forget this network", V.Coral) {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { Radio.forget(ctx, n.ssid) }
                                if (!ok) err = n.ssid to "Android wouldn't let VoidOS forget it."
                                open = null; st.refresh(); reload()
                            }
                        }
                    }
                } else {
                    GlassField(pass, { pass = it }, "Password for ${n.ssid}", hidden, onDone = {}, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SmallPill(if (hidden) "Show" else "Hide") { hidden = !hidden }
                        Spacer(Modifier.weight(1f))
                        SmallPill("Cancel", V.Muted) { open = null; pass = "" }
                        Box(Modifier.ccGlass(Pill, smoke = 0.5f, stain = V.Teal, lit = if (pass.length >= 8 || n.sec == Radio.Sec.WEP) 0.8f else 0.2f)
                            .tap {
                                if (pass.isEmpty()) return@tap
                                val pw = pass
                                joining = n.ssid; open = null; pass = ""
                                scope.launch {
                                    val e = withContext(Dispatchers.IO) { Radio.join(ctx, n, pw) }
                                    joining = null; st.refresh(); reload()
                                    if (e != null) { err = n.ssid to e; open = n.ssid }
                                }
                            }.padding(horizontal = 16.dp, vertical = 8.dp)) { VText("Join", size = Type.label, font = SansMedium, color = Color.White) }
                    }
                }
            }
        }
    }
}

/** Hold Bluetooth: paired devices to connect, and nearby ones to pair. */
@Composable
private fun ColumnScope.BtPanel(st: CcState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var devs by remember { mutableStateOf(CcSource.devices()) }
    val nearby = remember { mutableStateListOf<Radio.Dev>() }
    var busy by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    fun reload() = scope.launch { devs = withContext(Dispatchers.IO) { CcSource.devices() }; nearby.removeAll { n -> devs.any { it.address == n.address } } }

    if (CcSource.live && st.bt) {
        DisposableEffect(Unit) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i.action == BluetoothDevice.ACTION_FOUND) {
                        @Suppress("DEPRECATION") val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                        val dev = Radio.dev(d, emptySet())
                        if (!dev.paired && nearby.none { it.address == dev.address } && devs.none { it.address == dev.address } && dev.name != dev.address) nearby.add(dev)
                    } else reload()
                }
            }
            val f = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED); addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            ctx.registerReceiver(r, f)
            scope.launch(Dispatchers.IO) { Radio.prepareScan(ctx); Radio.discover(true) }
            onDispose { Radio.discover(false); runCatching { ctx.unregisterReceiver(r) } }
        }
        LaunchedEffect(Unit) { while (isActive) { delay(3000); reload() } }
    }

    PanelHead(G.BT, "Bluetooth", if (!st.bt) "Off" else devs.firstOrNull { it.connected }?.let { "Connected to ${it.name}" } ?: "On", st.bt, BtBlue) { st.switchBt(!st.bt) }
    if (!st.bt) { VText("Bluetooth is off. Tap the button to turn it on.", size = Type.body, color = V.Muted, modifier = Modifier.padding(start = 4.dp)); return }
    err?.let { VText(it, size = Type.caption, color = V.Coral, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) }

    Section("My devices")
    if (devs.isEmpty()) VText("Nothing paired yet.", size = Type.body, color = V.Muted, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
    devs.forEach { d ->
        PanelRow(
            chosen = d.connected, accent = BtBlue,
            onClick = {
                err = null; busy = d.address
                Radio.link(ctx, d.address, !d.connected) { ok ->
                    scope.launch {
                        delay(2500); busy = null; reload()
                        if (!ok) err = "Couldn't ${if (d.connected) "disconnect" else "connect to"} ${d.name}."
                    }
                }
            },
            lead = { Glyph(kindGlyph(d.kind), if (d.connected) BtBlue else V.Text, Modifier.size(22.dp)) },
            title = d.name,
            sub = when { busy == d.address -> if (d.connected) "Disconnecting…" else "Connecting…"; d.connected -> "Connected"; else -> "Not connected" },
            trail = {
                Box(Modifier.size(30.dp).clip(CircleShape).tap { open = if (open == d.address) null else d.address }, contentAlignment = Alignment.Center) {
                    Glyph(G.INFO, V.Muted, Modifier.size(17.dp))
                }
            },
        )
        if (open == d.address) Row(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 10.dp)) {
            SmallPill("Forget this device", V.Coral) {
                scope.launch { val ok = withContext(Dispatchers.IO) { Radio.unpair(d.address) }; if (!ok) err = "Android wouldn't let VoidOS forget ${d.name}."; open = null; delay(800); reload() }
            }
        }
    }

    Section(if (nearby.isEmpty()) "Looking for other devices…" else "Other devices")
    nearby.forEach { d ->
        PanelRow(
            onClick = { busy = d.address; scope.launch { val ok = withContext(Dispatchers.IO) { Radio.pair(d.address) }; if (!ok) { busy = null; err = "Couldn't pair with ${d.name}." } } },
            lead = { Glyph(kindGlyph(d.kind), V.Text, Modifier.size(22.dp)) },
            title = d.name,
            sub = if (busy == d.address) "Pairing…" else "Tap to pair",
        )
    }
}

private fun kindGlyph(k: Radio.Kind) = when (k) {
    Radio.Kind.HEADPHONES, Radio.Kind.SPEAKER -> G.VOLUME
    Radio.Kind.KEYBOARD -> G.COMPOSE
    Radio.Kind.PHONE -> G.PHONE
    Radio.Kind.COMPUTER -> G.DISPLAY
    Radio.Kind.WATCH -> G.TIMER
    else -> G.BT
}

/** Hold Do Not Disturb: how quiet. */
@Composable
private fun ColumnScope.DndPanel(st: CcState) {
    val store = VoidApp.instance.store
    val on = store.focusOn.isNotEmpty() && st.dnd != NotificationManager.INTERRUPTION_FILTER_ALL
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    PanelHead(G.FOCUS_MODE, "Focus", if (on) store.focusOn else "Off", on, V.Purple) {
        if (on) { store.setFocus(""); st.switchDnd(NotificationManager.INTERRUPTION_FILTER_ALL) }
        else store.focusModes.firstOrNull()?.let { store.setFocus(it.name); st.switchDnd(it.filter) }
    }
    store.focusModes.forEach { m ->
        val chosen = on && store.focusOn == m.name
        PanelRow(
            chosen = chosen, accent = V.Purple,
            onClick = {
                if (chosen) { store.setFocus(""); st.switchDnd(NotificationManager.INTERRUPTION_FILTER_ALL) }
                else { store.setFocus(m.name); st.switchDnd(m.filter) }
            },
            lead = { Glyph(focusGlyph(m.glyph), if (chosen) Color.White else V.Text, Modifier.size(20.dp)) },
            title = m.name, sub = dndLabel(m.filter),
        )
    }
    if (adding) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            VField(name, { name = it.take(20) }, Modifier.weight(1f), placeholder = "Name")
            Spacer(Modifier.width(10.dp))
            VButton("Add", filled = true) {
                if (name.isNotBlank() && store.focusModes.none { it.name.equals(name.trim(), true) }) {
                    store.saveFocusModes(store.focusModes + FocusMode(name.trim(), "focus_mode", NotificationManager.INTERRUPTION_FILTER_PRIORITY))
                }
                name = ""; adding = false
            }
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VButton("New Focus") { adding = true }
            val custom = store.focusModes.drop(5)
            if (custom.isNotEmpty()) VButton("Remove ${custom.last().name}", accent = V.Muted) {
                if (store.focusOn == custom.last().name) { store.setFocus(""); st.switchDnd(NotificationManager.INTERRUPTION_FILTER_ALL) }
                store.saveFocusModes(store.focusModes.dropLast(1))
            }
        }
    }
}

internal fun focusGlyph(name: String): G = runCatching { G.valueOf(name.uppercase()) }.getOrDefault(G.FOCUS_MODE)

/** Hold rotation lock: which way the screen stays. */
@Composable
private fun ColumnScope.RotatePanel(store: Store, act: MainActivity) {
    PanelHead(G.ROTATE, "Rotation lock", rotLabel(store.rotation), store.rotation != "auto", V.Coral, null)
    listOf("auto" to "Turn with the tablet", "port" to "Always portrait", "land" to "Always landscape").forEach { (k, title) ->
        PanelRow(
            chosen = store.rotation == k, accent = V.Coral, onClick = { store.setRotation(k); act.applyRotation() },
            lead = { if (store.rotation == k) Glyph(G.CHECK, lerp(V.Coral, Color.White, 0.3f), Modifier.size(18.dp)) },
            title = title,
        )
    }
}

/** Hold brightness: a big slider, auto-brightness and Night Light. */
@Composable
private fun ColumnScope.BrightPanel(st: CcState) {
    VText("Brightness", size = Type.title, font = SansMedium, color = V.Text, modifier = Modifier.align(Alignment.CenterHorizontally))
    VText("${(st.bright * 100).toInt()}%", size = Type.label, color = V.Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
    Spacer(Modifier.height(16.dp))
    CcTall(G.SUN, st.bright, Modifier.align(Alignment.CenterHorizontally).width(120.dp).height(260.dp), SunFill, "Brightness", null) {
        st.bright = it; st.auto = false; Tablet.setBrightness(st.act, it)
    }
    Spacer(Modifier.height(22.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        LabelledBubble(G.DISPLAY, "Auto", st.auto, V.Sun) {
            val want = !st.auto
            if (Tablet.setAutoBrightness(st.act, want)) st.auto = want else st.note = "Android didn't let VoidOS change auto-brightness."
        }
        LabelledBubble(G.MOON, "Night Light", st.night, V.Orange) { st.switchNight(!st.night) }
    }
}

@Composable
private fun LabelledBubble(g: G, label: String, on: Boolean, accent: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val lit by animateFloatAsState(if (on) 1f else 0f, label = "lit")
        Box(Modifier.size(60.dp).semantics { contentDescription = label; selected = on }.ccGlass(CircleShape, smoke = 0.72f, stain = accent, lit = lit).tap(onClick),
            contentAlignment = Alignment.Center) { Glyph(g, if (on) Color.White else V.Text, Modifier.size(24.dp)) }
        Spacer(Modifier.height(6.dp))
        VText(label, size = Type.caption, color = V.Text)
        VText(if (on) "On" else "Off", size = Type.caption, color = if (on) lerp(accent, Color.White, 0.3f) else V.Muted)
    }
}

/** Hold volume: media big, alarms and notifications beside it. */
@Composable
private fun ColumnScope.VolumePanel(st: CcState) {
    val ctx = st.ctx
    var alarm by remember { mutableFloatStateOf(Tablet.stream(ctx, AudioManager.STREAM_ALARM)) }
    var notif by remember { mutableFloatStateOf(Tablet.stream(ctx, AudioManager.STREAM_NOTIFICATION)) }
    VText("Volume", size = Type.title, font = SansMedium, color = V.Text, modifier = Modifier.align(Alignment.CenterHorizontally))
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth().height(260.dp), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
        @Composable
        fun col(label: String, g: G, v: Float, fill: List<Color>, w: Dp, set: (Float) -> Unit) {
            Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                CcTall(g, v, Modifier.width(w).weight(1f), fill, label, null, set)
                Spacer(Modifier.height(8.dp))
                VText(label, size = Type.caption, color = V.Muted)
            }
        }
        col("Media", G.MEDIA, st.vol, VolFill, 110.dp) { st.vol = it; Tablet.setVolume(ctx, it) }
        col("Alarms", G.BELL, alarm, listOf(lerp(V.Purple, Color.White, 0.5f), V.Purple), 74.dp) { alarm = it; Tablet.setStream(ctx, AudioManager.STREAM_ALARM, it) }
        col("Notifications", G.MESSAGE, notif, listOf(lerp(V.Coral, Color.White, 0.5f), V.Coral), 74.dp) { notif = it; Tablet.setStream(ctx, AudioManager.STREAM_NOTIFICATION, it) }
    }
}

/** Hold now playing: artwork, a seek bar and full controls. */
@Composable
private fun ColumnScope.MediaPanel() {
    val p by Now.playing
    val ctx = LocalContext.current
    val cur = p
    if (cur == null) {
        Glyph(G.MEDIA, V.Muted, Modifier.size(40.dp).align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(12.dp))
        VText("Nothing is playing", size = Type.title, font = SansMedium, color = V.Text, modifier = Modifier.align(Alignment.CenterHorizontally))
        VText("Start music or a video in any app and you can control it from here.", size = Type.label, color = V.Muted,
            align = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally))
        return
    }
    var pos by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(cur) { while (isActive) { pos = Now.position(cur).toFloat(); delay(500) } }
    Box(Modifier.align(Alignment.CenterHorizontally).size(180.dp).ccGlass(RoundedCornerShape(Radius.panel)), contentAlignment = Alignment.Center) {
        cur.art?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: Glyph(G.MEDIA, V.Muted, Modifier.size(56.dp))
    }
    Spacer(Modifier.height(18.dp))
    VText(cur.title.ifBlank { cur.appName }, size = Type.title, font = SansMedium, color = V.Text, maxLines = 2, modifier = Modifier.align(Alignment.CenterHorizontally))
    VText(listOf(cur.artist, cur.appName).filter { it.isNotBlank() }.distinct().joinToString(" · "), size = Type.label, color = V.Muted, maxLines = 1,
        modifier = Modifier.align(Alignment.CenterHorizontally))
    Spacer(Modifier.height(16.dp))
    if (cur.durationMs > 0) {
        val frac = (pos / cur.durationMs).coerceIn(0f, 1f)
        Box(
            Modifier.fillMaxWidth().height(14.dp).ccGlass(Pill, smoke = 0.8f)
                .pointerInput(cur) { detectTapGestures { o -> Now.seekTo((o.x / size.width * cur.durationMs).toLong()) } }
                .pointerInput(cur) { detectHorizontalDragGestures { ch, _ -> ch.consume(); Now.seekTo((ch.position.x / size.width).coerceIn(0f, 1f).times(cur.durationMs).toLong()) } },
        ) { Box(Modifier.fillMaxHeight().fillMaxWidth(frac.coerceAtLeast(0.04f)).clip(Pill).background(Brush.horizontalGradient(VolFill.reversed()))) }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            VText(mmss(pos.toLong()), size = Type.caption, color = V.Muted)
            Spacer(Modifier.weight(1f))
            VText("-" + mmss(cur.durationMs - pos.toLong()), size = Type.caption, color = V.Muted)
        }
        Spacer(Modifier.height(10.dp))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconTap(G.PREV, 28.dp) { Now.prev() }
        Box(Modifier.size(72.dp).ccGlass(CircleShape, smoke = 0.6f, stain = V.Teal, lit = 0.5f).tap { Now.playPause() }, contentAlignment = Alignment.Center) {
            Glyph(if (cur.playing) G.PAUSE else G.PLAY, Color.White, Modifier.size(32.dp))
        }
        IconTap(G.NEXT, 28.dp) { Now.next() }
    }
    Spacer(Modifier.height(16.dp))
    Box(Modifier.align(Alignment.CenterHorizontally)) { SmallPill("Open ${cur.appName}") { Now.open(ctx) } }
}

private fun mmss(ms: Long): String { val s = (ms.coerceAtLeast(0) / 1000); return "%d:%02d".format(s / 60, s % 60) }

/**
 * The row of small round switches under the main controls: airplane mode, location,
 * power saving, screenshot, screen recording, torch-bright white screen, and casting.
 * Each reads its state when the control centre opens and flips it through root.
 */
@Composable
private fun QuickToggles(onClose: () -> Unit, bubble: Dp, modifier: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var plane by remember { mutableStateOf(com.andysocial.voidos.Device.airplaneOn(ctx)) }
    var loc by remember { mutableStateOf(com.andysocial.voidos.Device.locationOn(ctx)) }
    var saver by remember { mutableStateOf(com.andysocial.voidos.Device.saverOn(ctx)) }
    var rec by remember { mutableStateOf(com.andysocial.voidos.Device.recording != null) }
    var note by remember { mutableStateOf<String?>(null) }
    fun flip(get: () -> Boolean, set: (Boolean) -> Unit, act: (Boolean) -> Boolean, failed: String) {
        val to = !get(); set(to)
        scope.launch { if (!withContext(Dispatchers.IO) { act(to) }) { set(!to); note = failed } }
    }
    val items = listOf<Triple<G, String, Pair<Boolean, () -> Unit>>>(
        Triple(G.PLANE, "Airplane mode", plane to { flip({ plane }, { plane = it }, { com.andysocial.voidos.Device.airplane(it) }, "Airplane mode didn't switch.") }),
        Triple(G.PIN, "Location", loc to {
            flip({ loc }, { loc = it; VoidApp.instance.store.set("locationOff", !it) }, { com.andysocial.voidos.Device.location(it) }, "Location didn't switch.")
        }),
        Triple(G.LEAF, "Power saving", saver to { flip({ saver }, { saver = it }, { com.andysocial.voidos.Device.saver(it) }, "Power saving didn't switch.") }),
        Triple(G.SCREENSHOT, "Screenshot", false to {
            onClose()
            scope.launch {
                kotlinx.coroutines.delay(450)
                val f = withContext(Dispatchers.IO) { com.andysocial.voidos.Device.screenshot() }
                android.widget.Toast.makeText(ctx, if (f != null) "Screenshot saved" else "Screenshot failed", android.widget.Toast.LENGTH_SHORT).show()
            }
        }),
        Triple(G.RECORD, if (rec) "Stop recording" else "Record screen", rec to {
            if (rec) scope.launch { withContext(Dispatchers.IO) { com.andysocial.voidos.Device.stopRecording() }; rec = false; android.widget.Toast.makeText(ctx, "Recording saved to Movies", android.widget.Toast.LENGTH_SHORT).show() }
            else { onClose(); scope.launch { kotlinx.coroutines.delay(450); rec = withContext(Dispatchers.IO) { com.andysocial.voidos.Device.startRecording() } } }
        }),
    )
    Column(modifier) {
        // one glass strip, the same pane and bubble size as the Wi-Fi square: no loose buttons
        CcCluster(items.map { (g, label, st) -> CcItem(g, label, st.first, V.Teal, tap = st.second) }, items.size, bubble, Modifier.fillMaxWidth())
        note?.let { VText(it, size = Type.label, color = V.Coral, modifier = Modifier.padding(top = 8.dp)) }
    }
}


/** The notification centre: what apps have posted, newest first. Tap one to open it, Clear to dismiss. */
@Composable
private fun PowerPanel() {
    GlassActions(listOf(
        GlassAction("Restart", G.RESTART) { Thread { com.andysocial.voidos.Device.reboot("restart") }.start() },
        GlassAction("Power off", G.POWER, warn = true) { Thread { com.andysocial.voidos.Device.reboot("off") }.start() },
    ))
}

@Composable
private fun NoticesPanel(onOpened: () -> Unit) {
    val list by com.andysocial.voidos.VoidListener.notices
    if (list.isEmpty()) VText("Nothing new", size = Type.body, color = V.Muted)
    list.forEach { n ->
        Column(
            Modifier.fillMaxWidth().padding(vertical = Grid.xs).ccGlass(RoundedCornerShape(Radius.card), smoke = 0.5f)
                .tap { com.andysocial.voidos.VoidListener.open(n.key); onOpened() }
                .padding(horizontal = Grid.l, vertical = Grid.m),
        ) {
            VText(n.appName, size = Type.caption, color = V.Muted, maxLines = 1)
            VText(n.title, size = Type.body, color = V.White, maxLines = 1)
            if (n.text.isNotBlank()) VText(n.text, size = Type.label, color = V.Text, maxLines = 2)
        }
    }
    if (list.isNotEmpty()) {
        Spacer(Modifier.height(Grid.m))
        Box(Modifier.ccGlass(Pill, smoke = 0.6f).tap { com.andysocial.voidos.VoidListener.clearAll() }
            .padding(horizontal = Grid.l, vertical = Grid.s)) { VText("Clear all", size = Type.label, color = V.Text) }
    }
}
