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
        }
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
}
