package com.andysocial.voidos.hub

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The tablet side of the hub. Reads the one summary the aggregator wrote, keeps the last good
 * copy on disk (so a failed refresh still shows the last numbers and "updated X min ago"), and
 * remembers what the user last saw for "Since you last looked".
 *
 * The only secret on the tablet is the hub device token, which the user makes themselves when
 * they deploy the aggregator (docs/hub-setup.md). It's encrypted with a key held in the Android
 * Keystore. Store and API keys (App Store Connect, AdMob, Brevo...) live only on the server.
 */
object HubRepo {
    private const val PREFS = "voidos_hub"
    private const val K_URL = "url"
    private const val K_TOKEN = "token"
    private const val K_LAST = "last"
    private const val K_SEEN = "seen"
    private const val K_REFRESH_AT = "refreshAt"
    private const val K_SITES = "sites"
    private const val KEY_ALIAS = "voidos_hub_token"

    var summary by mutableStateOf<HubSummary?>(null); private set
    var serverUrl by mutableStateOf(""); private set
    var hasToken by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    /** The last refresh's problem, in words, or null when it worked. */
    var error by mutableStateOf<String?>(null); private set
    /** Lines for the wake card; cleared when the user dismisses it. */
    var wakeLines by mutableStateOf<List<String>>(emptyList()); private set
    /** Apps that went live since the last refresh: the standby ring flashes while this is set. */
    var justLive by mutableStateOf<List<String>>(emptyList())
    /** Sites the user added by hand in Settings › Hub, sent to the aggregator for checking. */
    var manualSites by mutableStateOf<List<Pair<String, String>>>(emptyList()); private set

    private var prefs: SharedPreferences? = null
    private val io = Executors.newSingleThreadExecutor()
    private var lastRefresh = 0L

