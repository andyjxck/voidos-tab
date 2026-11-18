package com.andysocial.voidos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

/**
 * Rings an alarm. Runs in the foreground so Android can't stop it mid-ring,
 * holds the CPU awake, wakes the screen and opens [AlarmActivity] over
 * everything, including the lock screens. The alarm plays on the alarm
 * stream (so Do Not Disturb lets it through), set to the alarm's own volume
 * while it rings and put back afterwards, and pauses any music while it rings.
 */
class AlarmService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var wake: PowerManager.WakeLock? = null
    private var focus: AudioFocusRequest? = null
    private var savedVolume = -1
    private var startedAt = 0L
    private var target = 1f
    private var rampMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FIRE -> ring(intent.getIntExtra("id", -1))
            ACTION_STOP -> finish()
            ACTION_PREVIEW_STOP -> finish()
            else -> if (Alarms.ringing.value == null) finish()
        }
        return START_NOT_STICKY
    }

    private fun ring(id: Int) {
        // foreground first: Android gives a started foreground service five seconds to say so
        startForeground(NOTE_RING, ringingNote(Alarms.get(id)))
        val a = Alarms.fire(this, id)
        if (a == null) {
            if (Alarms.ringing.value == null) finish()
            else startForeground(NOTE_RING, ringingNote(Alarms.ringing.value?.alarm))
            return
        }
        stopSound()
        Alarms.ringing.value = Ringing(a, System.currentTimeMillis())
        startForeground(NOTE_RING, ringingNote(a))
        getSystemService(NotificationManager::class.java)?.cancel(NOTE_SNOOZED)
        wake = wake ?: getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voidos:alarm")?.apply { acquire(Alarms.RING_LIMIT_MS + 60_000) }
        wakeScreen()
        play(a)
        runCatching { com.andysocial.voidos.ui.Idle.touch() }
        runCatching {
            startActivity(Intent(this, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION))
        }
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, Alarms.RING_LIMIT_MS)
    }

    private val timeout = Runnable {
        val r = Alarms.ringing.value
        Alarms.dismiss()
        if (r != null) note(NOTE_SNOOZED, base("Missed alarm", (r.alarm.label.ifBlank { "Alarm" }) + " rang for 15 minutes").build())
        finish()
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        runCatching {
            getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "voidos:alarmscreen")
                ?.acquire(10_000)
        }
    }

    private fun play(a: Alarm) {
        val sound = AlarmSounds.byKey(a.sound)
        val am = getSystemService(AudioManager::class.java)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        if (am != null) {
            runCatching {
                val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
                am.setStreamVolume(AudioManager.STREAM_ALARM, (max * a.volume).toInt().coerceIn(1, max), 0)
            }
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).build()
            runCatching { am.requestAudioFocus(focus!!) }
        }
        target = 1f
        // a sound that builds up on its own doesn't get a second ramp on top
        rampMs = if (sound.cat == SoundCat.PROGRESSIVE) 0L else a.rampSec * 1000L
        startedAt = SystemClock.elapsedRealtime()
        player = startPlayer(this, sound, attrs, AlarmMath.rampLevel(0, rampMs)) ?: run {
            // the bundled sound failed to load: fall back to the tablet's own alarm tone
            fallback(attrs); null
        }
        main.removeCallbacks(rampTick)
        if (rampMs > 0) main.post(rampTick)
    }

    private var tone: android.media.Ringtone? = null
    private fun fallback(attrs: AudioAttributes) {
        val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM) ?: return
        tone = runCatching { android.media.RingtoneManager.getRingtone(this, uri) }.getOrNull()?.apply {
            audioAttributes = attrs
            runCatching { play() }
        }
    }

    private val rampTick = object : Runnable {
        override fun run() {
            val p = player ?: return
            val lvl = AlarmMath.rampLevel(SystemClock.elapsedRealtime() - startedAt, rampMs) * target
            runCatching { p.setVolume(lvl, lvl) }
            if (lvl < target) main.postDelayed(this, 200)
        }
    }

    private fun stopSound() {
        main.removeCallbacks(rampTick)
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        runCatching { tone?.stop() }; tone = null
        val am = getSystemService(AudioManager::class.java)
        if (am != null) {
            if (savedVolume >= 0) runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) }
            savedVolume = -1
            focus?.let { runCatching { am.abandonAudioFocusRequest(it) } }
            focus = null
        }
    }

    private fun finish() {
        main.removeCallbacks(timeout)
        stopSound()
        wake?.let { if (it.isHeld) runCatching { it.release() } }
        wake = null
        @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        stopSound()
        wake?.let { if (it.isHeld) runCatching { it.release() } }
        super.onDestroy()
    }

    // --------------------------------------------------------------- notifications

    private fun base(title: String, text: String): Notification.Builder {
        channel(this)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.vi_bell)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
    }

    private fun ringingNote(a: Alarm?): Notification {
        val full = PendingIntent.getActivity(this, 2, Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = base(a?.label?.ifBlank { null } ?: "Alarm", a?.let { AlarmMath.clock(it.hour, it.minute, VoidApp.instance.store.use24h) } ?: "")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (a != null && a.snoozeMin > 0) b.addAction(Notification.Action.Builder(null, "Snooze ${a.snoozeMin} min", action(this, ACTION_SNOOZE)).build())
        b.addAction(Notification.Action.Builder(null, "Stop", action(this, ACTION_DISMISS)).build())
        return b.build()
    }

    private fun note(id: Int, n: Notification) = runCatching { getSystemService(NotificationManager::class.java)?.notify(id, n) }

    companion object {
        const val CHANNEL = "voidos_alarms"
        const val NOTE_RING = 4100
        const val NOTE_SNOOZED = 4101
        const val ACTION_FIRE = "com.andysocial.voidos.alarm.FIRE"
        const val ACTION_STOP = "com.andysocial.voidos.alarm.STOP"
        const val ACTION_PREVIEW_STOP = "com.andysocial.voidos.alarm.PREVIEW_STOP"
        const val ACTION_SNOOZE = "com.andysocial.voidos.alarm.SNOOZE"
        const val ACTION_DISMISS = "com.andysocial.voidos.alarm.DISMISS"
        const val ACTION_CANCEL_SNOOZE = "com.andysocial.voidos.alarm.CANCEL_SNOOZE"

        fun start(c: Context, id: Int) {
            val i = Intent(c, AlarmService::class.java).setAction(ACTION_FIRE).putExtra("id", id)
            runCatching { c.startForegroundService(i) }
        }

        private fun stopService(c: Context) {
            runCatching { c.startService(Intent(c, AlarmService::class.java).setAction(ACTION_STOP)) }
                .onFailure { runCatching { c.stopService(Intent(c, AlarmService::class.java)) } }
        }

        /** Snooze button: silence now, ring again after the alarm's snooze length. */
        fun snooze(c: Context) {
            val r = Alarms.ringing.value ?: return
            val at = Alarms.snooze(c) ?: return dismiss(c)
            stopService(c)
            val clock = java.text.SimpleDateFormat(if (VoidApp.instance.store.use24h) "HH:mm" else "h:mm a", java.util.Locale.UK).format(java.util.Date(at))
            channel(c)
            val n = Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.vi_bell)
                .setContentTitle((r.alarm.label.ifBlank { "Alarm" }) + " snoozed")
                .setContentText("Rings again at $clock")
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Turn off", action(c, ACTION_CANCEL_SNOOZE, r.alarm.id)).build())
                .build()
            runCatching { c.getSystemService(NotificationManager::class.java)?.notify(NOTE_SNOOZED, n) }
        }

        /** Stop button. */
        fun dismiss(c: Context) {
            Alarms.dismiss()
            stopService(c)
        }

        fun cancelSnooze(c: Context, id: Int) {
            Alarms.cancelSnooze(c, id)
            runCatching { c.getSystemService(NotificationManager::class.java)?.cancel(NOTE_SNOOZED) }
        }

        private fun action(c: Context, act: String, id: Int = 0): PendingIntent = PendingIntent.getBroadcast(
            c, act.hashCode() + id, Intent(c, AlarmReceiver::class.java).setAction(act).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun channel(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) != null) return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }

        /**
         * Starts a player for [sound] on the given stream. Sounds that build up
         * loop only their loud end ([AlarmSound.loopFromMs]); the rest loop whole.
         */
        fun startPlayer(c: Context, sound: AlarmSound, attrs: AudioAttributes, volume: Float): MediaPlayer? = runCatching {
            val fd = c.resources.openRawResourceFd(sound.res)
            MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                fd.close()
                if (sound.loopFromMs <= 0) isLooping = true
                else setOnCompletionListener { mp -> runCatching { mp.seekTo(sound.loopFromMs.toInt()); mp.start() } }
                setVolume(volume, volume)
                prepare()
                start()
            }
        }.getOrNull()
    }
}

/** Plays a sound from the alarm editor, quietly, on the alarm stream. One at a time. */
object AlarmPreview {
    private var player: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    private val stopLater = Runnable { stop() }
    val playing = androidx.compose.runtime.mutableStateOf<String?>(null)

    fun toggle(c: Context, key: String, volume: Float) {
        if (playing.value == key) { stop(); return }
        stop()
        val sound = AlarmSounds.byKey(key)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        // progressive sounds start almost silent, so the preview jumps to where they get going
        player = AlarmService.startPlayer(c, sound, attrs, (volume * 0.7f).coerceIn(0.1f, 1f))?.also {
            if (sound.loopFromMs > 0) runCatching { it.seekTo((sound.loopFromMs - 8_000).coerceAtLeast(0).toInt()) }
        }
        if (player != null) {
            playing.value = key
            main.postDelayed(stopLater, 12_000)
        }
    }

    fun stop() {
        main.removeCallbacks(stopLater)
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        playing.value = null
    }
}
