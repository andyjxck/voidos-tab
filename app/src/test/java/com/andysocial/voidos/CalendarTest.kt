package com.andysocial.voidos

import com.andysocial.voidos.hub.DevApp
import com.andysocial.voidos.hub.DevApps
import com.andysocial.voidos.hub.Hub
import com.andysocial.voidos.hub.HubCalendar
import com.andysocial.voidos.hub.HubEvent
import com.andysocial.voidos.ui.Module
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** 2.0.2: the hub calendar's rules, the summary's events, Calendar taking Weather's place, and Android Settings leaving Dev Apps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CalendarTest {
    private val zone = ZoneId.of("Europe/London")
    private val today = LocalDate.of(2026, 11, 21)
    private fun at(d: LocalDate) = d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private fun ev(id: String, d: LocalDate, kind: String = "release", app: String = "asc:1", title: String = id) = HubEvent(id, at(d), kind, app, title)

    @Test fun upcomingSoonestFirstPastNewestFirst() {
        val list = listOf(ev("a", today.minusDays(3)), ev("b", today.plusDays(5)), ev("c", today), ev("d", today.minusDays(1)), ev("e", today.plusDays(1)))
        assertEquals(listOf("c", "e", "b"), HubCalendar.upcoming(list, today, zone).map { it.id })
        assertEquals(listOf("d", "a"), HubCalendar.past(list, today, zone).map { it.id })
    }

    @Test fun monthCellsStartOnMonday() {
        // November 2026 starts on a Sunday: six blanks, then 30 days, filled out to whole weeks
        val cells = HubCalendar.cells(YearMonth.of(2026, 11))
        assertEquals(6, cells.takeWhile { it == null }.size)
        assertEquals(30, cells.count { it != null })
        assertEquals(0, cells.size % 7)
        val byDay = HubCalendar.byDay(listOf(ev("a", today), ev("b", today), ev("c", LocalDate.of(2026, 12, 1))), YearMonth.of(2026, 11), zone)
        assertEquals(setOf(21), byDay.keys)
        assertEquals(2, byDay[21]!!.size)
    }

    @Test fun datesReadNaturally() {
        assertEquals("Today", HubCalendar.date(at(today), today, zone))
        assertEquals("Tomorrow", HubCalendar.date(at(today.plusDays(1)), today, zone))
        assertEquals("Yesterday", HubCalendar.date(at(today.minusDays(1)), today, zone))
        assertEquals("Tue 1 Dec", HubCalendar.date(at(LocalDate.of(2026, 12, 1)), today, zone))
        assertEquals("14 Feb 2027", HubCalendar.date(at(LocalDate.of(2027, 2, 14)), today, zone))
    }

    @Test fun troubleIsCoral() {
        val coral = com.andysocial.voidos.ui.V.Coral
        assertEquals(coral, HubCalendar.colour(ev("r", today.minusDays(2), "review", title = "1.2 rejected"), today, zone))
        assertEquals(coral, HubCalendar.colour(ev("s", today.plusDays(5), "ssl"), today, zone))
        assertEquals(com.andysocial.voidos.ui.V.Amber, HubCalendar.colour(ev("s2", today.plusDays(60), "ssl"), today, zone))
        assertEquals(com.andysocial.voidos.ui.V.Teal, HubCalendar.colour(ev("rel", today.minusDays(2)), today, zone))
    }

    @Test fun summaryCarriesEvents() {
        val json = """{"updatedAt":1,"apps":[{"id":"asc:1","name":"Voided","kind":"app"}],
            "events":[{"id":"x","at":1764590400000,"kind":"scheduled","appId":"asc:1","title":"1.5 scheduled","detail":"Approved"},
                       {"id":"y","at":1764763200000,"kind":"payout","appId":"","title":"Apple payout (est.)","estimated":true},
                       {"id":"bad","at":0,"kind":"ssl","title":"no date"}]}"""
        val s = Hub.parse(json)
        assertEquals(listOf("x", "y"), s.events.map { it.id })
        assertTrue(s.events[1].estimated)
        // a hidden app's events go with it
        assertEquals(listOf("y"), Hub.withoutHidden(s, setOf("asc:1")).events.map { it.id })
    }

    @Test fun calendarTakesWeathersPlace() {
        assertTrue(Module.entries.none { it.name == "WEATHER" })
        // same place in the order: after Time, before Settings
        val order = Module.entries.map { it.name }
        assertEquals(order.indexOf("TOOLS") + 1, order.indexOf("CALENDAR"))
        assertEquals(order.indexOf("SYSTEM") - 1, order.indexOf("CALENDAR"))
        // a split or a screen saved as Weather opens the Calendar
        assertEquals(Module.CALENDAR, Module.of("WEATHER"))
        assertEquals(Module.NOTES, Module.of("NOTES"))
    }

    @Test fun androidSettingsLeavesDevApps() {
        assertTrue(DevApps.defaults.none { it.pkg == "com.android.settings" })
        assertTrue(DevApps.defaults.any { it.pkg == DevApps.MAGISK })
        val saved = DevApps.encode(listOf(DevApp("AdMob", "", "https://apps.admob.com"), DevApp("Android Settings", "com.android.settings", ""), DevApp("Magisk", DevApps.MAGISK, "")))
        val purged = DevApps.purged(saved)!!
        assertTrue(DevApps.decode(purged).none { it.pkg == "com.android.settings" })
        assertEquals(2, DevApps.decode(purged).size)
        assertNull("nothing to take out", DevApps.purged(purged))
        // on startup a saved list loses it, and the list still ends with Magisk
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("voidos", 0).edit().putString("devApps", saved).commit()
        val st = Store(ctx)
        assertTrue(st.devApps.lines().none { "com.android.settings" in it })
        val list = DevApps.list(st)
        assertTrue(list.none { it.pkg == "com.android.settings" })
        assertEquals(1, list.count { it.pkg == DevApps.MAGISK })
    }
}
