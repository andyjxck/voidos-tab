package com.andysocial.voidos

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.PowerManager
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * A kitchen timer. While running, [endAt] is the wall-clock finish time; while
 * paused, [left] holds what's left. [rang] is set once it has gone off.
 */
data class KTimer(
    val id: Int,
    val label: String,
    val totalMs: Long,
    val endAt: Long,
    val left: Long,
    val running: Boolean,
    val rang: Boolean = false,
    val colour: Int = 0,
) {
    fun remaining(now: Long = System.currentTimeMillis()) = if (running) (endAt - now).coerceAtLeast(0) else left
}

/**
 * Several kitchen timers at once. Each running timer has an exact alarm with
 * Android's AlarmManager, so it goes off on time even with the screen off or
 * while you're in another app; the sound loops until you press Stop in VoidOS.
 * Timers are saved, so they survive VoidOS being restarted.
 */
object KTimers {
    val list = mutableStateOf<List<KTimer>>(emptyList())
    /** Timers that have finished and are ringing. */
    val ringing = mutableStateOf<List<KTimer>>(emptyList())
    private var tone: Ringtone? = null
    private var loaded = false

    private fun prefs(c: Context) = c.getSharedPreferences("voidos_timers", Context.MODE_PRIVATE)

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        val raw = prefs(c).getString("list", "[]")!!
        list.value = runCatching {
            val a = JSONArray(raw)
            List(a.length()) {
                val o = a.getJSONObject(it)
                KTimer(o.getInt("id"), o.getString("label"), o.getLong("total"), o.getLong("end"), o.getLong("left"), o.getBoolean("run"), o.optBoolean("rang"), o.optInt("col"))
            }
        }.getOrDefault(emptyList())
        // anything that finished while VoidOS wasn't running rings now
        check(c)
    }

    private fun save(c: Context) {
        val a = JSONArray()
        list.value.forEach {
            a.put(JSONObject().put("id", it.id).put("label", it.label).put("total", it.totalMs).put("end", it.endAt)
                .put("left", it.left).put("run", it.running).put("rang", it.rang).put("col", it.colour))
        }
        prefs(c).edit().putString("list", a.toString()).apply()
    }

    fun add(c: Context, label: String, ms: Long, start: Boolean = true) {
        val id = (list.value.maxOfOrNull { it.id } ?: 0) + 1
        val now = System.currentTimeMillis()
        val t = KTimer(id, label, ms, now + ms, ms, start, colour = id % 6)
        list.value = list.value + t
        if (start) schedule(c, t)
        save(c)
    }

    fun toggle(c: Context, id: Int) = update(c, id) { t ->
        val now = System.currentTimeMillis()
        if (t.running) t.copy(running = false, left = t.remaining(now)).also { cancel(c, id) }
        else {
            val base = if (t.rang || t.left <= 0) t.totalMs else t.left
            t.copy(running = true, endAt = now + base, left = base, rang = false).also { schedule(c, it) }
        }
    }

    fun addTime(c: Context, id: Int, ms: Long) = update(c, id) { t ->
        val now = System.currentTimeMillis()
        if (t.running) t.copy(endAt = maxOf(t.endAt, now) + ms, totalMs = t.totalMs + ms).also { schedule(c, it) }
        else if (t.rang) t.copy(running = true, rang = false, endAt = now + ms, left = ms, totalMs = ms).also { schedule(c, it) }
        else t.copy(left = (t.left + ms).coerceAtLeast(1000), totalMs = (t.totalMs + ms).coerceAtLeast(1000))
    }

    fun remove(c: Context, id: Int) {
        cancel(c, id)
        list.value = list.value.filter { it.id != id }
        ringing.value = ringing.value.filter { it.id != id }
        if (ringing.value.isEmpty()) stopSound()
        save(c)
    }

    /** Stop every ringing timer; finished timers stay on the board to restart. */
    fun silence(c: Context) {
        ringing.value = emptyList()
        stopSound()
        save(c)
    }

    private fun update(c: Context, id: Int, f: (KTimer) -> KTimer) {
        list.value = list.value.map { if (it.id == id) f(it) else it }
        ringing.value = ringing.value.filter { r -> list.value.any { it.id == r.id && it.rang } }
        if (ringing.value.isEmpty()) stopSound()
        save(c)
    }

    /** Called by the alarm and by the screen's ticker: ring anything that's due. */
    fun check(c: Context) {
        val now = System.currentTimeMillis()
        val due = list.value.filter { it.running && it.endAt <= now + 250 }
        if (due.isEmpty()) return
        list.value = list.value.map { t -> if (due.any { it.id == t.id }) t.copy(running = false, left = 0, rang = true) else t }
        ringing.value = (ringing.value + due.map { d -> list.value.first { it.id == d.id } }).distinctBy { it.id }
        save(c)
        startSound(c)
    }

    private fun startSound(c: Context) {
        val store = runCatching { VoidApp.instance.store }.getOrNull()
        if (store != null && !store.timerSound) return
        if (tone?.isPlaying == true) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        tone = runCatching { RingtoneManager.getRingtone(c.applicationContext, uri) }.getOrNull()?.apply {
            audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            runCatching { isLooping = true }
            play()
        }
        // wake the screen so the stop button is there
        runCatching {
            val pm = c.getSystemService(PowerManager::class.java)
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "voidos:timer")
            wl.acquire(10_000)
        }
        // bring VoidOS to the front so the stop screen shows
        runCatching {
            c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    private fun stopSound() { runCatching { tone?.stop() }; tone = null }

    private fun pi(c: Context, id: Int) = PendingIntent.getBroadcast(
        c, 9000 + id, Intent(c, TimerReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun schedule(c: Context, t: KTimer) {
        val am = c.getSystemService(AlarmManager::class.java)
        runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.endAt, pi(c, t.id)) }
    }

    private fun cancel(c: Context, id: Int) {
        runCatching { c.getSystemService(AlarmManager::class.java).cancel(pi(c, id)) }
    }
}

class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        KTimers.load(c)
        KTimers.check(c)
    }
}
