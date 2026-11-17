package com.andysocial.voidos.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Timer state lives outside the screen so it keeps running while you look elsewhere. */
object FocusTimer {
    val totalMs = mutableLongStateOf(25 * 60_000L)
    val endAt = mutableLongStateOf(0L)        // 0 = not running
    val pausedLeft = mutableLongStateOf(0L)   // >0 = paused
    val rounds = mutableIntStateOf(0)
    val finished = mutableStateOf(false)

    fun left(now: Long): Long = when {
        endAt.longValue > 0 -> (endAt.longValue - now).coerceAtLeast(0)
        pausedLeft.longValue > 0 -> pausedLeft.longValue
        else -> totalMs.longValue
    }
    val running get() = endAt.longValue > 0
    fun start() { endAt.longValue = System.currentTimeMillis() + left(System.currentTimeMillis()); pausedLeft.longValue = 0; finished.value = false }
    fun pause() { pausedLeft.longValue = left(System.currentTimeMillis()); endAt.longValue = 0 }
    fun reset() { endAt.longValue = 0; pausedLeft.longValue = 0; finished.value = false }
    fun set(min: Int) { reset(); totalMs.longValue = min * 60_000L }
}

@Composable
fun FocusScreen() {
    val now = rememberNow(250)
    val left = FocusTimer.left(now)
    val total = FocusTimer.totalMs.longValue
    val done by FocusTimer.finished

    LaunchedEffect(FocusTimer.endAt.longValue) {
        while (isActive && FocusTimer.running) {
            if (FocusTimer.left(System.currentTimeMillis()) <= 0) {
                FocusTimer.endAt.longValue = 0
                FocusTimer.finished.value = true
                FocusTimer.rounds.intValue++
                runCatching {
                    val tg = ToneGenerator(AudioManager.STREAM_ALARM, 90)
                    repeat(3) { tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 300); delay(600) }
                    tg.release()
                }
                break
            }
            delay(250)
        }
    }

    val portrait = LocalPortrait.current
    Split(Modifier.fillMaxSize(), spacing = 24.dp) {
        SquareFit(Modifier.main()) { side ->
            Canvas(Modifier.size(side)) {
                val c = Offset(size.width / 2, size.height / 2)
                val R = size.minDimension / 2 * 0.92f
                drawOct(c, R, V.Line, width = 2.5f)
                val frac = if (total > 0) left.toFloat() / total else 0f
                drawArc(
                    if (done) V.Green else V.Coral, -90f, 360f * frac, false,
                    Offset(c.x - R * 0.82f, c.y - R * 0.82f), Size(R * 1.64f, R * 1.64f), style = Stroke(14f, cap = StrokeCap.Round),
                )
                for (i in 0 until 60) {
                    val a = Math.toRadians(i * 6.0 - 90)
                    val r1 = R * 0.66f; val r2 = R * (if (i % 5 == 0) 0.60f else 0.63f)
                    drawLine(V.Tick, Offset(c.x + Math.cos(a).toFloat() * r1, c.y + Math.sin(a).toFloat() * r1),
                        Offset(c.x + Math.cos(a).toFloat() * r2, c.y + Math.sin(a).toFloat() * r2), 2f)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val s = (left + 999) / 1000
                VText("%d:%02d".format(s / 60, s % 60), size = 92.sp, color = V.White, font = Jupi)
                VText(
                    when { done -> "DONE. TAKE A BREAK."; FocusTimer.running -> "FOCUSING"; FocusTimer.pausedLeft.longValue > 0 -> "PAUSED"; else -> "READY" },
                    size = 16.sp, color = if (done) V.Green else V.Coral, spacing = 3.sp,
                )
            }
        }
        if (portrait) {
            // portrait: the dial on top, lengths and buttons side by side under it
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally)) {
                Column { FocusLengths(total) }
                Column(Modifier.width(284.dp)) { FocusActions() }
            }
        } else {
            Column(Modifier.side(300.dp), verticalArrangement = Arrangement.Center) {
                FocusLengths(total)
                Gap(h = 16.dp)
                FocusActions()
            }
        }
    }
}

@Composable
private fun FocusLengths(total: Long) {
    Label("Length")
    Gap(h = 10.dp)
    listOf(listOf(5, 15, 25), listOf(45, 60, 90)).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { m ->
                VButton("$m", Modifier.width(88.dp), accent = if (total == m * 60_000L) V.Coral else V.Muted, filled = total == m * 60_000L) { FocusTimer.set(m) }
            }
        }
        Gap(h = 10.dp)
    }
}

@Composable
private fun FocusActions() {
    if (FocusTimer.running) VButton("Pause", Modifier.width(284.dp), accent = V.Yellow, filled = true) { FocusTimer.pause() }
    else VButton(if (FocusTimer.pausedLeft.longValue > 0) "Resume" else "Start", Modifier.width(284.dp), accent = V.Coral, filled = true) { FocusTimer.start() }
    Gap(h = 10.dp)
    VButton("Reset", Modifier.width(284.dp), accent = V.Muted) { FocusTimer.reset() }
    Gap(h = 24.dp)
    Label("Rounds finished since power on")
    Gap(h = 4.dp)
    VText("${FocusTimer.rounds.intValue}", size = 34.sp, color = V.Text, font = Jupi)
    Gap(h = 8.dp)
    VText("The timer keeps running while you use the rest of VoidOS. It beeps three times at the end.", size = 12.sp, color = V.Muted, font = FontFamily.Default, lineHeight = 17.sp)
}
