package com.andysocial.voidos.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.drawBehind

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.CircleShape

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
const val HIDDEN = "hidden"

fun blockReason(ctx: Context, store: Store, pkg: String): String? {
    val pm = ctx.packageManager
    val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return "not installed"
    if (!ai.enabled) return "turned off in Android"
    if (runCatching { pm.isPackageSuspended(pkg) }.getOrDefault(false)) return "paused by Android"
    // the drawer shows only the apps chosen in Manage; the rest stay hidden and can't be opened from VoidOS
    if (pkg !in store.allowed) return HIDDEN
    // 2.0: only Dev Apps (and the browser) launch from VoidOS; everything else stays hidden
    if (pkg !in com.andysocial.voidos.hub.DevApps.allowedPackages(ctx, store)) return HIDDEN
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
        launchMsg.value = if (why == HIDDEN) "$name is hidden. Add it in Settings › Dev Apps to open it from VoidOS." else "$name is $why."
        blocked.value = pkg
        return false
    }
    return runCatching {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); launchMsg.value = null
        if (pkg != null) store.noteRecent(pkg)
        true
    }
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
 * "void" a VoidOS line icon for apps it knows and a greyed icon on a quiet tile
 * for the rest, "letter" the app's initials on a quiet tile.
 */
@Composable
fun AppGlyph(app: AppEntry, style: String, size: Dp) {
    val ctx = LocalContext.current
    if (style == "glass") { GlassAppIcon(app.pkg, size); return }
    val glyph = if (style == "void") glyphForPackage(app.pkg) else null
    // no squares anywhere: the plain styles sit in a neutral glass drop too
    val drop: @Composable (@Composable () -> Unit) -> Unit = { inner ->
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            GlassDrop(V.Panel2, Modifier.size(size)) {}
            inner()
        }
    }
    when {
        style == "letter" -> drop { VText(initialsFor(app.label), size = (size.value * 0.34f).sp, color = V.Text, font = SansMedium, maxLines = 1) }
        glyph != null -> drop { Glyph(glyph, V.Text, Modifier.size(size * 0.5f)) }
        else -> {
            var icon by remember(app.pkg) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(app.pkg) { icon = Icons.get(ctx, app.pkg) }
            if (style == "void") drop {
                icon?.let { Image(it, app.label, Modifier.size(size * 0.56f), colorFilter = greyscale, alpha = 0.9f) }
            }
            else Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                icon?.let { Image(it, app.label, Modifier.size(size)) }
            }
        }
    }
}

/**
 * Apps, like an iPad home screen: a light title, a search pill, then a calm
 * grid of icons with their names, no boxes. Only apps that will really open
 * are here: the ones chosen in Manage that Android hasn't turned off or
 * paused. Starred apps (long-press) also sit in a glass dock at the bottom.
 * Manage is a plain list with a switch per app, shown ones first.
 */
