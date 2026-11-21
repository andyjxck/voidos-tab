package com.andysocial.voidos.hub

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andysocial.voidos.Store
import com.andysocial.voidos.ui.G
import com.andysocial.voidos.ui.Gap
import com.andysocial.voidos.ui.Glyph
import com.andysocial.voidos.ui.Grid
import com.andysocial.voidos.ui.LocalPortrait
import com.andysocial.voidos.ui.OctBtn
import com.andysocial.voidos.ui.OctS
import com.andysocial.voidos.ui.PageHeader
import com.andysocial.voidos.ui.SansMedium
import com.andysocial.voidos.ui.Type
import com.andysocial.voidos.ui.V
import com.andysocial.voidos.ui.VCard
import com.andysocial.voidos.ui.VText
import com.andysocial.voidos.ui.pane
import com.andysocial.voidos.ui.tap
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * 2.0.2, the hub calendar: it takes Weather's place. Every dated thing the Void Hub knows (releases,
 * scheduled and approved versions, review changes, the Apple payout and renewal, SSL and domain
 * expiry, GitHub releases and builds), on a month with dots and in an agenda of Upcoming and Past.
 * Pure parts are in [HubCalendar] so they are tested without a screen.
 */
object HubCalendar {
    fun day(at: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

    /** Today and later, soonest first. */
    fun upcoming(events: List<HubEvent>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<HubEvent> =
        events.filter { !day(it.at, zone).isBefore(today) }.sortedBy { it.at }

    /** Before today, newest first. */
    fun past(events: List<HubEvent>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<HubEvent> =
        events.filter { day(it.at, zone).isBefore(today) }.sortedByDescending { it.at }

    /** The month's events by day of the month. */
    fun byDay(events: List<HubEvent>, month: YearMonth, zone: ZoneId = ZoneId.systemDefault()): Map<Int, List<HubEvent>> =
        events.mapNotNull { e -> day(e.at, zone).takeIf { YearMonth.from(it) == month }?.let { it.dayOfMonth to e } }
            .groupBy({ it.first }, { it.second })

    /** The month's cells, Monday first: null for the blanks before the 1st and after the last day. */
    fun cells(month: YearMonth): List<Int?> {
        val lead = month.atDay(1).dayOfWeek.value - 1
        val days = month.lengthOfMonth()
        val n = ((lead + days + 6) / 7) * 7
        return List(n) { i -> (i - lead + 1).takeIf { it in 1..days } }
    }

    /** Per-app notes, on the day each was last written. */
    fun noteEvents(notes: List<com.andysocial.voidos.Note>, s: HubSummary?): List<HubEvent> =
        notes.filter { it.app.isNotBlank() && it.updated > 0 && s?.app(it.app) != null }.map { n ->
            val first = n.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            HubEvent("note:${n.id}", n.updated, "note", n.app, first?.take(60) ?: "Note", "Edited")
        }

    fun label(kind: String): String = when (kind) {
        "note" -> "Note"
        "release" -> "Release"
        "scheduled" -> "Scheduled"
        "pending" -> "Approved"
        "phased" -> "Phased release"
        "review" -> "Review"
        "payout" -> "Payout"
        "renewal" -> "Renewal"
        "ssl" -> "SSL"
        "domain" -> "Domain"
        "build" -> "GitHub"
        "cost" -> "Cost"
        else -> kind.replaceFirstChar { it.uppercase() }
    }

    /** Teal for things that went out, amber for things on their way or due, coral for trouble. */
    fun colour(e: HubEvent, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Color {
        val t = (e.title + " " + e.detail).lowercase()
        if ("reject" in t || "fail" in t || "invalid" in t || "removed" in t) return V.Coral
        val daysAway = java.time.temporal.ChronoUnit.DAYS.between(today, day(e.at, zone))
        return when (e.kind) {
            "release", "phased", "payout", "build" -> V.Teal
            "note" -> V.Muted
            "ssl", "domain", "renewal", "cost" -> if (daysAway in 0..14) V.Coral else V.Amber
            else -> V.Amber
        }
    }

    fun glyph(kind: String): G = when (kind) {
        "release", "scheduled", "pending", "phased" -> G.STORE
        "review" -> G.STAR
        "payout", "renewal", "cost" -> G.MONEY
        "ssl", "domain" -> G.WEB
        "build" -> G.CODE
        "note" -> G.NOTES
        else -> G.CALENDAR
    }

    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
    private val dayYearFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)
    fun date(at: Long, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
        val d = day(at, zone)
        return when (d) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            today.minusDays(1) -> "Yesterday"
            else -> if (d.year == today.year) d.format(dayFmt) else d.format(dayYearFmt)
        }
    }
}

@Composable
fun CalendarScreen(store: Store) {
    val portrait = LocalPortrait.current
    val s = HubRepo.summary
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val events = s?.events.orEmpty() + HubCalendar.noteEvents(store.notes, s)
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var picked by remember { mutableStateOf<LocalDate?>(null) }
    // the app panel a tapped event opened; the panel's own close clears StatusNav
    var shownFor by remember { mutableStateOf<String?>(null) }
    val openId by StatusNav.open
    Box(Modifier.fillMaxSize().testTag("calendar")) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val narrow = portrait || maxWidth < 640.dp
            Column(Modifier.fillMaxSize()) {
                PageHeader("Calendar", portrait, sub = freshness(s))
                Gap(h = Grid.m)
                if (s == null || events.isEmpty()) {
                    Box(Modifier.fillMaxSize().testTag("calendar_empty"), contentAlignment = Alignment.Center) { NotConnected("NO DATES") }
                    return@Column
                }
                val open: (HubEvent) -> Unit = { e ->
                    if (s.app(e.appId) != null) { shownFor = e.appId; StatusNav.open.value = e.appId }
                }
                val monthCard = @Composable { m: Modifier ->
                    MonthCard(month, today, picked, HubCalendar.byDay(events, month, zone), m,
                        onMonth = { month = it; picked = null },
                        onDay = { d -> picked = if (picked == d) null else d })
                }
                val agenda = @Composable { m: Modifier ->
                    Agenda(events, s, today, picked, m, open)
                }
                if (narrow) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        monthCard(Modifier.fillMaxWidth())
                        Gap(h = Grid.l)
                        agenda(Modifier.fillMaxWidth())
                        Gap(h = Grid.l)
                    }
                } else {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Grid.l)) {
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) { monthCard(Modifier.fillMaxWidth()) }
                        Column(Modifier.weight(1.15f).fillMaxHeight().verticalScroll(rememberScrollState())) { agenda(Modifier.fillMaxWidth()); Gap(h = Grid.l) }
                    }
                }
            }
            val app = shownFor?.takeIf { it == openId }?.let { s?.app(it) }
            androidx.compose.animation.AnimatedVisibility(
                visible = app != null,
                modifier = Modifier.align(if (narrow) Alignment.BottomCenter else Alignment.CenterEnd),
                enter = if (narrow) slideInVertically { it } + fadeIn() else slideInHorizontally { it / 2 } + fadeIn(),
                exit = if (narrow) slideOutVertically { it } + fadeOut() else slideOutHorizontally { it / 2 } + fadeOut(),
            ) {
                val a = app ?: s?.apps?.firstOrNull()
                if (a != null && s != null) {
                    val w = if (narrow) maxWidth else (maxWidth * 0.48f).coerceAtLeast(320.dp).coerceAtMost(maxWidth)
                    val h = if (narrow) maxHeight * 0.7f else maxHeight
                    AppPanel(a, s, store, null, Modifier.size(w, h))
                }
            }
        }
    }
}

