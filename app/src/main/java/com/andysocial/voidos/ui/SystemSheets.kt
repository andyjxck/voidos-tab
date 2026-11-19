package com.andysocial.voidos.ui

import android.media.AudioManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andysocial.voidos.KeyguardState
import com.andysocial.voidos.Store
import com.andysocial.voidos.VolumeState

/**
 * The one backdrop every VoidOS system surface sits on (power menu, PIN pad, app switcher,
 * Control Centre): the deep VoidOS room, nearly opaque, with the aurora dimmed in it, so
 * whatever is behind (Home's clock and widgets, an app) never shows through and clashes.
 */
@Composable
fun SystemScrim(modifier: Modifier = Modifier, strength: Float = 1f) {
    Box(modifier.fillMaxSize().graphicsLayer { alpha = strength }) {
        Box(Modifier.fillMaxSize().background(V.Deep))
        Aurora(Modifier.fillMaxSize(), intensity = 0.45f)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(V.Deep.copy(alpha = 0.55f), V.Deep.copy(alpha = 0.25f), V.Deep.copy(alpha = 0.7f)))))
    }
}

/** The streams the volume sliders offer, in order: what each is called and its glyph. */
val VolumeStreams: List<Triple<Int, String, G>> = listOf(
    Triple(AudioManager.STREAM_MUSIC, "Media", G.MEDIA),
    Triple(AudioManager.STREAM_RING, "Ring", G.RING),
    Triple(AudioManager.STREAM_NOTIFICATION, "Notifications", G.BELL),
    Triple(AudioManager.STREAM_ALARM, "Alarms", G.TIMER),
)

/** Pure: a volume level as a 0..1 fraction. */
fun volumeFraction(level: Int, min: Int, max: Int): Float =
    if (max <= min) 0f else ((level - min).toFloat() / (max - min)).coerceIn(0f, 1f)

/** Pure: the level for a 0..1 fraction. */
fun volumeLevel(fraction: Float, min: Int, max: Int): Int =
    (min + Math.round(fraction.coerceIn(0f, 1f) * (max - min))).coerceIn(min, max)

/**
 * VoidOS's volume sliders: a glass pill on the right edge for the volume the keys move, and a
 * tap on its glyph opens all four (media, ring, notifications, alarms) side by side.
 */
@Composable
fun VolumeHud(audio: AudioManager, onTouch: () -> Unit) {
    CompositionLocalProvider(LocalRoom provides Rooms.overlay) {
        var all by remember { mutableStateOf(false) }
        val bump = VolumeState.bump.intValue
        val main = VolumeState.stream.intValue
        val streams = if (all) VolumeStreams else VolumeStreams.filter { it.first == main }.ifEmpty { listOf(Triple(main, "Volume", G.VOLUME)) }
        Row(
            Modifier.padding(end = 20.dp).liquidGlass(RoundedCornerShape(30.dp), GlassLook(smoke = 0.82f, depth = 1f, shine = 1.2f, magnify = 1.1f))
                .padding(horizontal = 10.dp, vertical = 12.dp)
                .semantics { contentDescription = "Volume" },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            streams.forEach { (stream, label, g) ->
                VolumeSlider(audio, stream, label, g, bump, onTouch, onGlyph = { all = !all; onTouch() })
            }
        }
    }
}

