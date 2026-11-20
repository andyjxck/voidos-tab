package com.andysocial.voidos.hub

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.andysocial.voidos.MainActivity
import com.andysocial.voidos.Store
import com.andysocial.voidos.ui.G
import com.andysocial.voidos.ui.installed
import com.andysocial.voidos.ui.launchMsg

/**
 * VoidOS 2.0's Dev Apps: the only things that launch from VoidOS. Each entry is the native app
 * when it's installed, else a browser shortcut to the web console. The list is plain text in
 * the Store ("Name|package|url" a line) so it's easy to edit in Settings › Dev Apps, or by hand.
 */
data class DevApp(val name: String, val pkg: String = "", val url: String = "") {
    val key: String get() = pkg.ifBlank { url }
    fun line(): String = "${name.replace("|", " ")}|$pkg|$url"
}

object DevApps {
    /**
     * The starting list. Package names are the Play Store ids as far as known; when one isn't
     * installed (or the id is wrong) VoidOS falls back to the web console, so a miss costs nothing.
     */
    val defaults = listOf(
        DevApp("App Store Connect", "", "https://appstoreconnect.apple.com"),
        DevApp("AdMob", "com.google.android.apps.admob", "https://apps.admob.com"),
        DevApp("Firebase", "", "https://console.firebase.google.com"),
        DevApp("Google Cloud", "com.google.android.apps.cloudconsole", "https://console.cloud.google.com"),
        DevApp("Brevo", "", "https://app.brevo.com"),
        DevApp("Fasthosts", "", "https://admin.fasthosts.co.uk"),
        DevApp("Cloudflare", "", "https://dash.cloudflare.com"),
        DevApp("Adsterra", "", "https://beta.publishers.adsterra.com"),
        DevApp("GitHub", "com.github.android", "https://github.com"),
        DevApp("Browser", "", "https://www.google.com"),
        DevApp("Magisk", "com.topjohnwu.magisk", ""),
        DevApp("Android Settings", "com.android.settings", ""),
    )

    /** 2.0.1: always on the list, also on a list saved before they were defaults. */
    private val pinned = defaults.takeLast(2)

    fun encode(list: List<DevApp>): String = list.joinToString("\n") { it.line() }

    fun decode(s: String): List<DevApp> = s.lines().mapNotNull { l ->
        val p = l.split('|')
        if (p.size < 3 || p[0].isBlank() || (p[1].isBlank() && p[2].isBlank())) null
        else DevApp(p[0].trim(), p[1].trim(), p[2].trim())
    }

    fun list(store: Store): List<DevApp> {
        val saved = store.devApps.takeIf { it.isNotBlank() }?.let { decode(it) } ?: return defaults
        return saved + pinned.filter { p -> saved.none { it.pkg == p.pkg } }
    }

    /** Pure: a typed "name" and "package or web address" as an entry, or null when it isn't usable. */
    fun parseEntry(name: String, target: String): DevApp? {
        val n = name.trim(); val t = target.trim()
        if (n.isEmpty() || t.isEmpty() || '|' in t) return null
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> DevApp(n, "", t)
            looksLikeHost(t) -> DevApp(n, "", "https://$t")
            Regex("^[a-zA-Z]\\w*(\\.[a-zA-Z]\\w*)+$").matches(t) -> DevApp(n, t, "")
            else -> null
        }
    }

    private val tlds = setOf("com", "co", "uk", "io", "dev", "net", "org", "app", "cloud")
    private fun looksLikeHost(t: String): Boolean {
        if (' ' in t || t.startsWith("com.") || t.startsWith("org.") || t.startsWith("uk.")) return false
        val host = t.substringBefore('/')
        return host.contains('.') && host.substringAfterLast('.').lowercase() in tlds
    }

    /** The browser apps on the tablet. */
    fun browsers(ctx: Context): Set<String> = runCatching {
        ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), 0)
            .map { it.activityInfo.packageName }.toSet()
    }.getOrDefault(emptySet())

    /** The packages VoidOS may launch: the Dev Apps that are installed, and the browser. Everything else stays hidden. */
    fun allowedPackages(ctx: Context, store: Store): Set<String> =
        list(store).map { it.pkg }.filter { it.isNotBlank() }.toSet() + browsers(ctx)

    fun native(ctx: Context, a: DevApp): Boolean = a.pkg.isNotBlank() && installed(ctx, a.pkg)

    fun glyph(a: DevApp): G = when {
        a.name.contains("store", true) || a.url.contains("apple") -> G.STORE
        a.name.contains("admob", true) || a.name.contains("adsterra", true) -> G.ADS
        a.name.contains("firebase", true) -> G.CLOUD_UP
        a.name.contains("cloud", true) -> G.DNS
        a.name.contains("brevo", true) -> G.MAIL
        a.name.contains("github", true) -> G.CODE
        a.name.contains("fasthosts", true) -> G.WEB
        a.name.contains("browser", true) -> G.GLOBE
        a.pkg == "com.topjohnwu.magisk" -> G.KEY
        a.pkg == "com.android.settings" -> G.SYSTEM
        else -> G.WEB
    }

    /** Opens [url] in the browser, letting the browser through VoidOS's lock-task rules first. */
    fun openUrl(ctx: Context, store: Store, url: String): Boolean {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val target = i.resolveActivity(ctx.packageManager)?.packageName
            ?: browsers(ctx).firstOrNull()?.also { i.setPackage(it) }
        if (target == null) { launchMsg.value = "No browser is installed."; return false }
        allow(ctx, store, target)
        return runCatching { ctx.startActivity(i); store.noteRecent(target); true }
            .getOrElse { launchMsg.value = "Android blocked the browser from opening."; false }
    }

    fun launch(ctx: Context, store: Store, a: DevApp): Boolean {
        if (native(ctx, a)) {
            allow(ctx, store, a.pkg)
            val i = ctx.packageManager.getLaunchIntentForPackage(a.pkg) ?: return false
            return runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); store.noteRecent(a.pkg); true }
                .getOrElse { launchMsg.value = "Android blocked ${a.name} from opening."; false }
        }
        if (a.url.isBlank()) { launchMsg.value = "${a.name} isn't installed."; return false }
        return openUrl(ctx, store, a.url)
    }

    /** Lock mode only lets through what's on its list: add a Dev App the first time it opens. */
    private fun allow(ctx: Context, store: Store, pkg: String) {
        if (pkg !in store.allowed) {
            store.setAllowed(pkg, true)
            (ctx as? MainActivity)?.applyPolicies()
        }
    }
}
