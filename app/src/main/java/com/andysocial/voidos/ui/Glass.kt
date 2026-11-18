package com.andysocial.voidos.ui

import androidx.compose.ui.draw.drawBehind

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The glass kit. Android 10 cannot blur what sits behind a view, so "glass"
 * here is drawn: a translucent fill that is lighter at the top, a hairline rim
 * that catches the light along the top edge, and a soft lens for whatever is
 * chosen. Every control that used to be a loose row of rounded buttons is one
 * of these bars now.
 */

/** Bar corners: soft, not fully round, so a long bar still reads as one piece. */
val BarShape = RoundedCornerShape(18.dp)

/** A surface that sits on the page: quieter than [glass], so cards don't shout. */
fun Modifier.pane(shape: Shape = Oct, lift: Float = 1f): Modifier = this
    .clip(shape)
    .drawBehind { glassFill(0.74f, lift * 0.85f) }
    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.03f), Color.White.copy(alpha = 0.07f))), shape)

/** The chosen segment: a teal-tinted lens with a bright rim along its top. */
private fun Modifier.lens(shape: Shape, accent: Color): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.34f), accent.copy(alpha = 0.16f))))
    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.42f), accent.copy(alpha = 0.18f))), shape)

/**
 * One continuous glass bar split into equal segments, for picking one option.
 * The lens slides to the chosen segment. Hairline dividers sit between the
 * others and fade out next to the lens. A [selected] key that is not in the
 * bar shows no lens (time-zone rows, where the choice is in another row).
 */
@Composable
fun GlassSegments(
    options: List<Pair<String, String>>,
    selected: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = V.Teal,
    height: Dp = 50.dp,
    textSize: Float = 15f,
    onPick: (String) -> Unit,
) {
    val idx = options.indexOfFirst { it.first == selected }
    val n = options.size.coerceAtLeast(1)
    BoxWithConstraints(
        modifier.fillMaxWidth().height(height).glass(BarShape, if (enabled) 0.82f else 0.45f),
    ) {
        val segW = maxWidth / n
        val x by animateDpAsState(segW * idx.coerceAtLeast(0), spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow), label = "lens")
        val shown by animateFloatAsState(if (idx >= 0) 1f else 0f, label = "lensOn")
        if (shown > 0.01f) Box(
            Modifier.offset(x = x).width(segW).fillMaxHeight().padding(4.dp)
                .lens(RoundedCornerShape(14.dp), if (enabled) calm(accent) else V.Tick),
        )
        // dividers: hidden either side of the lens
        for (i in 1 until n) {
            val nearLens = idx >= 0 && (i == idx || i == idx + 1)
            val a by animateFloatAsState(if (nearLens) 0f else 1f, label = "div")
            Box(
                Modifier.offset(x = segW * i).width(1.dp).fillMaxHeight().padding(vertical = height * 0.28f)
                    .background(Color.White.copy(alpha = 0.10f * a)),
            )
        }
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, (k, l) ->
                val on = i == idx
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .semantics { this.selected = on; role = Role.Tab }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                            enabled = enabled,
                        ) { onPick(k) },
                    contentAlignment = Alignment.Center,
                ) {
                    VText(
                        l, size = textSize.sp, maxLines = 1, font = if (on) SansMedium else Sans,
                        color = when { !enabled -> V.Tick; on -> V.White; else -> V.Text.copy(alpha = 0.62f) },
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

/** One action in a [GlassActions] bar. */
class GlassAction(val label: String, val glyph: G? = null, val warn: Boolean = false, val enabled: Boolean = true, val active: Boolean = false, val onClick: () -> Unit)

/**
 * Several actions as one glass bar: equal segments, hairline dividers, and a
 * soft light under the finger while it is pressed. With [stacked], the glyph
 * sits over its label (the control centre's bottom bar).
 */
@Composable
fun GlassActions(actions: List<GlassAction>, modifier: Modifier = Modifier, height: Dp = 50.dp, stacked: Boolean = false) {
    Row(modifier.fillMaxWidth().height(height).glass(BarShape, 0.82f)) {
        actions.forEachIndexed { i, a ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = height * 0.26f).background(Color.White.copy(alpha = 0.10f)))
            val src = remember { MutableInteractionSource() }
            val pressed by src.collectIsPressedAsState()
            val glow by animateFloatAsState(if (pressed) 1f else 0f, label = "press")
            val tint = when { !a.enabled -> V.Tick; a.warn -> V.Coral; a.active -> V.Teal; else -> V.Text }
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.10f * glow))
                    .background(if (a.active) V.Teal.copy(alpha = 0.10f) else Color.Transparent)
                    .semantics { role = Role.Button; selected = a.active }
                    .clickable(interactionSource = src, indication = null, enabled = a.enabled, onClick = a.onClick),
                contentAlignment = Alignment.Center,
            ) {
                if (stacked && a.glyph != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Glyph(a.glyph, tint, Modifier.size(22.dp))
                    Gap(h = 5.dp)
                    VText(a.label, size = 12.sp, color = tint.copy(alpha = if (a.warn || a.active) 1f else 0.8f), maxLines = 1)
                } else Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
                    if (a.glyph != null) { Glyph(a.glyph, tint, Modifier.size(18.dp)); Gap(w = 8.dp) }
                    VText(a.label, size = 15.sp, color = tint, maxLines = 1)
                }
            }
        }
    }
}

