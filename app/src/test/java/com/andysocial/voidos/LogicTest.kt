package com.andysocial.voidos

import com.andysocial.voidos.ui.accentFor
import com.andysocial.voidos.ui.EdgeAction
import com.andysocial.voidos.ui.EdgeRouter
import com.andysocial.voidos.ui.edgeExclusion
import com.andysocial.voidos.ui.categories
import com.andysocial.voidos.ui.cupGrams
import com.andysocial.voidos.ui.fmtDur
import com.andysocial.voidos.ui.fmtNum
import com.andysocial.voidos.ui.glyphForPackage
import com.andysocial.voidos.ui.initialsFor
import com.andysocial.voidos.ui.G
import com.andysocial.voidos.ui.BootChain
import com.andysocial.voidos.ui.WeatherRepo
import com.andysocial.voidos.ui.eraseAt
import com.andysocial.voidos.ui.octRadius
import com.andysocial.voidos.ui.timerGrid
import com.andysocial.voidos.ui.wxAge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Plain logic: the unit converter, the shopping aisles, timers, the PIN and the icon helpers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LogicTest {

    private fun unit(cat: String, name: String) = categories.first { it.first == cat }.second.first { it.name == name }
    private fun conv(cat: String, from: String, to: String, v: Double) = unit(cat, to).fromBase(unit(cat, from).toBase(v))

    @Test fun weight() {
        assertEquals(2.2046, conv("Weight", "Kilograms", "Pounds", 1.0), 1e-3)
        assertEquals(453.592, conv("Weight", "Pounds", "Grams", 1.0), 1e-3)
        assertEquals(14.0, conv("Weight", "Stone", "Pounds", 1.0), 1e-6)
        assertEquals(16.0, conv("Weight", "Pounds", "Ounces", 1.0), 1e-6)
    }

    @Test fun volume() {
        assertEquals(568.261, conv("Volume", "Pints (UK)", "Millilitres", 1.0), 1e-3)
        assertEquals(20.0, conv("Volume", "Pints (UK)", "Fluid oz (UK)", 1.0), 1e-6)
        assertEquals(3.0, conv("Volume", "Tablespoons", "Teaspoons", 1.0), 1e-9)
    }

    @Test fun oven() {
        assertEquals(356.0, conv("Temperature", "Celsius", "Fahrenheit", 180.0), 1e-9)
        assertEquals(160.0, conv("Temperature", "Celsius", "Fan oven", 180.0), 1e-9)
        assertEquals(4.0, conv("Temperature", "Celsius", "Gas mark", 180.0), 1e-9)
        assertEquals(6.0, conv("Temperature", "Fahrenheit", "Gas mark", 400.0), 1e-9)
        assertEquals(220.0, conv("Temperature", "Gas mark", "Celsius", 7.0), 1e-9)
        // no gas mark for a fridge or a pizza oven
        assertTrue(conv("Temperature", "Celsius", "Gas mark", 20.0).isNaN())
        assertTrue(conv("Temperature", "Celsius", "Gas mark", 300.0).isNaN())
    }

    @Test fun length() {
        assertEquals(2.54, conv("Length", "Inches", "Centimetres", 1.0), 1e-9)
        assertEquals(12.0, conv("Length", "Feet", "Inches", 1.0), 1e-9)
    }

    @Test fun cupsAndNumbers() {
        assertEquals(125.0, cupGrams.toMap()["Plain flour"]!!, 0.0)
        assertEquals("454", fmtNum(453.59))
        assertEquals("2.2", fmtNum(2.2046).take(3))
        assertEquals("12.5", fmtNum(12.5))
        assertEquals("-", fmtNum(Double.NaN))
        assertEquals("5:00", fmtDur(300_000))
        assertEquals("1:00:00", fmtDur(3_600_000))
        assertEquals("0:01", fmtDur(1))
    }

    @Test fun aisles() {
        mapOf(
            "Cheddar" to "Dairy & eggs", "Eggs" to "Dairy & eggs", "Semi-skimmed milk" to "Dairy & eggs",
            "Sourdough loaf" to "Bakery", "Bananas" to "Fruit & veg", "Butternut squash" to "Fruit & veg",
            "Ginger" to "Fruit & veg", "Gin" to "Drinks", "Orange juice" to "Drinks", "Tea bags" to "Drinks",
            "Ice cream" to "Frozen", "Oven chips" to "Frozen", "Rice" to "Cupboard", "Olive oil" to "Cupboard",
            "Steak" to "Meat & fish", "Ham" to "Meat & fish", "Shampoo" to "Household", "Toilet roll" to "Household",
            "Washing-up liquid" to "Household", "Tin foil" to "Household", "Cat food" to "Pets", "Birthday card" to "Other",
        ).forEach { (item, aisle) -> assertEquals(item, aisle, aisleFor(item)) }
    }

    private lateinit var store: Store
    @Before fun fresh() {
        store = VoidApp.instance.store
        store.shopping.forEach { store.removeItem(it.id) }
        KTimers.list.value.forEach { KTimers.remove(RuntimeEnvironment.getApplication(), it.id) }
    }

    @org.junit.After fun tidy() {
        val c = RuntimeEnvironment.getApplication()
        KTimers.silence(c)
        KTimers.list.value.forEach { KTimers.remove(c, it.id) }
    }

    @Test fun shoppingList() {
        store.addItem("  bananas ")
        store.addItem("Bananas")          // same item, not added twice
        store.addItem("")
        store.addItem("milk")
        assertEquals(listOf("Milk", "Bananas"), store.shopping.map { it.text })
        val milk = store.shopping.first()
        store.toggleItem(milk.id)
        assertTrue(store.shopping.first { it.id == milk.id }.done)
        store.addItem("Milk")             // ticked off, so it can go on again
        assertEquals(3, store.shopping.size)
        store.clearDone()
        assertEquals(listOf("Milk", "Bananas"), store.shopping.map { it.text })
    }

    @Test fun pin() {
        store.setPin("2468")
        assertTrue(store.checkPin("2468"))
        assertFalse(store.checkPin("1234"))
    }

    @Test fun timers() {
        val c = RuntimeEnvironment.getApplication()
        KTimers.add(c, "Eggs", 7 * 60_000L)
        KTimers.add(c, "Rice", 12 * 60_000L, start = false)
        val (eggs, rice) = KTimers.list.value
        assertTrue(eggs.running); assertFalse(rice.running)
        assertEquals(12 * 60_000L, rice.remaining())
        KTimers.addTime(c, rice.id, 60_000)
        assertEquals(13 * 60_000L, KTimers.list.value.first { it.id == rice.id }.remaining())
        KTimers.toggle(c, eggs.id)                       // pause
        assertFalse(KTimers.list.value.first { it.id == eggs.id }.running)
        // a timer that's due rings and stays on the board
        KTimers.toggle(c, rice.id)
        KTimers.list.value = KTimers.list.value.map { if (it.id == rice.id) it.copy(endAt = System.currentTimeMillis() - 1) else it }
        KTimers.check(c)
        val rang = KTimers.list.value.first { it.id == rice.id }
        assertTrue(rang.rang); assertFalse(rang.running)
        assertEquals(listOf(rice.id), KTimers.ringing.value.map { it.id })
        KTimers.silence(c)
        assertTrue(KTimers.ringing.value.isEmpty())
        // restarting a rung timer starts it from its full length
        KTimers.toggle(c, rice.id)
        assertTrue(KTimers.list.value.first { it.id == rice.id }.running)
    }

    @Test fun icons() {
        assertEquals(G.MEDIA, glyphForPackage("com.spotify.music"))
        assertEquals(G.VIDEO, glyphForPackage("com.google.android.youtube"))
        assertEquals(G.MESSAGE, glyphForPackage("com.whatsapp"))
        assertEquals(G.GLOBE, glyphForPackage("com.android.chrome"))
        assertEquals(G.CAMERA, glyphForPackage("com.android.camera2"))
        assertNull(glyphForPackage("com.example.unknown"))
        assertEquals("SP", initialsFor("Spotify"))
        assertEquals("BS", initialsFor("BBC Sounds"))
        assertEquals("?", initialsFor("  "))
        assertEquals(accentFor("com.whatsapp"), accentFor("com.whatsapp"))
    }

    @Test fun bootChainSaysWhoDrawsEachStage() {
        fun v(name: String, st: List<BootChain.Stage>) = st.first { it.name == name }
        // everything VoidOS: module in, device owner, Android lock off, VoidOS lock on
        val all = BootChain.stages("1.0", androidLockSecure = false, androidLockOff = true, voidLock = true, owner = true)
        assertEquals(listOf("Power-on logo", "Boot animation", "Lock screen", "Home screen"), all.map { it.name })
        assertEquals("VoidOS", v("Boot animation", all).value)
        assertEquals("VoidOS", v("Lock screen", all).value)
        assertTrue(v("Lock screen", all).ok && v("Home screen", all).ok && v("Boot animation", all).ok)
        assertFalse(v("Power-on logo", all).ok) // the app can't see the logo partition, so it never claims it
        // an Android PIN keeps Android's keyguard in force whatever VoidOS does
        val pin = BootChain.stages("1.0", androidLockSecure = true, androidLockOff = true, voidLock = true, owner = true)
        assertEquals("Android: remove the Android PIN", v("Lock screen", pin).value)
        assertFalse(v("Lock screen", pin).ok)
        // no module, not owner
        val stock = BootChain.stages(null, androidLockSecure = false, androidLockOff = false, voidLock = true, owner = false)
        assertEquals("Android (no module)", v("Boot animation", stock).value)
        assertEquals("VoidOS, Android's behind it", v("Lock screen", stock).value)
        assertFalse(v("Home screen", stock).ok)
        assertEquals("None", v("Lock screen", BootChain.stages(null, false, true, false, true)).value)
        assertEquals("Android", v("Lock screen", BootChain.stages(null, false, false, false, true)).value)
    }

    @Test fun timerGridGivesBigCells() {
        assertEquals(1 to 1, timerGrid(1, 600f, 500f))
        // two timers side by side on a wide pane, stacked on a tall one
        assertEquals(2 to 1, timerGrid(2, 700f, 400f))
        assertEquals(1 to 2, timerGrid(2, 400f, 700f))
        val (c, r) = timerGrid(5, 700f, 500f)
        assertTrue(c * r >= 5)
    }

    @Test fun octagonRadius() {
        // edge middles sit at the apothem, corners at apothem / cos(22.5 deg)
        assertEquals(100f, octRadius(100f, 0.0), 0.01f)
        assertEquals(100f, octRadius(100f, Math.PI / 2), 0.01f)
        assertEquals((100 / Math.cos(Math.PI / 8)).toFloat(), octRadius(100f, Math.PI / 8), 0.01f)
    }

    @Test fun inkSurvivesSaving() {
        val ink = listOf(Stroke(0xFF00FFAA.toInt(), 0.006f, 1, listOf(0.1f, 0.2f, 0.3f, 0.4f)), Stroke(-1, 0.024f, 0, listOf(0.5f, 0.5f)))
        val back = strokesFromJson(strokesToJson(ink))
        assertEquals(ink.size, back.size)
        assertEquals(ink[0].argb, back[0].argb); assertEquals(1, back[0].style)
        assertEquals(0.3f, back[0].pts[2], 0.0001f)
        store.saveNote(Note(4242L, "sketch", 2, 1L, ink))
        val fresh = Store(RuntimeEnvironment.getApplication())
        assertEquals(2, fresh.notes.first { it.id == 4242L }.ink.size)
        store.deleteNote(4242L)
    }

    @Test fun eraserRemovesOnlyTouchedStrokes() {
        val ink = mutableListOf(Stroke(-1, 0.01f, 0, listOf(0.1f, 0.1f, 0.2f, 0.1f)), Stroke(-1, 0.01f, 0, listOf(0.8f, 0.8f, 0.9f, 0.9f)))
        assertTrue(eraseAt(ink, 0.15f, 0.1f, 0.02f))
        assertEquals(1, ink.size)
        assertFalse(eraseAt(ink, 0.5f, 0.5f, 0.02f))
    }

    @Test fun widgetsSaveTheirPlace() {
        store.saveWidgets(emptyList())
        store.addWidget("sky"); store.addWidget("sky")
        assertEquals("no duplicates", 1, store.widgets.size)
        store.moveWidget(store.widgets[0].id, 0.25f, 1.7f)
        val fresh = Store(RuntimeEnvironment.getApplication())
        assertEquals(0.25f, fresh.widgets[0].x, 0.001f)
        assertEquals("kept on screen", 1f, fresh.widgets[0].y, 0.001f)
        // deleting a note takes its widget with it
        store.saveNote(Note(99L, "x", 0, 1L)); store.addWidget("note", 99L)
        store.deleteNote(99L)
        assertTrue(store.widgets.none { it.kind == "note" })
        store.saveWidgets(emptyList())
    }

    @Test fun escapeCodeIsFixedAndSeparate() {
        assertTrue(store.checkEscape("4517"))
        listOf("0000", "1234", "4518", "", "45170").forEach { assertFalse(it, store.checkEscape(it)) }
        // the lock-screen PIN is its own thing
        store.setPin("2222")
        assertFalse(store.checkEscape("2222")); assertTrue(store.checkPin("2222"))
    }

    @Test fun widgetsStretchAndTurn() {
        store.saveWidgets(emptyList())
        store.addWidget("sky")
        val id = store.widgets[0].id
        store.placeWidget(id, 0.3f, 0.4f, 9f, 0.1f, 450f)
        val w = Store(RuntimeEnvironment.getApplication()).widgets[0]
        assertEquals(WIDGET_MAX_SCALE, w.sw, 0.001f); assertEquals(WIDGET_MIN_SCALE, w.sh, 0.001f)
        assertEquals("turns fold into -180..180", 90f, w.rot, 0.001f)
        assertEquals("near-straight snaps", 0f, normDeg(3f), 0.001f)
        assertEquals(30f, normDeg(30f), 0.001f)
        store.saveWidgets(emptyList())
        store.placeClock(ClockPlace(0.2f, 0.3f, 5f, -93f))
        val c = Store(RuntimeEnvironment.getApplication()).clock
        assertEquals(0.2f, c.cx, 0.001f); assertEquals(CLOCK_MAX_SCALE, c.scale, 0.001f); assertEquals(-90f, c.rot, 0.001f)
        store.resetClock()
    }

    @Test fun pinchMath() {
        val t = com.andysocial.voidos.ui.FaceTarget(1, com.andysocial.voidos.ui.FaceBox(500f, 300f, 200f, 100f, 0f), false, 50f, 900f, 50f, 900f)
        fun p(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x, y)
        // spread sideways: wider only
        val wide = com.andysocial.voidos.ui.pinchStep(t, t.box, p(450f, 300f), p(550f, 300f), p(400f, 300f), p(600f, 300f))
        assertTrue(wide.w > 240f); assertEquals(100f, wide.h, 0.01f)
        // spread up and down: taller only
        val tall = com.andysocial.voidos.ui.pinchStep(t, t.box, p(500f, 250f), p(500f, 350f), p(500f, 200f), p(500f, 400f))
        assertEquals(200f, tall.w, 0.01f); assertTrue(tall.h > 120f)
        // turned 90 degrees, a sideways spread stretches its height (its own axis)
        val turned = t.box.copy(rot = 90f)
        val side = com.andysocial.voidos.ui.pinchStep(t, turned, p(450f, 300f), p(550f, 300f), p(400f, 300f), p(600f, 300f))
        assertTrue(side.h > 120f); assertEquals(200f, side.w, 0.5f)
        // twist
        val tw = com.andysocial.voidos.ui.pinchStep(t, t.box, p(450f, 300f), p(550f, 300f), p(500f, 250f), p(500f, 350f))
        assertEquals(90f, tw.rot, 0.01f)
    }

    @Test fun weatherParsesAndAges() {
        val raw = """{"current":{"temperature_2m":7.4,"apparent_temperature":4.9,"weather_code":3,"wind_speed_10m":14.2,"relative_humidity_2m":81,"is_day":1},
            "hourly":{"time":["2026-11-17T21:00"],"temperature_2m":[7.0],"weather_code":[3],"precipitation_probability":[10]},
            "daily":{"time":["2026-11-17"],"weather_code":[61],"temperature_2m_max":[9.1],"temperature_2m_min":[3.2],"sunrise":["2026-11-17T07:31"],"sunset":["2026-11-17T16:12"],"precipitation_probability_max":[60]}}"""
        val wx = WeatherRepo.parse(raw, 1000L)
        assertEquals(7.4, wx.temp, 0.01); assertEquals("07:31", wx.sunrise); assertEquals("Today", wx.days[0].label)
        assertEquals("just now", wxAge(0L, 30_000L))
        assertEquals("45m ago", wxAge(0L, 45 * 60_000L))
        assertEquals("3h ago", wxAge(0L, 3 * 3_600_000L))
        assertEquals("2d ago", wxAge(0L, 50 * 3_600_000L))
    }

    // ---- edge wheels: which touches the wheel takes (tvdpi, 1.33 px per dp, 1280 px wide)

    private val d = 1.33f
    private val wide = 1280f

    @Test fun edgeHardStripIsTheWheelsAtOnce() {
        val r = EdgeRouter(d)
        assertEquals(EdgeAction.CONSUME, r.down(3f, 400f, wide, 0, enabled = true))
        assertTrue(r.left)
        assertEquals(EdgeAction.CONSUME, r.move(60f, 420f, 16))
        assertEquals(EdgeAction.CONSUME, r.up())
        // the right side too, and the far edge of the strip still counts
        assertEquals(EdgeAction.CONSUME, r.down(wide - 34f * d + 1f, 400f, wide, 0, enabled = true))
        assertFalse(r.left)
        r.up()
    }

    @Test fun edgeSoftStripNeedsAQuickInwardFlick() {
        val r = EdgeRouter(d)
        val x = 50f * d
        // the press itself goes to the page, in case it's a tap or a page swipe
        assertEquals(EdgeAction.PASS, r.down(x, 400f, wide, 0, enabled = true))
        assertEquals(EdgeAction.PASS, r.move(x + 4f, 401f, 8)) // under the slop: still undecided
        assertEquals(EdgeAction.CLAIM, r.move(x + 40f, 405f, 40)) // quick, flat, inwards: the wheel's
        assertTrue(r.owned)
        assertEquals(EdgeAction.CONSUME, r.move(x + 90f, 420f, 56))
        assertEquals(EdgeAction.CONSUME, r.up())
    }

    @Test fun edgeSoftStripLetsOtherGesturesThrough() {
        val r = EdgeRouter(d)
        val x = 50f * d
        // slow
        r.down(x, 400f, wide, 0, enabled = true)
        assertEquals(EdgeAction.PASS, r.move(x + 40f, 400f, 600))
        assertEquals(EdgeAction.PASS, r.move(x + 200f, 400f, 700))
        assertEquals(EdgeAction.PASS, r.up())
        // outwards, towards the bezel
        r.down(x, 400f, wide, 0, enabled = true)
        assertEquals(EdgeAction.PASS, r.move(x - 30f, 400f, 30))
        r.up()
        // mostly vertical (scrolling a list near the edge)
        r.down(x, 400f, wide, 0, enabled = true)
        assertEquals(EdgeAction.PASS, r.move(x + 15f, 460f, 30))
        r.up()
    }

    @Test fun edgeMiddleAndDisabledPassThrough() {
        val r = EdgeRouter(d)
        assertEquals(EdgeAction.PASS, r.down(640f, 400f, wide, 0, enabled = true))
        assertEquals(EdgeAction.PASS, r.move(700f, 400f, 10))
        assertEquals(EdgeAction.PASS, r.up())
        // lock screen, control centre and so on switch the edges off
        assertEquals(EdgeAction.PASS, r.down(2f, 400f, wide, 0, enabled = false))
        assertEquals(EdgeAction.PASS, r.move(80f, 400f, 10))
        assertEquals(EdgeAction.PASS, r.up())
    }

    @Test fun edgeExclusionFitsAndroidsLimit() {
        // Android 10 honours at most 200 dp per side edge; ask for exactly that, centred
        val rects = edgeExclusion(800, 1280, d)
        assertEquals(2, rects.size)
        rects.forEach { assertEquals((200 * d).toInt(), it[3] - it[1]); assertTrue(kotlin.math.abs((1280 - it[3]) - it[1]) <= 1) }
        assertEquals(0, rects[0][0]); assertEquals(800, rects[1][2])
    }
}
