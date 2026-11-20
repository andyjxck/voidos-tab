package com.andysocial.voidos.hub

import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.remember
import com.andysocial.voidos.ui.Grid
import com.andysocial.voidos.ui.Radius
import com.andysocial.voidos.ui.Glyph
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andysocial.voidos.Store
import com.andysocial.voidos.ui.FitText
import com.andysocial.voidos.ui.G
import com.andysocial.voidos.ui.Gap
import com.andysocial.voidos.ui.Label
import com.andysocial.voidos.ui.LocalPortrait
import com.andysocial.voidos.ui.Module
import com.andysocial.voidos.ui.NotesNav
import com.andysocial.voidos.ui.Oct
import com.andysocial.voidos.ui.PageTitle
import com.andysocial.voidos.ui.RoundIcon
import com.andysocial.voidos.ui.SansLight
import com.andysocial.voidos.ui.SansMedium
import com.andysocial.voidos.ui.Type
import com.andysocial.voidos.ui.V
import com.andysocial.voidos.ui.VButton
import com.andysocial.voidos.ui.VText
import com.andysocial.voidos.ui.glass
import com.andysocial.voidos.ui.honeyLayout
import com.andysocial.voidos.ui.pane
import kotlin.math.roundToInt

/** Which app's panel is open on the status board (null: none). Set from the drawers too. */
object StatusNav {
    val open = mutableStateOf<String?>(null)
    /** 2.0.1: the app a long-press asked to hide. */
    val hideAsk = mutableStateOf<String?>(null)
}

/**
 * 2.0's status board, where the apps honeycomb was: each of the user's apps and sites as a cell
 * with the old tick ring lit in its status (teal fine, yellow needs a look, coral errors). Tap
 * one and, in landscape, the cluster slides to the left while its panel opens beside it; in
 * portrait the panel comes up from the bottom and the cluster moves up out of its way.
 * [onGo] switches page (the Notes button); null inside a half of the book.
 */
@Composable
fun StatusBoard(store: Store, onGo: ((Module) -> Unit)?) {
    val portrait = LocalPortrait.current
    val s = HubRepo.summary
    val openId by StatusNav.open
    val app = s?.app(openId)
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().testTag("status_board")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PageTitle("Status", portrait)
                if (s != null) VText(freshness(s), size = Type.caption, color = V.Muted, maxLines = 2)
            }
            if (s != null) StatusChip(s.overall, text = when (s.overall) {
                HubStatus.FINE -> "All fine"
                HubStatus.ATTENTION -> "${s.apps.count { it.glow == HubStatus.ATTENTION }} need a look"
                HubStatus.ERRORS -> "${s.apps.count { it.glow == HubStatus.ERRORS }} with errors"
                HubStatus.UNKNOWN -> "No data"
            })
        }
        Gap(h = 8.dp)
        if (s == null || s.apps.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { NotConnected("NO DATA") }
            return@Column
        }
        // clipped to its own box: the rings can never ride up over the title
        BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
            val d = LocalDensity.current
            val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
            val panelOpen = app != null
            // landscape: the comb gets the left part, the panel the right; portrait: comb top, panel bottom
            val panelW = if (portrait) w else (w * 0.46f).coerceIn(with(d) { 340.dp.toPx() }, with(d) { 520.dp.toPx() })
            val panelH = if (portrait) h * 0.58f else h
            val combW = if (panelOpen && !portrait) w - panelW - with(d) { 16.dp.toPx() } else w
            val combH = if (panelOpen && portrait) h - panelH - with(d) { 12.dp.toPx() } else h
            val cw by animateFloatAsState(combW, tween(320), label = "combW")
            val ch by animateFloatAsState(combH, tween(320), label = "combH")
            // rings shrink to 52dp before the cluster would spill (half a book with large text)
            val layout = honeyLayout(s.apps.size, cw, ch, with(d) { 52.dp.toPx() }, with(d) { 168.dp.toPx() })
            val cell = layout.cell
            s.apps.forEachIndexed { i, a ->
                val o = layout.cells.getOrNull(i) ?: return@forEachIndexed
                val cx = cw / 2f + o.x * cell; val cy = ch / 2f + o.y * cell
                StatusCell(
                    a, chosen = a.id == openId, sizePx = cell * 0.94f,
                    modifier = Modifier.offset { IntOffset((cx - cell * 0.47f).roundToInt(), (cy - cell * 0.47f).roundToInt()) },
                ) { StatusNav.open.value = if (openId == a.id) null else a.id }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = panelOpen,
                modifier = Modifier.align(if (portrait) Alignment.BottomCenter else Alignment.CenterEnd),
                enter = if (portrait) slideInVertically { it } + fadeIn() else slideInHorizontally { it / 2 } + fadeIn(),
                exit = if (portrait) slideOutVertically { it } + fadeOut() else slideOutHorizontally { it / 2 } + fadeOut(),
            ) {
                val shown = app ?: s.apps.first()
                AppPanel(
                    shown, s, store, onGo,
                    Modifier.size(with(d) { panelW.toDp() }, with(d) { panelH.toDp() }),
                )
            }
        }
    }
    HideAsk(s)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun StatusCell(a: HubApp, chosen: Boolean, sizePx: Float, modifier: Modifier, onTap: () -> Unit) {
    val d = LocalDensity.current
    val size = with(d) { sizePx.toDp() }
    val col = HubLook.colour(a.glow)
    Box(
        modifier.size(size).clip(CircleShape)
            .combinedClickable(onClick = onTap, onLongClick = { StatusNav.hideAsk.value = a.id })
            .semantics { contentDescription = "${a.name}, ${a.glow.label}" }.testTag("cell_${a.id}"),
        contentAlignment = Alignment.Center,
    ) {
        // the old face's 60-tick ring is the status: teal fine, amber a look, coral errors
        TickRing(col, Modifier.fillMaxSize(), glow = chosen || a.glow == HubStatus.ERRORS)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.size(size * 0.70f), verticalArrangement = Arrangement.Center) {
            if (a.kind == HubKind.APP) AppIcon(a, size * 0.40f)
            else Box(Modifier.size(size * 0.40f).glass(Radius.Card, 0.75f), contentAlignment = Alignment.Center) { Glyph(G.WEB, col, Modifier.size(size * 0.2f)) }
            Gap(h = (size.value * 0.04f).coerceIn(3f, 8f).dp)
            FitText(shortName(a.name), (size.value * 0.078f).coerceIn(9f, 12.5f).sp, Modifier.fillMaxWidth(), color = if (chosen) V.White else V.Text, font = SansMedium,
                align = Alignment.Center, min = 7.sp, lines = 1)
        }
    }
}

