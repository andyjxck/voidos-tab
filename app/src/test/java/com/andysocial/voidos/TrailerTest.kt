package com.andysocial.voidos

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import com.andysocial.voidos.ui.CcSource
import com.andysocial.voidos.ui.ControlCentre
import com.andysocial.voidos.ui.LocalPortrait
import com.andysocial.voidos.ui.LockScreen
import com.andysocial.voidos.ui.LockState
import com.andysocial.voidos.ui.Module
import com.andysocial.voidos.ui.Shell
import com.andysocial.voidos.ui.VoidRoot
import com.andysocial.voidos.ui.Idle
import com.andysocial.voidos.ui.Stopwatch
import com.andysocial.voidos.ui.V
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.time.Duration
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

/**
 * Records VoidOS in motion for the trailer: the real screens, driven by real
 * touches (down, move, up through Compose's pointer input), one frame every
 * 33 ms of app time. Each scene writes raw RGBA frames plus a touch log the
 * compositor uses to draw the fingertip.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class TrailerBase {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    protected lateinit var act: MainActivity
    protected val store get() = VoidApp.instance.store
    private val outDir = File("/home/sprite/tr3/rec").also { it.mkdirs() }
    open val tag = "land"

    private var raw: BufferedOutputStream? = null
    private var touchLog: java.io.Writer? = null
    private var textLog: java.io.Writer? = null
    private var bmp: Bitmap? = null
    private var buf: ByteBuffer? = null
    protected var frame = 0
    private var consumed = 0L
    private val down = linkedMapOf<Int, Offset>()
    private var marks = mutableListOf<String>()

    @Before fun seedAll() {
        act = rule.activity
        rule.mainClock.autoAdvance = false
        store.set("setupDone", true)
        store.resetClock()
        com.andysocial.voidos.ui.EscapeDoor.reset(); com.andysocial.voidos.ui.EscapeDoor.asked.value = false
        LockState.locked.value = false
        store.setAmbient(0)
        act.sendStickyBroadcast(android.content.Intent(android.content.Intent.ACTION_BATTERY_CHANGED)
            .putExtra(android.os.BatteryManager.EXTRA_LEVEL, 82).putExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
            .putExtra(android.os.BatteryManager.EXTRA_STATUS, android.os.BatteryManager.BATTERY_STATUS_DISCHARGING))
        com.andysocial.voidos.ui.Idle.ambient.value = false
        // wall clock: this evening, a few seconds before the minute turns
        val c = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 18, 19, 41, 57); set(Calendar.MILLISECOND, 0) }
        android.os.SystemClock.setCurrentTimeMillis(c.timeInMillis)
        store.chooseAutoPlace(false)
        store.setPlace("London", 51.5085f, -0.1257f)
        store.setPin("2580")
        store.set("lockNeedsPin", true)
        if (store.shopping.isEmpty()) listOf("Oat milk", "Bananas", "Sourdough", "Coffee beans", "Cheddar").forEach { store.addItem(it) }
        KTimers.silence(act)
        KTimers.list.value.forEach { KTimers.remove(act, it.id) }
        Stopwatch.reset()
        CcSource.live = false
        CcSource.wifiOn = { _ -> true }
        CcSource.ssid = { _ -> "Home Wi-Fi" }
        CcSource.networks = { _ ->
            listOf(
                Radio.Net("Home Wi-Fi", 4, Radio.Sec.WPA, connected = true, saved = true),
                Radio.Net("BT-Hub-7F2A", 3, Radio.Sec.WPA),
                Radio.Net("VM5G-3C1D", 3, Radio.Sec.SAE),
                Radio.Net("Costa Free WiFi", 2, Radio.Sec.OPEN),
            )
        }
        CcSource.btOn = { true }
        CcSource.devices = { listOf(Radio.Dev("AirPods Pro", "AA:BB:CC:00:00:01", Radio.Kind.HEADPHONES, paired = true, connected = true)) }
    }

    /** The app exactly as MainActivity draws it: VoidRoot, with its backdrop, lock screen and overlays. */
    protected fun showApp(module: Module = Module.FACE) {
        VoidApp.booted = true
        rule.runOnUiThread {
            val view = act.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as androidx.compose.ui.platform.ComposeView
            view.setContent { VoidRoot(store, act) }
        }
        idle(800)
        if (module != Module.FACE) { Idle.openModule.value = module.name }
        idle(1500)
    }

    /** Fake installed apps so the Apps screen has something in it (Robolectric has none). */
    protected fun seedApps() {
        val pm = shadowOf(act.packageManager)
        val apps = listOf("YouTube" to 0xFFE53935, "Spotify" to 0xFF1DB954, "Chrome" to 0xFF4285F4, "Netflix" to 0xFFB71C1C,
            "Maps" to 0xFF34A853, "Photos" to 0xFFFBBC05, "Gmail" to 0xFFEA4335, "Calendar" to 0xFF1A73E8,
            "Camera" to 0xFF607D8B, "Clock" to 0xFF3949AB, "Files" to 0xFF00897B, "Play Store" to 0xFF01875F,
            "Bloom" to 0xFF8E6CF0, "Void Baller" to 0xFF2E7D32, "Void Musician" to 0xFFAD1457, "Zen Void" to 0xFF26A69A,
            "WhatsApp" to 0xFF25D366, "Discord" to 0xFF5865F2, "Twitch" to 0xFF9146FF, "BBC iPlayer" to 0xFFE91E63)
        apps.forEachIndexed { i, (name, col) ->
            val pkg = "com.trailer.app$i"
            val ai = android.content.pm.ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = name; name.let { } ; flags = 0; enabled = true }
            val pi = android.content.pm.PackageInfo().apply { packageName = pkg; applicationInfo = ai }
            pm.installPackage(pi)
            val ri = android.content.pm.ResolveInfo().apply {
                activityInfo = android.content.pm.ActivityInfo().apply { packageName = pkg; this.name = "$pkg.Main"; applicationInfo = ai; nonLocalizedLabel = name }
            }
            pm.addResolveInfoForIntent(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), ri)
            val bmp = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            p.color = col.toInt(); c.drawCircle(96f, 96f, 92f, p)
            p.color = 0xFFFFFFFF.toInt(); p.textSize = 96f; p.textAlign = android.graphics.Paint.Align.CENTER; p.isFakeBoldText = true
            c.drawText(name.take(1), 96f, 130f, p)
            pm.setApplicationIcon(pkg, android.graphics.drawable.BitmapDrawable(act.resources, bmp))
        }
    }

    /** Let app time pass without recording. */
    protected fun idle(ms: Long) {
        var left = ms
        while (left > 0) { val s = minOf(33L, left); advance(s); left -= s }
        rule.waitForIdle()
    }

    /** Wait for real (network) work while app time creeps on. */
    protected fun waitReal(ms: Long, until: () -> Boolean = { false }) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end && !until()) { Thread.sleep(150); advance(33) }
    }

    private fun advance(ms: Long) {
        rule.mainClock.advanceTimeBy(ms)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    protected fun begin(name: String) {
        end()
        raw = BufferedOutputStream(FileOutputStream(File(outDir, "${tag}_$name.rgba")), 1 shl 22)
        touchLog = File(outDir, "${tag}_$name.touch").bufferedWriter()
        textLog = File(outDir, "${tag}_$name.txt").bufferedWriter()
        frame = 0
        val v = act.window.decorView
        textLog!!.write("size ${v.width}x${v.height} density ${act.resources.displayMetrics.density} version ${BuildConfig.VERSION_NAME}\n")
    }

    protected fun end() {
        raw?.close(); touchLog?.close(); textLog?.close()
        raw = null; touchLog = null; textLog = null
    }

    /** One frame: move time on by what's left of 33 ms, draw, save. */
    protected fun tick(n: Int = 1) = repeat(n) {
        val left = (33L - consumed).coerceAtLeast(0L)
        consumed = 0
        if (left > 0) advance(left) else shadowOf(Looper.getMainLooper()).idle()
        capture()
    }

    private fun capture() {
        val v = act.window.decorView
        val b = bmp?.takeIf { it.width == v.width && it.height == v.height }
            ?: Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { bmp = it; buf = ByteBuffer.allocate(v.width * v.height * 4) }
        rule.runOnUiThread { b.eraseColor(0xFF000000.toInt()); v.draw(android.graphics.Canvas(b)) }
        val bb = buf!!; bb.clear(); b.copyPixelsToBuffer(bb)
        raw?.write(bb.array(), 0, bb.position())
        touchLog?.write(down.entries.joinToString(";", prefix = "$frame ") { "${it.key},${it.value.x.toInt()},${it.value.y.toInt()}" } + "\n")
        if (marks.isNotEmpty()) { textLog?.write("#$frame ${marks.joinToString(" | ")}\n"); marks.clear() }
        frame++
    }

    protected fun mark(s: String) { marks += s }

    /** Everything the screen says right now, for checking the footage afterwards. */
    protected fun dumpTexts(label: String) {
        val all = rule.onAllNodes(SemanticsMatcher("text") { it.config.contains(SemanticsProperties.Text) }, useUnmergedTree = true)
            .fetchSemanticsNodes().map { n -> n.config[SemanticsProperties.Text].joinToString { it.text } }
        textLog?.write("#$frame TEXTS[$label]: ${all.joinToString(" / ")}\n")
    }

    private fun touch(block: TouchInjectionScope.() -> Unit) {
        val t0 = rule.mainClock.currentTime
        rule.onRoot().performTouchInput(block)
        consumed += rule.mainClock.currentTime - t0
    }

    // ---- finger gestures, one frame at a time

    protected fun press(p: Offset, id: Int = 0) { touch { down(id, p) }; down[id] = p }
    protected fun moveFinger(p: Offset, id: Int = 0) { touch { moveTo(id, p, delayMillis = 0) }; down[id] = p }
    protected fun lift(id: Int = 0) { touch { up(id) }; down.remove(id) }

    protected fun tap(p: Offset, hold: Int = 3, after: Int = 6) {
        press(p); tick(hold); lift(); tick(after)
    }

    private fun ease(t: Float) = if (t < 0.5f) 4 * t * t * t else 1 - Math.pow((-2.0 * t + 2), 3.0).toFloat() / 2

    /** A drag along straight segments, eased, over [frames]. */
    protected fun drag(from: Offset, to: Offset, frames: Int, holdFirst: Int = 0, holdLast: Int = 2, linear: Boolean = false) {
        press(from); tick(1)
        if (holdFirst > 0) tick(holdFirst)
        for (k in 1..frames) {
            val t = k / frames.toFloat()
            val e = if (linear) t else ease(t)
            moveFinger(from + (to - from) * e); tick()
        }
        if (holdLast > 0) tick(holdLast)
        lift(); tick()
    }

    /** A quick flick: fast, so the pager or sheet takes it as a fling. */
    protected fun flick(from: Offset, to: Offset, frames: Int = 6) {
        press(from); tick()
        for (k in 1..frames) { moveFinger(from + (to - from) * (k / frames.toFloat())); tick() }
        lift(); tick()
    }

    protected fun pinch(c: Offset, r0: Float, r1: Float, a0: Float, a1: Float, frames: Int) {
        fun p(r: Float, a: Float, s: Int) = c + Offset(cos(a) * r * s, sin(a) * r * s)
        press(p(r0, a0, -1), 0); press(p(r0, a0, 1), 1); tick(2)
        for (k in 1..frames) {
            val e = ease(k / frames.toFloat())
            val r = r0 + (r1 - r0) * e; val a = a0 + (a1 - a0) * e
            moveFinger(p(r, a, -1), 0); moveFinger(p(r, a, 1), 1); tick()
        }
        tick(2); lift(0); lift(1); tick()
    }

    protected fun boundsOf(m: SemanticsMatcher): Rect =
        rule.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
            .firstOrNull { it.width > 1f && it.height > 1f && it.center.x in 0f..W && it.center.y in 0f..H }
            ?: rule.onAllNodes(m).fetchSemanticsNodes().map { it.boundsInRoot }
                .firstOrNull { it.width > 1f && it.height > 1f && it.center.x in 0f..W && it.center.y in 0f..H }
            ?: rule.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
    protected fun textAt(t: String, exact: Boolean = true) = boundsOf(hasText(t, substring = !exact, ignoreCase = true)).center
    protected fun descAt(d: String) = boundsOf(hasContentDescription(d)).center
    protected fun has(t: String) = rule.onAllNodes(hasText(t, substring = true, ignoreCase = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    protected val W get() = act.window.decorView.width.toFloat()
    protected val H get() = act.window.decorView.height.toFloat()

    protected fun typeSlowly(fieldIndex: Int, text: String, framesPerChar: Int = 3) {
        var s = ""
        text.forEach { ch ->
            s += ch
            rule.onAllNodes(hasSetTextAction())[fieldIndex].performTextReplacement(s)
            tick(framesPerChar)
        }
    }

    protected val noteTime get() = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 18, 18, 6, 0) }.timeInMillis
    protected fun seedFace() {
        KTimers.add(act, "Laundry", 9 * 60_000L + 14_000L)
        KTimers.add(act, "Oven", 23 * 60_000L + 40_000L)
        store.saveWidgets(emptyList())
        store.notes.forEach { store.deleteNote(it.id) }
        store.saveNote(Note(77L, "Bins out Thursday\nRecycling too", 1, noteTime))
        store.addWidget("sky")
        store.addWidget("note", 77L)
    }

    /** Locked tablet: swipe up, PIN, the home screen opens up behind. */
    @Test fun s01_unlock() {
        seedFace()
        PinGuard.reset()
        WeatherWarm.warm(this)
        store.set("voidLock", true)
        LockState.locked.value = true
        showApp()
        begin("unlock")
        dumpTexts("lock")
        tick(30)
        mark("swipe up")
        drag(Offset(W / 2, H * 0.88f), Offset(W / 2, H * 0.35f), 10)
        tick(14)
        dumpTexts("pin")
        "2580".forEach { d -> mark("key $d"); tap(textAt(d.toString()), hold = 3, after = 5) }
        tick(40)
        dumpTexts("home")
        end()
    }

    /** Home: the clock rolls over a minute; hold a widget, move it, stretch it. */
    @Test fun s02_home() {
        seedFace()
        WeatherWarm.warm(this)
        showApp()
        begin("home")
        dumpTexts("face")
        tick(75)
        val sky = boundsOf(androidx.compose.ui.test.hasTestTag("widget-sky"))
        val dx = if (W > H) 300f else 160f; val dy = if (W > H) -110f else 180f
        mark("hold widget")
        drag(sky.center, sky.center + Offset(dx, dy), 24, holdFirst = 24, holdLast = 6)
        tick(10)
        val sky2 = boundsOf(androidx.compose.ui.test.hasTestTag("widget-sky"))
        mark("stretch widget")
        pinch(sky2.center, 60f, 90f, 0.5f, 0.5f, 20)
        tick(12)
        mark("twist widget")
        pinch(boundsOf(androidx.compose.ui.test.hasTestTag("widget-sky")).center, 80f, 80f, 0.5f, 0.2f, 16)
        tick(30)
        dumpTexts("face end")
        end()
    }

    /** Pull down the control centre, hold Wi-Fi, slide brightness and volume, pick a Focus mode. */
    @Test fun s03_control() {
        seedFace()
        WeatherWarm.warm(this)
        showApp()
        begin("control")
        tick(10)
        mark("pull down")
        drag(Offset(W * 0.5f, 12f), Offset(W * 0.5f, H * 0.45f), 9)
        tick(24)
        dumpTexts("cc open")
        runCatching {
            val wifi = descAt("Wi-Fi")
            mark("hold wifi")
            press(wifi); tick(22); lift(); tick(40)
            dumpTexts("wifi list")
            tap(Offset(W * 0.06f, H * 0.94f), after = 4)
            tick(16)
            dumpTexts("cc again")
        }.onFailure { mark("wifi failed ${it.message}") }
        runCatching {
            val b = boundsOf(hasContentDescription("Brightness"))
            mark("brightness $b")
            if (b.height > b.width) {
                drag(Offset(b.center.x, b.bottom - 16f), Offset(b.center.x, b.top + b.height * 0.25f), 16); tick(6)
                drag(Offset(b.center.x, b.top + b.height * 0.25f), Offset(b.center.x, b.top + b.height * 0.55f), 10)
            } else {
                drag(Offset(b.left + 16f, b.center.y), Offset(b.left + b.width * 0.75f, b.center.y), 16); tick(6)
                drag(Offset(b.left + b.width * 0.75f, b.center.y), Offset(b.left + b.width * 0.45f, b.center.y), 10)
            }
            tick(6)
        }.onFailure { mark("brightness failed ${it.message}") }
        runCatching {
            val b = boundsOf(hasContentDescription("Volume"))
            mark("volume $b")
            if (b.height > b.width) drag(Offset(b.center.x, b.bottom - 16f), Offset(b.center.x, b.top + b.height * 0.3f), 14)
            else drag(Offset(b.left + 16f, b.center.y), Offset(b.left + b.width * 0.7f, b.center.y), 14)
            tick(10)
        }.onFailure { mark("volume failed ${it.message}") }
        runCatching {
            mark("focus")
            tap(descAt("Focus"), after = 24)
            dumpTexts("focus list")
            mark("work"); tap(textAt("Work"), after = 30)
            dumpTexts("focus work")
        }.onFailure { mark("focus failed ${it.message}") }
        tick(10)
        end()
    }

    /** Tools: quick timers, the stopwatch. */
    @Test fun s04_tools() {
        showApp(Module.TOOLS)
        begin("tools")
        tick(8)
        dumpTexts("tools")
        runCatching { mark("quick 1"); tap(textAt("1"), after = 45) }.onFailure { mark("q1 failed") }
        runCatching { mark("quick 3"); tap(textAt("3"), after = 40) }.onFailure { mark("q3 failed") }
        dumpTexts("two timers")
        runCatching {
            mark("stopwatch tab"); tap(textAt("Stopwatch"), after = 14)
            mark("start"); tap(textAt("Start"), after = 45)
            mark("lap"); tap(textAt("Lap"), after = 30)
            tap(textAt("Lap"), after = 30)
        }.onFailure { mark("stopwatch failed ${it.message}") }
        dumpTexts("stopwatch laps")
        end()
    }

    /** Notes: a new sketch, drawn with two colours and two pen sizes. */
    @Test fun s05_notes() {
        store.notes.forEach { store.deleteNote(it.id) }
        store.saveNote(Note(78L, "Saturday\nPick up the cake\nBalloons\nCandles", 4, noteTime - 24 * 3_600_000L))
        showApp(Module.NOTES)
        begin("notes")
        tick(8)
        dumpTexts("notes")
        mark("sketch")
        runCatching { tap(descAt("Sketch"), after = 16) }.onFailure { mark("sketch failed ${it.message}") }
        dumpTexts("sketch")
        val land = W > H
        val cx = W * (if (land) 0.58f else 0.5f); val cy = H * (if (land) 0.45f else 0.42f)
        val k0 = if (land) 1f else 0.75f
        fun stroke(pts: List<Offset>) {
            press(pts.first()); tick()
            pts.drop(1).forEach { moveFinger(it); tick() }
            lift(); tick(3)
        }
        mark("draw 1")
        stroke((0..28).map { k -> val a = k / 28f * 6.5f; val r = (26f + k * 4.6f) * k0; Offset(cx + cos(a) * r, cy + sin(a) * r * 0.8f) })
        fun pickColour(i: Int) = runCatching {
            mark("colour panel"); tap(descAt("Colour"), after = 10)
            val sw = rule.onAllNodes(hasContentDescription("Colour #", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            mark("swatches ${sw.size}")
            if (sw.size > i) { tap(sw[i].boundsInRoot.center, after = 6) }
            tap(descAt("Colour"), after = 8)
        }.onFailure { mark("colour failed ${it.message}") }
        pickColour(3)
        mark("draw 2")
        stroke((0..26).map { k -> Offset(cx - 230f * k0 + k * 17.7f * k0, cy + 150f * k0 + sin(k / 3f) * 30f) })
        pickColour(7)
        mark("draw 3")
        stroke((0..20).map { k -> val a = k / 20f * 6.28f; Offset(cx + 200f * k0 + cos(a) * 46f, cy - 105f * k0 + sin(a) * 46f) })
        tick(30)
        dumpTexts("drawn")
        end()
    }

    /** Weather: search a place by name, pick it, the forecast fills in. */
    @Test fun s06_weather() {
        WeatherWarm.warm(this)
        showApp(Module.WEATHER)
        begin("weather")
        tick(12)
        dumpTexts("london")
        runCatching {
            mark("places")
            tap(textAt("Places"), after = 10)
            val field = rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size - 1
            mark("type")
            typeSlowly(field, "Edinb", 4)
            waitReal(8000) { has("Scotland") }
            tick(16)
            dumpTexts("results")
            mark("pick"); tap(textAt("Scotland", exact = false), after = 4)
            waitReal(8000) { !has("Scotland") && has("Edinburgh") }
            runCatching { if (has("Done")) tap(textAt("Done"), after = 6) }
            waitReal(4000)
        }.onFailure { mark("weather search failed ${it.message}") }
        tick(50)
        dumpTexts("edinburgh")
        end()
    }

    /** The screens sliding past, Home to Settings and back. */
    @Test fun s07_screens() {
        seedFace()
        seedApps()
        WeatherWarm.warm(this)
        showApp()
        begin("screens")
        tick(8)
        repeat(6) { i ->
            mark("swipe left $i")
            drag(Offset(W * 0.70f, H * 0.5f), Offset(W * 0.22f, H * 0.5f), 9, holdLast = 0, linear = true)
            tick(20)
            mark("page ${com.andysocial.voidos.ui.ShellNow.module.value}")
            dumpTexts("page ${i + 1}")
        }
        end()
    }

    /** Apps: the honeycomb, panned around. */
    @Test fun s08_apps() {
        seedApps()
        showApp(Module.APPS)
        begin("apps")
        tick(10)
        dumpTexts("apps")
        runCatching {
            mark("pan")
            drag(Offset(W * 0.5f, H * 0.6f), Offset(W * 0.5f, H * 0.42f), 16)
            tick(12)
            drag(Offset(W * 0.5f, H * 0.42f), Offset(W * 0.5f, H * 0.58f), 16)
        }
        tick(20)
        dumpTexts("apps end")
        end()
    }
}

@Config(qualifiers = "w1280dp-h800dp-xlarge-land-mdpi", sdk = [33], instrumentedPackages = ["com.andysocial.voidos"])
class TrailerLandTest : TrailerBase() { override val tag = "land" }

@Config(qualifiers = "w800dp-h1280dp-xlarge-port-mdpi", sdk = [33], instrumentedPackages = ["com.andysocial.voidos"])
class TrailerPortTest : TrailerBase() { override val tag = "port" }

/** Loads a real forecast for the place once, before recording. */
object WeatherWarm {
    fun warm(@Suppress("UNUSED_PARAMETER") t: TrailerBase) {
        kotlinx.coroutines.runBlocking { runCatching { com.andysocial.voidos.ui.WeatherRepo.refresh(VoidApp.instance.store, force = true) } }
    }
}
