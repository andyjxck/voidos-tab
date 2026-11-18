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
    /** The boot module version that carries the lava lamp and landscape. */
    const val CURRENT_MODULE = "3.0"

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
            Logo.OTHER -> "Custom (not Lenovo's)"
            Logo.UNKNOWN -> "Couldn't check"
        },
        "Startup animation" to if (s.module != null) "VoidOS animation" else "Lenovo animation",
        "Shutdown animation" to if (s.module != null && s.shutdownFile) "VoidOS animation" else "Lenovo animation",
    )

    /** One sentence under the lines, or null when nothing needs saying. */
    fun note(s: State): String? = when {
        s.module != null && outdated(s.module) -> "There's a newer startup animation (the lava lamp). Install it from the voidos-tab folder to get it."
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
 * About this tablet: what a person wants to know, in plain words. The
 * technical rows sit in a closed section at the bottom. The model line is
 * also the hidden way out of VoidOS: tap it twenty times.
 */
@Composable
fun AboutTabletPane(store: Store, act: MainActivity) {
    val ctx = LocalContext.current
    val disk = remember { runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull() }
    val mem = remember { ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it) } }
    val bat = rememberBattery()
    val gb = { b: Long -> "%.1f GB".format(b / 1_000_000_000.0) }
    var startup by remember { mutableStateOf<Startup.State?>(null) }
    LaunchedEffect(Unit) { startup = withContext(Dispatchers.IO) { Startup.read() } }
    var more by remember { mutableStateOf(false) }

    VText(store.hubName.ifBlank { "VoidOS" }, size = 34.sp, font = SansLight, color = V.White)
    VText("VoidOS ${BuildConfig.VERSION_NAME}", size = 14.sp, color = V.Teal)
    Gap(h = 18.dp)
    GlassGroup {
        AboutLine("Tablet", tabletName(), Modifier.pointerInput(Unit) { detectTapGestures { EscapeDoor.tap() } }.testTag("about-device"))
        AboutLine("VoidOS", "Version ${BuildConfig.VERSION_NAME}")
        AboutLine("Android", "Android ${Build.VERSION.RELEASE}")
        AboutLine("Storage", disk?.let { "${gb(it.availableBytes)} free of ${gb(it.totalBytes)}" } ?: "Couldn't read")
        AboutLine("Battery", if (bat.pct < 0) "Reading…" else "${bat.pct}%" + if (bat.charging) ", charging" else "")
        AboutLine("Memory", "${gb(mem.totalMem)}")
    }
    Gap(h = 22.dp)
    Label("Startup")
    Gap(h = 8.dp)
    val s = startup
    GlassGroup {
        if (s == null) {
            listOf("Startup logo", "Startup animation", "Shutdown animation").forEach { AboutLine(it, "Checking…") }
        } else Startup.lines(s).forEach { (k, v) -> AboutLine(k, v) }
    }
    s?.let { Startup.note(it) }?.let {
        Gap(h = 8.dp)
        VText(it, size = 13.sp, color = V.Muted, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 6.dp))
    }
    Gap(h = 22.dp)
    Row(
        Modifier.fillMaxWidth().tap { more = !more }.padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VText("Technical details", size = 14.sp, color = V.Muted, modifier = Modifier.weight(1f))
        Glyph(G.CHEV, V.Muted, Modifier.size(16.dp).graphicsLayer { rotationZ = if (more) 90f else 0f })
    }
    AnimatedVisibility(more) {
        Column {
            Gap(h = 6.dp)
            val bl = RootInfo.prop("ro.boot.verifiedbootstate")
            val locked = RootInfo.prop("ro.boot.flash.locked")
            GlassGroup {
                AboutLine("Model number", Build.MODEL)
                AboutLine("Software build", Build.DISPLAY)
                AboutLine("Security update", Build.VERSION.SECURITY_PATCH)
                AboutLine("Bootloader", when { locked == "0" || bl == "orange" -> "Unlocked"; locked == "1" -> "Locked"; else -> "Unknown" })
                AboutLine("Root", if (RootInfo.hasSu()) "Magisk" else "No")
                AboutLine("Startup module", s?.module?.let { "Version $it" } ?: if (s == null) "Checking…" else "Not installed")
                AboutLine("In charge of the tablet", if (Policy.isOwner(act)) "VoidOS (device owner)" else "Android")
            }
            Gap(h = 12.dp)
            VText("To hand the tablet back from your Mac:", size = 12.sp, color = V.Muted)
            Gap(h = 4.dp)
            listOf(
                "./adb shell am broadcast -a com.andysocial.voidos.action.UNLOCK -n com.andysocial.voidos/.ReleaseReceiver",
                "./adb shell dpm remove-active-admin com.andysocial.voidos/.VoidAdmin",
                "./adb uninstall com.andysocial.voidos",
            ).forEach { VText(it, size = 11.sp, color = V.Muted, font = FontFamily.Monospace, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 4.dp)) }
        }
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
