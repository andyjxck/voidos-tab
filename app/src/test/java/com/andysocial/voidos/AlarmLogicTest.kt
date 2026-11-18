package com.andysocial.voidos

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
/** The alarm maths: when each alarm rings next, snooze, skip, the clock changes and the volume ramp. */
class AlarmLogicTest {
    private val london = ZoneId.of("Europe/London")

    /** 18 November 2026 is a Wednesday. */
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), london)
    private fun ms(z: ZonedDateTime) = z.toInstant().toEpochMilli()
    private fun ring(a: Alarm, now: ZonedDateTime) = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(AlarmMath.trigger(a, ms(now), london)!!), london)
    private fun mask(vararg d: DayOfWeek) = d.fold(0) { m, x -> m or (1 shl (x.value - 1)) }

    @Test fun onceLaterToday() {
        assertEquals(at(2026, 11, 18, 7, 0), ring(Alarm(1, 7, 0), at(2026, 11, 18, 1, 55)))
    }

    @Test fun onceAlreadyPassedIsTomorrow() {
        assertEquals(at(2026, 11, 19, 7, 0), ring(Alarm(1, 7, 0), at(2026, 11, 18, 9, 0)))
    }

    @Test fun exactlyNowIsTomorrowNotNow() {
        assertEquals(at(2026, 11, 19, 7, 0), ring(Alarm(1, 7, 0), at(2026, 11, 18, 7, 0)))
    }

    @Test fun weekdaysSkipTheWeekend() {
        val a = Alarm(1, 7, 0, days = AlarmMath.WEEKDAYS)
        // Friday 20 Nov after 7:00 -> Monday 23 Nov
        assertEquals(at(2026, 11, 23, 7, 0), ring(a, at(2026, 11, 20, 8, 0)))
        // Saturday -> Monday
        assertEquals(at(2026, 11, 23, 7, 0), ring(a, at(2026, 11, 21, 6, 0)))
    }

    @Test fun weekendsOnly() {
        val a = Alarm(1, 9, 30, days = AlarmMath.WEEKENDS)
        assertEquals(at(2026, 11, 21, 9, 30), ring(a, at(2026, 11, 18, 12, 0)))
        assertEquals(at(2026, 11, 22, 9, 30), ring(a, at(2026, 11, 21, 10, 0)))
    }

    @Test fun singleDayAWeekAway() {
        val a = Alarm(1, 7, 0, days = mask(DayOfWeek.WEDNESDAY))
        // Wednesday after 7:00 -> next Wednesday
        assertEquals(at(2026, 11, 25, 7, 0), ring(a, at(2026, 11, 18, 7, 1)))
    }

    @Test fun offAlarmsNeverRing() {
        assertNull(AlarmMath.trigger(Alarm(1, 7, 0, enabled = false), ms(at(2026, 11, 18, 1, 0)), london))
    }

    @Test fun snoozeWinsOverTheSchedule() {
        val snoozeAt = ms(at(2026, 11, 18, 7, 9))
        val a = Alarm(1, 7, 0, days = AlarmMath.EVERY_DAY, snoozeAt = snoozeAt)
        assertEquals(snoozeAt, AlarmMath.trigger(a, ms(at(2026, 11, 18, 7, 0)) + 1000, london))
        // once the snooze moment has passed, the normal schedule is back
        assertEquals(at(2026, 11, 19, 7, 0), ring(a, at(2026, 11, 18, 7, 10)))
    }

    @Test fun skipNextJumpsOneRing() {
        val a = Alarm(1, 7, 0, days = AlarmMath.WEEKDAYS)
        val now = at(2026, 11, 18, 1, 0)
        val next = AlarmMath.trigger(a, ms(now), london)!!
        val skipped = a.copy(skipUntil = next + 1)
        assertEquals(at(2026, 11, 19, 7, 0), ring(skipped, now))
        // skipping Friday's lands on Monday
        val fri = at(2026, 11, 20, 1, 0)
        val friNext = AlarmMath.trigger(a, ms(fri), london)!!
        assertEquals(at(2026, 11, 23, 7, 0), ring(a.copy(skipUntil = friNext + 1), fri))
    }

    @Test fun clocksGoForwardStillRings() {
        // 29 March 2026: 01:00-02:00 doesn't exist in London. A 1:30 alarm rings at 2:30 BST (the same instant as 1:30 GMT would be).
        val r = ring(Alarm(1, 1, 30), at(2026, 3, 29, 0, 30))
        assertEquals(29, r.dayOfMonth)
        assertEquals(2, r.hour); assertEquals(30, r.minute)
    }

    @Test fun clocksGoBackRingsOnce() {
        // 25 October 2026: 01:00-02:00 happens twice. The alarm rings at the first 1:30, and the next one is the next day.
        val first = ring(Alarm(1, 1, 30, days = AlarmMath.EVERY_DAY), at(2026, 10, 25, 0, 30))
        assertEquals(25, first.dayOfMonth)
        val after = ring(Alarm(1, 1, 30, days = AlarmMath.EVERY_DAY), first.plusMinutes(1))
        assertEquals(26, after.dayOfMonth)
    }

    @Test fun soonestPicksTheNextOn() {
        val now = at(2026, 11, 18, 1, 0)
        val list = listOf(Alarm(1, 8, 0), Alarm(2, 6, 30), Alarm(3, 5, 0, enabled = false))
        assertEquals(2, AlarmMath.soonest(list, ms(now), london)!!.first.id)
        assertNull(AlarmMath.soonest(listOf(Alarm(3, 5, 0, enabled = false)), ms(now), london))
    }

    @Test fun dayLabels() {
        assertEquals("Once", AlarmMath.daysLabel(0))
        assertEquals("Weekdays", AlarmMath.daysLabel(AlarmMath.WEEKDAYS))
        assertEquals("Weekends", AlarmMath.daysLabel(AlarmMath.WEEKENDS))
        assertEquals("Every day", AlarmMath.daysLabel(AlarmMath.EVERY_DAY))
        assertEquals("Mon, Wed, Fri", AlarmMath.daysLabel(mask(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)))
    }

    @Test fun untilLabels() {
        assertEquals("in less than a minute", AlarmMath.untilLabel(0))
        assertEquals("in 1 min", AlarmMath.untilLabel(20_000))
        assertEquals("in 5 h 5 min", AlarmMath.untilLabel((5 * 60 + 5) * 60_000L))
        assertEquals("in 2 h", AlarmMath.untilLabel(2 * 3_600_000L))
        assertEquals("in 2 days 3 h", AlarmMath.untilLabel((51 * 60 + 10) * 60_000L))
    }

    @Test fun rampClimbsSteadilyToFull() {
        assertEquals(1f, AlarmMath.rampLevel(0, 0), 0f)
        assertEquals(0.04f, AlarmMath.rampLevel(0, 30_000), 0.001f)
        assertEquals(1f, AlarmMath.rampLevel(30_000, 30_000), 0.001f)
        assertEquals(1f, AlarmMath.rampLevel(90_000, 30_000), 0.001f)
        var last = 0f
        for (t in 0..30_000 step 1000) {
            val v = AlarmMath.rampLevel(t.toLong(), 30_000)
            assertTrue("ramp must never drop", v >= last)
            last = v
        }
        // still quiet a third of the way in
        assertTrue(AlarmMath.rampLevel(10_000, 30_000) < 0.1f)
    }

    @Test fun clockText() {
        assertEquals("07:05", AlarmMath.clock(7, 5, true))
        assertEquals("7:05", AlarmMath.clock(7, 5, false))
        assertEquals("12:00", AlarmMath.clock(0, 0, false))
        assertEquals("12:30", AlarmMath.clock(12, 30, false))
        assertEquals("AM", AlarmMath.ampm(0)); assertEquals("PM", AlarmMath.ampm(12))
    }

    @Test fun jsonRoundTripAndBadInput() {
        val a = Alarm(4, 6, 15, days = AlarmMath.WEEKDAYS, label = "Gym", sound = "goat", rampSec = 60, snoozeMin = 5, volume = 0.6f)
        assertEquals(a, Alarm.fromJson(a.toJson()))
        // an unknown sound falls back to the default, and silly numbers are kept in range
        val bad = Alarm.fromJson(JSONObject().put("id", 9).put("h", 30).put("m", -4).put("sound", "nope").put("vol", 7.0))
        assertEquals(23, bad.hour); assertEquals(0, bad.minute)
        assertEquals(AlarmSounds.DEFAULT, bad.sound)
        assertEquals(1f, bad.volume, 0f)
    }

    @Test fun everyCategoryHasSounds() {
        SoundCat.entries.forEach { c -> assertTrue("$c has no sounds", AlarmSounds.all.count { it.cat == c } >= 3) }
        assertEquals(AlarmSounds.all.size, AlarmSounds.all.map { it.key }.toSet().size)
        // only sounds that build up loop from part-way through
        AlarmSounds.all.forEach { s -> assertEquals(s.key, s.cat == SoundCat.PROGRESSIVE, s.loopFromMs > 0) }
    }
}
