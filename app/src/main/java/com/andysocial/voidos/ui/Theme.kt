package com.andysocial.voidos.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface

/**
 * The VoidOS palette. Mostly neutral graphite with one accent, the teal of the
 * logo's dot. Coral is kept for warnings. The other colours stay for the things
 * that carry meaning (weather symbols, note colours); screens should not use
 * them as decoration.
 */
object V {
    val Bg = Color(0xFF0B0B0D)
    val Deep = Color(0xFF070708)
    val Panel = Color(0xFF161618)
    val Panel2 = Color(0xFF212124)
    val Line = Color(0xFF2E2E33)
    val Tick = Color(0xFF4A4A50)
    val Text = Color(0xFFF2F2F5)
    val White = Color(0xFFFFFFFF)
    val Muted = Color(0xFF8E8E95)
    val Teal = Color(0xFF52D4DC)      // the logo's dot: the one accent
    val Cyan = Color(0xFF5AC8D2)
    val Coral = Color(0xFFF2667A)     // warnings and destructive actions
    val Red = Color(0xFFE5484D)
    val Green = Color(0xFF4CC864)
    val Yellow = Color(0xFFF0CC4A)
    val Pink = Color(0xFFE47BA4)
    val Purple = Color(0xFFA585E8)
    val Orange = Color(0xFFEA9550)
    val Sun = Color(0xFFFAD278)

    /** Note and widget colours the user can pick. Not for decoration. */
    val Lap = listOf(Coral, Purple, Teal, Green, Yellow, Orange)
}

/**
 * Accent discipline: every control gets the one accent, except warnings, which
 * stay coral, and neutral greys, which stay grey. Lets older call sites keep
 * passing their old per-screen colours without the screen turning into a rainbow.
 */
fun calm(c: Color): Color = when (c) {
    V.Coral, V.Red -> V.Coral
    V.Muted, V.Tick, V.Text, V.White, V.Line -> c
    else -> V.Teal
}

private fun sys(name: String) = FontFamily(Typeface(android.graphics.Typeface.create(name, android.graphics.Typeface.NORMAL)))

// Manrope (The Manrope Project Authors, SIL Open Font License, docs/licenses/Manrope-OFL.txt),
// built into the app in four weights so every screen, the clocks and the wheel use the one
// typeface. Its rounded, open shapes sit better on the liquid glass than a plain grotesque,
// and its figures are tabular, so times and numbers don't jitter as they change.
private fun face(res: Int) = FontFamily(androidx.compose.ui.text.font.Font(res))

/** Body text (Manrope Regular). */
val Sans: FontFamily = face(com.andysocial.voidos.R.font.manrope_regular)

/** Titles, buttons, labels (Manrope SemiBold). */
val SansMedium: FontFamily = face(com.andysocial.voidos.R.font.manrope_semibold)

/** Numbers and large readouts (Manrope Light). */
val SansLight: FontFamily = face(com.andysocial.voidos.R.font.manrope_light)

/** The big clock (Manrope ExtraLight). */
val SansThin: FontFamily = face(com.andysocial.voidos.R.font.manrope_thin)
