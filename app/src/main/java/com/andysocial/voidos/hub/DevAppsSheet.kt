package com.andysocial.voidos.hub

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

@Composable
fun DevAppsGrid(store: Store, modifier: Modifier = Modifier, onLaunched: () -> Unit = {}) {
    val ctx = LocalContext.current
    val list = DevApps.list(store)
    BoxWithConstraints(modifier.glass(RoundedCornerShape(36.dp), 0.7f).tap { }.padding(24.dp).semantics { contentDescription = "Dev Apps" }.testTag("dev_apps")) {
        val cols = when { maxWidth < 420.dp -> 3; maxWidth < 640.dp -> 4; else -> 5 }
        Column {
            Label("Dev Apps")
            Gap(h = 14.dp)
            list.chunked(cols).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { a ->
                        val native = DevApps.native(ctx, a)
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).tap { if (DevApps.launch(ctx, store, a)) onLaunched() }
                                .padding(vertical = 10.dp).semantics(mergeDescendants = true) { contentDescription = a.name + if (native) "" else ", opens in the browser" },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.size(60.dp).glass(CircleShape, 0.55f), contentAlignment = Alignment.Center) {
                                Glyph(DevApps.glyph(a), V.White, Modifier.size(28.dp))
                                if (native) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(8.dp).background(V.Teal, CircleShape))
                            }
                            Gap(h = 6.dp)
                            FitText(a.name, 12.sp, Modifier.fillMaxWidth(), color = V.Text, font = SansMedium, align = Alignment.Center, min = 8.sp)
                        }
                    }
                    repeat(cols - row.size) { Box(Modifier.weight(1f)) }
                }
                Gap(h = 8.dp)
            }
        }
    }
}

/** Settings › Dev Apps: the list, one row each, remove with ×, add a name and a package or web address, or put the defaults back. */
@Composable
fun DevAppsEditor(store: Store) {
    val list = DevApps.list(store)
    var name by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().testTag("dev_apps_editor")) {
        list.forEachIndexed { i, a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(DevApps.glyph(a), V.Muted, Modifier.size(20.dp))
                Gap(w = 10.dp)
                Column(Modifier.weight(1f)) {
                    VText(a.name, size = Type.body, color = V.Text, maxLines = 1)
                    VText(listOf(a.pkg, a.url).filter { it.isNotBlank() }.joinToString(" · "), size = Type.caption, color = V.Muted, maxLines = 2)
                }
                VText("Remove", size = Type.label, color = V.Coral, font = SansMedium,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).tap { store.saveDevApps(DevApps.encode(list.filterIndexed { j, _ -> j != i }).ifEmpty { "-||-" }) }.padding(8.dp))
            }
        }
        Gap(h = 8.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            VField(name, { name = it; problem = null }, Modifier.weight(1f), placeholder = "Name")
            VField(target, { target = it; problem = null }, Modifier.weight(1.6f), placeholder = "Package or web address")
            VButton("Add", filled = true, enabled = name.isNotBlank() && target.isNotBlank()) {
                val e = DevApps.parseEntry(name, target)
                if (e == null) problem = "That isn't a package name (com.example.app) or a web address."
                else { store.saveDevApps(DevApps.encode(list + e)); name = ""; target = "" }
            }
        }
        problem?.let { VText(it, size = Type.caption, color = V.Coral) }
        Gap(h = 8.dp)
        VText("Put back the starting list", size = Type.label, color = V.Teal, font = SansMedium,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).tap { store.saveDevApps("") }.padding(vertical = 8.dp))
    }
}
