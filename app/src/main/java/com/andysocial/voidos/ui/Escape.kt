package com.andysocial.voidos.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andysocial.voidos.MainActivity
import com.andysocial.voidos.Policy
import com.andysocial.voidos.Store
import kotlinx.coroutines.delay

/** Four-digit PIN pad. */
@Composable
fun PinPad(title: String, subtitle: String, error: String?, onPin: (String) -> Unit, modifier: Modifier = Modifier) {
    var entry by remember { mutableStateOf("") }
    LaunchedEffect(error) { if (error != null) entry = "" }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        VText(sentence(title), size = 22.sp, color = V.White)
        Gap(h = 6.dp)
        VText(subtitle, size = 13.sp, color = V.Muted, font = FontFamily.Default, align = TextAlign.Center)
        Gap(h = 18.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(4) { i ->
                Box(
                    Modifier.size(22.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (i < entry.length) V.Teal else Color.Transparent)
                        .border(1.5.dp, if (i < entry.length) V.Teal else V.Tick, androidx.compose.foundation.shape.CircleShape)
                )
            }
        }
        Gap(h = 8.dp)
        VText(error ?: " ", size = 12.sp, color = V.Coral, font = FontFamily.Default)
        Gap(h = 8.dp)
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "<")
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier.size(78.dp, 58.dp).clip(OctS)
                            .background(if (k.isEmpty()) Color.Transparent else V.Panel2)
                            .border(1.dp, if (k.isEmpty()) Color.Transparent else V.Line, OctS)
                            .clickable(enabled = k.isNotEmpty()) {
                                if (k == "<") entry = entry.dropLast(1)
                                else if (entry.length < 4) {
                                    entry += k
                                    if (entry.length == 4) { val e = entry; entry = ""; onPin(e) }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (k == "<") Glyph(G.BACK, V.Muted, Modifier.size(22.dp))
                        else VText(k, size = 24.sp, color = V.Text, font = SansLight)
                    }
                }
            }
        }
    }
}

/**
 * The way out. Reached by holding the VoidOS mark for three seconds.
 * Wrong PINs lock the pad for 30 s after five tries.
 */
@Composable
fun EscapeOverlay(store: Store, act: MainActivity, onClose: () -> Unit) {
    var unlocked by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var fails by remember { mutableIntStateOf(0) }
    var lockedUntil by remember { mutableLongStateOf(0L) }
    var confirmRelease by remember { mutableStateOf(false) }
    var released by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(lockedUntil) {
        if (lockedUntil > 0) { delay(lockedUntil - System.currentTimeMillis()); lockedUntil = 0; error = null }
    }

    Box(Modifier.fillMaxSize().background(V.Bg.copy(alpha = 0.97f)).pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }, contentAlignment = Alignment.Center) {
        Box(Modifier.align(Alignment.TopEnd).padding(24.dp)) { VButton("Close", accent = V.Muted, onClick = onClose) }
        if (!unlocked) {
            PinPad(
                title = "Escape hatch",
                subtitle = if (store.hasPin) "Enter your VoidOS PIN" else "No PIN set yet, enter any four digits to set one",
                error = error,
                onPin = { pin ->
                    when {
                        lockedUntil > System.currentTimeMillis() -> error = "Too many tries. Wait 30 seconds."
                        !store.hasPin -> { store.setPin(pin); unlocked = true }
                        store.checkPin(pin) -> { fails = 0; unlocked = true }
                        else -> {
                            fails++
                            if (fails >= 5) { fails = 0; lockedUntil = System.currentTimeMillis() + 30_000; error = "Too many tries. Wait 30 seconds." }
                            else error = "Wrong PIN (${5 - fails} left)"
                        }
                    }
                },
            )
        } else if (released != null) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                VText("Released", size = 26.sp, color = V.Coral)
                Gap(h = 12.dp)
                VText(released!!, size = 14.sp, color = V.Text, font = FontFamily.Default, align = TextAlign.Center, lineHeight = 20.sp)
                Gap(h = 20.dp)
                VButton("Open Android settings", accent = V.Teal, filled = true) { act.openAndroidSettings(); onClose() }
            }
        } else {
            Column(Modifier.widthIn(max = 620.dp).fillMaxWidth().padding(horizontal = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                VText("Escape hatch", size = 24.sp, color = V.White)
                VText("Pick how far out you want to go. Pressing Home always brings you back to VoidOS while it's still device owner.", size = 13.sp, color = V.Muted, font = FontFamily.Default, lineHeight = 18.sp)
                Gap(h = 4.dp)
                EscapeOption("Visit Android settings", "Leaves lock mode for this trip and opens stock Settings.", V.Teal) {
                    act.openAndroidSettings(); onClose()
                }
                EscapeOption("Turn off lock mode", "Stays on VoidOS but brings back the Android bars and lets every app run.", V.Yellow) {
                    store.set("kiosk", false); store.set("hideBars", false); act.applyPolicies(); onClose()
                }
                if (!confirmRelease) {
                    EscapeOption("Release VoidOS completely", "Un-hides every app, drops every rule and removes VoidOS as device owner. You can then uninstall it.", V.Coral) {
                        confirmRelease = true
                    }
                } else {
                    VPanel(accent = V.Coral) {
                        VText("Sure? this hands the tablet back to stock Android.", size = 13.sp, color = V.Coral)
                        Gap(h = 12.dp)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            VButton("Release", accent = V.Coral, filled = true) {
                                act.leaveLockTask()
                                val ok = Policy.release(act)
                                released = if (ok) "VoidOS is no longer device owner. To remove it: Android Settings, Apps, VoidOS, Uninstall. Or from your Mac: adb uninstall com.andysocial.voidos"
                                else "VoidOS wasn't device owner, so there was nothing to release. It's a normal app you can uninstall from Android Settings."
                            }
                            VButton("Cancel", accent = V.Muted) { confirmRelease = false }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EscapeOption(title: String, detail: String, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(Oct).background(V.Panel).border(1.dp, accent.copy(alpha = 0.6f), Oct).clickable(onClick = onClick).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            VText(sentence(title), size = 15.sp, color = accent)
            Gap(h = 4.dp)
            VText(detail, size = 12.sp, color = V.Muted, font = FontFamily.Default, lineHeight = 16.sp)
        }
        Glyph(G.NEXT, accent, Modifier.size(20.dp))
    }
}
