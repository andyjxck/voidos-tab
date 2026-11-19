package com.andysocial.voidos.ui

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.andysocial.voidos.Store
import com.andysocial.voidos.Su
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/*
 * The book: Home split in two like an open book.
 *
 * Drag a screen (Notes, Weather, Tools...) out of the tab wheel, or an app out of the app
 * wheel, and drop it on one half of the screen. It opens in that half and the watch face
 * slides over to the other half. Drop a second one on the watch face's half and it takes
 * that half too, and both stay open and usable: two at most, never more. With both halves
 * filled the time and date sit in a small island at the top. Close a half and the watch
 * face comes back into it; close the last one and Home is the full watch face again.
 * Swipe away from Home for five seconds or more and it's the full watch face when you
 * come back; back sooner and the book is just as you left it.
 *
 * Landscape splits left and right, portrait top and bottom: "first" is left or top.
 *
 * VoidOS's own screens are drawn right inside the half, live, with all their touches.
 * Another app can't be drawn inside VoidOS (Android keeps every app in its own window),
 * so an app dropped on a half goes through Android's own split screen instead: the app's
 * window takes that half and VoidOS's window the other. Android has one split, so one app
 * at a time: dropping a second app replaces the first.
 */

enum class PaneKind { SCREEN, APP }

/** What one half of the book shows: one of VoidOS's screens (a [Module] name) or an app (a package). */
data class Pane(val kind: PaneKind, val key: String) {
    fun encode() = (if (kind == PaneKind.SCREEN) "s:" else "a:") + key

    companion object {
        fun screen(m: Module) = Pane(PaneKind.SCREEN, m.name)
        fun app(pkg: String) = Pane(PaneKind.APP, pkg)
        fun decode(s: String?): Pane? {
            if (s.isNullOrEmpty() || s.length < 3 || s[1] != ':') return null
            val key = s.substring(2)
            return when (s[0]) {
                's' -> runCatching { Module.valueOf(key) }.getOrNull()?.takeIf { it != Module.FACE }?.let { screen(it) }
                'a' -> app(key)
                else -> null
            }
        }
    }
}

/**
 * Both halves. A null half is where the watch face sits; both null is the plain full
 * watch face. Kept free of Android so the rules can be tested with plain values.
 */
data class BookState(val first: Pane? = null, val second: Pane? = null) {
    val isOpen get() = first != null || second != null

    /** The watch face's half: true first, false second, null when it fills Home or is covered. */
    val faceFirst: Boolean? get() = when {
        first == null && second != null -> true
        second == null && first != null -> false
        else -> null
    }
    val faceShown get() = first == null || second == null
    val app: Pane? get() = listOfNotNull(first, second).firstOrNull { it.kind == PaneKind.APP }
    val appFirst: Boolean? get() = when {
        first?.kind == PaneKind.APP -> true
        second?.kind == PaneKind.APP -> false
        else -> null
    }

    /** Both halves filled: the watch face is covered, so the time and date move up into the island. */
    val full get() = first != null && second != null

    /**
     * [p] dropped on a half. It takes that half, and whatever is open on the other half
     * stays open beside it: two at most, both live. Dropping something that's already open
     * on the other half moves it across (the two swap). Android has only one split, so a
     * second app dropped beside an app replaces it, and the watch face takes that half.
     */
    fun drop(p: Pane, toFirst: Boolean): BookState {
        val here = if (toFirst) first else second
        val there = if (toFirst) second else first
        if (here == p) return this
        val newThere = when {
            there == p -> here
            p.kind == PaneKind.APP && there?.kind == PaneKind.APP -> null
            else -> there
        }
        return if (toFirst) BookState(p, newThere) else BookState(newThere, p)
    }

    /** One half closed: the watch face comes back into it. */
    fun close(isFirst: Boolean) = if (isFirst) copy(first = null) else copy(second = null)

    fun withoutApp() = BookState(first?.takeIf { it.kind != PaneKind.APP }, second?.takeIf { it.kind != PaneKind.APP })

    fun encode() = (first?.encode() ?: "") + "|" + (second?.encode() ?: "")

