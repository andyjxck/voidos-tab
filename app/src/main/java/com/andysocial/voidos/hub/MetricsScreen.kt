package com.andysocial.voidos.hub

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.andysocial.voidos.Store
import com.andysocial.voidos.ui.Chip
import com.andysocial.voidos.ui.FitText
import com.andysocial.voidos.ui.Gap
import com.andysocial.voidos.ui.Label
import com.andysocial.voidos.ui.LocalPortrait
import com.andysocial.voidos.ui.Oct
import com.andysocial.voidos.ui.PageTitle
import com.andysocial.voidos.ui.SansLight
import com.andysocial.voidos.ui.SansMedium
import com.andysocial.voidos.ui.Type
import com.andysocial.voidos.ui.V
import com.andysocial.voidos.ui.VButton
import com.andysocial.voidos.ui.VField
import com.andysocial.voidos.ui.VText
import com.andysocial.voidos.ui.pane
import com.andysocial.voidos.ui.tap

/**
 * 2.0's metric view: the app chosen in the right drawer × the metric chosen in the left one.
 * "All apps" shows the metric across everything, with each app's share. Revenue adds the payout
 * card, Reviews the inbox with replying, and the costs sit under All apps › Revenue (phase 4).
 */
@Composable
fun MetricsScreen(store: Store) {
    val portrait = LocalPortrait.current
    val s = HubRepo.summary
    val metric = Metric.of(store.hubMetric) ?: Metric.REVENUE
    val appId = store.hubApp.takeIf { it == Hub.ALL || s?.app(it) != null } ?: Hub.ALL
    val appName = if (appId == Hub.ALL) "All apps" else s?.app(appId)?.name ?: "All apps"
    Column(Modifier.fillMaxSize().testTag("metrics")) {
        PageTitle(metric.label, portrait)
        VText(listOf(appName, freshness(s)).filter { it.isNotBlank() }.joinToString(" · "), size = Type.caption, color = V.Muted, maxLines = 1)
        Gap(h = 10.dp)
        // the drawers choose, but the chips work too, for a book half or no drawers
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Metric.entries.forEach { m -> Chip(m.label, m == metric, V.Teal, Modifier.padding(end = 6.dp), glyph = HubDrawers.glyph(m)) { store.setHub(metric = m.name) } }
        }
        Gap(h = 6.dp)
        if (s == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { NotConnected("Nothing linked yet") }; return@Column }
        if (s.apps.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { NotConnected("No apps linked yet") }; return@Column }
        // a metric no linked source provides: say which source would, rather than show zeros
        if (Hub.total(s, metric) == null && !(metric == Metric.REVENUE && (s.payout != null || s.costs.isNotEmpty())) && !(metric == Metric.REVIEWS && s.reviews.isNotEmpty())) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { NotConnected("No ${metric.label.lowercase()} yet", how = "Link ${sourceFor(metric.label)} in Settings \u203a Hub") }
            return@Column
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            (listOf(Hub.ALL to "All apps") + s.apps.map { it.id to it.name }).forEach { (id, name) ->
                Chip(name, id == appId, V.Teal, Modifier.padding(end = 6.dp)) { store.setHub(app = id) }
            }
        }
        Gap(h = 12.dp)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            val v = Hub.value(s, appId, metric)
            val wrap: @Composable (@Composable (Modifier) -> Unit, @Composable (Modifier) -> Unit) -> Unit = { a, b ->
                if (portrait) { a(Modifier.fillMaxWidth()); Gap(h = 12.dp); b(Modifier.fillMaxWidth()) }
                else Row(Modifier.fillMaxWidth()) { a(Modifier.weight(1f)); Gap(w = 12.dp); b(Modifier.weight(1f)) }
            }
            wrap({ m -> BigNumber(metric, v, m) }, { m -> if (appId == Hub.ALL) Ranking(s, metric, m) else Trend(metric, v, m) })
            Gap(h = 12.dp)
            when {
                metric == Metric.REVENUE && appId == Hub.ALL -> wrap({ m -> PayoutCard(s.payout, m) }, { m -> CostsCard(s.costs, m) })
                metric == Metric.REVIEWS || metric == Metric.RATING -> ReviewsInbox(s, appId, store, Modifier.fillMaxWidth())
                metric == Metric.CRASHES -> CrashList(s, appId, Modifier.fillMaxWidth())
                metric == Metric.UPTIME -> SitesCard(s, Modifier.fillMaxWidth())
                else -> {}
            }
            if (appId == Hub.ALL && metric == Metric.REVENUE && s.repos.isNotEmpty()) { Gap(h = 12.dp); ReposCard(s.repos, Modifier.fillMaxWidth()) }
            Gap(h = 24.dp)
        }
    }
}

