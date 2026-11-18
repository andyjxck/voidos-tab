package com.andysocial.voidos.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andysocial.voidos.Alarm
import com.andysocial.voidos.AlarmMath
import com.andysocial.voidos.AlarmPreview
import com.andysocial.voidos.AlarmService
import com.andysocial.voidos.AlarmSounds
import com.andysocial.voidos.Alarms
import com.andysocial.voidos.Ringing
import com.andysocial.voidos.SoundCat
import com.andysocial.voidos.VoidApp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs


private fun use24h() = runCatching { VoidApp.instance.store.use24h }.getOrDefault(true)

// ===================================================================== list

/** Tools > Alarms: the alarm list, or the editor when one is open. */
@Composable
fun AlarmsPane() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Alarms.load(ctx) }
    var editing by remember { mutableStateOf<Alarm?>(null) }
    val e = editing
    if (e != null) {
        AlarmEditor(e, isNew = Alarms.get(e.id) == null, onClose = { editing = null })
        return
    }
    val list = Alarms.list.value
    val now = rememberNow(15_000)
    val next = AlarmMath.soonest(list, now, Alarms.zone)
    fun newAlarm() = Alarm(id = Alarms.newId(), hour = 7, minute = 0)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(if (next != null) "Next alarm" else "Alarms")
                Gap(h = 4.dp)
                VText(
                    if (next != null) AlarmMath.untilLabel(next.second - now).replaceFirstChar(Char::uppercase) else if (list.isEmpty()) "None set" else "All off",
                    size = 22.sp, font = SansLight, color = V.Text, maxLines = 1,
                )
            }
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(V.Teal).testTag("alarm_add")
                    .semantics { contentDescription = "Add alarm" }.clickable { editing = newAlarm() },
                contentAlignment = Alignment.Center,
            ) { Glyph(G.PLUS, V.Bg, Modifier.size(22.dp)) }
        }
        if (list.isEmpty()) {
            Centered(Modifier.fillMaxSize()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Glyph(G.BELL, V.Tick, Modifier.size(64.dp))
                    Gap(h = 16.dp)
                    VText("No alarms yet", size = 20.sp, color = V.Text, font = SansLight)
                    Gap(h = 6.dp)
                    VText("Wake up to birds, a bugle or a screaming goat.", size = 14.sp, color = V.Muted, align = TextAlign.Center)
                    Gap(h = 20.dp)
                    VButton("Add alarm", filled = true) { editing = newAlarm() }
                }
            }
        } else {
            LazyVerticalGrid(
                GridCells.Adaptive(300.dp),
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(list, key = { it.id }) { a -> AlarmCard(a, now) { editing = a } }
            }
        }
    }
}

@Composable
private fun AlarmCard(a: Alarm, now: Long, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    val h24 = use24h()
    val snoozed = a.enabled && a.snoozeAt > now
    val skipping = a.enabled && a.skipUntil > now
    val sound = AlarmSounds.byKey(a.sound)
    Column(
        Modifier.fillMaxWidth().pane(Oct)
            .clickable(onClick = onEdit).padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)
            .testTag("alarm_${a.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    VText(AlarmMath.clock(a.hour, a.minute, h24), size = 52.sp, font = SansThin, color = if (a.enabled) V.White else V.Muted, maxLines = 1)
                    if (!h24) { Gap(w = 6.dp); VText(AlarmMath.ampm(a.hour), size = 18.sp, color = if (a.enabled) V.Text else V.Muted, modifier = Modifier.padding(bottom = 10.dp)) }
                }
                VText(listOf(a.label.ifBlank { "Alarm" }, AlarmMath.daysLabel(a.days)).joinToString("  ·  "), size = 14.sp, color = if (a.enabled) V.Text else V.Muted, maxLines = 1)
                Gap(h = 2.dp)
                VText(sound.name + if (a.rampSec > 0 && sound.cat != SoundCat.PROGRESSIVE) ", gradual" else "", size = 13.sp, color = V.Muted, maxLines = 1)
            }
            VToggle(a.enabled) { Alarms.setEnabled(ctx, a.id, it) }
        }
        when {
            snoozed -> {
                Gap(h = 10.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VText("Snoozed until " + SimpleDateFormat(if (h24) "HH:mm" else "h:mm a", Locale.UK).format(Date(a.snoozeAt)), size = 13.sp, color = V.Teal, modifier = Modifier.weight(1f), maxLines = 1)
                    SmallKey("Stop snooze") { AlarmService.cancelSnooze(ctx, a.id) }
                }
            }
            a.enabled && a.repeats -> {
                Gap(h = 10.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VText(if (skipping) "Skipping the next one" else "", size = 13.sp, color = V.Muted, modifier = Modifier.weight(1f), maxLines = 1)
                    SmallKey(if (skipping) "Undo skip" else "Skip next") { Alarms.skipNext(ctx, a.id, !skipping) }
                }
            }
        }
    }
}

