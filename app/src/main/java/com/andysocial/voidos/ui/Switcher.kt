package com.andysocial.voidos.ui

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andysocial.voidos.Store
import com.andysocial.voidos.Su
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * VoidOS's own app switcher, in place of Android's Recents (which Hide Android switches off).
 * Swipe up from the bottom edge, in VoidOS or over any app, and the apps you've used
 * fan out as glass cards: tap one to go back to it, flick one up to close it.
 */
object Switcher {
    val shown = mutableStateOf(false)

    /**
     * Opened from the bottom edge over another app. With Android's navigation bar gone there's
     * no back or home button, so the switcher is how you leave an app: tap outside the cards to
     * go back to it, Back to step back inside it, Home to stay in VoidOS.
     */
    val fromApp = mutableStateOf(false)

    /** When each recent app was last at the front, from Android's usage records. */
    val lastUsed = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * The apps used lately, newest first: what Android recorded coming to the front over the last
     * two days (VoidOS has usage access through root), then the apps opened from VoidOS.
     */
    fun recent(ctx: Context, store: Store, limit: Int = 12): List<String> {
        val fromSystem = runCatching {
            val usm = ctx.getSystemService(UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            val ev = usm.queryEvents(now - 2 * 86_400_000L, now)
            val e = UsageEvents.Event()
            val last = HashMap<String, Long>()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                @Suppress("DEPRECATION")
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) last[e.packageName] = e.timeStamp
            }
            lastUsed.clear(); lastUsed.putAll(last)
            last.entries.sortedByDescending { it.value }.map { it.key }
        }.getOrDefault(emptyList())
        return order(fromSystem, store.recentApps, AppsRepo.all(ctx).map { it.pkg }.toSet(), ctx.packageName)
            .filter { blockReason(ctx, store, it) == null }
            .take(limit)
    }

    /** Pure: system order first, then VoidOS's own list, only apps you can open, never VoidOS. */
    fun order(system: List<String>, voidRecent: List<String>, launchable: Set<String>, self: String): List<String> =
        (system + voidRecent).distinct().filter { it != self && it in launchable }

    /** Closes an app for good: Android stops it (root). */
    fun close(pkg: String) { Su.run("am force-stop ${Su.q(pkg)}") }
}

/** Pure: how many columns of cards fit in this width, between 2 and 4. */
fun switcherColumns(widthDp: Float, cardMinDp: Float = 230f, gapDp: Float = 20f, sideDp: Float = 48f): Int =
    (((widthDp - 2 * sideDp + gapDp) / (cardMinDp + gapDp)).toInt()).coerceIn(2, 4)

/**
 * Pure: the columns and card height for [n] cards in a [widthDp] x [heightDp] area. It starts
 * at the columns [switcherColumns] gives and adds columns (down to 170dp wide cards) and
 * shortens the cards (down to 136dp) until every card fits without scrolling. When even that
 * can't fit them all, the cards stay full size and the grid scrolls.
 */
fun switcherLayout(widthDp: Float, heightDp: Float, n: Int, gapDp: Float = 20f): Pair<Int, Float> {
    val base = switcherColumns(widthDp + 96f)
    if (n <= 0) return base to 190f
    for (cols in base..6) {
        val cardW = (widthDp - gapDp * (cols - 1)) / cols
        if (cols > base && cardW < 170f) break
        val rows = (n + cols - 1) / cols
        val h = ((heightDp - gapDp * (rows - 1)) / rows).coerceAtMost(190f)
        if (h >= 136f) return cols to h
    }
    return base to 190f
}

/** Pure: "Just now", "5 min ago", "2 h ago", "Yesterday", for when an app was last used. */
fun lastUsedLabel(now: Long, at: Long?): String {
    if (at == null || at <= 0L) return "Recently"
    val m = (now - at) / 60_000L
    return when {
        m < 1 -> "Just now"
        m < 60 -> "$m min ago"
        m < 24 * 60 -> "${m / 60} h ago"
        m < 48 * 60 -> "Yesterday"
        else -> "${m / (24 * 60)} days ago"
    }
}

/**
 * The app switcher, redone in 1.19.0: it sits on VoidOS's own solid backdrop (nothing from Home
 * shows through), with a header, a tidy grid of same-size glass cards (icon, name, when you last
 * used it, a close button) and one bar at the bottom for Back, Home and Close all.
 */