@Composable
private fun MonthCard(
    month: YearMonth, today: LocalDate, picked: LocalDate?, byDay: Map<Int, List<HubEvent>>, modifier: Modifier,
    onMonth: (YearMonth) -> Unit, onDay: (LocalDate) -> Unit,
) {
    val title = month.month.getDisplayName(TextStyle.FULL, Locale.UK) + " " + month.year
    VCard(title, modifier.testTag("calendar_month"), trailing = {
        StepButton(G.BACK, "Previous month") { onMonth(month.minusMonths(1)) }
        Gap(w = Grid.s)
        StepButton(G.NEXT, "Next month") { onMonth(month.plusMonths(1)) }
    }) {
        Row(Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                VText(it, Modifier.weight(1f), size = Type.caption, color = V.Muted, align = TextAlign.Center, font = SansMedium)
            }
        }
        Gap(h = Grid.s)
        HubCalendar.cells(month).chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { d ->
                    Box(Modifier.weight(1f).aspectRatio(1f, matchHeightConstraintsFirst = false).heightIn(max = 64.dp).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (d != null) {
                            val date = month.atDay(d)
                            val evs = byDay[d].orEmpty()
                            DayCell(d, evs.map { HubCalendar.colour(it, today) }.distinct().take(3), date == today, date == picked) { onDay(date) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(d: Int, dots: List<Color>, isToday: Boolean, isPicked: Boolean, onTap: () -> Unit) {
    val bg = when { isPicked -> V.Teal.copy(alpha = 0.22f); else -> Color.Transparent }
    Box(
        Modifier.fillMaxSize().background(bg, OctBtn)
            .then(if (isToday) Modifier.border(1.5.dp, V.Teal, OctBtn) else Modifier)
            .tap(onTap).semantics { contentDescription = "Day $d" + if (dots.isNotEmpty()) ", has events" else "" },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            VText("$d", size = Type.body, color = if (isToday || isPicked) V.White else if (dots.isEmpty()) V.Muted else V.Text, font = SansMedium, maxLines = 1)
            Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                if (dots.isEmpty()) Box(Modifier.size(5.dp))
                dots.forEach { c -> Box(Modifier.size(5.dp).background(c, CircleShape)) }
            }
        }
    }
}

@Composable
private fun StepButton(g: G, label: String, onTap: () -> Unit) {
    Box(Modifier.size(34.dp).pane(OctBtn).tap(onTap).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Glyph(g, V.Text, Modifier.size(16.dp))
    }
}

@Composable
private fun Agenda(events: List<HubEvent>, s: HubSummary, today: LocalDate, picked: LocalDate?, modifier: Modifier, onOpen: (HubEvent) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Grid.l)) {
        if (picked != null) {
            val onDay = events.filter { HubCalendar.day(it.at) == picked }.sortedBy { it.at }
            VCard(HubCalendar.date(picked.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), today), Modifier.fillMaxWidth().testTag("calendar_day")) {
                if (onDay.isEmpty()) VText("\u2014", size = Type.body, color = V.Muted)
                onDay.forEach { EventRow(it, s, today, onOpen) }
            }
        }
        val up = HubCalendar.upcoming(events, today)
        val past = HubCalendar.past(events, today)
        if (up.isNotEmpty()) VCard("Upcoming", Modifier.fillMaxWidth().testTag("calendar_upcoming"), trailing = { Count(up.size) }) {
            up.forEach { EventRow(it, s, today, onOpen) }
        }
        if (past.isNotEmpty()) VCard("Past", Modifier.fillMaxWidth().testTag("calendar_past"), dot = V.Muted, trailing = { Count(past.size) }) {
            past.forEach { EventRow(it, s, today, onOpen) }
        }
    }
}

