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

@Composable
fun MediaScreen() {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(AudioManager::class.java) }
    val maxVol = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var vol by remember { mutableFloatStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol) }
    val tracks by Player.tracks
    val idx = Player.index.intValue
    val playing by Player.playing
    val noiseOn by Noise.on
    var swell by remember { mutableStateOf(Noise.swell) }
    var prog by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Player.scan(ctx) }
    LaunchedEffect(playing) { while (isActive && playing) { prog = Player.progress(); delay(500) } }

    Split(Modifier.fillMaxSize()) {
        Column(Modifier.main(1.4f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            NowPlayingPanel(Modifier.fillMaxWidth().weight(1.2f))
            // the tablet's own music + volume
            VPanel(Modifier.fillMaxWidth().weight(1f), accent = V.Pink) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label("On this tablet")
                        Gap(h = 6.dp)
                        val cur = tracks.getOrNull(idx)
                        VText(cur?.title?.uppercase() ?: if (tracks.isEmpty()) "NO MUSIC FILES FOUND" else "PICK A TRACK", size = 18.sp, color = V.White, maxLines = 2)
                        VText(cur?.artist ?: if (tracks.isEmpty()) "Copy music into the Music folder over USB." else "", size = 12.sp, color = V.Muted, font = FontFamily.Default, maxLines = 2)
                    }
                    IconTap(G.PREV, V.Text, 50.dp) { Player.prev(ctx) }
                    Gap(w = 6.dp)
                    IconTap(if (playing) G.PAUSE else G.PLAY, V.Pink, 60.dp) { Player.toggle(ctx) }
                    Gap(w = 6.dp)
                    IconTap(G.NEXT, V.Text, 50.dp) { Player.next(ctx) }
                }
                Gap(h = 10.dp)
                Canvas(Modifier.fillMaxWidth().height(22.dp)) {
                    val bars = 64
                    for (i in 0 until bars) {
                        val x = size.width * (i + 0.5f) / bars
                        val hgt = size.height * (0.25f + 0.75f * ((i * 7919 % 13) / 13f))
                        drawLine(if (i.toFloat() / bars <= prog) V.Pink else V.Line, Offset(x, (size.height - hgt) / 2), Offset(x, (size.height + hgt) / 2), 5f, StrokeCap.Round)
                    }
                }
                Box(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Glyph(G.VOLUME, V.Muted, Modifier.size(22.dp))
                    Gap(w = 10.dp)
                    VSlider(vol, Modifier.weight(1f), accent = V.Pink) {
                        vol = it
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, (it * maxVol).toInt(), 0)
                    }
                    Gap(w = 16.dp)
                    VButton(if (noiseOn) "Noise off" else "Brown noise", accent = V.Teal, filled = noiseOn) { if (noiseOn) Noise.stop() else Noise.start() }
                    Gap(w = 10.dp)
                    VText("SWELL", size = 10.sp, color = V.Muted)
                    Gap(w = 6.dp)
                    VToggle(swell) { swell = it; Noise.swell = it }
                }
            }
        }
        VPanel(Modifier.side(330.dp, 0.8f), padding = 12.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp)) {
                Label("${tracks.size} tracks", modifier = Modifier.weight(1f))
                VButton("Rescan", accent = V.Muted) { scope.launch { Player.scan(ctx) } }
            }
            Gap(h = 8.dp)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                itemsIndexed(tracks, key = { _, t -> t.uri.toString() }) { i, t ->
                    val sel = i == idx
                    Row(
                        Modifier.fillMaxWidth().clip(OctS).background(if (sel) V.Pink.copy(alpha = 0.12f) else V.Panel2)
                            .border(1.dp, if (sel) V.Pink else V.Line, OctS).clickable { Player.play(ctx, i) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            VText(t.title, size = 14.sp, color = if (sel) V.Pink else V.Text, font = FontFamily.Default, maxLines = 1)
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

/**
 * Whatever is playing in any app: YouTube, Spotify, BBC Sounds, the browser.
 * Needs notification access once; until then the buttons send plain media keys.
 */
@Composable
private fun NowPlayingPanel(modifier: Modifier) {
    val ctx = LocalContext.current
    val p by com.andysocial.voidos.Now.playing
    val connected by com.andysocial.voidos.VoidListener.connected
    val now = rememberNow(500)
    val access = remember(now / 5000) { com.andysocial.voidos.VoidListener.enabled(ctx) }
    VPanel(modifier, accent = V.Purple) {
        Label(if (p != null) "Playing in ${p!!.appName}" else "Playing anywhere")
        Gap(h = 10.dp)
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true).clip(Oct).background(V.Panel2).border(1.dp, V.Line, Oct),
                contentAlignment = Alignment.Center) {
                val art = p?.art
                if (art != null) androidx.compose.foundation.Image(art.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                else Glyph(G.MEDIA, V.Tick, Modifier.size(56.dp))
            }
            Gap(w = 18.dp)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                val np = p
                if (np == null || np.title.isBlank()) {
                    VText("NOTHING PLAYING", size = 20.sp, color = V.White)
                    Gap(h = 4.dp)
                    VText(
                        if (access || connected) "Start something in YouTube, Spotify or any app and it shows here."
                        else "Turn on notification access so VoidOS can see and control what's playing in other apps.",
                        size = 13.sp, color = V.Muted, font = FontFamily.Default, lineHeight = 18.sp,
                    )
                    if (!access && !connected) {
                        Gap(h = 10.dp)
                        VButton("Turn on access", accent = V.Purple) {
                            runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    }
                } else {
                    VText(np.title, size = 22.sp, color = V.White, font = FontFamily.Default, maxLines = 2, lineHeight = 26.sp)
                    VText(np.artist, size = 14.sp, color = V.Muted, font = FontFamily.Default, maxLines = 1)
                    Gap(h = 12.dp)
                    val pos = com.andysocial.voidos.Now.position(np)
                    if (np.durationMs > 0) {
                        VSlider((pos.toFloat() / np.durationMs).coerceIn(0f, 1f), Modifier.fillMaxWidth(), accent = V.Purple) {
                            com.andysocial.voidos.Now.seekTo((it * np.durationMs).toLong())
                        }
                        Row {
                            VText(fmtDur(pos), size = 11.sp, color = V.Muted, modifier = Modifier.weight(1f))
                            VText(fmtDur(np.durationMs), size = 11.sp, color = V.Muted)
                        }
                    }
                }
            }
        }
        Gap(h = 10.dp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val live = p != null
            IconTap(G.PREV, V.Text, 52.dp) { if (live) com.andysocial.voidos.Now.prev() else mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
            SmallKey("-10S") { com.andysocial.voidos.Now.skip(-10_000) }
            IconTap(if (p?.playing == true) G.PAUSE else G.PLAY, V.Purple, 64.dp) { if (live) com.andysocial.voidos.Now.playPause() else mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
            SmallKey("+10S") { com.andysocial.voidos.Now.skip(10_000) }
            IconTap(G.NEXT, V.Text, 52.dp) { if (live) com.andysocial.voidos.Now.next() else mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT) }
            Box(Modifier.weight(1f))
            if (live) VButton("Open app", accent = V.Purple) { com.andysocial.voidos.Now.open(ctx) }
        }
    }
}
