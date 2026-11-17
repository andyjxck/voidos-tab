package com.andysocial.voidos.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.content.ContentUris
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import android.content.Intent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.random.Random

data class Track(val uri: Uri, val title: String, val artist: String, val ms: Long)

/** Plays music files that are on the tablet (Music, Downloads...). One player for the whole app. */
object Player {
    private var mp: MediaPlayer? = null
    val tracks = mutableStateOf<List<Track>>(emptyList())
    val index = mutableIntStateOf(-1)
    val playing = mutableStateOf(false)

    suspend fun scan(ctx: Context) = withContext(Dispatchers.IO) {
        val out = mutableListOf<Track>()
        runCatching {
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION),
                "${MediaStore.Audio.Media.DURATION} > 20000", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE",
            )?.use { c ->
                while (c.moveToNext()) {
                    out += Track(
                        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0)),
                        c.getString(1) ?: "Untitled", c.getString(2)?.takeIf { it != "<unknown>" } ?: "", c.getLong(3),
                    )
                }
            }
        }
        tracks.value = out
    }

    fun play(ctx: Context, i: Int) {
        val list = tracks.value
        if (i !in list.indices) return
        Noise.stop()
        mp?.release()
        mp = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(ctx, list[i].uri)
                setOnCompletionListener { play(ctx, (i + 1) % list.size) }
                prepare(); start()
            }
        }.getOrNull()
        index.intValue = i
        playing.value = mp != null
    }

    fun toggle(ctx: Context) {
        val p = mp
        if (p == null) { if (tracks.value.isNotEmpty()) play(ctx, maxOf(0, index.intValue)); return }
        if (p.isPlaying) { p.pause(); playing.value = false } else { Noise.stop(); p.start(); playing.value = true }
    }

    fun next(ctx: Context) { if (tracks.value.isNotEmpty()) play(ctx, (index.intValue + 1).mod(tracks.value.size)) }
    fun prev(ctx: Context) { if (tracks.value.isNotEmpty()) play(ctx, (index.intValue - 1).mod(tracks.value.size)) }
    fun progress(): Float = runCatching { mp?.let { it.currentPosition.toFloat() / it.duration.coerceAtLeast(1) } ?: 0f }.getOrDefault(0f)
    fun pause() { runCatching { if (mp?.isPlaying == true) mp?.pause() }; playing.value = false }
    fun positionMs(): Long = runCatching { mp?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
    fun durationMs(): Long = runCatching { mp?.duration?.toLong()?.coerceAtLeast(0L) ?: 0L }.getOrDefault(0L)
    fun seekTo(ms: Long) { runCatching { mp?.seekTo(ms.toInt()) } }
    fun skip(by: Long) = seekTo((positionMs() + by).coerceIn(0L, durationMs()))
}

/**
 * Void noise: brown noise made on the tablet (no files, no internet), with an
 * optional slow swell like rain on a window.
 */
object Noise {
    private var track: AudioTrack? = null
    @Volatile private var run = false
    val on = mutableStateOf(false)
    @Volatile var swell = true

    fun start() {
        if (run) return
        Player.pause()
        run = true; on.value = true
        Thread {
            val rate = 22050
            val buf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(buf * 2).build()
            track = t
            t.play()
            val chunk = ShortArray(2048)
            var last = 0.0
            var phase = 0.0
            val rnd = Random(System.nanoTime())
            while (run) {
                for (i in chunk.indices) {
                    last = (last + 0.02 * (rnd.nextDouble() * 2 - 1)) / 1.02
                    phase += 1.0 / (rate * 9.0)
                    val amp = if (swell) 0.75 + 0.25 * kotlin.math.sin(phase * 2 * Math.PI) else 1.0
                    chunk[i] = (last * 3.2 * amp * Short.MAX_VALUE).coerceIn(-32000.0, 32000.0).toInt().toShort()
                }
                t.write(chunk, 0, chunk.size)
            }
            runCatching { t.stop(); t.release() }
            track = null
        }.apply { name = "void-noise"; start() }
    }

    fun stop() { run = false; on.value = false }
}

/** Media keys go to whatever app is playing (Spotify, YouTube Music...). */
private fun mediaKey(ctx: Context, code: Int) {
    val am = ctx.getSystemService(AudioManager::class.java)
    val t = SystemClock.uptimeMillis()
    am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
    am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
}

/**
 * What the Sound screen is showing: an app (YouTube, Spotify...) or a music
 * file on the tablet. Both go through the same card with the same buttons.
 * An app that's playing wins; a local file shows while it's the one in use.
 */
