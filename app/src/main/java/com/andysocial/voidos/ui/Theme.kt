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

// Inter (Rasmus Andersson, SIL Open Font License), built into the app so every screen uses it.
private fun inter(res: Int) = FontFamily(androidx.compose.ui.text.font.Font(res))

/** Body text. */
val Sans: FontFamily = inter(com.andysocial.voidos.R.font.inter_regular)

/** Medium weight: titles, buttons, labels. */
val SansMedium: FontFamily = inter(com.andysocial.voidos.R.font.inter_semibold)

/** Light weight: numbers and large readouts. */
val SansLight: FontFamily = inter(com.andysocial.voidos.R.font.inter_light)

/** Thin weight: the big clock. */
val SansThin: FontFamily = inter(com.andysocial.voidos.R.font.inter_thin)
