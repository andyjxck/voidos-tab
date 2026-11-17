package com.andysocial.voidos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.alpha
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Telephony
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.andysocial.voidos.MainActivity
import com.andysocial.voidos.Store
import com.andysocial.voidos.VoidApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(val pkg: String, val label: String)

/** Small icon cache: icons are drawn at 96 px so 2 GB of RAM isn't wasted on them. */
object Icons {
    private val cache = object : android.util.LruCache<String, ImageBitmap>(120) {}
    suspend fun get(ctx: Context, pkg: String): ImageBitmap? {
        cache.get(pkg)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { evenIcon(ctx.packageManager.getApplicationIcon(pkg).toBitmap(128, 128)).asImageBitmap() }
                .getOrNull()?.also { cache.put(pkg, it) }
        }
    }
}

/**
 * Old-style icons come with their own blank margin, different for every app,
 * so they sit smaller than the rest. This crops each icon to what's actually
 * drawn and centres it in a 96 px square with the same small margin.
 */
internal fun evenIcon(src: android.graphics.Bitmap): android.graphics.Bitmap {
    val w = src.width; val h = src.height
    val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
    var l = w; var t = h; var r = -1; var b = -1
    for (y in 0 until h) for (x in 0 until w) if ((px[y * w + x] ushr 24) > 24) {
        if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
    }
    val out = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
    if (r < l || b < t) return out
    val bw = r - l + 1; val bh = b - t + 1
    val k = 92f / maxOf(bw, bh)
    val dw = bw * k; val dh = bh * k
    val dst = android.graphics.RectF((96 - dw) / 2, (96 - dh) / 2, (96 + dw) / 2, (96 + dh) / 2)
    android.graphics.Canvas(out).drawBitmap(src, android.graphics.Rect(l, t, r + 1, b + 1), dst, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
    return out
}

/** Why an app won't open, if Android or lock mode would stop it; null when it opens. */
fun blockReason(ctx: Context, store: Store, pkg: String): String? {
    val pm = ctx.packageManager
    val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return "not installed"
    if (!ai.enabled) return "turned off in Android"
    if (runCatching { pm.isPackageSuspended(pkg) }.getOrDefault(false)) return "paused by Android"
    if (store.kiosk && pkg !in store.allowed) return "blocked by lock mode"
    return null
}

object AppsRepo {
    private var cached: List<AppEntry>? = null
    fun all(ctx: Context, fresh: Boolean = false): List<AppEntry> {
        if (!fresh) cached?.let { return it }
        return launchableApps(ctx).also { cached = it }
    }
    fun find(ctx: Context, pkg: String): AppEntry? = all(ctx).firstOrNull { it.pkg == pkg }
        ?: runCatching { AppEntry(pkg, ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()) }.getOrNull()
}

fun launchableApps(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { it.activityInfo.packageName }
        .distinct()
        .filter { it != ctx.packageName }
        .map { p -> AppEntry(p, runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)) }
        .sortedBy { it.label.lowercase() }
}

/** Last launch problem, shown by the drawer. */
val launchMsg = mutableStateOf<String?>(null)
/** The app that was just stopped, so the drawer can offer to allow it. */
val blocked = mutableStateOf<String?>(null)

fun launchApp(ctx: Context, store: Store, pkg: String): Boolean {
    val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
    if (i == null) { launchMsg.value = "That app can't be opened."; return false }
    return startSafely(ctx, store, i, pkg)
}

private fun startSafely(ctx: Context, store: Store, i: Intent, pkg: String?): Boolean {
    if (pkg != null) blockReason(ctx, store, pkg)?.let { why ->
        val name = AppsRepo.find(ctx, pkg)?.label ?: "That app"
        launchMsg.value = if (why == "blocked by lock mode") "$name is blocked by lock mode. Tap Manage to allow it." else "$name is $why."
        blocked.value = pkg
        return false
    }
    return runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); launchMsg.value = null; true }
        .getOrElse { launchMsg.value = "Android blocked that app from opening."; false }
}

/**
 * The big shortcuts: each finds whatever app on the tablet does that job, so
 * "Message" opens WhatsApp, Messenger, Google Messages... whichever is there.
 */
enum class Quick(val label: String, val glyph: G, val accent: Color) {
    WATCH("Watch", G.VIDEO, V.Coral), MESSAGE("Message", G.MESSAGE, V.Teal), BROWSE("Browse", G.GLOBE, V.Purple),
    MUSIC("Music", G.MEDIA, V.Green), CAMERA("Camera", G.CAMERA, V.Yellow), PHOTOS("Photos", G.STAR, V.Orange),
}

