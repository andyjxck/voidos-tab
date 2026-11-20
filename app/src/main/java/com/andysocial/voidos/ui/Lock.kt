package com.andysocial.voidos.ui

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andysocial.voidos.KTimers
import com.andysocial.voidos.Now
import com.andysocial.voidos.Store
import com.andysocial.voidos.VoidListener
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Whether the VoidOS welcome/lock screen is up. MainActivity raises it when the screen turns on. */
object LockState {
    val locked = mutableStateOf(true)
}

/**
 * The VoidOS lock screen. This runs inside the launcher: Android's own lock
 * screen is switched off by VoidOS (device owner), so this is what you see
 * when the screen wakes. Swipe up to open the hub; with "PIN to unlock" on,
 * the escape-hatch PIN is asked first.
 *
 * Landscape: the clock on the left, now playing, timers and notifications on
 * the right. Portrait: everything in one column.
 */
@Composable
fun LockScreen(store: Store, onUnlock: () -> Unit) {
    val portrait = LocalPortrait.current
    val now = rememberNow(1000)
    val scope = rememberCoroutineScope()
    val lift = remember { Animatable(0f) }
    var askPin by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    fun release() {
        if (store.lockNeedsPin) askPin = true else onUnlock()
    }

    CompositionLocalProvider(LocalRoom provides Rooms.lock) {
    BoxWithConstraints(Modifier.fillMaxSize().background(V.Bg)) {
        val hPx = with(density) { maxHeight.toPx() }
        Box(
            Modifier.fillMaxSize()
                .offset { IntOffset(0, -lift.value.roundToInt()) }
                .graphicsLayer { alpha = 1f - (lift.value / (hPx * 0.5f)).coerceIn(0f, 1f) }
                .pointerInput(askPin) {
                    if (askPin) return@pointerInput
                    detectVerticalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (lift.value > hPx * 0.18f) { release(); lift.snapTo(0f) } else lift.animateTo(0f, Motion.release())
                            }
                        },
                        onVerticalDrag = { ch, dy -> ch.consume(); scope.launch { lift.snapTo((lift.value - dy).coerceAtLeast(0f)) } },
                    )
                },
        ) {
            // under the PIN pad the lock screen is only a backdrop: keep it out of reach of touch and TalkBack
            if (askPin) { LockBackdrop(now); if (portrait) Box(Modifier.fillMaxSize().padding(top = 40.dp), contentAlignment = Alignment.TopCenter) { LockClockSmall(store, now) }; return@Box }
            LockBackdrop(now)
            if (portrait) {
                Column(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    LockClock(store, now, big = true)
                    Gap(h = 28.dp)
                    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) { LockCards(store, now) }
                    SwipeHint()
                }
            } else {
                Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp)) {
                    Column(Modifier.weight(1.1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                        LockClock(store, now, big = true, start = true)
                    }
                    Gap(w = 40.dp)
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                        LockCards(store, now)
                    }
                }
                Box(Modifier.fillMaxSize().padding(bottom = 24.dp), contentAlignment = Alignment.BottomCenter) { SwipeHint() }
            }
        }
        if (askPin) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))) {
                LockPin(store, title = "Enter PIN", onOk = { askPin = false; onUnlock() }, onCancel = { askPin = false })
            }
        }
    }
    }
}

/**
 * The lock screen's room: the aurora in the logo's purple, teal and coral, and
 * a slow orb of light behind the clock that drifts and breathes. Still dark
 * overall, so it's kind to a panel that stays on.
 */
@Composable
private fun LockBackdrop(now: Long) {
    val tick = rememberTicker(40L)
    Box(Modifier.fillMaxSize()) {
        Aurora(mood = Ambience.LOCK, intensity = 1.25f)
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val k = (phase(tick.value, 24_000L) * 2 * PI).toFloat()
            val c = Offset(w * (if (w > h) 0.27f else 0.5f) + 18f * cos(k), h * (if (w > h) 0.5f else 0.26f) + 14f * sin(2 * k))
            val r = minOf(w, h) * (0.52f + 0.04f * sin(k))
            drawCircle(Brush.radialGradient(listOf(V.Purple.copy(alpha = 0.20f), V.Teal.copy(alpha = 0.06f), Color.Transparent), center = c, radius = r), r, c)
        }
    }
}