// =================================================================== editor

private val rampChoices = listOf(0 to "Off", 30 to "30 s", 60 to "1 min", 120 to "2 min", 300 to "5 min")
private val snoozeChoices = listOf(0 to "Off", 5 to "5", 9 to "9", 10 to "10", 15 to "15")

@Composable
fun AlarmEditor(start: Alarm, isNew: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var a by remember(start.id) { mutableStateOf(start) }
    var pickSound by rememberSaveable { mutableStateOf(false) }
    val portrait = LocalPortrait.current
    DisposableEffect(Unit) { onDispose { AlarmPreview.stop() } }

    fun save() { AlarmPreview.stop(); Alarms.put(ctx, a.copy(enabled = true, label = a.label.trim())); onClose() }

    Column(Modifier.fillMaxSize()) {
        // top bar: Cancel | title | Save
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                VText("Cancel", size = 17.sp, color = V.Teal, modifier = Modifier.clip(Pill).clickable { AlarmPreview.stop(); onClose() }.padding(horizontal = 6.dp, vertical = 10.dp))
            }
            VText(if (isNew) "New alarm" else "Edit alarm", size = 17.sp, font = SansMedium, color = V.White)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                VText("Save", size = 17.sp, font = SansMedium, color = V.Teal,
                    modifier = Modifier.clip(Pill).testTag("alarm_save").clickable { save() }.padding(horizontal = 6.dp, vertical = 10.dp))
            }
        }
        val wheel = @Composable { m: Modifier, rowH: Dp ->
            TimeWheel(a.hour, a.minute, use24h(), m, rowH) { h, mi -> a = a.copy(hour = h, minute = mi) }
        }
        val options = @Composable { m: Modifier ->
            Column(m) {
                AlarmOptions(a, pickSound, { pickSound = it }, { a = it })
                if (!isNew) {
                    Gap(h = 16.dp)
                    Box(
                        Modifier.fillMaxWidth().pane(Oct).clickable { AlarmPreview.stop(); Alarms.remove(ctx, a.id); onClose() }
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) { VText("Delete alarm", size = 16.sp, color = V.Coral) }
                }
                Gap(h = 24.dp)
            }
        }
        if (portrait) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                wheel(Modifier.fillMaxWidth().height(250.dp), 50.dp)
                Gap(h = 16.dp)
                options(Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(Modifier.weight(0.42f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    wheel(Modifier.fillMaxWidth().height(300.dp), 60.dp)
                }
                options(Modifier.weight(0.58f).fillMaxHeight().verticalScroll(rememberScrollState()))
            }
        }
    }
}

