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
import androidx.compose.ui.Modifier
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
    BackHandler(enabled = shown) { shown = false }
    AnimatedVisibility(shown, enter = fadeIn(Motion.quick()), exit = fadeOut(Motion.quick())) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
                .clickable(interactionSource = null, indication = null) { shown = false }
                .semantics { contentDescription = "App switcher" },
            contentAlignment = Alignment.Center,
        ) {
            if (apps.isEmpty()) {
                VText("No recent apps", size = Type.body, color = V.Muted)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(Grid.l),
                    ) {
                        items(apps, key = { it }) { pkg ->
                            val e = all.firstOrNull { it.pkg == pkg }
                            if (e != null) SwitcherCard(e, store,
                                onOpen = { shown = false; if (!launchApp(ctx, store, pkg)) store.forgetRecent(pkg) },
                                onClose = {
                                    apps.remove(pkg); store.forgetRecent(pkg)
                                    scope.launch(Dispatchers.IO) { Switcher.close(pkg) }
                                },
                            )
                        }
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.height(Grid.xl))
                    Box(
                        Modifier.glass(Pill, 0.7f).clickable {
                            val gone = apps.toList(); apps.clear(); gone.forEach { store.forgetRecent(it) }
                            scope.launch(Dispatchers.IO) { gone.forEach { Switcher.close(it) } }
                            shown = false
                        }.padding(horizontal = Grid.l, vertical = Grid.s),
                    ) { VText("Close all", size = Type.label, color = V.Text) }
                }
            }
        }
    }
}

@Composable
private fun SwitcherCard(e: AppEntry, store: Store, onOpen: () -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val dy = remember { Animatable(0f) }
    val shape = RoundedCornerShape(Radius.card)
    Box(
        Modifier.width(200.dp).height(300.dp)
            .offset { IntOffset(0, dy.value.roundToInt()) }
            .graphicsLayer { alpha = (1f + dy.value / 900f).coerceIn(0f, 1f) }
            .pointerInput(e.pkg) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (dy.value < -160f) { dy.animateTo(-1200f, Motion.quick()); onClose() }
                            else dy.animateTo(0f, Motion.spring())
                        }
                    },
                ) { ch, d -> ch.consume(); scope.launch { dy.snapTo((dy.value + d).coerceAtMost(40f)) } }
            }
            .pane(shape)
            .clickable(onClick = onOpen)
            .semantics { contentDescription = e.label },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AppGlyph(e, store.iconStyle, 84.dp)
            androidx.compose.foundation.layout.Spacer(Modifier.height(Grid.m))
            VText(e.label, size = Type.body, color = V.White, maxLines = 1, modifier = Modifier.padding(horizontal = Grid.m))
        }
    }
}
