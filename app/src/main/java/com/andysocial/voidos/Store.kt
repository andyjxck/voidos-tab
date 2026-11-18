package com.andysocial.voidos

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * A note: typed text plus anything drawn on it. [ink] strokes keep their
 * points as fractions of the page width, so a drawing keeps its shape when
 * the tablet turns.
 */
data class Note(val id: Long, val text: String, val colour: Int, val updated: Long, val ink: List<Stroke> = emptyList())

/** One pen stroke: [argb] colour, [width] as a fraction of the page width, [style] 0 solid, 1 dotted, 2 dashed. */
data class Stroke(val argb: Int, val width: Float, val style: Int, val pts: List<Float>)

/** A widget on the face. [x], [y] place its top-left corner as fractions of the free space; [ref] is the note it shows. */
/**
 * A widget on the face. [x] and [y] place its top-left as a fraction of the
 * room left around it; [sw] and [sh] stretch its width and height from the
 * kind's normal size (pinch to make it thinner or wider); [rot] turns it, in degrees.
 */
data class FaceWidget(
    val id: Long, val kind: String, val x: Float, val y: Float, val ref: Long = 0L,
    val sw: Float = 1f, val sh: Float = 1f, val rot: Float = 0f,
)

/** One Focus: its name, its icon's name and what it lets through (a NotificationManager interruption filter). */
data class FocusMode(val name: String, val glyph: String, val filter: Int)

/** Where the clock sits on the face: its centre as fractions of the face, its size and its turn. */
data class ClockPlace(val cx: Float = 0.5f, val cy: Float = 0.5f, val scale: Float = 1f, val rot: Float = 0f)

/** How far a widget can be stretched or squeezed on each side, and how big the clock can go. */
internal const val WIDGET_MIN_SCALE = 0.6f
internal const val WIDGET_MAX_SCALE = 2.6f
internal const val CLOCK_MIN_SCALE = 0.35f
internal const val CLOCK_MAX_SCALE = 1.6f

internal fun strokesToJson(ink: List<Stroke>): JSONArray = JSONArray().also { a ->
    ink.forEach { st ->
        val pts = JSONArray()
        st.pts.forEach { pts.put(Math.round(it * 10000) / 10000.0) }
        a.put(JSONObject().put("c", st.argb).put("w", st.width.toDouble()).put("s", st.style).put("p", pts))
    }
}

internal fun strokesFromJson(a: JSONArray?): List<Stroke> = if (a == null) emptyList() else List(a.length()) { i ->
    val o = a.getJSONObject(i)
    val p = o.getJSONArray("p")
    Stroke(o.getInt("c"), o.getDouble("w").toFloat(), o.optInt("s"), List(p.length()) { p.getDouble(it).toFloat() })
}

/**
 * Everything VoidOS remembers. Backed by SharedPreferences, exposed as Compose
 * state so the UI recomposes when a setting changes.
 */
class Store(private val ctx: Context) {
    private val p = ctx.getSharedPreferences("voidos", Context.MODE_PRIVATE)

    private fun b(key: String, def: Boolean) = mutableStateOf(p.getBoolean(key, def))

    var setupDone by mutableStateOf(p.getBoolean("setupDone", false)); private set
    var kiosk by mutableStateOf(p.getBoolean("kiosk", false)); private set
    var hideBars by mutableStateOf(p.getBoolean("hideBars", true)); private set
    var noLockScreen by mutableStateOf(p.getBoolean("noLockScreen", true)); private set
    var blockUpdates by mutableStateOf(p.getBoolean("blockUpdates", true)); private set
    var deskMode by mutableStateOf(p.getBoolean("deskMode", true)); private set
    var hideLenovo by mutableStateOf(p.getBoolean("hideLenovo", false)); private set
    var use24h by mutableStateOf(p.getBoolean("use24h", true)); private set
    var ambientMinutes by mutableStateOf(p.getInt("ambientMinutes", 5)); private set
    var allowed by mutableStateOf(p.getStringSet("allowed", emptySet())!!.toSet()); private set
    var city by mutableStateOf(p.getString("city", "Redditch")!!); private set
    var lat by mutableStateOf(p.getFloat("lat", 52.3065f)); private set
    var lon by mutableStateOf(p.getFloat("lon", -1.9457f)); private set
    var wallpaperDone by mutableStateOf(p.getBoolean("wallpaperDone", false)); private set

