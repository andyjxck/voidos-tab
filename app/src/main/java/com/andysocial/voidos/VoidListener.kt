package com.andysocial.voidos

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.mutableStateOf

/** What's playing right now in any app (YouTube, Spotify, the browser...). */
data class Playing(
    val pkg: String,
    val appName: String,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val speed: Float,
)

/** A notification as the hub shows it: which app, a title and one line. */
data class Notice(val key: String, val pkg: String, val appName: String, val title: String, val text: String, val time: Long)

/**
 * Notification access. Android only lets an app see other apps' media
 * sessions and notifications once it's a notification listener, which you
 * switch on once (System > Media & notifications, or the adb command in the
 * README). With it, VoidOS shows and controls whatever is playing and puts
 * recent notifications on the lock screen.
 */
class VoidListener : NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> Now.attach(this, list) }

    override fun onListenerConnected() {
        instance = this
        connected.value = true
        msm = getSystemService(MediaSessionManager::class.java)
        val me = ComponentName(this, VoidListener::class.java)
        runCatching {
            msm?.addOnActiveSessionsChangedListener(sessionsListener, me, main)
            Now.attach(this, msm?.getActiveSessions(me))
        }
        refreshNotices()
    }

    override fun onListenerDisconnected() {
        connected.value = false
        instance = null
        runCatching { msm?.removeOnActiveSessionsChangedListener(sessionsListener) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refreshNotices()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refreshNotices()

    internal fun refreshNotices() {
        val list = runCatching { activeNotifications?.toList() }.getOrNull() ?: return
        val pm = packageManager
        // apps switched off in Settings > Notifications: cleared as soon as they post
        list.filter { it.packageName in muted && it.isClearable }.forEach { runCatching { cancelNotification(it.key) } }
        notices.value = list.asSequence()
            .filter { it.packageName !in muted }
            .filter { it.isClearable && it.packageName != packageName }
            .filter { it.notification.category != android.app.Notification.CATEGORY_TRANSPORT }
            .mapNotNull { sbn ->
                val ex = sbn.notification.extras
                val title = ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: return@mapNotNull null
                val text = ex.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
                val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
                Notice(sbn.key, sbn.packageName, app, title, text, sbn.postTime)
            }
            .sortedByDescending { it.time }
            .take(12)
            .toList()
    }

    companion object {
        @Volatile private var instance: VoidListener? = null
        /** Packages whose notifications are switched off; kept in step by [Store]. */
        @Volatile var muted: Set<String> = emptySet()

        fun clearFrom(pkg: String) {
            val l = instance ?: return
            runCatching { l.activeNotifications.filter { it.packageName == pkg && it.isClearable }.forEach { l.cancelNotification(it.key) } }
            runCatching { l.refreshNotices() }
        }

        /** Opens what a notification points at (as tapping it in Android's shade would). */
        fun open(key: String) {
            val l = instance ?: return
            runCatching {
                val sbn = l.activeNotifications.firstOrNull { it.key == key } ?: return
                sbn.notification.contentIntent?.send()
                if (sbn.notification.flags and android.app.Notification.FLAG_AUTO_CANCEL != 0) l.cancelNotification(key)
            }
        }

        fun clearAll() { runCatching { instance?.cancelAllNotifications() } }

        val connected = mutableStateOf(false)
        val notices = mutableStateOf<List<Notice>>(emptyList())

        fun enabled(c: Context): Boolean {
            val flat = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.split(':').any { ComponentName.unflattenFromString(it)?.packageName == c.packageName }
        }

        const val ADB_ALLOW = "./adb shell cmd notification allow_listener com.andysocial.voidos/com.andysocial.voidos.VoidListener"
    }
}

/** The media session VoidOS is following, picked as the one playing (else the newest). */
object Now {
    val playing = mutableStateOf<Playing?>(null)
    private var controller: MediaController? = null
    private val cb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() { controller = null; playing.value = null }
    }
    private var ctx: Context? = null

    fun attach(c: Context, list: List<MediaController>?) {
        ctx = c.applicationContext
        val pick = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        if (pick?.sessionToken == controller?.sessionToken) { publish(); return }
        runCatching { controller?.unregisterCallback(cb) }
        controller = pick
        runCatching { pick?.registerCallback(cb, Handler(Looper.getMainLooper())) }
        publish()
    }

    private fun publish() {
        val mc = controller ?: run { playing.value = null; return }
        val md = mc.metadata
        val st = mc.playbackState
        val pm = ctx?.packageManager
        val app = runCatching { pm!!.getApplicationLabel(pm.getApplicationInfo(mc.packageName, 0)).toString() }.getOrDefault(mc.packageName)
        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        playing.value = Playing(
            pkg = mc.packageName,
            appName = app,
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "",
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: "",
            art = art,
            playing = st?.state == PlaybackState.STATE_PLAYING,
            positionMs = st?.position ?: 0L,
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            updatedAt = st?.lastPositionUpdateTime ?: android.os.SystemClock.elapsedRealtime(),
            speed = st?.playbackSpeed ?: 1f,
        )
    }

    /** Where playback is now, moving on from the last report while playing. */
    fun position(p: Playing): Long {
        if (!p.playing) return p.positionMs
        val moved = ((android.os.SystemClock.elapsedRealtime() - p.updatedAt) * p.speed).toLong()
        return (p.positionMs + moved).coerceIn(0L, if (p.durationMs > 0) p.durationMs else Long.MAX_VALUE)
    }

    fun playPause() { val mc = controller ?: return; if (mc.playbackState?.state == PlaybackState.STATE_PLAYING) mc.transportControls.pause() else mc.transportControls.play() }
    fun next() { controller?.transportControls?.skipToNext() }
    fun prev() { controller?.transportControls?.skipToPrevious() }
    fun seekTo(ms: Long) { controller?.transportControls?.seekTo(ms) }
    fun skip(by: Long) { val p = playing.value ?: return; seekTo((position(p) + by).coerceAtLeast(0L)) }
    fun open(c: Context) {
        val pkg = controller?.packageName ?: return
        controller?.sessionActivity?.let { runCatching { it.send(); return } }
        c.packageManager.getLaunchIntentForPackage(pkg)?.let { runCatching { c.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }
}
