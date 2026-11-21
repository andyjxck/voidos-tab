package com.andysocial.voidos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.andysocial.voidos.ui.LocalPortrait
import com.andysocial.voidos.ui.PlaceSwipe
import com.andysocial.voidos.ui.WeatherRepo
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Weather: several kept places, and swiping up to go round them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WeatherPlacesTest {

    @get:Rule val rule = createComposeRule()

    private val london = "London|51.5|-0.12"
    private val paris = "Paris|48.85|2.35"
    private val tokyo = "Tokyo|35.68|139.69"

    private fun raw(temp: Double) = """{"current":{"temperature_2m":$temp,"apparent_temperature":$temp,"weather_code":3,"wind_speed_10m":10,"relative_humidity_2m":80,"is_day":1},
        "hourly":{"time":["2026-11-18T22:00"],"temperature_2m":[$temp],"weather_code":[3],"precipitation_probability":[10]},
        "daily":{"time":["2026-11-18"],"weather_code":[3],"temperature_2m_max":[$temp],"temperature_2m_min":[$temp],"sunrise":["2026-11-18T07:31"],"sunset":["2026-11-18T16:12"],"precipitation_probability_max":[10]}}"""

    private fun key(e: String) = e.split('|').let { "${it[1].toFloat()},${it[2].toFloat()}" }
    private fun save(s: Store, e: String, temp: Double, at: Long = System.currentTimeMillis()) =
        s.setWeatherCacheFor(key(e), JSONObject().put("raw", raw(temp)).put("t", at).put("place", key(e)).toString())

    // ---- which place comes next

    @Test fun goesRoundAndStartsAgain() {
        val order = PlaceSwipe.order(listOf(london, paris))
        assertEquals(listOf(PlaceSwipe.HERE, london, paris), order)
        assertEquals(london, PlaceSwipe.next(order, PlaceSwipe.HERE))
        assertEquals(paris, PlaceSwipe.next(order, london))
        assertEquals(PlaceSwipe.HERE, PlaceSwipe.next(order, paris)) // the end goes back to the first
    }

    @Test fun nowhereElseToGo() {
        assertNull(PlaceSwipe.next(PlaceSwipe.order(emptyList()), PlaceSwipe.HERE))
        // one kept place: Here and that place swap
        val one = PlaceSwipe.order(listOf(london))
        assertEquals(london, PlaceSwipe.next(one, PlaceSwipe.HERE))
        assertEquals(PlaceSwipe.HERE, PlaceSwipe.next(one, london))
    }

    @Test fun currentPlace() {
        assertEquals(PlaceSwipe.HERE, PlaceSwipe.current(true, "Paris", listOf(london, paris)))
        assertEquals(paris, PlaceSwipe.current(false, "Paris", listOf(london, paris)))
        assertEquals(PlaceSwipe.HERE, PlaceSwipe.current(false, "Rome", listOf(london, paris)))
    }

    // ---- which drags it takes

    @Test fun onlyAClearPushUp() {
        val slop = 10f
        assertEquals(PlaceSwipe.Call.WAIT, PlaceSwipe.call(3f, -5f, slop))
        assertEquals(PlaceSwipe.Call.CLAIM, PlaceSwipe.call(4f, -20f, slop))
        assertEquals(PlaceSwipe.Call.PASS, PlaceSwipe.call(-30f, -5f, slop))  // page swipe
        assertEquals(PlaceSwipe.Call.PASS, PlaceSwipe.call(15f, -15f, slop))  // diagonal: not ours
        assertEquals(PlaceSwipe.Call.PASS, PlaceSwipe.call(0f, 20f, slop))    // downwards
        assertTrue(PlaceSwipe.commits(-80f, 0f, 72f, 900f))
        assertFalse(PlaceSwipe.commits(-50f, -100f, 72f, 900f))
        assertTrue(PlaceSwipe.commits(-30f, -1500f, 72f, 900f))  // quick flick
        assertFalse(PlaceSwipe.commits(-10f, -1500f, 72f, 900f))
        assertTrue(PlaceSwipe.fromBottomEdge(790f, 800f, 28f))
        assertFalse(PlaceSwipe.fromBottomEdge(500f, 800f, 28f))
    }

    // ---- each place shows its own weather

    @Test fun eachPlaceItsOwnForecast() = runBlocking {
        val s = Store(RuntimeEnvironment.getApplication())
        s.chooseAutoPlace(false)
        s.addPlace("London", 51.5f, -0.12f); s.addPlace("Paris", 48.85f, 2.35f)
        save(s, london, 7.0); save(s, paris, 15.0)
        s.setPlace("London", 51.5f, -0.12f); WeatherRepo.refresh(s)
        assertEquals(7.0, WeatherRepo.state.value!!.temp, 0.01)
        s.setPlace("Paris", 48.85f, 2.35f); WeatherRepo.refresh(s)
        assertEquals(15.0, WeatherRepo.state.value!!.temp, 0.01)
        assertFalse(WeatherRepo.offline.value)
    }

    @Test fun hereComesBackToWhereTheTabletWas() {
        val s = Store(RuntimeEnvironment.getApplication())
        s.chooseAutoPlace(true); s.setPlace("Redditch", 52.31f, -1.94f)
        assertEquals("Redditch|52.31|-1.94", s.herePlace())
        s.chooseAutoPlace(false); s.setPlace("Paris", 48.85f, 2.35f)
        assertEquals("Redditch|52.31|-1.94", s.herePlace()) // a picked place isn't "here"
    }


}