    // v2
    /** "auto" follows the tablet, "land" or "port" pins it. */
    var rotation by mutableStateOf(p.getString("rotation", "auto")!!); private set
    var voidLock by mutableStateOf(p.getBoolean("voidLock", true)); private set
    /** The VoidOS lock screen asks for the VoidOS PIN. On unless the user turns it off (new key, so it starts on). */
    var lockNeedsPin by mutableStateOf(p.getBoolean("pinLock", true)); private set
    var lockNotices by mutableStateOf(p.getBoolean("lockNotices", true)); private set
    var timerSound by mutableStateOf(p.getBoolean("timerSound", true)); private set
    var railLabels by mutableStateOf(p.getBoolean("railLabels", true)); private set
    var tempF by mutableStateOf(p.getBoolean("tempF", false)); private set
    var nightAuto by mutableStateOf(p.getBoolean("nightAuto", false)); private set
    var autoNight by mutableStateOf(p.getBoolean("autoNight", false)); private set
    var alarmVibrate by mutableStateOf(p.getBoolean("alarmVibrate", true)); private set
    var waveWake by mutableStateOf(p.getBoolean("waveWake", false)); private set
    var faceDown by mutableStateOf(p.getBoolean("faceDown", false)); private set
    var pickUp by mutableStateOf(p.getBoolean("pickUp", false)); private set
    var nightWarmth by mutableStateOf(p.getFloat("nightWarmth", 0.5f)); private set
    fun set(key: String, v: Float) { p.edit().putFloat(key, v).apply(); if (key == "nightWarmth") nightWarmth = v }
    /** Swipe left and right on a screen to move to the next one. */
    var swipeScreens by mutableStateOf(p.getBoolean("swipeScreens", true)); private set
    /** Settings > About > Edge touch debug: shows the edge zones and the last touch. */
    var edgeDebug by mutableStateOf(p.getBoolean("edgeDebug", false)); private set
    var edgeInApps by mutableStateOf(p.getBoolean("edgeInApps", true)); private set
    /** The slow moving light behind every screen. Off holds it still. */
    var motion by mutableStateOf(p.getBoolean("motion", true)); private set
    /** Apps opened from VoidOS, newest first: what the right-edge app wheel offers. */
    var recentApps by mutableStateOf(p.getString("recentApps", "")!!.split(',').filter { it.isNotBlank() }); private set
    var favs by mutableStateOf(p.getString("favs", "")!!.split(',').filter { it.isNotBlank() }); private set
    var hubName by mutableStateOf(p.getString("hubName", "Home")!!); private set
    /** How Android app icons are drawn: "colour" (as shipped), "void" (VoidOS glyph or tinted) or "letter". */
    var iconStyle by mutableStateOf(p.getString("iconStyle", "void")!!); private set

    /** The VoidOS PIN as "pbkdf2$<rounds>$<salt hex>$<hash hex>"; empty means still the starting code. */
    private var vpin: String = p.getString("voidPin", "")!!

    fun set(key: String, v: Boolean) {
        p.edit().putBoolean(key, v).apply()
        when (key) {
            "setupDone" -> setupDone = v
            "kiosk" -> kiosk = v
            "hideBars" -> hideBars = v
            "noLockScreen" -> noLockScreen = v
            "blockUpdates" -> blockUpdates = v
            "deskMode" -> deskMode = v
            "hideLenovo" -> hideLenovo = v
            "use24h" -> use24h = v
            "wallpaperDone" -> wallpaperDone = v
            "voidLock" -> voidLock = v
            "lockNeedsPin" -> { lockNeedsPin = v; p.edit().putBoolean("pinLock", v).apply() }
            "lockNotices" -> lockNotices = v
            "timerSound" -> timerSound = v
            "railLabels" -> railLabels = v
            "tempF" -> tempF = v
            "nightAuto" -> nightAuto = v
            "autoNight" -> autoNight = v
            "alarmVibrate" -> alarmVibrate = v
            "waveWake" -> waveWake = v
            "faceDown" -> faceDown = v
            "pickUp" -> pickUp = v
            "swipeScreens" -> swipeScreens = v
            "edgeDebug" -> { edgeDebug = v; com.andysocial.voidos.ui.EdgeSwipe.debug.value = v }
            "motion" -> { motion = v; com.andysocial.voidos.ui.Ambience.motion.value = v }
            "edgeInApps" -> edgeInApps = v
        }
    }

