package com.andysocial.voidos.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andysocial.voidos.PinGuard

private val Letters = mapOf("2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ")

/**
 * VoidOS's PIN pad, built like the iPhone's: a row of dots and round glass
 * keys with their letters. With [check] set it does the whole job itself:
 * a wrong PIN shakes the dots, five wrong in a row lock it for a while
 * (shared across every pad by [PinGuard]), and [onPin] only hears the right
 * one. Without [check], [onPin] hears every entry and the caller shows
 * [error]; bump [shakeKey] to shake.
 */
@Composable
fun GlassPinPad(
    title: String,
    subtitle: String? = null,
    length: Int = 4,
    error: String? = null,
    shakeKey: Int = 0,
    check: ((String) -> Boolean)? = null,
    keySize: Dp = 76.dp,
    modifier: Modifier = Modifier,
    onPin: (String) -> Unit,
) {
    var entry by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var shakes by remember { mutableIntStateOf(0) }
    val shake = remember { Animatable(0f) }
    val now = rememberNow(500)
    val waiting = check != null && PinGuard.waiting(now)

    LaunchedEffect(shakes, shakeKey) {
        if (shakes == 0 && shakeKey == 0) return@LaunchedEffect
        shake.snapTo(0f)
        shake.animateTo(0f, keyframes {
            durationMillis = 420
            -18f at 50; 16f at 110; -12f at 170; 9f at 230; -5f at 290; 2f at 350
        })
    }
    LaunchedEffect(error) { if (error != null) { entry = ""; shakes++ } }

    fun key(k: String) {
        if (waiting) return
        wrong = false
        if (k == "<") { entry = entry.dropLast(1); return }
        if (entry.length >= length) return
        entry += k
        if (entry.length == length) {
            val e = entry
            entry = ""
            if (check == null) onPin(e)
            else if (check(e)) { PinGuard.ok(); onPin(e) }
            else { wrong = true; shakes++; PinGuard.miss() }
        }
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        VText(title, size = 21.sp, color = V.White, align = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            VText(subtitle, size = Type.label, color = V.Muted, align = TextAlign.Center)
        }
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.graphicsLayer { translationX = shake.value * density }.semantics { contentDescription = "${entry.length} of $length digits entered" },
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            repeat(length) { i ->
                val filled by animateFloatAsState(if (i < entry.length) 1f else 0f, Motion.quick(), label = "dot")
                Box(
                    Modifier.size(14.dp).graphicsLayer { val s = 1f + 0.15f * filled; scaleX = s; scaleY = s }
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.16f + 0.84f * filled)),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        val msg = when {
            waiting -> "Try again in ${secs(PinGuard.waitUntil.longValue - now)}"
            wrong -> "Wrong PIN"
            error != null -> error
            else -> " "
        }
        VText(msg, size = Type.label, color = lerpCoral(), align = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        val gap = keySize * 0.3f
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "<")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.padding(vertical = keySize * 0.1f)) {
                row.forEach { k -> PinKey(k, keySize, dim = waiting) { key(k) } }
            }
        }
    }
}

private fun lerpCoral() = androidx.compose.ui.graphics.lerp(V.Coral, Color.White, 0.2f)

private fun secs(ms: Long): String {
    val s = ((ms + 999) / 1000).coerceAtLeast(1)
    return if (s >= 60) "${(s + 59) / 60} min" else "$s s"
}

/** One round glass key. Brightens under the finger, like the iPhone's. */
@Composable
private fun PinKey(k: String, size: Dp, dim: Boolean, onTap: () -> Unit) {
    if (k.isEmpty()) { Box(Modifier.size(size)); return }
    var down by remember { mutableStateOf(false) }
    val lit by animateFloatAsState(if (down) 1f else 0f, tween(if (down) 40 else 260), label = "key")
    val back = k == "<"
    val view = androidx.compose.ui.platform.LocalView.current
    Box(
        Modifier.size(size)
            .semantics(mergeDescendants = true) { role = Role.Button; if (back) contentDescription = "Delete"; onClick { onTap(); true } }
            .graphicsLayer { alpha = if (dim) 0.35f else 1f }
            .then(
                if (back) Modifier.clip(CircleShape)
                else Modifier.liquidGlass(CircleShape, GlassLook(smoke = 0.5f - 0.3f * lit, depth = 0.4f, shine = 0.9f + 0.6f * lit, magnify = 1.18f + 0.2f * lit, edge = 0.35f))
            )
            .pointerInput(dim) {
                detectTapGestures(onPress = { down = true; view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); tryAwaitRelease(); down = false }, onTap = { onTap() })
            },
        contentAlignment = Alignment.Center,
    ) {
        if (back) Glyph(G.BACK, V.Text.copy(alpha = 0.8f), Modifier.size(size * 0.3f))
        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            VText(k, size = (size.value * 0.4f).sp, color = Color.White, font = SansLight)
            Letters[k]?.let { VText(it, size = (size.value * 0.12f).sp, color = V.Text.copy(alpha = 0.7f), spacing = 1.5.sp) }
        }
    }
}
