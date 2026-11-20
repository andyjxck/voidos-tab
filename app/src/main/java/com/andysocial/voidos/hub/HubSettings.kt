package com.andysocial.voidos.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

/** The data sources the aggregator knows, in setup order, with what each feeds. */
val HUB_SOURCES = listOf(
    "appstore" to "App Store Connect · apps, downloads, sales, payouts, review status, reviews",
    "play" to "Google Play · apps and reviews, if you publish there",
    "admob" to "AdMob · earnings, impressions, eCPM",
    "firebase" to "Firebase Analytics · active users",
    "crashlytics" to "Crashlytics (BigQuery export) · crashes",
    "sites" to "Uptime, SSL and domain checks · your sites",
    "brevo" to "Brevo · bounces and blocks",
    "github" to "GitHub · latest commits and builds",
    "billing" to "Google Cloud billing · spend",
    "elevenlabs" to "ElevenLabs · credit balance",
)

/**
 * Settings › Hub. Whether the hub is linked, the server's address and
 * this tablet's token, the sites to check, and each source's state. Store and API keys are never
 * typed here: they go into the server's secrets (docs/hub-setup.md), so they never sit on the tablet.
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
    Column(Modifier.fillMaxWidth().testTag("hub_settings")) {
        VText("Your store and API keys stay on your own server, never on this tablet. The tablet only reads the one summary the server writes.",
            size = Type.body, color = V.Muted)
        Gap(h = 14.dp)
        Label("Status")
        Gap(h = 6.dp)
        VText(when {
            !HubRepo.connected -> "Not linked yet. Add your server and token below; docs/hub-setup.md has the steps."
            HubRepo.error != null -> "Couldn't reach your server: ${HubRepo.error}"
            else -> freshness(s).ifBlank { "Linked. Waiting for the first summary." }
        }, size = Type.caption, color = if (HubRepo.connected && HubRepo.error != null) V.Coral else V.Muted, maxLines = 2)
        Gap(h = 18.dp)

        Label("Your hub server")
        Gap(h = 6.dp)
        VField(url, { url = it; saved = null }, Modifier.fillMaxWidth(), placeholder = "https://europe-west2-<project>.cloudfunctions.net/hub")
        Gap(h = 8.dp)
        VField(token, { token = it; saved = null }, Modifier.fillMaxWidth(), placeholder = if (HubRepo.hasToken) "Token saved · type to replace" else "Device token (HUB_TOKEN)")
        Gap(h = 8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            VButton("Save", filled = true, enabled = url.isNotBlank()) {
                if (!url.trim().startsWith("https://")) { saved = "The address must start with https://"; return@VButton }
                HubRepo.setServer(ctx, url, token.takeIf { it.isNotBlank() }); token = ""; saved = "Saved"
            }
            if (HubRepo.connected) VButton(if (HubRepo.busy) "Refreshing…" else "Refresh now", enabled = !HubRepo.busy) { HubRepo.refresh(ctx) }
            if (HubRepo.hasToken) VButton("Forget token") { HubRepo.setServer(ctx, url, ""); saved = "Token removed" }
        }
        saved?.let { VText(it, size = Type.caption, color = if (it.startsWith("The")) V.Coral else V.Teal) }
        Gap(h = 4.dp)
        VText("The token is encrypted with a key kept in the tablet's Keystore. The server refreshes every hour, and when the screen comes on if the last refresh was over 10 minutes ago.",
            size = Type.caption, color = V.Muted)
        Gap(h = 18.dp)

        Label("Sources")
        Gap(h = 6.dp)
        Column(Modifier.fillMaxWidth().pane(Oct).padding(16.dp)) {
            HUB_SOURCES.forEach { (key, what) ->
                val st = s?.sources?.firstOrNull { it.key == key }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(when { st == null || !st.connected -> HubStatus.UNKNOWN; st.ok -> HubStatus.FINE; else -> HubStatus.ERRORS }, text = "")
                    Column(Modifier.weight(1f)) {
                        VText(what.substringBefore(" · "), size = Type.body, color = V.Text, maxLines = 1)
                        VText(what.substringAfter(" · "), size = Type.caption, color = V.Muted, maxLines = 1)
                    }
                    VText(when {
                        st == null || !st.connected -> "Not connected"
                        st.ok -> Hub.ago(st.at, System.currentTimeMillis()).removePrefix("updated ")
                        else -> st.error.ifBlank { "Failed" }
                    }, size = Type.caption, color = if (st != null && st.connected && !st.ok) V.Coral else V.Muted, maxLines = 1, font = SansMedium)
                }
            }
        }
        Gap(h = 6.dp)
        VText("To connect a source, add its key to the server, as docs/hub-setup.md in the VoidOS repo explains, then refresh.",
            size = Type.caption, color = V.Muted)
        Gap(h = 18.dp)

        Label("Sites to check")
        Gap(h = 6.dp)
        val sites = HubRepo.manualSites
        if (sites.isEmpty()) VText("No sites yet. Add the Staff Hub and the White Swan site to have them checked.", size = Type.caption, color = V.Muted, maxLines = 2)
        sites.forEachIndexed { i, (n, u) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    VText(n, size = Type.body, color = V.Text, maxLines = 1)
                    VText(u, size = Type.caption, color = V.Muted, maxLines = 1)
                }
                VText("Remove", size = Type.label, color = V.Coral, font = SansMedium,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).tap { HubRepo.setSites(ctx, sites.filterIndexed { j, _ -> j != i }) }.padding(8.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            VField(siteName, { siteName = it }, Modifier.weight(1f), placeholder = "Name")
            VField(siteUrl, { siteUrl = it }, Modifier.weight(1.6f), placeholder = "https://…")
            VButton("Add", enabled = siteName.isNotBlank() && siteUrl.trim().startsWith("https://")) {
                HubRepo.setSites(ctx, sites + (siteName.trim() to siteUrl.trim())); siteName = ""; siteUrl = ""
            }
        }
        Gap(h = 4.dp)
        VText("Sent to your server with each refresh, which checks their uptime, certificate and domain. A sites list in the server's hub/config takes priority.", size = Type.caption, color = V.Muted, maxLines = 3)
        Gap(h = 24.dp)
    }
}