@Composable
fun AppsScreen(store: Store) {
    val ctx = LocalContext.current
    val portrait = LocalPortrait.current
    var managing by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var all by remember { mutableStateOf(AppsRepo.all(ctx)) }
    LaunchedEffect(refresh) { all = withContext(Dispatchers.IO) { AppsRepo.all(ctx, fresh = true) } }
    val q = query.trim()
    val match = if (q.isEmpty()) all else all.filter { it.label.contains(q, ignoreCase = true) }
    // only the apps chosen in Manage that Android will also open; everything else is simply not here
    val reasons = remember(all, store.allowed, refresh) { all.associate { it.pkg to blockReason(ctx, store, it.pkg) } }
    val open = match.filter { reasons[it.pkg] == null }
    val shownCount = all.count { reasons[it.pkg] == null }
    val favs = store.favs.mapNotNull { f -> all.firstOrNull { it.pkg == f && reasons[it.pkg] == null } }
    val quick = remember(store.allowed, refresh) { Quick.entries.filter { qk -> quickTargets(ctx, qk).any { it in store.allowed && reasons[it] == null } } }
    val msg by launchMsg
    val stopped by blocked

    LaunchedEffect(managing) { if (!managing) refresh++ }
    // a stopped launch says so for a few seconds, then gets out of the way
    LaunchedEffect(msg) { if (msg != null) { kotlinx.coroutines.delay(5000); launchMsg.value = null } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                VText(
                    if (managing) "Done" else "Manage",
                    size = Type.headline, color = V.Teal, font = if (managing) SansMedium else Sans,
                    modifier = Modifier.clip(Pill).clickable {
                        if (managing) (ctx as? MainActivity)?.applyPolicies()
                        managing = !managing; query = ""
                    }.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Gap(h = 16.dp)
            SearchPill(query, { query = it }, hint = if (managing) "Search all ${all.size} apps" else "Search")
            if (managing) ManageList(store, match, reasons, Modifier.weight(1f))
            else {
                if (q.isEmpty() && quick.isNotEmpty()) {
                    Gap(h = 12.dp)
                    // the shortcuts as one long glass bar, like the Off / Automatic / Provider picker in Settings:
                    // equal segments inside the same glass, no separate pills. Icon over name when six share a narrow screen.
                    val stack = portrait && quick.size > 4
                    GlassActions(
                        quick.map { qk -> GlassAction(qk.label, qk.glyph) { runQuick(ctx, store, qk) } },
                        height = if (stack) 60.dp else 48.dp, stacked = stack,
                    )
                }
                Gap(h = 8.dp)
                if (open.isEmpty()) {
                    Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Glyph(G.APPS, V.Tick, Modifier.size(44.dp))
                        Gap(h = 12.dp)
                        VText(if (q.isNotEmpty()) "No app matches \"$q\"" else "No apps chosen yet", size = Type.title, font = SansMedium, color = V.Text)
                        if (q.isEmpty()) {
                            Gap(h = 8.dp)
                            VText("No apps", size = Type.body, color = V.Muted)
                        }
                    }
                } else if (q.isEmpty()) {
                    // the honeycomb: pan around, tap to open, hold and drag up to open beside the clock
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        HoneycombApps(
                            store, open,
                            onOpen = { app -> launchApp(ctx, store, app.pkg) },
                            onSplit = { app -> Split.open(ctx, store, app.pkg) },
                            // the starred dock floats over the bottom; keep the comb above it
                            bottomInset = if (favs.isNotEmpty()) 96.dp else 0.dp,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(if (portrait) 104.dp else 118.dp),
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                    ) {
                        items(open, key = { it.pkg }) { app -> AppTile(app, store, starred = app.pkg in store.favs) }
                    }
                }
            }
        }
        if (!managing && q.isEmpty() && favs.isNotEmpty()) Dock(favs.take(if (portrait) 6 else 9), store, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
        // a stopped launch: a floating note with the fix right there
        msg?.let { m ->
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp, start = 16.dp, end = 16.dp).widthIn(max = 620.dp)
                    .liquidGlass(Pill, GlassLook(smoke = 1.25f, depth = 1.2f))
                    .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VText(m, size = Type.body, color = V.Text, modifier = Modifier.weight(1f, fill = false), maxLines = 2)
                if (stopped != null && reasons[stopped] == HIDDEN) {
                    Gap(w = 8.dp)
                    VText("Manage", size = Type.body, color = V.Teal, font = SansMedium,
                        modifier = Modifier.clip(Pill).clickable { managing = true; launchMsg.value = null }.padding(horizontal = 8.dp, vertical = 8.dp))
                }
                RoundIcon(G.CLOSE, "Dismiss", size = 34.dp) { launchMsg.value = null; blocked.value = null }
            }
        }
    }
}

