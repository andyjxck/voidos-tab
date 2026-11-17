package com.andysocial.voidos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andysocial.voidos.Note
import com.andysocial.voidos.Store
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Notes: a list on the left, a big writing surface on the right. Saves as you type. */
@Composable
fun NotesScreen(store: Store) {
    var openId by remember { mutableLongStateOf(store.notes.firstOrNull()?.id ?: 0L) }
    val open = store.notes.firstOrNull { it.id == openId }
    var text by remember(openId) { mutableStateOf(open?.text ?: "") }
    var colour by remember(openId) { mutableStateOf(open?.colour ?: 0) }

    // autosave 600 ms after typing stops
    LaunchedEffect(text, colour, openId) {
        delay(600)
        if (openId != 0L && (text != (open?.text ?: "") || colour != (open?.colour ?: 0))) {
            store.saveNote(Note(openId, text, colour, System.currentTimeMillis()))
        }
    }

    Split(Modifier.fillMaxSize()) {
        Column(Modifier.side(300.dp, portraitWeight = 0.38f)) {
            VButton("New note", Modifier.fillMaxWidth(), accent = V.Yellow, filled = true) {
                val id = System.currentTimeMillis()
                store.saveNote(Note(id, "", 0, id))
                openId = id
            }
            Gap(h = 12.dp)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                items(store.notes, key = { it.id }) { n ->
                    val sel = n.id == openId
                    val acc = V.Lap[n.colour % V.Lap.size]
                    Column(
                        Modifier.fillMaxWidth().clip(OctS).background(if (sel) V.Panel2 else V.Panel)
                            .border(1.dp, if (sel) acc else V.Line, OctS).clickable { openId = n.id }.padding(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(OctS).background(acc))
                            Gap(w = 8.dp)
                            Label(SimpleDateFormat("d MMM HH:mm", Locale.UK).format(Date(n.updated)))
                        }
                        Gap(h = 6.dp)
                        VText(n.text.trim().ifEmpty { "Empty note" }, size = 14.sp, color = V.Text, font = FontFamily.Default, maxLines = 2, lineHeight = 18.sp)
                    }
                }
            }
        }
        VPanel(Modifier.main(0.62f), accent = if (open != null) V.Lap[colour % V.Lap.size] else null) {
            if (open == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    VText("PICK A NOTE OR START A NEW ONE", size = 14.sp, color = V.Muted, spacing = 1.sp)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    V.Lap.forEachIndexed { i, c ->
                        Box(
                            Modifier.padding(end = 10.dp).size(26.dp).clip(OctS).background(c.copy(alpha = if (i == colour) 1f else 0.3f))
                                .clickable { colour = i }
                        )
                    }
                    Box(Modifier.weight(1f))
                    IconTap(G.TRASH, V.Coral, size = 44.dp) {
                        store.deleteNote(openId)
                        openId = store.notes.firstOrNull()?.id ?: 0L
                    }
                }
                Gap(h = 14.dp)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TextStyle(color = V.Text, fontSize = 20.sp, lineHeight = 30.sp),
                    cursorBrush = SolidColor(V.Yellow),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                Gap(h = 6.dp)
                Label("${text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }} words / saved on the tablet only")
            }
        }
    }
}