@Composable
private fun Count(n: Int) = VText("$n", size = Type.caption, color = V.Muted, font = SansMedium)

@Composable
private fun EventRow(e: HubEvent, s: HubSummary, today: LocalDate, onOpen: (HubEvent) -> Unit) {
    val app = s.app(e.appId)
    val c = HubCalendar.colour(e, today)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).then(if (app != null) Modifier.tap { onOpen(e) } else Modifier)
            .padding(vertical = Grid.xs).testTag("event_${e.kind}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            if (app != null && app.kind == HubKind.APP) AppIcon(app, 36.dp)
            else Box(Modifier.size(36.dp).background(c.copy(alpha = 0.14f), OctS), contentAlignment = Alignment.Center) { Glyph(HubCalendar.glyph(e.kind), c, Modifier.size(18.dp)) }
        }
        Gap(w = Grid.m)
        Column(Modifier.weight(1f)) {
            val who = app?.let { shortName(it.name) }
            VText(if (who != null) "$who \u00b7 ${e.title}" else e.title, size = Type.body, color = V.Text, maxLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(c, CircleShape))
                Gap(w = Grid.xs)
                VText(
                    (HubCalendar.label(e.kind).uppercase() + if (e.detail.isNotBlank()) "  \u00b7  ${e.detail}" else ""),
                    size = Type.caption, color = V.Muted, maxLines = 2, spacing = 0.6.sp,
                )
            }
        }
        Gap(w = Grid.s)
        VText((if (e.estimated) "~" else "") + HubCalendar.date(e.at, today), size = Type.label, color = if (c == V.Coral) V.Coral else V.Text, font = SansMedium, maxLines = 1, align = TextAlign.End)
    }
}
