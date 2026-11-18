package com.andysocial.voidos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/*
 * Small shared pieces for the calmer screens (Notes, Sound, Apps): round glass
 * buttons with a name for touch-and-talk, a search pill, a back link, the
 * grouped-list row shape, and dates the way people say them.
 */

/** A round glass button with an icon. [active] fills it with the accent, softly. */
@Composable
fun RoundIcon(
    g: G,
    desc: String,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    active: Boolean = false,
    tint: Color = V.Text,
    onClick: () -> Unit,
) {
    Box(
        modifier.size(size)
            .then(if (active) Modifier.droplet(CircleShape, V.Teal) else Modifier.glass(CircleShape, 0.6f))
            .semantics { contentDescription = desc; role = Role.Button }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Glyph(g, if (active) V.Teal else tint, Modifier.size(size * 0.48f)) }
}

/** A grey search pill. */
@Composable
fun SearchPill(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, hint: String = "Search") {
    Row(
        modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(Radius.control)).background(Color.White.copy(alpha = 0.07f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(G.SEARCH, V.Muted, Modifier.size(16.dp))
        Gap(w = 8.dp)
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) VText(hint, size = Type.body, color = V.Muted)
            val focus = androidx.compose.ui.platform.LocalFocusManager.current
            BasicTextField(
                query, onChange, singleLine = true,
                textStyle = TextStyle(color = V.Text, fontSize = 15.sp, fontFamily = Sans),
                cursorBrush = SolidColor(V.Teal), modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focus.clearFocus() }, onDone = { focus.clearFocus() }),
            )
        }
        if (query.isNotEmpty()) Box(Modifier.size(28.dp).tap { onChange("") }, contentAlignment = Alignment.Center) {
            Glyph(G.CLOSE, V.Muted, Modifier.size(14.dp))
        }
    }
}

/** "‹ Notes": the way back from a page opened full screen in portrait. */
@Composable
fun BackLink(text: String, onClick: () -> Unit) {
    Row(
        Modifier.height(42.dp).clip(Pill).clickable(onClick = onClick).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VText("‹", size = Type.large, color = V.Teal, font = SansLight)
        Gap(w = 4.dp)
        VText(text, size = Type.headline, color = V.Teal)
    }
}

/** Row shape in a grouped list: the first and last rows carry the group's corners. */
fun groupShape(first: Boolean, last: Boolean, r: Dp = 16.dp) = RoundedCornerShape(
    topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp,
    bottomStart = if (last) r else 0.dp, bottomEnd = if (last) r else 0.dp,
)

/** The fill behind a grouped-list row. */
val GroupFill = Color(0xFF1C1C1F).copy(alpha = 0.82f)

/** Date buckets for a list, newest first: Today, Yesterday, Previous 7 days, then month names. */
fun dayBucket(t: Long, now: Long = System.currentTimeMillis()): String {
    val d = daysBetween(t, now)
    return when {
        d <= 0 -> "Today"
        d == 1 -> "Yesterday"
        d < 7 -> "Previous 7 days"
        d < 30 -> "Previous 30 days"
        else -> SimpleDateFormat(if (sameYear(t, now)) "MMMM" else "MMMM yyyy", Locale.UK).format(Date(t))
    }
}

/** A list row's date: the time today, the weekday this week, else the date. */
fun shortWhen(t: Long, now: Long = System.currentTimeMillis()): String {
    val d = daysBetween(t, now)
    val pattern = when {
        d <= 0 -> "HH:mm"
        d == 1 -> return "Yesterday"
        d < 7 -> "EEEE"
        else -> "d/M/yy"
    }
    return SimpleDateFormat(pattern, Locale.UK).format(Date(t))
}

/** A page's date line: "18 November 2026 at 02:10". */
fun longWhen(t: Long): String = SimpleDateFormat("d MMMM yyyy 'at' HH:mm", Locale.UK).format(Date(t))

private fun daysBetween(a: Long, b: Long): Int {
    fun day(t: Long) = Calendar.getInstance().apply {
        timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return ((day(b) - day(a)) / 86_400_000L).toInt()
}

private fun sameYear(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR)
}
