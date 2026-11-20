package com.andysocial.voidos.ui

import com.andysocial.voidos.R

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import com.andysocial.voidos.Locator
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andysocial.voidos.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.abs
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView

data class Hour(val label: String, val temp: Double, val code: Int, val rain: Int)
data class Day(val label: String, val code: Int, val max: Double, val min: Double, val rain: Int)
data class Wx(
    val temp: Double, val feels: Double, val code: Int, val wind: Double, val humidity: Int, val isDay: Boolean,
    val sunrise: String, val sunset: String, val hours: List<Hour>, val days: List<Day>, val fetched: Long,
)

/**
 * Open-Meteo: free, no key, no account. A fresh forecast is kept for 30
 * minutes. Every good answer is also saved, so after a restart or with no
 * Wi-Fi the last forecast still shows, marked offline with its age.
 */
object WeatherRepo {
    val state = mutableStateOf<Wx?>(null)
    val error = mutableStateOf<String?>(null)
    /** True when what's on screen is the saved forecast, because the last fetch failed. */
    val offline = mutableStateOf(false)
    private var forPlace = ""
    private var loaded = false

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        return try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    /** Put the saved forecast on screen once, before any fetch, so the face is never blank. */
    fun loadCache(store: Store) {
        if (loaded) return
        loaded = true
        val raw = store.weatherCache() ?: return
        runCatching {
            val o = JSONObject(raw)
            parse(o.getString("raw"), o.getLong("t")) to o.optString("place")
        }.onSuccess { (wx, place) ->
            if (state.value == null) { state.value = wx; forPlace = place; offline.value = true }
        }
    }

    private fun fresh(wx: Wx) = System.currentTimeMillis() - wx.fetched < 30 * 60_000

    /** The last forecast saved for exactly this place, or null. Never another place's. */
    private fun cached(store: Store, place: String): Wx? {
        val raw = store.weatherCacheFor(place)
            ?: store.weatherCache()?.takeIf { runCatching { JSONObject(it).optString("place") }.getOrNull() == place }
            ?: return null
        return runCatching { val o = JSONObject(raw); parse(o.getString("raw"), o.getLong("t")) }.getOrNull()
    }