    /** True once a server and its token are saved. Until then every hub screen shows its empty state. */
    val connected: Boolean get() = serverUrl.isNotBlank() && hasToken

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        p.edit().remove("mode").apply() // the 2.0 alpha's Off / Demo / Server switch is gone
        serverUrl = p.getString(K_URL, "") ?: ""
        hasToken = !p.getString(K_TOKEN, null).isNullOrEmpty()
        lastRefresh = p.getLong(K_REFRESH_AT, 0L)
        manualSites = decodeSites(p.getString(K_SITES, "") ?: "")
        // only a summary the server sent counts: one saved by the alpha's demo mode is dropped
        summary = if (connected) p.getString(K_LAST, null)?.let { runCatching { Hub.parse(it) }.getOrNull() }?.takeIf { !it.demo } else null
    }

    /** For tests only: show this summary without touching disk or network. */
    internal fun preview(s: HubSummary?, server: String = "", token: Boolean = false) { summary = s; serverUrl = server; hasToken = token }

    fun setServer(ctx: Context, url: String, token: String?) {
        init(ctx)
        serverUrl = url.trim().trimEnd('/')
        val e = prefs!!.edit().putString(K_URL, serverUrl)
        if (token != null) {
            if (token.isBlank()) e.remove(K_TOKEN) else e.putString(K_TOKEN, encrypt(token.trim()))
            hasToken = token.isNotBlank()
        }
        e.apply()
        error = null
        if (!connected) { summary = null; prefs?.edit()?.remove(K_LAST)?.remove(K_SEEN)?.apply() }
        else refresh(ctx, force = true)
    }

    fun setSites(ctx: Context, sites: List<Pair<String, String>>) {
        init(ctx)
        manualSites = sites
        prefs?.edit()?.putString(K_SITES, encodeSites(sites))?.apply()
    }

    fun dismissWake() { wakeLines = emptyList() }

    /** The screen came on: refresh unless the last one was under ten minutes ago, then show what changed. */
    fun onScreenOn(ctx: Context) {
        init(ctx)
        refresh(ctx, force = false, wake = true)
    }

    fun refresh(ctx: Context, force: Boolean = true, wake: Boolean = false) {
        init(ctx)
        if (!connected || busy) return
        val now = System.currentTimeMillis()
        if (!force && !Hub.shouldRefresh(lastRefresh, now)) {
            if (wake) showSince()
            return
        }
        busy = true
        val url = serverUrl
        val token = prefs?.getString(K_TOKEN, null)?.let { decrypt(it) }
        io.execute {
            val result = runCatching { fetch(url, token, ask = true) }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                busy = false
                result.onSuccess { (json, s) ->
                    lastRefresh = System.currentTimeMillis()
                    val before = summary
                    summary = s
                    error = null
                    val live = s.wentLive(before).map { it.name }
                    if (live.isNotEmpty()) justLive = live
                    prefs?.edit()?.putString(K_LAST, json)?.putLong(K_REFRESH_AT, lastRefresh)?.apply()
                    if (wake) showSince()
                }.onFailure {
                    error = when (it) {
                        is java.net.UnknownHostException -> "No internet connection"
                        is java.net.SocketTimeoutException -> "The hub server didn't answer in time"
                        is SecurityException -> "The hub server didn't accept this tablet's token"
                        else -> "Couldn't reach the hub server"
                    }
                }
            }
        }
    }

    private fun showSince() {
        val now = summary ?: return
        val seen = prefs?.getString(K_SEEN, null)?.let { runCatching { Hub.parse(it) }.getOrNull() }
        val lines = Hub.sinceLastLooked(seen, now)
        if (lines.isNotEmpty()) wakeLines = lines
        prefs?.getString(K_LAST, null)?.let { prefs?.edit()?.putString(K_SEEN, it)?.apply() }
    }

    /**
     * GET {server}/summary returns the Firestore summary. With ask, the server is told the screen
     * came on, and it refreshes the sources first unless it did so under ten minutes ago.
     */
    private fun fetch(base: String, token: String?, ask: Boolean): Pair<String, HubSummary> {
        val c = URL("$base/summary" + if (ask) "?wake=1" else "").openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 45_000
        c.setRequestProperty("Authorization", "Bearer ${token.orEmpty()}")
        // the sites you listed in Settings › Hub; the server checks these unless its own config lists some
        sitesHeader(manualSites)?.let { c.setRequestProperty("X-Hub-Sites", it) }
        c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode == 401 || c.responseCode == 403) throw SecurityException("token")
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            val body = c.inputStream.bufferedReader().readText()
            return body to Hub.parse(body)
        } finally { c.disconnect() }
    }

    /**
     * Reply to a store review (phase 2). The server posts it to App Store Connect with its own key.
     * [done] runs on the main thread with null on success, else the problem in words.
     */
    fun reply(ctx: Context, review: StoreReview, text: String, done: (String?) -> Unit) {
        init(ctx)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        if (!connected) { main.post { done("Set up the hub in Settings › Hub first") }; return }
        val url = serverUrl
        val token = prefs?.getString(K_TOKEN, null)?.let { decrypt(it) }
        io.execute {
            val r = runCatching {
                val c = URL("$url/reply").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true
                c.connectTimeout = 8000; c.readTimeout = 30_000
                c.setRequestProperty("Authorization", "Bearer ${token.orEmpty()}")
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(JSONObject().put("reviewId", review.id).put("appId", review.appId).put("text", text).toString().toByteArray()) }
                val code = c.responseCode
                c.disconnect()
                if (code !in 200..299) throw java.io.IOException("HTTP $code")
            }
            main.post {
                if (r.isSuccess) summary = summary?.let { s -> s.copy(reviews = s.reviews.map { if (it.id == review.id) it.copy(reply = text) else it }) }
                done(r.exceptionOrNull()?.let { "Couldn't send the reply" })
            }
        }
    }

    /** The sites with an address, as one header value: name|url pairs joined by tabs, URL-encoded. */
    internal fun sitesHeader(sites: List<Pair<String, String>>): String? {
        val ok = sites.filter { (n, u) -> n.isNotBlank() && (u.startsWith("https://") || u.startsWith("http://")) }
        if (ok.isEmpty()) return null
        return java.net.URLEncoder.encode(ok.joinToString("\t") { (n, u) -> "${n.replace("|", " ").replace("\t", " ")}|$u" }, "UTF-8")
    }

    // ---- sites list: "name|url" lines

    fun encodeSites(s: List<Pair<String, String>>): String = s.joinToString("\n") { "${it.first.replace("|", " ")}|${it.second}" }
    fun decodeSites(s: String): List<Pair<String, String>> = s.lines().mapNotNull { l ->
        val i = l.indexOf('|'); if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim()
    }

    // ---- Keystore-backed encryption for the device token

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }

    private fun encrypt(plain: String): String = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        "k1:" + Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }.getOrElse { "p1:" + Base64.encodeToString(plain.toByteArray(), Base64.NO_WRAP) } // no Keystore (tests): app-private prefs only

    private fun decrypt(s: String): String? = runCatching {
        val parts = s.split(':')
        when (parts[0]) {
            "k1" -> Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)))
                String(doFinal(Base64.decode(parts[2], Base64.NO_WRAP)))
            }
            "p1" -> String(Base64.decode(parts[1], Base64.NO_WRAP))
            else -> null
        }
    }.getOrNull()
}
