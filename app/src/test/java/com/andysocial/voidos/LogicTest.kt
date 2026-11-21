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
import com.andysocial.voidos.ui.Startup
import com.andysocial.voidos.ui.tabletName
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

    @Test fun aboutStartupInPlainWords() {
        val v = Startup.VOIDOS_LOGO_SHA
        val st = Startup.STOCK_LOGO_SHA
        val other = "0".repeat(64)
        assertEquals(Startup.Logo.VOIDOS, Startup.logoFrom("$v  -\n$other  -"))
        assertEquals(Startup.Logo.LENOVO, Startup.logoFrom("$other  -\n$st  -"))
        assertEquals(Startup.Logo.OTHER, Startup.logoFrom("$other  -\n$other  -"))
        assertEquals(Startup.Logo.UNKNOWN, Startup.logoFrom(null))
        assertEquals(Startup.Logo.UNKNOWN, Startup.logoFrom("Permission denied"))
        assertEquals("3.0", Startup.versionFrom("id=voidos_boot\nname=VoidOS\nversion=3.0\nversionCode=3"))
        assertNull(Startup.versionFrom(null))
        assertTrue(Startup.outdated("2.0"))
        assertTrue(Startup.outdated("3.0"))
        assertFalse(Startup.outdated("3.1"))
        val lines = Startup.lines(Startup.State(Startup.Logo.VOIDOS, "3.1", true)).toMap()
        assertEquals("VoidOS logo", lines["Startup logo"])
        assertEquals("VoidOS animation", lines["Startup animation"])
        assertEquals("VoidOS animation", lines["Shutdown animation"])
        assertNull(Startup.note(Startup.State(Startup.Logo.VOIDOS, "3.1", true)))
        val stock = Startup.lines(Startup.State(Startup.Logo.LENOVO, null, false)).toMap()
        assertEquals("Lenovo logo", stock["Startup logo"])
        assertEquals("Lenovo animation", stock["Startup animation"])
        assertTrue(Startup.note(Startup.State(Startup.Logo.VOIDOS, "2.0", true))!!.contains("newer"))
        // no jargon on the plain page
        val words = (lines.keys + lines.values + stock.values).joinToString(" ").lowercase()
        listOf("partition", "magisk", "module", "root", "adb", "system app").forEach { assertFalse(it, words.contains(it)) }
        assertEquals("Lenovo Tab M10 HD (2nd Gen)", tabletName("LENOVO", "TB-X306F"))
        assertEquals("Google Pixel 4", tabletName("google", "Pixel 4"))
    }

    @Test fun startupLogoHashesMatchTheFiles() {
        val logo = java.io.File("../root/logo-voidos.bin").takeIf { it.exists() } ?: java.io.File("root/logo-voidos.bin")
        if (!logo.exists()) return
        val b = logo.readBytes()
        assertEquals(Startup.VOIDOS_LOGO_LEN, b.size)
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
        assertEquals(Startup.VOIDOS_LOGO_SHA, sha)
    }

    @Test fun voidPinStartsAs4517AndIsHashed() {
        val c = RuntimeEnvironment.getApplication()
        assertTrue(store.lockNeedsPin)                 // on out of the box
        assertTrue(store.checkPin("4517"))             // the code the user already uses to leave VoidOS
        assertTrue(store.checkEscape("4517"))
        assertFalse(store.checkPin("1234"))
        assertFalse(store.pinChanged)
        store.setPin("135790")
        assertTrue(store.checkPin("135790"))
        assertTrue(store.checkEscape("135790"))        // one PIN for both
        assertFalse(store.checkPin("4517"))
        assertEquals(6, store.pinLength())
        val prefs = c.getSharedPreferences("voidos", android.content.Context.MODE_PRIVATE).all.values.joinToString(" ")
        assertFalse(prefs.contains("135790"))          // never stored as typed
        assertTrue(prefs.contains("pbkdf2$"))
        store.resetPin()
        assertTrue(store.checkPin("4517"))
        store.set("lockNeedsPin", false)
        assertFalse(store.lockNeedsPin)
    }

    @Test fun pinHashIsSaltedAndChecks() {
        val a = PinHash.make("4517")
        val b = PinHash.make("4517")
        assertFalse(a == b)                            // different salt every time
        assertTrue(PinHash.check("4517", a))
        assertFalse(PinHash.check("4518", a))
        assertFalse(PinHash.check("4517", "garbage"))
    }

    @Test fun pinLockoutAfterFiveWrong() {
        PinGuard.reset()
        repeat(4) { assertEquals(0L, PinGuard.miss(1000L)) }
        assertFalse(PinGuard.waiting(1000L))
        assertEquals(30_000L, PinGuard.miss(1000L))
        assertTrue(PinGuard.waiting(20_000L))
        assertFalse(PinGuard.waiting(31_001L))
        repeat(4) { PinGuard.miss(40_000L) }
        assertEquals(60_000L, PinGuard.miss(40_000L))  // longer the second time
        PinGuard.ok()
        assertFalse(PinGuard.waiting(40_001L))
        assertEquals(0L, PinGuard.miss(50_000L))       // a right PIN starts the count again
        PinGuard.reset()
    }

    @Test fun wifiListMergesAndSorts() {
        val raw = listOf(
            Triple("Home", -70, Radio.Sec.WPA),
            Triple("Home", -50, Radio.Sec.WPA),
            Triple("Cafe", -60, Radio.Sec.OPEN),
            Triple("", -40, Radio.Sec.OPEN),
            Triple("Neighbour", -85, Radio.Sec.SAE),
        )
        val list = Radio.merge(raw, saved = setOf("Cafe"), connected = "Home")
        assertEquals(listOf("Home", "Cafe", "Neighbour"), list.map { it.ssid })   // connected first, then strongest; hidden ones dropped
        assertEquals(4, list[0].bars)
        assertTrue(list[0].connected && list[0].locked)
        assertTrue(list[1].saved && !list[1].locked)
        assertEquals(1, list[2].bars)
        // connected but not in the scan yet: still shown
        assertEquals("Office", Radio.merge(emptyList(), emptySet(), "Office").single().ssid)
        assertEquals(Radio.Sec.WPA, Radio.secOf("[WPA2-PSK-CCMP][ESS]"))
        assertEquals(Radio.Sec.SAE, Radio.secOf("[RSN-SAE-CCMP][ESS]"))
        assertEquals(Radio.Sec.WEP, Radio.secOf("[WEP][ESS]"))
        assertEquals(Radio.Sec.OPEN, Radio.secOf("[ESS]"))
        assertEquals("My Wi-Fi", Radio.clean("\"My Wi-Fi\""))
    }

    @Suppress("DEPRECATION")
    @Test fun wifiConfigForEachSecurity() {
        val wpa = Radio.config("Home", Radio.Sec.WPA, "hunter22")
        assertEquals("\"Home\"", wpa.SSID)
        assertEquals("\"hunter22\"", wpa.preSharedKey)
        assertTrue(wpa.allowedKeyManagement.get(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK))
        val open = Radio.config("Cafe", Radio.Sec.OPEN, "")
        assertTrue(open.allowedKeyManagement.get(android.net.wifi.WifiConfiguration.KeyMgmt.NONE))
    }

    @Test fun wifiRootJoinScript() {
        val wpa = Radio.wpaScript(Radio.Net("Andy's Wi-Fi", 3, Radio.Sec.WPA), "pa ss")
        assertTrue(wpa, wpa.contains("add_network"))
        assertTrue(wpa, wpa.contains("ssid '\"Andy'\\''s Wi-Fi\"'"))
        assertTrue(wpa, wpa.contains("psk '\"pa ss\"'"))
        assertTrue(wpa, wpa.endsWith("select_network \$n && wpa_cli -i wlan0 save_config"))
        val open = Radio.wpaScript(Radio.Net("Cafe", 2, Radio.Sec.OPEN), "")
        assertTrue(open, open.contains("key_mgmt NONE"))
        assertFalse(open, open.contains("psk"))
    }

    @Test fun wifiSwitchFallsBackToRoot() {
        val ran = mutableListOf<String>()
        Su.fake = { ran += it; Su.Out(0, "") }
        Radio.settleMs = 0
        try {
            val c = RuntimeEnvironment.getApplication()
            val wm = c.getSystemService(android.net.wifi.WifiManager::class.java)
            @Suppress("DEPRECATION") wm.setWifiEnabled(true)
            Radio.setWifi(c, false)
            // Robolectric lets the public call through, so root is only the fallback
            assertFalse(wm.isWifiEnabled)
        } finally { Su.fake = null; Radio.settleMs = null }
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
        store.addWidget("battery"); store.addWidget("battery")
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

    @Test fun escapeCodeIsTheVoidPin() {
        store.resetPin()
        assertTrue(store.checkEscape("4517"))
        listOf("0000", "1234", "4518", "", "45170").forEach { assertFalse(it, store.checkEscape(it)) }
        // one VoidOS PIN for the lock screen and the way out
        store.setPin("2222")
        assertTrue(store.checkEscape("2222")); assertTrue(store.checkPin("2222"))
        assertFalse(store.checkEscape("4517"))
        store.resetPin()
    }

    @Test fun widgetsStretchAndTurn() {
        store.saveWidgets(emptyList())
        store.addWidget("battery")
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

    /** A quick swipe in leaves the wheel open to tap; only a held, settled slide picks on release. */
    @Test fun wheelReleaseRule() {
        val pick: (Boolean, Long, Long, Long) -> Boolean = { a, b, c, d -> com.andysocial.voidos.ui.releasePicks(a, b, c, d) }
        assertEquals(false, pick(true, 0L, 60L, 150L))   // flick: 150 ms in all
        assertEquals(false, pick(false, 0L, 0L, 900L))   // a tap on the edge, held: never picks
        assertEquals(false, pick(true, 0L, 840L, 900L))  // still sliding between slots at release
        assertEquals(true, pick(true, 0L, 500L, 900L))   // held on a slot, then let go
    }
}
