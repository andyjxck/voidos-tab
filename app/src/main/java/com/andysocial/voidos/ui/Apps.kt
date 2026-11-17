package com.andysocial.voidos.ui

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
import androidx.compose.runtime.produceState
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
            runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }
                .getOrNull()?.also { cache.put(pkg, it) }
        }
    }
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

fun launchApp(ctx: Context, store: Store, pkg: String): Boolean {
    val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
    if (i == null) { launchMsg.value = "That app can't be opened."; return false }
    return startSafely(ctx, store, i, pkg)
}

private fun startSafely(ctx: Context, store: Store, i: Intent, pkg: String?): Boolean {
    if (store.kiosk && pkg != null && pkg !in store.allowed) {
        launchMsg.value = "Lock mode is on and that app isn't allowed. Allow it under Manage."
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
    if (targets.isNotEmpty()) { launchApp(ctx, store, targets.first()); return }
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
            val icon by produceState<ImageBitmap?>(null, app.pkg) { value = Icons.get(ctx, app.pkg) }
            if (style == "void") Box(
                Modifier.size(size).clip(OctS).background(accent.copy(alpha = 0.12f)).border(1.5.dp, accent, OctS),
                contentAlignment = Alignment.Center,
            ) { icon?.let { Image(it, app.label, Modifier.size(size * 0.66f), colorFilter = greyscale, alpha = 0.9f) } }
            else icon?.let { Image(it, app.label, Modifier.size(size)) }
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
    val all by produceState(AppsRepo.all(ctx), refresh) { value = withContext(Dispatchers.IO) { AppsRepo.all(ctx, fresh = true) } }
    val base = if (managing || !store.kiosk) all else all.filter { it.pkg in store.allowed }
    val shown = if (query.isBlank()) base else base.filter { it.label.contains(query.trim(), ignoreCase = true) }
    val favs = store.favs.mapNotNull { f -> all.firstOrNull { it.pkg == f } }
    val msg by launchMsg

    LaunchedEffect(managing) { if (!managing) refresh++ }

    Column(Modifier.fillMaxSize()) {
        // header: search + manage
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Glyph(G.SEARCH, V.Muted, Modifier.size(22.dp))
                Gap(w = 10.dp)
                VField(query, { query = it }, Modifier.weight(1f), placeholder = "Search ${base.size} apps")
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
                if (store.kiosk) "Tap to allow or block. Lock mode is on, so blocked apps can't open."
                else "Tap to choose which apps lock mode lets through. Long-press any app to star it for the face.",
                size = 12.sp, color = V.Muted, font = FontFamily.Default,
            )
        }
        msg?.let { Gap(h = 8.dp); VText(it, size = 12.sp, color = V.Coral, font = FontFamily.Default) }
        Gap(h = 12.dp)

        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (portrait) 108.dp else 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            if (!managing && query.isBlank()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Quick.entries.forEach { q -> QuickTile(q) { runQuick(ctx, store, q) } }
                    }
                }
                if (favs.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { Label("Starred", modifier = Modifier.padding(top = 6.dp)) }
                    items(favs, key = { "f_" + it.pkg }) { app -> AppTile(app, store, managing, starred = true) }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Label(if (store.kiosk) "Allowed in lock mode" else "All apps", modifier = Modifier.padding(top = 6.dp)) }
            }
            if (shown.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    VText(if (query.isNotBlank()) "NO APP MATCHES" else "NOTHING ALLOWED YET. TAP MANAGE.", size = 13.sp, color = V.Muted,
                        align = TextAlign.Center, spacing = 1.sp, modifier = Modifier.padding(top = 40.dp).fillMaxWidth())
                }
            }
            items(shown, key = { it.pkg }) { app -> AppTile(app, store, managing, starred = app.pkg in store.favs) }
        }
    }
}

@Composable
private fun QuickTile(q: Quick, onClick: () -> Unit) {
    Column(
        Modifier.width(118.dp).clip(Oct).background(q.accent.copy(alpha = 0.12f)).border(1.dp, q.accent.copy(alpha = 0.6f), Oct)
            .tap(onClick).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Glyph(q.glyph, q.accent, Modifier.size(34.dp))
        Gap(h = 8.dp)
        VText(q.label.uppercase(), size = 11.sp, color = q.accent, spacing = 1.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(app: AppEntry, store: Store, managing: Boolean, starred: Boolean) {
    val ctx = LocalContext.current
    val allowed = app.pkg in store.allowed
    Column(
        Modifier.fillMaxWidth().clip(Oct)
            .background(if (managing && allowed) V.Purple.copy(alpha = 0.14f) else V.Panel)
            .border(1.dp, if (managing && allowed) V.Purple else V.Line, Oct)
            .combinedClickable(
                onClick = { if (managing) store.setAllowed(app.pkg, !allowed) else launchApp(ctx, store, app.pkg) },
                onLongClick = { store.toggleFav(app.pkg) },
            )
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp)) {
            Box(Modifier.align(Alignment.Center)) { AppGlyph(app, store.iconStyle, 52.dp) }
            if (starred) Glyph(G.STAR, V.Yellow, Modifier.size(16.dp).align(Alignment.TopEnd))
        }
        Gap(h = 8.dp)
        VText(app.label, size = 12.sp, color = V.Text, font = FontFamily.Default, maxLines = 1, align = TextAlign.Center)
        if (managing) {
            Gap(h = 4.dp)
            VText(if (allowed) "ALLOWED" else "BLOCKED", size = 10.sp, color = if (allowed) V.Purple else V.Tick, spacing = 1.sp)
        }
    }
}