    // ---- world clocks: "name|zone id" lines
    var worldClocks by mutableStateOf(
        (p.getString("worldClocks", null) ?: "London|Europe/London\nNew York|America/New_York\nTokyo|Asia/Tokyo")
            .lines().mapNotNull { l -> l.split('|').takeIf { it.size == 2 }?.let { it[0] to it[1] } },
    ); private set
    private fun saveClocks(v: List<Pair<String, String>>) { worldClocks = v; p.edit().putString("worldClocks", v.joinToString("\n") { "${it.first}|${it.second}" }).apply() }
    fun addWorldClock(name: String, zone: String) { if (worldClocks.none { it.second == zone && it.first == name }) saveClocks(worldClocks + (name to zone)) }
    fun removeWorldClock(i: Int) { saveClocks(worldClocks.filterIndexed { k, _ -> k != i }) }

    // ---- Focus modes: "name|glyph|filter|allowAlarms" lines; filter 2 priority, 3 none, 4 alarms only
    var focusModes by mutableStateOf(
        (p.getString("focusModes", null) ?: "Do Not Disturb|focus_mode|4\nSleep|bed|3\nWork|work|2\nReading|book|4\nGaming|game|2")
            .lines().mapNotNull { l -> l.split('|').takeIf { it.size == 3 }?.let { FocusMode(it[0], it[1], it[2].toIntOrNull() ?: 4) } },
    ); private set
    /** The Focus that's on, by name; empty when none. */
    var focusOn by mutableStateOf(p.getString("focusOn", "")!!); private set
    fun setFocus(name: String) { focusOn = name; p.edit().putString("focusOn", name).apply() }
    fun saveFocusModes(v: List<FocusMode>) { focusModes = v; p.edit().putString("focusModes", v.joinToString("\n") { "${it.name}|${it.glyph}|${it.filter}" }).apply() }

    // ---- split view: the app open beside the clock on Home, or empty
    var splitApp by mutableStateOf(p.getString("splitApp", "")!!); private set
    fun setSplit(pkg: String) { splitApp = pkg; p.edit().putString("splitApp", pkg).apply() }

    @JvmName("pickRotation") fun setRotation(r: String) { rotation = r; p.edit().putString("rotation", r).apply() }
    @JvmName("pickIconStyle") fun setIconStyle(v: String) { iconStyle = v; p.edit().putString("iconStyle", v).apply() }
    @JvmName("pickHubName") fun setHubName(n: String) { hubName = n; p.edit().putString("hubName", n).apply() }

    /** Favourite apps, shown first in the drawer and on the face. Order kept. */
    fun toggleFav(pkg: String) {
        favs = if (pkg in favs) favs - pkg else (favs + pkg).take(8)
        p.edit().putString("favs", favs.joinToString(",")).apply()
    }

    fun noteRecent(pkg: String) {
        recentApps = (listOf(pkg) + recentApps.filter { it != pkg }).take(8)
        p.edit().putString("recentApps", recentApps.joinToString(",")).apply()
    }
    fun forgetRecent(pkg: String) {
        recentApps = recentApps.filter { it != pkg }
        p.edit().putString("recentApps", recentApps.joinToString(",")).apply()
    }

    fun setAmbient(min: Int) { ambientMinutes = min; p.edit().putInt("ambientMinutes", min).apply() }

    fun setAllowed(pkg: String, on: Boolean) {
        allowed = if (on) allowed + pkg else allowed - pkg
        p.edit().putStringSet("allowed", allowed).apply()
    }

    fun setPlace(name: String, la: Float, lo: Float) {
        city = name; lat = la; lon = lo
        p.edit().putString("city", name).putFloat("lat", la).putFloat("lon", lo).apply()
    }