private val messagingPkgs = listOf(
    "com.whatsapp", "com.google.android.apps.messaging", "com.facebook.orca", "org.telegram.messenger", "com.snapchat.android",
    "com.discord", "org.thoughtcrime.securesms", "com.instagram.android", "com.microsoft.teams", "com.skype.raider",
)
private val watchPkgs = listOf("com.google.android.youtube", "com.google.android.apps.youtube.kids", "com.netflix.mediaclient", "com.amazon.avod.thirdpartyclient", "com.disney.disneyplus", "bbc.iplayer.android")
private val musicPkgs = listOf("com.spotify.music", "com.google.android.apps.youtube.music", "com.amazon.mp3", "deezer.android.app", "com.apple.android.music")

fun installed(ctx: Context, pkg: String) = ctx.packageManager.getLaunchIntentForPackage(pkg) != null

/** The packages that can do this job, best first. */
fun quickTargets(ctx: Context, q: Quick): List<String> {
    val pm = ctx.packageManager
    fun resolveAll(i: Intent) = pm.queryIntentActivities(i, 0).map { it.activityInfo.packageName }.distinct()
    return when (q) {
        Quick.WATCH -> watchPkgs.filter { installed(ctx, it) }
        Quick.MESSAGE -> (listOfNotNull(runCatching { Telephony.Sms.getDefaultSmsPackage(ctx) }.getOrNull()) + messagingPkgs)
            .distinct().filter { installed(ctx, it) } + resolveAll(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING))
        Quick.BROWSE -> resolveAll(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")))
        Quick.MUSIC -> musicPkgs.filter { installed(ctx, it) } + resolveAll(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC))
        Quick.CAMERA -> resolveAll(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        Quick.PHOTOS -> resolveAll(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY))
    }.distinct().filter { it != ctx.packageName && installed(ctx, it) }
}

fun runQuick(ctx: Context, store: Store, q: Quick) {
    val targets = quickTargets(ctx, q)
    // prefer an app that will actually open
    val ok = targets.firstOrNull { blockReason(ctx, store, it) == null }
    if (ok != null || targets.isNotEmpty()) { launchApp(ctx, store, ok ?: targets.first()); return }
    // nothing installed for it: fall back to the web where that makes sense
    when (q) {
        Quick.WATCH -> startSafely(ctx, store, Intent(Intent.ACTION_VIEW, Uri.parse("https://m.youtube.com")), null)
        Quick.MUSIC -> startSafely(ctx, store, Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com")), null)
        else -> launchMsg.value = "No ${q.label.lowercase()} app is installed. Get one from the Play Store."
    }
}

@Composable
fun AppIcon(app: AppEntry, size: Dp, onClick: () -> Unit) {
    Box(Modifier.size(size).tap(onClick), contentAlignment = Alignment.Center) {
        AppGlyph(app, VoidApp.instance.store.iconStyle, size * 0.9f)
    }
}

// ------------------------------------------------------------- custom icons

/** Apps that get a hand-drawn VoidOS glyph in the "void" icon style. */
private val glyphFor: List<Pair<Regex, G>> = listOf(
    Regex("camera") to G.CAMERA,
    Regex("youtube|netflix|primevideo|disney|iplayer|tvplayer|video|plex|twitch") to G.VIDEO,
    Regex("spotify|music|sounds|podcast|radio|audible|deezer|tidal|soundcloud") to G.MEDIA,
    Regex("whatsapp|messag|mms|telegram|signal|discord|snapchat|messenger") to G.MESSAGE,
    Regex("dialer|\\.phone|contacts") to G.PHONE,
    Regex("chrome|browser|firefox|opera|duckduckgo|edge|\\.web") to G.GLOBE,
    Regex("weather") to G.WEATHER,
    Regex("deskclock|\\.clock|alarm|timer") to G.TIMER,
    Regex("calendar") to G.LIST,
    Regex("keep|notes|docs|evernote|onenote") to G.NOTES,
    Regex("settings") to G.SYSTEM,
    Regex("recipe|food|deliveroo|ubereats|justeat|tesco|sainsbury|asda|ocado") to G.CUTLERY,
    Regex("home|nest|hue|smartthings|alexa|ring") to G.HOUSE,
    Regex("gallery|photos") to G.CAMERA,
)

fun glyphForPackage(pkg: String): G? = glyphFor.firstOrNull { it.first.containsMatchIn(pkg.lowercase()) }?.second

/** A steady accent per app, so the same app keeps its colour everywhere. */
fun accentFor(pkg: String): Color {
    val palette = listOf(V.Teal, V.Coral, V.Yellow, V.Green, V.Purple, V.Orange, V.Pink, V.Cyan)
    return palette[Math.floorMod(pkg.hashCode(), palette.size)]
}

/** Up to two letters for the "letter" style: "BBC Sounds" -> "BS", "Spotify" -> "SP". */
fun initialsFor(label: String): String {
    val words = label.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2)
        else -> "${words[0].first()}${words[1].first()}"
    }.uppercase()
}

