package com.andysocial.voidos.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andysocial.voidos.KTimer
import com.andysocial.voidos.KTimers
import com.andysocial.voidos.Store
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

enum class KTab(val label: String, val glyph: G) { TIMERS("Timers", G.TIMER), CONVERT("Convert", G.CONVERT), LIST("List", G.LIST) }

@Composable
fun KitchenScreen(store: Store) {
    var tab by rememberSaveable { mutableStateOf(KTab.TIMERS) }
    Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            KTab.entries.forEach { t ->
                Chip(t.label, t == tab, V.Orange, glyph = t.glyph) { tab = t }
            }
            Box(Modifier.weight(1f))
            val running = KTimers.list.value.count { it.running }
            val left = store.shopping.count { !it.done }
            VText("$running RUNNING / $left TO BUY", size = 11.sp, color = V.Muted, spacing = 1.sp)
        }
        Gap(h = 14.dp)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                KTab.TIMERS -> TimersPane()
                KTab.CONVERT -> ConvertPane()
                KTab.LIST -> ShoppingPane(store)
            }
        }
    }
}

/** A selectable octagon-cut chip. */
@Composable
fun Chip(text: String, selected: Boolean, accent: Color, modifier: Modifier = Modifier, glyph: G? = null, onClick: () -> Unit) {
    Row(
        modifier.clip(OctS).background(if (selected) accent.copy(alpha = 0.18f) else V.Panel)
            .border(1.dp, if (selected) accent else V.Line, OctS).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) { Glyph(glyph, if (selected) accent else V.Muted, Modifier.size(18.dp)); Gap(w = 8.dp) }
        VText(text.uppercase(), size = 12.sp, color = if (selected) accent else V.Text, spacing = 1.sp)
    }
}

fun fmtDur(ms: Long): String {
    val s = (ms + 999) / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) String.format(Locale.UK, "%d:%02d:%02d", h, m, sec) else String.format(Locale.UK, "%d:%02d", m, sec)
}

// ------------------------------------------------------------------- timers

private val presets = listOf("Eggs" to 7, "Pasta" to 10, "Rice" to 12, "Tea" to 4, "Pizza" to 15, "Oven" to 25, "Rest" to 5, "Noodles" to 4)

@Composable
fun TimersPane() {
    val ctx = LocalContext.current
    val now = rememberNow(250)
    remember(now) { KTimers.check(ctx); 0 }
    var mins by remember { mutableIntStateOf(5) }
    var secs by remember { mutableIntStateOf(0) }
    var label by remember { mutableStateOf("Timer") }
    val timers = KTimers.list.value

    Split(Modifier.fillMaxSize()) {
        // new timer
        VPanel(Modifier.side(340.dp, 0.9f), accent = V.Orange) {
            Label("New timer")
            Gap(h = 10.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Stepper("MIN", mins, { mins = (mins + it).coerceIn(0, 240) }, big = 10)
                Gap(w = 6.dp)
                VText(":", size = 40.sp, color = V.Muted, font = Jupi)
                Gap(w = 6.dp)
                Stepper("SEC", secs, { secs = (secs + it).coerceIn(0, 50) }, step = 10, big = 30)
            }
            Gap(h = 12.dp)
            Label("Quick start")
            Gap(h = 8.dp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { (n, m) ->
                    Chip("$n $m", false, V.Orange) { KTimers.add(ctx, n, m * 60_000L) }
                }
            }
            Gap(h = 12.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Timer", "Hob", "Oven", "Grill").forEach { n -> Chip(n, label == n, V.Yellow) { label = n } }
            }
            Box(Modifier.weight(1f))
            VButton("Start ${fmtDur((mins * 60L + secs) * 1000)}", Modifier.fillMaxWidth(), accent = V.Orange, filled = true) {
                val ms = (mins * 60L + secs) * 1000
                if (ms > 0) KTimers.add(ctx, label, ms)
            }
        }
        // running timers
        Box(Modifier.main()) {
            if (timers.isEmpty()) {
                Centered(Modifier.fillMaxSize()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Glyph(G.TIMER, V.Tick, Modifier.size(64.dp))
                        Gap(h = 12.dp)
                        VText("NO TIMERS.\nRUN AS MANY AS YOU LIKE AT ONCE.", size = 13.sp, color = V.Muted, align = TextAlign.Center, spacing = 1.sp, lineHeight = 22.sp)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(200.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(timers, key = { it.id }) { t -> TimerCard(t, now) }
                }
            }
        }
    }
}

@Composable
private fun Stepper(unit: String, value: Int, onChange: (Int) -> Unit, step: Int = 1, big: Int = 10) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallKey("+$big") { onChange(big) }
            SmallKey("+$step") { onChange(step) }
        }
        VText(String.format(Locale.UK, "%02d", value), size = 52.sp, color = V.White, font = Jupi)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallKey("-$big") { onChange(-big) }
            SmallKey("-$step") { onChange(-step) }
        }
        Gap(h = 2.dp)
        VText(unit, size = 9.sp, color = V.Muted, spacing = 2.sp)
    }
}

