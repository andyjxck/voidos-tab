package com.andysocial.voidos

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.View

/**
 * Every touch vibration VoidOS makes goes through here, so they are all the same gentle kind.
 *
 * Android's own [View.performHapticFeedback] LONG_PRESS (and the one Compose plays on a long click)
 * is the device's full long-press pattern, a strong buzz; that's what the drawers, the wheel and long
 * presses used. These are short, light one-shots instead: at a low strength when the motor can vary its strength, and kept
 * very short when it can't. Android's "Touch vibration" setting still switches them all off.
 */
object Haptics {
    enum class Kind(val ms: Long, val amplitude: Int, val fixedMs: Long) {
        /** Sliding from one row to the next, a PIN key: barely there. */
        TICK(ms = 7, amplitude = 45, fixedMs = 6),
        /** A hold that took (picked up a row, armed a close, a long press): a soft knock. */
        PRESS(ms = 12, amplitude = 80, fixedMs = 10),
    }

    /** The strongest any touch vibration may be, of 255; Android's DEFAULT_AMPLITUDE is the motor's full default. */
    const val MAX_AMPLITUDE = 90
    /** The longest any touch vibration may run, in ms. Android's long-press fallback runs about 50 ms or more. */
    const val MAX_MS = 15L

    fun effect(kind: Kind, amplitudeControl: Boolean): VibrationEffect =
        if (amplitudeControl) VibrationEffect.createOneShot(kind.ms, kind.amplitude)
        else VibrationEffect.createOneShot(kind.fixedMs, VibrationEffect.DEFAULT_AMPLITUDE)

    private val ATTRS = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    fun tick(view: View) = play(view.context, Kind.TICK)
    fun press(view: View) = play(view.context, Kind.PRESS)

    fun play(ctx: Context, kind: Kind) {
        runCatching {
            if (Settings.System.getInt(ctx.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return
            val v = ctx.getSystemService(Vibrator::class.java) ?: return
            if (!v.hasVibrator()) return
            v.vibrate(effect(kind, v.hasAmplitudeControl()), ATTRS)
        }
    }
}

/** Compose's own long-click buzz is the strong long-press pattern; this turns it off, so VoidOS's light [Haptics] are the only ones. */
private object NoComposeHaptics : androidx.compose.ui.hapticfeedback.HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: androidx.compose.ui.hapticfeedback.HapticFeedbackType) {}
}

/** Wraps every VoidOS window's content: no Compose-made vibrations inside. */
@androidx.compose.runtime.Composable
fun QuietHaptics(content: @androidx.compose.runtime.Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalHapticFeedback provides NoComposeHaptics, content = content)
}
