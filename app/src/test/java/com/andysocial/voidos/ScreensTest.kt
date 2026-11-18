package com.andysocial.voidos

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assertIsDisplayed
import com.andysocial.voidos.ui.BootAnim
import com.andysocial.voidos.ui.ControlCentre
import com.andysocial.voidos.ui.LocalPortrait
import com.andysocial.voidos.ui.LockScreen
import com.andysocial.voidos.ui.Module
import com.andysocial.voidos.ui.Setup
import com.andysocial.voidos.ui.Shell
import com.andysocial.voidos.ui.V
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import org.junit.Assert.assertTrue
import org.junit.Before
import androidx.compose.ui.geometry.Offset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders every VoidOS screen on the JVM at the TB-X306F's size (1280 x 800 px
 * at tvdpi, so 960 x 600 dp; the tighter of the densities the tablet ships
 * with) and saves a PNG of each to docs/screens. Each screen is also checked
 * for the content it should show, so a layout that crashes or loses its
 * content fails the build. The two subclasses run it in landscape and portrait.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ScreensTest(private val tag: String, private val keepShots: Boolean = true) {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var act: MainActivity
    private val store get() = VoidApp.instance.store
    private val outDir = (if (keepShots) File(System.getProperty("voidos.shots") ?: "build/screens") else File("build/screens-extra")).also { it.mkdirs() }

    @Before
    fun seed() {
        act = rule.activity
        // the hub never idles (clocks tick every second), so time only moves when a test moves it
        rule.mainClock.autoAdvance = false
        fakeApps()
        store.set("setupDone", true)
        store.resetClock()
        com.andysocial.voidos.ui.EscapeDoor.reset(); com.andysocial.voidos.ui.EscapeDoor.asked.value = false
        if (store.shopping.isEmpty()) listOf("Semi-skimmed milk", "Bananas", "Sourdough loaf", "Washing-up liquid", "Cheddar").forEach { store.addItem(it) }
        // start from a known board (other test classes share these objects)
        KTimers.silence(act)
        KTimers.list.value.forEach { KTimers.remove(act, it.id) }
        run {
            KTimers.add(act, "Laundry", 9 * 60_000L)
            KTimers.add(act, "Call Maya", 22 * 60_000L)
            KTimers.add(act, "Stretch", 6 * 60_000L, start = false)
        }
    }

    /** A few launchable apps, so the drawer has something to draw. */
    private fun fakeApps() {
        val spm = shadowOf(VoidApp.instance.packageManager)
        listOf("com.spotify.music" to "Spotify", "com.google.android.youtube" to "YouTube", "com.whatsapp" to "WhatsApp",
            "com.android.chrome" to "Chrome", "com.netflix.mediaclient" to "Netflix", "com.bbc.sounds" to "BBC Sounds").forEach { (pkg, label) ->
            val ai = ApplicationInfo().apply { packageName = pkg; name = label; nonLocalizedLabel = label; flags = ApplicationInfo.FLAG_INSTALLED; enabled = true }
            spm.installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = ai })
            val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
            val ri = ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = pkg; name = "$pkg.Main"; applicationInfo = ai; exported = true; enabled = true } }
            spm.addResolveInfoForIntent(launch, ri)
            spm.addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), ri)
            spm.addActivityIfNotPresent(ComponentName(pkg, "$pkg.Main"))
            spm.addIntentFilterForActivity(ComponentName(pkg, "$pkg.Main"),
                android.content.IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        }
    }

    private fun show(content: @Composable () -> Unit) {
        // MainActivity already set its own content, so swap what its ComposeView shows
        rule.runOnUiThread {
            val view = act.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as androidx.compose.ui.platform.ComposeView
            view.setContent {
                BoxWithConstraints(Modifier.fillMaxSize().background(V.Bg)) {
                    CompositionLocalProvider(LocalPortrait provides (maxHeight > maxWidth)) { content() }
                }
            }
        }
        settle()
    }

    private fun settle(ms: Long = 1500) {
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(ms)
        rule.mainClock.advanceTimeBy(300)
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(300)
    }

    private fun shot(name: String) {
        rule.onRoot().captureRoboImage(File(outDir, "${tag}_$name.png").path)
        audit(name)
    }

    /**
     * Layout audit: every piece of text whose layout overflowed its box (cut
     * off or squashed) and every node that sits outside the screen without a
     * scrolling parent. Written to docs/screens/audit.txt; [clipped] collects
     * them so a test can fail on them.
     */
    private val clipped = mutableListOf<String>()
    private fun audit(name: String) {
        val root = rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val w = root.size.width; val h = root.size.height
        val lines = mutableListOf<String>()
        fun scrolls(n: androidx.compose.ui.semantics.SemanticsNode): Boolean {
            var p = n.parent
            while (p != null) {
                val c = p.config
                if (c.contains(androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange) ||
                    c.contains(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)) return true
                p = p.parent
            }
            return false
        }
        val texts = mutableListOf<Pair<androidx.compose.ui.semantics.SemanticsNode, String>>()
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode) {
            val text = n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString { it.text }
            if (n.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) && text == null) {
                val b = n.boundsInRoot
                if (!scrolls(n) && b.width > 0 && (b.left < -1 || b.top < -1 || b.right > w + 1 || b.bottom > h + 1))
                    lines += "  OFFSCREEN button at $b (screen ${w}x$h)"
            }
            if (text != null && text.isNotBlank()) {
                texts += n to text
                val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                n.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
                val r = results.firstOrNull()
                if (r != null) {
                    val why = listOfNotNull(
                        // (didOverflowWidth is unreliable under Robolectric, so compare sizes)
                        "wider than its box".takeIf { r.lineCount == 1 && r.multiParagraph.intrinsics.maxIntrinsicWidth > r.size.width + 2 },
                        "taller than its box".takeIf { r.multiParagraph.height > r.size.height + 2 },
                        "ellipsized".takeIf { (0 until r.lineCount).any { r.isLineEllipsized(it) } },
                    )
                    if (why.isNotEmpty()) lines += "  TEXT CUT  \"$text\" box=${n.size} text=${r.size} lines=${r.lineCount} (${why.joinToString()})"
                }
                val b = n.boundsInRoot
                if (!scrolls(n) && (b.left < -1 || b.top < -1 || b.right > w + 1 || b.bottom > h + 1))
                    lines += "  OFFSCREEN \"$text\" at $b (screen ${w}x$h)"
            }
            n.children.forEach { walk(it) }
        }
        walk(root)
        // text drawn on top of other text
        for (i in texts.indices) for (j in i + 1 until texts.size) {
            val (a, ta) = texts[i]; val (b, tb) = texts[j]
            val r = a.boundsInRoot.intersect(b.boundsInRoot)
            if (r.width <= 0 || r.height <= 0) continue
            val small = minOf(a.boundsInRoot.width * a.boundsInRoot.height, b.boundsInRoot.width * b.boundsInRoot.height)
            if (small > 0 && r.width * r.height > 0.3f * small) lines += "  OVERLAP   \"$ta\" / \"$tb\""
        }
        File(outDir, "audit.txt").appendText("[$tag] $name: ${if (lines.isEmpty()) "ok" else "${lines.size} issue(s)"}\n" + lines.joinToString("") { "$it\n" })
        clipped += lines.map { "$name $it" }
    }

    private fun tap(label: String) {
        // the semantics click works for a tab scrolled out of view too
        rule.onAllNodes(hasText(label, ignoreCase = true) and hasClickAction()).onFirst()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        settle(800)
    }

    private fun expect(vararg text: String) {
        text.forEach { t ->
            val found = rule.onAllNodesWithText(t, substring = true, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            if (!found) {
                val all = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("any text") {
                    it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text) }, useUnmergedTree = true)
                    .fetchSemanticsNodes().map { n -> n.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text } }
                throw AssertionError("[$tag] expected \"$t\" on screen; texts: $all")
            }
        }
    }

    private fun checkPortrait() {
        val portrait = act.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        assertTrue("[$tag] wrong orientation", portrait == tag.startsWith("port"))
    }

    @Test fun face() {
        checkPortrait()
        store.saveWidgets(emptyList())
        show { Shell(store, act, Module.FACE) }
        shot("01_face")
        // only the running timers show; the paused one stays off the face
        expect("Laundry", "Call Maya")
        val paused = rule.onAllNodesWithText("Stretch", substring = true, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("[$tag] paused timer should not be on the face", paused.isEmpty())
        // the time is dead centre on the dial, timers or not
        val dial = rule.onNodeWithTag("dial", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val time = rule.onNodeWithTag("time", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("[$tag] time off centre by ${time.center - dial.center}", (time.center - dial.center).getDistance() < 2f)
    }

    @Test fun faceWidgets() {
        store.saveWidgets(emptyList())
        if (store.notes.isEmpty()) store.saveNote(Note(77L, "Bins out Thursday", 1, 77L))
        store.addWidget("sky"); store.addWidget("list"); store.addWidget("note", store.notes.first().id)
        show { Shell(store, act, Module.FACE) }
        expect("Cheddar", "Bins out Thursday", store.city.uppercase()); shot("01b_face_widgets")
        store.saveWidgets(emptyList())
    }

    /** Hold to drag, pinch to stretch on one axis, twist to turn: on a widget and on the clock. */
    @Test fun faceMoveStretchTurn() {
        store.saveWidgets(emptyList())
        store.addWidget("sky")
        show { Shell(store, act, Module.FACE) }
        fun box() = rule.onNodeWithTag("widget-sky", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val id = store.widgets.first().id
        fun wd() = store.widgets.first { it.id == id }
        // hold, then drag up and right
        val start = wd()
        var c = box().center
        rule.onRoot().performTouchInput { down(c) }
        rule.mainClock.advanceTimeBy(900)
        rule.onRoot().performTouchInput { moveBy(Offset(120f, -90f)); moveBy(Offset(60f, -40f)); up() }
        settle(400)
        assertTrue("[$tag] held widget should move: ${start.x},${start.y} -> ${wd().x},${wd().y}", wd().x > start.x && wd().y < start.y)
        // a quick swipe that starts on it is not a drag
        val before = wd()
        c = box().center
        rule.onRoot().performTouchInput { down(c); moveBy(Offset(-200f, 0f)); up() }
        settle(800)
        assertEquals("[$tag] a swipe must not move the widget", before.x, wd().x, 0.001f)
        // two fingers spread sideways: wider, not taller
        c = box().center
        rule.onRoot().performTouchInput {
            down(0, c - Offset(40f, 0f)); down(1, c + Offset(40f, 0f))
            moveTo(0, c - Offset(90f, 0f)); moveTo(1, c + Offset(90f, 0f))
            moveTo(0, c - Offset(130f, 0f)); moveTo(1, c + Offset(130f, 0f))
            up(0); up(1)
        }
        settle(400)
        assertTrue("[$tag] spread sideways should widen: sw=${wd().sw}", wd().sw > 1.4f)
        assertEquals("[$tag] spread sideways should not change the height", 1f, wd().sh, 0.05f)
        // twist a quarter turn
        c = box().center
        rule.onRoot().performTouchInput {
            down(0, c - Offset(70f, 0f)); down(1, c + Offset(70f, 0f))
            moveTo(0, c - Offset(50f, 50f)); moveTo(1, c + Offset(50f, 50f))
            moveTo(0, c - Offset(0f, 70f)); moveTo(1, c + Offset(0f, 70f))
            up(0); up(1)
        }
        settle(400)
        assertEquals("[$tag] a quarter twist should turn it 90 degrees", 90f, kotlin.math.abs(wd().rot), 0.5f)
        shot("01c_face_stretched")
        // and it keeps all of that after a restart
        val fresh = Store(act).widgets.first { it.id == id }
        assertEquals(wd().sw, fresh.sw, 0.001f); assertEquals(wd().rot, fresh.rot, 0.001f)

        // the clock: hold to move it, pinch to make it smaller
        store.saveWidgets(emptyList())
        settle(300)
        val t = rule.onNodeWithTag("time", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        rule.onRoot().performTouchInput { down(t) }
        rule.mainClock.advanceTimeBy(900)
        rule.onRoot().performTouchInput { moveBy(Offset(0f, -60f)); moveBy(Offset(0f, -40f)); up() }
        settle(400)
        assertTrue("[$tag] held clock should move up: cy=${store.clock.cy}", store.clock.cy < 0.45f)
        val t2 = rule.onNodeWithTag("time", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        rule.onRoot().performTouchInput {
            down(0, t2 - Offset(150f, 0f)); down(1, t2 + Offset(150f, 0f))
            moveTo(0, t2 - Offset(110f, 0f)); moveTo(1, t2 + Offset(110f, 0f))
            moveTo(0, t2 - Offset(75f, 0f)); moveTo(1, t2 + Offset(75f, 0f))
            up(0); up(1)
        }
        settle(400)
        assertTrue("[$tag] pinching in should shrink the clock: scale=${store.clock.scale}", store.clock.scale < 0.7f)
        shot("01d_face_clock_moved")
        store.resetClock()
    }

    @Test fun tools() {
        show { Shell(store, act, Module.TOOLS) }
        expect("Tools", "Timers", "Laundry", "Call Maya", "Start"); shot("02_tools_timers")
        // the old cooking presets and the focus timers are gone
        listOf("Pasta", "Eggs", "Deep work", "Focus", "Kitchen").forEach { gone ->
            val left = rule.onAllNodesWithText(gone, substring = true, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue("[$tag] \"$gone\" should be gone from Tools", left.isEmpty())
        }
        tap("Convert"); expect("Kilograms", "Pounds"); shot("03_tools_convert")
        tap("Temperature"); expect("Gas mark"); shot("04_tools_convert_temperature")
        tap("List"); expect("Sourdough loaf", "Cheddar"); shot("06_tools_list")
    }

    @Test fun sky() { show { Shell(store, act, Module.WEATHER) }; shot("07_sky") }
    @Test fun notes() {
        show { Shell(store, act, Module.NOTES) }; shot("08_notes")
        tap("Sketch"); expect("Eraser", "Undo", "Clear"); shot("08b_notes_draw")
    }
    @Test fun sound() {
        show { Shell(store, act, Module.MEDIA) }
        expect("Nothing playing"); shot("09_media")
        val old = rule.onAllNodesWithText("On this tablet", substring = true, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("[$tag] the separate On this tablet section should be gone", old.isEmpty())
    }

    private fun chooseApps(vararg pkgs: String) {
        store.allowed.toList().forEach { store.setAllowed(it, false) }
        pkgs.forEach { store.setAllowed(it, true) }
    }

    @Test fun apps() {
        chooseApps("com.spotify.music", "com.whatsapp", "com.google.android.youtube", "com.bbc.sounds")
        show { Shell(store, act, Module.APPS) }
        expect("Spotify", "WhatsApp"); shot("12_apps")
        tap("Manage"); expect("Done", "Hidden", "Chrome"); shot("13_apps_manage")
        tap("Done")
        store.setIconStyle("letter"); settle(); expect("SP", "BS"); shot("14_apps_icons_letters")
        store.setIconStyle("colour"); settle(); shot("15_apps_icons_colour")
        store.setIconStyle("void")
    }

    @Test fun appsHidden() {
        // only Spotify chosen: every other app is simply not in the drawer, lock mode or not
        chooseApps("com.spotify.music")
        listOf(false, true).forEach { kiosk ->
            store.set("kiosk", kiosk)
            show { Shell(store, act, Module.APPS) }
            expect("Spotify")
            listOf("WhatsApp", "Chrome", "Netflix").forEach { gone ->
                val left = rule.onAllNodesWithText(gone, useUnmergedTree = true).fetchSemanticsNodes()
                assertTrue("[$tag] hidden app $gone is still in the drawer (kiosk=$kiosk)", left.isEmpty())
            }
        }
        shot("12b_apps_hidden")
        // Manage shows them all again, to choose from
        tap("Manage"); expect("WhatsApp", "Chrome", "Hidden")
        tap("WhatsApp"); tap("Done"); expect("Spotify", "WhatsApp")
        store.set("kiosk", false)
    }

    @Test fun controlCentre() {
        show {
            Box(Modifier.fillMaxSize()) {
                Shell(store, act, Module.FACE)
                ControlCentre(true, store, act, onClose = {}, onSettings = {})
            }
        }
        expect("Wi-Fi", "Bluetooth", "Do not disturb", "Rotation lock", "Auto", "Volume", "Stay awake", "Screen off", "Settings")
        // no way out of VoidOS in the control centre any more
        rule.onAllNodesWithText("Leave VoidOS", substring = true).assertCountEquals(0)
        shot("16_control_centre")
    }

    @Test fun wheel() {
        show { Shell(store, act, Module.FACE) }
        // finger on the left edge, then slide down: the tab wheel opens and follows
        rule.onRoot().performTouchInput { down(Offset(6f, height * 0.5f)); moveTo(Offset(40f, height * 0.5f + 160f)) }
        settle(600)
        // sliding down and out lands on the last slot, Settings
        expect("Settings"); shot("17_tab_wheel")
        rule.onRoot().performTouchInput { up() }
        settle(800)
        // letting go opens it
        expect("Wi-Fi", "Bluetooth")
        // a slide up the edge from there picks a slot above
        rule.onRoot().performTouchInput { down(Offset(6f, height * 0.5f)); moveTo(Offset(6f, height * 0.5f - 220f)) }
        settle(600)
        shot("17b_tab_wheel_up")
        rule.onRoot().performTouchInput { up() }
        settle(800)
        // right edge: the app wheel, which lists apps opened from VoidOS
        rule.onRoot().performTouchInput { down(Offset(width - 6f, height * 0.5f)); moveTo(Offset(width - 60f, height * 0.5f)) }
        settle(600)
        shot("18_app_wheel")
        rule.onRoot().performTouchInput { up() }
        settle(800)
    }

    @Test fun escape() {
        show { com.andysocial.voidos.ui.EscapeOverlay(store, act) {} }
        shot("44_escape_pin")
    }

    /** The way out is hidden: About, then the Device line, twenty taps, no hint on the way. */
    @Test fun hiddenWayOut() {
        show { Shell(store, act, Module.SYSTEM) }
        tap("About"); settle(600)
        val door = rule.onNodeWithTag("about-device", useUnmergedTree = true)
        repeat(19) { door.performTouchInput { click() }; settle(60) }
        listOf("Four digits", "more tap", "taps away", "almost").forEach { hint ->
            assertTrue("[$tag] no hint or door after 19 taps (\"$hint\")",
                rule.onAllNodesWithText(hint, substring = true, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        }
        door.performTouchInput { click() }; settle(600)
        expect("Four digits"); shot("45_hidden_way_out")
    }

    @Test fun system() {
        show { Shell(store, act, Module.SYSTEM) }
        shot("20_system_wifi")
        listOf("Bluetooth", "Network", "Display & sound", "Do not disturb", "Battery", "Home & lock screen", "Date & time",
            "Lock mode", "Storage", "Boot & root", "Android settings", "About").forEachIndexed { i, t ->
            tap(t)
            shot("%02d_system_%s".format(21 + i, t.lowercase().replace(Regex("[^a-z]+"), "_").trim('_')))
            // upright, a page opens over the list: go back to it
            if (tag.startsWith("port")) tap("All settings")
        }
    }

    @Test fun lock() {
        show { LockScreen(store) {} }
        expect("Swipe up"); shot("40_lock")
    }

    @Test fun lockSwipe() {
        var unlocked = false
        show { LockScreen(store) { unlocked = true } }
        swipeUp()
        assertTrue("[$tag] swipe up should unlock", unlocked)
    }

    @Test fun lockPin() {
        store.setPin("1234"); store.set("lockNeedsPin", true)
        var unlocked = false
        show { LockScreen(store) { unlocked = true } }
        swipeUp()
        assertTrue("[$tag] PIN should be asked first", !unlocked)
        expect("ENTER PIN TO UNLOCK"); shot("41_lock_pin")
        "1235".forEach { tap(it.toString()) }
        assertTrue("[$tag] wrong PIN must not unlock", !unlocked)
        expect("WRONG PIN")
        "1234".forEach { tap(it.toString()) }
        settle(800)
        assertTrue("[$tag] right PIN should unlock", unlocked)
        store.set("lockNeedsPin", false)
    }

    private fun swipeUp() {
        rule.onRoot().performTouchInput { swipeUp(startY = bottom * 0.85f, endY = top + bottom * 0.15f, durationMillis = 300) }
        settle(1200)
    }

    @Test fun boot() {
        show { Box(Modifier.fillMaxSize()) { BootAnim {} } }
        shot("42_boot_mid")
    }

    @Test fun setup() {
        store.set("setupDone", false)
        show { Setup(store, act) }
        shot("43_setup")
        store.set("setupDone", true)
    }
}

@Config(qualifiers = "w960dp-h600dp-xlarge-land-tvdpi", sdk = [33])
class LandscapeScreensTest : ScreensTest("land")

@Config(qualifiers = "w600dp-h960dp-xlarge-port-tvdpi", sdk = [33])
class PortraitScreensTest : ScreensTest("port")

/** The same screens with Android's display size turned up a notch (hdpi): tighter still. Audited, not kept. */
@Config(qualifiers = "w853dp-h533dp-xlarge-land-hdpi", sdk = [33])
class LandscapeLargeTextScreensTest : ScreensTest("land_big", keepShots = false)

@Config(qualifiers = "w533dp-h853dp-xlarge-port-hdpi", sdk = [33])
class PortraitLargeTextScreensTest : ScreensTest("port_big", keepShots = false)
