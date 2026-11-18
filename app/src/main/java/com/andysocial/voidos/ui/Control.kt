package com.andysocial.voidos.ui

import android.app.NotificationManager
import com.andysocial.voidos.Radio
import com.andysocial.voidos.Su
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andysocial.voidos.MainActivity
import com.andysocial.voidos.Now
import com.andysocial.voidos.Policy
import com.andysocial.voidos.Store
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** The tablet switches the control centre drives. Each one fails quietly and reports what it managed. */
object Tablet {
    private fun dpmSet(ctx: Context, key: String, v: String) =
        Policy.isOwner(ctx) && runCatching { Policy.dpm(ctx).setSystemSetting(Policy.admin(ctx), key, v) }.isSuccess

    private fun sysSet(ctx: Context, key: String, v: Int): Boolean =
        dpmSet(ctx, key, v.toString()) ||
            (Settings.System.canWrite(ctx) && runCatching { Settings.System.putInt(ctx.contentResolver, key, v) }.getOrDefault(false))

    fun brightness(ctx: Context): Float =
        runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)

    fun autoBrightness(ctx: Context): Boolean =
        runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) == 1 }.getOrDefault(false)

    /** Sets the tablet's brightness. Falls back to this window only, when Android refuses the system setting. */
    fun setBrightness(act: MainActivity, v: Float) {
        val f = v.coerceIn(0f, 1f)
        if (autoBrightness(act)) sysSet(act, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
        val ok = sysSet(act, Settings.System.SCREEN_BRIGHTNESS, (4 + f * 251).toInt())
        act.window.attributes = act.window.attributes.apply { screenBrightness = if (ok) -1f else 0.02f + f * 0.98f }
    }

    fun setAutoBrightness(act: MainActivity, on: Boolean): Boolean {
        val ok = sysSet(act, Settings.System.SCREEN_BRIGHTNESS_MODE, if (on) 1 else 0)
        if (on) act.window.attributes = act.window.attributes.apply { screenBrightness = -1f }
        return ok
    }

    fun volume(ctx: Context): Float {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return 0f
        return am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }

    fun setVolume(ctx: Context, v: Float) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, (v.coerceIn(0f, 1f) * max).toInt(), 0) }
    }

    fun wifiOn(ctx: Context): Boolean = Radio.wifiOn(ctx)

    /** Device owner, then root. Blocks for up to 4 s: call off the main thread. */
    fun setWifi(ctx: Context, on: Boolean): Boolean = Radio.setWifi(ctx, on)

    fun btOn(): Boolean = Radio.btOn()

    /** The public call, then root. Blocks: call off the main thread. */
    fun setBt(on: Boolean): Boolean = Radio.setBt(on)

    /** A stream's volume, 0..1. */
    fun stream(ctx: Context, s: Int): Float {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return 0f
        return am.getStreamVolume(s).toFloat() / am.getStreamMaxVolume(s).coerceAtLeast(1)
    }

    fun setStream(ctx: Context, s: Int, v: Float) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        runCatching { am.setStreamVolume(s, (v.coerceIn(0f, 1f) * am.getStreamMaxVolume(s)).toInt(), 0) }
    }

    /** Android's Night Light (warmer screen). A secure setting: VoidOS writes it itself, or through root. */
    fun nightLight(ctx: Context): Boolean =
        runCatching { Settings.Secure.getInt(ctx.contentResolver, "night_display_activated") == 1 }.getOrDefault(false)

    fun setNightLight(ctx: Context, on: Boolean): Boolean {
        val v = if (on) 1 else 0
        if (runCatching { Settings.Secure.putInt(ctx.contentResolver, "night_display_activated", v) }.getOrDefault(false)) return true
        return Su.ok("settings put secure night_display_activated $v")
    }

    fun dndAllowed(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    /** Gives VoidOS Do Not Disturb access through root, so the user never has to. Blocking. */
    fun grantDnd(ctx: Context): Boolean =
        dndAllowed(ctx) || (Su.ok("cmd notification allow_dnd ${ctx.packageName}") && dndAllowed(ctx))

    fun dndOn(ctx: Context): Boolean = dndMode(ctx) != NotificationManager.INTERRUPTION_FILTER_ALL

    fun dndMode(ctx: Context): Int =
        ctx.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL

    /** Do not disturb lets alarms and timers through. Asks root for access when it's missing. Blocking. */
    fun setDnd(ctx: Context, on: Boolean): Boolean =
        setDndMode(ctx, if (on) NotificationManager.INTERRUPTION_FILTER_ALARMS else NotificationManager.INTERRUPTION_FILTER_ALL)

    /** One of NotificationManager.INTERRUPTION_FILTER_*. */
    fun setDndMode(ctx: Context, mode: Int): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return false
        if (!grantDnd(ctx)) return false
        return runCatching { nm.setInterruptionFilter(mode) }.isSuccess
    }

    /** Turns the screen off. Needs device owner (or admin). */
    fun screenOff(ctx: Context): Boolean = runCatching { Policy.dpm(ctx).lockNow() }.isSuccess
}

/** Settings deep links the control centre does not cover. */
fun openAndroidSettings(ctx: Context, action: String = Settings.ACTION_SETTINGS) {
    runCatching { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
