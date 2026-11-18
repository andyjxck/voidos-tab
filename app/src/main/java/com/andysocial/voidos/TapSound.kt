package com.andysocial.voidos

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.view.MotionEvent
import kotlin.math.hypot

/**
 * The tap sound: a short soft tick (res/raw/tap.wav) played on every tap, at the
 * system's touch-sound volume. Settings > Tap sound turns it off.
 */
object TapSound {
    private var pool: SoundPool? = null
    private var id = 0
    private var ready = false
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L

    fun init(ctx: Context) {
        if (pool != null) return
        val p = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
        ).build()
        p.setOnLoadCompleteListener { _, _, status -> ready = status == 0 }
        id = p.load(ctx, R.raw.tap, 1)
        pool = p
    }

    fun play() { if (ready) pool?.play(id, 0.6f, 0.6f, 1, 0, 1f) }

    /** Watches the touches going into the activity; a short touch that hardly moved is a tap. */
    fun onTouch(ev: MotionEvent, on: Boolean, slop: Float) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; downT = ev.eventTime }
            MotionEvent.ACTION_UP -> if (on && isTap(downX, downY, downT, ev.x, ev.y, ev.eventTime, slop)) play()
        }
    }

    fun isTap(x0: Float, y0: Float, t0: Long, x1: Float, y1: Float, t1: Long, slop: Float) =
        hypot(x1 - x0, y1 - y0) <= slop && t1 - t0 in 0..450
}