@Composable
private fun LockClock(store: Store, now: Long, big: Boolean, start: Boolean = false) {
    val wx by WeatherRepo.state
    val bat = rememberBattery()
    val tick = rememberTicker()
    val thin = rememberFace("sans-serif-thin")
    val d = LocalDensity.current
    val portrait = LocalPortrait.current
    // 2.0.1: the lock screen wears Home's octagon dial, so the tablet has one clock face everywhere
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        DotLabel(greeting(now) + (if (store.hubName.isNotBlank() && store.hubName != "Home") ", ${store.hubName}" else ""), V.Teal, Modifier.widthIn(max = 420.dp), color = lerpTeal())
        Gap(h = 12.dp)
        VoidDial(now, store.use24h, Modifier.size(if (big) (if (portrait) 440.dp else 400.dp) else 260.dp).breathingGlow(tick, Ambience.LOCK, 1.3f).semantics(mergeDescendants = true) {})
        Gap(h = 12.dp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            wx?.let { w ->
                Row(Modifier.glass(Radius.Control, 0.7f).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    WxIcon(w.code, w.isDay, Modifier.size(26.dp))
                    Gap(w = 8.dp)
                    VText("${t(w.temp)}  ${wxWords(w.code)}".uppercase(), size = Type.label, font = SansMedium, spacing = 1.5.sp, color = V.Text)
                }
            }
            Row(Modifier.glass(Radius.Control, 0.7f).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BatteryGlyph(bat)
                if (bat.pct >= 0) { Gap(w = 8.dp); VText("${bat.pct}%" + if (bat.charging) "  CHARGING" else "", size = Type.label, font = SansMedium, spacing = 1.5.sp, color = V.Text) }
            }
        }
    }
}

/** The screen's teal, softened toward white: for small accent words. */
private fun lerpTeal(): Color = androidx.compose.ui.graphics.lerp(V.Teal, Color.White, 0.25f)

/**
 * Today at a glance, so the lock screen is never empty: the day's high and low
 * and the next alarm. Shown when nothing else (music, timers, notifications) is.
 */
@Composable
private fun LockToday(store: Store, now: Long) {
    val wx by WeatherRepo.state
    val next = com.andysocial.voidos.AlarmMath.soonest(com.andysocial.voidos.Alarms.list.value, now, com.andysocial.voidos.Alarms.zone)
    VCard("Today", Modifier.fillMaxWidth()) {
        NextEventLine(store, V.Text)
        Gap(h = 8.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val day = wx?.days?.firstOrNull()
            Column(Modifier.weight(1f)) {
                VText(day?.let { "${t(it.max)} / ${t(it.min)}" } ?: "\u2014", size = Type.title2, color = V.White, font = SansLight)
                VText((day?.let { if (it.rain > 0) "${it.rain}% rain" else wxWords(it.code) } ?: "No data").uppercase(), size = Type.caption, font = SansMedium, spacing = 1.5.sp, color = V.Muted)
            }
            Gap(w = 16.dp)
            Column(Modifier.weight(1f)) {
                VText(next?.let { SimpleDateFormat(if (store.use24h) "HH:mm" else "h:mm a", Locale.UK).format(Date(it.second)) } ?: "\u2014", size = Type.title2, color = V.White, font = SansLight)
                VText(if (next != null) "NEXT ALARM" else "NO ALARM", size = Type.caption, font = SansMedium, spacing = 1.5.sp, color = V.Muted)
            }
        }
    }
}