@Composable
private fun BigNumber(m: Metric, v: MetricValue?, modifier: Modifier) {
    Box(modifier.height(200.dp).pane(Oct)) {
        // the old face's octagon, drawn thin inside the glass
        Canvas(Modifier.fillMaxSize().padding(10.dp)) { octagon(V.Teal.copy(alpha = 0.35f)) }
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 22.dp), verticalArrangement = Arrangement.Center) {
            Label("Today")
            if (v == null) VText("Nothing for this app yet", size = Type.title, color = V.Muted, maxLines = 2)
            else {
                FitText(Hub.fmt(v.today, m, v.currency), 64.sp(), Modifier.fillMaxWidth().height(76.dp), color = V.White, font = SansLight)
                VText("Last 7 days: " + Hub.fmt(v.week, m, v.currency), size = Type.body, color = V.Muted, maxLines = 1)
            }
        }
    }
}

private fun Int.sp() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

@Composable
private fun Trend(m: Metric, v: MetricValue?, modifier: Modifier) {
    Column(modifier.height(200.dp).pane(Oct).padding(20.dp)) {
        Label("Last ${v?.series?.size ?: 0} days")
        Gap(h = 10.dp)
        if (v == null || v.series.isEmpty()) VText("No history yet", size = Type.body, color = V.Muted)
        else {
            TickBars(v.series, V.Teal, Modifier.fillMaxWidth().weight(1f))
            Gap(h = 6.dp)
            Row(Modifier.fillMaxWidth()) {
                VText("low " + Hub.fmt(v.series.min(), m, v.currency), size = Type.caption, color = V.Muted, modifier = Modifier.weight(1f), maxLines = 1)
                VText("high " + Hub.fmt(v.series.max(), m, v.currency), size = Type.caption, color = V.Muted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Ranking(s: HubSummary, m: Metric, modifier: Modifier) {
    val rank = Hub.ranking(s, m)
    Column(modifier.height(200.dp).pane(Oct).padding(20.dp)) {
        Label("By app")
        Gap(h = 8.dp)
        if (rank.isEmpty()) VText("No app has this yet", size = Type.body, color = V.Muted)
        val top = rank.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1.0
        Column(Modifier.verticalScroll(rememberScrollState())) {
            rank.forEach { (a, x) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    VText(a.name, size = Type.label, color = V.Text, modifier = Modifier.width(118.dp), maxLines = 1)
                    Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(V.Tick.copy(alpha = 0.35f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth((x / top).toFloat().coerceIn(0.02f, 1f)).clip(RoundedCornerShape(4.dp)).background(HubLook.colour(a.glow).takeIf { it != V.Tick } ?: V.Teal))
                    }
                    Gap(w = 8.dp)
                    VText(Hub.fmt(x, m, a.metrics[m]?.currency ?: "GBP"), size = Type.label, color = V.Muted, modifier = Modifier.width(76.dp), maxLines = 1,
                        align = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
        }
    }
}

@Composable
private fun PayoutCard(p: Payout?, modifier: Modifier) {
    Column(modifier.pane(Oct).padding(20.dp)) {
        Label("App Store payout")
        Gap(h = 6.dp)
        if (p == null || p.nextDate.isBlank()) { VText("Payouts not connected · set up in Settings › Hub", size = Type.body, color = V.Muted, maxLines = 2); return@Column }
        Figure("Next payment ${p.nextDate}", p.estimate?.let { "about " + Hub.money(it, p.currency) } ?: "–", sub = p.lastAmount?.let { "Last: ${Hub.money(it, p.currency)} on ${p.lastDate}" } ?: "")
    }
}

@Composable
private fun CostsCard(costs: List<CostLine>, modifier: Modifier) {
    Column(modifier.pane(Oct).padding(20.dp)) {
        Label("Running costs")
        Gap(h = 6.dp)
        if (costs.isEmpty()) VText("Costs not connected · set up in Settings › Hub", size = Type.body, color = V.Muted, maxLines = 2)
        costs.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    VText(c.label, size = Type.body, color = V.Text, maxLines = 2)
                    VText(listOf(c.note, c.due).filter { it.isNotBlank() }.joinToString(" "), size = Type.caption, color = V.Muted, maxLines = 1)
                }
                VText(c.amount?.let { if (c.currency == "credits") Hub.count(it) else Hub.money(it, c.currency) } ?: "–", size = Type.headline,
                    color = if (c.warn) V.Coral else V.White, font = SansMedium, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ReposCard(repos: List<RepoState>, modifier: Modifier) {
    Column(modifier.pane(Oct).padding(20.dp)) {
        Label("GitHub")
        Gap(h = 6.dp)
        repos.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusChip(when (r.build) { "success" -> HubStatus.FINE; "failure" -> HubStatus.ERRORS; "" -> HubStatus.UNKNOWN; else -> HubStatus.ATTENTION }, text = "")
                Column(Modifier.weight(1f)) {
                    VText(r.repo.substringAfter('/'), size = Type.body, color = V.Text, maxLines = 1)
                    VText(r.message, size = Type.caption, color = V.Muted, maxLines = 1)
                }
                VText(Hub.ago(r.at, System.currentTimeMillis()).removePrefix("updated "), size = Type.caption, color = V.Muted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SitesCard(s: HubSummary, modifier: Modifier) {
    Column(modifier.pane(Oct).padding(20.dp)) {
        Label("Sites")
        Gap(h = 6.dp)
        if (s.sites.isEmpty()) VText("Uptime checks not connected · set up in Settings › Hub", size = Type.body, color = V.Muted, maxLines = 2)
        s.sites.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusChip(siteStatus(c), text = if (c.up) "Up" else "Down")
                Gap(w = 10.dp)
                VText(c.host, size = Type.body, color = V.Text, modifier = Modifier.weight(1f), maxLines = 1)
                VText(listOfNotNull(c.ms?.let { "$it ms" }, c.sslDays?.let { "SSL ${it}d" }, c.domainDays?.let { "domain ${it}d" }).joinToString(" · "),
                    size = Type.caption, color = if (siteStatus(c) == HubStatus.FINE) V.Muted else V.Yellow, maxLines = 1)
            }
        }
    }
}

/** A site is in trouble when it's down, and needs a look when its certificate or domain runs out within 14 days. */
fun siteStatus(c: SiteCheck): HubStatus = when {
    !c.up -> HubStatus.ERRORS
    (c.sslDays ?: 99) < 14 || (c.domainDays ?: 99) < 14 -> HubStatus.ATTENTION
    else -> HubStatus.FINE
}

@Composable
private fun CrashList(s: HubSummary, appId: String, modifier: Modifier) {
    val list = s.apps.filter { appId == Hub.ALL || it.id == appId }.flatMap { a -> a.crashes.map { a to it } }
    Column(modifier.pane(Oct).padding(20.dp)) {
        Label("Crash groups")
        Gap(h = 6.dp)
        if (list.isEmpty()) VText(if (s.sources.any { it.key == "crashlytics" }) "No crashes. Nice." else "Crashlytics not connected · set up in Settings › Hub", size = Type.body, color = V.Muted, maxLines = 2)
        list.forEach { (a, c) ->
            Column(Modifier.padding(vertical = 4.dp)) {
                VText(c.title, size = Type.body, color = if (c.fresh) V.Coral else V.Text, maxLines = 1)
                VText("${a.name} · ${c.events} events · ${c.users} users" + (if (c.version.isNotBlank()) " · ${c.version}" else ""), size = Type.caption, color = V.Muted, maxLines = 1)
            }
        }
    }
}

/** Phase 2: the reviews inbox. Reply from the tablet; the server posts it with its own App Store key. */
@Composable
private fun ReviewsInbox(s: HubSummary, appId: String, store: Store, modifier: Modifier) {
    val ctx = LocalContext.current
    val list = s.reviews.filter { appId == Hub.ALL || it.appId == appId }.sortedByDescending { it.at }
    var replying by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    Column(modifier.pane(Oct).padding(20.dp).testTag("reviews_inbox")) {
        Label("Reviews")
        Gap(h = 6.dp)
        if (list.isEmpty()) VText("No reviews yet", size = Type.body, color = V.Muted)
        list.forEach { r ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VText("★".repeat(r.rating.coerceIn(0, 5)) + "☆".repeat((5 - r.rating).coerceIn(0, 5)), size = Type.body, color = if (r.rating <= 2) V.Coral else V.Yellow, maxLines = 1)
                    Gap(w = 8.dp)
                    VText(r.title, size = Type.body, font = SansMedium, color = V.Text, modifier = Modifier.weight(1f), maxLines = 1)
                    VText(s.app(r.appId)?.name ?: "", size = Type.caption, color = V.Muted, maxLines = 1)
                }
                VText(r.body, size = Type.body, color = V.Muted, maxLines = 4)
                VText("${r.author}" + (if (r.territory.isNotBlank()) " · ${r.territory}" else ""), size = Type.caption, color = V.Tick, maxLines = 1)
                if (r.reply != null) VText("You replied: ${r.reply}", size = Type.caption, color = V.Teal, maxLines = 2)
                else if (replying == r.id) {
                    Gap(h = 6.dp)
                    VField(text, { text = it }, Modifier.fillMaxWidth(), placeholder = "Your reply", singleLine = false)
                    Gap(h = 6.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VButton("Send reply", filled = true, enabled = text.isNotBlank()) {
                            HubRepo.reply(ctx, r, text.trim()) { err -> note = err; if (err == null) { replying = null; text = "" } }
                        }
                        VButton("Cancel") { replying = null; text = "" }
                    }
                } else VText("Reply", size = Type.label, color = V.Teal, font = SansMedium,
                    modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).tap { replying = r.id; text = "" }.padding(vertical = 4.dp))
            }
        }
        note?.let { VText(it, size = Type.caption, color = V.Coral) }
    }
}