    suspend fun refresh(store: Store, force: Boolean = false) {
        loadCache(store)
        val place = "${store.lat},${store.lon}"
        if (place != forPlace) {
            // a different place: drop the last place's weather at once. Its own saved forecast
            // shows straight away only while it's fresh; otherwise the page says it's loading.
            val c = cached(store, place)
            forPlace = place; error.value = null
            if (c != null && fresh(c)) { state.value = c; offline.value = false } else { state.value = null; offline.value = false }
        }
        val cur = state.value
        if (!force && !offline.value && cur != null && fresh(cur)) return
        val got = withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=${store.lat}&longitude=${store.lon}" +
                    "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m,is_day" +
                    "&hourly=temperature_2m,weather_code,precipitation_probability" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,precipitation_probability_max" +
                    "&timezone=auto&forecast_days=6&forecast_hours=12"
                val raw = get(url)
                val now = System.currentTimeMillis()
                val wx = parse(raw, now)
                val saved = JSONObject().put("raw", raw).put("t", now).put("place", place).toString()
                store.setWeatherCache(saved)
                store.setWeatherCacheFor(place, saved)
                wx
            }
        }
        // swiped on to another place while this was loading: this answer isn't for the page any more
        if ("${store.lat},${store.lon}" != place || forPlace != place) return
        got.onSuccess { state.value = it; error.value = null; offline.value = false }
            .onFailure {
                // keep this place's last forecast if there is one; just say it's old
                if (state.value == null) state.value = cached(store, place)
                offline.value = state.value != null
                error.value = "No connection"
            }
    }

    /** Open-Meteo's answer -> [Wx]. Public so the offline cache and the tests read it the same way. */
    fun parse(raw: String, fetched: Long): Wx {
        val o = JSONObject(raw)
        val c = o.getJSONObject("current")
        val h = o.getJSONObject("hourly")
        val d = o.getJSONObject("daily")
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.UK)
        val isoD = SimpleDateFormat("yyyy-MM-dd", Locale.UK)
        val hours = (0 until h.getJSONArray("time").length()).map { i ->
            val t = iso.parse(h.getJSONArray("time").getString(i))!!
            Hour(SimpleDateFormat("HH", Locale.UK).format(t), h.getJSONArray("temperature_2m").getDouble(i),
                h.getJSONArray("weather_code").getInt(i), h.getJSONArray("precipitation_probability").optInt(i, 0))
        }
        val days = (0 until d.getJSONArray("time").length()).map { i ->
            val t = isoD.parse(d.getJSONArray("time").getString(i))!!
            Day(if (i == 0) "Today" else SimpleDateFormat("EEE", Locale.UK).format(t),
                d.getJSONArray("weather_code").getInt(i), d.getJSONArray("temperature_2m_max").getDouble(i),
                d.getJSONArray("temperature_2m_min").getDouble(i), d.getJSONArray("precipitation_probability_max").optInt(i, 0))
        }
        fun hm(s: String) = s.substringAfter('T')
        return Wx(
            c.getDouble("temperature_2m"), c.getDouble("apparent_temperature"), c.getInt("weather_code"),
            c.getDouble("wind_speed_10m"), c.getInt("relative_humidity_2m"), c.getInt("is_day") == 1,
            hm(d.getJSONArray("sunrise").getString(0)), hm(d.getJSONArray("sunset").getString(0)),
            hours, days, fetched,
        )
    }

    /** A place found by name: its name, where it is (region, country) and its position. */
    data class Place(val name: String, val where: String, val lat: Float, val lon: Float, val tz: String? = null)

    /** Geocode a place name. Returns the best match or null. */
    suspend fun find(name: String): Triple<String, Float, Float>? = search(name).firstOrNull()?.let { Triple(it.name, it.lat, it.lon) }

    /** Up to [n] places matching [name], best first; empty if none or offline. */
    suspend fun search(name: String, n: Int = 8): List<Place> = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext emptyList()
        runCatching {
            val o = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=$n&name=" + URLEncoder.encode(name, "UTF-8")))
            val a = o.optJSONArray("results") ?: return@runCatching emptyList()
            (0 until a.length()).map { i ->
                val r = a.getJSONObject(i)
                Place(
                    r.getString("name"),
                    listOf(r.optString("admin1"), r.optString("country")).filter { it.isNotBlank() }.joinToString(", "),
                    r.getDouble("latitude").toFloat(), r.getDouble("longitude").toFloat(), r.optString("timezone").ifBlank { null },
                )
            }
        }.getOrDefault(emptyList())
    }
}

/** "Updated 3h ago" style age for the offline badge. */
fun wxAge(fetched: Long, now: Long = System.currentTimeMillis()): String {
    val m = ((now - fetched) / 60_000).coerceAtLeast(0)
    return when {
        m < 1 -> "just now"
        m < 60 -> "${m}m ago"
        m < 48 * 60 -> "${m / 60}h ago"
        else -> "${m / (24 * 60)}d ago"
    }
}

/** The offline flag: impossible to miss, says how old the numbers are. */
@Composable
fun OfflineBadge(wx: Wx?, modifier: Modifier = Modifier) {
    // a full-width banner: no Wi-Fi is the first thing you see on the page
    Row(
        modifier.fillMaxWidth().droplet(Radius.Card, V.Coral, 2.2f)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(G.WIFI_OFF, V.Coral, Modifier.size(30.dp))
        Gap(w = 16.dp)
        Column(Modifier.weight(1f)) {
            VText("No Wi-Fi", size = Type.title2, font = SansMedium, color = V.White, maxLines = 1)
            VText(if (wx == null) "No data" else "From ${wxAge(wx.fetched)}", size = Type.body, color = V.Text, maxLines = 1)
        }
    }
}

