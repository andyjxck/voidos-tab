package com.andysocial.voidos.hub

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/*
 * VoidOS 2.0, the Andysocial Void Hub: the data the tablet shows.
 *
 * The tablet never talks to App Store Connect, AdMob, Firebase, Brevo and so on itself, and
 * holds none of their keys. A small cloud function (functions/ in the repo) pulls from every
 * source, works out one summary and writes it to Firestore as a single JSON string; the tablet
 * reads that one document. This file is that summary's shape, parsed with org.json, and the
 * pure rules the screens use (status, totals, "updated X min ago", when to refresh). Pure, so the
 * unit tests check it without a device.
 */

/** What a hub entry is: one of the user's store apps, or a website added by hand. */
enum class HubKind { APP, SITE }

/** fine / attention / errors, the glow round each app on the status board. */
enum class HubStatus(val label: String) {
    FINE("Fine"), ATTENTION("Needs a look"), ERRORS("Errors"), UNKNOWN("No data");

    companion object {
        fun of(s: String?): HubStatus = when (s?.lowercase(Locale.ROOT)) {
            "fine", "ok" -> FINE
            "attention", "warn", "warning" -> ATTENTION
            "errors", "error", "down" -> ERRORS
            else -> UNKNOWN
        }
        /** The worst of several: errors beat attention beat fine; no data only when there's nothing else. */
        fun worst(all: Collection<HubStatus>): HubStatus = when {
            ERRORS in all -> ERRORS
            ATTENTION in all -> ATTENTION
            FINE in all -> FINE
            else -> UNKNOWN
        }
    }
}

/** How a metric's numbers read. */
enum class MetricUnit { COUNT, MONEY, PERCENT, RATING, MILLIS }

/**
 * The left drawer: one row per metric. [key] is the field name in the summary JSON.
 * [sum] says how "All apps" combines the apps: added up, or averaged (ratings, uptime).
 */
enum class Metric(val key: String, val label: String, val unit: MetricUnit, val sum: Boolean = true, val sitesToo: Boolean = false) {
    REVENUE("revenue", "Revenue", MetricUnit.MONEY),
    DOWNLOADS("downloads", "Downloads", MetricUnit.COUNT),
    USERS("users", "Active users", MetricUnit.COUNT, sitesToo = true),
    ADS("ads", "Ad earnings", MetricUnit.MONEY),
    IMPRESSIONS("impressions", "Impressions", MetricUnit.COUNT),
    ECPM("ecpm", "eCPM", MetricUnit.MONEY, sum = false),
    CRASHES("crashes", "Crashes", MetricUnit.COUNT),
    REVIEWS("reviews", "Reviews", MetricUnit.COUNT),
    RATING("rating", "Rating", MetricUnit.RATING, sum = false),
    UPTIME("uptime", "Uptime", MetricUnit.PERCENT, sum = false, sitesToo = true),
    BOUNCES("bounces", "Email bounces", MetricUnit.COUNT, sitesToo = true),
    ;

    companion object {
        fun of(key: String?): Metric? = entries.firstOrNull { it.key == key || it.name == key }
    }
}

/** One metric for one app: today, the last 7 days, and a short daily series (oldest first) for the sparkline. */
data class MetricValue(
    val today: Double? = null,
    val week: Double? = null,
    val series: List<Double> = emptyList(),
    val currency: String = "GBP",
)

/** Where the app is in App Store review (phase 2), e.g. "WAITING_FOR_REVIEW", "READY_FOR_SALE". */
data class ReviewState(val state: String, val version: String = "", val since: Long = 0L) {
    val label: String get() = reviewLabel(state)
    val live: Boolean get() = state == "READY_FOR_SALE" || state == "READY_FOR_DISTRIBUTION"
    val waiting: Boolean get() = state in setOf("WAITING_FOR_REVIEW", "IN_REVIEW", "PENDING_DEVELOPER_RELEASE", "PROCESSING_FOR_APP_STORE", "PENDING_APPLE_RELEASE")
    val rejected: Boolean get() = state in setOf("REJECTED", "METADATA_REJECTED", "INVALID_BINARY", "DEVELOPER_REJECTED")
}

