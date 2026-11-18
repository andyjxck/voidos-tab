package com.andysocial.voidos

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.pow

/**
 * One alarm. [days] is a bit mask, Monday = bit 0 ... Sunday = bit 6; 0 means
 * "once" (the next time the clock reads [hour]:[minute]). [rampSec] is how long
 * the volume takes to climb from a whisper to [volume]; 0 starts at full volume.
 * [snoozeMin] 0 hides the snooze button. [snoozeAt] is set while snoozed and
 * [skipUntil] skips every ring before that moment ("skip next").
 */
data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val days: Int = 0,
    val enabled: Boolean = true,
    val label: String = "",
    val sound: String = AlarmSounds.DEFAULT,
    val rampSec: Int = 30,
    val snoozeMin: Int = 9,
    val volume: Float = 0.8f,
    val snoozeAt: Long = 0L,
    val skipUntil: Long = 0L,
) {
    val repeats get() = days != 0
    fun on(d: DayOfWeek) = days and (1 shl (d.value - 1)) != 0

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("h", hour).put("m", minute).put("days", days).put("on", enabled).put("label", label)
        .put("sound", sound).put("ramp", rampSec).put("snooze", snoozeMin).put("vol", volume.toDouble())
        .put("snoozeAt", snoozeAt).put("skip", skipUntil)

    companion object {
        fun fromJson(o: JSONObject) = Alarm(
            id = o.getInt("id"), hour = o.getInt("h").coerceIn(0, 23), minute = o.getInt("m").coerceIn(0, 59),
            days = o.optInt("days", 0) and 0x7F, enabled = o.optBoolean("on", true), label = o.optString("label", ""),
            sound = AlarmSounds.byKey(o.optString("sound", AlarmSounds.DEFAULT)).key, rampSec = o.optInt("ramp", 30).coerceAtLeast(0),
            snoozeMin = o.optInt("snooze", 9).coerceAtLeast(0), volume = o.optDouble("vol", 0.8).toFloat().coerceIn(0.05f, 1f),
            snoozeAt = o.optLong("snoozeAt", 0L), skipUntil = o.optLong("skip", 0L),
        )
    }
}

/** Pure alarm maths: no Android in here, so the tests can pin every edge. */
object AlarmMath {
    const val WEEKDAYS = 0b0011111
    const val WEEKENDS = 0b1100000
    const val EVERY_DAY = 0b1111111

    /** The next moment strictly after [now] that this alarm's clock time comes round, ignoring snooze and skip. */
    fun nextTime(a: Alarm, now: ZonedDateTime): ZonedDateTime {
        val zone = now.zone
        val t = LocalTime.of(a.hour, a.minute)
        var day: LocalDate = now.toLocalDate()
        repeat(8) {
            // ZonedDateTime.of moves a time that falls in a spring-forward gap to just after it
            val at = ZonedDateTime.of(day, t, zone)
            if (at.isAfter(now) && (!a.repeats || a.on(day.dayOfWeek))) return at
            day = day.plusDays(1)
        }
        return ZonedDateTime.of(day, t, zone)
    }

    /** When this alarm should next ring, in epoch ms, or null when it is off. Snooze wins; skip pushes past one ring. */
    fun trigger(a: Alarm, nowMs: Long, zone: ZoneId): Long? {
        if (!a.enabled) return null
        if (a.snoozeAt > nowMs) return a.snoozeAt
        var from = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        var at = nextTime(a, from)
        var guard = 0
        while (a.skipUntil > 0 && at.toInstant().toEpochMilli() < a.skipUntil && guard++ < 14) {
            from = at
            at = nextTime(a, from)
        }
        return at.toInstant().toEpochMilli()
    }

    /** The soonest alarm that is on, with the moment it rings. */
    fun soonest(list: List<Alarm>, nowMs: Long, zone: ZoneId): Pair<Alarm, Long>? =
        list.mapNotNull { a -> trigger(a, nowMs, zone)?.let { a to it } }.minByOrNull { it.second }

    /** "Every day", "Weekdays", "Weekends", "Never" (once), else "Mon, Wed, Fri". */
    fun daysLabel(days: Int): String = when (days and EVERY_DAY) {
        0 -> "Once"
        EVERY_DAY -> "Every day"
        WEEKDAYS -> "Weekdays"
        WEEKENDS -> "Weekends"
        else -> DayOfWeek.entries.filter { days and (1 shl (it.value - 1)) != 0 }
            .joinToString(", ") { it.name.take(3).lowercase().replaceFirstChar(Char::uppercase) }
    }

