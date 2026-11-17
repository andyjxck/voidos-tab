package com.andysocial.voidos.ui

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

    suspend fun refresh(store: Store, force: Boolean = false) {
        loadCache(store)
        val place = "${store.lat},${store.lon}"
        val cur = state.value
        if (!force && !offline.value && cur != null && place == forPlace && System.currentTimeMillis() - cur.fetched < 30 * 60_000) return
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=${store.lat}&longitude=${store.lon}" +
                    "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m,is_day" +
                    "&hourly=temperature_2m,weather_code,precipitation_probability" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,precipitation_probability_max" +
                    "&timezone=auto&forecast_days=6&forecast_hours=12"
                val raw = get(url)
                val now = System.currentTimeMillis()
                val wx = parse(raw, now)
                store.setWeatherCache(JSONObject().put("raw", raw).put("t", now).put("place", place).toString())
                wx
            }.onSuccess { state.value = it; error.value = null; offline.value = false; forPlace = place }
                .onFailure {
                    // keep whatever forecast we have; just say it's old
                    offline.value = state.value != null
                    error.value = "No connection"
                }
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
            Day(if (i == 0) "TODAY" else SimpleDateFormat("EEE", Locale.UK).format(t).uppercase(),
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

    /** Geocode a place name. Returns (name, lat, lon) or null. */
    suspend fun find(name: String): Triple<String, Float, Float>? = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + URLEncoder.encode(name, "UTF-8")))
            val r = o.getJSONArray("results").getJSONObject(0)
            Triple(r.getString("name"), r.getDouble("latitude").toFloat(), r.getDouble("longitude").toFloat())
        }.getOrNull()
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
    Row(
        modifier.clip(RoundedCornerShape(50)).background(V.Coral.copy(alpha = 0.16f)).border(1.5.dp, V.Coral, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(V.Coral))
        Gap(w = 8.dp)
        VText(if (wx == null) "OFFLINE" else "OFFLINE / ${wxAge(wx.fetched).uppercase()}", size = 11.sp, color = V.Coral, spacing = 1.5.sp, maxLines = 1)
    }
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
    G.SUN -> V.Sun; G.MOON -> V.Text; G.RAIN -> V.Teal; G.SNOW -> V.White; G.STORM -> V.Purple; else -> V.Muted
}

