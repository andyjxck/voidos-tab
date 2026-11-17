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
                LockPin(store, title = "ENTER PIN TO UNLOCK", onOk = { askPin = false; onUnlock() }, onCancel = { askPin = false })
            }
        }
    }
}

/** Slow drifting lap ticks and a faint mark: the screen is alive but dark, kind to an always-on panel. */
@Composable
private fun LockBackdrop(now: Long) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val unit = minOf(w, h)
        val c = Offset(w * (if (w > h) 0.27f else 0.5f), h * (if (w > h) 0.5f else 0.28f))
        val drift = ((now / 1000) % 600) / 600f * 2 * PI
        val ring = unit * 0.42f
        for (i in 0 until 120) {
            val a = i / 120.0 * 2 * PI + drift * 0.1
            val col = V.Lap[(i / 20) % 6].copy(alpha = 0.10f)
            drawLine(col, Offset(c.x + (ring * cos(a)).toFloat(), c.y + (ring * sin(a)).toFloat()),
                Offset(c.x + ((ring + unit * 0.03f) * cos(a)).toFloat(), c.y + ((ring + unit * 0.03f) * sin(a)).toFloat()), unit * 0.004f, StrokeCap.Round)
        }
        drawOct(c, ring * 0.92f, V.Line.copy(alpha = 0.5f), width = 2f)
    }
}

@Composable
private fun LockClock(store: Store, now: Long, big: Boolean, start: Boolean = false) {
    val cal = Calendar.getInstance().apply { timeInMillis = now }
    val minute = cal.get(Calendar.MINUTE)
    val wx by WeatherRepo.state
    val bat = rememberBattery()
    Column(horizontalAlignment = if (start) Alignment.Start else Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VoidMark(Modifier.size(30.dp))
            Gap(w = 12.dp)
            VText(store.hubName.uppercase() + " HUB", size = 14.sp, color = V.Muted, spacing = 4.sp)
        }
        Gap(h = 12.dp)
        VText(SimpleDateFormat(if (store.use24h) "HH:mm" else "h:mm", Locale.UK).format(Date(now)), size = if (big) 128.sp else 80.sp, color = V.White)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(OctS).background(V.Lap[minute % 6]))
            Gap(w = 10.dp)
            VText(SimpleDateFormat("EEEE d MMMM", Locale.UK).format(Date(now)).uppercase(), size = 18.sp, color = V.Text, spacing = 2.sp)
        }
        Gap(h = 14.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            wx?.let { w ->
                Glyph(wxGlyph(w.code, w.isDay), wxColour(w.code, w.isDay), Modifier.size(28.dp))
                Gap(w = 8.dp)
                VText("${t(w.temp)}  ${wxWords(w.code).uppercase()}", size = 14.sp, color = V.Text, spacing = 1.sp)
                Gap(w = 22.dp)
            }
            Glyph(G.BATTERY, if (bat.charging) V.Green else V.Muted, Modifier.size(24.dp))
            Gap(w = 8.dp)
            VText(if (bat.pct >= 0) "${bat.pct}%" + if (bat.charging) " CHARGING" else "" else "--", size = 14.sp, color = V.Text, spacing = 1.sp)
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
            Modifier.fillMaxWidth().clip(Oct).background(V.Panel).border(1.dp, V.Purple.copy(alpha = 0.6f), Oct).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(56.dp).clip(OctS).background(V.Panel2), contentAlignment = Alignment.Center) {
                val art = np.art
                if (art != null) Image(art.asImageBitmap(), null, Modifier.fillMaxSize()) else Glyph(G.MEDIA, V.Purple, Modifier.size(28.dp))
            }
            Gap(w = 12.dp)
            Column(Modifier.weight(1f)) {
                VText(np.title, size = 15.sp, color = V.White, font = FontFamily.Default, maxLines = 1)
                VText(np.artist.ifBlank { np.appName }, size = 12.sp, color = V.Muted, font = FontFamily.Default, maxLines = 1)
            }
            IconTap(G.PREV, V.Text, 44.dp) { Now.prev() }
            Gap(w = 6.dp)
            IconTap(if (np.playing) G.PAUSE else G.PLAY, V.Purple, 44.dp) { Now.playPause() }
            Gap(w = 6.dp)
            IconTap(G.NEXT, V.Text, 44.dp) { Now.next() }
        }
    }
    timers.take(3).forEach { tm ->
        Row(
            Modifier.fillMaxWidth().clip(OctS).background(V.Panel).border(1.dp, V.Lap[tm.colour % 6].copy(alpha = 0.6f), OctS).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(G.TIMER, V.Lap[tm.colour % 6], Modifier.size(22.dp))
            Gap(w = 10.dp)
            VText(tm.label.uppercase(), size = 13.sp, color = V.Text, spacing = 1.sp, modifier = Modifier.weight(1f))
            VText(fmtDur(tm.remaining(now)), size = 22.sp, color = V.White, font = Jupi)
        }
    }
    if (store.lockNotices) {
        notices.take(if (LocalPortrait.current) 5 else 3).forEach { n ->
            Row(
                Modifier.fillMaxWidth().clip(OctS).background(V.Panel).border(1.dp, V.Line, OctS).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(G.BELL, V.Teal, Modifier.size(20.dp))
                Gap(w = 10.dp)
                Column(Modifier.weight(1f)) {
                    VText("${n.appName.uppercase()} / ${n.title}", size = 11.sp, color = V.Teal, maxLines = 1, spacing = 1.sp)
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
        VText("SWIPE UP TO OPEN", size = 12.sp, color = V.Muted, spacing = 3.sp, align = TextAlign.Center)
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
        VText(title, size = 16.sp, color = V.Text, spacing = 2.sp)
        Gap(h = 14.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(maxOf(4, entry.length)) { i -> Box(Modifier.size(16.dp).clip(OctS).background(if (i < entry.length) V.Teal else V.Line)) }
        }
        Gap(h = 6.dp)
        VText(if (waiting) "TRY AGAIN IN ${(waitUntil - now + 999) / 1000}S" else if (wrong) "WRONG PIN" else " ", size = 11.sp, color = V.Coral, spacing = 1.sp)
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