/** WMO weather code -> a full-colour weather picture (Meteocons by Bas Milius, MIT; tools/meteocons). */
fun wxArt(code: Int, day: Boolean = true): Int {
    val d = day
    return when (code) {
        0 -> if (d) R.drawable.wx_clear_day else R.drawable.wx_clear_night
        1, 2 -> if (d) R.drawable.wx_partly_cloudy_day else R.drawable.wx_partly_cloudy_night
        3 -> if (d) R.drawable.wx_overcast_day else R.drawable.wx_overcast_night
        45, 48 -> if (d) R.drawable.wx_fog_day else R.drawable.wx_fog_night
        51, 53, 55 -> R.drawable.wx_drizzle
        56, 57, 66, 67 -> R.drawable.wx_sleet
        61, 63 -> R.drawable.wx_rain
        65, 82 -> R.drawable.wx_extreme_rain
        71, 73, 77 -> R.drawable.wx_snow
        75 -> R.drawable.wx_extreme_snow
        80, 81 -> if (d) R.drawable.wx_partly_cloudy_day_rain else R.drawable.wx_partly_cloudy_night_rain
        85, 86 -> if (d) R.drawable.wx_partly_cloudy_day_snow else R.drawable.wx_partly_cloudy_night_snow
        95 -> if (d) R.drawable.wx_thunderstorms_day else R.drawable.wx_thunderstorms_night
        96, 99 -> if (d) R.drawable.wx_thunderstorms_day_rain else R.drawable.wx_thunderstorms_night_rain
        else -> R.drawable.wx_cloudy
    }
}

/** The weather picture for a code, in its own colours. The art has a margin of its own, so it's drawn a little larger than a glyph. */
@Composable
fun WxIcon(code: Int, day: Boolean = true, modifier: Modifier = Modifier.size(40.dp)) {
    androidx.compose.foundation.Image(
        androidx.compose.ui.res.painterResource(wxArt(code, day)), contentDescription = wxWords(code),
        modifier = modifier, contentScale = androidx.compose.ui.layout.ContentScale.Fit,
    )
}

/** WMO weather code -> glyph + words. */
fun wxGlyph(code: Int, day: Boolean = true): G = when (code) {
    0, 1 -> if (day) G.SUN else G.MOON
    2, 3 -> G.CLOUD
    45, 48 -> G.FOG
    in 51..67, in 80..82 -> G.RAIN
    in 71..77, 85, 86 -> G.SNOW
    in 95..99 -> G.STORM
    else -> G.CLOUD
}

fun wxWords(code: Int): String = when (code) {
    0 -> "Clear"; 1 -> "Mostly clear"; 2 -> "Partly cloudy"; 3 -> "Overcast"
    45, 48 -> "Fog"
    51, 53, 55 -> "Drizzle"; 56, 57 -> "Freezing drizzle"
    61 -> "Light rain"; 63 -> "Rain"; 65 -> "Heavy rain"; 66, 67 -> "Freezing rain"
    71 -> "Light snow"; 73 -> "Snow"; 75 -> "Heavy snow"; 77 -> "Snow grains"
    80 -> "Showers"; 81 -> "Heavy showers"; 82 -> "Violent showers"
    85, 86 -> "Snow showers"
    95 -> "Thunderstorm"; 96, 99 -> "Thunder and hail"
    else -> "Unknown"
}

fun wxColour(code: Int, day: Boolean = true): Color = when (wxGlyph(code, day)) {
    G.SUN -> V.Sun; else -> V.Text
}

fun t(d: Double) = "${d.roundToInt()}°"

/**
 * Swiping up on the Weather page moves to the next place: Here first, then the
 * places you've kept, in the order of the bar, and round to the start again.
 * The rules are kept apart from the screen so the tests can check them.
 */
object PlaceSwipe {
    const val HERE = "here"

    fun order(places: List<String>): List<String> = listOf(HERE) + places

    fun current(autoPlace: Boolean, city: String, places: List<String>): String =
        if (autoPlace) HERE else places.firstOrNull { it.substringBefore('|') == city } ?: HERE

    /** The place after [cur], back to the first after the last; null when there's nowhere else to go. */
    fun next(order: List<String>, cur: String): String? {
        if (order.size < 2) return null
        val i = order.indexOf(cur).coerceAtLeast(0)
        return order[(i + 1) % order.size]
    }

    enum class Call { WAIT, CLAIM, PASS }

    /**
     * After the finger has moved ([dx], [dy] from where it went down): keep waiting, take the
     * gesture (a clear push upwards), or leave it to the page swipe and everything else.
     */
    fun call(dx: Float, dy: Float, slop: Float): Call = when {
        dx * dx + dy * dy < slop * slop -> Call.WAIT
        dy < 0 && -dy > abs(dx) * 1.5f -> Call.CLAIM
        else -> Call.PASS
    }

    /** On lift: far enough up, or a quick flick up part of the way. */
    fun commits(dy: Float, vy: Float, distance: Float, flingSpeed: Float): Boolean =
        -dy >= distance || (vy <= -flingSpeed && -dy >= distance / 3)