@Composable
fun AppSwitcher(store: Store) {
    var shown by Switcher.shown
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val apps = remember { mutableStateListOf<String>() }
    val all = remember(shown) { if (shown) AppsRepo.all(ctx) else emptyList() }
    androidx.compose.runtime.LaunchedEffect(shown) {
        if (shown) {
            val list = withContext(Dispatchers.IO) { Switcher.recent(ctx, store) }
            apps.clear(); apps.addAll(list)
        }
    }
    BackHandler(enabled = shown) { dismiss(ctx, back = false) }
    AnimatedVisibility(shown, enter = fadeIn(Motion.quick()), exit = fadeOut(Motion.quick())) {
        androidx.compose.runtime.CompositionLocalProvider(LocalRoom provides Rooms.overlay) {
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.fillMaxSize()
                .clickable(interactionSource = null, indication = null) { dismiss(ctx, back = false) }
                .semantics { contentDescription = "App switcher" },
        ) {
            SystemScrim()
            val cols = switcherColumns(maxWidth.value)
            val now = System.currentTimeMillis()
            Column(Modifier.fillMaxSize().padding(horizontal = 48.dp).padding(top = 40.dp, bottom = 28.dp)) {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        VText("Recent apps", size = 30.sp, font = SansLight, color = V.White)
                        VText(
                            when (apps.size) { 0 -> "Nothing open"; 1 -> "1 app"; else -> "${apps.size} apps" } +
                                if (apps.isNotEmpty()) "  ·  tap to open, swipe up to close" else "",
                            size = Type.label, color = V.Muted,
                        )
                    }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.height(28.dp))
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    if (apps.isEmpty()) {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Glyph(G.APPS, V.Muted, Modifier.size(44.dp))
                            androidx.compose.foundation.layout.Spacer(Modifier.height(Grid.m))
                            VText("No recent apps", size = Type.body, color = V.Muted)
                        }
                    } else androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                        val (gridCols, cardH) = switcherLayout(maxWidth.value, maxHeight.value, apps.size)
                        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(gridCols),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(apps.size, key = { apps[it] }) { i ->
                                val pkg = apps[i]
                                val e = all.firstOrNull { it.pkg == pkg }
                                if (e != null) SwitcherCard(e, store, lastUsedLabel(now, Switcher.lastUsed[pkg]), cardH.dp,
                                    onOpen = { shown = false; if (!launchApp(ctx, store, pkg)) store.forgetRecent(pkg) },
                                    onClose = {
                                        apps.remove(pkg); store.forgetRecent(pkg)
                                        scope.launch(Dispatchers.IO) { Switcher.close(pkg) }
                                    },
                                )
                            }
                        }
                    }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.height(20.dp))
                // one bar: Back and Home over an app on the left, Close all on the right
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (Switcher.fromApp.value) {
                        SwitcherButton("Back", G.BACK) { dismiss(ctx, back = true) }
                        androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
                        SwitcherButton("Home", G.HOUSE) { Switcher.fromApp.value = false; shown = false }
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    if (apps.isNotEmpty()) SwitcherButton("Close all", G.CLOSE, accent = true) {
                        val gone = apps.toList(); apps.clear(); gone.forEach { store.forgetRecent(it) }
                        scope.launch(Dispatchers.IO) { gone.forEach { Switcher.close(it) } }
                        Switcher.fromApp.value = false; shown = false
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun SwitcherButton(label: String, g: G, accent: Boolean = false, onTap: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.height(52.dp)
            .liquidGlass(Pill, GlassLook(smoke = 0.55f, depth = 0.7f, shine = 1.1f, magnify = 1.1f))
            .clip(Pill).clickable(onClick = onTap)
            .padding(horizontal = 22.dp)
            .semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(g, if (accent) V.Coral else V.Text, Modifier.size(18.dp))
        androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
        VText(label, size = Type.body, color = V.Text)
    }
}

/**
 * Closes the switcher. Opened over another app, it goes back to that app (VoidOS steps behind
 * it), and with [back] it then sends that app one Back press (root), standing in for the
 * navigation bar's back button.
 */
private fun dismiss(ctx: Context, back: Boolean) {
    val overApp = Switcher.fromApp.value
    Switcher.fromApp.value = false
    Switcher.shown.value = false
    if (!overApp) return
    (ctx as? android.app.Activity)?.moveTaskToBack(true)
    if (back) Thread { Thread.sleep(350); Su.run("input keyevent 4") }.start()
}

@Composable
private fun SwitcherCard(e: AppEntry, store: Store, used: String, height: androidx.compose.ui.unit.Dp, onOpen: () -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val dy = remember { Animatable(0f) }
    val shape = RoundedCornerShape(26.dp)
    Box(
        Modifier.fillMaxWidth().height(height)
            .testTag("switcher_card")
            .offset { IntOffset(0, dy.value.roundToInt()) }
            .graphicsLayer { alpha = (1f + dy.value / 700f).coerceIn(0f, 1f) }
            .pointerInput(e.pkg) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (dy.value < -140f) { dy.animateTo(-900f, Motion.quick()); onClose() }
                            else dy.animateTo(0f, Motion.spring())
                        }
                    },
                ) { ch, d -> ch.consume(); scope.launch { dy.snapTo((dy.value + d).coerceAtMost(30f)) } }
            }
            .liquidGlass(shape, GlassLook(smoke = 0.5f, depth = 0.8f, shine = 1f, magnify = 1.05f))
            .clip(shape)
            .clickable(onClick = onOpen)
            .semantics { contentDescription = e.label },
    ) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.Top) {
                AppGlyph(e, store.iconStyle, if (height < 160.dp) 48.dp else if (height < 175.dp) 56.dp else 64.dp)
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable { scope.launch { dy.animateTo(-900f, Motion.quick()); onClose() } }
                        .semantics { contentDescription = "Close ${e.label}" },
                    contentAlignment = Alignment.Center,
                ) { Glyph(G.CLOSE, V.Text.copy(alpha = 0.8f), Modifier.size(14.dp)) }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            VText(e.label, size = 19.sp, color = V.White, maxLines = 1)
            VText(used, size = Type.label, color = V.Muted, maxLines = 1)
        }
    }
}