    companion object {
        fun decode(s: String?): BookState {
            val parts = (s ?: "").split('|')
            val a = Pane.decode(parts.getOrNull(0))
            val b = Pane.decode(parts.getOrNull(1))
            // two apps can't share Android's one split: keep the first
            return if (a?.kind == PaneKind.APP && b?.kind == PaneKind.APP) BookState(a, null) else BookState(a, b)
        }

        /** How long you have to be away from Home before the book closes back to the watch face. */
        const val AWAY_MS = 5_000L

        /** Coming back to Home at [now] after leaving it at [leftAt]: true when you were away long enough to start fresh. */
        fun awayLongEnough(leftAt: Long?, now: Long) = leftAt != null && now - leftAt >= AWAY_MS
    }
}

/** The book's state, kept across restarts (an app half is dropped again if its split isn't there). */
object Book {
    val state = mutableStateOf(BookState())

    /** Which half was filled last, so Back closes that one first. */
    private var lastFirst: Boolean? = null
    private var loaded = false

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("voidos_book", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        runCatching { state.value = BookState.decode(prefs(ctx).getString("book", "")) }
    }

    fun set(ctx: Context, s: BookState) {
        state.value = s
        runCatching { prefs(ctx).edit().putString("book", s.encode()).apply() }
    }

    /** When you last swiped away from Home, null while you're on it. */
    var leftAt: Long? = null
        private set

    /**
     * The page changed. Leaving Home starts the clock; coming back within five seconds keeps
     * the book as it was, and coming back after longer closes it to the plain watch face.
     * Only time spent away counts: sitting on the split never closes it. True when it closed.
     */
    fun onHome(ctx: Context, onHome: Boolean, now: Long): Boolean {
        if (!onHome) { if (leftAt == null) leftAt = now; return false }
        val reset = BookState.awayLongEnough(leftAt, now) && state.value.isOpen
        leftAt = null
        if (reset) { set(ctx, BookState()); lastFirst = null }
        return reset
    }

    fun drop(ctx: Context, p: Pane, toFirst: Boolean) {
        // a fresh drop is what you want now, however long you were away
        leftAt = null
        lastFirst = toFirst
        set(ctx, state.value.drop(p, toFirst))
    }

    fun close(ctx: Context, isFirst: Boolean) {
        set(ctx, state.value.close(isFirst))
        if (lastFirst == isFirst) lastFirst = null
    }

    /** Back on Home: closes the half filled last, or else any open VoidOS half. True when it closed one. */
    fun back(ctx: Context): Boolean {
        val s = state.value
        val side = lastFirst?.takeIf { (if (it) s.first else s.second)?.kind == PaneKind.SCREEN }
            ?: when {
                s.second?.kind == PaneKind.SCREEN -> false
                s.first?.kind == PaneKind.SCREEN -> true
                else -> null
            }
            ?: return false
        close(ctx, side)
        return true
    }

    /** Testing: start from a closed book. */
    fun reset() { state.value = BookState(); lastFirst = null; leftAt = null }

    /**
     * The Android split for an app half: the app's window on [appFirst]'s side (left or top),
     * VoidOS's on the other. Runs as root, off the main thread. Android 10 won't split the
     * screen inside the kiosk lock, so VoidOS steps out of it while the split lasts, the
     * same way the honeycomb's drag-to-split does.
     */
    suspend fun openApp(ctx: Context, store: Store, pkg: String, appFirst: Boolean): Boolean {
        val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString() ?: return false
        store.setSplit(pkg)
        (ctx as? com.andysocial.voidos.MainActivity)?.let { a -> runCatching { a.stopLockTask() } }
        val me = "${ctx.packageName}/.MainActivity"
        return withContext(Dispatchers.IO) { Su.ok(splitCommand(launch, me, appFirst), timeoutS = 15) }
    }

    /** Ends the app half: VoidOS full screen again (MainActivity puts the kiosk lock back). */
    suspend fun closeApp(ctx: Context, store: Store) {
        set(ctx, state.value.withoutApp())
        store.setSplit("")
        withContext(Dispatchers.IO) { Su.ok("am start --windowingMode 1 -n ${ctx.packageName}/.MainActivity >/dev/null", timeoutS = 10) }
    }
}

