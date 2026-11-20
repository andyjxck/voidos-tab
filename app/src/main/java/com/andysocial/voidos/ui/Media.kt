package com.andysocial.voidos.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.testTag
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

/**
 * Sound, laid out like a music player rather than a dashboard: one card for
 * whatever is playing (an app such as YouTube or Spotify, or a file on the
 * tablet) with big artwork, the title, a thin progress line, the controls and
 * volume. Your own music files are in a sidebar behind the Files button,
 * which slides in from the right (over the card when the tablet is upright).
 * Brown noise is one quiet row underneath.
 */
@Composable
fun MediaScreen() {
    val ctx = LocalContext.current
    val portrait = LocalPortrait.current
    val tracks by Player.tracks
    var files by remember { mutableStateOf(false) }
    val now = rememberNow(500)
    val src = currentSource(now)
    // look for music files quietly; the list only appears when you open it
    LaunchedEffect(Unit) { Player.scan(ctx) }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    RoundIcon(G.FOLDER, "Files", active = files) { files = !files }
                }
                Gap(h = 16.dp)
                NowPlayingCard(src, tracks.isNotEmpty(), Modifier.fillMaxWidth().weight(1f)) { files = true }
                Gap(h = 12.dp)
                NoiseRow()
            }
            if (!portrait) {
                AnimatedVisibility(
                    files,
                    enter = expandHorizontally() + fadeIn(),
                    exit = shrinkHorizontally() + fadeOut(),
                ) { FilesSidebar(tracks, Modifier.padding(start = 16.dp).width(330.dp).fillMaxHeight()) { files = false } }
            }
        }
        if (portrait) {
            // upright there is no room beside the card: the sidebar slides over it, a tap outside closes it
            AnimatedVisibility(files, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).tap { files = false })
            }
            AnimatedVisibility(
                files, modifier = Modifier.align(Alignment.CenterEnd),
                enter = slideInHorizontally { it } + fadeIn(),
                exit = slideOutHorizontally { it } + fadeOut(),
            ) { FilesSidebar(tracks, Modifier.fillMaxWidth(0.86f).fillMaxHeight()) { files = false } }
        }
    }
}

/** One soft colour from the artwork, for the card's glow. Grey when there's no artwork. */
private fun artTint(a: android.graphics.Bitmap?): Color {
    if (a == null) return Color.Transparent
    return runCatching {
        val px = android.graphics.Bitmap.createScaledBitmap(a, 1, 1, true).getPixel(0, 0)
        val c = Color(px)
        // keep the colour, tame the brightness so the card stays dark
        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(px, it) }
        hsv[1] = hsv[1].coerceAtMost(0.6f); hsv[2] = hsv[2].coerceIn(0.35f, 0.7f)
        if (c.alpha == 0f) Color.Transparent else Color(android.graphics.Color.HSVToColor(hsv))
    }.getOrDefault(Color.Transparent)
}

/**
 * Whatever is playing: YouTube, Spotify, BBC Sounds, the browser, or a music
 * file on the tablet, all with the same card and buttons. Apps need
 * notification access once; until then the buttons send plain media keys.
 */