/** One app in the grid: icon and name, nothing around them. Long-press stars it for the dock. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(app: AppEntry, store: Store, starred: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxWidth().squish().clip(Radius.Card)
            .combinedClickable(onClick = { launchApp(ctx, store, app.pkg) }, onLongClick = { com.andysocial.voidos.Haptics.press(view); store.toggleFav(app.pkg) })
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(68.dp).drawBehind {
                // a soft pool of light under each icon, so the grid sits on the glass instead of floating
                val c = androidx.compose.ui.geometry.Offset(size.width / 2, size.height * 0.92f)
                drawOval(Brush.radialGradient(listOf(Ambience.tint().copy(alpha = 0.22f), Color.Transparent), center = c, radius = size.width * 0.55f),
                    topLeft = androidx.compose.ui.geometry.Offset(0f, size.height * 0.72f), size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.4f))
            },
            contentAlignment = Alignment.Center,
        ) {
            AppGlyph(app, store.iconStyle, 64.dp)
            if (starred) Box(Modifier.align(Alignment.TopEnd).size(10.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color.White, V.Teal))))
        }
        Gap(h = 8.dp)
        VText(app.label, size = Type.label, color = V.Text, maxLines = 1, align = TextAlign.Center)
    }
}

/** Starred apps in a glass dock, icons only, like the iPad's. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Dock(apps: List<AppEntry>, store: Store, modifier: Modifier) {
    val view = androidx.compose.ui.platform.LocalView.current
    val ctx = LocalContext.current
    val shape = Radius.Panel
    Row(
        modifier.glass(shape, 0.92f)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        apps.forEach { app ->
            Box(
                Modifier.size(64.dp).squish(0.88f).clip(Radius.Control)
                    .semantics { contentDescription = app.label }
                    .combinedClickable(onClick = { launchApp(ctx, store, app.pkg) }, onLongClick = { com.andysocial.voidos.Haptics.press(view); store.toggleFav(app.pkg) }),
                contentAlignment = Alignment.Center,
            ) { AppGlyph(app, store.iconStyle, 60.dp) }
        }
    }
}

/**
 * Manage: every app on the tablet in two groups, Shown then Hidden, each a
 * plain list with a switch. A star puts the app in the dock. Apps Android has
 * turned off or paused can't be shown and say why.
 */
@Composable
private fun ManageList(store: Store, apps: List<AppEntry>, reasons: Map<String, String?>, modifier: Modifier) {
    val shown = apps.filter { it.pkg in store.allowed }
    val hidden = apps.filter { it.pkg !in store.allowed }
    LazyColumn(modifier, contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        item(key = "intro") {
            VText(
                "Only the apps you switch on appear in VoidOS. Hidden apps don't show anywhere and can't be opened from it.",
                size = Type.label, color = V.Muted, lineHeight = 18.sp, modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 4.dp),
            )
        }
        listOf("Shown" to shown, "Hidden" to hidden).forEach { (title, list) ->
            if (list.isEmpty()) return@forEach
            item(key = "h-$title") {
                VText(title, size = Type.body, font = SansMedium, color = V.Text, modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp))
            }
            itemsIndexed(list, key = { _, a -> "m-" + a.pkg }) { i, app ->
                val why = reasons[app.pkg]?.takeIf { it != HIDDEN }
                ManageRow(app, store, on = app.pkg in store.allowed, starred = app.pkg in store.favs, why = why, first = i == 0, last = i == list.lastIndex)
            }
        }
    }
}

@Composable
private fun ManageRow(app: AppEntry, store: Store, on: Boolean, starred: Boolean, why: String?, first: Boolean, last: Boolean) {
    Column(Modifier.fillMaxWidth().clip(groupShape(first, last)).background(GroupFill).clickable { store.setAllowed(app.pkg, !on) }) {
        if (!first) Spacer(Modifier.height(4.dp))
        Row(Modifier.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.alpha(if (on) 1f else 0.55f)) { AppGlyph(app, store.iconStyle, 40.dp) }
            Gap(w = 16.dp)
            Column(Modifier.weight(1f)) {
                VText(app.label, size = Type.headline, color = V.Text, maxLines = 1)
                if (why != null) VText(sentence(why) + ", so it won't open", size = Type.caption, color = V.Coral, maxLines = 1)
            }
            if (on) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).semantics { contentDescription = if (starred) "Unstar ${app.label}" else "Star ${app.label}" }
                        .clickable { store.toggleFav(app.pkg) },
                    contentAlignment = Alignment.Center,
                ) { Glyph(G.STAR, if (starred) V.Teal else V.Tick, Modifier.size(20.dp)) }
                Gap(w = 8.dp)
            }
            VToggle(on) { store.setAllowed(app.pkg, it) }
        }
    }
}