/** The board's label: the name before its store subtitle ("Fit Void: Gym Workout Tracker" reads "Fit Void"). */
fun shortName(n: String): String = n.substringBefore(':').substringBefore(" - ").substringBefore(" \u2013 ").trim().ifEmpty { n }

/** Long-press an app: one small chamfered card, Hide or keep it. Hidden apps come back from Settings › Hidden apps. */
@Composable
fun HideAsk(s: HubSummary?) {
    val id by StatusNav.hideAsk
    val a = s?.app(id) ?: return
    Box(Modifier.fillMaxSize().background(V.Bg.copy(alpha = 0.55f)).clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { StatusNav.hideAsk.value = null }, contentAlignment = Alignment.Center) {
        Column(Modifier.width(300.dp).pane(Oct).padding(Grid.xl).testTag("hide_ask"), horizontalAlignment = Alignment.CenterHorizontally) {
            if (a.kind == HubKind.APP) AppIcon(a, 64.dp)
            Gap(h = Grid.m)
            VText(shortName(a.name), size = Type.title, font = SansMedium, color = V.White, maxLines = 1)
            Gap(h = Grid.xl)
            Row(horizontalArrangement = Arrangement.spacedBy(Grid.s)) {
                VButton("Cancel", Modifier.weight(1f)) { StatusNav.hideAsk.value = null }
                VButton("Hide", Modifier.weight(1f), accent = V.Coral) {
                    HubRepo.hide(a.id); if (StatusNav.open.value == a.id) StatusNav.open.value = null; StatusNav.hideAsk.value = null
                }
            }
        }
    }
}