/**
 * A grouped list in one glass card: rows split by inset hairlines, the way
 * iPad settings group things, instead of a stack of separate boxes.
 */
@Composable
fun GlassGroup(modifier: Modifier = Modifier, content: @Composable GlassGroupScope.() -> Unit) {
    Column(modifier.fillMaxWidth().pane(Oct)) {
        val scope = remember { GlassGroupScope() }
        scope.count = 0
        scope.content()
    }
}

class GlassGroupScope internal constructor() {
    internal var count = 0

    /** One row: optional glyph, title and detail, a trailing control or a chevron when it opens something. */
    @Composable
    fun Item(title: String, detail: String? = null, glyph: G? = null, selected: Boolean = false, chevron: Boolean = false, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
        if (count++ > 0) Box(Modifier.padding(start = if (glyph != null) 54.dp else 18.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))
        Row(
            Modifier.fillMaxWidth()
                .background(if (selected) V.Teal.copy(alpha = 0.10f) else Color.Transparent)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 18.dp, vertical = if (detail != null) 12.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (glyph != null) { Glyph(glyph, if (selected) V.Teal else V.Text.copy(alpha = 0.8f), Modifier.size(20.dp)); Gap(w = 16.dp) }
            Column(Modifier.weight(1f)) {
                VText(sentence(title), size = 16.sp, color = if (selected) V.White else V.Text, maxLines = 2)
                if (detail != null) { Gap(h = 2.dp); VText(detail, size = 13.sp, color = V.Muted, lineHeight = 18.sp) }
            }
            if (trailing != null) { Gap(w = 12.dp); trailing() }
            else if (selected) Glyph(G.CHECK, V.Teal, Modifier.size(20.dp))
            else if (chevron) VText("\u203A", size = 22.sp, color = V.Tick, font = SansLight)
        }
    }
}

/**
 * The room behind every screen: near-black with two very soft glows, teal from
 * the top left and violet from the bottom right, so glass has something to sit
 * on and the screen isn't a flat void. Drawn once; nothing moves.
 */
@Composable
fun Backdrop(modifier: Modifier = Modifier) {
    // the aurora: slow lights in the colours of the screen you're on (Alive.kt)
    Aurora(modifier)
}

/** A thin page heading block: a light title and a quiet line under it. */
@Composable
fun PageTitle(text: String, portrait: Boolean, modifier: Modifier = Modifier) {
    // the title, and just under it a short stroke of the screen's own colour. Drawn below the
    // text's box, not laid out, so pages keep exactly the room they had
    VText(
        text, size = if (portrait) 30.sp else 32.sp, font = SansLight, color = V.White, spacing = (-0.3).sp,
        modifier = modifier.drawBehind {
            val c = Ambience.tint()
            val y = size.height + 2.dp.toPx()
            drawRoundRect(
                Brush.horizontalGradient(listOf(c, c.copy(alpha = 0.15f)), startX = 1.dp.toPx(), endX = 29.dp.toPx()),
                topLeft = androidx.compose.ui.geometry.Offset(1.dp.toPx(), y), size = androidx.compose.ui.geometry.Size(28.dp.toPx(), 3.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f.dp.toPx()),
            )
        },
    )
}

@Suppress("unused")
private val keepImports = listOf(Arrangement.Center, Modifier.widthIn(max = 1.dp))