fun t(d: Double) = "${d.roundToInt()}°"

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
    var editing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var findErr by remember { mutableStateOf<String?>(null) }

    val portrait = LocalPortrait.current
    val now: @Composable (Modifier) -> Unit = { mod ->
        VPanel(mod, accent = wx?.let { wxColour(it.code, it.isDay) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (store.autoPlace) { Glyph(G.GLOBE, V.Sun, Modifier.size(16.dp)); Gap(w = 6.dp) }
                VText(store.city.uppercase(), size = 16.sp, color = V.Text, spacing = 2.sp, modifier = Modifier.weight(1f), maxLines = 1)
                VButton(if (editing) "Cancel" else "Place", accent = V.Muted) { editing = !editing; findErr = null }
            }
            if (off || (wx == null && err != null)) { Gap(h = 10.dp); OfflineBadge(wx) }
            if (editing) {
                Gap(h = 12.dp)
                VField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "Town or city")
                Gap(h = 8.dp)
                VButton("Find", accent = V.Sun, filled = true) {
                    scope.launch {
                        val r = WeatherRepo.find(query.trim())
                        if (r == null) findErr = "Couldn't find that place" else {
                            store.chooseAutoPlace(false)
                            store.setPlace(r.first, r.second, r.third); editing = false; query = ""
                            WeatherRepo.refresh(store, force = true)
                        }
                    }
                }
                findErr?.let { Gap(h = 6.dp); VText(it, size = 12.sp, color = V.Coral, font = FontFamily.Default) }
                if (!store.autoPlace) {
                    Gap(h = 8.dp)
                    VButton("Use my location", accent = V.Sun) {
                        store.chooseAutoPlace(true); editing = false
                    }
                }
            }
            val w = wx
            if (w == null) {
                Gap(h = if (portrait) 16.dp else 40.dp)
                VText(if (err != null) "NO WI-FI, NO SAVED FORECAST YET" else "LOADING SKY", size = 14.sp, color = V.Muted)
            } else if (portrait && !editing) {
                // portrait: glyph and temperature on the left, the numbers on the right
                Gap(h = 10.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Glyph(wxGlyph(w.code, w.isDay), wxColour(w.code, w.isDay), Modifier.size(84.dp), weight = 3f)
                    Gap(w = 14.dp)
                    Column {
                        VText(t(w.temp), size = 72.sp, color = V.White, font = Jupi)
                        VText(wxWords(w.code).uppercase(), size = 14.sp, color = wxColour(w.code, w.isDay), spacing = 2.sp, maxLines = 1)
                    }
                    Box(Modifier.weight(1f))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            Stat("Feels", t(w.feels)); Stat("Wind", "${w.wind.roundToInt()} km/h"); Stat("Humid", "${w.humidity}%")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) { Stat("Sunrise", w.sunrise); Stat("Sunset", w.sunset) }
                    }
                }
                Gap(h = 8.dp)
                Label("Open-Meteo", color = V.Tick)
            } else if (!portrait) {
                Gap(h = 24.dp)
                Glyph(wxGlyph(w.code, w.isDay), wxColour(w.code, w.isDay), Modifier.size(110.dp), weight = 3f)
                Gap(h = 10.dp)
                VText(t(w.temp), size = 96.sp, color = V.White, font = Jupi)
                VText(wxWords(w.code).uppercase(), size = 16.sp, color = wxColour(w.code, w.isDay), spacing = 2.sp)
                Gap(h = 20.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    Stat("Feels", t(w.feels)); Stat("Wind", "${w.wind.roundToInt()} km/h"); Stat("Humid", "${w.humidity}%")
                }
                Gap(h = 14.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) { Stat("Sunrise", w.sunrise); Stat("Sunset", w.sunset) }
                Gap(h = 14.dp)
                Label("Open-Meteo", color = V.Tick)
            }
        }
    }
    val graph: @Composable (Modifier) -> Unit = { mod ->
        VPanel(mod) {
            Label("Next 12 hours")
            Gap(h = 10.dp)
            wx?.let { TempGraph(it.hours, Modifier.fillMaxWidth().weight(1f)) }
        }
    }
    val days: @Composable () -> Unit = {
        VPanel(Modifier.fillMaxWidth().height(200.dp)) {
            Label("Six days")
            Gap(h = 12.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                wx?.days?.forEach { d ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        VText(d.label, size = 12.sp, color = V.Muted, spacing = 1.sp)
                        Gap(h = 8.dp)
                        Glyph(wxGlyph(d.code), wxColour(d.code), Modifier.size(38.dp))
                        Gap(h = 8.dp)
                        VText(t(d.max), size = 20.sp, color = V.White, font = Jupi)
                        VText(t(d.min), size = 14.sp, color = V.Muted, font = Jupi)
                        VText("${d.rain}%", size = 11.sp, color = V.Teal)
                    }
                }
            }
        }
    }

    if (portrait) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            now(Modifier.fillMaxWidth())
            graph(Modifier.fillMaxWidth().weight(1f))
            days()
        }
    } else {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            now(Modifier.width(380.dp).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                graph(Modifier.fillMaxWidth().weight(1f))
                days()
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column { Label(label); Gap(h = 2.dp); VText(value, size = 16.sp, color = V.Text) }
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
            drawPath(path, V.Sun, style = Stroke(4f, cap = StrokeCap.Round))
            hours.forEachIndexed { i, h -> drawOct(Offset(i * stepX, y(h.temp)), 7f, V.Bg, fill = true); drawOct(Offset(i * stepX, y(h.temp)), 7f, V.Sun) }
        }
        Gap(h = 6.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            hours.forEach { h ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    VText(t(h.temp), size = 13.sp, color = V.Text)
                    VText(h.label, size = 11.sp, color = V.Muted)
                }
            }
        }
    }
}
