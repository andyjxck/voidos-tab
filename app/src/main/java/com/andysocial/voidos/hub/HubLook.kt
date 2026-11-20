package com.andysocial.voidos.hub

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.andysocial.voidos.ui.G
import com.andysocial.voidos.ui.Gap
import com.andysocial.voidos.ui.Glyph
import com.andysocial.voidos.ui.Label
import com.andysocial.voidos.ui.SansMedium
import com.andysocial.voidos.ui.Type
import com.andysocial.voidos.ui.V
import com.andysocial.voidos.ui.VText
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.background
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import com.andysocial.voidos.ui.Grid
import com.andysocial.voidos.ui.Radius

/*
 * The Void Hub look: a blend of VoidOS's first face and today's.
 *
 * From the first VoidOS (the PineTime-style analogue face, commits ba5cf3c–f406fb0): the 60-tick
 * ring round the dial, the octagon outline it sat in, the thin hour bars standing in a row, and
 * the small uppercase spaced labels ("STEPS") under a big number. From today's: liquid glass
 * panels, Manrope everywhere (the user asked for one font), the single teal accent with coral
 * kept for warnings.
 *
 * So in the hub: every status glow is the old tick ring, lit in the status colour; every
 * sparkline is a row of the old hour bars; numbers are big light Manrope over an old-style
 * label; and it all sits on the glass.
 */
object HubLook {
    fun colour(s: HubStatus): Color = when (s) {
        HubStatus.FINE -> V.Teal
        HubStatus.ATTENTION -> V.Yellow
        HubStatus.ERRORS -> V.Coral
        HubStatus.UNKNOWN -> V.Tick
    }
}

/**
 * The old dial's tick ring, as a status light: [ticks] short marks round a circle, every fifth
 * one longer, lit in [colour] up to [fill] (0..1) and dim after it. Used round every app on the
 * status board, and as the glow behind an app's tile.
 */
@Composable
fun TickRing(colour: Color, modifier: Modifier = Modifier, fill: Float = 1f, ticks: Int = 60, glow: Boolean = true) {
    Canvas(modifier) { tickRing(colour, fill, ticks, glow) }
}

fun DrawScope.tickRing(colour: Color, fill: Float = 1f, ticks: Int = 60, glow: Boolean = true) {
    val r = size.minDimension / 2f
    val c = center
    if (glow) drawCircle(colour.copy(alpha = 0.16f), r * 0.98f, c)
    val lit = (fill.coerceIn(0f, 1f) * ticks).toInt()
    for (i in 0 until ticks) {
        val major = i % 5 == 0
        val a = (i / ticks.toFloat()) * 2f * PI.toFloat() - PI.toFloat() / 2f
        val outer = r * 0.97f
        val inner = r * (if (major) 0.84f else 0.89f)
        val col = if (i < lit) colour else V.Tick.copy(alpha = 0.55f)
        drawLine(col, Offset(c.x + cos(a) * inner, c.y + sin(a) * inner), Offset(c.x + cos(a) * outer, c.y + sin(a) * outer),
            strokeWidth = (if (major) 2.6f else 1.6f) * density, cap = StrokeCap.Round)
    }
}

/** The old face's octagon outline, drawn as a thin line: the frame round the metric view's big number. */
fun DrawScope.octagon(colour: Color, width: Float = 1.4f) {
    val w = size.width; val h = size.height; val k = minOf(w, h) * 0.18f
    val p = Path().apply {
        moveTo(k, 0f); lineTo(w - k, 0f); lineTo(w, k); lineTo(w, h - k); lineTo(w - k, h)
        lineTo(k, h); lineTo(0f, h - k); lineTo(0f, k); close()
    }
    drawPath(p, colour, style = Stroke(width * density))
}

/**
 * A sparkline as the old face's hour bars: one thin rounded bar per day, the last (today) in the
 * accent and the rest dimmer. Empty series draws nothing.
 */
@Composable
fun TickBars(series: List<Double>, colour: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (series.isEmpty()) return@Canvas
        val max = series.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val n = series.size
        val gap = size.width / n
        val bw = (gap * 0.42f).coerceAtMost(9f * density)
        series.forEachIndexed { i, v ->
            val h = (v / max).toFloat().coerceIn(0.04f, 1f) * size.height
            val x = gap * i + gap / 2f
            val col = if (i == n - 1) colour else colour.copy(alpha = 0.38f)
            drawLine(col, Offset(x, size.height), Offset(x, size.height - h), strokeWidth = bw, cap = StrokeCap.Round)
        }
    }
}

/** A figure the old way: the uppercase label over a big number, with a short line under it. */
@Composable
fun Figure(label: String, value: String, modifier: Modifier = Modifier, sub: String = "", accent: Color = V.White, big: Boolean = false) {
    Column(modifier) {
        Label(label)
        Gap(h = 2.dp)
        com.andysocial.voidos.ui.FitText(value, if (big) Type.display else Type.large, Modifier.fillMaxWidth(), color = accent,
            font = com.andysocial.voidos.ui.SansLight)
        if (sub.isNotEmpty()) VText(sub, size = Type.caption, color = V.Muted, maxLines = 1)
    }
}

