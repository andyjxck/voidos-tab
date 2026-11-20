package com.andysocial.voidos

import com.andysocial.voidos.hub.DemoHub
import com.andysocial.voidos.hub.Hub
import com.andysocial.voidos.hub.HubApp
import com.andysocial.voidos.hub.HubKind
import com.andysocial.voidos.hub.HubStatus
import com.andysocial.voidos.hub.HubSummary
import com.andysocial.voidos.hub.Metric
import com.andysocial.voidos.hub.MetricValue
import com.andysocial.voidos.hub.ReviewState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 2.0: the hub summary, its maths, the refresh rule, the JSON both sides share, and Home widgets surviving a restart. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HubModelTest {
    private val t0 = 1_763_600_000_000L // a fixed instant, mid-afternoon UTC

    private fun app(id: String, kind: HubKind = HubKind.APP, vararg m: Pair<Metric, MetricValue>, review: ReviewState? = null) =
        HubApp(id, id.replaceFirstChar { it.uppercase() }, kind, review = review, metrics = m.toMap())

    @Test fun refreshNeverTwiceInTenMinutes() {
        assertTrue(Hub.shouldRefresh(0L, t0))
        assertFalse(Hub.shouldRefresh(t0, t0 + 9 * 60_000L))
        assertTrue(Hub.shouldRefresh(t0, t0 + 10 * 60_000L))
        // a clock that went backwards always refreshes
        assertTrue(Hub.shouldRefresh(t0, t0 - 1))
    }

    @Test fun agoReadsNaturally() {
        assertEquals("never updated", Hub.ago(0L, t0))
        assertEquals("updated just now", Hub.ago(t0, t0 + 20_000))
        assertEquals("updated 12 min ago", Hub.ago(t0, t0 + 12 * 60_000L))
        assertEquals("updated 3 h ago", Hub.ago(t0, t0 + 3 * 3_600_000L))
        assertEquals("updated 2 days ago", Hub.ago(t0, t0 + 50 * 3_600_000L))
    }

    @Test fun allAppsSumsOrAverages() {
        val s = HubSummary(t0, listOf(
            app("a", HubKind.APP, Metric.DOWNLOADS to MetricValue(10.0, 70.0, listOf(1.0, 2.0, 3.0)), Metric.RATING to MetricValue(4.0)),
            app("b", HubKind.APP, Metric.DOWNLOADS to MetricValue(5.0, 30.0, listOf(4.0, 5.0)), Metric.RATING to MetricValue(5.0)),
        ))
        val dl = Hub.total(s, Metric.DOWNLOADS)!!
        assertEquals(15.0, dl.today!!, 1e-9)
        assertEquals(100.0, dl.week!!, 1e-9)
        // the shorter series lines up with the end of the longer one
        assertEquals(listOf(1.0, 6.0, 8.0), dl.series)
        assertEquals(4.5, Hub.total(s, Metric.RATING)!!.today!!, 1e-9)
        assertNull(Hub.total(s, Metric.CRASHES))
        assertEquals(listOf("a", "b"), Hub.ranking(s, Metric.DOWNLOADS).map { it.first.id })
        assertEquals(15.0, Hub.value(s, Hub.ALL, Metric.DOWNLOADS)!!.today!!, 1e-9)
        assertEquals(5.0, Hub.value(s, "b", Metric.DOWNLOADS)!!.today!!, 1e-9)
    }

    @Test fun numbersFormat() {
        assertEquals("£12.40", Hub.fmt(12.4, Metric.REVENUE))
        assertEquals("£12,345", Hub.fmt(12_345.0, Metric.REVENUE))
        assertEquals("–", Hub.fmt(null, Metric.REVENUE))
        assertEquals("1,234", Hub.fmt(1234.0, Metric.DOWNLOADS))
        assertEquals("12.3k", Hub.fmt(12_300.0, Metric.DOWNLOADS))
        assertEquals("100%", Hub.fmt(99.999, Metric.UPTIME))
        assertEquals("4.6★", Hub.fmt(4.62, Metric.RATING))
        assertEquals("$3.00", Hub.money(3.0, "USD"))
    }

    @Test fun worstStatusWins() {
        assertEquals(HubStatus.ERRORS, HubStatus.worst(listOf(HubStatus.FINE, HubStatus.ERRORS, HubStatus.ATTENTION)))
        assertEquals(HubStatus.ATTENTION, HubStatus.worst(listOf(HubStatus.FINE, HubStatus.ATTENTION, HubStatus.UNKNOWN)))
        assertEquals(HubStatus.UNKNOWN, HubStatus.worst(emptyList()))
        assertEquals(HubStatus.ERRORS, HubStatus.of("down"))
    }

    @Test fun goingLiveIsSpotted() {
        val before = HubSummary(t0, listOf(app("voided", review = ReviewState("IN_REVIEW", "1.4"))))
        val after = HubSummary(t0 + 3_600_000L, listOf(app("voided", review = ReviewState("READY_FOR_SALE", "1.4"))))
        assertEquals(listOf("voided"), after.wentLive(before).map { it.id })
        assertTrue(after.wentLive(after).isEmpty())
        val lines = Hub.sinceLastLooked(before, after)
        assertEquals("Voided is live on the App Store", lines.first())
        // nothing new: nothing to say
        assertTrue(Hub.sinceLastLooked(after, after).isEmpty())
    }

    @Test fun sinceLastLookedCountsTheDifference() {
        val before = HubSummary(t0, listOf(app("a", HubKind.APP, Metric.DOWNLOADS to MetricValue(10.0), Metric.REVENUE to MetricValue(1.0))))
        val after = HubSummary(t0 + 600_000L, listOf(app("a", HubKind.APP, Metric.DOWNLOADS to MetricValue(14.0), Metric.REVENUE to MetricValue(3.5))))
        assertEquals(listOf("+4 downloads", "+£2.50 revenue"), Hub.sinceLastLooked(before, after))
    }

    @Test fun demoSummaryRoundTripsThroughJson() {
        val demo = DemoHub.summary(t0)
        assertTrue(demo.demo)
        assertTrue(demo.apps.any { it.kind == HubKind.SITE })
        assertTrue(demo.storeApps.size >= 4)
    }

    @Test fun parsesTheServerSummary() {
        val json = """{"updatedAt":$t0,"apps":[{"id":"asc:1","name":"Voided","kind":"app","icon":"https://x/icon.png",
            "status":"attention","review":{"state":"WAITING_FOR_REVIEW","version":"1.4"},
            "metrics":{"downloads":{"today":12,"week":80,"series":[1,2,3]},"nonsense":{"today":1}}},
            {"id":"site:staff","name":"Staff Hub","kind":"site","url":"https://tws-rota.co.uk"}],
            "sources":{"appStoreConnect":{"label":"App Store Connect","connected":true,"ok":false,"at":${t0 - 3_600_000L},"error":"HTTP 401"}}}"""
        val s = Hub.parse(json)
        assertEquals(2, s.apps.size)
        val v = s.app("asc:1")!!
        assertEquals(HubStatus.ATTENTION, v.glow)
        assertEquals("Waiting for review", v.review!!.label)
        assertEquals(12.0, v.metrics[Metric.DOWNLOADS]!!.today!!, 1e-9)
        assertEquals(1, v.metrics.size)
        assertEquals(HubKind.SITE, s.app("site:staff")!!.kind)
        val src = s.sources.single()
        assertFalse(src.ok); assertEquals("HTTP 401", src.error)
    }

    /** The contract: the summary the aggregator's own tests produce (functions/test) parses into what the screens need. */
    @Test fun parsesTheAggregatorsOwnSummary() {
        val f = listOf("../functions/test/fixtures/summary-sample.json", "functions/test/fixtures/summary-sample.json").map { java.io.File(it) }.first { it.exists() }
        val s = Hub.parse(f.readText())
        assertEquals(listOf("Button Panic", "Voided"), s.apps.map { it.name })
        val v = s.app("asc:111")!!
        assertEquals(HubStatus.ATTENTION, v.glow)
        assertEquals("Waiting for review", v.review!!.label)
        assertEquals("https://is1.mzstatic.com/voided.png", v.icon)
        assertEquals(7.0, v.metrics[Metric.DOWNLOADS]!!.today!!, 1e-9)
        assertEquals(7, v.metrics[Metric.DOWNLOADS]!!.series.size)
        assertEquals("Thanks Kev!", v.latestReview!!.reply)
        assertTrue(s.reviews.any { it.id == "r2" && it.appId == "asc:111" })
        assertTrue(s.payout!!.nextDate.isNotBlank())
        val asc = s.sources.first { it.key == "appstore" }
        assertTrue(asc.connected && asc.ok)
        assertFalse(s.sources.first { it.key == "admob" }.connected)
        // every source key the server sends is one Settings › Hub knows (the Apple renewal is a cost line only)
        val known = com.andysocial.voidos.hub.HUB_SOURCES.map { it.first }.toSet() + "apple"
        s.sources.forEach { assertTrue("unknown source ${it.key}", it.key in known) }
    }

    @Test fun homeWidgetsSurviveARestart() {
        val ctx = RuntimeEnvironment.getApplication()
        val s = Store(ctx)
        s.saveWidgets(emptyList())
        s.addWidget("sky"); s.addWidget("hrev")
        // a retired kind can't be added
        s.addWidget("world")
        assertEquals(listOf("sky", "hrev"), s.widgets.map { it.kind })
        // and on the next start they're all still there (2.0 alpha lost them: the retired list loaded too late)
        assertEquals(listOf("sky", "hrev"), Store(ctx).widgets.map { it.kind })
        // widgets saved by 1.x of a kind 2.0 retired are dropped, the rest kept
        ctx.getSharedPreferences("voidos", 0).edit().putString("widgets",
            """[{"id":1,"k":"sound","x":0.1,"y":0.1},{"id":2,"k":"sky","x":0.9,"y":0.1}]""").commit()
        assertEquals(listOf("sky"), Store(ctx).widgets.map { it.kind })
    }
}