fun reviewLabel(state: String): String = when (state) {
    "READY_FOR_SALE", "READY_FOR_DISTRIBUTION" -> "Live"
    "WAITING_FOR_REVIEW" -> "Waiting for review"
    "IN_REVIEW" -> "In review"
    "PENDING_DEVELOPER_RELEASE" -> "Approved, waiting for you"
    "PENDING_APPLE_RELEASE" -> "Approved"
    "PROCESSING_FOR_APP_STORE" -> "Processing"
    "PREPARE_FOR_SUBMISSION" -> "Preparing"
    "REJECTED" -> "Rejected"
    "METADATA_REJECTED" -> "Metadata rejected"
    "INVALID_BINARY" -> "Invalid binary"
    "DEVELOPER_REJECTED" -> "Pulled by you"
    "REMOVED_FROM_SALE", "DEVELOPER_REMOVED_FROM_SALE" -> "Off sale"
    "" -> ""
    else -> state.lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
}

/** A customer review from the store (phase 2's inbox). */
data class StoreReview(
    val id: String,
    val appId: String,
    val rating: Int,
    val title: String,
    val body: String,
    val author: String,
    val territory: String = "",
    val at: Long = 0L,
    val reply: String? = null,
)

/** One crash or error group (phase 3, Crashlytics through BigQuery). */
data class CrashIssue(val title: String, val subtitle: String = "", val events: Int = 0, val users: Int = 0, val version: String = "", val fresh: Boolean = false)

/** One entry on the status board and in the right drawer. */
data class HubApp(
    val id: String,
    val name: String,
    val kind: HubKind = HubKind.APP,
    val platform: String = "ios",
    val icon: String = "",
    val url: String = "",
    val consoleUrl: String = "",
    val adsUrl: String = "",
    val status: HubStatus = HubStatus.UNKNOWN,
    val statusNote: String = "",
    val review: ReviewState? = null,
    val latestReview: StoreReview? = null,
    val crashes: List<CrashIssue> = emptyList(),
    val metrics: Map<Metric, MetricValue> = emptyMap(),
) {
    /** Two letters for the tile when there's no icon yet ("Button Panic" -> "BP"). */
    val initials: String get() = name.split(' ', '-', '_').filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

    /**
     * The glow: what the aggregator said, else worked out here from the numbers:
     * a rejected build or a down site is errors, fresh crashes or a build in review is a look.
     */
    val glow: HubStatus get() = if (status != HubStatus.UNKNOWN) status else derive()

    private fun derive(): HubStatus {
        val up = metrics[Metric.UPTIME]?.today
        val crashToday = metrics[Metric.CRASHES]?.today ?: 0.0
        return when {
            review?.rejected == true -> HubStatus.ERRORS
            up != null && up < 99.0 -> HubStatus.ERRORS
            crashToday >= 10 -> HubStatus.ERRORS
            crashToday > 0 || review?.waiting == true -> HubStatus.ATTENTION
            metrics.isEmpty() && review == null -> HubStatus.UNKNOWN
            else -> HubStatus.FINE
        }
    }
}

/** One data source's last pull, for "Not connected" and "couldn't reach X" lines. */
data class SourceState(val key: String, val label: String, val connected: Boolean, val ok: Boolean, val at: Long = 0L, val error: String = "")

/**
 * 2.0.2: Apple's newest Finance Report: the proceeds Apple *reported* for one fiscal month
 * ([month] is "YYYY-MM"). It is not a payment: no API gives Apple's payment amounts or dates.
 */
data class FinanceMonth(val month: String, val proceeds: Double, val currency: String = "GBP", val partial: Boolean = false)

/** One running cost (phase 4): cloud spend, ElevenLabs balance, Apple renewal. */
data class CostLine(val key: String, val label: String, val amount: Double? = null, val currency: String = "GBP", val note: String = "", val due: String = "", val warn: Boolean = false)

/** A GitHub repo's latest commit and build (phase 3). */
data class RepoState(val repo: String, val message: String, val sha: String, val at: Long, val build: String = "", val url: String = "")

/** A site's certificate and domain dates (phase 3). */
data class SiteCheck(val appId: String, val host: String, val up: Boolean, val ms: Long? = null, val sslDays: Int? = null, val domainDays: Int? = null)

/** The whole summary document. */
/**
 * 2.0.2, one dated thing on the hub calendar. [kind] is release, scheduled, pending, phased,
 * review, payout, renewal, ssl, domain, build or cost; [appId] is "" for ones that belong to no app.
 */
data class HubEvent(val id: String, val at: Long, val kind: String, val appId: String, val title: String, val detail: String = "", val estimated: Boolean = false)