    /** A touch that starts on the bottom edge belongs to the app switcher, never to this. */
    fun fromBottomEdge(rootY: Float, rootHeight: Float, guard: Float): Boolean =
        rootHeight > 0 && rootY > rootHeight - guard
}

/** Swipe up anywhere on the page (not from the bottom edge) to call [onEnd] with whether it counted. */
private fun Modifier.placeSwipe(
    enabled: Boolean,
    rootTop: () -> Float,
    rootHeight: () -> Float,
    onDrag: (Float) -> Unit,
    onEnd: (Boolean) -> Unit,
): Modifier = pointerInput(enabled) {
    if (!enabled) return@pointerInput
    val slop = viewConfiguration.touchSlop
    val distance = 72.dp.toPx()
    val fling = 900.dp.toPx()
    val guard = 28.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (PlaceSwipe.fromBottomEdge(down.position.y + rootTop(), rootHeight(), guard)) return@awaitEachGesture
        val vt = VelocityTracker()
        vt.addPosition(down.uptimeMillis, down.position)
        var claimed = false
        while (true) {
            val ev = awaitPointerEvent()
            val ch = ev.changes.firstOrNull { it.id == down.id }
            if (ch == null || ev.changes.count { it.pressed } > 1) { if (claimed) onEnd(false); break }
            val d = ch.position - down.position
            if (!ch.pressed) {
                // a lift we've been consuming isn't consumed by anyone else; one that is was a cancel
                if (claimed) onEnd(!ch.isConsumed && PlaceSwipe.commits(d.y, vt.calculateVelocity().y, distance, fling))
                break
            }
            vt.addPosition(ch.uptimeMillis, ch.position)
            if (!claimed) {
                if (ch.isConsumed) break // something inside took it first
                when (PlaceSwipe.call(d.x, d.y, slop)) {
                    PlaceSwipe.Call.CLAIM -> claimed = true
                    PlaceSwipe.Call.PASS -> break
                    PlaceSwipe.Call.WAIT -> {}
                }
            }
            if (claimed) { ch.consume(); onDrag(d.y) }
        }
    }
}

