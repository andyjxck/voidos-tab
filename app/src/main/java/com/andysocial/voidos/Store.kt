package com.andysocial.voidos

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class Note(val id: Long, val text: String, val colour: Int, val updated: Long)

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
    var lockNeedsPin by mutableStateOf(p.getBoolean("lockNeedsPin", false)); private set
    var lockNotices by mutableStateOf(p.getBoolean("lockNotices", true)); private set
    var timerSound by mutableStateOf(p.getBoolean("timerSound", true)); private set
    var railLabels by mutableStateOf(p.getBoolean("railLabels", true)); private set
    var tempF by mutableStateOf(p.getBoolean("tempF", false)); private set
    var favs by mutableStateOf(p.getString("favs", "")!!.split(',').filter { it.isNotBlank() }); private set
    var hubName by mutableStateOf(p.getString("hubName", "Kitchen")!!); private set

    private var pinHash: String = p.getString("pinHash", "")!!

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
            "lockNeedsPin" -> lockNeedsPin = v
            "lockNotices" -> lockNotices = v
            "timerSound" -> timerSound = v
            "railLabels" -> railLabels = v
            "tempF" -> tempF = v
        }
    }

    fun setRotation(r: String) { rotation = r; p.edit().putString("rotation", r).apply() }
    fun setHubName(n: String) { hubName = n; p.edit().putString("hubName", n).apply() }

    /** Favourite apps, shown first in the drawer and on the face. Order kept. */
    fun toggleFav(pkg: String) {
        favs = if (pkg in favs) favs - pkg else (favs + pkg).take(8)
        p.edit().putString("favs", favs.joinToString(",")).apply()
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

    // ---- escape PIN (stored as a salted hash, never in plain text) ----
    val hasPin get() = pinHash.isNotEmpty()
    private fun hash(pin: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(("voidos:" + pin).toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun setPin(pin: String) { pinHash = hash(pin); p.edit().putString("pinHash", pinHash).apply() }
    fun checkPin(pin: String) = hasPin && hash(pin) == pinHash

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
            Note(o.getLong("id"), o.getString("text"), o.optInt("colour", 0), o.optLong("updated"))
        }
    }.getOrDefault(emptyList())

    fun saveNote(n: Note) {
        val rest = notes.filter { it.id != n.id }
        notes = listOf(n) + rest
        writeNotes()
    }

    fun deleteNote(id: Long) { notes = notes.filter { it.id != id }; writeNotes() }

    private fun writeNotes() {
        val a = JSONArray()
        notes.sortedByDescending { it.updated }.forEach {
            a.put(JSONObject().put("id", it.id).put("text", it.text).put("colour", it.colour).put("updated", it.updated))
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
fun aisleFor(t: String): String {
    val s = t.lowercase()
    fun has(vararg w: String) = w.any { s.contains(it) }
    return when {
        has("milk", "cheese", "butter", "yog", "cream", "egg") -> "Dairy & eggs"
        has("bread", "roll", "wrap", "bagel", "crumpet", "croissant") -> "Bakery"
        has("apple", "banana", "onion", "potato", "carrot", "tomato", "lettuce", "pepper", "garlic", "fruit", "veg", "lemon", "lime", "cucumber", "spring onion", "mushroom") -> "Fruit & veg"
        has("chicken", "beef", "pork", "mince", "bacon", "sausage", "ham", "duck", "fish", "salmon", "tuna", "prawn") -> "Meat & fish"
        has("pasta", "rice", "noodle", "flour", "sugar", "oil", "sauce", "tin", "beans", "cereal", "oats", "spice", "salt", "stock", "hoisin", "soy") -> "Cupboard"
        has("frozen", "ice", "chips", "pizza") -> "Frozen"
        has("tea", "coffee", "juice", "water", "cola", "squash", "beer", "wine") -> "Drinks"
        has("cat", "litter", "dog", "pet") -> "Pets"
        has("soap", "shampoo", "toilet", "kitchen roll", "bin bag", "washing", "bleach", "sponge", "foil", "cling") -> "Household"
        else -> "Other"
    }
}
