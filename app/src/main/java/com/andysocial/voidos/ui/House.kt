package com.andysocial.voidos.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Placeholder for the Void Home house panel. Nothing here is connected yet:
 * it just reserves the slot and says so.
 */
@Composable
fun HouseScreen() {
    Split(Modifier.fillMaxSize(), spacing = 20.dp) {
        Box(Modifier.main(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                // a blueprint-ish empty floor plan
                val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                val w = size.width * 0.8f; val h = size.height * 0.7f
                val o = Offset((size.width - w) / 2, (size.height - h) / 2)
                drawRect(V.Green.copy(alpha = 0.6f), o, Size(w, h), style = Stroke(6f))
                drawLine(V.Green.copy(alpha = 0.4f), Offset(o.x + w * 0.55f, o.y), Offset(o.x + w * 0.55f, o.y + h), 4f, pathEffect = dash)
                drawLine(V.Green.copy(alpha = 0.4f), Offset(o.x, o.y + h * 0.5f), Offset(o.x + w * 0.55f, o.y + h * 0.5f), 4f, pathEffect = dash)
                drawLine(V.Green.copy(alpha = 0.4f), Offset(o.x + w * 0.55f, o.y + h * 0.62f), Offset(o.x + w, o.y + h * 0.62f), 4f, pathEffect = dash)
                drawArc(V.Green.copy(alpha = 0.5f), 180f, 90f, true, Offset(o.x + w * 0.2f - 50f, o.y + h - 50f), Size(100f, 100f), style = Stroke(2f))
            }
        }
        VPanel(Modifier.side(380.dp, portraitWeight = 1.1f), accent = V.Green) {
            VText("VOID HOME", size = 24.sp, color = V.Green, spacing = 3.sp)
            Gap(h = 6.dp)
            Label("Not connected yet")
            Gap(h = 18.dp)
            VText(
                "This slot is for your Void Home house: today's jobs, bills due and the floor plan, read from the same Firebase house your iPhone uses.\n\nIt isn't wired up in this version. Nothing here talks to Firebase yet.",
                size = 14.sp, color = V.Text, font = FontFamily.Default, lineHeight = 21.sp,
            )
            Box(Modifier.weight(1f))
            Column(Modifier.fillMaxWidth()) {
                Label("Planned")
                Gap(h = 6.dp)
                listOf("Today's To-Do", "Bills due this week", "Live floor plan").forEach {
                    VText("/  ${it.uppercase()}", size = 13.sp, color = V.Muted, spacing = 1.sp)
                    Gap(h = 4.dp)
                }
            }
        }
    }
}
