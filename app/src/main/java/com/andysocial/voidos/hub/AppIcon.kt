package com.andysocial.voidos.hub

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import com.andysocial.voidos.ui.Sans
import com.andysocial.voidos.ui.V
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * An app's real store icon. Fetched once from the URL the hub sends (the App Store artwork),
 * kept in memory and on disk under cache/hub-icons, so the board, drawers, panel, Notes and
 * Metrics all draw the same picture without going back to the network. Initials only show
 * while the picture loads or when there is none.
 */
object AppIcons {
    private val mem = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** For tests: icons by app id, drawn without any network. */
    internal val preview = mutableMapOf<String, Bitmap>()

    fun cached(url: String): Bitmap? = mem.get(url)

    suspend fun load(ctx: Context, url: String): Bitmap? = withContext(Dispatchers.IO) {
        mem.get(url)?.let { return@withContext it }
        val dir = File(ctx.cacheDir, "hub-icons").apply { mkdirs() }
        val f = File(dir, sha(url) + ".png")
        val bmp = if (f.length() > 0) BitmapFactory.decodeFile(f.path) else runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 10000
            try {
                if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }.let { bytes ->
                    f.writeBytes(bytes)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            } finally { c.disconnect() }
        }.getOrNull()
        bmp?.also { mem.put(url, it) }
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun initials(name: String): String {
        val words = name.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotBlank() && it.lowercase() != "void" }
        val w = words.ifEmpty { name.split(' ').filter { it.isNotBlank() } }
        return w.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "·" }
    }
}

/** The app's icon at [size], in the system's squircle-ish corner (22% like the store), initials while it loads. */
@Composable
fun AppIcon(app: HubApp, size: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val pre = AppIcons.preview[app.id]
    var img by remember(app.icon) { mutableStateOf<ImageBitmap?>((pre ?: app.icon.takeIf { it.isNotBlank() }?.let { AppIcons.cached(it) })?.asImageBitmap()) }
    if (img == null && pre == null && app.icon.isNotBlank()) {
        LaunchedEffect(app.icon) { AppIcons.load(ctx, app.icon)?.let { img = it.asImageBitmap() } }
    }
    val shape = RoundedCornerShape(size * 0.225f)
    val i = img
    if (i != null) {
        Image(i, app.name, modifier.size(size).clip(shape), contentScale = ContentScale.Crop)
    } else {
        Box(modifier.size(size).clip(shape).background(Color(0x1FFFFFFF)), contentAlignment = Alignment.Center) {
            Text(AppIcons.initials(app.name), color = V.Text.copy(alpha = 0.8f), fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.34f).sp)
        }
    }
}