    // ---- the VoidOS PIN: one code for the lock screen and the way out ----
    // Stored only as a salted PBKDF2 hash. Until the user picks their own it is
    // the starting code, whose fixed hash ships in the app (ESCAPE_HASH).
    /** There is always a PIN: the starting code until the user changes it. */
    val hasPin get() = true
    /** True once the user has picked their own PIN in Settings. */
    val pinChanged get() = vpin.isNotEmpty()
    private fun legacy(pin: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(("voidos:" + pin).toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun setPin(pin: String) {
        require(pin.length in 4..8 && pin.all { it.isDigit() }) { "PIN must be 4 to 8 digits" }
        vpin = PinHash.make(pin)
        p.edit().putString("voidPin", vpin).putInt("voidPinLen", pin.length).remove("pinHash").apply()
    }
    /** Back to the starting code. */
    fun resetPin() { vpin = ""; p.edit().remove("voidPin").apply() }
    fun pinLength(): Int = if (vpin.isEmpty()) 4 else p.getInt("voidPinLen", 4)
    fun checkPin(pin: String): Boolean = if (vpin.isEmpty()) legacy(pin) == ESCAPE_HASH else PinHash.check(pin, vpin)
    /** The way out of VoidOS asks for the same VoidOS PIN. */
    fun checkEscape(pin: String) = checkPin(pin)

    // ---- hidden-by-VoidOS bookkeeping ----
    fun hiddenByUs(): Set<String> = p.getStringSet("hiddenByUs", emptySet())!!.toSet()
    fun setHiddenByUs(s: Set<String>) { p.edit().putStringSet("hiddenByUs", s).apply() }

    // ---- notes, kept as a small JSON file ----
    private val notesFile = File(ctx.filesDir, "notes.json")
    var notes by mutableStateOf(loadNotes()); private set

    private fun loadNotes(): List<Note> = runCatching {
        if (!notesFile.exists()) return@runCatching emptyList<Note>()
        val a = JSONArray(notesFile.readText())
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Note(o.getLong("id"), o.getString("text"), o.optInt("colour", 0), o.optLong("updated"), strokesFromJson(o.optJSONArray("ink")))
        }
    }.getOrDefault(emptyList())

    fun saveNote(n: Note) {
        val rest = notes.filter { it.id != n.id }
        notes = listOf(n) + rest
        writeNotes()
    }

    fun deleteNote(id: Long) {
        notes = notes.filter { it.id != id }; writeNotes()
        if (widgets.any { it.kind == "note" && it.ref == id }) saveWidgets(widgets.filterNot { it.kind == "note" && it.ref == id })
    }

    // ---- face widgets: optional, draggable, saved with their place ----
    var widgets by mutableStateOf(loadWidgets()); private set

    private fun loadWidgets(): List<FaceWidget> {
        val raw = p.getString("widgets", null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            List(a.length()) { val o = a.getJSONObject(it); FaceWidget(
                o.getLong("id"), o.getString("k"), o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), o.optLong("r"),
                o.optDouble("sw", 1.0).toFloat(), o.optDouble("sh", 1.0).toFloat(), o.optDouble("rot", 0.0).toFloat(),
            ) }
        }.getOrDefault(emptyList())
    }

    fun saveWidgets(list: List<FaceWidget>) {
        widgets = list
        val a = JSONArray()
        list.forEach {
            a.put(
                JSONObject().put("id", it.id).put("k", it.kind).put("x", it.x.toDouble()).put("y", it.y.toDouble()).put("r", it.ref)
                    .put("sw", it.sw.toDouble()).put("sh", it.sh.toDouble()).put("rot", it.rot.toDouble()),
            )
        }
        p.edit().putString("widgets", a.toString()).apply()
    }