@Composable
private fun NowPlayingCard(src: Source?, hasFiles: Boolean, modifier: Modifier, onFiles: () -> Unit) {
    val ctx = LocalContext.current
    val connected by com.andysocial.voidos.VoidListener.connected
    val access = remember(System.currentTimeMillis() / 5000) { com.andysocial.voidos.VoidListener.enabled(ctx) }
    val tint = remember(src?.art) { artTint(src?.art) }
    val shape = Radius.Panel
    BoxWithConstraints(
        modifier.liquidGlass(shape, GlassLook(smoke = 0.8f, depth = 1f))
            .background(Brush.radialGradient(listOf(tint.copy(alpha = 0.34f), Color.Transparent), center = Offset(0f, 0f), radius = 1400f))
            .padding(if (LocalPortrait.current) 24.dp else 28.dp),
    ) {
        // The buttons need about 300 dp across and the details about 330 dp down. When the card
        // is too small for that next to (or under) the artwork, as in half the book, the artwork
        // goes and the details scroll instead of being squashed.
        val wideArt = minOf(maxHeight, maxWidth * 0.42f)
        val wide = maxWidth > maxHeight * 1.15f && maxWidth - wideArt - 36.dp >= 300.dp
        val tall = minOf(maxWidth * 0.82f, maxHeight * 0.48f)
        val noArt = !wide && maxHeight - tall - 28.dp < 340.dp && maxHeight < 620.dp
        val artSide = if (wide) wideArt else tall
        val art: @Composable () -> Unit = {
            Box(
                Modifier.size(artSide).clip(Radius.Card).background(Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                val a = src?.art
                if (a != null) androidx.compose.foundation.Image(a.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                else Glyph(G.MEDIA, V.Tick, Modifier.fillMaxSize(0.32f))
            }
        }
        val details: @Composable (Modifier) -> Unit = { m ->
            Column(m, horizontalAlignment = if (wide) Alignment.Start else Alignment.CenterHorizontally) {
                if (src == null) {
                    VText("Not playing", size = Type.large, font = SansMedium, color = V.White)
                    Gap(h = 8.dp)
                    VText(
                        if (access || connected) "Play something in YouTube, Spotify or any app and it shows here."
                        else "Turn on notification access so VoidOS can see and control what's playing in other apps.",
                        size = Type.body, color = V.Muted, lineHeight = 21.sp, align = if (wide) TextAlign.Start else TextAlign.Center,
                    )
                    Gap(h = 16.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!access && !connected) VButton("Turn on access", accent = V.Teal, filled = true) {
                            Thread { com.andysocial.voidos.Stealth.grant(ctx) }.start()
                        }
                        if (hasFiles) VButton("Play your music", accent = V.Teal) { Player.play(ctx, 0); onFiles() }
                    }
                } else {
                    VText(src.title, size = if (wide) 30.sp else 26.sp, font = SansMedium, color = V.White, maxLines = 2, lineHeight = 36.sp,
                        align = if (wide) TextAlign.Start else TextAlign.Center)
                    Gap(h = 4.dp)
                    if (src.artist.isNotBlank()) VText(src.artist, size = Type.title, color = V.Text.copy(alpha = 0.65f), maxLines = 1)
                    if (!src.local) {
                        Gap(h = 4.dp)
                        VText("Open ${src.app} \u203a", size = Type.body, color = V.Teal, maxLines = 1,
                            modifier = Modifier.clip(Pill).clickable { com.andysocial.voidos.Now.open(ctx) }.padding(vertical = 4.dp))
                    }
                }
                Gap(h = 24.dp)
                Progress(src, Modifier.fillMaxWidth())
                Gap(h = 16.dp)
                Controls(src, Modifier.fillMaxWidth())
                Gap(h = 16.dp)
                Volume(Modifier.fillMaxWidth())
            }
        }
        if (wide) Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            art(); Gap(w = 36.dp); details(Modifier.weight(1f))
        } else if (noArt) Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("mediaCompact"),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            details(Modifier.fillMaxWidth().widthIn(max = 560.dp))
        } else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            art(); Gap(h = 28.dp); details(Modifier.fillMaxWidth().widthIn(max = 560.dp))
        }
    }
}

/** A thin line that shows how far along the track is; drag it to move. */
@Composable
private fun Progress(src: Source?, modifier: Modifier) {
    val dur = src?.dur ?: 0L
    val f = if (dur > 0) (src!!.pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(22.dp), contentAlignment = Alignment.CenterStart) {
            GlassChannel(f, if (dur > 0) 1f else 0f)
            if (dur > 0) VSliderTouch(Modifier.matchParentSize()) {
                val to = (it * dur).toLong()
                if (src!!.local) Player.seekTo(to) else com.andysocial.voidos.Now.seekTo(to)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            VText(if (dur > 0) fmtDur(src!!.pos) else "--:--", size = Type.caption, color = V.Muted, modifier = Modifier.weight(1f))
            VText(if (dur > 0) "-" + fmtDur((dur - src!!.pos).coerceAtLeast(0)) else "--:--", size = Type.caption, color = V.Muted)
        }
    }
}

/** Back, back 10 seconds, play or pause, forward 10 seconds, next. Same buttons for every source. */
@Composable
private fun Controls(src: Source?, modifier: Modifier) {
    val ctx = LocalContext.current
    val local = src?.local == true
    val live = src != null && !local
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
        SkipTen("-10", "Back 10 seconds") { if (local) Player.skip(-10_000) else com.andysocial.voidos.Now.skip(-10_000) }
        BareIcon(G.PREV, "Previous", 34.dp) { if (local) Player.prev(ctx) else if (live) com.andysocial.voidos.Now.prev() else mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
        Box(
            Modifier.size(74.dp).clip(OctBtn).background(V.White)
                .semantics { contentDescription = if (src?.playing == true) "Pause" else "Play" }
                .clickable { if (local) Player.toggle(ctx) else if (live) com.andysocial.voidos.Now.playPause() else mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) },
            contentAlignment = Alignment.Center,
        ) { Glyph(if (src?.playing == true) G.PAUSE else G.PLAY, V.Bg, Modifier.size(34.dp)) }
        BareIcon(G.NEXT, "Next", 34.dp) { if (local) Player.next(ctx) else if (live) com.andysocial.voidos.Now.next() else mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT) }
        SkipTen("+10", "Forward 10 seconds") { if (local) Player.skip(10_000) else com.andysocial.voidos.Now.skip(10_000) }
    }
}

@Composable
private fun BareIcon(g: G, desc: String, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size + 22.dp).clip(OctBtn).semantics { contentDescription = desc }.clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Glyph(g, V.White, Modifier.size(size)) }
}