private val greyscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * One Android app's icon in the chosen style: "colour" is the app's own icon,
 * "void" a VoidOS glyph for apps it knows and a greyed icon in an octagon for
 * the rest, "letter" the app's initials in an octagon.
 */
@Composable
fun AppGlyph(app: AppEntry, style: String, size: Dp) {
    val ctx = LocalContext.current
    val accent = accentFor(app.pkg)
    val glyph = if (style == "void") glyphForPackage(app.pkg) else null
    when {
        style == "letter" -> Box(
            Modifier.size(size).clip(OctS).background(accent.copy(alpha = 0.12f)).border(1.5.dp, accent, OctS),
            contentAlignment = Alignment.Center,
        ) { VText(initialsFor(app.label), size = (size.value * 0.32f).sp, color = accent, font = Kenney, maxLines = 1) }
        glyph != null -> Box(
            Modifier.size(size).clip(OctS).background(accent.copy(alpha = 0.12f)).border(1.5.dp, accent, OctS),
            contentAlignment = Alignment.Center,
        ) { Glyph(glyph, accent, Modifier.size(size * 0.58f)) }
        else -> {
            var icon by remember(app.pkg) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(app.pkg) { icon = Icons.get(ctx, app.pkg) }
            if (style == "void") Box(
                Modifier.size(size).clip(OctS).background(accent.copy(alpha = 0.12f)).border(1.5.dp, accent, OctS),
                contentAlignment = Alignment.Center,
            ) { icon?.let { Image(it, app.label, Modifier.size(size * 0.66f), colorFilter = greyscale, alpha = 0.9f) } }
            else Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                icon?.let { Image(it, app.label, Modifier.size(size)) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppsScreen(store: Store) {
    val ctx = LocalContext.current
    val portrait = LocalPortrait.current
    var managing by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var showBlocked by remember { mutableStateOf(false) }
    var all by remember { mutableStateOf(AppsRepo.all(ctx)) }
    LaunchedEffect(refresh) { all = withContext(Dispatchers.IO) { AppsRepo.all(ctx, fresh = true) } }
    val q = query.trim()
    val match = if (q.isEmpty()) all else all.filter { it.label.contains(q, ignoreCase = true) }
    // what opens and what doesn't, worked out once per list
    val reasons = remember(all, store.kiosk, store.allowed, refresh) { all.associate { it.pkg to blockReason(ctx, store, it.pkg) } }
    val open = match.filter { reasons[it.pkg] == null }
    val shut = match.filter { reasons[it.pkg] != null }
    val favs = store.favs.mapNotNull { f -> open.firstOrNull { it.pkg == f } }
    val msg by launchMsg
    val stopped by blocked

    LaunchedEffect(managing) { if (!managing) refresh++ }

    Column(Modifier.fillMaxSize()) {
        // header: search + manage, nothing else
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Glyph(G.SEARCH, V.Muted, Modifier.size(22.dp))
                Gap(w = 10.dp)
                VField(query, { query = it }, Modifier.weight(1f), placeholder = "Search ${open.size} apps")
            }
            Gap(w = 12.dp)
            VButton(if (managing) "Done" else "Manage", accent = V.Purple, filled = managing) {
                if (managing) (ctx as? MainActivity)?.applyPolicies()
                managing = !managing
            }
        }
        if (managing) {
            Gap(h = 8.dp)
            VText(
                if (store.kiosk) "Lock mode is on. Tap an app to allow or block it. Long-press to star it for the face."
                else "Lock mode is off, so every app opens. Tap apps to choose what lock mode will let through. Long-press to star.",
                size = 12.sp, color = V.Muted, font = FontFamily.Default,
            )
        }
        // a blocked launch says so, and offers the fix right there
        msg?.let { m ->
            Gap(h = 8.dp)
            Row(
                Modifier.fillMaxWidth().clip(OctS).background(V.Coral.copy(alpha = 0.1f)).border(1.dp, V.Coral.copy(alpha = 0.6f), OctS).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(G.LOCK, V.Coral, Modifier.size(18.dp)); Gap(w = 10.dp)
                VText(m, size = 13.sp, color = V.Text, font = FontFamily.Default, modifier = Modifier.weight(1f))
                if (stopped != null && reasons[stopped] == "blocked by lock mode") {
                    VButton("Manage", accent = V.Purple) { managing = true; launchMsg.value = null }
                    Gap(w = 6.dp)
                }
                IconTap(G.CLOSE, V.Muted, 36.dp) { launchMsg.value = null; blocked.value = null }
            }
        }
        Gap(h = 12.dp)

        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (portrait) 96.dp else 104.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            if (managing) {
                // managing: every app, allowed ones first
                val sorted = match.sortedBy { if (it.pkg in store.allowed) 0 else 1 }
                items(sorted, key = { "m_" + it.pkg }) { app -> AppTile(app, store, managing = true, starred = app.pkg in store.favs, why = null) }
                return@LazyVerticalGrid
            }
            if (q.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Quick.entries.forEach { qk -> QuickTile(qk) { runQuick(ctx, store, qk) } }
                    }
                }
                if (favs.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { Label("Starred", modifier = Modifier.padding(top = 10.dp, start = 4.dp)) }
                    items(favs, key = { "f_" + it.pkg }) { app -> AppTile(app, store, false, starred = true, why = null) }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Label("Apps", modifier = Modifier.padding(top = 10.dp, start = 4.dp)) }
            }
            if (open.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    VText(if (q.isNotEmpty()) "NO APP MATCHES" else "NOTHING ALLOWED YET. TAP MANAGE.", size = 13.sp, color = V.Muted,
                        align = TextAlign.Center, spacing = 1.sp, modifier = Modifier.padding(vertical = 30.dp).fillMaxWidth())
                }
            }
            items(open, key = { it.pkg }) { app -> AppTile(app, store, false, starred = app.pkg in store.favs, why = null) }
            // apps that won't open: counted, folded away, and honest about why
            if (shut.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp).clip(OctS).clickable { showBlocked = !showBlocked }.padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Glyph(G.LOCK, V.Muted, Modifier.size(16.dp)); Gap(w = 8.dp)
                        Label("${shut.size} won't open${if (store.kiosk) " in lock mode" else ""}", Modifier.weight(1f))
                        VText(if (showBlocked) "HIDE" else "SHOW", size = 11.sp, color = V.Purple, spacing = 1.sp)
                    }
                }
                if (showBlocked || q.isNotEmpty()) items(shut, key = { "x_" + it.pkg }) { app -> AppTile(app, store, false, starred = false, why = reasons[app.pkg]) }
            }
        }
    }
}