@Composable
fun SmallKey(text: String, accent: Color = V.Muted, onClick: () -> Unit) {
    Box(
        Modifier.size(54.dp, 36.dp).clip(OctS).background(V.Panel2).border(1.dp, V.Line, OctS).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { VText(text, size = 12.sp, color = accent) }
}

@Composable
private fun TimerCard(t: KTimer, now: Long) {
    val ctx = LocalContext.current
    val left = t.remaining(now)
    val col = if (t.rang) V.Coral else V.Lap[t.colour % 6]
    val frac = if (t.totalMs > 0) (left.toFloat() / t.totalMs).coerceIn(0f, 1f) else 0f
    Column(
        Modifier.fillMaxWidth().clip(Oct).background(V.Panel).border(1.dp, col.copy(alpha = if (t.running || t.rang) 0.9f else 0.4f), Oct).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VText(t.label.uppercase(), size = 12.sp, color = col, spacing = 2.sp, modifier = Modifier.weight(1f), maxLines = 1)
            Box(Modifier.size(30.dp).clickable { KTimers.remove(ctx, t.id) }, contentAlignment = Alignment.Center) { Glyph(G.CLOSE, V.Muted, Modifier.size(16.dp)) }
        }
        Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2, size.height / 2)
                drawOct(c, size.width / 2 - 4f, V.Line, width = 3f)
                val sw = 9f
                drawArc(col.copy(alpha = 0.18f), -90f, 360f, false, Offset(sw * 2, sw * 2), Size(size.width - sw * 4, size.height - sw * 4), style = Stroke(sw))
                drawArc(col, -90f, 360f * frac, false, Offset(sw * 2, sw * 2), Size(size.width - sw * 4, size.height - sw * 4), style = Stroke(sw, cap = StrokeCap.Butt))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                VText(if (t.rang) "DONE" else fmtDur(left), size = if (left >= 3_600_000) 22.sp else 30.sp, color = if (t.rang) V.Coral else V.White, font = Jupi)
                VText(if (t.rang) "TAP RESTART" else if (t.running) "RUNNING" else "PAUSED", size = 9.sp, color = V.Muted, spacing = 1.sp)
            }
        }
        Gap(h = 8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallKey("+1M") { KTimers.addTime(ctx, t.id, 60_000) }
            Box(
                Modifier.size(54.dp, 36.dp).clip(OctS).background(col.copy(alpha = 0.2f)).border(1.dp, col, OctS).clickable { KTimers.toggle(ctx, t.id) },
                contentAlignment = Alignment.Center,
            ) { Glyph(if (t.running) G.PAUSE else G.PLAY, col, Modifier.size(18.dp)) }
            SmallKey("+5M") { KTimers.addTime(ctx, t.id, 300_000) }
        }
    }
}