@Composable
private fun SkipTen(text: String, desc: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(OctBtn).semantics { contentDescription = desc }.clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { VText(text, size = Type.body, font = SansMedium, color = V.Text.copy(alpha = 0.7f)) }
}

/** Media volume, with a quiet and a loud speaker at either end. */
@Composable
private fun Volume(modifier: Modifier) {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(AudioManager::class.java) }
    val maxVol = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var vol by remember { mutableFloatStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Glyph(G.VOLUME, V.Muted, Modifier.size(16.dp))
        Gap(w = 12.dp)
        Box(Modifier.weight(1f).height(26.dp), contentAlignment = Alignment.CenterStart) {
            GlassChannel(vol.coerceIn(0f, 1f), 1f)
            VSliderTouch(Modifier.matchParentSize()) {
                vol = it
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (it * maxVol).toInt(), 0)
            }
        }
        Gap(w = 12.dp)
        Glyph(G.VOLUME, V.Text, Modifier.size(22.dp))
    }
}

/** Brown noise made on the tablet: one glass bar like the player above, a round play button, and Steady / Swell once it's on. */
@Composable
private fun NoiseRow() {
    val noiseOn by Noise.on
    var swell by remember { mutableStateOf(Noise.swell) }
    val shape = Pill
    Row(
        Modifier.fillMaxWidth().liquidGlass(shape, GlassLook(smoke = 1.1f)).padding(start = 8.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(OctBtn).background(if (noiseOn) V.Teal.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.07f))
                .tap { if (noiseOn) Noise.stop() else Noise.start() }.semantics { contentDescription = if (noiseOn) "Stop brown noise" else "Play brown noise" },
            contentAlignment = Alignment.Center,
        ) { Glyph(if (noiseOn) G.PAUSE else G.WAVE, if (noiseOn) V.Teal else V.Text, Modifier.size(24.dp)) }
        Gap(w = 14.dp)
        VText("Brown noise", size = Type.headline, color = if (noiseOn) V.White else V.Text, modifier = Modifier.weight(1f), maxLines = 1)
        if (noiseOn) {
            GlassSegments(listOf("steady" to "Steady", "swell" to "Swell"), if (swell) "swell" else "steady", Modifier.width(210.dp), height = 40.dp) {
                swell = it == "swell"; Noise.swell = swell
            }
        }
    }
}

/** Your own music files: title and artist, the one playing in the accent. */
@Composable
private fun FilesSidebar(tracks: List<Track>, modifier: Modifier, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val idx = Player.index.intValue
    val shape = Radius.Panel
    Column(
        modifier.liquidGlass(shape, GlassLook(smoke = 1.25f, depth = 1.2f)).tap { }.padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                VText("Your music", size = Type.title, font = SansMedium, color = V.White)
                VText(if (tracks.size == 1) "1 song" else "${tracks.size} songs", size = Type.caption, color = V.Muted)
            }
            RoundIcon(G.CLOSE, "Close files", size = 36.dp, onClick = onClose)
        }
        Gap(h = 12.dp)
        if (tracks.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                VText("No music files yet", size = Type.headline, color = V.Muted)
                Gap(h = 8.dp)
                VText("Copy songs into the tablet's Music folder and they show up here.", size = Type.label, color = V.Tick, align = TextAlign.Center)
            }
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
            itemsIndexed(tracks, key = { _, t -> t.uri.toString() }) { i, t ->
                val sel = i == idx
                Column(Modifier.fillMaxWidth().clip(Radius.Control).background(if (sel) Color.White.copy(alpha = 0.07f) else Color.Transparent).clickable { Player.play(ctx, i) }) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            VText(t.title, size = Type.body, color = if (sel) V.Teal else V.Text, maxLines = 1)
                            if (t.artist.isNotEmpty()) VText(t.artist, size = Type.caption, color = V.Muted, maxLines = 1)
                        }
                        Gap(w = 8.dp)
                        VText(fmtDur(t.ms), size = Type.caption, color = V.Muted)
                    }
                    if (i < tracks.lastIndex) Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

/** A glass channel filled with clear light up to [f]: a capsule of glass, not a line. */
@Composable
private fun GlassChannel(f: Float, fill: Float) {
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        val h = size.height
        val cr = androidx.compose.ui.geometry.CornerRadius(h / 2)
        drawRoundRect(Color.White.copy(alpha = 0.08f), cornerRadius = cr)
        val fw = (size.width * f).coerceAtLeast(if (f > 0f) h else 0f)
        if (fill > 0f && fw > 0f) drawRoundRect(
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.70f))),
            size = androidx.compose.ui.geometry.Size(fw, h), cornerRadius = cr, alpha = fill,
        )
        drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.Transparent), endY = h * 0.55f), cornerRadius = cr)
    }
}