@Composable
private fun VolumeSlider(audio: AudioManager, stream: Int, label: String, g: G, bump: Int, onTouch: () -> Unit, onGlyph: () -> Unit) {
    val min = remember(stream) { runCatching { if (android.os.Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(stream) else 0 }.getOrDefault(0) }
    val max = remember(stream) { runCatching { audio.getStreamMaxVolume(stream) }.getOrDefault(15).coerceAtLeast(1) }
    var level by remember(stream) { mutableIntStateOf(runCatching { audio.getStreamVolume(stream) }.getOrDefault(0)) }
    LaunchedEffect(bump, stream) { level = runCatching { audio.getStreamVolume(stream) }.getOrDefault(level) }
    val frac by animateFloatAsState(volumeFraction(level, min, max), Motion.quick(), label = "vol")
    val h = 200.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        VText("${Math.round(volumeFraction(level, min, max) * 100)}", size = Type.caption, color = V.Text, align = TextAlign.Center)
        Box(Modifier.height(8.dp))
        Box(
            Modifier.width(48.dp).height(h).clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = 0.10f))
                .semantics { contentDescription = "$label volume" }
                .pointerInput(stream, max) {
                    fun set(y: Float) {
                        val f = 1f - (y / size.height)
                        val l = volumeLevel(f, min, max)
                        if (l != level) { level = l; runCatching { audio.setStreamVolume(stream, l, 0) } }
                        onTouch()
                    }
                    detectVerticalDragGestures(onDragStart = { set(it.y) }) { ch, _ -> set(ch.position.y) }
                }
                .pointerInput(stream, max) { detectTapGestures { o -> val l = volumeLevel(1f - o.y / size.height, min, max); level = l; runCatching { audio.setStreamVolume(stream, l, 0) }; onTouch() } },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(frac.coerceAtLeast(0.001f)).background(Brush.verticalGradient(listOf(V.White, V.Text.copy(alpha = 0.85f)))))
        }
        Box(Modifier.height(10.dp))
        Box(
            Modifier.size(36.dp).clip(CircleShape).clickable { onGlyph() }.semantics { contentDescription = "All volumes" },
            contentAlignment = Alignment.Center,
        ) { Glyph(if (level <= min) G.VIBRATE.takeIf { stream == AudioManager.STREAM_RING } ?: g else g, V.Text, Modifier.size(20.dp)) }
        VText(label, size = 11.sp, color = V.Muted, align = TextAlign.Center, maxLines = 1)
    }
}

/** VoidOS's power menu: lock, restart and power off, on the VoidOS backdrop. */
@Composable
fun PowerMenu(onLock: () -> Unit, onRestart: () -> Unit, onPowerOff: () -> Unit, onDismiss: () -> Unit) {
    CompositionLocalProvider(LocalRoom provides Rooms.overlay) {
        var confirm by remember { mutableStateOf<String?>(null) }
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDismiss() } }.semantics { contentDescription = "Power menu" },
            contentAlignment = Alignment.Center,
        ) {
            SystemScrim()
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                VText(if (confirm == null) "Power" else "$confirm?", size = 26.sp, font = SansLight, color = V.White)
                Box(Modifier.height(6.dp))
                VText(if (confirm == null) "Tap outside to cancel" else "Tap again to confirm", size = Type.label, color = V.Muted)
                Box(Modifier.height(32.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    PowerButton("Lock", G.LOCK, false, 88.dp) { onLock() }
                    PowerButton("Restart", G.RESTART, confirm == "Restart", 88.dp) { if (confirm == "Restart") onRestart() else confirm = "Restart" }
                    PowerButton("Power off", G.POWER, confirm == "Power off", 88.dp, warn = true) { if (confirm == "Power off") onPowerOff() else confirm = "Power off" }
                }
            }
        }
    }
}

@Composable
private fun PowerButton(label: String, g: G, armed: Boolean, size: Dp, warn: Boolean = false, onTap: () -> Unit) {
    val lit by animateFloatAsState(if (armed) 1f else 0f, Motion.quick(), label = "arm")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size)
                .then(if (armed) Modifier.droplet(CircleShape, if (warn) V.Coral else V.Teal, 0.6f + 0.4f * lit) else Modifier.liquidGlass(CircleShape, GlassLook(smoke = 0.6f, depth = 0.8f, shine = 1.3f, magnify = 1.2f)))
                .pointerInput(Unit) { detectTapGestures { onTap() } }
                .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = label; onClick { onTap(); true } },
            contentAlignment = Alignment.Center,
        ) { Glyph(g, if (warn && armed) V.White else V.Text, Modifier.size(size * 0.36f)) }
        Box(Modifier.height(10.dp))
        VText(label, size = Type.body, color = V.Text)
    }
}

/**
 * VoidOS's PIN pad over Android's lock screen. Android's PIN can be any length, so it has an
 * OK key; the entry goes to Android underneath, which checks it.
 */
