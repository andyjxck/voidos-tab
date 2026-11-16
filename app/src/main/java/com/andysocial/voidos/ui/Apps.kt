package com.andysocial.voidos.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.andysocial.voidos.MainActivity
import com.andysocial.voidos.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(val pkg: String, val label: String)

/** Small icon cache: icons are drawn at 72 px so 2 GB of RAM isn't wasted on them. */
object Icons {
    private val cache = object : android.util.LruCache<String, ImageBitmap>(80) {}
    suspend fun get(ctx: Context, pkg: String): ImageBitmap? {
        cache.get(pkg)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(72, 72).asImageBitmap() }
                .getOrNull()?.also { cache.put(pkg, it) }
        }
    }
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

@Composable
fun AppsScreen(store: Store) {
    val ctx = LocalContext.current
    var managing by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    val all by produceState(emptyList<AppEntry>(), refresh) { value = withContext(Dispatchers.IO) { launchableApps(ctx) } }
    val shown = if (managing) all else all.filter { it.pkg in store.allowed }
    var msg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(managing) { if (!managing) refresh++ }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                VText(if (managing) "CHOOSE WHAT VOIDOS LETS THROUGH" else "${shown.size} APPS ALLOWED", size = 16.sp, color = V.Text, spacing = 1.sp)
                Gap(h = 4.dp)
                VText(
                    if (managing) "Tap an app to allow or block it. In lock mode, blocked apps can't open at all."
                    else if (store.kiosk) "Lock mode is on: only these apps can open." else "Lock mode is off: this list is just what VoidOS shows.",
                    size = 12.sp, color = V.Muted, font = FontFamily.Default,
                )
            }
            VButton(if (managing) "Done" else "Manage", accent = V.Purple, filled = managing) {
                if (managing) (ctx as? MainActivity)?.applyPolicies()
                managing = !managing
            }
        }
        msg?.let { Gap(h = 8.dp); VText(it, size = 12.sp, color = V.Coral, font = FontFamily.Default) }
        Gap(h = 14.dp)
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                VText("NOTHING ALLOWED YET.\nTAP MANAGE TO LET APPS IN.", size = 15.sp, color = V.Muted, align = TextAlign.Center, spacing = 1.sp, lineHeight = 24.sp)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(132.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(shown, key = { it.pkg }) { app ->
                    val allowed = app.pkg in store.allowed
                    val icon by produceState<ImageBitmap?>(null, app.pkg) { value = Icons.get(ctx, app.pkg) }
                    Column(
                        Modifier.fillMaxWidth().clip(Oct)
                            .background(if (managing && allowed) V.Purple.copy(alpha = 0.14f) else V.Panel)
                            .border(1.dp, if (managing && allowed) V.Purple else V.Line, Oct)
                            .clickable {
                                if (managing) store.setAllowed(app.pkg, !allowed)
                                else {
                                    val i = ctx.packageManager.getLaunchIntentForPackage(app.pkg)
                                    if (i == null) msg = "${app.label} can't be opened."
                                    else runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                        .onFailure { msg = "${app.label} was blocked." }
                                }
                            }
                            .padding(vertical = 16.dp, horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                            icon?.let { Image(it, null, Modifier.size(52.dp)) }
                        }
                        Gap(h = 10.dp)
                        VText(app.label, size = 12.sp, color = V.Text, font = FontFamily.Default, maxLines = 1, align = TextAlign.Center)
                        if (managing) {
                            Gap(h = 6.dp)
                            VText(if (allowed) "ALLOWED" else "BLOCKED", size = 10.sp, color = if (allowed) V.Purple else V.Tick, spacing = 1.sp)
                        }
                    }
                }
            }
        }
    }
}