@Composable
fun WeatherScreen(store: Store) {
    val wx by WeatherRepo.state
    val err by WeatherRepo.error
    val off by WeatherRepo.offline
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    // ask once for location if we can't grant it ourselves (device owner grants it silently)
    val askLoc = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scope.launch { if (Locator.locate(ctx, store)) WeatherRepo.refresh(store, force = true) }
    }
    LaunchedEffect(store.autoPlace) {
        if (store.autoPlace && !Locator.selfGrant(ctx)) askLoc.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        else Locator.locate(ctx, store)
    }
    LaunchedEffect(store.lat, store.lon) { WeatherRepo.refresh(store) }
    // a place picked before places could be kept becomes the first kept place
    LaunchedEffect(Unit) { if (!store.autoPlace && store.places.isEmpty() && store.city.isNotBlank()) store.addPlace(store.city, store.lat, store.lon) }
    var editing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var findErr by remember { mutableStateOf<String?>(null) }

    // move to a place from the bar or the swipe: Here goes back to where the tablet last was
    // straight away, and the forecast follows the place (refresh runs when lat/lon change)
    fun select(k: String) {
        if (k == PlaceSwipe.HERE) {
            store.chooseAutoPlace(true)
            store.herePlace()?.split('|')?.let { (n, la, lo) ->
                val a = la.toFloatOrNull(); val o = lo.toFloatOrNull()
                if (a != null && o != null) store.setPlace(n, a, o)
            }
        } else {
            val (n, la, lo) = k.split('|')
            store.chooseAutoPlace(false); store.setPlace(n, la.toFloat(), lo.toFloat())
        }
    }
    val order = PlaceSwipe.order(store.places)
    val canSwipe = !editing && order.size >= 2
    val drag = remember { Animatable(0f) }
    val view = LocalView.current
    val density = LocalDensity.current
    var rootTop by remember { mutableStateOf(0f) }
    val hop = with(density) { 56.dp.toPx() }
    // a swipe up that counted moves on to the next place; one that didn't springs back
    fun finishSwipe(go: Boolean) {
        scope.launch {
            val next = PlaceSwipe.next(PlaceSwipe.order(store.places), PlaceSwipe.current(store.autoPlace, store.city, store.places))
            if (go && next != null) {
                drag.animateTo(-hop, tween(110))
                select(next)
                drag.snapTo(hop)
                drag.animateTo(0f, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))
            } else drag.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium))
        }
    }
    val swipe = Modifier
        .onGloballyPositioned { rootTop = it.positionInRoot().y }
        .placeSwipe(
            enabled = canSwipe,
            rootTop = { rootTop },
            rootHeight = { view.rootView.height.toFloat() },
            onDrag = { dy -> scope.launch { drag.snapTo((dy * 0.35f).coerceIn(-hop, 0f)) } },
            onEnd = { go -> finishSwipe(go) },
        )
        .graphicsLayer {
            translationY = drag.value
            alpha = 1f - (abs(drag.value) / (hop * 2.5f)).coerceIn(0f, 0.4f)
        }

    val portrait = LocalPortrait.current
    // compact: half the screen in the book (or beside an app), where neither layout below fits
    val now: @Composable (Modifier, Boolean) -> Unit = { mod, compact ->
        VPanel(mod, accent = wx?.let { wxColour(it.code, it.isDay) }, padding = if (compact) 16.dp else 20.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (store.autoPlace) { Glyph(G.GLOBE, V.Muted, Modifier.size(16.dp)); Gap(w = 8.dp) }
                VText(store.city, size = Type.headline, color = V.Text, modifier = Modifier.weight(1f), maxLines = 1)
                VButton(if (editing) "Done" else "Places", accent = V.Muted) { editing = !editing; findErr = null }
            }
            // more than one place: one glass bar to switch between them
            if (!editing && store.places.isNotEmpty()) {
                Gap(h = 10.dp)
                val opts = listOf("here" to "Here") + store.places.map { it to it.substringBefore('|') }
                val cur = PlaceSwipe.current(store.autoPlace, store.city, store.places)
                GlassSegments(opts, cur, Modifier.fillMaxWidth(), height = 40.dp) { k -> select(k) }
            }
            if (off || (wx == null && err != null)) { Gap(h = 8.dp); OfflineBadge(wx) }
            if (editing) {
                Gap(h = 12.dp)
                PlaceSearch(query, { query = it }) { r ->
                    store.chooseAutoPlace(false)
                    store.addPlace(r.name, r.lat, r.lon)
                    store.setPlace(r.name, r.lat, r.lon); editing = false; query = ""
                    scope.launch { WeatherRepo.refresh(store, force = true) }
                }
                // the places you've kept: tap the cross to drop one
                store.places.forEach { e ->
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph(G.PIN, V.Muted, Modifier.size(18.dp)); Gap(w = 10.dp)
                        VText(e.substringBefore('|'), size = Type.body, color = V.Text, modifier = Modifier.weight(1f), maxLines = 1)
                        RoundIcon(G.CLOSE, "Remove ${e.substringBefore('|')}", size = 34.dp) { store.removePlace(e) }
                    }
                }
                findErr?.let { Gap(h = 8.dp); VText(it, size = Type.caption, color = V.Coral, font = Sans) }
                if (!store.autoPlace) {
                    Gap(h = 8.dp)
                    VButton("Use my location") {
                        store.chooseAutoPlace(true); editing = false
                    }
                }
            }
            val w = wx
            if (w == null) {
                Gap(h = if (portrait) 16.dp else 40.dp)
                VText(if (err != null) "NO WI-FI, NO SAVED FORECAST YET" else "LOADING SKY", size = Type.body, color = V.Muted)
            } else if (compact) {
                if (!editing) CompactNow(w)
            } else if (portrait && !editing) {
                // portrait: glyph and temperature on the left, the numbers on the right
                Gap(h = 8.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WxIcon(w.code, w.isDay, Modifier.size(112.dp))
                    Gap(w = 16.dp)
                    Column {
                        VText(t(w.temp), size = 72.sp, color = V.White, font = SansLight)
                        VText(wxWords(w.code), size = Type.body, color = wxColour(w.code, w.isDay), maxLines = 1)
                    }
                    Box(Modifier.weight(1f))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Stat("Feels", t(w.feels)); Stat("Wind", "${w.wind.roundToInt()} km/h"); Stat("Humidity", "${w.humidity}%")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { Stat("Sunrise", w.sunrise); Stat("Sunset", w.sunset) }
                    }
                }
                Gap(h = 8.dp)
                Label("Open-Meteo", color = V.Tick)
            } else if (!portrait) {
                // glyph beside the temperature, so the place bar, the hero and both rows of numbers
                // fit the 524 dp panel (stacked, sunrise, sunset and the credit fell off the bottom)
                Gap(h = 16.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WxIcon(w.code, w.isDay, Modifier.size(112.dp))
                    Gap(w = 16.dp)
                    Column {
                        VText(t(w.temp), size = 84.sp, color = V.White, font = SansLight, maxLines = 1)
                        VText(wxWords(w.code), size = Type.headline, color = wxColour(w.code, w.isDay), maxLines = 2)
                    }
                }
                Gap(h = 20.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Stat("Feels", t(w.feels)); Stat("Wind", "${w.wind.roundToInt()} km/h"); Stat("Humidity", "${w.humidity}%")
                }
                Gap(h = 16.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) { Stat("Sunrise", w.sunrise); Stat("Sunset", w.sunset) }
                Gap(h = 16.dp)
                Label("Open-Meteo", color = V.Tick)
            }
        }
    }
    val graph: @Composable (Modifier) -> Unit = { mod ->
        VPanel(mod) {
            Label("Next 12 hours")
            Gap(h = 8.dp)
            wx?.let { TempGraph(it.hours, Modifier.fillMaxWidth().weight(1f)) }
        }
    }
    val days: @Composable () -> Unit = {
        // as tall as its rows: a fixed 200 dp cut the rain chances off the bottom
        VPanel(Modifier.fillMaxWidth().heightIn(min = 200.dp)) {
            Label("Six days")
            Gap(h = 12.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                wx?.days?.forEach { d ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        VText(d.label, size = Type.caption, color = V.Muted)
                        Gap(h = 8.dp)
                        WxIcon(d.code, true, Modifier.size(52.dp))
                        Gap(h = 8.dp)
                        VText(t(d.max), size = Type.title, color = V.White, font = SansLight)
                        VText(t(d.min), size = Type.body, color = V.Muted, font = SansLight)
                        VText("${d.rain}%", size = Type.caption, color = V.Teal)
                    }
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (compactWeather(maxWidth, maxHeight)) {
            // Half a screen: one column that scrolls when it has to. Nothing is squeezed to fit:
            // the hours wrap into rows, the days stack as a list. When it scrolls, pushing on
            // past the bottom still swipes to the next place.
            val scroll = rememberScrollState()
            val pull = remember { mutableFloatStateOf(0f) }
            // a swipe that scrolled the page on its way down doesn't also change place when it
            // hits the bottom: you lift, then swipe again from the bottom to move on
            val scrolledThisSwipe = remember { mutableStateOf(false) }
            val distance = with(density) { 72.dp.toPx() }
            val fling = with(density) { 900.dp.toPx() }
            val overPull = remember(canSwipe) {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        // finger going back down while pulling: undo the pull before the page scrolls
                        if (pull.floatValue <= 0f && available.y < 0f && scroll.value < scroll.maxValue) scrolledThisSwipe.value = true
                        if (pull.floatValue <= 0f || available.y <= 0f) return Offset.Zero
                        val used = minOf(available.y, pull.floatValue)
                        pull.floatValue -= used
                        scope.launch { drag.snapTo((-pull.floatValue * 0.35f).coerceIn(-hop, 0f)) }
                        return Offset(0f, used)
                    }
                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                        if (!canSwipe || scrolledThisSwipe.value || source != NestedScrollSource.UserInput || available.y >= 0f) return Offset.Zero
                        pull.floatValue += -available.y
                        scope.launch { drag.snapTo((-pull.floatValue * 0.35f).coerceIn(-hop, 0f)) }
                        return Offset(0f, available.y)
                    }
                    override suspend fun onPreFling(available: Velocity): Velocity {
                        scrolledThisSwipe.value = false
                        if (pull.floatValue <= 0f) return Velocity.Zero
                        val go = PlaceSwipe.commits(-pull.floatValue, available.y, distance, fling)
                        pull.floatValue = 0f
                        finishSwipe(go)
                        return available
                    }
                }
            }
            Column(
                Modifier.fillMaxSize().then(swipe).nestedScroll(overPull)
                    .verticalScroll(scroll, enabled = scroll.maxValue > 0)
                    .testTag("weatherCompact"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                now(Modifier.fillMaxWidth(), true)
                wx?.let { w ->
                    CompactHours(w)
                    CompactDays(w.days)
                }
            }
        } else if (portrait) {
            Column(Modifier.fillMaxSize().then(swipe), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                now(Modifier.fillMaxWidth(), false)
                graph(Modifier.fillMaxWidth().weight(1f))
                days()
            }
        } else {
            Row(Modifier.fillMaxSize().then(swipe), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                now(Modifier.width(380.dp).fillMaxHeight(), false)
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    graph(Modifier.fillMaxWidth().weight(1f))
                    days()
                }
            }
        }
    }
}