@Composable
private fun AlarmOptions(a: Alarm, pickSound: Boolean, setPick: (Boolean) -> Unit, set: (Alarm) -> Unit) {
    val ctx = LocalContext.current
    val sound = AlarmSounds.byKey(a.sound)

    Label("Repeat", Modifier.padding(start = 4.dp, bottom = 8.dp))
    GroupBox {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, d ->
                val on = a.days and (1 shl i) != 0
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(if (on) V.Teal else V.Panel2)
                        .testTag("day_$i").clickable { set(a.copy(days = a.days xor (1 shl i))) },
                    contentAlignment = Alignment.Center,
                ) { VText(d, size = 15.sp, font = SansMedium, color = if (on) V.Bg else V.Text) }
            }
        }
        Gap(h = 12.dp)
        val presets = listOf(0 to "Once", AlarmMath.WEEKDAYS to "Weekdays", AlarmMath.WEEKENDS to "Weekends", AlarmMath.EVERY_DAY to "Every day")
        GlassSegments(presets.map { it.first.toString() to it.second }, a.days.toString(), Modifier.fillMaxWidth(), height = 40.dp) { k ->
            set(a.copy(days = k.toInt()))
        }
    }
    Gap(h = 18.dp)

    Label("Label", Modifier.padding(start = 4.dp, bottom = 8.dp))
    VField(a.label, { set(a.copy(label = it.take(40))) }, Modifier.fillMaxWidth(), placeholder = "Alarm")
    Gap(h = 18.dp)

    Label("Sound", Modifier.padding(start = 4.dp, bottom = 8.dp))
    GroupBox(padding = 0.dp) {
        Row(
            Modifier.fillMaxWidth().clickable { setPick(!pickSound) }.padding(horizontal = 16.dp, vertical = 14.dp).testTag("alarm_sound"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                VText(sound.name, size = 16.sp, color = V.White, maxLines = 1)
                VText(sound.cat.label + "  ·  " + sound.about, size = 13.sp, color = V.Muted, maxLines = 1)
            }
            Glyph(G.CHEV, V.Muted, Modifier.size(16.dp).graphicsLayer { rotationZ = if (pickSound) 90f else 0f })
        }
        if (pickSound) SoundPicker(a.sound, a.volume) { set(a.copy(sound = it)) }
    }
    Gap(h = 18.dp)

    Label("Gradual volume", Modifier.padding(start = 4.dp, bottom = 8.dp))
    if (sound.cat == SoundCat.PROGRESSIVE) {
        GroupBox { VText("${sound.name} builds up on its own, from quiet to loud.", size = 14.sp, color = V.Muted) }
    } else {
        Segmented(rampChoices.map { it.second }, rampChoices.indexOfFirst { it.first == a.rampSec }.coerceAtLeast(0), Modifier.fillMaxWidth()) {
            set(a.copy(rampSec = rampChoices[it].first))
        }
    }
    Gap(h = 18.dp)

    Label("Volume", Modifier.padding(start = 4.dp, bottom = 8.dp))
    GroupBox(padding = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            Glyph(G.VOLUME, V.Muted, Modifier.size(20.dp))
            Gap(w = 10.dp)
            VSlider(a.volume, Modifier.weight(1f)) { set(a.copy(volume = it.coerceIn(0.05f, 1f))) }
            Gap(w = 10.dp)
            VText("${(a.volume * 100).toInt()}%", size = 14.sp, color = V.Muted, modifier = Modifier.width(44.dp), align = TextAlign.End)
        }
    }
    Gap(h = 18.dp)

    Label("Snooze (minutes)", Modifier.padding(start = 4.dp, bottom = 8.dp))
    Segmented(snoozeChoices.map { it.second }, snoozeChoices.indexOfFirst { it.first == a.snoozeMin }.coerceAtLeast(0), Modifier.fillMaxWidth()) {
        set(a.copy(snoozeMin = snoozeChoices[it].first))
    }
    LaunchedEffect(Unit) { Alarms.load(ctx) }
}

@Composable
private fun GroupBox(padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().pane(Oct).padding(padding), content = content)