/** Full-screen alert while a timer is ringing. Shows over every screen. */
@Composable
fun TimerAlert() {
    val ctx = LocalContext.current
    val ringing = KTimers.ringing.value
    if (ringing.isEmpty()) return
    val now = rememberNow(500)
    val flash = (now / 500) % 2 == 0L
    Box(Modifier.fillMaxSize().background(V.Bg.copy(alpha = 0.96f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Glyph(G.BELL, if (flash) V.Coral else V.Orange, Modifier.size(96.dp))
            Gap(h = 16.dp)
            VText(ringing.joinToString(" + ") { it.label }.uppercase(), size = 34.sp, color = V.White, spacing = 3.sp, align = TextAlign.Center)
            Gap(h = 6.dp)
            VText("IS DONE", size = 16.sp, color = V.Coral, spacing = 4.sp)
            Gap(h = 28.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                VButton("Stop", Modifier.width(200.dp), accent = V.Coral, filled = true) { KTimers.silence(ctx) }
                VButton("+1 minute", Modifier.width(200.dp), accent = V.Orange) { ringing.forEach { KTimers.addTime(ctx, it.id, 60_000) } }
            }
        }
    }
}

// ------------------------------------------------------------------ convert

private data class Unit_(val name: String, val short: String, val toBase: (Double) -> Double, val fromBase: (Double) -> Double)

private fun lin(name: String, short: String, factor: Double) = Unit_(name, short, { it * factor }, { it / factor })

private val gasMarks = listOf(1 to 140.0, 2 to 150.0, 3 to 170.0, 4 to 180.0, 5 to 190.0, 6 to 200.0, 7 to 220.0, 8 to 230.0, 9 to 240.0)

private val categories: List<Pair<String, List<Unit_>>> = listOf(
    "Weight" to listOf(lin("Grams", "g", 1.0), lin("Kilograms", "kg", 1000.0), lin("Ounces", "oz", 28.349523125), lin("Pounds", "lb", 453.59237), lin("Stone", "st", 6350.29318)),
    "Volume" to listOf(
        lin("Millilitres", "ml", 1.0), lin("Litres", "l", 1000.0), lin("Teaspoons", "tsp", 5.0), lin("Tablespoons", "tbsp", 15.0),
        lin("Cups (US)", "cup", 236.5882365), lin("Fluid oz (UK)", "fl oz", 28.4130625), lin("Pints (UK)", "pt", 568.26125),
    ),
    "Oven" to listOf(
        Unit_("Celsius", "°C", { it }, { it }),
        Unit_("Fan oven", "°C fan", { it + 20 }, { it - 20 }),
        Unit_("Fahrenheit", "°F", { (it - 32) * 5 / 9 }, { it * 9 / 5 + 32 }),
        Unit_("Gas mark", "gas", { g -> gasMarks.minByOrNull { abs(it.first - g) }!!.second }, { c -> gasMarks.minByOrNull { abs(it.second - c) }!!.first.toDouble() }),
    ),
    "Length" to listOf(lin("Centimetres", "cm", 1.0), lin("Millimetres", "mm", 0.1), lin("Inches", "in", 2.54), lin("Feet", "ft", 30.48)),
)

/** Grams in one US cup, for the cups-to-grams helper. */
private val cupGrams = listOf("Plain flour" to 125.0, "Caster sugar" to 200.0, "Brown sugar" to 220.0, "Icing sugar" to 120.0, "Butter" to 227.0, "Rice (raw)" to 185.0, "Oats" to 90.0, "Milk" to 240.0, "Water" to 237.0, "Honey" to 340.0)

private fun fmtNum(v: Double): String {
    if (v.isNaN() || v.isInfinite()) return "-"
    val a = abs(v)
    return when {
        a >= 100 -> v.roundToLong().toString()
        a >= 10 -> String.format(Locale.UK, "%.1f", v).removeSuffix(".0")
        else -> String.format(Locale.UK, "%.2f", v).trimEnd('0').trimEnd('.')
    }
}

@Composable
fun ConvertPane() {
    var cat by rememberSaveable { mutableIntStateOf(0) }
    var from by rememberSaveable { mutableIntStateOf(0) }
    var entry by rememberSaveable { mutableStateOf("100") }
    var cups by rememberSaveable { mutableStateOf(false) }
    val units = categories[cat].second
    val value = entry.toDoubleOrNull() ?: 0.0

    Split(Modifier.fillMaxSize()) {
        VPanel(Modifier.side(330.dp, 1.1f), accent = V.Orange) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEachIndexed { i, (n, _) -> Chip(n, !cups && cat == i, V.Orange) { cups = false; cat = i; from = 0; entry = if (n == "Oven") "180" else "100" } }
                Chip("Cups", cups, V.Yellow) { cups = true; entry = "1" }
            }
            Gap(h = 12.dp)
            Row(verticalAlignment = Alignment.Bottom) {
                VText(entry.ifEmpty { "0" }, size = 46.sp, color = V.White, font = Jupi, maxLines = 1, modifier = Modifier.weight(1f))
                VText(if (cups) "CUPS" else units[from].short.uppercase(), size = 14.sp, color = V.Orange, spacing = 1.sp)
            }
            Gap(h = 10.dp)
            NumPad(Modifier.weight(1f).fillMaxWidth()) { k ->
                entry = when (k) {
                    "<" -> entry.dropLast(1)
                    "." -> if (entry.contains('.')) entry else (entry.ifEmpty { "0" } + ".")
                    "C" -> ""
                    else -> if (entry == "0") k else (entry + k).take(8)
                }
            }
        }
        Column(Modifier.main()) {
            if (cups) {
                Label("${fmtNum(value)} US cup${if (value == 1.0) "" else "s"} weighs about")
                Gap(h = 10.dp)
                LazyVerticalGrid(GridCells.Adaptive(190.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(cupGrams) { (n, g) -> ResultTile(n, "${fmtNum(g * value)} g", V.Yellow) }
                }
            } else {
                Label("Convert from")
                Gap(h = 8.dp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    units.forEachIndexed { i, u -> Chip(u.short, i == from, V.Orange) { from = i } }
                }
                Gap(h = 14.dp)
                val base = units[from].toBase(value)
                LazyVerticalGrid(GridCells.Adaptive(190.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(units.filterIndexed { i, _ -> i != from }) { u ->
                        val out = u.fromBase(base)
                        ResultTile(u.name, if (u.short == "gas") "Gas ${out.toInt()}" else "${fmtNum(out)} ${u.short}", V.Orange)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultTile(name: String, value: String, accent: Color) {
    Column(Modifier.fillMaxWidth().clip(Oct).background(V.Panel).border(1.dp, V.Line, Oct).padding(14.dp)) {
        VText(name.uppercase(), size = 10.sp, color = V.Muted, spacing = 1.sp)
        Gap(h = 6.dp)
        VText(value, size = 24.sp, color = accent, font = Jupi, maxLines = 1)
    }
}

@Composable
fun NumPad(modifier: Modifier, onKey: (String) -> Unit) {
    val keys = listOf("7", "8", "9", "4", "5", "6", "1", "2", "3", ".", "0", "<")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.chunked(3).forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier.weight(1f).fillMaxSize().clip(OctS).background(V.Panel2).border(1.dp, V.Line, OctS)
                            .clickable { onKey(k) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (k == "<") Glyph(G.BACK, V.Muted, Modifier.size(20.dp)) else VText(k, size = 22.sp, color = V.Text, font = Jupi)
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------- shopping list

private val staples = listOf("Milk", "Bread", "Eggs", "Butter", "Cheese", "Tea", "Coffee", "Pasta", "Rice", "Onions", "Potatoes", "Chicken", "Bananas", "Cat food", "Toilet roll", "Washing up liquid")

@Composable
fun ShoppingPane(store: Store) {
    var text by remember { mutableStateOf("") }
    val items = store.shopping
    Split(Modifier.fillMaxSize()) {
        VPanel(Modifier.side(330.dp, 0.8f), accent = V.Green) {
            Label("Add")
            Gap(h = 8.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                VField(text, { text = it }, Modifier.weight(1f), placeholder = "Type an item", onDone = { store.addItem(text); text = "" })
                Gap(w = 8.dp)
                IconTap(G.PLUS, V.Green, 48.dp) { store.addItem(text); text = "" }
            }
            Gap(h = 12.dp)
            Label("Staples")
            Gap(h = 8.dp)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                staples.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { s ->
                            val on = items.any { !it.done && it.text.equals(s, true) }
                            Chip(s, on, V.Green, Modifier.weight(1f)) { if (!on) store.addItem(s) }
                        }
                    }
                }
            }
        }
        Column(Modifier.main()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("${items.count { !it.done }} to buy, ${items.count { it.done }} in the basket", modifier = Modifier.weight(1f))
                if (items.any { it.done }) VButton("Clear basket", accent = V.Muted) { store.clearDone() }
            }
            Gap(h = 10.dp)
            if (items.isEmpty()) {
                Centered(Modifier.fillMaxSize()) { VText("THE LIST IS EMPTY", size = 13.sp, color = V.Muted, spacing = 1.sp) }
            } else {
                val groups = items.filter { !it.done }.groupBy { it.aisle }.toSortedMap() + (if (items.any { it.done }) mapOf("In the basket" to items.filter { it.done }) else emptyMap())
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                    groups.forEach { (aisle, list) ->
                        item(key = "h_$aisle") { Gap(h = 6.dp); VText(aisle.uppercase(), size = 10.sp, color = V.Green, spacing = 2.sp) }
                        items(list, key = { it.id }) { it2 ->
                            Row(
                                Modifier.fillMaxWidth().clip(OctS).background(V.Panel).border(1.dp, V.Line, OctS)
                                    .clickable { store.toggleItem(it2.id) }.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier.size(24.dp).clip(CutCorner6).background(if (it2.done) V.Green else Color.Transparent).border(1.5.dp, if (it2.done) V.Green else V.Tick, CutCorner6),
                                    contentAlignment = Alignment.Center,
                                ) { if (it2.done) Glyph(G.CHECK, V.Bg, Modifier.size(16.dp)) }
                                Gap(w = 14.dp)
                                VText(it2.text, size = 16.sp, color = if (it2.done) V.Muted else V.Text, font = FontFamily.Default, modifier = Modifier.weight(1f))
                                Box(Modifier.size(32.dp).clickable { store.removeItem(it2.id) }, contentAlignment = Alignment.Center) { Glyph(G.CLOSE, V.Tick, Modifier.size(16.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

val CutCorner6 = androidx.compose.foundation.shape.CutCornerShape(6.dp)