@Composable
fun KeyguardPad(store: Store, onPin: (String) -> Unit) {
    CompositionLocalProvider(LocalRoom provides Rooms.overlay) {
        val now = rememberNow(1000)
        val wrong = KeyguardState.wrong.intValue
        val busy = KeyguardState.busy.value
        BoxWithConstraints(
            Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }.semantics { contentDescription = "VoidOS lock screen" },
            contentAlignment = Alignment.Center,
        ) {
            SystemScrim()
            val portrait = maxHeight > maxWidth
            val key = if (portrait) 80.dp else minOf(72.dp, maxHeight / 8.2f)
            val t = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault())
            val time = t.format(java.time.format.DateTimeFormatter.ofPattern(if (store.use24h) "HH:mm" else "h:mm"))
            val date = t.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM"))
            val pad = @Composable {
                GlassPinPad(
                    title = if (busy) "Unlocking" else "Enter PIN",
                    length = 4, open = true, busy = busy, keySize = key,
                    error = if (wrong > 0) "Wrong PIN" else null, shakeKey = wrong,
                    onPin = onPin,
                )
            }
            if (portrait) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                VText(time, size = 64.sp, font = SansThin, color = V.White)
                VText(date, size = Type.body, color = V.Text)
                Box(Modifier.height(36.dp))
                pad()
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(72.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    VText(time, size = 72.sp, font = SansThin, color = V.White)
                    VText(date, size = Type.body, color = V.Text)
                }
                pad()
            }
        }
    }
}

@Suppress("unused")
private val keep = MutableInteractionSource::class

/**
 * VoidOS's own "Restarting" / "Powering off" screen. It goes up a moment before VoidOS asks
 * Android to restart, and it sits above Android's grey "Shutting down..." dialog (an
 * accessibility overlay is drawn above every system dialog), so that dialog is never seen.
 * Then the tablet's shutdown animation takes over.
 */
@Composable
fun PowerOffScreen(restart: Boolean) {
    Box(Modifier.fillMaxSize().background(V.Bg).semantics { contentDescription = if (restart) "Restarting" else "Powering off" }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.foundation.Canvas(Modifier.size(260.dp, 90.dp)) {
                drawVoidWord(androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2), size.width, alpha = 0.9f)
            }
            Gap(h = 28.dp)
            VText(if (restart) "Restarting" else "Powering off", size = Type.title, color = V.Text)
            Gap(h = 6.dp)
            VText(if (restart) "Back in a minute" else "See you soon", size = Type.caption, color = V.Muted)
        }
    }
}

/** Pure: the battery levels VoidOS warns at, on the way down and not charging. */
internal val LOW_BATTERY_STEPS = listOf(20, 10, 5)

/** Pure: the step just crossed going from [was] to [now], if any (and only while not charging). */
internal fun lowBatteryStep(was: Int, now: Int, charging: Boolean): Int? =
    if (charging || was < 0 || now < 0) null else LOW_BATTERY_STEPS.filter { now <= it && was > it }.minOrNull()

/**
 * VoidOS's low battery warning. Android's is a notification, and the status bar is off under
 * VoidOS, so without this there'd be no warning at all.
 */
@Composable
fun LowBatteryBanner(pct: Int, onDismiss: () -> Unit) {
    val warn = pct <= 10
    Box(Modifier.padding(top = 20.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(28.dp))
                .liquidGlass(RoundedCornerShape(28.dp), GlassLook(smoke = 0.92f, depth = 1.4f, stain = if (warn) V.Coral else V.Yellow, stainAlpha = 0.16f))
                .clickable(remember { MutableInteractionSource() }, null) { onDismiss() }
                .semantics(mergeDescendants = true) { contentDescription = "Battery low, $pct percent" }
                .padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(G.BATTERY, if (warn) V.Coral else V.Yellow, Modifier.size(24.dp))
            Gap(w = 12.dp)
            Column {
                VText("Battery low \u00b7 $pct%", size = Type.body, color = V.White, font = Sans)
                VText(if (pct <= 5) "Plug in now, the tablet will switch off soon" else "Plug in soon", size = Type.caption, color = V.Muted)
            }
        }
    }
}