data class HubSummary(
    val updatedAt: Long,
    val apps: List<HubApp>,
    val sources: List<SourceState> = emptyList(),
    val finance: FinanceMonth? = null,
    val reviews: List<StoreReview> = emptyList(),
    val costs: List<CostLine> = emptyList(),
    val repos: List<RepoState> = emptyList(),
    val sites: List<SiteCheck> = emptyList(),
    val demo: Boolean = false,
    /** 2.0.2: the hub calendar's dated events, newest first. */
    val events: List<HubEvent> = emptyList(),
) {
    fun app(id: String?): HubApp? = apps.firstOrNull { it.id == id }
    val storeApps: List<HubApp> get() = apps.filter { it.kind == HubKind.APP }
    val overall: HubStatus get() = HubStatus.worst(apps.map { it.glow })
    /** Apps whose build is live now but wasn't in [before]: the standby ring flashes for these. */
    fun wentLive(before: HubSummary?): List<HubApp> = apps.filter { a ->
        a.review?.live == true && before?.app(a.id)?.review?.let { !it.live } == true
    }
}

object Hub {
    /** The summary without the apps hidden on this tablet: their reviews, sites and repos go with them. */
    fun withoutHidden(s: HubSummary, hidden: Set<String>): HubSummary {
        if (hidden.isEmpty() || s.apps.none { it.id in hidden }) return s
        return s.copy(
            apps = s.apps.filter { it.id !in hidden },
            reviews = s.reviews.filter { it.appId !in hidden },
            sites = s.sites.filter { it.appId !in hidden },
            events = s.events.filter { it.appId !in hidden },
        )
    }

    /** Pseudo app id for the "All apps" row at the top of the right drawer. */
    const val ALL = "__all"

    /** Hourly on the server; on the tablet, a refresh when the screen comes on, but never twice in this long. */
    const val MIN_REFRESH_MS = 10 * 60_000L

    fun shouldRefresh(lastRefresh: Long, now: Long): Boolean = lastRefresh <= 0L || now - lastRefresh >= MIN_REFRESH_MS || now < lastRefresh

    /** "updated just now", "updated 12 min ago", "updated 3 h ago", "updated 2 days ago". */
    fun ago(at: Long, now: Long): String {
        if (at <= 0L) return "never updated"
        val m = ((now - at).coerceAtLeast(0) / 60_000L)
        return when {
            m < 1 -> "updated just now"
            m < 60 -> "updated $m min ago"
            m < 48 * 60 -> "updated ${m / 60} h ago"
            else -> "updated ${m / 1440} days ago"
        }
    }

    /** One metric across every app ("All apps"): summed, or averaged for ratings, uptime and eCPM. */
    fun total(s: HubSummary, m: Metric): MetricValue? {
        val vals = s.apps.mapNotNull { it.metrics[m] }
        if (vals.isEmpty()) return null
        fun comb(xs: List<Double>): Double? = if (xs.isEmpty()) null else if (m.sum) xs.sum() else xs.average()
        val len = vals.maxOf { it.series.size }
        val series = (0 until len).map { i ->
            val col = vals.mapNotNull { v -> v.series.getOrNull(i - (len - v.series.size)) }
            comb(col) ?: 0.0
        }
        return MetricValue(comb(vals.mapNotNull { it.today }), comb(vals.mapNotNull { it.week }), series, vals.first().currency)
    }

    /** 2.0.2: the ranges the Metrics page offers, in days. */
    val RANGES = listOf(7, 30, 90, 365)

    fun rangeLabel(days: Int): String = when (days) { 7 -> "7D"; 30 -> "30D"; 90 -> "90D"; 365 -> "1Y"; else -> "${days}D" }

    /**
     * 2.0.2: [v] over the last [days]: the series cut to that many days and [MetricValue.week]
     * as the range's figure (added up, or for ratings, uptime and eCPM the average of the days
     * with data). With a 7-day range this is the server's own week figure.
     */
    fun ranged(v: MetricValue?, m: Metric, days: Int): MetricValue? {
        if (v == null) return null
        if (days == 7 && v.week != null) return v.copy(series = v.series.takeLast(7))
        val cut = v.series.takeLast(days)
        if (cut.isEmpty()) return v.copy(series = cut)
        val fig = if (m.sum) cut.sum() else cut.filter { it > 0.0 }.takeIf { it.isNotEmpty() }?.average()
        return v.copy(week = fig, series = cut)
    }

    /** Each app's figure for the range, biggest first. */
    fun ranking(s: HubSummary, m: Metric, days: Int): List<Pair<HubApp, Double>> =
        s.apps.mapNotNull { a -> ranged(a.metrics[m], m, days)?.week?.let { a to it } }.sortedByDescending { it.second }

