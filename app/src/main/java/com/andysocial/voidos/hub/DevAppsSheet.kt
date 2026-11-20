package com.andysocial.voidos.hub

import com.andysocial.voidos.ui.OctBtn
import com.andysocial.voidos.ui.Pill
import androidx.compose.ui.graphics.asImageBitmap
import com.andysocial.voidos.ui.DotLabel
import com.andysocial.voidos.ui.VCard
import com.andysocial.voidos.ui.Grid
import com.andysocial.voidos.ui.pane
import com.andysocial.voidos.ui.Radius
import com.andysocial.voidos.ui.G
import com.andysocial.voidos.ui.Chamfer
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.andysocial.voidos.Store
import com.andysocial.voidos.ui.FitText
import com.andysocial.voidos.ui.Gap
import com.andysocial.voidos.ui.Glyph
import com.andysocial.voidos.ui.Label
import com.andysocial.voidos.ui.SansMedium
import com.andysocial.voidos.ui.SystemScrim
import com.andysocial.voidos.ui.Type
import com.andysocial.voidos.ui.V
import com.andysocial.voidos.ui.VButton
import com.andysocial.voidos.ui.VField
import com.andysocial.voidos.ui.VText
import com.andysocial.voidos.ui.glass
import com.andysocial.voidos.ui.tap

/** Whether Dev Apps is showing (the bottom-edge swipe sets this), and whether it was opened over an app. */
object DevAppsNav {
    val shown = mutableStateOf(false)
    val fromApp = mutableStateOf(false)
}

/**
 * 2.0's Dev Apps, up from the bottom edge where the app switcher was: the work apps, a tile each,
 * native when installed (a teal dot) or the web console in the browser. Everything else stays
 * hidden from VoidOS.
 */
@Composable
fun DevAppsSheet(store: Store) {
    val shown by DevAppsNav.shown
    BackHandler(enabled = shown) { DevAppsNav.shown.value = false }
    AnimatedVisibility(shown, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().tap { DevAppsNav.shown.value = false }) { SystemScrim() }
    }
    AnimatedVisibility(shown, enter = slideInVertically { it / 6 } + fadeIn(), exit = slideOutVertically { it / 6 } + fadeOut()) {
        BoxWithConstraints(Modifier.fillMaxSize().tap { DevAppsNav.shown.value = false }, contentAlignment = Alignment.BottomCenter) {
            DevAppsGrid(store, Modifier.widthIn(max = 980.dp).width(min(maxWidth - 32.dp, 980.dp)).padding(bottom = 28.dp)) { DevAppsNav.shown.value = false }
        }
    }
}

/** An installed app's own launcher icon, or the glyph on glass when it's a web console. */
@Composable
fun DevAppIcon(a: DevApp, size: androidx.compose.ui.unit.Dp) {
    val ctx = LocalContext.current
    val icon = remember(a.pkg) {
        if (a.pkg.isBlank()) null else runCatching {
            val d = ctx.packageManager.getApplicationIcon(a.pkg)
            val px = (size.value * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(48)
            val b = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(b); d.setBounds(0, 0, px, px); d.draw(c)
            b.asImageBitmap()
        }.getOrNull()
    }
    if (icon != null) androidx.compose.foundation.Image(icon, a.name, Modifier.size(size).clip(Chamfer(Radius.control)))
    else Box(Modifier.size(size).glass(Chamfer(Radius.control), 0.6f), contentAlignment = Alignment.Center) {
        Glyph(DevApps.glyph(a), V.White, Modifier.size(size * 0.46f))
    }
}

/** 2.0.1: one chamfered glass panel, the dot-and-caps header, tiles of real icons on an even grid. */
@Composable
fun DevAppsGrid(store: Store, modifier: Modifier = Modifier, onLaunched: () -> Unit = {}) {
    val ctx = LocalContext.current
    val list = DevApps.list(store)
    BoxWithConstraints(modifier.pane(Chamfer(Radius.panel)).tap { }.padding(Grid.xl).semantics { contentDescription = "Dev Apps" }.testTag("dev_apps")) {
        val cols = when { maxWidth < 420.dp -> 4; maxWidth < 640.dp -> 5; else -> 6 }
        Column {
            DotLabel("Dev Apps")
            Gap(h = Grid.l)
            list.chunked(cols).forEachIndexed { ri, row ->
                if (ri > 0) Gap(h = Grid.m)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Grid.m)) {
                    row.forEach { a ->
                        Column(
                            Modifier.weight(1f).clip(Chamfer(Radius.card)).tap { if (DevApps.launch(ctx, store, a)) onLaunched() }
                                .padding(vertical = Grid.s).semantics(mergeDescendants = true) { contentDescription = a.name },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            DevAppIcon(a, 56.dp)
                            Gap(h = Grid.s)
                            FitText(a.name, 12.sp, Modifier.fillMaxWidth(), color = V.Text, font = SansMedium, align = Alignment.Center, min = 9.sp)
                        }
                    }
                    repeat(cols - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Settings › Dev Apps, 2.0.1: one card of rows (icon, name, a small ×), an add row, and Reset. */
@Composable
fun DevAppsEditor(store: Store) {
    val list = DevApps.list(store)
    var name by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("dev_apps_editor"), verticalArrangement = Arrangement.spacedBy(Grid.l)) {
        VCard("Dev Apps", trailing = {
            VText("Reset", size = Type.label, color = V.Teal, font = SansMedium,
                modifier = Modifier.clip(Radius.Chip).tap { store.saveDevApps("") }.padding(horizontal = Grid.s, vertical = Grid.xs))
        }) {
            list.forEachIndexed { i, a ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(V.Line.copy(alpha = 0.5f)))
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    DevAppIcon(a, 34.dp)
                    Gap(w = Grid.m)
                    Column(Modifier.weight(1f)) {
                        VText(a.name, size = Type.body, color = V.Text, maxLines = 1)
                        VText(a.pkg.ifBlank { a.url.removePrefix("https://") }, size = Type.caption, color = V.Muted, maxLines = 1)
                    }
                    Box(Modifier.size(36.dp).clip(Radius.Chip).tap { store.saveDevApps(DevApps.encode(list.filterIndexed { j, _ -> j != i }).ifEmpty { "-||-" }) }, contentAlignment = Alignment.Center) {
                        Glyph(G.CLOSE, V.Muted, Modifier.size(16.dp))
                    }
                }
            }
        }
        VCard("Add") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Grid.s), verticalAlignment = Alignment.CenterVertically) {
                VField(name, { name = it; bad = false }, Modifier.weight(1f), placeholder = "Name")
                VField(target, { target = it; bad = false }, Modifier.weight(1.6f), placeholder = "Package or address")
                VButton("Add", filled = true, enabled = name.isNotBlank() && target.isNotBlank()) {
                    val e = DevApps.parseEntry(name, target)
                    if (e == null) bad = true else { store.saveDevApps(DevApps.encode(list + e)); name = ""; target = "" }
                }
            }
            if (bad) { Gap(h = Grid.s); VText("Not valid", size = Type.caption, color = V.Coral) }
        }
    }
}