/**
 * The shell commands for Android's split screen: windowing mode 3 is the primary half
 * (left in landscape, top in portrait), 4 the other half. The primary is started first.
 */
internal fun splitCommand(app: String, me: String, appFirst: Boolean): String {
    val primary = if (appFirst) app else me
    val secondary = if (appFirst) me else app
    return "settings put global force_resizable_activities 1; " +
        "am start --windowingMode 3 -n $primary >/dev/null; sleep 0.4; " +
        "am start --windowingMode 4 -n $secondary >/dev/null"
}

/** Screens that can open in a half of the book: everything but Home itself. */
fun bookable(m: Module) = m != Module.FACE

/**
 * Home: the watch face, and the book when something's been dropped on it. [face] draws the
 * watch face in whatever room it's given; [screen] draws one of VoidOS's screens.
 */
@Composable
fun BookHome(store: Store, face: @Composable () -> Unit, screen: @Composable (Module) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Book.load(ctx) }
    val s by Book.state
    val split by Idle.split

    // an app half lives only as long as Android's split: if the split ends (the divider
    // dragged off, or it never started) the half closes and the watch face comes back
    val app = s.app
    val wasSplit = remember { mutableStateOf(false) }
    LaunchedEffect(app, split) {
        if (app == null) { wasSplit.value = split; return@LaunchedEffect }
        if (split) { wasSplit.value = true; return@LaunchedEffect }
        if (!wasSplit.value) delay(6_000)
        if (!Idle.split.value && Book.state.value.app == app) {
            Book.set(ctx, Book.state.value.withoutApp())
            if (store.splitApp == app.key) store.setSplit("")
        }
        wasSplit.value = false
    }

    if (app != null && split) {
        // VoidOS has its own window beside the app's: it shows the other half, full width
        val mine = if (s.appFirst == true) s.second else s.first
        Box(Modifier.fillMaxSize().testTag("book")) {
            PaneBody(mine, face, screen)
            if (mine != null) TimeIsland(store, Modifier.align(Alignment.TopCenter))
            CloseDrop(
                Modifier.align(if (s.appFirst == true) Alignment.TopStart else Alignment.TopEnd),
                "Close ${appLabel(ctx, app.key)}",
            ) { scope.launch { Book.closeApp(ctx, store) } }
        }
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize().testTag(if (s.isOpen) "book" else "home")) {
        val portrait = maxHeight > maxWidth
        val fullW = maxWidth
        val fullH = maxHeight
        val total = if (portrait) maxHeight else maxWidth
        val gutter = 12.dp
        val half = (total - gutter) / 2
        // how far each half is open, 0..1: the watch face gives up that much room on that side
        val a by animateFloatAsState(if (s.first != null) 1f else 0f, Motion.spring(), label = "bookFirst")
        val b by animateFloatAsState(if (s.second != null) 1f else 0f, Motion.spring(), label = "bookSecond")
        // the face stays out of the way of a half only while it's showing
        val faceStart = (half + gutter) * a
        val faceEnd = (half + gutter) * b
        val faceLen = (total - faceStart - faceEnd).coerceAtLeast(0.dp)
        if (s.faceShown || a < 1f || b < 1f) {
            Box(
                Modifier
                    .offset { if (portrait) IntOffset(0, faceStart.roundToPx()) else IntOffset(faceStart.roundToPx(), 0) }
                    .let { if (portrait) it.size(maxWidth, faceLen) else it.size(faceLen, maxHeight) }
                    .testTag("bookFace"),
            ) {
                CompositionLocalProvider(LocalPortrait provides (if (portrait) faceLen > fullW else fullH > faceLen)) { face() }
            }
        }
        for (isFirst in listOf(true, false)) {
            val p = if (isFirst) s.first else s.second
            val shown = if (isFirst) a else b
            // keep what was there while it slides away, so closing animates too
            val held = remember { mutableStateOf<Pane?>(null) }
            if (p != null) held.value = p
            val pane = p ?: held.value?.takeIf { shown > 0.01f }
            if (pane == null || pane.kind == PaneKind.APP) continue
            val m = runCatching { Module.valueOf(pane.key) }.getOrNull() ?: continue
            val start = if (isFirst) 0.dp else total - half
            Box(
                Modifier
                    .offset { if (portrait) IntOffset(0, start.roundToPx()) else IntOffset(start.roundToPx(), 0) }
                    .let { if (portrait) it.size(maxWidth, half) else it.size(half, maxHeight) }
                    .graphicsLayer {
                        // slides in from its own edge, like a page turning onto the spread
                        val off = (1f - shown) * (if (portrait) size.height else size.width) * 0.35f * (if (isFirst) -1f else 1f)
                        if (portrait) translationY = off else translationX = off
                        alpha = shown.coerceIn(0f, 1f)
                    }
                    .testTag(if (isFirst) "bookFirst" else "bookSecond"),
            ) {
                CompositionLocalProvider(LocalPortrait provides (if (portrait) half > fullW else fullH > half)) {
                    Box(Modifier.fillMaxSize().padding(top = 52.dp)) { screen(m) }
                }
                CloseDrop(
                    Modifier.align(if (isFirst || portrait) Alignment.TopStart else Alignment.TopEnd),
                    "Close ${m.label}",
                ) { if (p != null) Book.close(ctx, isFirst) }
            }
        }
        // both halves filled: the watch face is covered, so the time and date ride in a small
        // island in the band above the panes, clear of both of them
        val island by animateFloatAsState(if (s.full) 1f else 0f, Motion.spring(), label = "island")
        if (island > 0.01f) TimeIsland(
            store,
            Modifier.align(Alignment.TopCenter).graphicsLayer {
                alpha = island; scaleX = 0.7f + 0.3f * island; scaleY = 0.7f + 0.3f * island
                translationY = (1f - island) * -24f
            },
        )
    }
}