/**
 * Full screen is about 880 x 524 dp lying down and 556 x 884 standing up; half the book is
 * about 434 x 472 or 556 x 384. Only a box that's small both ways gets the compact layout,
 * so full-screen Weather keeps the layout it always had.
 */
fun compactWeather(w: Dp, h: Dp) = w < 700.dp && h < 700.dp

/** Whether an hour ("07", "18") falls between sunrise and sunset ("07:35", "16:10"). */
fun dayAt(label: String, sunrise: String, sunset: String): Boolean {
    val h = label.take(2).toIntOrNull() ?: return true
    val up = sunrise.take(2).toIntOrNull() ?: return true
    val down = sunset.take(2).toIntOrNull() ?: return true
    return h in up..down
}

/** The hero for half a screen: glyph and temperature side by side, then the five numbers in a grid. */
@Composable
private fun CompactNow(w: Wx) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 480.dp
        Column {
            Gap(h = 10.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                WxIcon(w.code, w.isDay, Modifier.size(if (wide) 76.dp else 64.dp))
                Gap(w = 14.dp)
                Column(Modifier.weight(1f)) {
                    VText(t(w.temp), size = if (wide) 56.sp else 52.sp, color = V.White, font = SansLight, maxLines = 1)
                    VText(wxWords(w.code), size = Type.body, color = wxColour(w.code, w.isDay), maxLines = 2)
                }
            }
            Gap(h = 12.dp)
            val stats = listOf(
                "Feels" to t(w.feels), "Wind" to "${w.wind.roundToInt()} km/h", "Humidity" to "${w.humidity}%",
                "Sunrise" to w.sunrise, "Sunset" to w.sunset,
            )
            val cols = if (wide) 5 else 3
            stats.chunked(cols).forEachIndexed { i, row ->
                if (i > 0) Gap(h = 10.dp)
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { (l, v) ->
                        Column(Modifier.weight(1f)) { Label(l); Gap(h = 2.dp); VText(v, size = Type.headline, color = V.Text) }
                    }
                    repeat(cols - row.size) { Box(Modifier.weight(1f)) }
                }
            }
            Gap(h = 10.dp)
            Label("Open-Meteo", color = V.Tick)
        }
    }
}

