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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assertIsDisplayed
import com.andysocial.voidos.ui.BootAnim
import com.andysocial.voidos.ui.LocalPortrait
import com.andysocial.voidos.ui.LockScreen
import com.andysocial.voidos.ui.Module
import com.andysocial.voidos.ui.Setup
import com.andysocial.voidos.ui.Shell
import com.andysocial.voidos.ui.V
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Before
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
        fakeApps()
        store.set("setupDone", true)
        if (store.shopping.isEmpty()) listOf("Semi-skimmed milk", "Bananas", "Sourdough loaf", "Washing-up liquid", "Cheddar").forEach { store.addItem(it) }
        if (KTimers.list.value.isEmpty()) {
            KTimers.add(act, "Pasta", 9 * 60_000L)
            KTimers.add(act, "Oven chips", 22 * 60_000L)
            KTimers.add(act, "Eggs", 6 * 60_000L, start = false)
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

    @Test fun face() { checkPortrait(); show { Shell(store, act, Module.FACE) }; shot("01_face"); expect("Timers", "List", "Notes") }

    @Test fun kitchen() {
        show { Shell(store, act, Module.KITCHEN) }
        expect("Pasta", "Oven chips"); shot("02_kitchen_timers")
        tap("Convert"); expect("Kilograms", "Pounds"); shot("03_kitchen_convert")
        tap("Oven"); expect("Gas mark"); shot("04_kitchen_convert_oven")
        tap("Cups"); expect("Plain flour"); shot("05_kitchen_convert_cups")
        tap("List"); expect("Bananas", "Cheddar"); shot("06_kitchen_list")
    }

    @Test fun sky() { show { Shell(store, act, Module.WEATHER) }; shot("07_sky") }
    @Test fun notes() { show { Shell(store, act, Module.NOTES) }; shot("08_notes") }
    @Test fun sound() { show { Shell(store, act, Module.MEDIA) }; expect("On this tablet"); shot("09_media") }
    @Test fun focus() { show { Shell(store, act, Module.FOCUS) }; shot("10_focus") }
    @Test fun house() { show { Shell(store, act, Module.HOUSE) }; shot("11_house") }

    @Test fun apps() {
        show { Shell(store, act, Module.APPS) }
        expect("Spotify", "WhatsApp", "Watch"); shot("12_apps")
        tap("Manage"); expect("Done", "Blocked"); shot("13_apps_manage")
        tap("Done")
        store.setIconStyle("letter"); settle(); expect("SP", "BS"); shot("14_apps_icons_letters")
        store.setIconStyle("colour"); settle(); shot("15_apps_icons_colour")
        store.setIconStyle("void")
    }

    @Test fun escape() {
        show { com.andysocial.voidos.ui.EscapeOverlay(store, act) {} }
        shot("34_escape_pin")
    }

    @Test fun system() {
        show { Shell(store, act, Module.SYSTEM) }
        shot("20_system_wifi")
        listOf("Bluetooth", "Screen + sound", "Hub + lock", "Do not disturb", "Date + time", "Battery", "Network",
            "Storage + apps", "Android settings", "Boot + root", "VoidOS rules", "About").forEachIndexed { i, t ->
            tap(t)
            shot("%02d_system_%s".format(21 + i, t.lowercase().replace(Regex("[^a-z]+"), "_").trim('_')))
        }
    }

    @Test fun lock() {
        show { LockScreen(store) {} }
        expect("Swipe up"); shot("30_lock")
    }

    @Test fun lockPin() {
        store.setPin("1234"); store.set("lockNeedsPin", true)
        show { LockScreen(store) {} }
        shot("31_lock_pin_idle")
        store.set("lockNeedsPin", false)
    }

    @Test fun boot() {
        show { Box(Modifier.fillMaxSize()) { BootAnim {} } }
        shot("32_boot_mid")
    }

    @Test fun setup() {
        store.set("setupDone", false)
        show { Setup(store, act) }
        shot("33_setup")
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
