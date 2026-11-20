package com.andysocial.voidos

import kotlinx.coroutines.launch
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.andysocial.voidos.ui.AppGlyph
import com.andysocial.voidos.ui.AppsRepo
import com.andysocial.voidos.ui.EdgeSwipe
import com.andysocial.voidos.ui.EdgeWheels
import com.andysocial.voidos.ui.Glyph
import com.andysocial.voidos.ui.LocalRoom
import com.andysocial.voidos.ui.Module
import com.andysocial.voidos.ui.Rooms
import com.andysocial.voidos.ui.WheelItem
import com.andysocial.voidos.ui.blockReason
import com.andysocial.voidos.ui.launchApp
import kotlin.math.abs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize

/**
 * The edge wheels over other apps.
 *
 * While another app is in front, two narrow, invisible strips sit on the left and right
 * edges (the middle three quarters of the screen height). They are the only part of the
 * screen VoidOS touches: everything else goes straight to the app. A swipe inwards from
 * a strip opens a full-screen window with the same glass wheels as inside VoidOS; when
 * the wheel closes that window is removed again. Picking a screen brings VoidOS back on
 * that screen; picking an app opens it.
 *
 * It needs the "draw over other apps" permission. VoidOS asks root for it once (Magisk
 * may ask you to allow), and the same can be done from the Mac with
 * `adb shell appops set com.andysocial.voidos SYSTEM_ALERT_WINDOW allow`.
 */
object EdgeOverlay {
    /** Whether VoidOS may draw over other apps. */
    val allowed = mutableStateOf(false)

    /** True while VoidOS itself is in front: it handles its own edges then, so the strips stand down. */
    @Volatile var hostInFront = true
        private set

    internal var service: EdgeOverlayService? = null

    fun canDraw(ctx: Context): Boolean = Settings.canDrawOverlays(ctx).also { allowed.value = it }

    /** Starts or stops the overlay to match the setting and the permission. Call it whenever either may have changed. */
    fun sync(ctx: Context, store: Store) {
        val app = ctx.applicationContext
        // with Hide Android on there's no navigation bar, so the bottom-edge swipe is the only way
        // out of another app: keep the overlay running for it even when the wheels are off in apps
        if (!store.edgeInApps && !store.hideBars) { app.stopService(Intent(app, EdgeOverlayService::class.java)); return }
        wheelsInApps = store.edgeInApps
        if (canDraw(app)) {
            runCatching {
                val i = Intent(app, EdgeOverlayService::class.java)
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
            }.onFailure { EdgeSwipe.log("overlay: could not start (${it.message})") }
        } else askRootOnce(app, store)
    }

    /** Whether the side wheels and the control centre strip show over apps (the bottom strip always does). */
    @Volatile var wheelsInApps = true

    fun hostShown(shown: Boolean) {
        hostInFront = shown
        service?.refreshStrips()
    }

    /** Grants itself the overlay permission through root, once per version, off the main thread. */
    private fun askRootOnce(ctx: Context, store: Store) {
        val prefs = ctx.getSharedPreferences("voidos_overlay", Context.MODE_PRIVATE)
        val key = "rootAsked_${BuildConfig.VERSION_CODE}"
        if (prefs.getBoolean(key, false)) return
        prefs.edit().putBoolean(key, true).apply()
        Thread {
            val ok = runCatching {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow"))
                val done = p.waitFor(25, java.util.concurrent.TimeUnit.SECONDS)
                if (!done) p.destroy()
                done && p.exitValue() == 0
            }.getOrDefault(false)
            EdgeSwipe.log("overlay: root grant ${if (ok) "ok" else "not given"}")
            Handler(Looper.getMainLooper()).post { if (canDraw(ctx)) sync(ctx, store) }
        }.start()
    }
}

class EdgeOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val store get() = (application as VoidApp).store
    private val main = Handler(Looper.getMainLooper())

    private var left: View? = null
    private var right: View? = null
    private var wheel: ComposeView? = null
    private var wheelShown = false
    private var top: View? = null
    private var bottom: View? = null
    private var cc: ComposeView? = null
    private var ccShown = false

    /** For tests: the edge strip windows, and whether the wheel window is up. */
    internal val strips: List<View> get() = listOfNotNull(left, right)
    internal val wheelOpen: Boolean get() = wheelShown
    /** For tests: the top strip, and whether the control centre window is up. */
    internal val topStrip: View? get() = top
    internal val controlOpen: Boolean get() = ccShown

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        saved.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        startForeground(NOTE_ID, note())
        EdgeOverlay.service = this
        if (EdgeOverlay.wheelsInApps) { addStrips(); addTop() }
        addBottom()
        EdgeSwipe.log("overlay: on")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (EdgeOverlay.wheelsInApps && left == null) { addStrips(); if (top == null) addTop() }
        if (!EdgeOverlay.wheelsInApps) {
            listOfNotNull(left, right, top).forEach { runCatching { wm.removeView(it) } }
            left = null; right = null; top = null
        }
        if (bottom == null) addBottom()
        refreshStrips()
        return START_STICKY
    }

    override fun onDestroy() {
        EdgeOverlay.service = null
        hideWheel()
        hideControl()
        listOfNotNull(left, right, top, bottom).forEach { runCatching { wm.removeView(it) } }
        left = null; right = null; top = null; bottom = null
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        EdgeSwipe.log("overlay: off")
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // the strips cover the middle of the side edges, so they follow the screen round
        listOfNotNull(left, right).forEach { v -> runCatching { wm.updateViewLayout(v, stripParams(v === left)) } }
        top?.let { v -> runCatching { wm.updateViewLayout(v, topParams()) } }
        bottom?.let { v -> runCatching { wm.updateViewLayout(v, bottomParams()) } }
    }

    private fun note(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Edge drawers", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Edge drawers").setOngoing(true).build()
    }

    // ---------------------------------------------------------------- the strips

    private fun overlayType() = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun stripParams(isLeft: Boolean): WindowManager.LayoutParams {
        val dm = resources.displayMetrics
        return WindowManager.LayoutParams(
            (STRIP_DP * dm.density).toInt(),
            (dm.heightPixels * 0.76f).toInt(),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = (if (isLeft) Gravity.LEFT else Gravity.RIGHT) or Gravity.CENTER_VERTICAL
            title = if (isLeft) "VoidOS edge left" else "VoidOS edge right"
        }
    }

    private fun addStrips() {
        listOf(true, false).forEach { isLeft ->
            val v = Strip(this, isLeft)
            v.visibility = if (EdgeOverlay.hostInFront) View.GONE else View.VISIBLE
            runCatching { wm.addView(v, stripParams(isLeft)) }
                .onSuccess { if (isLeft) left = v else right = v }
                .onFailure { EdgeSwipe.log("overlay: strip refused (${it.message})") }
        }
    }

    fun refreshStrips() {
        main.post {
            val vis = if (EdgeOverlay.hostInFront || wheelShown || ccShown) View.GONE else View.VISIBLE
            left?.visibility = vis
            right?.visibility = vis
            top?.visibility = vis
            bottom?.visibility = vis
            if (EdgeOverlay.hostInFront && wheelShown) EdgeSwipe.overlaySink?.cancel()
            if (EdgeOverlay.hostInFront && ccShown) hideControl()
        }
    }

    // ---------------------------------------------------------------- the control centre over other apps

    /** A strip across the middle of the top edge: pull down from it inside any app for the control centre. */
    private fun topParams(): WindowManager.LayoutParams {
        val dm = resources.displayMetrics
        return WindowManager.LayoutParams(
            (dm.widthPixels * 0.6f).toInt(),
            (STRIP_DP * dm.density).toInt(),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; title = "VoidOS edge top" }
    }

    private fun addTop() {
        val v = TopStrip(this)
        v.visibility = if (EdgeOverlay.hostInFront) View.GONE else View.VISIBLE
        runCatching { wm.addView(v, topParams()) }
            .onSuccess { top = v }
            .onFailure { EdgeSwipe.log("overlay: top strip refused (${it.message})") }
    }

    /** A strip across the middle of the bottom edge: swipe up from it inside any app for VoidOS's app switcher. */
    private fun bottomParams(): WindowManager.LayoutParams = topParams().apply {
        val dm = resources.displayMetrics
        // with Android's navigation bar gone this is the whole bottom edge, bar the corners
        width = (dm.widthPixels * MainActivity.BOTTOM_HALF_WIDTH * 2).toInt()
        height = (BOTTOM_STRIP_DP * dm.density).toInt()
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; title = "VoidOS edge bottom"
    }

    private fun addBottom() {
        val v = BottomStrip(this)
        v.visibility = if (EdgeOverlay.hostInFront) View.GONE else View.VISIBLE
        runCatching { wm.addView(v, bottomParams()) }
            .onSuccess { bottom = v }
            .onFailure { EdgeSwipe.log("overlay: bottom strip refused (${it.message})") }
    }

    /** Opens the app switcher only for a push upwards. */
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class BottomStrip(ctx: Context) : View(ctx) {
        private var downX = 0f
        private var downY = 0f
        private var done = false
        private val slop = SWIPE_DP * resources.displayMetrics.density

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; done = false }
                MotionEvent.ACTION_MOVE -> if (!done) {
                    val dy = downY - ev.rawY
                    val dx = abs(ev.rawX - downX)
                    if (dx > slop * 2 && dx > dy) done = true
                    else if (dy >= slop && dy > dx) { done = true; showSwitcher() }
                }
            }
            return true
        }
    }

    /** VoidOS's app switcher, over whatever app is in front: VoidOS comes forward with it open. */
    internal fun showSwitcher() {
        EdgeSwipe.log("overlay: app switcher")
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SWITCHER, true)
                    .putExtra(MainActivity.EXTRA_FROM_APP, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
    }

    /** Opens the control centre only for a pull downwards; a tap or a sideways touch does nothing. */
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class TopStrip(ctx: Context) : View(ctx) {
        private var downX = 0f
        private var downY = 0f
        private var done = false
        private val slop = SWIPE_DP * resources.displayMetrics.density

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; done = false }
                MotionEvent.ACTION_MOVE -> if (!done) {
                    val dy = ev.rawY - downY
                    val dx = abs(ev.rawX - downX)
                    if (dx > slop * 2 && dx > dy) done = true
                    else if (dy >= slop && dy > dx) { done = true; showControl() }
                }
            }
            return true
        }
    }

    /** Opens the control centre over whatever app is in front, in a window of its own. */
    internal fun showControl() {
        if (ccShown) return
        val act = MainActivity.live?.get()
        if (act == null || act.isDestroyed) {
            // VoidOS isn't running behind this app: bring it back with its control centre open
            EdgeSwipe.log("overlay: control centre via VoidOS")
            runCatching {
                startActivity(
                    Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_CONTROL, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            }
            return
        }
        val back = androidx.activity.OnBackPressedDispatcher()
        val owner = object : androidx.activity.OnBackPressedDispatcherOwner {
            override val onBackPressedDispatcher get() = back
            override val lifecycle: Lifecycle get() = registry
        }
        val v = object : FrameLayoutCompat(this) {
            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    if (event.action == android.view.KeyEvent.ACTION_UP) { if (back.hasEnabledCallbacks()) back.onBackPressed() else hideControl() }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        val cv = ComposeView(this).also { c ->
            c.setViewTreeLifecycleOwner(this)
            c.setViewTreeSavedStateRegistryOwner(this)
            c.setContent {
                CompositionLocalProvider(androidx.activity.compose.LocalOnBackPressedDispatcherOwner provides owner, LocalRoom provides Rooms.overlay) {
                    QuietHaptics { OverlayControl(act) }
                }
            }
        }
        v.addView(cv)
        runCatching { wm.addView(v, controlParams()) }
            .onSuccess { cc = cv; ccHost = v; ccShown = true; refreshStrips(); EdgeSwipe.log("overlay: control centre opened over an app") }
            .onFailure { EdgeSwipe.log("overlay: control centre window refused (${it.message})") }
    }

    private var ccHost: View? = null

    internal fun hideControl() {
        if (!ccShown) return
        ccHost?.let { runCatching { wm.removeView(it) } }
        cc?.disposeComposition()
        cc = null; ccHost = null; ccShown = false
        refreshStrips()
    }

    /** Focusable, so the Wi-Fi password box can take the keyboard. */
    private fun controlParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        overlayType(),
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT; title = "VoidOS control centre"
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }

    @androidx.compose.runtime.Composable
    private fun OverlayControl(act: MainActivity) {
        var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(Unit) { open = true }
        androidx.compose.foundation.layout.BoxWithConstraints(androidx.compose.ui.Modifier.fillMaxSize()) {
            CompositionLocalProvider(com.andysocial.voidos.ui.LocalPortrait provides (maxHeight > maxWidth)) {
                com.andysocial.voidos.ui.ControlCentre(
                    open, store, act,
                    onClose = { open = false; main.postDelayed({ hideControl() }, 320) },
                    onSettings = { hideControl(); backToVoid(Module.SYSTEM.name) },
                )
            }
        }
    }

    /**
     * One invisible strip. It opens the wheel only for a swipe inwards; a touch that
     * doesn't move in is simply dropped, so a stray tap on the very edge does nothing.
     */
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class Strip(ctx: Context, private val isLeft: Boolean) : View(ctx) {
        private var downX = 0f
        private var downY = 0f
        private var downT = 0L
        private var opened = false
        private var dead = false
        private val slop = SWIPE_DP * resources.displayMetrics.density

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val x = ev.rawX; val y = ev.rawY; val t = ev.eventTime
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = x; downY = y; downT = t; opened = false; dead = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dead) return true
                    if (!opened) {
                        val inward = if (isLeft) x - downX else downX - x
                        val dy = abs(y - downY)
                        if (dy > slop * 2 && dy > inward) { dead = true; return true }
                        if (inward >= slop && inward > dy * 0.8f) {
                            opened = true
                            openWheel(isLeft, downX, downY, downT)
                            feed { it.move(x, y, t) }
                        }
                    } else feed { it.move(x, y, t) }
                }
                MotionEvent.ACTION_UP -> if (opened) feed { it.end(x, y, t) }
                MotionEvent.ACTION_CANCEL -> if (opened) feed { it.cancel() }
            }
            return true
        }
    }

    // ---------------------------------------------------------------- the wheel window

    private val pending = ArrayList<(EdgeSwipe.Sink) -> Unit>()

    /** Hands a touch to the wheel; until the wheel has composed (its first frame) touches wait in line. */
    private fun feed(f: (EdgeSwipe.Sink) -> Unit) {
        val s = EdgeSwipe.overlaySink
        if (s != null && pending.isEmpty()) { f(s); return }
        pending += f
        flush()
    }

    private fun flush() {
        val s = EdgeSwipe.overlaySink
        if (s == null) { if (wheelShown) main.postDelayed({ flush() }, 16); return }
        val q = pending.toList(); pending.clear()
        q.forEach { it(s) }
    }

    private fun openWheel(isLeft: Boolean, x: Float, y: Float, t: Long) {
        showWheel()
        EdgeSwipe.log("overlay: wheel ${if (isLeft) "left" else "right"} from another app")
        feed { it.begin(isLeft, x, y, t) }
    }

    private fun wheelParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.LEFT; title = "VoidOS edge wheel" }

    private fun showWheel() {
        if (wheelShown) return
        val v = wheel ?: ComposeView(this).also { cv ->
            cv.setViewTreeLifecycleOwner(this)
            cv.setViewTreeSavedStateRegistryOwner(this)
            cv.setContent { QuietHaptics { OverlayWheels(onClosed = { main.post { hideWheel() } }) } }
            wheel = cv
        }
        runCatching { wm.addView(v, wheelParams()) }
            .onSuccess { wheelShown = true; refreshStrips() }
            .onFailure { EdgeSwipe.log("overlay: wheel window refused (${it.message})") }
    }

    private fun hideWheel() {
        if (!wheelShown) return
        pending.clear()
        wheel?.let { runCatching { wm.removeView(it) } }
        // a fresh window next time: the wheel starts from closed, with today's apps
        wheel?.disposeComposition()
        wheel = null
        EdgeSwipe.overlaySink = null
        wheelShown = false
        refreshStrips()
    }

    @androidx.compose.runtime.Composable
    private fun OverlayWheels(onClosed: () -> Unit) {
        val ctx = this
        val st = store
        // 2.0: over a Dev App the drawers are the same hub drawers as on VoidOS: picking a metric or
        // an app brings VoidOS back on the metric view with that choice
        com.andysocial.voidos.hub.HubRepo.init(ctx)
        val summary = com.andysocial.voidos.hub.HubRepo.summary
        CompositionLocalProvider(LocalRoom provides Rooms.overlay) {
            com.andysocial.voidos.ui.EdgeDrawers(
                tabs = com.andysocial.voidos.hub.HubDrawers.metricItems(),
                currentTab = st.hubMetric,
                onTab = { k -> st.setHub(metric = k); backToVoid(Module.METRICS.name) },
                apps = com.andysocial.voidos.hub.HubDrawers.appItems(summary),
                emptyText = com.andysocial.voidos.hub.HubDrawers.emptyText(summary),
                currentApp = st.hubApp,
                onApp = { id -> st.setHub(app = id); backToVoid(Module.METRICS.name) },
                overlay = true,
                onClosed = onClosed,
            )
        }
    }

    /** Brings VoidOS back to the front, on the screen picked. */
    private fun backToVoid(module: String) {
        com.andysocial.voidos.ui.Idle.openModule.value = module
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_MODULE, module)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }.onFailure { EdgeSwipe.log("overlay: could not bring VoidOS back (${it.message})") }
    }

    companion object {
        const val CHANNEL = "edge"
        const val NOTE_ID = 7301
        /** How wide each invisible edge strip is. */
        const val STRIP_DP = 14f
        const val BOTTOM_STRIP_DP = 18f
        /** How far a finger must move inwards from a strip before the wheel opens. */
        const val SWIPE_DP = 14f
    }
}

/** A plain frame for the control centre window, so it can catch the back key. */
internal open class FrameLayoutCompat(ctx: Context) : android.widget.FrameLayout(ctx)