    /** "in 7 h 30 min", "in 12 min", "in less than a minute". Rounds up, like the clock app does. */
    fun untilLabel(ms: Long): String {
        val totalMin = ((ms + 59_999) / 60_000).coerceAtLeast(0)
        if (totalMin < 1) return "in less than a minute"
        val d = totalMin / (60 * 24); val h = (totalMin / 60) % 24; val m = totalMin % 60
        return "in " + listOfNotNull(
            if (d > 0) "$d day" + (if (d > 1) "s" else "") else null,
            if (h > 0) "$h h" else null,
            if (m > 0 && d == 0L) "$m min" else null,
        ).joinToString(" ")
    }

    /**
     * Gradual volume: the fraction of the alarm's volume [elapsedMs] into the
     * ring. Starts at 4% and climbs along a cube curve, which the ear hears as
     * a steady rise; reaches 1 at [rampMs]. No ramp means full volume at once.
     */
    fun rampLevel(elapsedMs: Long, rampMs: Long): Float {
        if (rampMs <= 0) return 1f
        val p = (elapsedMs.toFloat() / rampMs).coerceIn(0f, 1f)
        return (0.04f + 0.96f * p.toDouble().pow(3.0).toFloat()).coerceIn(0f, 1f)
    }

    /** Clock text for an alarm's time. */
    fun clock(hour: Int, minute: Int, use24h: Boolean): String =
        if (use24h) String.format(java.util.Locale.UK, "%02d:%02d", hour, minute)
        else String.format(java.util.Locale.UK, "%d:%02d", if (hour % 12 == 0) 12 else hour % 12, minute)

    fun ampm(hour: Int) = if (hour < 12) "AM" else "PM"
}

enum class SoundCat(val label: String) { GENTLE("Gentle"), NORMAL("Classic"), LOUD("Loud"), FUNNY("Funny"), PROGRESSIVE("Builds up") }

/** A bundled alarm sound. [loopFromMs] is where it loops back to (progressive sounds loop only their loud end). */
data class AlarmSound(val key: String, val name: String, val cat: SoundCat, val res: Int, val loopFromMs: Long, val about: String)

/** The sounds that ship with VoidOS. Sources and licences: docs/alarm-sounds.md. */
object AlarmSounds {
    const val DEFAULT = "sunrise"
    val all = listOf(
        AlarmSound("dawn_chorus", "Dawn chorus", SoundCat.GENTLE, R.raw.alarm_dawn_chorus, 0L, "Birds at first light"),
        AlarmSound("singing_bowl", "Singing bowl", SoundCat.GENTLE, R.raw.alarm_singing_bowl, 0L, "One long, soft ring"),
        AlarmSound("music_box", "Music box", SoundCat.GENTLE, R.raw.alarm_music_box, 0L, "A little wind-up tune"),
        AlarmSound("wind_chimes", "Wind chimes", SoundCat.GENTLE, R.raw.alarm_wind_chimes, 0L, "Chimes in a breeze"),
        AlarmSound("twin_bell", "Twin bell clock", SoundCat.NORMAL, R.raw.alarm_twin_bell, 0L, "The old wind-up clock"),
        AlarmSound("digital", "Digital clock", SoundCat.NORMAL, R.raw.alarm_digital, 0L, "Bedside clock beeps"),
        AlarmSound("rotary_phone", "Rotary phone", SoundCat.NORMAL, R.raw.alarm_rotary_phone, 0L, "An old phone ringing"),
        AlarmSound("cuckoo", "Cuckoo clock", SoundCat.NORMAL, R.raw.alarm_cuckoo, 0L, "Cuckoo, cuckoo"),
        AlarmSound("school_bell", "School bell", SoundCat.LOUD, R.raw.alarm_school_bell, 0L, "End of break"),
        AlarmSound("fog_horn", "Fog horn", SoundCat.LOUD, R.raw.alarm_fog_horn, 0L, "Deep and long"),
        AlarmSound("air_raid", "Air raid siren", SoundCat.LOUD, R.raw.alarm_air_raid, 0L, "Rising and falling siren"),
        AlarmSound("air_horn", "Air horn", SoundCat.LOUD, R.raw.alarm_air_horn, 0L, "Three blasts, again and again"),
        AlarmSound("rooster", "Rooster", SoundCat.FUNNY, R.raw.alarm_rooster, 0L, "Cock-a-doodle-doo"),
        AlarmSound("goat", "Screaming goat", SoundCat.FUNNY, R.raw.alarm_goat, 0L, "Exactly what it says"),
        AlarmSound("ducks", "Duck parade", SoundCat.FUNNY, R.raw.alarm_ducks, 0L, "Quacks and squeaky toys"),
        AlarmSound("bugle", "Bugle call", SoundCat.FUNNY, R.raw.alarm_bugle, 0L, "Reveille, soldier"),
        AlarmSound("slide_whistle", "Slide whistle", SoundCat.FUNNY, R.raw.alarm_slide_whistle, 0L, "Wheee-ooo"),
        AlarmSound("sunrise", "Sunrise", SoundCat.PROGRESSIVE, R.raw.alarm_sunrise, 68_000L, "Birds, chimes, music box, then the bell"),
        AlarmSound("escalation", "Escalation", SoundCat.PROGRESSIVE, R.raw.alarm_escalation, 29_834L, "Duck, rooster, bugle, then air horn"),
        AlarmSound("pulse", "Pulse", SoundCat.PROGRESSIVE, R.raw.alarm_pulse, 48_051L, "Soft beeps that speed up and get loud"),
    )