@Composable
private fun SoundPicker(selected: String, volume: Float, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    var cat by remember { mutableStateOf(AlarmSounds.byKey(selected).cat) }
    val playing = AlarmPreview.playing.value
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(V.Line))
        Gap(h = 12.dp)
        // categories as chips that wrap onto the width they have
        GlassSegments(SoundCat.entries.map { it.name to it.label }, cat.name, Modifier.fillMaxWidth(), height = 40.dp, textSize = 13f) { cat = SoundCat.valueOf(it) }
        Gap(h = 8.dp)
        AlarmSounds.all.filter { it.cat == cat }.forEach { s ->
            val on = s.key == selected
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) V.Panel2 else Color.Transparent)
                    .clickable { onPick(s.key) }.padding(horizontal = 8.dp, vertical = 8.dp).testTag("sound_${s.key}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(if (playing == s.key) V.Teal else V.Panel2)
                        .border(1.dp, V.White.copy(alpha = 0.06f), CircleShape)
                        .semantics { contentDescription = "Preview ${s.name}" }
                        .clickable { AlarmPreview.toggle(ctx, s.key, volume) },
                    contentAlignment = Alignment.Center,
                ) { Glyph(if (playing == s.key) G.PAUSE else G.PLAY, if (playing == s.key) V.Bg else V.Text, Modifier.size(16.dp)) }
                Gap(w = 12.dp)
                Column(Modifier.weight(1f)) {
                    VText(s.name, size = 15.sp, color = V.White, maxLines = 1)
                    VText(s.about, size = 12.sp, color = V.Muted, maxLines = 1)
                }
                if (on) Glyph(G.CHECK, V.Teal, Modifier.size(18.dp))
            }
        }
    }
}

// ==================================================================== wheel

/** Hour and minute wheels (plus AM/PM in 12-hour mode), like the iPhone's. */
@Composable
fun TimeWheel(hour: Int, minute: Int, h24: Boolean, modifier: Modifier, rowH: Dp, onChange: (Int, Int) -> Unit) {
    val pm = hour >= 12
    Box(modifier, contentAlignment = Alignment.Center) {
        // the band the chosen row sits in
        Box(Modifier.fillMaxWidth().height(rowH).clip(RoundedCornerShape(14.dp)).background(V.Panel2))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (h24) {
                Wheel(24, hour, { String.format(Locale.UK, "%02d", it) }, rowH, Modifier.width(rowH * 1.7f), "wheel_hour") { onChange(it, minute) }
            } else {
                Wheel(12, hour % 12, { if (it == 0) "12" else "$it" }, rowH, Modifier.width(rowH * 1.7f), "wheel_hour") { onChange(it + if (pm) 12 else 0, minute) }
            }
            VText(":", size = (rowH.value * 0.62f).sp, font = SansThin, color = V.White, modifier = Modifier.padding(bottom = 6.dp))
            Wheel(60, minute, { String.format(Locale.UK, "%02d", it) }, rowH, Modifier.width(rowH * 1.7f), "wheel_minute") { onChange(hour, it) }
            if (!h24) {
                Gap(w = 12.dp)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(false to "AM", true to "PM").forEach { (isPm, t) ->
                        Box(
                            Modifier.clip(Pill).background(if (pm == isPm) V.Text else V.Panel).clickable {
                                if (pm != isPm) onChange((hour + 12) % 24, minute)
                            }.padding(horizontal = 14.dp, vertical = 8.dp),
                        ) { VText(t, size = 15.sp, color = if (pm == isPm) V.Bg else V.Text) }
                    }
                }
            }
        }
    }
}

/** One wheel: an endless list that snaps to the middle row. */
@Composable
private fun Wheel(count: Int, value: Int, label: (Int) -> String, rowH: Dp, modifier: Modifier, tag: String, onChange: (Int) -> Unit) {
    val loops = 400
    val mid = count * (loops / 2)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = mid + value)
    val fling = rememberSnapFlingBehavior(state, SnapPosition.Center)
    val centre by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val c = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - c) }?.index ?: (mid + value)
        }
    }
    val cur by rememberUpdatedState(value)
    val cb by rememberUpdatedState(onChange)
    LaunchedEffect(state) {
        snapshotFlow { centre }.collect { i -> val v = ((i % count) + count) % count; if (v != cur) cb(v) }
    }
    BoxWithConstraints(modifier.fillMaxHeight()) {
        val pad = ((maxHeight - rowH) / 2).coerceAtLeast(0.dp)
        LazyColumn(
            state = state,
            flingBehavior = fling,
            contentPadding = PaddingValues(vertical = pad),
            modifier = Modifier.fillMaxSize().testTag(tag),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(count * loops) { i ->
                val d = abs(i - centre)
                Box(Modifier.height(rowH).fillMaxWidth().alpha(when (d) { 0 -> 1f; 1 -> 0.45f; 2 -> 0.22f; else -> 0.1f }), contentAlignment = Alignment.Center) {
                    VText(label(((i % count) + count) % count), size = (rowH.value * (if (d == 0) 0.62f else 0.5f)).sp,
                        font = if (d == 0) SansLight else SansThin, color = V.White, maxLines = 1)
                }
            }
        }
    }
}

