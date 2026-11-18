package com.andysocial.voidos.ui

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
        if (store.lockNeedsPin && store.hasPin) askPin = true else onUnlock()
    }

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
                                if (lift.value > hPx * 0.18f) { release(); lift.snapTo(0f) } else lift.animateTo(0f, spring())
                            }
                        },
                        onVerticalDrag = { ch, dy -> ch.consume(); scope.launch { lift.snapTo((lift.value - dy).coerceAtLeast(0f)) } },
                    )
                },
        ) {
            // under the PIN pad the lock screen is only a backdrop: keep it out of reach of touch and TalkBack
            if (askPin) { LockBackdrop(now); return@Box }
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
                Box(Modifier.fillMaxSize().padding(bottom = 22.dp), contentAlignment = Alignment.BottomCenter) { SwipeHint() }
            }
        }
        if (askPin) {
            Box(Modifier.fillMaxSize().background(V.Bg.copy(alpha = 0.97f))) {
                LockPin(store, title = "Enter PIN to unlock", onOk = { askPin = false; onUnlock() }, onCancel = { askPin = false })
            }
        }
    }
}

/** A faint glow behind the clock: dark and calm, kind to an always-on panel. */
@Composable
private fun LockBackdrop(now: Long) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val c = Offset(w * (if (w > h) 0.27f else 0.5f), h * (if (w > h) 0.5f else 0.28f))
        drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(V.Teal.copy(alpha = 0.07f), androidx.compose.ui.graphics.Color.Transparent), center = c, radius = minOf(w, h) * 0.6f), minOf(w, h) * 0.6f, c)
    }
}

@Composable
private fun LockClock(store: Store, now: Long, big: Boolean, start: Boolean = false) {
    val cal = Calendar.getInstance().apply { timeInMillis = now }
    val minute = cal.get(Calendar.MINUTE)
    val wx by WeatherRepo.state
    val bat = rememberBattery()
    Column(horizontalAlignment = if (start) Alignment.Start else Alignment.CenterHorizontally) {
        VText(SimpleDateFormat("EEEE d MMMM", Locale.UK).format(Date(now)), size = 20.sp, color = V.Text.copy(alpha = 0.8f))
        VText(SimpleDateFormat(if (store.use24h) "HH:mm" else "h:mm", Locale.UK).format(Date(now)), size = if (big) 150.sp else 96.sp, color = V.White, font = SansThin)
        Gap(h = 10.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            wx?.let { w ->
                Glyph(wxGlyph(w.code, w.isDay), wxColour(w.code, w.isDay), Modifier.size(28.dp))
                Gap(w = 8.dp)
                VText("${t(w.temp)}  ${wxWords(w.code)}", size = 15.sp, color = V.Text)
                Gap(w = 22.dp)
            }
            BatteryGlyph(bat)
            if (bat.charging) { Gap(w = 8.dp); VText("Charging", size = 15.sp, color = V.Muted) }
        }
    }
}

@Composable
private fun LockCards(store: Store, now: Long) {
    val p by Now.playing
    val timers = KTimers.list.value.filter { it.running }.sortedBy { it.endAt }
    val notices by VoidListener.notices
    p?.takeIf { it.title.isNotBlank() }?.let { np ->
        Row(
            Modifier.fillMaxWidth().glass(Oct, 0.8f).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(56.dp).clip(OctS).background(V.Panel2), contentAlignment = Alignment.Center) {
                val art = np.art
                if (art != null) Image(art.asImageBitmap(), null, Modifier.fillMaxSize()) else Glyph(G.MEDIA, V.Muted, Modifier.size(28.dp))
            }
            Gap(w = 12.dp)
            Column(Modifier.weight(1f)) {
                VText(np.title, size = 15.sp, color = V.White, font = FontFamily.Default, maxLines = 1)
                VText(np.artist.ifBlank { np.appName }, size = 12.sp, color = V.Muted, font = FontFamily.Default, maxLines = 1)
            }
            IconTap(G.PREV, V.Text, 44.dp) { Now.prev() }
            Gap(w = 6.dp)
            IconTap(if (np.playing) G.PAUSE else G.PLAY, V.White, 44.dp) { Now.playPause() }
            Gap(w = 6.dp)
            IconTap(G.NEXT, V.Text, 44.dp) { Now.next() }
        }
    }
    timers.take(3).forEach { tm ->
        Row(
            Modifier.fillMaxWidth().glass(OctS, 0.8f).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(G.TIMER, V.Teal, Modifier.size(20.dp))
            Gap(w = 12.dp)
            VText(tm.label, size = 15.sp, color = V.Text, modifier = Modifier.weight(1f))
            VText(fmtDur(tm.remaining(now)), size = 22.sp, color = V.White, font = SansLight)
        }
    }
    if (store.lockNotices) {
        notices.take(if (LocalPortrait.current) 5 else 3).forEach { n ->
            Row(
                Modifier.fillMaxWidth().glass(OctS, 0.8f).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(G.BELL, V.Muted, Modifier.size(20.dp))
                Gap(w = 12.dp)
                Column(Modifier.weight(1f)) {
                    VText("${n.appName} \u00b7 ${n.title}", size = 13.sp, color = V.Muted, maxLines = 1)
                    if (n.text.isNotBlank()) VText(n.text, size = 13.sp, color = V.Text, font = FontFamily.Default, maxLines = 1)
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
        Glyph(G.CHEV, V.Teal, Modifier.size(26.dp).graphicsLayer { rotationZ = -90f; translationY = bob * 8f })
        Gap(h = 4.dp)
        VText("Swipe up to open", size = 12.sp, color = V.Muted, align = TextAlign.Center)
        Gap(h = 8.dp)
        Box(Modifier.width(120.dp).size(120.dp, 4.dp).clip(OctS).background(V.Line))
    }
}

/** The escape-hatch PIN pad, reused by the lock screen. */
@Composable
fun LockPin(store: Store, title: String, onOk: () -> Unit, onCancel: () -> Unit) {
    var entry by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    // PINs are always four digits (set on the escape-hatch pad), so check at four.
    // After five wrong tries in a row the pad waits 30 seconds.
    var misses by remember { mutableStateOf(0) }
    var waitUntil by remember { mutableStateOf(0L) }
    LaunchedEffect(entry) {
        if (entry.length < 4) return@LaunchedEffect
        if (store.checkPin(entry)) { misses = 0; onOk(); return@LaunchedEffect }
        wrong = true; entry = ""
        if (++misses >= 5) { misses = 0; waitUntil = System.currentTimeMillis() + 30_000 }
    }
    val now = rememberNow(1000)
    val waiting = now < waitUntil
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        VText(title, size = 16.sp, color = V.Text)
        Gap(h = 14.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(maxOf(4, entry.length)) { i -> Box(Modifier.size(16.dp).clip(OctS).background(if (i < entry.length) V.Teal else V.Line)) }
        }
        Gap(h = 6.dp)
        VText(if (waiting) "TRY AGAIN IN ${(waitUntil - now + 999) / 1000}S" else if (wrong) "WRONG PIN" else " ", size = 11.sp, color = V.Coral)
        Gap(h = 10.dp)
        Box(Modifier.width(300.dp).size(300.dp, 340.dp)) {
            NumPad(Modifier.fillMaxSize()) { k ->
                if (waiting) return@NumPad
                wrong = false
                entry = when (k) { "<" -> entry.dropLast(1); "." -> ""; else -> entry + k }
            }
        }
        Gap(h = 14.dp)
        VButton("Cancel", accent = V.Muted) { onCancel() }
    }
}