    fun byKey(key: String): AlarmSound = all.firstOrNull { it.key == key } ?: all.first { it.key == DEFAULT }
}

/** What is ringing right now. */
data class Ringing(val alarm: Alarm, val since: Long, val snoozed: Int = 0)

/**
 * The alarm list and its schedule. Every alarm that is on is handed to
 * AlarmManager.setAlarmClock: the exact, wake-from-idle alarm Android keeps
 * for clocks, which also shows the alarm icon in the status bar. The alarms
 * are put back after a reboot, a time or time-zone change and an app update
 * ([AlarmBootReceiver]).
 */
object Alarms {
    val list = mutableStateOf<List<Alarm>>(emptyList())
    val ringing = mutableStateOf<Ringing?>(null)
    private var loaded = false

    /** Ring for at most this long, then stop and leave a missed-alarm note. */
    const val RING_LIMIT_MS = 15 * 60_000L
    val zone: ZoneId get() = ZoneId.systemDefault()

    private fun prefs(c: Context) = c.getSharedPreferences("voidos_alarms", Context.MODE_PRIVATE)

    fun load(c: Context, force: Boolean = false) {
        if (loaded && !force) return
        loaded = true
        list.value = runCatching {
            val a = JSONArray(prefs(c).getString("alarms", "[]"))
            List(a.length()) { Alarm.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList()).sortedWith(compareBy({ it.hour }, { it.minute }))
    }

    private fun save(c: Context) {
        val a = JSONArray(); list.value.forEach { a.put(it.toJson()) }
        prefs(c).edit().putString("alarms", a.toString()).apply()
    }

    fun newId(): Int = (list.value.maxOfOrNull { it.id } ?: 0) + 1

    /** Adds or replaces (same id), saves and reschedules. Editing an alarm clears its snooze and skip. */
    fun put(c: Context, a: Alarm) {
        load(c)
        val clean = a.copy(snoozeAt = 0L, skipUntil = 0L)
        list.value = (list.value.filter { it.id != a.id } + clean).sortedWith(compareBy({ it.hour }, { it.minute }))
        save(c); schedule(c, clean)
    }

    fun setEnabled(c: Context, id: Int, on: Boolean) = update(c, id) { it.copy(enabled = on, snoozeAt = 0L, skipUntil = 0L) }

    /** Skip the next ring of a repeating alarm (or undo the skip). */
    fun skipNext(c: Context, id: Int, skip: Boolean) = update(c, id) { a ->
        if (!skip) a.copy(skipUntil = 0L) else {
            val next = AlarmMath.trigger(a.copy(skipUntil = 0L, snoozeAt = 0L), System.currentTimeMillis(), zone) ?: return@update a
            a.copy(skipUntil = next + 1, snoozeAt = 0L)
        }
    }

    fun remove(c: Context, id: Int) {
        load(c)
        list.value = list.value.filter { it.id != id }
        save(c); cancel(c, id)
    }

    fun get(id: Int) = list.value.firstOrNull { it.id == id }

    private fun update(c: Context, id: Int, f: (Alarm) -> Alarm) {
        load(c)
        var changed: Alarm? = null
        list.value = list.value.map { if (it.id == id) f(it).also { n -> changed = n } else it }
        save(c)
        changed?.let { schedule(c, it) }
    }

    /** Puts every alarm back in AlarmManager. Safe to call any time. */
    fun rescheduleAll(c: Context) {
        load(c)
        // a snooze that ran out while the tablet was off is dropped
        val now = System.currentTimeMillis()
        if (list.value.any { it.snoozeAt in 1..now }) {
            list.value = list.value.map { if (it.snoozeAt in 1..now) it.copy(snoozeAt = 0L) else it }
            save(c)
        }
        list.value.forEach { schedule(c, it) }
    }

    fun schedule(c: Context, a: Alarm) {
        val at = AlarmMath.trigger(a, System.currentTimeMillis(), zone)
        if (at == null) { cancel(c, a.id); return }
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val show = PendingIntent.getActivity(
            c, 7000 + a.id, Intent(c, MainActivity::class.java).putExtra("open", "alarms"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching { am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi(c, a.id)) }
            .onFailure { runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c, a.id)) } }
    }

    private fun cancel(c: Context, id: Int) {
        runCatching { c.getSystemService(AlarmManager::class.java)?.cancel(pi(c, id)) }
    }

    fun pi(c: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
        c, 8000 + id, Intent(c, AlarmReceiver::class.java).setAction("com.andysocial.voidos.ALARM").putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * AlarmManager fired [id]. Clears its snooze, works out its next ring
     * (a one-off alarm turns itself off) and returns the alarm to ring, or
     * null when it should not ring (deleted, off, or skipped).
     */
    fun fire(c: Context, id: Int, nowMs: Long = System.currentTimeMillis()): Alarm? {
        load(c)
        val a = get(id) ?: return null
        if (!a.enabled) return null
        if (a.skipUntil > nowMs) { schedule(c, a); return null }
        val after = if (a.repeats) a.copy(snoozeAt = 0L, skipUntil = 0L) else a.copy(enabled = false, snoozeAt = 0L, skipUntil = 0L)
        list.value = list.value.map { if (it.id == id) after else it }
        save(c)
        // a repeating alarm is set again straight away, so a crash mid-ring can't lose tomorrow's
        if (after.repeats) schedule(c, after) else cancel(c, id)
        return a
    }

    /** Snoozes what is ringing: rings again in its snooze length. Returns the moment, or null when it can't snooze. */
    fun snooze(c: Context, nowMs: Long = System.currentTimeMillis()): Long? {
        val r = ringing.value ?: return null
        val mins = r.alarm.snoozeMin.takeIf { it > 0 } ?: return null
        val at = nowMs + mins * 60_000L
        load(c)
        val base = get(r.alarm.id) ?: r.alarm.copy(enabled = false)
        // a snoozed one-off alarm has to be on for its snooze to fire
        val snoozed = base.copy(enabled = true, snoozeAt = at)
        list.value = (list.value.filter { it.id != snoozed.id } + snoozed).sortedWith(compareBy({ it.hour }, { it.minute }))
        save(c); schedule(c, snoozed)
        ringing.value = null
        return at
    }

    /** Stops what is ringing. (A one-off alarm already turned itself off when it fired.) */
    fun dismiss() {
        ringing.value = null
    }

    /** Cancel a snooze from the list ("Snoozed until 7:39" > stop). */
    fun cancelSnooze(c: Context, id: Int) = update(c, id) {
        // a one-off alarm is only on while snoozed, so it goes back off
        if (it.repeats) it.copy(snoozeAt = 0L) else it.copy(snoozeAt = 0L, enabled = false)
    }
}

/** AlarmManager's alarm went off (hand it to the ringing service), or a notification button was pressed. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Alarms.load(c)
        val id = i.getIntExtra("id", -1)
        when (i.action) {
            AlarmService.ACTION_SNOOZE -> AlarmService.snooze(c)
            AlarmService.ACTION_DISMISS -> AlarmService.dismiss(c)
            AlarmService.ACTION_CANCEL_SNOOZE -> AlarmService.cancelSnooze(c, id)
            else -> if (id >= 0) AlarmService.start(c, id)
        }
    }
}

/** Puts the alarms back after a reboot, a clock or time-zone change, or an update of VoidOS. */
class AlarmBootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Alarms.load(c, force = true)
        Alarms.rescheduleAll(c)
    }
}
