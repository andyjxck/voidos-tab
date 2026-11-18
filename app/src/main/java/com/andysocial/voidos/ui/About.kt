package com.andysocial.voidos.ui

import android.app.ActivityManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andysocial.voidos.BuildConfig
import com.andysocial.voidos.MainActivity
import com.andysocial.voidos.Policy
import com.andysocial.voidos.Store
import com.andysocial.voidos.Su
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What the About page says about the tablet's startup. Reading the logo
 * partition and Magisk's module list needs root, so [read] blocks and the
 * page calls it off the main thread. The wording is pure and unit-tested.
 */
object Startup {
    /** sha256 of root/logo-voidos.bin (925 736 bytes): the VoidOS power-on logo. */
    const val VOIDOS_LOGO_SHA = "6eb5b5fa33c0383cc02cf976035161c6946bad1c9186da99ebe99e50b7b7524b"
    const val VOIDOS_LOGO_LEN = 925_736
    /** sha256 of the stock S120637_230301 logo's first 914 373 bytes (its header says that's the whole image). */
    const val STOCK_LOGO_SHA = "73b55bdf66637c60a8e71bba11556b2d92f00cf9c78ed8abdfa5c2ff6456f7db"
    const val STOCK_LOGO_LEN = 914_373
    /** The newest boot module: balloon intro, then the bouncing dot, landscape versions. */
    const val CURRENT_MODULE = "3.1"

    enum class Logo { VOIDOS, LENOVO, OTHER, UNKNOWN }

    data class State(
        val logo: Logo,
        /** The boot module's version, or null when it isn't installed (or is switched off in Magisk). */
        val module: String?,
        val shutdownFile: Boolean,
    )

    /** Logo from the two hashes the shell printed (VoidOS-length first, stock-length second). */
    fun logoFrom(out: String?): Logo {
        if (out.isNullOrBlank()) return Logo.UNKNOWN
        val hashes = Regex("[0-9a-f]{64}").findAll(out).map { it.value }.toList()
        if (hashes.size < 2) return Logo.UNKNOWN
        return when {
            hashes[0] == VOIDOS_LOGO_SHA -> Logo.VOIDOS
            hashes[1] == STOCK_LOGO_SHA -> Logo.LENOVO
            else -> Logo.OTHER
        }
    }

    /** version= from a module.prop. */
    fun versionFrom(prop: String?): String? =
        prop?.lineSequence()?.firstOrNull { it.startsWith("version=") }?.substringAfter('=')?.trim()?.ifEmpty { null }

    /** Older than [CURRENT_MODULE]. */
    fun outdated(v: String?): Boolean {
        if (v == null) return false
        fun parts(s: String) = s.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(v); val b = parts(CURRENT_MODULE)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x < y
        }
        return false
    }

    /** The plain lines for the page: label, value. */
    fun lines(s: State): List<Pair<String, String>> = listOf(
        "Startup logo" to when (s.logo) {
            Logo.VOIDOS -> "VoidOS logo"
            Logo.LENOVO -> "Lenovo logo"
            Logo.OTHER -> "A custom logo"
            Logo.UNKNOWN -> "Couldn't check"
        },
        "Startup animation" to if (s.module != null) "VoidOS animation" else "Lenovo animation",
        "Shutdown animation" to if (s.module != null && s.shutdownFile) "VoidOS animation" else "Lenovo animation",
    )

    /** One sentence under the lines, or null when nothing needs saying. */
    fun note(s: State): String? = when {
        s.module != null && outdated(s.module) -> "There's a newer startup animation in your voidos-tab folder. Install it to get it."
        s.logo == Logo.UNKNOWN -> "VoidOS couldn't look at the startup logo. If Magisk asks to allow VoidOS, tap Allow."
        else -> null
    }

    /** Reads it all from the tablet. Blocking. */
    fun read(): State {
        val logo = logoFrom(
            Su.run("p=/dev/block/by-name/logo; head -c $VOIDOS_LOGO_LEN \$p | sha256sum; head -c $STOCK_LOGO_LEN \$p | sha256sum", 15).takeIf { it.ok }?.text,
        )
        val dir = "/data/adb/modules/voidos_boot"
        val mod = Su.run("[ -f $dir/disable ] || [ -f $dir/remove ] || cat $dir/module.prop").takeIf { it.ok }?.text
        val version = versionFrom(mod)
            ?: runCatching { File("/system/etc/voidos_boot.txt").readText().trim().ifEmpty { null } }.getOrNull()
        val shutdown = listOf("/system/media/shutdownanimation.zip", "$dir/system/media/shutdownanimation.zip").any { runCatching { File(it).exists() }.getOrDefault(false) }
            || Su.ok("[ -f $dir/system/media/shutdownanimation.zip ]")
        return State(logo, version, shutdown)
    }
}

/** "Lenovo Tab M10 HD (2nd Gen)" for this tablet; otherwise the maker and model Android reports. */
fun tabletName(maker: String = Build.MANUFACTURER, model: String = Build.MODEL): String = when {
    model.startsWith("TB-X306") -> "Lenovo Tab M10 HD (2nd Gen)"
    model.startsWith(maker, ignoreCase = true) -> model
    else -> "${maker.replaceFirstChar { it.uppercase() }} $model"
}

/**
 * About: VoidOS's own identity and nothing else. No maker's name, no system
 * housekeeping. The system's own build IDs are left exactly as they are; they're
 * simply not shown. The device line is also the hidden way out of VoidOS: tap it
 * twenty times.
 */
@Composable
fun AboutTabletPane(store: Store, act: MainActivity) {
    val ctx = LocalContext.current
    val disk = remember { runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull() }
    val mem = remember { ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it) } }
    val bat = rememberBattery()
    val gb = { b: Long -> "%.0f GB".format(b / 1_000_000_000.0) }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        VoidMark(Modifier.size(64.dp))
        Gap(h = 14.dp)
        VText("VoidOS", size = 30.sp, font = SansLight, color = V.White, spacing = (-0.3).sp)
        VText(BuildConfig.VERSION_NAME, size = 14.sp, color = V.Muted)
    }
    GlassGroup {
        AboutLine("Device", store.hubName.ifBlank { "Void Tab" }, Modifier.pointerInput(Unit) { detectTapGestures { EscapeDoor.tap() } }.testTag("about-device"))
        AboutLine("Storage", disk?.let { "${gb(it.availableBytes)} free of ${gb(it.totalBytes)}" } ?: "—")
        AboutLine("Memory", gb(mem.totalMem))
        AboutLine("Battery", if (bat.pct < 0) "—" else "${bat.pct}%" + if (bat.charging) ", charging" else "")
    }
}

@Composable
private fun AboutLine(k: String, v: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        VText(k, size = 15.sp, color = V.Muted, modifier = Modifier.width(170.dp))
        VText(v, size = 15.sp, color = V.Text, maxLines = 2, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(2.dp))
}