/** A small status dot with its word: the panel's header line. */
@Composable
fun StatusChip(s: HubStatus, modifier: Modifier = Modifier, text: String = s.label) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp)) { TickRing(HubLook.colour(s), Modifier.size(14.dp), ticks = 12, glow = false) }
        Gap(w = 6.dp)
        VText(text, size = Type.label, color = if (s == HubStatus.UNKNOWN) V.Muted else HubLook.colour(s), font = SansMedium, maxLines = 1)
    }
}

/**
 * The empty state every hub surface uses until real data exists: "Nothing linked yet" over
 * "Link App Store Connect in Settings › Hub". [what] names what's missing; [how] the next step.
 */
/**
 * The one empty state on the tablet: a short dash line and "No data", nothing else. Setup help
 * lives in docs/hub-setup.md, never on the screen.
 */
@Composable
fun NotConnected(what: String = "No data", modifier: Modifier = Modifier, glyphSize: Dp = 40.dp) {
    Column(modifier.padding(Grid.l).testTag("empty_state"), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(width = (glyphSize.value * 0.6f).dp, height = 2.dp).background(V.Tick, Radius.Chip))
        Gap(h = Grid.m)
        VText(what, size = Type.label, font = SansMedium, color = V.Muted, maxLines = 1, spacing = 1.2.sp,
            align = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** The line under every hub screen's title: "updated 6 min ago", or the last problem. */
fun freshness(s: HubSummary?, now: Long = System.currentTimeMillis()): String {
    if (s == null) return ""
    val base = Hub.ago(s.updatedAt, now)
    return when {
        HubRepo.error != null -> "${HubRepo.error} · showing the last numbers, $base"
        else -> base
    }
}

/**
 * The hub's Home widgets: revenue today, downloads today, crashes, uptime, and the overall status.
 * Each is the old face's label-over-number on the glass, with the tick ring or hour bars.
 */
@Composable
fun HubWidget(kind: String, wide: Boolean, iw: Float, ih: Float) {
    val s = HubRepo.summary
    val (label, metric) = when (kind) {
        "hrev" -> "Revenue today" to Metric.REVENUE
        "hdl" -> "Downloads today" to Metric.DOWNLOADS
        "hcrash" -> "Crashes today" to Metric.CRASHES
        "hup" -> "Uptime" to Metric.UPTIME
        else -> "Status" to null
    }
    Column(Modifier.fillMaxWidth()) {
        Label(label)
        Gap(h = 4.dp)
        val v0 = metric?.let { m -> s?.let { Hub.total(it, m) } }
        if (s == null || s.apps.isEmpty() || (metric != null && v0 == null)) {
            VText("\u2014", size = Type.title2, color = V.Muted, font = SansMedium, maxLines = 1)
            return@Column
        }
        if (metric == null) {
            val st = s.overall
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(minOf(56f, ih - 30f).coerceAtLeast(16f).dp)) { TickRing(HubLook.colour(st), Modifier.fillMaxWidth().size(minOf(56f, ih - 30f).coerceAtLeast(16f).dp)) }
                Gap(w = 10.dp)
                Column {
                    HubStatus.entries.filter { it != HubStatus.UNKNOWN }.forEach { k ->
                        val n = s.apps.count { it.glow == k }
                        if (n > 0) VText("$n ${k.label.lowercase()}", size = Type.label, color = HubLook.colour(k), maxLines = 1)
                    }
                }
            }
            return@Column
        }
        val v = Hub.total(s, metric)
        val col = if (metric == Metric.CRASHES && (v?.today ?: 0.0) > 0) V.Coral else V.White
        com.andysocial.voidos.ui.FitText(Hub.fmt(v?.today, metric, v?.currency ?: "GBP"), 40.sp(), Modifier.fillMaxWidth().size(width = iw.dp, height = minOf(52f, ih * 0.42f).coerceAtLeast(14f).dp),
            color = col, font = com.andysocial.voidos.ui.SansLight)
        if (v != null && v.series.isNotEmpty() && ih > 80f) TickBars(v.series.takeLast(if (wide) 14 else 7), if (col == V.Coral) V.Coral else V.Teal, Modifier.fillMaxWidth().size(width = iw.dp, height = (ih - 74f).coerceIn(10f, 60f).dp))
    }
}

private fun Int.sp() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

/**
 * The standby ring as an alert light: (colour, flashing) or null to leave it as the charging ring.
 * An app that just went live flashes it teal (phase 2); errors glow it coral, and once real data is
 * connected, all fine glows it a steady teal (phase 3). With nothing linked it stays the plain ring.
 */
fun ringAlert(s: HubSummary?, justLive: Boolean): Pair<androidx.compose.ui.graphics.Color, Boolean>? = when {
    justLive -> V.Teal to true
    s == null || s.demo || s.apps.isEmpty() -> null
    s.overall == HubStatus.ERRORS -> V.Coral to false
    s.overall == HubStatus.FINE -> V.Teal to false
    else -> null
}
