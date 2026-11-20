package com.andysocial.voidos.hub

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.andysocial.voidos.ui.G
import com.andysocial.voidos.ui.Glyph
import com.andysocial.voidos.ui.V
import com.andysocial.voidos.ui.WheelItem

/** The rows of 2.0's edge drawers: metrics on the left, apps and sites on the right. */
object HubDrawers {
    fun glyph(m: Metric): G = when (m) {
        Metric.REVENUE -> G.MONEY
        Metric.DOWNLOADS -> G.DOWNLOAD
        Metric.USERS -> G.CHART
        Metric.ADS -> G.ADS
        Metric.IMPRESSIONS -> G.ADS
        Metric.ECPM -> G.MONEY
        Metric.CRASHES -> G.BUG
        Metric.REVIEWS -> G.REVIEW
        Metric.RATING -> G.STAR
        Metric.UPTIME -> G.UPTIME
        Metric.BOUNCES -> G.MAIL
    }

    fun metricItems(): List<WheelItem> = Metric.entries.map { item(it.name, it.label, glyph(it), V.Text) }

    /** "All apps" first, then the store apps, then the sites. Empty until the hub has a summary. */
    /** What the right drawer says before anything is linked, the same words as the other empty states. */
    fun emptyText(s: HubSummary?): String = if (s == null) "Nothing linked yet" else "No apps linked yet"

    fun appItems(s: HubSummary?): List<WheelItem> {
        if (s == null || s.apps.isEmpty()) return emptyList()
        return listOf(item(Hub.ALL, "All apps", G.APPS, V.Text)) +
            s.apps.sortedBy { if (it.kind == HubKind.APP) 0 else 1 }.map {
                item(it.id, it.name, if (it.kind == HubKind.SITE) G.WEB else G.STORE, HubLook.colour(it.glow).takeIf { c -> c != V.Tick } ?: V.Text)
            }
    }

    /** A row: the glyph, tinted by the drawer (the chosen row) or, for apps, by the app's status glow. */
    private fun item(key: String, label: String, g: G, tint: Color) =
        WheelItem(key, label) { m: Modifier, c: Color -> Glyph(g, if (tint == V.Text) c else tint, m) }
}