/** The next 12 hours for half a screen: rows of six (or all twelve when there's room), never squeezed. */
@Composable
private fun CompactHours(w: Wx) {
    if (w.hours.isEmpty()) return
    VPanel(Modifier.fillMaxWidth().testTag("weatherHours"), padding = 16.dp) {
        Label("Next 12 hours")
        Gap(h = 10.dp)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cols = when {
                maxWidth >= 56.dp * 12 -> 12
                maxWidth >= 56.dp * 6 -> 6
                else -> 4
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                w.hours.chunked(cols).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { h ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                VText(h.label, size = Type.caption, color = V.Muted)
                                Gap(h = 4.dp)
                                WxIcon(h.code, dayAt(h.label, w.sunrise, w.sunset), Modifier.size(28.dp))
                                Gap(h = 4.dp)
                                VText(t(h.temp), size = Type.headline, color = V.Text)
                                VText("${h.rain}%", size = Type.caption, color = V.Teal)
                            }
                        }
                        repeat(cols - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** Six days for half a screen: one row each, low and high either side of a bar across the week's range. */
@Composable
private fun CompactDays(days: List<Day>) {
    if (days.isEmpty()) return
    val lo = days.minOf { it.min }
    val hi = days.maxOf { it.max }
    VPanel(Modifier.fillMaxWidth().testTag("weatherDays"), padding = 16.dp) {
        Label("Six days")
        Gap(h = 6.dp)
        days.forEach { d ->
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                VText(d.label, Modifier.widthIn(min = 56.dp), size = Type.body, color = V.Text)
                Gap(w = 6.dp)
                WxIcon(d.code, true, Modifier.size(30.dp))
                Gap(w = 8.dp)
                VText("${d.rain}%", Modifier.widthIn(min = 44.dp), size = Type.label, color = V.Teal)
                VText(t(d.min), Modifier.widthIn(min = 40.dp), size = Type.body, color = V.Muted, font = SansLight, align = TextAlign.End)
                Gap(w = 10.dp)
                Canvas(Modifier.weight(1f).height(6.dp)) {
                    val span = (hi - lo).takeIf { it > 0 } ?: 1.0
                    val a = ((d.min - lo) / span).toFloat() * size.width
                    val b = ((d.max - lo) / span).toFloat() * size.width
                    val y = size.height / 2
                    drawLine(V.Muted.copy(alpha = 0.22f), Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
                    drawLine(
                        Brush.horizontalGradient(listOf(V.Teal, wxColour(d.code)), startX = a, endX = maxOf(b, a + 1f)),
                        Offset(a, y), Offset(maxOf(b, a + 1f), y), size.height, StrokeCap.Round,
                    )
                }
                Gap(w = 10.dp)
                VText(t(d.max), Modifier.widthIn(min = 40.dp), size = Type.body, color = V.White)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column { Label(label); Gap(h = 2.dp); VText(value, size = Type.headline, color = V.Text) }
}

@Composable
fun TempGraph(hours: List<Hour>, modifier: Modifier) {
    if (hours.isEmpty()) return
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val lo = hours.minOf { it.temp } - 1; val hi = hours.maxOf { it.temp } + 1
            val stepX = size.width / (hours.size - 1).coerceAtLeast(1)
            fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()
            // rain bars
            hours.forEachIndexed { i, h ->
                val bh = size.height * h.rain / 100f * 0.6f
                drawLine(V.Teal.copy(alpha = 0.25f), Offset(i * stepX, size.height), Offset(i * stepX, size.height - bh), 10f)
            }
            val path = Path()
            hours.forEachIndexed { i, h -> if (i == 0) path.moveTo(0f, y(h.temp)) else path.lineTo(i * stepX, y(h.temp)) }
            drawPath(path, V.Text.copy(alpha = 0.85f), style = Stroke(4f, cap = StrokeCap.Round))
            hours.forEachIndexed { i, h -> drawOct(Offset(i * stepX, y(h.temp)), 7f, V.Bg, fill = true); drawOct(Offset(i * stepX, y(h.temp)), 7f, V.Text) }
        }
        Gap(h = 8.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            hours.forEach { h ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    VText(t(h.temp), size = Type.label, color = V.Text)
                    VText(h.label, size = Type.caption, color = V.Muted)
                }
            }
        }
    }
}

/**
 * Search for a place as you type: matching towns and cities, each with its region and
 * country so you can tell the Newports apart. Tap one to choose it.
 */
@Composable
fun PlaceSearch(query: String, onQuery: (String) -> Unit, onPick: (WeatherRepo.Place) -> Unit) {
    var results by remember { mutableStateOf<List<WeatherRepo.Place>>(emptyList()) }
    var looked by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.trim().length < 2) { results = emptyList(); looked = false; return@LaunchedEffect }
        kotlinx.coroutines.delay(300)
        results = WeatherRepo.search(query.trim()); looked = true
    }
    VField(query, onQuery, Modifier.fillMaxWidth(), placeholder = "Search a town or city")
    Gap(h = 8.dp)
    results.forEach { r ->
        Row(
            Modifier.fillMaxWidth().clip(Radius.Control).tap { onPick(r) }.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(G.PIN, V.Muted, Modifier.size(18.dp)); Gap(w = 12.dp)
            Column(Modifier.weight(1f)) {
                VText(r.name, size = Type.headline, color = V.Text, maxLines = 1)
                if (r.where.isNotBlank()) VText(r.where, size = Type.label, color = V.Muted, maxLines = 1)
            }
        }
    }
    if (looked && results.isEmpty()) VText("No places found", size = Type.label, color = V.Muted)
}
