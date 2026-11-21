package com.andysocial.voidos.ui

import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 2.0.1, the VoidOS design system's shared pieces. Everything here is the merged Home look:
 * chamfered liquid glass, the old face's dot-and-small-caps header, Manrope, teal for the
 * accent, amber for attention, coral for errors. Screens build from these, not their own boxes.
 */

/** The small caps header every card wears: a coloured dot, then the label in spaced capitals. */
@Composable
fun DotLabel(text: String, dot: Color = V.Teal, modifier: Modifier = Modifier, color: Color = V.Muted) {
    // 2.0.2: an empty label never leaves a lone dot behind
    if (text.isBlank()) return
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
    // on the smallest widgets the name gets the room, the dot steps aside
    val roomy = maxWidth > 150.dp
    val density = androidx.compose.ui.platform.LocalDensity.current
    val tm = androidx.compose.ui.text.rememberTextMeasurer()
    val up = text.uppercase()
    // 2.0.2: the size is picked in composition from the width the header really has and the
    // text is drawn by BasicText. (2.0.1 drew a measured layout by hand, and on the tablet the
    // header could come out as a dot with no name.)
    val maxW = with(density) { (maxWidth - if (roomy) 6.dp + Grid.s else 0.dp).roundToPx() }.coerceAtLeast(1)
    fun st(v: Float) = androidx.compose.ui.text.TextStyle(color = color, fontSize = v.sp, fontFamily = SansMedium, letterSpacing = (v / 11f).sp)
    val (shown, style) = androidx.compose.runtime.remember(up, maxW, color) {
        var found: Pair<String, androidx.compose.ui.text.TextStyle>? = null
        for (cand in listOf(up, up.split(' ', '-', ',').first())) {
            var v = Type.caption.value
            while (v >= 6.5f) {
                if (tm.measure(cand, st(v), maxLines = 1, softWrap = false).size.width <= maxW) { found = cand to st(v); break }
                v -= 0.5f
            }
            if (found != null) break
        }
        found ?: (up.split(' ', '-', ',').first() to st(6.5f))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (roomy) { Box(Modifier.size(6.dp).background(dot, CircleShape)); Box(Modifier.size(Grid.s)) }
        // the whole name is what's read out, even when only its first word fits on screen
        Box(Modifier.weight(1f, fill = false).clearAndSetSemantics { this.text = androidx.compose.ui.text.AnnotatedString(up) }) {
            androidx.compose.foundation.text.BasicText(
                shown, style = style, maxLines = 1, softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
            )
        }
    }
    }
}

/** The one card: chamfered glass, the dot-and-caps header, content under it on the 4 dp grid. */
@Composable
fun VCard(
    label: String?,
    modifier: Modifier = Modifier,
    dot: Color = V.Teal,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    padding: Dp = Grid.l,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.pane(Radius.Card).padding(padding)) {
        if (label != null || trailing != null) {
            Row(Modifier.fillMaxWidth().heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                if (label != null) DotLabel(label, dot, Modifier.weight(1f)) else Box(Modifier.weight(1f))
                trailing?.invoke(this)
            }
            Box(Modifier.size(Grid.m))
        }
        content()
    }
}

/** A chamfered glass button that opens a picker: an optional leading picture, the value, a small chevron. */
@Composable
fun SelectButton(text: String, modifier: Modifier = Modifier, tag: String = "", leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        modifier.height(44.dp).glass(Radius.Control, 0.7f).clickable(onClick = onClick).padding(start = Grid.m, end = Grid.s)
            .then(if (tag.isNotEmpty()) Modifier.testTag(tag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Box(Modifier.size(Grid.s)) }
        VText(text, size = Type.body, color = V.White, font = SansMedium, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
        Box(Modifier.size(Grid.s))
        Glyph(G.CHEV, V.Muted, Modifier.size(14.dp).rotate90())
    }
}

private fun Modifier.rotate90(): Modifier = this.rotate(90f)

/** One option in a picker grid: chamfered, glass when chosen, a leading picture and a name. */
@Composable
fun PickTile(text: String, chosen: Boolean, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        modifier.height(48.dp)
            .then(if (chosen) Modifier.glass(Radius.Control, 0.6f).border(1.dp, V.Teal.copy(alpha = 0.55f), Radius.Control)
                else Modifier.clip(Radius.Control).background(Color.White.copy(alpha = 0.05f)))
            .clickable(onClick = onClick).padding(horizontal = Grid.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Box(Modifier.size(Grid.s + 2.dp)) }
        VText(text, size = Type.body, color = if (chosen) V.White else V.Text, font = SansMedium, maxLines = 1)
    }
}

/**
 * A picker: the screen dims and one chamfered glass panel holds the options, laid out as a
 * wrapping grid so nothing ever runs off the edge. Tap outside to close.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoxScope.PickSheet(visible: Boolean, title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, Modifier.matchParentSize(), enter = fadeIn(Motion.quick()), exit = fadeOut(Motion.quick())) {
        Box(
            Modifier.fillMaxSize().background(V.Bg.copy(alpha = 0.6f))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.padding(Grid.xl).widthIn(max = 640.dp).fillMaxWidth().pane(Radius.Panel)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
                    .padding(Grid.xl).testTag("pick_sheet"),
            ) {
                DotLabel(title)
                Box(Modifier.size(Grid.l))
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Grid.s), verticalArrangement = Arrangement.spacedBy(Grid.s)) { content() }
                }
            }
        }
    }
}

/** A page's header: the title, and one quiet line under it when there is something to say. */
@Composable
fun PageHeader(title: String, portrait: Boolean, sub: String = "", trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            PageTitle(title, portrait)
            if (sub.isNotBlank()) VText(sub, size = Type.caption, color = V.Muted, maxLines = 1)
        }
        trailing?.invoke(this)
    }
}