    fun addWidget(kind: String, ref: Long = 0L) {
        if (widgets.any { it.kind == kind && it.ref == ref }) return
        // new widgets go to the first free corner, then the middle of each side, so none lands on another or on the clock
        val slots = listOf(0f to 1f, 1f to 1f, 0f to 0f, 1f to 0.14f, 0f to 0.5f, 1f to 0.5f, 0.5f to 1f, 0.5f to 0f)
        val free = slots.firstOrNull { (x, y) -> widgets.none { Math.abs(it.x - x) < 0.2f && Math.abs(it.y - y) < 0.2f } } ?: (0.5f to 1f)
        saveWidgets(widgets + FaceWidget(System.currentTimeMillis() + widgets.size, kind, free.first, free.second, ref))
    }

    fun moveWidget(id: Long, x: Float, y: Float) = saveWidgets(widgets.map { if (it.id == id) it.copy(x = x.coerceIn(0f, 1f), y = y.coerceIn(0f, 1f)) else it })
    fun removeWidget(id: Long) = saveWidgets(widgets.filter { it.id != id })
    /** Saves a widget's place, stretch and turn after a pinch or a drag. */
    fun placeWidget(id: Long, x: Float, y: Float, sw: Float, sh: Float, rot: Float) = saveWidgets(
        widgets.map {
            if (it.id == id) it.copy(
                x = x.coerceIn(0f, 1f), y = y.coerceIn(0f, 1f),
                sw = sw.coerceIn(WIDGET_MIN_SCALE, WIDGET_MAX_SCALE), sh = sh.coerceIn(WIDGET_MIN_SCALE, WIDGET_MAX_SCALE),
                rot = normDeg(rot),
            ) else it
        },
    )

    // ---- the clock's place on the face (hold to move it, pinch to size and turn it) ----
    var clock by mutableStateOf(
        ClockPlace(p.getFloat("clockX", 0.5f), p.getFloat("clockY", 0.5f), p.getFloat("clockS", 1f), p.getFloat("clockR", 0f)),
    ); private set

    fun placeClock(c: ClockPlace) {
        val k = ClockPlace(c.cx.coerceIn(0f, 1f), c.cy.coerceIn(0f, 1f), c.scale.coerceIn(CLOCK_MIN_SCALE, CLOCK_MAX_SCALE), normDeg(c.rot))
        clock = k
        p.edit().putFloat("clockX", k.cx).putFloat("clockY", k.cy).putFloat("clockS", k.scale).putFloat("clockR", k.rot).apply()
    }
    fun resetClock() = placeClock(ClockPlace())

    // ---- weather: where the tablet is, and the last forecast it fetched ----
    /** True while the place comes from the tablet's location; false once a city is picked by hand. */
    var autoPlace by mutableStateOf(p.getBoolean("autoPlace", true)); private set
    fun chooseAutoPlace(v: Boolean) { autoPlace = v; p.edit().putBoolean("autoPlace", v).apply() }
    fun weatherCache(): String? = p.getString("wxCache", null)
    fun setWeatherCache(json: String) { p.edit().putString("wxCache", json).apply() }

    private fun writeNotes() {
        val a = JSONArray()
        notes.sortedByDescending { it.updated }.forEach {
            val o = JSONObject().put("id", it.id).put("text", it.text).put("colour", it.colour).put("updated", it.updated)
            if (it.ink.isNotEmpty()) o.put("ink", strokesToJson(it.ink))
            a.put(o)
        }
        runCatching {
            val tmp = File(notesFile.path + ".tmp")
            tmp.writeText(a.toString())
            tmp.renameTo(notesFile)
        }
    }

    // ------------------------------------------------------------ shopping list
    private val listFile = File(ctx.filesDir, "shopping.json")
    var shopping by mutableStateOf(loadList()); private set

    private fun loadList(): List<ShopItem> = runCatching {
        val a = JSONArray(listFile.readText())
        List(a.length()) { val o = a.getJSONObject(it); ShopItem(o.getLong("id"), o.getString("t"), o.optBoolean("d"), o.optString("aisle", "")) }
    }.getOrDefault(emptyList())

    fun addItem(text: String) {
        val t = text.trim().replaceFirstChar { it.uppercase() }
        if (t.isEmpty() || shopping.any { !it.done && it.text.equals(t, true) }) return
        shopping = listOf(ShopItem(System.nanoTime(), t, false, aisleFor(t))) + shopping
        writeList()
    }
    fun toggleItem(id: Long) { shopping = shopping.map { if (it.id == id) it.copy(done = !it.done) else it }; writeList() }
    fun removeItem(id: Long) { shopping = shopping.filter { it.id != id }; writeList() }
    fun clearDone() { shopping = shopping.filter { !it.done }; writeList() }

