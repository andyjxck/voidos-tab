package com.andysocial.voidos.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andysocial.voidos.MainActivity
import com.andysocial.voidos.Policy
import com.andysocial.voidos.Store

/** First run: explain device owner status and choose the escape PIN. */
@Composable
fun Setup(store: Store, act: MainActivity) {
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val tick = rememberNow(2000)
    val owner = remember(tick) { Policy.isOwner(act) }

    // landscape: the story on the left, the PIN pad on the right; portrait: stacked
    Split(Modifier.fillMaxSize().padding(if (LocalPortrait.current) 28.dp else 48.dp), spacing = 24.dp) {
        Column(Modifier.main().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
            VText("VOIDOS", size = 54.sp, color = V.White, spacing = 10.sp)
            VText("TAB EDITION", size = 14.sp, color = V.Teal, spacing = 5.sp)
            Gap(h = 28.dp)
            VPanel(Modifier.widthIn(max = 520.dp).fillMaxWidth(), accent = if (owner) V.Green else V.Yellow) {
                Label("Device owner")
                Gap(h = 6.dp)
                VText(if (owner) "YES. VOIDOS CAN RUN THE WHOLE TABLET." else "NOT YET", size = 16.sp, color = if (owner) V.Green else V.Yellow)
                Gap(h = 8.dp)
                VText(
                    if (owner) "Lock mode, hidden extras, update blocking and the Wi-Fi and brightness controls are available."
                    else "VoidOS works as a normal home screen until you run this from your Mac's platform-tools folder:\n\n${Policy.SET_OWNER_CMD}\n\nIt only works while no Google or other account is on the tablet.",
                    size = 13.sp, color = V.Muted, font = FontFamily.Default, lineHeight = 19.sp,
                )
            }
            Gap(h = 18.dp)
            VText(
                "Your PIN is the way out. Hold the VoidOS mark (top left) for three seconds any time, then enter it.",
                size = 13.sp, color = V.Muted, font = FontFamily.Default, lineHeight = 19.sp, modifier = Modifier.widthIn(max = 520.dp),
            )
        }
        Box(Modifier.main(1.1f), contentAlignment = Alignment.Center) {
            PinPad(
                title = if (first == null) "Choose a PIN" else "Once more",
                subtitle = if (first == null) "Four digits for the escape hatch" else "Type the same four digits again",
                error = error,
                onPin = { pin ->
                    if (first == null) { first = pin; error = null }
                    else if (pin == first) {
                        store.setPin(pin)
                        store.set("setupDone", true)
                        act.applyPolicies()
                    } else { first = null; error = "They didn't match. Start again." }
                },
            )
        }
    }
}