/**
 * The time and date in a small pill at the top, like a phone's island, shown while both
 * halves of the book are filled and the watch face has nowhere to go. It sits in the
 * 52 dp band above the panes, between their close buttons, so it never covers either.
 */
@Composable
internal fun TimeIsland(store: Store, modifier: Modifier = Modifier) {
    val now = rememberNow(1000)
    val time = SimpleDateFormat(if (store.use24h) "HH:mm" else "h:mm", Locale.UK).format(Date(now))
    val date = SimpleDateFormat("EEE d MMM", Locale.UK).format(Date(now))
    Row(
        modifier.padding(top = 6.dp).height(38.dp)
            .clip(Pill).background(Color.Black.copy(alpha = 0.82f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), Pill)
            .padding(horizontal = 18.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$time, $date" }
            .testTag("timeIsland"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VText(time, size = Type.body, color = V.Text, font = Sans, maxLines = 1)
        Box(Modifier.padding(horizontal = 10.dp).size(4.dp).clip(CircleShape).background(V.Teal.copy(alpha = 0.8f)))
        VText(date, size = Type.label, color = V.Muted, font = Sans, maxLines = 1)
    }
}

@Composable
private fun PaneBody(p: Pane?, face: @Composable () -> Unit, screen: @Composable (Module) -> Unit) {
    val m = p?.takeIf { it.kind == PaneKind.SCREEN }?.let { runCatching { Module.valueOf(it.key) }.getOrNull() }
    if (m == null) face() else Box(Modifier.fillMaxSize().padding(top = 52.dp)) { screen(m) }
}

/** The way out of a half: one small drop of glass in its top corner, a cross inside. */
@Composable
private fun CloseDrop(modifier: Modifier, label: String, onClose: () -> Unit) {
    Box(
        modifier.padding(4.dp).size(42.dp)
            .liquidGlass(CircleShape, GlassLook(smoke = 0.9f, depth = 1.1f, shine = 1.2f))
            .squish()
            .tap(onClose)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Glyph(G.CLOSE, V.Text, Modifier.size(16.dp)) }
}

private fun appLabel(ctx: Context, pkg: String): String =
    runCatching { AppsRepo.all(ctx).firstOrNull { it.pkg == pkg }?.label }.getOrNull() ?: "app"