private data class Source(
    val app: String, val title: String, val artist: String, val art: android.graphics.Bitmap?, val playing: Boolean,
    val pos: Long, val dur: Long, val local: Boolean,
)

@Composable
private fun currentSource(tick: Long): Source? {
    val p by com.andysocial.voidos.Now.playing
    val localPlaying by Player.playing
    val tracks by Player.tracks
    val idx = Player.index.intValue
    val app = p?.takeIf { it.title.isNotBlank() }
    val local = tracks.getOrNull(idx)
    @Suppress("UNUSED_EXPRESSION") tick
    return when {
        app != null && (app.playing || !localPlaying) && !(local != null && localPlaying) ->
            Source(app.appName, app.title, app.artist, app.art, app.playing, com.andysocial.voidos.Now.position(app), app.durationMs, local = false)
        local != null -> Source("This tablet", local.title, local.artist, null, localPlaying, Player.positionMs(), Player.durationMs().takeIf { it > 0 } ?: local.ms, local = true)
        app != null -> Source(app.appName, app.title, app.artist, app.art, app.playing, com.andysocial.voidos.Now.position(app), app.durationMs, local = false)
        else -> null
    }
}

@Composable
fun MediaScreen() {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(AudioManager::class.java) }
    val maxVol = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var vol by remember { mutableFloatStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol) }
    val tracks by Player.tracks
    val noiseOn by Noise.on
    var swell by remember { mutableStateOf(Noise.swell) }
    var files by remember { mutableStateOf(false) }
    // look for music files quietly; the list only appears when you open it
    LaunchedEffect(Unit) { Player.scan(ctx) }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            NowPlayingPanel(Modifier.fillMaxWidth().weight(1f), hasFiles = tracks.isNotEmpty(), filesOpen = files) { files = !files }
            // volume and brown noise: one quiet strip
            Row(
                Modifier.fillMaxWidth().clip(OctS).background(V.Panel).border(1.dp, V.Line, OctS).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(G.VOLUME, V.Muted, Modifier.size(22.dp))
                Gap(w = 10.dp)
                VSlider(vol, Modifier.weight(1f), accent = V.Purple) {
                    vol = it
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, (it * maxVol).toInt(), 0)
                }
                Gap(w = 16.dp)
                VButton(if (noiseOn) "Noise off" else "Brown noise", accent = V.Teal, filled = noiseOn) { if (noiseOn) Noise.stop() else Noise.start() }
                if (noiseOn) {
                    Gap(w = 10.dp)
                    VText("SWELL", size = 10.sp, color = V.Muted)
                    Gap(w = 6.dp)
                    VToggle(swell) { swell = it; Noise.swell = it }
                }
            }
        }
        // your own music files: a drawer you open when you want it
        androidx.compose.animation.AnimatedVisibility(
            files && tracks.isNotEmpty(),
            enter = androidx.compose.animation.expandHorizontally() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkHorizontally() + androidx.compose.animation.fadeOut(),
        ) {
            val idx = Player.index.intValue
            VPanel(Modifier.padding(start = 14.dp).width(if (LocalPortrait.current) 260.dp else 320.dp).fillMaxHeight(), padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp)) {
                    Label("Files on the tablet", modifier = Modifier.weight(1f))
                    IconTap(G.CLOSE, V.Muted, 40.dp) { files = false }
                }
                Gap(h = 8.dp)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                    itemsIndexed(tracks, key = { _, t -> t.uri.toString() }) { i, t ->
                        val sel = i == idx
                        Row(
                            Modifier.fillMaxWidth().clip(OctS).background(if (sel) V.Purple.copy(alpha = 0.12f) else V.Panel2)
                                .border(1.dp, if (sel) V.Purple else V.Line, OctS).clickable { Player.play(ctx, i) }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                VText(t.title, size = 14.sp, color = if (sel) V.Purple else V.Text, font = FontFamily.Default, maxLines = 1)
                                if (t.artist.isNotEmpty()) VText(t.artist, size = 11.sp, color = V.Muted, font = FontFamily.Default, maxLines = 1)
                            }
                            val s = t.ms / 1000
                            VText("%d:%02d".format(s / 60, s % 60), size = 11.sp, color = V.Muted)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Whatever is playing: YouTube, Spotify, BBC Sounds, the browser, or a music
 * file on the tablet. Apps need notification access once; until then the
 * buttons send plain media keys.
 */
@Composable
private fun NowPlayingPanel(modifier: Modifier, hasFiles: Boolean, filesOpen: Boolean, onFiles: () -> Unit) {
    val ctx = LocalContext.current
    val connected by com.andysocial.voidos.VoidListener.connected
    val now = rememberNow(500)
    val access = remember(now / 5000) { com.andysocial.voidos.VoidListener.enabled(ctx) }
    val src = currentSource(now)
    VPanel(modifier, accent = V.Purple) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(if (src != null) "Playing on ${src.app}" else "Sound", Modifier.weight(1f))
            if (hasFiles) VButton(if (filesOpen) "Hide files" else "Files", accent = V.Muted) { onFiles() }
        }
        Gap(h = 10.dp)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth > maxHeight * 1.3f
            val art: @Composable (Modifier) -> Unit = { m ->
                Box(m.aspectRatio(1f).clip(Oct).background(V.Panel2).border(1.dp, V.Line, Oct), contentAlignment = Alignment.Center) {
                    val a = src?.art
                    if (a != null) androidx.compose.foundation.Image(a.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    else Glyph(if (src?.local == true) G.NOTES else G.MEDIA, V.Tick, Modifier.fillMaxSize(0.4f))
                }
            }
            val info: @Composable (Modifier) -> Unit = { m ->
                Column(m.verticalScroll(rememberScrollState()), horizontalAlignment = if (wide) Alignment.Start else Alignment.CenterHorizontally) {
                    if (src == null) {
                        VText("NOTHING PLAYING", size = 22.sp, color = V.White)
                        Gap(h = 6.dp)
                        VText(
                            if (access || connected) "Play something in YouTube, Spotify or any app and it shows here."
                            else "Turn on notification access so VoidOS can see and control what's playing in other apps.",
                            size = 14.sp, color = V.Muted, font = FontFamily.Default, lineHeight = 19.sp,
                        )
                        if (!access && !connected) {
                            Gap(h = 10.dp)
                            VButton("Turn on access", accent = V.Purple) {
                                runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }
                        }
                    } else {
                        VText(src.title, size = 26.sp, color = V.White, font = FontFamily.Default, maxLines = 2, lineHeight = 31.sp)
                        if (src.artist.isNotBlank()) VText(src.artist, size = 16.sp, color = V.Muted, font = FontFamily.Default, maxLines = 1)
                        Gap(h = 14.dp)
                        if (src.dur > 0) {
                            VSlider((src.pos.toFloat() / src.dur).coerceIn(0f, 1f), Modifier.fillMaxWidth(), accent = V.Purple) {
                                val to = (it * src.dur).toLong()
                                if (src.local) Player.seekTo(to) else com.andysocial.voidos.Now.seekTo(to)
                            }
                            Row(Modifier.fillMaxWidth()) {
                                VText(fmtDur(src.pos), size = 11.sp, color = V.Muted, modifier = Modifier.weight(1f))
                                VText(fmtDur(src.dur), size = 11.sp, color = V.Muted)
                            }
                        }
                    }
                }
            }
            if (wide) Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                art(Modifier.fillMaxHeight()); Gap(w = 22.dp); info(Modifier.weight(1f))
            } else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                art(Modifier.weight(1f, fill = false).fillMaxWidth(0.7f)); Gap(h = 16.dp); info(Modifier.fillMaxWidth())
            }
        }
        Gap(h = 12.dp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
            val local = src?.local == true
            val live = src != null && !local
            IconTap(G.PREV, V.Text, 56.dp) { if (local) Player.prev(ctx) else if (live) com.andysocial.voidos.Now.prev() else mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
            SmallKey("-10S") { if (local) Player.skip(-10_000) else com.andysocial.voidos.Now.skip(-10_000) }
            IconTap(if (src?.playing == true) G.PAUSE else G.PLAY, V.Purple, 72.dp) {
                if (local) Player.toggle(ctx) else if (live) com.andysocial.voidos.Now.playPause() else mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            }
            SmallKey("+10S") { if (local) Player.skip(10_000) else com.andysocial.voidos.Now.skip(10_000) }
            IconTap(G.NEXT, V.Text, 56.dp) { if (local) Player.next(ctx) else if (live) com.andysocial.voidos.Now.next() else mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT) }
            if (live) { Gap(w = 8.dp); VButton("Open app", accent = V.Purple) { com.andysocial.voidos.Now.open(ctx) } }
        }
    }
}
