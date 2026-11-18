package com.andysocial.voidos.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The one design system for every VoidOS screen. One font family (the system sans in
 * four weights, see Theme.kt), one type scale, a 4 dp spacing grid, four corner radii and
 * two motions. Screens use these names, not their own numbers, so everything lines up.
 */

/** The type scale. Everything VoidOS writes is one of these sizes. */
object Type {
    val caption = 12.sp      // small print under a value
    val label = 13.sp        // labels, chips, hints
    val body = 15.sp         // rows, lists, buttons
    val headline = 17.sp     // row titles, emphasised body
    val title = 20.sp        // a section's first line
    val title2 = 22.sp       // card headings
    val large = 28.sp        // big values (temperature, timer)
    val display = 34.sp      // the largest non-clock readout
}

/** The spacing grid: every gap and padding is a multiple of 4 dp. */
object Grid {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Corner radii: chips, controls, cards, panels. Nothing else. */
object Radius {
    val chip = 8.dp
    val control = 14.dp
    val card = 20.dp
    val panel = 28.dp
    val Chip = RoundedCornerShape(chip)
    val Control = RoundedCornerShape(control)
    val Card = RoundedCornerShape(card)
    val Panel = RoundedCornerShape(panel)
}

/** The same two motions everywhere: a soft spring for things that move, a quick ease for fades. */
object Motion {
    const val QUICK_MS = 180
    fun <T> spring(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)
    fun <T> quick(): FiniteAnimationSpec<T> = tween(QUICK_MS, easing = FastOutSlowInEasing)
    /** 1.14: the four other motions every screen uses, so nothing moves on its own timing. */
    /** Things that appear or switch on: a little overshoot. */
    fun <T> pop(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.72f, stiffness = 400f)
    /** Under a finger: fast and firm. */
    fun <T> press(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.7f, stiffness = 1400f)
    /** Letting go: springs back with a bounce. */
    fun <T> release(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.5f, stiffness = 500f)
    /** Dimming and fading behind a sheet. */
    fun <T> fade(): FiniteAnimationSpec<T> = tween(260, easing = FastOutSlowInEasing)
    val springOffset: FiniteAnimationSpec<IntOffset> = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)
    val springSize: FiniteAnimationSpec<IntSize> = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)
}