    /** What the metric view shows for one app, or for "All apps". */
    fun value(s: HubSummary, appId: String, m: Metric): MetricValue? =
        if (appId == ALL) total(s, m) else s.app(appId)?.metrics?.get(m)

    /** Each app's share of one metric, biggest first, for the "All apps" bars. */
    fun ranking(s: HubSummary, m: Metric): List<Pair<HubApp, Double>> =
        s.apps.mapNotNull { a -> a.metrics[m]?.today?.let { a to it } }.sortedByDescending { it.second }

    fun fmt(v: Double?, m: Metric, currency: String = "GBP"): String = fmt(v, m.unit, currency)

    fun fmt(v: Double?, unit: MetricUnit, currency: String = "GBP"): String {
        if (v == null || v.isNaN()) return "–"
        return when (unit) {
            MetricUnit.MONEY -> money(v, currency)
            MetricUnit.PERCENT -> if (v >= 99.995) "100%" else String.format(Locale.UK, "%.2f%%", v)
            MetricUnit.RATING -> String.format(Locale.UK, "%.1f★", v)
            MetricUnit.MILLIS -> "${v.roundToLong()} ms"
            MetricUnit.COUNT -> count(v)
        }
    }

    fun money(v: Double, currency: String = "GBP"): String {
        val sym = when (currency.uppercase(Locale.ROOT)) { "GBP" -> "£"; "USD" -> "$"; "EUR" -> "€"; else -> "" }
        val body = when {
            abs(v) >= 10_000 -> String.format(Locale.UK, "%,.0f", v)
            else -> String.format(Locale.UK, "%,.2f", v)
        }
        return if (sym.isEmpty()) "$body ${currency.uppercase(Locale.ROOT)}" else (if (v < 0) "-$sym${body.removePrefix("-")}" else "$sym$body")
    }

    fun count(v: Double): String = when {
        abs(v) >= 1_000_000 -> String.format(Locale.UK, "%.1fM", v / 1_000_000)
        abs(v) >= 10_000 -> String.format(Locale.UK, "%.1fk", v / 1_000)
        else -> String.format(Locale.UK, "%,d", v.roundToLong())
    }

    /**
     * "Since you last looked" (phase 2): what changed between the summary the user last saw and
     * this one, in plain lines, most important first. Empty when nothing worth saying changed.
     */
    fun sinceLastLooked(before: HubSummary?, now: HubSummary): List<String> {
        if (before == null || before.updatedAt >= now.updatedAt) return emptyList()
        val out = mutableListOf<String>()
        now.wentLive(before).forEach { out += "${it.name} is live on the App Store" }
        now.apps.forEach { a ->
            val was = before.app(a.id)
            if (a.review?.rejected == true && was?.review?.rejected != true) out += "${a.name} was rejected in review"
            if (a.glow == HubStatus.ERRORS && was?.glow != HubStatus.ERRORS) out += "${a.name}: ${a.statusNote.ifBlank { "errors" }}"
        }
        val newReviews = now.reviews.count { r -> before.reviews.none { it.id == r.id } }
        if (newReviews > 0) out += if (newReviews == 1) "1 new review" else "$newReviews new reviews"
        fun delta(m: Metric): Double? {
            val a = total(now, m)?.today ?: return null
            val b = total(before, m)?.today ?: 0.0
            // the day rolled over: today's figure started again, so show today's total
            return if (sameDay(before.updatedAt, now.updatedAt)) a - b else a
        }
        delta(Metric.DOWNLOADS)?.takeIf { it >= 1 }?.let { out += "+${count(it)} downloads" }
        delta(Metric.REVENUE)?.takeIf { it >= 0.01 }?.let { out += "+${money(it, total(now, Metric.REVENUE)?.currency ?: "GBP")} revenue" }
        delta(Metric.ADS)?.takeIf { it >= 0.01 }?.let { out += "+${money(it, total(now, Metric.ADS)?.currency ?: "GBP")} from ads" }
        delta(Metric.CRASHES)?.takeIf { it >= 1 }?.let { out += "${count(it)} new crashes" }
        return out
    }

    private fun sameDay(a: Long, b: Long): Boolean = a / 86_400_000L == b / 86_400_000L

    // ------------------------------------------------------------------ JSON

    fun parse(json: String): HubSummary = parse(JSONObject(json))

