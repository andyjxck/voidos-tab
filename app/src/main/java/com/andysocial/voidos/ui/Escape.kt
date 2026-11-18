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
fun PinPad(title: String, subtitle: String, error: String?, onPin: (String) -> Unit, modifier: Modifier = Modifier) =
    GlassPinPad(title, subtitle, error = error, modifier = modifier, onPin = onPin)

/**
 * The only way out of VoidOS on the tablet itself, and deliberately hidden:
 * tap the Device line in Settings, About, twenty times with no long pauses,
 * then enter the fixed code. Nothing on screen counts or hints.
 */
object EscapeDoor {
    val asked = mutableStateOf(false)
    private var taps = 0
    private var last = 0L
    const val TAPS = 20
    private const val GAP_MS = 1500L

    /** One tap on the hidden spot. Returns true on the tap that opens the door. */
    fun tap(now: Long = System.currentTimeMillis()): Boolean {
        taps = if (now - last > GAP_MS) 1 else taps + 1
        last = now
        if (taps >= TAPS) { taps = 0; asked.value = true; return true }
        return false
    }
    fun reset() { taps = 0; last = 0L }
}

/** The way out's code pad. Wrong codes lock it for 30 s after five tries. */
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
            GlassPinPad(
                title = "Enter your VoidOS PIN",
                length = store.pinLength(),
                check = { store.checkEscape(it) },
                onPin = { unlocked = true },
            )
        } else if (released != null) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                VText("Released", size = Type.large, color = V.Coral)
                Gap(h = 12.dp)
                VText(released!!, size = Type.body, color = V.Text, font = Sans, align = TextAlign.Center, lineHeight = 20.sp)
                Gap(h = 20.dp)
                VButton("Open Android settings", accent = V.Teal, filled = true) { act.openAndroidSettings(); onClose() }
            }
        } else {
            Column(Modifier.widthIn(max = 620.dp).fillMaxWidth().padding(horizontal = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                VText("Out of VoidOS", size = Type.large, font = SansLight, color = V.White)
                VText("Pick how far out you want to go. Pressing Home always brings you back to VoidOS while it's still device owner.", size = Type.label, color = V.Muted, font = Sans, lineHeight = 18.sp)
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
                        VText("Sure? this hands the tablet back to stock Android.", size = Type.label, color = V.Coral)
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
        Modifier.fillMaxWidth().liquidGlass(Oct, GlassLook(smoke = 0.7f, depth = 0.6f, stain = accent, stainAlpha = 0.16f)).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            VText(sentence(title), size = Type.body, color = accent)
            Gap(h = 4.dp)
            VText(detail, size = Type.caption, color = V.Muted, font = Sans, lineHeight = 16.sp)
        }
        Glyph(G.NEXT, accent, Modifier.size(20.dp))
    }
}