    private fun writeList() {
        val a = JSONArray()
        shopping.forEach { a.put(JSONObject().put("id", it.id).put("t", it.text).put("d", it.done).put("aisle", it.aisle)) }
        runCatching { val tmp = File(listFile.path + ".tmp"); tmp.writeText(a.toString()); tmp.renameTo(listFile) }
    }
}

data class ShopItem(val id: Long, val text: String, val done: Boolean, val aisle: String)

/** Rough supermarket sections so the list groups itself. */
/**
 * Rough supermarket aisle for a shopping item. Words match from their start
 * ("egg" matches "eggs", not "veggie"; "ham" doesn't match "shampoo"), and the
 * more specific groups are checked first ("butternut squash" is veg, "ice
 * cream" frozen, "orange juice" a drink).
 */
fun aisleFor(t: String): String {
    val s = t.lowercase()
    // a trailing ! means the whole word: "gin!" is gin, not ginger
    fun has(vararg w: String) = w.any {
        val whole = it.endsWith("!")
        Regex("(^|[^a-z])" + Regex.escape(it.removeSuffix("!")) + if (whole) "($|[^a-z])" else "").containsMatchIn(s)
    }
    return when {
        has("kitchen roll", "toilet", "bin bag", "washing", "bleach", "sponge", "foil", "cling", "soap", "shampoo", "toothpaste", "dishwasher", "fabric", "detergent", "tissue") -> "Household"
        has("cat food", "cat litter", "litter", "dog food", "dog!", "pet!", "pet food") -> "Pets"
        has("butternut", "squash!") && !has("orange squash", "lemon squash", "squash drink", "fruit squash") -> "Fruit & veg"
        has("ice cream", "frozen", "ice!", "ice cubes", "oven chips", "chips", "fish fingers", "peas") -> "Frozen"
        has("juice", "tea!", "teabag", "coffee", "water", "cola", "squash", "lemonade", "beer", "wine", "cider", "gin!") -> "Drinks"
        has("apple", "banana", "onion", "potato", "carrot", "tomato", "lettuce", "pepper", "garlic", "fruit", "veg", "lemon", "lime", "cucumber", "mushroom", "broccoli", "spinach", "avocado", "grape", "berr", "strawberr", "orange", "pear", "salad", "herb", "ginger", "courgette", "leek") -> "Fruit & veg"
        has("milk", "cheese", "cheddar", "mozzarella", "parmesan", "butter", "yog", "cream", "egg") -> "Dairy & eggs"
        has("bread", "loaf", "sourdough", "roll", "wrap", "bagel", "crumpet", "croissant", "pitta", "naan", "muffin") -> "Bakery"
        has("chicken", "beef", "pork", "mince", "bacon", "sausage", "ham", "duck", "fish", "salmon", "tuna", "prawn", "lamb", "turkey", "steak", "chorizo", "cod") -> "Meat & fish"
        has("pasta", "spaghetti", "rice", "noodle", "flour", "sugar", "oil", "sauce", "tin!", "tinned", "beans", "cereal", "oats", "spice", "salt!", "stock", "hoisin", "soy", "vinegar", "honey", "jam", "crisps", "biscuit", "chocolate", "lentil", "chickpea") -> "Cupboard"
        else -> "Other"
    }
}

/** Salted SHA-256 of the starting VoidOS PIN (the old fixed escape code). */
internal const val ESCAPE_HASH = "2d9c9466f67b138142547b1005b1ce01effe35337d374b6163cd3b3fdc210c90"

/** An angle folded into -180..180, snapped to straight when it is within 4 degrees of a quarter turn. */
internal fun normDeg(d: Float): Float {
    var a = ((d % 360f) + 540f) % 360f - 180f
    val q = Math.round(a / 90f) * 90f
    if (Math.abs(a - q) < 4f) a = q
    return if (a == -180f) 180f else a
}