    fun parse(o: JSONObject): HubSummary {
        val apps = o.optJSONArray("apps").objects().map { parseApp(it) }
        val sources = o.optJSONObject("sources")?.let { src ->
            src.keys().asSequence().map { k ->
                val s = src.getJSONObject(k)
                SourceState(k, s.optString("label", k), s.optBoolean("connected", true), s.optBoolean("ok", false), s.optLong("at"), s.optString("error", ""))
            }.sortedBy { it.label }.toList()
        } ?: emptyList()
        val finance = o.optJSONObject("finance")?.takeIf { it.optString("month").isNotBlank() && it.has("proceeds") }?.let {
            FinanceMonth(it.optString("month"), it.optDouble("proceeds"), it.optString("currency", "GBP"), it.optBoolean("partial"))
        }
        return HubSummary(
            updatedAt = o.optLong("updatedAt"),
            apps = apps,
            sources = sources,
            finance = finance,
            reviews = o.optJSONArray("reviews").objects().map { parseReview(it, "") },
            costs = o.optJSONArray("costs").objects().map {
                CostLine(it.optString("key"), it.optString("label"), it.optDoubleOrNull("amount"), it.optString("currency", "GBP"), it.optString("note"), it.optString("due"), it.optBoolean("warn"))
            },
            repos = o.optJSONArray("repos").objects().map {
                RepoState(it.optString("repo"), it.optString("message"), it.optString("sha"), it.optLong("at"), it.optString("build"), it.optString("url"))
            },
            sites = o.optJSONArray("sites").objects().map {
                SiteCheck(it.optString("appId"), it.optString("host"), it.optBoolean("up", true), it.optLongOrNull("ms"), it.optIntOrNull("sslDays"), it.optIntOrNull("domainDays"))
            },
            demo = o.optBoolean("demo"),
            events = o.optJSONArray("events").objects().mapNotNull {
                val at = it.optLong("at")
                if (at <= 0L || it.optString("title").isBlank()) null
                else HubEvent(it.optString("id").ifBlank { "${it.optString("kind")}:$at" }, at, it.optString("kind"), it.optString("appId"), it.optString("title"), it.optString("detail"), it.optBoolean("estimated"))
            },
        )
    }

    private fun parseApp(o: JSONObject): HubApp {
        val id = o.optString("id")
        val metrics = o.optJSONObject("metrics")?.let { mo ->
            mo.keys().asSequence().mapNotNull { k ->
                val m = Metric.of(k) ?: return@mapNotNull null
                val v = mo.optJSONObject(k) ?: return@mapNotNull null
                m to MetricValue(v.optDoubleOrNull("today"), v.optDoubleOrNull("week"), v.optJSONArray("series").doubles(), v.optString("currency", "GBP"))
            }.toMap()
        } ?: emptyMap()
        return HubApp(
            id = id,
            name = o.optString("name", id),
            kind = if (o.optString("kind") == "site") HubKind.SITE else HubKind.APP,
            platform = o.optString("platform", "ios"),
            icon = o.optString("icon"),
            url = o.optString("url"),
            consoleUrl = o.optString("consoleUrl"),
            adsUrl = o.optString("adsUrl"),
            status = HubStatus.of(o.optString("status", "")),
            statusNote = o.optString("statusNote"),
            review = o.optJSONObject("review")?.let { ReviewState(it.optString("state"), it.optString("version"), it.optLong("since")) },
            latestReview = o.optJSONObject("latestReview")?.let { parseReview(it, id) },
            crashes = o.optJSONArray("crashes").objects().map {
                CrashIssue(it.optString("title"), it.optString("subtitle"), it.optInt("events"), it.optInt("users"), it.optString("version"), it.optBoolean("fresh"))
            },
            metrics = metrics,
        )
    }

    private fun parseReview(o: JSONObject, appId: String) = StoreReview(
        id = o.optString("id"), appId = o.optString("appId", appId), rating = o.optInt("rating"),
        title = o.optString("title"), body = o.optString("body"), author = o.optString("author"),
        territory = o.optString("territory"), at = o.optLong("at"),
        reply = o.optString("reply").takeIf { it.isNotBlank() },
    )

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONArray?.doubles(): List<Double> = if (this == null) emptyList() else (0 until length()).map { optDouble(it, 0.0) }
    private fun JSONObject.optDoubleOrNull(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null
    private fun JSONObject.optLongOrNull(k: String): Long? = if (has(k) && !isNull(k)) optLong(k) else null
    private fun JSONObject.optIntOrNull(k: String): Int? = if (has(k) && !isNull(k)) optInt(k) else null
}
