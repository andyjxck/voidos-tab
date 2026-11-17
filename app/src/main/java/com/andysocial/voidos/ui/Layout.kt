package com.andysocial.voidos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** True when the tablet is held upright. Set once at the root from the window size. */
val LocalPortrait = staticCompositionLocalOf { false }

/**
 * A two-or-more pane layout that runs side by side in landscape and stacks
 * top to bottom in portrait. Panes say how they size with [SplitScope.main]
 * (shares the spare room) or [SplitScope.side] (a fixed width in landscape,
 * a share of the height in portrait).
 */
interface SplitScope {
    val portrait: Boolean
    fun Modifier.main(weight: Float = 1f): Modifier
    fun Modifier.side(width: Dp, portraitWeight: Float = 1f): Modifier
}

@Composable
fun Split(modifier: Modifier = Modifier, spacing: Dp = 16.dp, content: @Composable SplitScope.() -> Unit) {
    if (LocalPortrait.current) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
            val col: ColumnScope = this
            val scope = object : SplitScope {
                override val portrait = true
                override fun Modifier.main(weight: Float) = with(col) { this@main.weight(weight).fillMaxWidth() }
                override fun Modifier.side(width: Dp, portraitWeight: Float) = with(col) { this@side.weight(portraitWeight).fillMaxWidth() }
            }
            scope.content()
        }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(spacing)) {
            val row: RowScope = this
            val scope = object : SplitScope {
                override val portrait = false
                override fun Modifier.main(weight: Float) = with(row) { this@main.weight(weight).fillMaxHeight() }
                override fun Modifier.side(width: Dp, portraitWeight: Float) = this.width(width).fillMaxHeight()
            }
            scope.content()
        }
    }
}

/** The biggest square that fits, centred: for dials that must stay round in either orientation. */
@Composable
fun SquareFit(modifier: Modifier = Modifier, content: @Composable (Dp) -> Unit) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val s = if (maxWidth < maxHeight) maxWidth else maxHeight
        content(s)
    }
}

/** Shorthand for a square modifier of the size SquareFit hands back. */
fun Modifier.square(s: Dp) = this.size(s)
