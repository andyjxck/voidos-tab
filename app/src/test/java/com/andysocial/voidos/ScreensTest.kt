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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipe
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assertIsDisplayed
import com.andysocial.voidos.ui.BootAnim
import com.andysocial.voidos.ui.ControlCentre
import com.andysocial.voidos.ui.CcPart
import com.andysocial.voidos.ui.CcSource
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.longClick
import com.andysocial.voidos.ui.LocalPortrait
import com.andysocial.voidos.ui.LockScreen
import com.andysocial.voidos.ui.Module
import com.andysocial.voidos.ui.Setup
import com.andysocial.voidos.ui.Shell
import com.andysocial.voidos.ui.V
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import androidx.compose.ui.unit.dp
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
        com.andysocial.voidos.ui.LockState.locked.value = false // the Shell tests start unlocked
        // the ambient clock counts real minutes since the last touch; a long class run must not drift into it
        com.andysocial.voidos.ui.Idle.touch(); com.andysocial.voidos.ui.Idle.ambient.value = false
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
            val found = rule.onAllNodesWithText(t, substring = true, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(hasContentDescription(t, substring = true, ignoreCase = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
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

    /** With the alarm widget on the face, the next alarm isn't repeated under the time. */
    @Test fun alarmNotTwice() {
        Alarms.list.value.forEach { Alarms.remove(act, it.id) }
        val t = java.time.LocalTime.now().plusHours(2)
        Alarms.put(act, Alarm(id = 5, hour = t.hour, minute = t.minute, label = "Soon"))
        fun underTime() = rule.onAllNodes(hasTestTag("next_alarm"), useUnmergedTree = true).fetchSemanticsNodes().size
        store.saveWidgets(emptyList())
        show { Shell(store, act, Module.FACE) }
        assertEquals("[$tag] next alarm should show under the time", 1, underTime())
        rule.runOnUiThread { store.addWidget("alarm") }
        settle(400)
        assertEquals("[$tag] alarm shown twice", 0, underTime())
        store.saveWidgets(emptyList())
        Alarms.remove(act, 5)
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
        expect("Timers", "Laundry", "Call Maya", "Start"); shot("02_tools_timers")
        // the old cooking presets and the focus timers are gone
        listOf("Pasta", "Eggs", "Deep work", "Focus", "Kitchen").forEach { gone ->
            val left = rule.onAllNodesWithText(gone, substring = true, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue("[$tag] \"$gone\" should be gone from Tools", left.isEmpty())
        }
        // Tools keeps Time and Organise on one glass bar; Camera and Utilities are pages of their own
        expect("Time", "Organise", "Stopwatch", "World")
        listOf("QR scan", "Magnifier", "Convert", "Level").forEach { gone ->
            assertTrue("[$tag] \"$gone\" should have moved off Tools", rule.onAllNodesWithText(gone, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        }
        tap("Organise"); tap("List"); expect("Sourdough loaf", "Cheddar"); shot("06_tools_list")
    }

    @Test fun utilitiesPage() {
        show { Shell(store, act, Module.UTILITIES) }
        expect("Convert", "Level", "Sound", "Torch")
        tap("Convert"); expect("Kilograms", "Pounds"); shot("03_tools_convert")
        tap("Temperature"); expect("Gas mark"); shot("04_tools_convert_temperature")
        // cast, sending files, FM radio and the ring light are gone
        listOf("Cast", "Send files", "FM radio", "Ring light").forEach { gone ->
            val left = rule.onAllNodesWithText(gone, useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue("[$tag] \"$gone\" should be gone from Tools", left.isEmpty())
        }
    }

    @Test fun newTools() {
        show { Shell(store, act, Module.TOOLS) }
        tap("Organise"); tap("Files"); expect("Tablet"); shot("06b_tools_files")
    }

    @Test fun cameraPage() {
        show { Shell(store, act, Module.CAMERA) }
        expect("QR scan", "Doc scan", "Magnifier", "Mirror"); shot("06e_camera_page")
        tap("Doc scan"); shot("06c_tools_docscan")
    }

    @Test fun torchFull() {
        show { Shell(store, act, Module.UTILITIES) }
        tap("Torch"); settle(400)
        // the torch takes the whole screen, with one button to close it
        assertTrue("[$tag] torch fills the screen", rule.onAllNodesWithTag("full_tool").fetchSemanticsNodes().isNotEmpty())
        shot("06d_tools_torch_full")
        rule.onNodeWithContentDescription("Close Torch").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); settle(600)
        assertTrue("[$tag] torch closed", rule.onAllNodesWithTag("full_tool").fetchSemanticsNodes().isEmpty())
    }

    @Test fun alarms() {
        Alarms.list.value.forEach { Alarms.remove(act, it.id) }
        Alarms.put(act, Alarm(id = 1, hour = 6, minute = 45, days = AlarmMath.WEEKDAYS, label = "Gym", sound = "rooster", rampSec = 60))
        Alarms.put(act, Alarm(id = 2, hour = 9, minute = 30, days = AlarmMath.WEEKENDS, sound = "sunrise"))
        show { Shell(store, act, Module.TOOLS) }
        tap("Alarms"); expect("Next alarm", "Gym", "Weekdays", "Rooster, gradual", "Sunrise", "Skip next"); shot("05_tools_alarms")
        rule.onNodeWithTag("alarm_1").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); settle(800)
        expect("Edit alarm", "Repeat", "Weekdays", "Gradual volume", "Snooze", "Delete alarm"); shot("05b_alarm_edit")
        rule.onNodeWithTag("alarm_sound").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); settle(800)
        expect("Gentle", "Classic", "Loud", "Funny", "Builds up", "Screaming goat"); shot("05c_alarm_sounds")
        tap("Cancel")
        rule.onNodeWithTag("alarm_add").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); settle(800)
        expect("New alarm", "Save"); shot("05d_alarm_new")
        tap("Cancel")
        // ringing, over VoidOS
        Alarms.ringing.value = Ringing(Alarms.get(1)!!, System.currentTimeMillis())
        show { Box(Modifier.fillMaxSize()) { Shell(store, act, Module.TOOLS); com.andysocial.voidos.ui.AlarmOverlay() } }
        expect("Gym", "Snooze", "9 minutes", "Stop"); shot("05e_alarm_ringing")
        Alarms.ringing.value = null
        Alarms.list.value.forEach { Alarms.remove(act, it.id) }
    }

    @Test fun sky() { show { Shell(store, act, Module.WEATHER) }; shot("07_sky") }

    /**
     * A fixed, worst-case forecast (minus temperatures, the longest words, 100% everywhere)
     * and a second kept place, so the Weather layout is checked on the same numbers every run
     * instead of whatever the sky is doing.
     */
    private fun seedWeather() {
        store.places.toList().forEach { store.removePlace(it) }
        store.addPlace("Henley-in-Arden", 52.29f, -1.78f)
        store.chooseAutoPlace(false)
        store.setPlace("Henley-in-Arden", 52.29f, -1.78f)
        pinWeather()
    }

    private fun pinWeather() {
        val hours = (0 until 12).map { i -> com.andysocial.voidos.ui.Hour("%02d".format((13 + i) % 24), -12.4 + i * 1.7, listOf(96, 3, 61, 71, 0, 2)[i % 6], listOf(100, 85, 40, 5, 0, 60)[i % 6]) }
        val days = listOf("Today", "Fri", "Sat", "Sun", "Mon", "Tue").mapIndexed { i, l ->
            com.andysocial.voidos.ui.Day(l, listOf(96, 3, 61, 75, 0, 82)[i], -1.0 + i * 3, -11.6 + i, listOf(100, 40, 85, 100, 0, 65)[i])
        }
        val wx = com.andysocial.voidos.ui.Wx(-3.4, -10.6, 96, 34.0, 100, true, "07:35", "16:10", hours, days, System.currentTimeMillis())
        // the screen's own refresh can land just after and blank it for a new place: pin until it stays
        repeat(5) {
            rule.runOnUiThread {
                com.andysocial.voidos.ui.WeatherRepo.state.value = wx
                com.andysocial.voidos.ui.WeatherRepo.offline.value = false
                com.andysocial.voidos.ui.WeatherRepo.error.value = null
            }
            settle(600)
            if (com.andysocial.voidos.ui.WeatherRepo.state.value === wx) return
        }
    }

    /** The audit lines a shot added: empty when nothing was cut off, squashed, overlapping or off screen. */
    private fun issuesOf(name: String) = clipped.filter { it.startsWith("$name ") }

    private fun openHalf(m: Module, first: Boolean, other: com.andysocial.voidos.ui.Pane? = null) {
        val pane = com.andysocial.voidos.ui.Pane.screen(m)
        rule.runOnUiThread {
            com.andysocial.voidos.ui.Book.set(act, if (first) com.andysocial.voidos.ui.BookState(first = pane, second = other) else com.andysocial.voidos.ui.BookState(first = other, second = pane))
        }
        settle(900)
    }

    private fun boundsOf(tagName: String) = rule.onNodeWithTag(tagName, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /**
     * Weather in either half of the book, in this orientation, beside the clock and beside Notes:
     * the compact layout, nothing cut or overlapping, every hour and day on screen or a scroll away,
     * no hour squeezed narrower than its own words, and the second place still in the place bar.
     */
    @Test fun weatherHalfWidth() {
        com.andysocial.voidos.ui.Book.reset()
        store.saveWidgets(emptyList())
        seedWeather()
        show { Shell(store, act, Module.FACE) }
        val notes = com.andysocial.voidos.ui.Pane.screen(Module.NOTES)
        for (other in listOf(null, notes)) for (first in listOf(true, false)) {
            com.andysocial.voidos.ui.Idle.touch()
            openHalf(Module.WEATHER, first, other)
            pinWeather()
            val side = if (first) "left" else "right"
            val name = "46_weather_half_${side}${if (other != null) "_notes" else ""}"
            shot(name)
            assertTrue("[$tag] $name: ${issuesOf(name)}", issuesOf(name).isEmpty())
            val pane = boundsOf(if (first) "bookFirst" else "bookSecond")
            val body = boundsOf("weatherCompact")
            assertTrue("[$tag] $name: compact Weather $body outside its half $pane", body.left >= pane.left - 1 && body.right <= pane.right + 1)
            // both places in the bar, the hero readable
            expect("Here", "Henley-in-Arden", "°", "Feels", "Sunrise", "Sunset", "Next 12 hours")
            // all 12 hours under the hours card, no column narrower than 52 dp, each inside the half once scrolled to
            val inHours = androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("weatherHours"))
            val rain = rule.onAllNodes(androidx.compose.ui.test.hasText("%", substring = true) and inHours, useUnmergedTree = true).fetchSemanticsNodes()
            assertEquals("[$tag] $name: rain chance under every hour", 12, rain.size)
            // each hour's column: the distance from one hour to the next along a row
            val rows = rain.groupBy { (it.positionInRoot.y / 8).toInt() }.values
            val minCell = rows.flatMap { r -> r.map { it.positionInRoot.x + it.size.width / 2f }.sorted().zipWithNext { a, b -> b - a } }.minOrNull()?.div(rule.density.density) ?: 999f
            assertTrue("[$tag] $name: an hour squeezed to a ${minCell}dp column", minCell >= 52f)
            rain.forEach { n ->
                val l = n.positionInRoot.x; val r = l + n.size.width
                assertTrue("[$tag] $name: an hour's rain chance at $l..$r sticks out of $pane", l >= pane.left - 1 && r <= pane.right + 1)
            }
            // all six days there; scroll down like a finger would until the last one is in view
            val inDays = androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("weatherDays"))
            listOf("Today", "Fri", "Sat", "Sun", "Mon", "Tue").forEach { d ->
                rule.onNode(androidx.compose.ui.test.hasText(d) and inDays, useUnmergedTree = true).assertExists()
            }
            val tue = rule.onNode(androidx.compose.ui.test.hasText("Tue") and inDays, useUnmergedTree = true)
            fun tueIn(): Boolean { val n = tue.fetchSemanticsNode(); val top = n.positionInRoot.y; return top >= pane.top - 1 && top + n.size.height <= pane.bottom + 1 }
            var swipes = 0
            while (!tueIn() && swipes < 4) {
                rule.onNodeWithTag("weatherCompact", useUnmergedTree = true).performTouchInput {
                    swipe(androidx.compose.ui.geometry.Offset(centerX, bottom - 20f), androidx.compose.ui.geometry.Offset(centerX, top + 20f), 500)
                }
                settle(900); swipes++
            }
            assertTrue("[$tag] $name: last day still not in view after $swipes swipes", tueIn())
            shot("${name}_scrolled")
            assertTrue("[$tag] ${name}_scrolled: ${issuesOf("${name}_scrolled")}", issuesOf("${name}_scrolled").isEmpty())
            // the swipe up only scrolled: still on the same place, still two panes
            // scrolling down to the days is not a swipe to the next place
            assertTrue("[$tag] $name: scrolling moved place to ${store.city}", store.city == "Henley-in-Arden")
            assertTrue("[$tag] $name: book closed", com.andysocial.voidos.ui.Book.state.value.isOpen)
        }
        rule.runOnUiThread { com.andysocial.voidos.ui.Book.reset() }
        settle(600)
    }

    /** Full-screen Weather keeps its own layout (not the compact one) and nothing on it is cut off. */
    @Test fun weatherFullScreen() {
        com.andysocial.voidos.ui.Book.reset()
        seedWeather()
        show { Shell(store, act, Module.WEATHER) }
        pinWeather()
        shot("46_weather_full")
        assertTrue("[$tag] full-screen Weather: ${issuesOf("46_weather_full")}", issuesOf("46_weather_full").isEmpty())
        assertTrue("[$tag] full screen should not be compact", rule.onAllNodesWithTag("weatherCompact", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        expect("Here", "Henley-in-Arden", "Sunrise", "Sunset", "Open-Meteo", "Six days", "Tue")
    }

    /** Every screen that can go in a half of the book, on each half: nothing cut, squashed, overlapping or off screen. */
    @Test fun everyScreenAtHalfWidth() {
        com.andysocial.voidos.ui.Book.reset()
        store.saveWidgets(emptyList())
        seedWeather()
        show { Shell(store, act, Module.FACE) }
        val bad = mutableListOf<String>()
        for (m in Module.values().filter { com.andysocial.voidos.ui.bookable(it) }) for (first in listOf(true, false)) {
            com.andysocial.voidos.ui.Idle.touch()
            openHalf(m, first)
            if (m == Module.WEATHER) pinWeather()
            val name = "47_half_${m.name.lowercase()}_${if (first) "left" else "right"}"
            shot(name)
            bad += issuesOf(name)
            // every tool on the Tools, Camera and Utilities pages, not just the first one
            val tools = com.andysocial.voidos.ui.KGroup.on(m).flatMap { it.tabs }.filter { it !in com.andysocial.voidos.ui.FULL_TOOLS }.drop(1)
            for (t in tools) {
                rule.runOnUiThread { com.andysocial.voidos.ui.pendingTool.value = t }
                settle(700)
                val tn = "${name}_${t.name.lowercase()}"
                rule.onRoot().captureRoboImage(File(outDir, "${tag}_$tn.png").path)
                audit(tn)
                bad += issuesOf(tn)
            }
            rule.runOnUiThread { com.andysocial.voidos.ui.pendingTool.value = null }
        }
        rule.runOnUiThread { com.andysocial.voidos.ui.Book.reset() }
        settle(600)
        assertTrue("[$tag] squashed at half width:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
    private fun tapDesc(d: String) { rule.onAllNodesWithContentDescription(d)[0].performClick(); settle() }
    private fun typeInto(i: Int, text: String) { rule.onAllNodes(hasSetTextAction())[i].performTextInput(text); settle() }

    @Test fun notes() {
        show { Shell(store, act, Module.NOTES) }
        expect("No notes yet"); shot("08_notes_empty")
        // a new note: the first line becomes its title in the list
        tapDesc("New note")
        // the list's search is field 0 in landscape; upright the note opens on its own, so it's the only field
        val field = rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size - 1
        typeInto(field, "Shopping\nmilk, eggs, bread")
        settle(900) // autosave
        assertEquals("Shopping\nmilk, eggs, bread", store.notes.first().text)
        expect("4 words")
        // Home and its colour aren't repeated in the note's header: they live in Home's "Add to face" menu
        fun hasDesc(d: String) = rule.onAllNodesWithContentDescription(d, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        listOf("Show on Home", "Remove from Home", "Widget colour").forEach { assertTrue("[$tag] $it should be gone from the note header", !hasDesc(it)) }
        assertTrue("[$tag] Draw still in the header", hasDesc("Draw"))
        shot("08_notes")
        // drawing: the pen tools sit in one capsule: undo/redo, tools, colour, size, brush, clear
        tapDesc("Draw"); expect("Eraser", "Clear")
        fun has(d: String) = rule.onAllNodesWithContentDescription(d, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        listOf("Undo", "Redo", "Pen", "Highlighter", "Eraser", "Colour", "Size", "Brush options", "Clear page").forEach {
            assertTrue("[$tag] pen tool $it missing", has(it))
        }
        shot("08b_notes_draw")
        // thickness: the size panel has a slider and thinner/thicker steps
        tapDesc("Size"); settle(400)
        listOf("Thinner", "Thicker", "Line preview").forEach { assertTrue("[$tag] size control $it missing", has(it)) }
        shot("08d_notes_size")
        // colour: wheel and swatches
        tapDesc("Colour"); settle(400)
        assertTrue("[$tag] colour wheel missing", has("Colour wheel"))
        shot("08e_notes_colour")
        // line style: solid, dotted and dashed in the brush menu
        tapDesc("Brush options"); settle(400)
        listOf("Solid", "Dotted", "Dashed").forEach { assertTrue("[$tag] line style $it missing", has(it)) }
        shot("08f_notes_brush")
        tapDesc("Brush options"); settle(300)
        tapDesc("Done drawing")
        if (tag.startsWith("port")) {
            // upright the note fills the screen and there's a way back to the list
            tap("Notes"); expect("Shopping"); shot("08c_notes_list")
        } else expect("Shopping")
    }

    /** Standby: digital or analogue face, and a glowing ring round the edge while charging. */
    @Test fun standbyClock() {
        store.chooseAmbientFace("digital")
        var bat by mutableStateOf(com.andysocial.voidos.ui.Battery(64, false))
        show { com.andysocial.voidos.ui.AmbientClock(store, bat) }
        settle(300)
        fun has(d: String) = rule.onAllNodesWithContentDescription(d, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        assertTrue("[$tag] no ring off charge", !has("Charging ring"))
        assertTrue("[$tag] digital shows no hands", !has("Analogue clock"))
        shot("60_standby_digital")
        bat = com.andysocial.voidos.ui.Battery(64, true)
        settle(300)
        assertTrue("[$tag] ring while charging", has("Charging ring"))
        expect("Charging")
        shot("61_standby_digital_charging")
        rule.runOnUiThread { store.chooseAmbientFace("analog") }
        settle(300)
        assertTrue("[$tag] analogue face", has("Analogue clock"))
        assertEquals("analog", store.ambientFace)
        shot("62_standby_analogue_charging")
        store.chooseAmbientFace("digital")
    }

    @Test fun notesSketch() {
        show { Shell(store, act, Module.NOTES) }
        tapDesc("Sketch"); expect("Eraser", "Clear")
        assertEquals(1, store.notes.size)
    }

    @Test fun sound() {
        show { Shell(store, act, Module.MEDIA) }
        expect("Not playing", "Brown noise"); shot("09_media")
        val old = rule.onAllNodesWithText("On this tablet", substring = true, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("[$tag] the separate On this tablet section should be gone", old.isEmpty())
        // the same controls whatever plays
        listOf("Play", "Previous", "Next", "Back 10 seconds", "Forward 10 seconds").forEach {
            assertTrue("[$tag] control $it missing", rule.onAllNodesWithContentDescription(it, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        }
        // your files are in a sidebar behind Files
        tapDesc("Files"); settle(500); expect("Your music"); shot("09b_media_files")
        tapDesc("Close files"); settle(500)
        rule.onAllNodesWithText("Your music").assertCountEquals(0)
    }

    private fun chooseApps(vararg pkgs: String) {
        store.allowed.toList().forEach { store.setAllowed(it, false) }
        pkgs.forEach { store.setAllowed(it, true) }
    }

    @Test fun apps() {
        chooseApps("com.spotify.music", "com.whatsapp", "com.google.android.youtube", "com.bbc.sounds")
        show { Shell(store, act, Module.APPS) }
        store.setIconStyle("glass"); settle()
        expect("Spotify", "WhatsApp"); shot("12_apps")
        tap("Manage"); expect("Done", "Shown", "Hidden"); shot("13_apps_manage")
        typeInto(0, "chro"); expect("Chrome"); rule.onAllNodesWithText("Spotify").assertCountEquals(0)
        tap("Done")
        store.setIconStyle("letter"); settle(); expect("SP", "BS"); shot("14_apps_icons_letters")
        store.setIconStyle("colour"); settle(); shot("15_apps_icons_colour")
        store.setIconStyle("void"); settle(); shot("15b_apps_icons_plain")
        store.setIconStyle("glass")
    }

    @Test fun appsHidden() {
        // only Spotify chosen: every other app is simply not in the drawer, lock mode or not
        chooseApps("com.spotify.music")
        listOf(false, true).forEach { kiosk ->
            store.set("kiosk", kiosk)
            show { Shell(store, act, Module.APPS) }
            expect("Spotify")
            listOf("WhatsApp", "Chrome", "Netflix").forEach { gone ->
                val left = rule.onAllNodesWithText(gone, useUnmergedTree = true).fetchSemanticsNodes() +
                    rule.onAllNodes(hasContentDescription(gone), useUnmergedTree = true).fetchSemanticsNodes()
                assertTrue("[$tag] hidden app $gone is still in the drawer (kiosk=$kiosk)", left.isEmpty())
            }
        }
        shot("12b_apps_hidden")
        // Manage shows them all again, to choose from
        tap("Manage"); expect("Hidden")
        typeInto(0, "whats"); expect("WhatsApp")
        tap("WhatsApp"); tap("Done"); expect("Spotify", "WhatsApp")
        store.set("kiosk", false)
    }

    private fun fakeRadios() {
        CcSource.live = false
        CcSource.wifiOn = { true }
        CcSource.ssid = { "Andy's Wi-Fi" }
        CcSource.networks = {
            listOf(
                Radio.Net("Andy's Wi-Fi", 4, Radio.Sec.WPA, connected = true, saved = true),
                Radio.Net("BT-Hub-7F2A", 3, Radio.Sec.WPA),
                Radio.Net("Costa Free WiFi", 2, Radio.Sec.OPEN),
                Radio.Net("Neighbour 5G", 1, Radio.Sec.SAE),
            )
        }
        CcSource.btOn = { true }
        CcSource.devices = {
            listOf(
                Radio.Dev("AirPods Pro", "AA:BB:CC:00:00:01", Radio.Kind.HEADPHONES, paired = true, connected = true),
                Radio.Dev("JBL Flip 5", "AA:BB:CC:00:00:02", Radio.Kind.SPEAKER, paired = true),
                Radio.Dev("Magic Keyboard", "AA:BB:CC:00:00:03", Radio.Kind.KEYBOARD, paired = true),
            )
        }
    }

    private fun showCc(part: CcPart? = null) {
        fakeRadios()
        show {
            Box(Modifier.fillMaxSize()) {
                Shell(store, act, Module.FACE)
                ControlCentre(true, store, act, onClose = {}, onSettings = {}, startExpanded = part)
            }
        }
        settle(600)
    }

    private fun described(d: String) = rule.onAllNodes(hasContentDescription(d), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Nothing in the control centre may run off the screen (the old bottom bar did in landscape). */
    private fun fitsOnScreen(name: String) {
        val off = clipped.filter { it.startsWith(name) && it.contains("OFFSCREEN") }
        assertTrue("[$tag] $name runs off screen: $off", off.isEmpty())
    }

    @Test fun controlCentre() {
        showCc()
        listOf("Wi-Fi", "Bluetooth", "Focus", "Rotation lock", "Brightness", "Volume").forEach {
            assertTrue("[$tag] control centre should have $it", described(it))
        }
        expect("Stay awake", "Settings", "Not playing")
        rule.onAllNodesWithText("Leave VoidOS", substring = true).assertCountEquals(0)
        // removed at the user's request: no loose Cast or Send files buttons
        listOf("Cast screen", "Send files", "FM radio").forEach {
            assertTrue("[$tag] control centre shouldn't have $it", !described(it))
        }
        listOf("Notifications", "Power", "Airplane mode", "Record screen").forEach {
            assertTrue("[$tag] control centre should have $it", described(it))
        }
        shot("16_control_centre"); fitsOnScreen("16_control_centre")
        // holding Wi-Fi opens the network list, like an iPad
        rule.onAllNodes(hasContentDescription("Wi-Fi"), useUnmergedTree = true).onFirst().performTouchInput { longClick() }
        settle(900)
        expect("Connected to Andy's Wi-Fi", "BT-Hub-7F2A", "Costa Free WiFi")
        CcSource.reset()
    }

    @Test fun controlCentreWifi() {
        showCc(CcPart.WIFI)
        expect("Networks", "Andy's Wi-Fi", "BT-Hub-7F2A", "Neighbour 5G")
        shot("16b_cc_wifi"); fitsOnScreen("16b_cc_wifi")
        // a locked network asks for its password in a glass field
        tap("BT-Hub-7F2A")
        expect("Password for BT-Hub-7F2A", "Join", "Show")
        shot("16c_cc_wifi_password")
        // the connected one offers to forget it
        tap("Andy's Wi-Fi")
        expect("Forget this network")
        CcSource.reset()
    }

    @Test fun controlCentreModules() {
        listOf(
            CcPart.CONNECT to listOf("Connections", "Andy's Wi-Fi"),
            CcPart.BT to listOf("My devices", "AirPods Pro", "Connected to AirPods Pro"),
            CcPart.DND to listOf("Alarms only", "Priority only", "Total silence"),
            CcPart.ROTATE to listOf("Turn with the tablet", "Always portrait"),
            CcPart.BRIGHT to listOf("Brightness", "Auto", "Night Light"),
            CcPart.VOLUME to listOf("Media", "Alarms", "Notifications"),
            CcPart.MEDIA to listOf("Nothing is playing"),
        ).forEachIndexed { i, (part, words) ->
            showCc(part)
            expect(*words.toTypedArray())
            val name = "16${'d' + i}_cc_${part.name.lowercase()}"
            shot(name); fitsOnScreen(name)
        }
        CcSource.reset()
    }

    // ---- edge wheels: touches go in through the activity, exactly as on the tablet,
    // so the test covers the routing that decides an edge swipe before the page sees it

    private var touchBase = 0L
    private var touchT = 0L
    private fun ev(action: Int, x: Float, y: Float) {
        if (action == android.view.MotionEvent.ACTION_DOWN) { touchBase = android.os.SystemClock.uptimeMillis(); touchT = touchBase }
        else touchT += 16
        rule.runOnUiThread {
            val e = android.view.MotionEvent.obtain(touchBase, touchT, action, x, y, 0)
            act.dispatchTouchEvent(e)
            e.recycle()
        }
    }
    private fun down(x: Float, y: Float) = ev(android.view.MotionEvent.ACTION_DOWN, x, y)
    private fun move(x: Float, y: Float) = ev(android.view.MotionEvent.ACTION_MOVE, x, y)
    private fun up(x: Float, y: Float) = ev(android.view.MotionEvent.ACTION_UP, x, y)
    /** A drag in small steps, the way a finger reports it. */
    private fun drag(x0: Float, y0: Float, x1: Float, y1: Float, steps: Int = 8) {
        for (k in 1..steps) move(x0 + (x1 - x0) * k / steps, y0 + (y1 - y0) * k / steps)
    }
    private val winW get() = act.window.decorView.width.toFloat()
    private val winH get() = act.window.decorView.height.toFloat()
    private val dens get() = act.resources.displayMetrics.density

    @Test fun wheel() {
        show { Shell(store, act, Module.FACE) }
        settle(300)
        val midY = winH * 0.5f
        // finger on the left edge, then slide down and out: the tab wheel opens and follows
        down(4f, midY); drag(4f, midY, 40f, midY + 160f)
        settle(600)
        // sliding down and out lands on the last slots: Settings, then the pencil past it
        shot("17_tab_wheel")
        touchT += 600 // held on the slot a moment, as a person choosing does
        up(40f, midY + 160f)
        settle(800)
        // letting go opens Settings, or on the pencil the picker for which screens the wheel shows
        if (rule.onAllNodesWithText("Side wheel").fetchSemanticsNodes().isNotEmpty()) {
            expect("Home", "Settings", "Done"); shot("17a_wheel_pages"); tap("Done")
        } else expect("Wi-Fi", "Bluetooth")
        // a slide up the edge from there picks a slot above
        down(4f, midY); drag(4f, midY, 4f, midY - 220f)
        settle(600)
        shot("17b_tab_wheel_up")
        touchT += 600
        up(4f, midY - 220f)
        settle(800)
        // right edge: the app wheel, which lists apps opened from VoidOS
        down(winW - 4f, midY); drag(winW - 4f, midY, winW - 60f, midY)
        settle(600)
        shot("18_app_wheel")
        up(winW - 60f, midY)
        settle(800)
    }

    /** A swipe whose first point lands a little way in from the bezel still opens the wheel, and the page doesn't turn. */
    @Test fun edgeFlickFromBezel() {
        show { Shell(store, act, Module.TOOLS) }
        settle(300)
        val midY = winH * 0.5f
        val x0 = 50f * dens // past the hard strip, inside the soft one
        down(x0, midY); drag(x0, midY, x0 + 140f * dens, midY + 10f, steps = 4)
        settle(600)
        // the tab wheel is open, level with the finger
        expect("Notes"); shot("17c_flick_from_bezel")
        up(x0 + 140f * dens, midY + 10f)
        settle(800)
        // a quick flick leaves the wheel open to tap (1.7.0 picked a slot and shut it in a blink,
        // which on the tablet looked like no wheel at all) and the page hasn't moved
        assertEquals(Module.TOOLS, com.andysocial.voidos.ui.ShellNow.module.value)
        listOf("Notes", "Weather", "Settings").forEach { rule.onAllNodesWithContentDescription(it, useUnmergedTree = true).fetchSemanticsNodes().let { n -> assertTrue("$it slot; edge log: ${com.andysocial.voidos.ui.EdgeSwipe.last.value}; descs: ${rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("d") { x -> x.config.contains(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) }, useUnmergedTree = true).fetchSemanticsNodes().map { x -> x.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription] }}", n.isNotEmpty()) } }
        shot("17d_flick_leaves_wheel_open")
        rule.onAllNodesWithContentDescription("Notes", useUnmergedTree = true)[0].performClick()
        settle(800)
        assertEquals(Module.NOTES, com.andysocial.voidos.ui.ShellNow.module.value)
    }

    /**
     * The 1.4.0-1.7.0 bug: the wheel's drawing called its own close() on the first frame,
     * so it vanished while the finger was still down. Hold a finger on the edge for two
     * seconds: the wheel must still be there, fully drawn.
     */
    @Test fun wheelStaysWhileHeld() {
        show { Shell(store, act, Module.FACE) }
        settle(300)
        val midY = winH * 0.5f
        down(4f, midY); drag(4f, midY, 60f * dens, midY + 30f * dens, steps = 6)
        settle(100)
        // Robolectric only draws when asked: the bug lived in the draw code, so draw it
        shot("17g_wheel_held")
        settle(2000)
        shot("17g_wheel_held")
        listOf("Tools", "Weather", "Settings").forEach { assertTrue("$it slot after 2s", rule.onAllNodesWithContentDescription(it, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) }
        assertTrue(com.andysocial.voidos.ui.EdgeSwipe.last.value != "wheel closed")
        up(60f * dens, midY + 30f * dens)
        settle(600)
    }

    /**
     * The wheel over another app: the same glass wheel, in its own window, fed by the
     * edge strips (here fed directly). Picking a screen asks VoidOS to come back on it.
     */
    @Test fun wheelOverAnotherApp() {
        var picked: String? = null
        show {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFFF2F2F5))) {
                androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color(0xFFE8ECF4), androidx.compose.ui.graphics.Color(0xFFFFFFFF))))) {
                        com.andysocial.voidos.ui.VText("Some other app", size = androidx.compose.ui.unit.TextUnit(40f, androidx.compose.ui.unit.TextUnitType.Sp), color = androidx.compose.ui.graphics.Color(0xFF222222), modifier = Modifier.align(androidx.compose.ui.Alignment.Center))
                    }
                }
                CompositionLocalProvider(com.andysocial.voidos.ui.LocalRoom provides com.andysocial.voidos.ui.Rooms.overlay) {
                    com.andysocial.voidos.ui.EdgeWheels(
                        tabs = Module.entries.map { m -> com.andysocial.voidos.ui.WheelItem(m.name, m.label) { mod, c -> com.andysocial.voidos.ui.Glyph(m.glyph, c, mod) } },
                        currentTab = "", onTab = { picked = it }, apps = emptyList(), onApp = {}, onAllApps = {}, overlay = true,
                    )
                }
            }
        }
        settle(300)
        val sink = com.andysocial.voidos.ui.EdgeSwipe.overlaySink
        assertTrue("overlay wheel listening", sink != null)
        val midY = winH * 0.5f
        val t = android.os.SystemClock.uptimeMillis()
        rule.runOnUiThread {
            sink!!.begin(true, 2f, midY, t)
            sink.move(30f * dens, midY + 10f, t + 40)
            sink.move(70f * dens, midY + 20f * dens, t + 120)
        }
        settle(600)
        shot("17h_wheel_over_app")
        listOf("Tools", "Notes", "Settings").forEach { assertTrue("$it slot over the app", rule.onAllNodesWithContentDescription(it, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) }
        rule.runOnUiThread { sink!!.end(70f * dens, midY + 20f * dens, t + 900) }
        settle(800)
        assertTrue("picked a screen: $picked", picked != null)
    }

    /**
     * The book: Notes dropped on the right half opens there, live, and the watch face slides to
     * the left half. A second screen replaces the first, so Home always keeps the other half.
     * Closing the half leaves the full watch face.
     */
    @Test fun bookSplitHome() {
        com.andysocial.voidos.ui.Book.reset()
        store.saveWidgets(emptyList())
        show { Shell(store, act, Module.FACE) }
        settle(300)
        val ctx = act
        fun b(t: String) = rule.onNodeWithTag(t, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val port = winH > winW
        rule.runOnUiThread { com.andysocial.voidos.ui.Book.drop(ctx, com.andysocial.voidos.ui.Pane.screen(Module.NOTES), toFirst = false) }
        settle(900)
        shot("40_book_notes_right")
        val face = b("bookFace"); val notes = b("bookSecond")
        if (port) assertTrue("[$tag] face above notes: $face / $notes", face.bottom <= notes.top + 1f && face.height > 0.4f * (notes.bottom - face.top))
        else assertTrue("[$tag] face left of notes: $face / $notes", face.right <= notes.left + 1f && face.width > 0.4f * (notes.right - face.left))
        assertTrue(rule.onAllNodesWithContentDescription("Close Notes", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        // dropped on the left instead: the face goes right
        rule.runOnUiThread { com.andysocial.voidos.ui.Book.drop(ctx, com.andysocial.voidos.ui.Pane.screen(Module.NOTES), toFirst = true) }
        settle(900)
        val face2 = b("bookFace"); val notes2 = b("bookFirst")
        if (port) assertTrue("[$tag] face below notes", face2.top >= notes2.bottom - 1f)
        else assertTrue("[$tag] face right of notes: $face2 / $notes2", face2.left >= notes2.right - 1f)
        shot("41_book_notes_left")
        // Weather dropped on the face's half: Notes stays on the left, both open, and the time
        // and date move up into the island
        rule.runOnUiThread { com.andysocial.voidos.ui.Book.drop(ctx, com.andysocial.voidos.ui.Pane.screen(Module.WEATHER), toFirst = false) }
        settle(900)
        shot("42_book_two_open")
        val B = com.andysocial.voidos.ui.BookState(com.andysocial.voidos.ui.Pane.screen(Module.NOTES), com.andysocial.voidos.ui.Pane.screen(Module.WEATHER))
        assertEquals("[$tag] Notes and Weather both open", B, com.andysocial.voidos.ui.Book.state.value)
        assertTrue("[$tag] Notes still open", rule.onAllNodesWithContentDescription("Close Notes", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue("[$tag] Weather open", rule.onAllNodesWithContentDescription("Close Weather", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue("[$tag] face covered", rule.onAllNodesWithTag("bookFace", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        val isl = b("timeIsland"); val n3 = b("bookFirst"); val w3 = b("bookSecond")
        // the island sits in the band above the panes' content (52 dp), clear of both
        val band = with(rule.density) { 52.dp.toPx() }
        assertTrue("[$tag] island in the top band: $isl", isl.height > 0f && isl.bottom <= minOf(n3.top, w3.top) + band + 1f)
        // swipe away and back within five seconds: still split
        val now = System.currentTimeMillis()
        rule.runOnUiThread {
            com.andysocial.voidos.ui.Book.onHome(ctx, false, now)
            assertFalse(com.andysocial.voidos.ui.Book.onHome(ctx, true, now + 4_000))
        }
        assertEquals("[$tag] back within 5s keeps the split", B, com.andysocial.voidos.ui.Book.state.value)
        // sitting on the split for a long time never closes it
        rule.runOnUiThread { assertFalse(com.andysocial.voidos.ui.Book.onHome(ctx, true, now + 60_000)) }
        assertEquals(B, com.andysocial.voidos.ui.Book.state.value)
        // close Weather: the watch face comes back into its half, Notes stays
        rule.onNodeWithContentDescription("Close Weather", useUnmergedTree = true).performClick()
        settle(900)
        assertEquals("[$tag] Notes left with the face", com.andysocial.voidos.ui.BookState(first = com.andysocial.voidos.ui.Pane.screen(Module.NOTES)), com.andysocial.voidos.ui.Book.state.value)
        assertTrue("[$tag] face back", rule.onAllNodesWithTag("bookFace", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue("[$tag] island gone", rule.onAllNodesWithTag("timeIsland", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        // away for five seconds or more: back to the plain watch face
        rule.runOnUiThread {
            com.andysocial.voidos.ui.Book.onHome(ctx, false, now)
            assertTrue(com.andysocial.voidos.ui.Book.onHome(ctx, true, now + 5_000))
        }
        settle(900)
        assertTrue("[$tag] closed book", !com.andysocial.voidos.ui.Book.state.value.isOpen)
        assertTrue(rule.onAllNodesWithTag("home", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        shot("43_book_closed")
    }

    /** Double-tap the background and drag: a box picks several widgets, and dragging one moves them all. */
    @Test fun faceGroupMove() {
        com.andysocial.voidos.ui.Book.reset()
        store.saveWidgets(emptyList())
        store.addWidget("sky"); store.addWidget("list")
        val sky = store.widgets.first { it.kind == "sky" }.id
        val list = store.widgets.first { it.kind == "list" }.id
        store.placeWidget(sky, 0.15f, 0.08f, 1f, 1f, 0f)
        store.placeWidget(list, 0.85f, 0.08f, 1f, 1f, 0f)
        show { Shell(store, act, Module.FACE) }
        settle(400)
        fun box(k: String) = rule.onNodeWithTag("widget-$k", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val y0 = winH * 0.86f; val x0 = winW * 0.14f
        // first tap, then the second tap held and dragged up to the top right
        down(x0, y0); up(x0, y0)
        down(x0, y0); drag(x0, y0, winW * 0.9f, winH * 0.05f, steps = 10)
        settle(200)
        shot("46_face_lasso")
        assertTrue("[$tag] box drawn", rule.onAllNodesWithTag("lasso", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        up(winW * 0.9f, winH * 0.05f)
        settle(400)
        assertTrue("[$tag] both picked", listOf("sky", "list").all { k -> rule.onAllNodesWithTag("picked-$k", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() })
        shot("47_face_picked")
        // drag the weather widget down: the list comes too
        val s0 = box("sky"); val l0 = box("list")
        val c = s0.center
        down(c.x, c.y); drag(c.x, c.y, c.x + 10f, c.y + 160f, steps = 8); up(c.x + 10f, c.y + 160f)
        settle(600)
        val s1 = box("sky"); val l1 = box("list")
        assertTrue("[$tag] weather moved down: $s0 -> $s1", s1.top > s0.top + 60f)
        assertTrue("[$tag] list moved with it: $l0 -> $l1", l1.top > l0.top + 60f)
        assertEquals(Module.FACE, com.andysocial.voidos.ui.ShellNow.module.value)
        shot("48_face_group_moved")
        // a tap on the background puts them down
        down(x0, y0); up(x0, y0)
        settle(600)
        assertTrue("[$tag] put down", rule.onAllNodesWithTag("picked-sky", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        store.saveWidgets(emptyList())
    }

    /** Pull a slot out of the tab wheel and let go on the right half: it opens there, beside the face. */
    @Test fun bookDragFromWheel() {
        com.andysocial.voidos.ui.Book.reset()
        store.saveWidgets(emptyList())
        show { Shell(store, act, Module.FACE) }
        settle(300)
        val midY = winH * 0.5f
        down(4f, midY); drag(4f, midY, 70f * dens, midY, steps = 6)
        settle(200)
        // out past the wheel and on across to the right half
        drag(70f * dens, midY, winW * 0.55f, midY, steps = 8)
        settle(200)
        drag(winW * 0.55f, midY, winW * 0.8f, midY + 20f, steps = 6)
        settle(300)
        shot("44_book_carrying")
        val drops = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("drop") { x -> x.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) { null }?.any { d -> d.startsWith("Drop ") } == true }, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("[$tag] carrying a slot; edge log: ${com.andysocial.voidos.ui.EdgeSwipe.last.value}", drops.isNotEmpty())
        up(winW * 0.8f, midY + 20f)
        settle(1200)
        val st = com.andysocial.voidos.ui.Book.state.value
        val port = winH > winW
        assertTrue("[$tag] dropped on the second half: $st; log ${com.andysocial.voidos.ui.EdgeSwipe.last.value}", if (port) st.isOpen else st.second != null && st.first == null)
        assertEquals(Module.FACE, com.andysocial.voidos.ui.ShellNow.module.value)
        shot("45_book_dropped")
        com.andysocial.voidos.ui.Book.reset()
    }

    /** The other direction: pulled out of the tab wheel and let go on the left (portrait: top) half. */
    @Test fun bookDragFromWheelToFirstHalf() {
        com.andysocial.voidos.ui.Book.reset()
        store.saveWidgets(emptyList())
        show { Shell(store, act, Module.FACE) }
        settle(300)
        val midY = winH * 0.5f
        val port = winH > winW
        // let go on the first half: left of centre in landscape, the top half upright
        val endX = if (port) winW * 0.6f else winW * 0.3f
        val endY = if (port) winH * 0.2f else midY + 20f
        down(4f, midY); drag(4f, midY, 70f * dens, midY, steps = 6)
        settle(200)
        drag(70f * dens, midY, winW * 0.55f, midY, steps = 8)
        settle(200)
        drag(winW * 0.55f, midY, endX, endY, steps = 6)
        settle(300)
        shot("44b_book_carrying_first")
        up(endX, endY)
        settle(1200)
        val st = com.andysocial.voidos.ui.Book.state.value
        assertTrue("[$tag] dropped on the first half: $st; log ${com.andysocial.voidos.ui.EdgeSwipe.last.value}", st.first != null && st.second == null)
        assertEquals("[$tag] Home on the other half", false, st.faceFirst)
        shot("45b_book_dropped_first")
        com.andysocial.voidos.ui.Book.reset()
    }

    /** The page dots are a second way in: a tap opens the tab wheel, no swipe needed. */
    @Test fun dotsOpenWheel() {
        show { Shell(store, act, Module.FACE) }
        settle(300)
        rule.onNodeWithContentDescription("Screens").performClick()
        settle(800)
        listOf("Tools", "Weather", "Settings").forEach { rule.onAllNodesWithContentDescription(it, useUnmergedTree = true).fetchSemanticsNodes().let { n -> assertTrue("$it slot; edge log: ${com.andysocial.voidos.ui.EdgeSwipe.last.value}; descs: ${rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("d") { x -> x.config.contains(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) }, useUnmergedTree = true).fetchSemanticsNodes().map { x -> x.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription] }}", n.isNotEmpty()) } }
        shot("17e_wheel_from_dots")
    }

    /** The debug overlay shows the router's last decision on screen. */
    @Test fun edgeDebugOverlay() {
        store.set("edgeDebug", true)
        show { Shell(store, act, Module.FACE) }
        settle(300)
        down(4f, winH * 0.5f); up(4f, winH * 0.5f)
        settle(600)
        rule.onAllNodesWithText("EDGE DEBUG", substring = true).assertCountEquals(1)
        shot("17f_edge_debug")
        store.set("edgeDebug", false)
    }

    /** A slow drag that starts in the soft strip is still a page swipe, not the wheel. */
    @Test fun edgeSlowDragTurnsPage() {
        show { Shell(store, act, Module.TOOLS) }
        settle(300)
        val midY = winH * 0.5f
        val x0 = winW - 50f * dens
        down(x0, midY)
        // slow (well past the flick window), then away from the edge
        touchT += 400
        drag(x0, midY, x0 - winW * 0.6f, midY, steps = 12)
        up(x0 - winW * 0.6f, midY)
        settle(1200)
        // no wheel came up; the pager moved on from Tools to the next screen, now Camera
        rule.onAllNodesWithText("Apps you open from VoidOS", substring = true).assertCountEquals(0)
        assertEquals(Module.CAMERA, com.andysocial.voidos.ui.ShellNow.module.value)
    }

    /** Nothing opens from the edges while the lock screen is up. */
    @Test fun edgeQuietWhenLocked() {
        store.set("voidLock", true)
        com.andysocial.voidos.ui.LockState.locked.value = true
        show {
            Box(Modifier.fillMaxSize()) {
                Shell(store, act, Module.FACE)
                LockScreen(store) {}
            }
        }
        settle(300)
        assertTrue("edges should be off under the lock screen", !com.andysocial.voidos.ui.EdgeSwipe.enabled)
        com.andysocial.voidos.ui.LockState.locked.value = false
        store.set("voidLock", false)
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
        listOf("Enter your VoidOS PIN", "more tap", "taps away", "almost").forEach { hint ->
            assertTrue("[$tag] no hint or door after 19 taps (\"$hint\")",
                rule.onAllNodesWithText(hint, substring = true, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        }
        door.performTouchInput { click() }; settle(600)
        expect("Enter your VoidOS PIN"); shot("45_hidden_way_out")
    }

    @Test fun system() {
        show { Shell(store, act, Module.SYSTEM) }
        shot("20_system_wifi")
        listOf("Bluetooth", "Network", "Display & sound", "Notifications", "Do not disturb", "Battery", "Home & lock screen", "Date & time",
            "Lock mode", "Storage", "System", "About").forEachIndexed { i, t ->
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
        store.set("lockNeedsPin", false)
        var unlocked = false
        show { LockScreen(store) { unlocked = true } }
        swipeUp()
        assertTrue("[$tag] swipe up should unlock", unlocked)
    }

    @Test fun lockPin() {
        PinGuard.reset()
        store.resetPin(); store.set("lockNeedsPin", true)
        var unlocked = false
        show { LockScreen(store) { unlocked = true } }
        swipeUp()
        assertTrue("[$tag] PIN should be asked first", !unlocked)
        expect("Enter PIN", "Cancel"); shot("41_lock_pin")
        "1235".forEach { tap(it.toString()) }
        assertTrue("[$tag] wrong PIN must not unlock", !unlocked)
        expect("Wrong PIN")
        // the VoidOS PIN is the code the user already uses to leave VoidOS
        "4517".forEach { tap(it.toString()) }
        settle(800)
        assertTrue("[$tag] right PIN should unlock", unlocked)
    }

    @Test fun lockPinLockout() {
        PinGuard.reset()
        store.resetPin(); store.set("lockNeedsPin", true)
        var unlocked = false
        show { LockScreen(store) { unlocked = true } }
        swipeUp()
        repeat(5) { "0000".forEach { tap(it.toString()) } }
        expect("Try again in")
        "4517".forEach { tap(it.toString()) }
        assertTrue("[$tag] keys must not work during the wait", !unlocked)
        shot("41b_lock_pin_wait")
        PinGuard.reset()
    }

    @Test fun lockNoPin() {
        store.set("lockNeedsPin", false)
        var unlocked = false
        show { LockScreen(store) { unlocked = true } }
        swipeUp()
        assertTrue("[$tag] with the PIN off, swipe up unlocks", unlocked)
        store.set("lockNeedsPin", true)
    }

    @Test fun about() {
        show { Shell(store, act, Module.SYSTEM) }
        tap("About"); settle(1500)
        expect("VoidOS", "Device", "Storage", "Memory", "Battery")
        listOf("Lenovo", "Magisk", "Bootloader", "running the tablet", "Boot & root").forEach {
            val n = rule.onAllNodesWithText(it, substring = true, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue("[$tag] About should not show \"$it\"", n.isEmpty())
        }
        shot("50_about")
    }

    @Test fun pinSettings() {
        PinGuard.reset(); store.resetPin()
        show { Shell(store, act, Module.SYSTEM) }
        tap("Home & lock screen"); settle(800)
        expect("Ask for PIN to unlock", "Change PIN")
        tap("Change")
        expect("Enter your current PIN")
        "4517".forEach { tap(it.toString()) }
        expect("Choose a new PIN")
        shot("51_pin_change")
        "2468".forEach { tap(it.toString()) }
        expect("Enter it again")
        "2468".forEach { tap(it.toString()) }
        assertTrue("[$tag] new PIN saved", store.checkPin("2468"))
        assertTrue("[$tag] and it is the way out too", store.checkEscape("2468"))
        store.resetPin()
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
