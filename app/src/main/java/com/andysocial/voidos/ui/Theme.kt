package com.andysocial.voidos.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.andysocial.voidos.R

/** The VoidOS palette, lifted from the PineTime firmware (ui_face.c / app.c). */
object V {
    val Bg = Color(0xFF080A12)        // FACE_BG  rgb(8,10,18)
    val Deep = Color(0xFF06080E)
    val Panel = Color(0xFF0E1320)
    val Panel2 = Color(0xFF151C2C)
    val Line = Color(0xFF2A3344)      // dial ticks
    val Tick = Color(0xFF464E60)      // FACE_TICK rgb(70,78,96)
    val Text = Color(0xFFD8E0EC)      // minute hand / date
    val White = Color(0xFFFFFFFF)
    val Muted = Color(0xFF8A94A8)     // "STEPS" label grey
    val Teal = Color(0xFF46D8E0)      // steps / accent
    val Cyan = Color(0xFF5AC8D2)      // seconds hand
    val Coral = Color(0xFFF0586E)     // heart rate
    val Red = Color(0xFFE63C3C)       // heart
    val Green = Color(0xFF3CC850)
    val Yellow = Color(0xFFEBC828)
    val Pink = Color(0xFFE66C9B)
    val Purple = Color(0xFF9B6CE6)
    val Orange = Color(0xFFE68C3C)
    val Sun = Color(0xFFFAD278)

    /** The lap-ring colour cycle from the digital watch face. */
    val Lap = listOf(Coral, Purple, Teal, Green, Yellow, Orange)
}

/** Kenney Future: every label and digit on the watch. CC0. */
val Kenney = FontFamily(Font(R.font.kenney_future))

/** Jupiteroid: the watch face numerals. CC0. */
val Jupi = FontFamily(Font(R.font.jupiteroid))