// ==================================================================== ringing

/** Shown over everything in VoidOS while an alarm rings (the same screen AlarmActivity shows over the lock screen). */
@Composable
fun AlarmOverlay() {
    val ctx = LocalContext.current
    val r = Alarms.ringing.value ?: return
    AlarmRingScreen(r, onSnooze = { AlarmService.snooze(ctx) }, onStop = { AlarmService.dismiss(ctx) })
}

@Composable
fun AlarmRingScreen(r: Ringing, onSnooze: () -> Unit, onStop: () -> Unit) {
    val now = rememberNow(1000)
    val h24 = use24h()
    val pulse = rememberInfiniteTransition(label = "ring")
    val glow by pulse.animateFloat(0.25f, 0.6f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow")
    val bell by pulse.animateFloat(-14f, 14f, infiniteRepeatable(tween(160), RepeatMode.Reverse), label = "bell")
    BoxWithConstraints(
        Modifier.fillMaxSize().background(V.Deep).clickable(enabled = false) {}.testTag("alarm_ringing"),
        contentAlignment = Alignment.Center,
    ) {
        val portrait = maxHeight > maxWidth
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                Brush.radialGradient(listOf(V.Teal.copy(alpha = glow * 0.35f), Color.Transparent), center = Offset(size.width / 2, size.height * 0.42f), radius = size.minDimension * 0.75f),
                radius = size.maxDimension, center = Offset(size.width / 2, size.height * 0.42f),
            )
        }
        val clockSize = (minOf(maxWidth.value, maxHeight.value * 1.3f) * 0.2f).coerceIn(72f, 170f).sp
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Glyph(G.BELL, V.Teal, Modifier.size(40.dp).graphicsLayer { rotationZ = bell })
            Gap(h = 14.dp)
            VText(r.alarm.label.ifBlank { "Alarm" }, size = 24.sp, color = V.Text, font = SansMedium, align = TextAlign.Center, maxLines = 2)
            Gap(h = 4.dp)
            Row(verticalAlignment = Alignment.Bottom) {
                VText(SimpleDateFormat(if (h24) "HH:mm" else "h:mm", Locale.UK).format(Date(now)), size = clockSize, color = V.White, font = SansThin, maxLines = 1)
                if (!h24) VText(SimpleDateFormat("a", Locale.UK).format(Date(now)), size = 26.sp, color = V.Text, modifier = Modifier.padding(bottom = 24.dp, start = 8.dp))
            }
            VText(SimpleDateFormat("EEEE d MMMM", Locale.UK).format(Date(now)), size = 18.sp, color = V.Muted)
            Gap(h = if (portrait) 64.dp else 36.dp)
            val w = Modifier.widthIn(max = 420.dp).fillMaxWidth()
            if (r.alarm.snoozeMin > 0) {
                Box(
                    w.height(76.dp).glass(Pill).testTag("alarm_snooze").clickable(onClick = onSnooze),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        VText("Snooze", size = 22.sp, color = V.White, font = SansMedium)
                        VText("${r.alarm.snoozeMin} minutes", size = 13.sp, color = V.Muted)
                    }
                }
                Gap(h = 14.dp)
            }
            Box(
                w.height(64.dp).clip(Pill).background(V.Teal).testTag("alarm_stop").clickable(onClick = onStop),
                contentAlignment = Alignment.Center,
            ) { VText("Stop", size = 20.sp, color = V.Bg, font = SansMedium) }
        }
    }
}
