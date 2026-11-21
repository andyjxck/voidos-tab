package com.andysocial.voidos.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import com.andysocial.voidos.ui.VCard
import com.andysocial.voidos.ui.Grid
import com.andysocial.voidos.ui.Radius
import com.andysocial.voidos.ui.Glyph
import com.andysocial.voidos.ui.G
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import com.andysocial.voidos.ui.Gap
import com.andysocial.voidos.ui.Label
import com.andysocial.voidos.ui.Oct
import com.andysocial.voidos.ui.SansMedium
import com.andysocial.voidos.ui.Segmented
import com.andysocial.voidos.ui.Type
import com.andysocial.voidos.ui.V
import com.andysocial.voidos.ui.VButton
import com.andysocial.voidos.ui.VField
import com.andysocial.voidos.ui.VText
import com.andysocial.voidos.ui.pane
import com.andysocial.voidos.ui.tap

/** The data sources the aggregator knows, in setup order. */
val HUB_SOURCES = listOf(
    "appstore" to "App Store Connect", "play" to "Google Play", "admob" to "AdMob", "firebase" to "Firebase",
    "crashlytics" to "Crashlytics", "sites" to "Uptime", "brevo" to "Brevo", "github" to "GitHub",
    "billing" to "Google Cloud", "elevenlabs" to "ElevenLabs",
)

/**
 * Settings › Hub, 2.0.1: three chamfered cards on the 4 dp grid. Server (address, token, refresh),
 * Sources (a dot and a time for each), Sites. No explanations: setup lives in docs/hub-setup.md.
 */
@Composable
fun HubSettings() {
    val ctx = LocalContext.current
    HubRepo.init(ctx)
    var url by remember { mutableStateOf(HubRepo.serverUrl) }
    var token by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf<String?>(null) }
    var siteName by remember { mutableStateOf("") }
    var siteUrl by remember { mutableStateOf("") }
    val s = HubRepo.summary
    val stateDot = when { !HubRepo.connected -> V.Tick; HubRepo.error != null -> V.Coral; else -> V.Teal }
    Column(Modifier.fillMaxWidth().testTag("hub_settings"), verticalArrangement = Arrangement.spacedBy(Grid.l)) {
        VCard("Server", dot = stateDot, trailing = {
            VText(when {
                !HubRepo.connected -> "Off"
                HubRepo.error != null -> HubRepo.error ?: ""
                else -> freshness(s).removePrefix("updated ").ifBlank { "Waiting" }
            }, size = Type.caption, color = if (HubRepo.connected && HubRepo.error != null) V.Coral else V.Muted, maxLines = 1)
        }) {
            VField(url, { url = it; saved = null }, Modifier.fillMaxWidth(), placeholder = "Address")
            Gap(h = Grid.s)
            VField(token, { token = it; saved = null }, Modifier.fillMaxWidth(), placeholder = if (HubRepo.hasToken) "Token \u2022\u2022\u2022\u2022\u2022\u2022" else "Token", password = true)
            Gap(h = Grid.m)
            Row(horizontalArrangement = Arrangement.spacedBy(Grid.s), verticalAlignment = Alignment.CenterVertically) {
                VButton("Save", filled = true, enabled = url.trim().startsWith("https://")) {
                    HubRepo.setServer(ctx, url, token.takeIf { it.isNotBlank() }); token = ""; saved = "Saved"
                }
                if (HubRepo.connected) VButton(if (HubRepo.busy) "Refreshing" else "Refresh", enabled = !HubRepo.busy) { HubRepo.refresh(ctx) }
                if (HubRepo.hasToken) VButton("Forget token", accent = V.Coral) { HubRepo.setServer(ctx, url, ""); saved = null }
                saved?.let { VText(it, size = Type.caption, color = V.Teal) }
            }
        }

        VCard("Sources") {
            HUB_SOURCES.forEachIndexed { i, (key, name) ->
                val st = s?.sources?.firstOrNull { it.key == key }
                val on = st != null && st.connected
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(V.Line.copy(alpha = 0.5f)))
                Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(when { !on -> V.Tick; st!!.ok -> V.Teal; else -> V.Coral }, CircleShape))
                    Gap(w = Grid.m)
                    VText(name, size = Type.body, color = if (on) V.Text else V.Muted, maxLines = 1, modifier = Modifier.weight(1f))
                    VText(when {
                        !on -> "\u2014"
                        st!!.ok -> Hub.ago(st.at, System.currentTimeMillis()).removePrefix("updated ")
                        else -> st.error.ifBlank { "Failed" }
                    }, size = Type.caption, color = if (on && !st!!.ok) V.Coral else V.Muted, maxLines = 1, font = SansMedium,
                        modifier = Modifier.widthIn(max = 260.dp))
                }
            }
        }

        VCard("Sites") {
            val sites = HubRepo.manualSites
            sites.forEachIndexed { i, (n, u) ->
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph(G.WEB, V.Teal, Modifier.size(18.dp))
                    Gap(w = Grid.m)
                    Column(Modifier.weight(1f)) {
                        VText(n, size = Type.body, color = V.Text, maxLines = 1)
                        VText(u.removePrefix("https://"), size = Type.caption, color = V.Muted, maxLines = 1)
                    }
                    Box(Modifier.size(36.dp).clip(Radius.Chip).tap { HubRepo.setSites(ctx, sites.filterIndexed { j, _ -> j != i }) }, contentAlignment = Alignment.Center) {
                        Glyph(G.CLOSE, V.Muted, Modifier.size(16.dp))
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(V.Line.copy(alpha = 0.5f)))
            }
            Gap(h = if (sites.isNotEmpty()) Grid.l else Grid.s)
            // 2.0.2: room above and below the inputs, so they don't sit on the card's bottom edge
            Row(Modifier.fillMaxWidth().padding(bottom = Grid.s).testTag("sites_add"), horizontalArrangement = Arrangement.spacedBy(Grid.m), verticalAlignment = Alignment.CenterVertically) {
                VField(siteName, { siteName = it }, Modifier.weight(1f).heightIn(min = 48.dp), placeholder = "Name")
                VField(siteUrl, { siteUrl = it }, Modifier.weight(1.6f).heightIn(min = 48.dp), placeholder = "https://")
                VButton("Add", enabled = siteName.isNotBlank() && siteUrl.trim().startsWith("https://")) {
                    HubRepo.setSites(ctx, sites + (siteName.trim() to siteUrl.trim())); siteName = ""; siteUrl = ""
                }
            }
        }
        Gap(h = Grid.l)
    }
}

/** Settings › Hidden apps: the apps hidden from the tablet, each with Show. */
@Composable
fun HiddenApps() {
    val ctx = LocalContext.current
    HubRepo.init(ctx)
    val hidden = HubRepo.allApps.filter { it.id in HubRepo.hidden }
    Column(Modifier.fillMaxWidth().testTag("hidden_apps")) {
        if (hidden.isEmpty()) { Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) { NotConnected("NONE HIDDEN") }; return@Column }
        VCard("Hidden", dot = V.Amber, trailing = { VText("${hidden.size}", size = Type.caption, color = V.Muted) }) {
            hidden.forEachIndexed { i, a ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(V.Line.copy(alpha = 0.5f)))
                Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (a.kind == HubKind.APP) AppIcon(a, 36.dp) else Glyph(G.WEB, V.Teal, Modifier.size(22.dp))
                    Gap(w = Grid.m)
                    VText(shortName(a.name), size = Type.body, color = V.Text, maxLines = 1, modifier = Modifier.weight(1f))
                    VButton("Show") { HubRepo.hide(a.id, false) }
                }
            }
        }
    }
}