/** An app's little window: status, errors, review state, the latest review, today's numbers, quick buttons. */
@Composable
fun AppPanel(a: HubApp, s: HubSummary, store: Store, onGo: ((Module) -> Unit)?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Column(modifier.pane(Oct).padding(20.dp).testTag("app_panel")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (a.kind == HubKind.APP) { AppIcon(a, 52.dp); Gap(w = Grid.m) }
            Column(Modifier.weight(1f)) {
                FitText(shortName(a.name), Type.title2, Modifier.fillMaxWidth(), color = V.White, font = SansMedium)
                StatusChip(a.glow, text = a.statusNote.ifBlank { a.glow.label })
            }
            RoundIcon(G.CLOSE, "Close panel", size = 38.dp) { StatusNav.open.value = null }
        }
        Gap(h = 12.dp)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            // today's numbers, two to a row, the old face's label-over-number
            val figs = panelFigures(a)
            if (figs.isEmpty()) NotConnected("NO DATA", Modifier.fillMaxWidth(), 28.dp)
            figs.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { (m, v) ->
                        Figure(m.label.uppercase(), Hub.fmt(v.today, m, v.currency), Modifier.weight(1f).padding(bottom = 10.dp),
                            sub = v.week?.let { "7 days: " + Hub.fmt(it, m, v.currency) } ?: "",
                            accent = if (m == Metric.CRASHES && (v.today ?: 0.0) > 0) V.Coral else V.White)
                    }
                    if (row.size == 1) Box(Modifier.weight(1f))
                }
            }
            a.review?.let { r ->
                Label("App Store review")
                Gap(h = 4.dp)
                VText(listOf(r.version.takeIf { it.isNotBlank() }?.let { "Version $it" }, r.label).filterNotNull().joinToString(" · "),
                    size = Type.body, color = if (r.rejected) V.Coral else if (r.live) V.Teal else V.Yellow, font = SansMedium, maxLines = 1)
                Gap(h = 12.dp)
            }
            if (a.crashes.isNotEmpty()) {
                Label("Errors and crashes")
                Gap(h = 4.dp)
                a.crashes.take(3).forEach { c ->
                    VText(c.title, size = Type.body, color = V.Text, maxLines = 1)
                    VText("${c.events} events · ${c.users} users" + (if (c.version.isNotBlank()) " · ${c.version}" else ""), size = Type.caption, color = V.Muted, maxLines = 1)
                    Gap(h = 6.dp)
                }
                Gap(h = 6.dp)
            }
            a.latestReview?.let { r ->
                Label("Latest review")
                Gap(h = 4.dp)
                VText("★".repeat(r.rating.coerceIn(0, 5)) + "☆".repeat((5 - r.rating).coerceIn(0, 5)) + "  " + r.title, size = Type.body, font = SansMedium, color = V.Text, maxLines = 1)
                VText(r.body, size = Type.body, color = V.Muted, maxLines = 3)
                Gap(h = 12.dp)
            }
        }
        Gap(h = 8.dp)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        // in half a book the panel is narrow: shorter labels so all three buttons still read whole
        val narrow = maxWidth < 380.dp
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val n = store.notes.count { it.app == a.id }
            VButton(if (n > 0 && !narrow) "Notes ($n)" else "Notes", Modifier.weight(1f)) { NotesNav.app.value = a.id; onGo?.invoke(Module.NOTES) }
            if (a.kind == HubKind.APP) {
                VButton(if (narrow) "Store" else "App Store", Modifier.weight(1f)) { DevApps.openUrl(ctx, store, a.consoleUrl.ifBlank { "https://appstoreconnect.apple.com/apps" }) }
                if (a.adsUrl.isNotBlank() || a.metrics.containsKey(Metric.ADS))
                    VButton("AdMob", Modifier.weight(1f)) { DevApps.openUrl(ctx, store, a.adsUrl.ifBlank { "https://apps.admob.com" }) }
            } else {
                if (a.url.isNotBlank()) VButton("Open site", Modifier.weight(1f)) { DevApps.openUrl(ctx, store, a.url) }
                if (a.consoleUrl.isNotBlank()) VButton("Console", Modifier.weight(1f)) { DevApps.openUrl(ctx, store, a.consoleUrl) }
            }
        }
        }
    }
}

/** The numbers an app's panel shows, in a sensible order, only the ones the hub has. */
fun panelFigures(a: HubApp): List<Pair<Metric, MetricValue>> {
    val order = if (a.kind == HubKind.SITE) listOf(Metric.UPTIME, Metric.USERS, Metric.BOUNCES)
        else listOf(Metric.DOWNLOADS, Metric.REVENUE, Metric.USERS, Metric.ADS, Metric.CRASHES, Metric.RATING)
    return order.mapNotNull { m -> a.metrics[m]?.let { m to it } }
}