@Composable
private fun LockCards(store: Store, now: Long) {
    val p by Now.playing
    val timers = KTimers.list.value.filter { it.running }.sortedBy { it.endAt }
    val notices by VoidListener.notices
    val quiet = p?.title.isNullOrBlank() && timers.isEmpty() && !(store.lockNotices && notices.isNotEmpty())
    if (quiet) LockToday(store, now)
    p?.takeIf { it.title.isNotBlank() }?.let { np ->
        Row(
            Modifier.fillMaxWidth().pane(Radius.Card).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(56.dp).clip(OctS).background(V.Panel2), contentAlignment = Alignment.Center) {
                val art = np.art
                if (art != null) Image(art.asImageBitmap(), null, Modifier.fillMaxSize()) else Glyph(G.MEDIA, V.Muted, Modifier.size(28.dp))
            }
            Gap(w = 12.dp)
            Column(Modifier.weight(1f)) {
                VText(np.title, size = Type.body, color = V.White, font = Sans, maxLines = 1)
                VText(np.artist.ifBlank { np.appName }, size = Type.caption, color = V.Muted, font = Sans, maxLines = 1)
            }
            IconTap(G.PREV, V.Text, 44.dp) { Now.prev() }
            Gap(w = 8.dp)
            IconTap(if (np.playing) G.PAUSE else G.PLAY, V.White, 44.dp) { Now.playPause() }
            Gap(w = 8.dp)
            IconTap(G.NEXT, V.Text, 44.dp) { Now.next() }
        }
    }
    timers.take(3).forEach { tm ->
        Row(
            Modifier.fillMaxWidth().pane(Radius.Card).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(G.TIMER, V.Teal, Modifier.size(20.dp))
            Gap(w = 12.dp)
            VText(tm.label, size = Type.body, color = V.Text, modifier = Modifier.weight(1f))
            VText(fmtDur(tm.remaining(now)), size = Type.title2, color = V.White, font = SansLight)
        }
    }
    if (store.lockNotices) {
        notices.take(if (LocalPortrait.current) 5 else 3).forEach { n ->
            Row(
                Modifier.fillMaxWidth().pane(Radius.Card).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(G.BELL, V.Muted, Modifier.size(20.dp))
                Gap(w = 12.dp)
                Column(Modifier.weight(1f)) {
                    VText("${n.appName} \u00b7 ${n.title}".uppercase(), size = Type.caption, font = SansMedium, spacing = 1.2.sp, color = V.Muted, maxLines = 1)
                    if (n.text.isNotBlank()) VText(n.text, size = Type.label, color = V.Text, font = Sans, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SwipeHint() {
    val now = rememberNow(60)
    val bob = sin((now % 1600) / 1600.0 * 2 * PI).toFloat()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Glyph(G.CHEV, V.White.copy(alpha = 0.8f), Modifier.size(24.dp).semantics { contentDescription = "Swipe up to open" }.graphicsLayer { rotationZ = -90f; translationY = bob * 8f; alpha = 0.55f + 0.45f * (1 - bob) / 2 })

    }
}

/** The VoidOS PIN pad on the lock screen: the same PIN as the way out. */
@Composable
fun LockPin(store: Store, title: String, onOk: () -> Unit, onCancel: () -> Unit) {
    val portrait = LocalPortrait.current
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Glyph(G.LOCK, V.Text, Modifier.size(22.dp))
        Gap(h = 12.dp)
        GlassPinPad(
            title = title,
            length = store.pinLength(),
            check = { store.checkPin(it) },
            keySize = if (portrait) 80.dp else 68.dp,
            onPin = { onOk() },
        )
        Gap(h = 16.dp)
        Box(Modifier.glass(Radius.Control, 0.6f).clickable { onCancel() }.padding(horizontal = 24.dp, vertical = 10.dp)) {
            VText("CANCEL", size = Type.label, font = SansMedium, spacing = 2.sp, color = V.Text)
        }
    }
}

/** The time and date, small, above the PIN pad. */
@Composable
private fun LockClockSmall(store: Store, now: Long) {
    val t = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault())
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer { alpha = 0.55f }) {
        VText(t.format(java.time.format.DateTimeFormatter.ofPattern(if (store.use24h) "HH:mm" else "h:mm")), size = Type.large, color = V.White, font = SansLight)
        VText(t.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")).uppercase(), size = Type.caption, font = SansMedium, spacing = 2.sp, color = V.Text)
    }
}
