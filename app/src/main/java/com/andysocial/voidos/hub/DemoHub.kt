package com.andysocial.voidos.hub

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Made-up numbers so every hub screen has something to show before any source is connected,
 * and so the tests are steady. Always marked as demo on screen ("Demo data"). Nothing here is
 * the user's real figures.
 */
object DemoHub {
    private fun wave(seed: Int, base: Double, swing: Double, days: Int = 14): List<Double> =
        (0 until days).map { d -> max(0.0, base + swing * sin((d + seed) * 0.9) + ((d * 7 + seed * 3) % 5) * swing * 0.15) }
            .map { (it * 100).roundToInt() / 100.0 }

    private fun mv(seed: Int, base: Double, swing: Double, round: Boolean = true): MetricValue {
        val s = wave(seed, base, swing).map { if (round) it.roundToInt().toDouble() else it }
        return MetricValue(s.last(), s.takeLast(7).sum().let { if (round) it else (it * 100).roundToInt() / 100.0 }, s)
    }

    fun summary(now: Long = System.currentTimeMillis()): HubSummary {
        val day = 86_400_000L
        val bp = HubApp(
            id = "demo:buttonpanic", name = "Button Panic", platform = "ios",
            consoleUrl = "https://appstoreconnect.apple.com/apps", adsUrl = "https://apps.admob.com",
            status = HubStatus.ATTENTION, statusNote = "3 new crashes in 1.4.2",
            review = ReviewState("READY_FOR_SALE", "1.4.2", now - 3 * day),
            latestReview = StoreReview("demo-r1", "demo:buttonpanic", 5, "Mercer is terrifying", "Played the whole story in one go. The Panel Report at the end got me.", "pressthebutton", "GBR", now - 5 * 3_600_000L),
            crashes = listOf(CrashIssue("EXC_BAD_ACCESS in PanelReport.render", "PanelReport.swift line 212", 3, 2, "1.4.2", fresh = true)),
            metrics = mapOf(
                Metric.DOWNLOADS to mv(1, 38.0, 12.0), Metric.REVENUE to mv(2, 6.4, 2.5, false),
                Metric.USERS to mv(3, 410.0, 60.0), Metric.ADS to mv(4, 3.1, 1.2, false),
                Metric.IMPRESSIONS to mv(5, 2900.0, 500.0), Metric.ECPM to MetricValue(1.07, 1.02, wave(6, 1.0, 0.15)),
                Metric.CRASHES to MetricValue(3.0, 5.0, listOf(0.0, 0.0, 1.0, 0.0, 0.0, 1.0, 3.0)),
                Metric.REVIEWS to MetricValue(2.0, 9.0, listOf(1.0, 0.0, 2.0, 1.0, 3.0, 0.0, 2.0)),
                Metric.RATING to MetricValue(4.6, 4.6, listOf(4.5, 4.5, 4.6, 4.6, 4.6, 4.6, 4.6)),
            ),
        )
        val voided = HubApp(
            id = "demo:voided", name = "Voided", platform = "ios",
            consoleUrl = "https://appstoreconnect.apple.com/apps", adsUrl = "https://apps.admob.com",
            status = HubStatus.ATTENTION, statusNote = "2.0 is waiting for review",
            review = ReviewState("WAITING_FOR_REVIEW", "2.0", now - day),
            latestReview = StoreReview("demo-r2", "demo:voided", 4, "Round 20 is brutal", "Love the rift heroes. Boss at 50 took me ages.", "riftwalker", "USA", now - 2 * day),
            metrics = mapOf(
                Metric.DOWNLOADS to mv(7, 22.0, 9.0), Metric.REVENUE to mv(8, 2.2, 1.4, false),
                Metric.USERS to mv(9, 180.0, 40.0), Metric.ADS to mv(10, 1.9, 0.8, false),
                Metric.IMPRESSIONS to mv(11, 1500.0, 300.0), Metric.ECPM to MetricValue(1.26, 1.2, wave(12, 1.2, 0.2)),
                Metric.CRASHES to MetricValue(0.0, 1.0, listOf(0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
                Metric.REVIEWS to MetricValue(1.0, 4.0, listOf(0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0)),
                Metric.RATING to MetricValue(4.3, 4.3, listOf(4.2, 4.2, 4.3, 4.3, 4.3, 4.3, 4.3)),
            ),
        )
        val baller = HubApp(
            id = "demo:voidballer", name = "Void Baller", platform = "ios",
            consoleUrl = "https://appstoreconnect.apple.com/apps", adsUrl = "https://apps.admob.com",
            status = HubStatus.FINE, review = ReviewState("READY_FOR_SALE", "1.1", now - 20 * day),
            latestReview = StoreReview("demo-r3", "demo:voidballer", 5, "Addictive", "One more go, every time.", "ballr", "GBR", now - 4 * day, reply = "Thanks for playing!"),
            metrics = mapOf(
                Metric.DOWNLOADS to mv(13, 51.0, 14.0), Metric.REVENUE to mv(14, 1.1, 0.9, false),
                Metric.USERS to mv(15, 520.0, 80.0), Metric.ADS to mv(16, 4.4, 1.5, false),
                Metric.IMPRESSIONS to mv(17, 4100.0, 700.0), Metric.ECPM to MetricValue(1.07, 1.05, wave(18, 1.05, 0.1)),
                Metric.CRASHES to MetricValue(0.0, 0.0, List(7) { 0.0 }),
                Metric.REVIEWS to MetricValue(0.0, 3.0, listOf(1.0, 0.0, 0.0, 1.0, 0.0, 1.0, 0.0)),
                Metric.RATING to MetricValue(4.7, 4.7, List(7) { 4.7 }),
            ),
        )
        val sober = HubApp(
            id = "demo:sobriety", name = "Sobriety Tracker", platform = "ios",
            consoleUrl = "https://appstoreconnect.apple.com/apps",
            status = HubStatus.FINE, review = ReviewState("READY_FOR_SALE", "1.3", now - 40 * day),
            metrics = mapOf(
                Metric.DOWNLOADS to mv(19, 17.0, 6.0), Metric.USERS to mv(20, 260.0, 30.0),
                Metric.CRASHES to MetricValue(0.0, 0.0, List(7) { 0.0 }),
                Metric.REVIEWS to MetricValue(0.0, 1.0, listOf(0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0)),
                Metric.RATING to MetricValue(4.9, 4.9, List(7) { 4.9 }),
            ),
        )
        val hub = HubApp(
            id = "site:staffhub", name = "Staff Hub", kind = HubKind.SITE, platform = "web", url = "https://tws-rota.co.uk",
            consoleUrl = "https://console.firebase.google.com", status = HubStatus.FINE,
            metrics = mapOf(
                Metric.UPTIME to MetricValue(100.0, 99.98, List(7) { if (it == 3) 99.86 else 100.0 }),
                Metric.USERS to mv(21, 14.0, 4.0), Metric.BOUNCES to MetricValue(0.0, 2.0, listOf(0.0, 1.0, 0.0, 0.0, 1.0, 0.0, 0.0)),
            ),
        )
        val swan = HubApp(
            id = "site:whiteswan", name = "White Swan", kind = HubKind.SITE, platform = "web",
            status = HubStatus.FINE,
            metrics = mapOf(Metric.UPTIME to MetricValue(100.0, 100.0, List(7) { 100.0 }), Metric.USERS to mv(22, 60.0, 15.0)),
        )
        val apps = listOf(bp, voided, baller, sober, hub, swan)
        return HubSummary(
            updatedAt = now - 6 * 60_000L,
            apps = apps,
            sources = listOf(
                SourceState("appstore", "App Store Connect", true, true, now - 6 * 60_000L),
                SourceState("admob", "AdMob", true, true, now - 6 * 60_000L),
                SourceState("firebase", "Firebase Analytics", true, true, now - 6 * 60_000L),
                SourceState("crashlytics", "Crashlytics", true, true, now - 6 * 60_000L),
                SourceState("sites", "Uptime checks", true, true, now - 6 * 60_000L),
            ),
            payout = Payout("2026-12-03", 112.40, "GBP", 96.15, "2026-11-05"),
            reviews = apps.mapNotNull { it.latestReview } + StoreReview("demo-r4", "demo:buttonpanic", 2, "Too hard", "Calm mode is not calm.", "nope", "GBR", now - 26 * 3_600_000L),
            costs = listOf(
                CostLine("gcp", "Google Cloud & Firebase", 3.12, "GBP", "this month so far"),
                CostLine("elevenlabs", "ElevenLabs", 41_200.0, "credits", "credits left"),
                CostLine("apple", "Apple Developer Program", 79.0, "GBP", "renews", "2027-03-14"),
            ),
            repos = listOf(
                RepoState("andyjxck/voidos-tab", "VoidOS 2.0.0-alpha1", "0000000", now - 2 * 3_600_000L, "success"),
                RepoState("andyjxck/staff-hub", "Owner-only login details", "0000000", now - day, "success"),
            ),
            sites = listOf(
                SiteCheck("site:staffhub", "tws-rota.co.uk", true, 182, 61, 240),
                SiteCheck("site:whiteswan", "White Swan site", true, 240, 74, 300),
            ),
            demo = true,
        )
    }
}
