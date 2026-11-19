package com.andysocial.voidos

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.andysocial.voidos.ui.KeyguardPad
import com.andysocial.voidos.ui.PowerMenu
import com.andysocial.voidos.ui.VolumeHud

/**
 * VoidOS's own system surfaces in place of Android's white ones. One accessibility service
 * (switched on by VoidOS itself through root, and by the system zip at boot) does three jobs,
 * because accessibility overlays are the only windows an app may draw above the lock screen
 * and the status bar:
 *
 *  - Volume: it filters the volume keys, changes the volume itself with no Android UI, and
 *    shows VoidOS's sliders. Android's panel never opens, because the key never reaches it.
 *  - Power: holding power still makes Android open its power dialog, and Android tells every
 *    app first (ACTION_CLOSE_SYSTEM_DIALOGS, reason "globalactions"). VoidOS closes Android's
 *    dialog on that signal and shows its own menu: lock, restart, power off, through root.
 *  - PIN: when the tablet has an Android PIN, Android's lock screen stays underneath, because
 *    it holds the key to the tablet's encrypted storage. VoidOS draws its own PIN pad above it,
 *    then types the PIN into Android's hidden pad through root. Android checks it and counts
 *    wrong tries, so there's no way round it: if VoidOS's pad ever fails, Android's is under it.
 */
