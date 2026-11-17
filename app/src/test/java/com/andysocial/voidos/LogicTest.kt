package com.andysocial.voidos

import com.andysocial.voidos.ui.accentFor
import com.andysocial.voidos.ui.categories
import com.andysocial.voidos.ui.cupGrams
import com.andysocial.voidos.ui.fmtDur
import com.andysocial.voidos.ui.fmtNum
import com.andysocial.voidos.ui.glyphForPackage
import com.andysocial.voidos.ui.initialsFor
import com.andysocial.voidos.ui.G
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
        assertEquals(356.0, conv("Oven", "Celsius", "Fahrenheit", 180.0), 1e-9)
        assertEquals(160.0, conv("Oven", "Celsius", "Fan oven", 180.0), 1e-9)
        assertEquals(4.0, conv("Oven", "Celsius", "Gas mark", 180.0), 1e-9)
        assertEquals(6.0, conv("Oven", "Fahrenheit", "Gas mark", 400.0), 1e-9)
        assertEquals(220.0, conv("Oven", "Gas mark", "Celsius", 7.0), 1e-9)
        // no gas mark for a fridge or a pizza oven
        assertTrue(conv("Oven", "Celsius", "Gas mark", 20.0).isNaN())
        assertTrue(conv("Oven", "Celsius", "Gas mark", 300.0).isNaN())
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
}