@Composable
private fun QuickTile(q: Quick, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(q.accent.copy(alpha = 0.1f)).border(1.dp, q.accent.copy(alpha = 0.5f), RoundedCornerShape(50))
            .tap(onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(q.glyph, q.accent, Modifier.size(20.dp))
        Gap(w = 8.dp)
        VText(q.label.uppercase(), size = 11.sp, color = q.accent, spacing = 1.sp, maxLines = 1)
    }
}

/**
 * One app: the same size tile for every app, icon on top, name underneath,
 * no box round it. A blocked app is dimmed with a lock and its reason.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(app: AppEntry, store: Store, managing: Boolean, starred: Boolean, why: String?) {
    val ctx = LocalContext.current
    val allowed = app.pkg in store.allowed
    val dim = why != null || (managing && !allowed)
    Column(
        Modifier.fillMaxWidth().height(112.dp).clip(Oct)
            .background(if (managing && allowed) V.Purple.copy(alpha = 0.12f) else Color.Transparent)
            .border(1.dp, if (managing && allowed) V.Purple.copy(alpha = 0.7f) else Color.Transparent, Oct)
            .combinedClickable(
                onClick = { if (managing) store.setAllowed(app.pkg, !allowed) else launchApp(ctx, store, app.pkg) },
                onLongClick = { store.toggleFav(app.pkg) },
            )
            .padding(top = 12.dp, start = 4.dp, end = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(58.dp).alpha(if (dim) 0.38f else 1f), contentAlignment = Alignment.Center) {
            AppGlyph(app, store.iconStyle, 54.dp)
        }
        Gap(h = 8.dp)
        VText(app.label, size = 12.sp, color = if (dim) V.Muted else V.Text, font = FontFamily.Default, maxLines = 1, align = TextAlign.Center)
        when {
            managing -> VText(if (allowed) "ALLOWED" else "BLOCKED", size = 9.sp, color = if (allowed) V.Purple else V.Tick, spacing = 1.sp)
            why != null -> VText(why.uppercase(), size = 8.sp, color = V.Coral, spacing = 0.5.sp, maxLines = 1)
            starred -> Glyph(G.STAR, V.Yellow, Modifier.size(11.dp))
        }
    }
}