class VoidKeysService : AccessibilityService(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    private val main = Handler(Looper.getMainLooper())
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val kg by lazy { getSystemService(KEYGUARD_SERVICE) as KeyguardManager }

    private var volView: View? = null
    private var powerView: View? = null
    private var lockView: View? = null
    private val hideVol = Runnable { removeVol() }

    override fun onCreate() {
        super.onCreate()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        registry.currentState = Lifecycle.State.RESUMED
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching { registerReceiver(rx, f) }
        connected.value = true
        main.postDelayed({ syncLock() }, 300)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(rx) }
        removeVol(); removePower(); removeLock()
        instance = null
        connected.value = false
        registry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // backup for the power dialog: if Android's opens anyway, close it and show ours
        val e = event ?: return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && isStockPowerDialog(e.packageName?.toString(), e.className?.toString())) {
            if (store().voidPower) { closeSystemDialogs(); showPower() }
        }
    }

    override fun onInterrupt() {}

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> {
                    val reason = i.getStringExtra("reason")
                    if (reason == "globalactions" && store().voidPower) {
                        closeSystemDialogs()
                        main.post { showPower() }
                    }
                }
                Intent.ACTION_SCREEN_OFF -> { removePower(); removeVol(); main.postDelayed({ syncLock() }, 150) }
                Intent.ACTION_SCREEN_ON -> syncLock()
                Intent.ACTION_USER_PRESENT -> removeLock()
            }
        }
    }

    private fun store() = (application as VoidApp).store

    // ---- volume

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!store().voidVolume) return false
        val dir = volumeDirection(event.keyCode) ?: return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            val stream = volumeStream(audio.mode, ringing = audio.mode == AudioManager.MODE_RINGTONE)
            VolumeState.stream.intValue = stream
            runCatching { audio.adjustStreamVolume(stream, dir, 0) }
            VolumeState.bump.intValue++
            main.post { showVol() }
        }
        return true
    }

    private fun params(touch: Boolean, full: Boolean, gravity: Int = Gravity.CENTER) = WindowManager.LayoutParams(
        if (full) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
        if (full) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touch) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        this.gravity = gravity
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private fun compose(content: @androidx.compose.runtime.Composable () -> Unit): ComposeView = ComposeView(this).also { c ->
        c.setViewTreeLifecycleOwner(this)
        c.setViewTreeSavedStateRegistryOwner(this)
        c.setContent { content() }
    }

    private fun showVol() {
        main.removeCallbacks(hideVol)
        if (volView == null) {
            val v = compose { VolumeHud(audio, onTouch = { main.removeCallbacks(hideVol); main.postDelayed(hideVol, VOL_HIDE_MS) }) }
            runCatching { wm.addView(v, params(touch = true, full = false, gravity = Gravity.END or Gravity.CENTER_VERTICAL)) }.onSuccess { volView = v }
        }
        main.postDelayed(hideVol, VOL_HIDE_MS)
    }

    private fun removeVol() { volView?.let { runCatching { wm.removeView(it) } }; volView = null }

    // ---- power

    fun showPower() {
        if (powerView != null) return
        val v = compose {
            PowerMenu(
                onLock = { removePower(); lockNow() },
                onRestart = { removePower(); Thread { Su.run("svc power reboot || reboot") }.start() },
                onPowerOff = { removePower(); Thread { Su.run("svc power shutdown || reboot -p") }.start() },
                onDismiss = { removePower() },
            )
        }
        runCatching { wm.addView(v, params(touch = true, full = true)) }.onSuccess { powerView = v }
    }

    private fun removePower() { powerView?.let { runCatching { wm.removeView(it) } }; powerView = null }

    private fun lockNow() {
        if (android.os.Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)) return
        Thread { Su.run("input keyevent KEYCODE_SLEEP") }.start()
    }

    @Suppress("DEPRECATION")
    private fun closeSystemDialogs() = runCatching { sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) }

    // ---- PIN over Android's lock screen

    private fun syncLock() {
        val want = store().voidKeyguard && kg.isKeyguardLocked && kg.isDeviceSecure
        if (want) showLock() else removeLock()
    }

    private fun showLock() {
        if (lockView != null) return
        KeyguardState.reset()
        val v = compose { KeyguardPad(store(), onPin = { pin -> unlockWith(pin) }) }
        runCatching { wm.addView(v, params(touch = true, full = true)) }.onSuccess { lockView = v }
    }

    private fun removeLock() { lockView?.let { runCatching { wm.removeView(it) } }; lockView = null }

    private fun setLockTouchable(on: Boolean) {
        val v = lockView ?: return
        runCatching { wm.updateViewLayout(v, params(touch = on, full = true)) }
    }

    /** Hands the PIN to Android's hidden pad: Android checks it, VoidOS only types it. */
    private fun unlockWith(pin: String) {
        KeyguardState.busy.value = true
        setLockTouchable(false)
        Thread {
            for (c in keyguardTypeCommands(pin)) Su.run(c, timeoutS = 5)
            var unlocked = false
            for (i in 0 until 20) {
                Thread.sleep(100)
                if (!kg.isKeyguardLocked) { unlocked = true; break }
            }
            main.post {
                KeyguardState.busy.value = false
                if (unlocked) removeLock()
                else {
                    Thread { Su.run(keyguardClearCommand(), timeoutS = 5) }.start()
                    KeyguardState.wrong.intValue++
                    setLockTouchable(true)
                }
            }
        }.start()
    }

    companion object {
        @Volatile var instance: VoidKeysService? = null
        val connected = mutableStateOf(false)
        const val VOL_HIDE_MS = 2200L
        const val COMPONENT = "com.andysocial.voidos/com.andysocial.voidos.VoidKeysService"

        /** Pure: the volume key's direction, or null for any other key. */
        fun volumeDirection(keyCode: Int): Int? = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
            KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
            KeyEvent.KEYCODE_VOLUME_MUTE -> AudioManager.ADJUST_TOGGLE_MUTE
            else -> null
        }

        /** Pure: which volume the keys move. Ringing: the ring. In a call: the call. Otherwise media. */
        fun volumeStream(mode: Int, ringing: Boolean): Int = when {
            ringing -> AudioManager.STREAM_RING
            mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }

        /** Pure: is this window Android's own power dialog? */
        fun isStockPowerDialog(pkg: String?, cls: String?): Boolean =
            pkg == "com.android.systemui" && cls != null && cls.contains("GlobalActions", ignoreCase = true)

        /**
         * Pure: the root commands that type a PIN into Android's hidden lock screen: bring up its
         * PIN pad, type the digits, press Enter. Digits only, so nothing else can be typed.
         */
        fun keyguardTypeCommands(pin: String): List<String> {
            val digits = pin.filter { it in '0'..'9' }
            if (digits.isEmpty()) return emptyList()
            return listOf(
                "wm dismiss-keyguard",
                "sleep 0.35; input text $digits; input keyevent KEYCODE_ENTER",
            )
        }

        /** Pure: clears Android's hidden PIN field after a wrong try. */
        fun keyguardClearCommand(): String = (1..16).joinToString(" ") { "KEYCODE_DEL" }.let { "input keyevent $it" }

        /** Pure: the enabled-services setting with VoidOS's added (on) or taken out (off). */
        fun servicesWith(current: String?, on: Boolean): String {
            val list = current.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() && it != COMPONENT && it != "com.andysocial.voidos/.VoidKeysService" }
            return (if (on) list + COMPONENT else list).joinToString(":")
        }

        /** Switches the service on (or off) through root. Blocks. */
        fun sync(c: Context, on: Boolean) {
            val cur = runCatching { android.provider.Settings.Secure.getString(c.contentResolver, "enabled_accessibility_services") }.getOrNull()
            val want = servicesWith(cur, on)
            if (want == cur.orEmpty()) return
            Su.run("settings put secure enabled_accessibility_services '$want'; settings put secure accessibility_enabled ${if (want.isEmpty()) 0 else 1}")
        }
    }
}

/** What the volume keys are moving, for the sliders. */
object VolumeState {
    val stream = mutableIntStateOf(AudioManager.STREAM_MUSIC)
    val bump = mutableIntStateOf(0)
}

/** The VoidOS PIN pad over Android's lock screen: wrong tries and the moment it's checking. */
object KeyguardState {
    val wrong = mutableIntStateOf(0)
    val busy = mutableStateOf(false)
    fun reset() { busy.value = false }
}
