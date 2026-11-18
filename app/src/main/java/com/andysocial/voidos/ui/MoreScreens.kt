package com.andysocial.voidos.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.RingtoneManager
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.andysocial.voidos.Cast
import com.andysocial.voidos.Extras
import com.andysocial.voidos.Policy
import com.andysocial.voidos.Share
import com.andysocial.voidos.Store
import com.andysocial.voidos.Su
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun bgRun(block: () -> Unit) = Thread { runCatching(block) }.apply { isDaemon = true; start() }

/** A quiet line of status text under a pane's controls. */
@Composable
private fun Note(text: String, color: Color = V.Muted) = VText(text, size = Type.body, color = color)

// =================================================================== Files

/** Where files live: the tablet, then any microSD card or USB drive that's in. */
fun storageRoots(): List<Pair<String, File>> {
    val list = mutableListOf("Tablet" to android.os.Environment.getExternalStorageDirectory())
    File("/storage").listFiles()?.filter { it.name != "emulated" && it.name != "self" && it.isDirectory }?.forEach { list += "Card or drive (${it.name})" to it }
    File("/mnt/media_rw").listFiles()?.filter { f -> list.none { it.second.name == f.name } }?.forEach { list += "USB drive (${it.name})" to it }
    return list
}

private fun mimeOf(f: File) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"

fun openFile(ctx: Context, store: Store, f: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val target = i.resolveActivity(ctx.packageManager)?.packageName
        if (target == null) { toast.value = "No app here opens .${f.extension} files."; return }
        if (target !in store.allowed && target != "android") store.setAllowed(target, true)
        ctx.startActivity(i)
    }.onFailure { toast.value = "That file can't be opened." }
}

/**
 * Files: the tablet's storage, a microSD card and USB drives, as a plain list.
 * Tap a folder to go in, a file to open it. Hold to pick files, then send them
 * to a phone (Share) or delete them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesPane(store: Store, pickOnly: Boolean = false, onSend: (List<File>) -> Unit) {
    val ctx = LocalContext.current
    val roots = remember { storageRoots() }
    var dir by remember { mutableStateOf(roots.first().second) }
    var refresh by remember { mutableIntStateOf(0) }
    val picked = remember { mutableStateListOf<File>() }
    var confirmDelete by remember { mutableStateOf(false) }
    var listing by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            if (ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                Policy.grantSelf(ctx, Manifest.permission.READ_EXTERNAL_STORAGE); Policy.grantSelf(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                refresh++
            }
        }
    }
    LaunchedEffect(dir, refresh) {
        listing = withContext(Dispatchers.IO) {
            val l = dir.listFiles()?.toList()
            // a card or drive Android won't let apps read directly: ask root for the names
            l ?: Su.run("ls -1a ${Su.q(dir.absolutePath)}").text.lines().filter { it.isNotBlank() && it != "." && it != ".." }.map { File(dir, it) }
        }.filter { !it.name.startsWith(".") }.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Grid.s), verticalAlignment = Alignment.CenterVertically) {
            val root = roots.firstOrNull { dir.absolutePath.startsWith(it.second.absolutePath) }
            if (root != null && dir != root.second) RoundIcon(G.BACK, "Up", size = 40.dp) { dir.parentFile?.let { dir = it } }
            roots.forEach { (name, f) ->
                val on = root?.second == f
                VText(name, size = Type.body, color = if (on) V.Bg else V.Text, font = if (on) SansMedium else Sans,
                    modifier = Modifier.clip(Pill).background(if (on) V.Teal else Color.White.copy(alpha = 0.06f)).tap { dir = f; picked.clear() }
                        .padding(horizontal = Grid.l, vertical = Grid.s))
            }
        }
        Gap(h = Grid.s)
        val rel = roots.firstOrNull { dir.absolutePath.startsWith(it.second.absolutePath) }?.let { dir.absolutePath.removePrefix(it.second.absolutePath).trim('/') }.orEmpty()
        if (rel.isNotEmpty()) VText(rel.replace("/", "  ›  "), size = Type.label, color = V.Muted, maxLines = 1)
        Gap(h = Grid.s)
        if (listing.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Note("Nothing in here") }
        else LazyColumn(Modifier.weight(1f)) {
            items(listing, key = { it.absolutePath }) { f ->
                val sel = f in picked
                Row(
                    Modifier.fillMaxWidth().clip(Radius.Card).background(if (sel) V.Teal.copy(alpha = 0.16f) else Color.Transparent)
                        .combinedClickable(
                            onClick = {
                                when {
                                    picked.isNotEmpty() || pickOnly -> if (f.isDirectory) dir = f else if (sel) picked.remove(f) else picked.add(f)
                                    f.isDirectory -> dir = f
                                    else -> openFile(ctx, store, f)
                                }
                            },
                            onLongClick = { if (!f.isDirectory) { if (sel) picked.remove(f) else picked.add(f) } },
                        ).padding(horizontal = Grid.m, vertical = Grid.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph(if (f.isDirectory) G.FOLDER else iconFor(f), if (f.isDirectory) V.Teal else V.Muted, Modifier.size(24.dp))
                    Gap(w = Grid.m)
                    Column(Modifier.weight(1f)) {
                        VText(f.name, size = Type.body, color = V.Text, maxLines = 1)
                        if (!f.isDirectory) VText(Share.size(f.length()) + "  ·  " + SimpleDateFormat("d MMM yyyy", Locale.UK).format(Date(f.lastModified())), size = Type.label, color = V.Muted, maxLines = 1)
                    }
                    if (sel) Glyph(G.CHECK, V.Teal, Modifier.size(20.dp))
                }
            }
        }
        if (picked.isNotEmpty()) {
            Gap(h = Grid.s)
            if (confirmDelete) GlassActions(listOf(
                GlassAction("Delete ${picked.size}", warn = true) {
                    val list = picked.toList(); picked.clear(); confirmDelete = false
                    bgRun { list.forEach { if (!it.deleteRecursively()) Su.run("rm -rf ${Su.q(it.absolutePath)}") }; refresh++ }
                },
                GlassAction("Keep") { confirmDelete = false },
            ), Modifier.widthIn(max = 520.dp))
            else GlassActions(listOfNotNull(
                GlassAction(if (pickOnly) "Add ${picked.size}" else "Send ${picked.size} to a phone", G.SEND) { onSend(picked.toList()); picked.clear() },
                if (pickOnly) null else GlassAction("Delete", G.TRASH, warn = true) { confirmDelete = true },
                GlassAction("Clear") { picked.clear() },
            ), Modifier.widthIn(max = 620.dp))
        }
    }
}

private fun iconFor(f: File): G = when (f.extension.lowercase()) {
    "jpg", "jpeg", "png", "gif", "webp", "heic" -> G.CAMERA
    "mp4", "mkv", "webm", "mov", "avi" -> G.VIDEO
    "mp3", "m4a", "flac", "wav", "ogg", "aac" -> G.MEDIA
    "pdf", "doc", "docx", "txt", "md", "odt" -> G.DOC
    "apk" -> G.APPS
    else -> G.FILES
}

// =================================================================== Share (Wi-Fi Direct)

/**
 * Send files to a phone over Wi-Fi Direct: the tablet makes its own network,
 * the phone scans the first code to join it and the second to open the page
 * with your files. Phones can send files back from the same page.
 */
@Composable
fun SharePane(store: Store) {
    val ctx = LocalContext.current
    var files by remember { mutableStateOf(Share.files) }
    var picking by remember { mutableStateOf(false) }
    var group by remember { mutableStateOf<Share.Group?>(null) }
    var state by remember { mutableStateOf(if (Share.running) "on" else "off") }
    var addrs by remember { mutableStateOf(Share.addresses()) }
    var received by remember { mutableStateOf(Share.received) }
    LaunchedEffect(state) {
        while (state == "on") { addrs = Share.addresses(); received = Share.received; delay(2000) }
    }
    if (picking) {
        FilesPane(store, pickOnly = true) { add -> files = (files + add).distinct(); Share.files = files; picking = false }
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Grid.m)) {
        SetSection("To send")
        if (files.isEmpty()) Note("Pick files, or just start: a phone can still send files to the tablet.")
        files.forEach { f ->
            SettingRow(f.name, Share.size(f.length())) { RoundIcon(G.CLOSE, "Remove", size = 34.dp) { files = files - f; Share.files = files } }
        }
        GlassActions(listOf(
            GlassAction("Add files", G.PLUS) { picking = true },
            if (state == "off") GlassAction("Start", G.SEND, active = true) {
                state = "starting"; Share.files = files
                bgRun {
                    Policy.grantSelf(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                    Policy.grantSelf(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    Share.startServer()
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Share.startGroup(ctx) { g -> group = g; state = "on"; addrs = Share.addresses() }
                    }
                }
            } else GlassAction("Stop", warn = true) { Share.stopAll(); group = null; state = "off" },
        ), Modifier.widthIn(max = 520.dp))
        when (state) {
            "starting" -> Note("Starting Wi-Fi Direct...")
            "on" -> {
                val p2pIp = addrs.firstOrNull { it.first.startsWith("p2p") }?.second
                val wifiIp = addrs.firstOrNull { it.first.startsWith("wlan") }?.second
                Row(horizontalArrangement = Arrangement.spacedBy(Grid.xl)) {
                    group?.let { g ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            QrImage(Share.wifiQr(g.ssid, g.pass), Modifier.size(200.dp).clip(Radius.Card))
                            Gap(h = Grid.s); VText("1. Scan to join", size = Type.body, color = V.Text)
                            VText(g.ssid, size = Type.label, color = V.Muted, maxLines = 1)
                            VText("Password ${g.pass}", size = Type.label, color = V.Muted, maxLines = 1)
                        }
                    }
                    val url = "http://${p2pIp ?: wifiIp ?: "192.168.49.1"}:${Share.PORT}"
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        QrImage(url, Modifier.size(200.dp).clip(Radius.Card))
                        Gap(h = Grid.s); VText(if (group != null) "2. Scan to open" else "Scan to open", size = Type.body, color = V.Text)
                        VText(url, size = Type.label, color = V.Muted, maxLines = 1)
                    }
                }
                if (group == null) Note("Wi-Fi Direct didn't start, so this only works for a phone on the same Wi-Fi as the tablet${if (wifiIp != null) " (\"http://$wifiIp:${Share.PORT}\")" else ""}.", V.Coral)
                else if (wifiIp != null) Note("A phone on the same Wi-Fi can skip the first code and open http://$wifiIp:${Share.PORT}.")
                if (received.isNotEmpty()) {
                    SetSection("Received, in Download/VoidOS")
                    received.take(8).forEach { n -> SettingRow(n) { VButton("Open", accent = V.Muted) { openFile(ctx, store, File(Share.inbox, n)) } } }
                }
            }
        }
    }
}

// =================================================================== Cast to TV

/**
 * Cast the screen to a TV that supports screen mirroring (Miracast). Looks for
 * TVs, connects with a tap, and shows what's connected with a Stop button.
 */
@Composable
fun CastPane(compact: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var st by remember { mutableStateOf<Cast.State?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    suspend fun scan() { busy = "Looking for TVs..."; st = withContext(Dispatchers.IO) { Cast.scan(ctx) }; busy = null }
    LaunchedEffect(Unit) { st = withContext(Dispatchers.IO) { Cast.status(ctx) }; scan() }
    // while connecting, check again every few seconds
    LaunchedEffect(st?.active) { while (st?.active == 1) { delay(2500); st = withContext(Dispatchers.IO) { Cast.status(ctx) } } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Grid.s)) {
        val s = st
        when {
            s?.error != null && s.tvs.isEmpty() -> Note(s.error, V.Coral)
            s != null && !s.supported -> Note("Android on this tablet says it can't cast to a TV.", V.Coral)
        }
        s?.takeIf { it.active != 0 }?.let { a ->
            val name = a.tvs.firstOrNull { it.address == a.activeAddress }?.name ?: "TV"
            SettingRow(if (a.active == 2) "Showing on $name" else "Connecting to $name...") {
                VButton("Stop", accent = V.Coral) { scope.launch { busy = "Stopping..."; withContext(Dispatchers.IO) { Cast.disconnect(ctx) }; st = withContext(Dispatchers.IO) { Cast.status(ctx) }; busy = null } }
            }
        }
        s?.tvs?.filter { it.address != s.activeAddress }?.forEach { tv ->
            SettingRow(tv.name, if (tv.available) null else "Not found right now") {
                Row(horizontalArrangement = Arrangement.spacedBy(Grid.s)) {
                    if (tv.remembered && !compact) VButton("Forget", accent = V.Muted) { scope.launch { withContext(Dispatchers.IO) { Cast.forget(ctx, tv.address) }; scan() } }
                    VButton("Show here", filled = tv.available, enabled = tv.available) {
                        scope.launch {
                            busy = "Connecting to ${tv.name}..."
                            st = withContext(Dispatchers.IO) { Cast.connect(ctx, tv.address); Cast.status(ctx) }
                            busy = null
                        }
                    }
                }
            }
        }
        if (s != null && s.supported && s.tvs.isEmpty() && busy == null) Note("No TVs found. Turn on screen mirroring on the TV, then look again.")
        busy?.let { Note(it) }
        if (busy == null) VButton("Look again", accent = V.Muted) { scope.launch { scan() } }
    }
}

// =================================================================== FM radio

/** FM radio: opens the tablet's own FM radio app when it has one. Wired headphones are the aerial. */
@Composable
fun FmPane(store: Store) {
    val ctx = LocalContext.current
    val pkg = remember { Extras.fmPackage(ctx) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Grid.m)) {
        if (pkg == null) {
            Note("This tablet's software has no FM radio app, so FM can't play here. Lenovo lists FM for some versions of this tablet only.")
        } else {
            Note("Plug in wired headphones first: their cable is the aerial.")
            VButton("Open FM radio", filled = true) { if (!Extras.openSystemApp(ctx, store, pkg)) toast.value = "FM radio wouldn't open." }
        }
    }
}

// =================================================================== Document scanner

/**
 * Scan a document with the camera: take the picture, drag the four corners onto
 * the page's corners, then save. The page is straightened, made crisp black on
 * white, and saved as a PDF and a picture in Documents/Scans.
 */
@Composable
fun DocScanPane(store: Store) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var allowed by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var shot by remember { mutableStateOf<Bitmap?>(null) }
    var saved by remember { mutableStateOf<File?>(null) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    if (!allowed) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            VButton("Use the camera", filled = true) { bgRun { if (Policy.grantSelf(ctx, Manifest.permission.CAMERA)) allowed = true } }
        }
        return
    }
    val s = shot
    if (s == null) {
        Box(Modifier.fillMaxSize().clip(Radius.Panel).background(Color.Black)) {
            AndroidView(factory = { c ->
                PreviewView(c).apply {
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    val fut = ProcessCameraProvider.getInstance(c)
                    fut.addListener({
                        val prov = fut.get()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }
                        val cap = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
                        val sel = if (prov.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                        runCatching { prov.unbindAll(); prov.bindToLifecycle(owner, sel, preview, cap); capture = cap }
                    }, ContextCompat.getMainExecutor(c))
                }
            }, modifier = Modifier.fillMaxSize())
            saved?.let { f -> VText("Saved ${f.nameWithoutExtension} in Documents/Scans", size = Type.body, color = V.Text, modifier = Modifier.align(Alignment.TopCenter).padding(Grid.l).glass(Pill, 0.7f).padding(horizontal = Grid.l, vertical = Grid.s)) }
            Box(
                Modifier.align(Alignment.BottomCenter).padding(Grid.xl).size(76.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.9f)).tap {
                    capture?.takePicture(ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) { shot = imageToBitmap(image); image.close() }
                        override fun onError(exception: ImageCaptureException) { toast.value = "The camera didn't take the picture." }
                    })
                },
            )
        }
        return
    }
    // corners as fractions of the picture: top-left, top-right, bottom-right, bottom-left
    val corners = remember(s) { mutableStateListOf(Offset(0.08f, 0.08f), Offset(0.92f, 0.08f), Offset(0.92f, 0.92f), Offset(0.08f, 0.92f)) }
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val boxW = with(density) { maxWidth.toPx() }; val boxH = with(density) { maxHeight.toPx() }
            val scale = minOf(boxW / s.width, boxH / s.height)
            val w = s.width * scale; val h = s.height * scale
            val ox = (boxW - w) / 2; val oy = (boxH - h) / 2
            Image(s.asImageBitmap(), "Scan", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            Canvas(Modifier.fillMaxSize().pointerInput(s) {
                var grab = -1
                detectDragGestures(
                    onDragStart = { p -> grab = corners.indices.minByOrNull { i -> (Offset(ox + corners[i].x * w, oy + corners[i].y * h) - p).getDistance() } ?: -1 },
                    onDrag = { ch, d -> ch.consume(); if (grab >= 0) corners[grab] = Offset((corners[grab].x + d.x / w).coerceIn(0f, 1f), (corners[grab].y + d.y / h).coerceIn(0f, 1f)) },
                )
            }) {
                val pts = corners.map { Offset(ox + it.x * w, oy + it.y * h) }
                val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                drawPath(path, V.Teal.copy(alpha = 0.18f))
                drawPath(path, V.Teal, style = Stroke(2.dp.toPx()))
                pts.forEach { drawCircle(V.Teal, 12.dp.toPx(), it); drawCircle(Color.White, 5.dp.toPx(), it) }
            }
        }
        Gap(h = Grid.m)
        GlassActions(listOf(
            GlassAction("Retake") { shot = null },
            GlassAction("Save", G.CHECK, active = true) {
                val pts = corners.toList()
                bgRun {
                    val f = saveScan(s, pts)
                    saved = f; shot = null
                    if (f == null) toast.value = "The scan couldn't be saved."
                }
            },
        ), Modifier.widthIn(max = 520.dp).align(Alignment.CenterHorizontally))
    }
}

private fun imageToBitmap(img: ImageProxy): Bitmap? = runCatching {
    val buf = img.planes[0].buffer
    val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
    val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val rot = img.imageInfo.rotationDegrees
    if (rot == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
}.getOrNull()

/** Straightens the page between [pts] (fractions), makes it crisp, and saves a PDF and a JPEG. */
fun saveScan(src: Bitmap, pts: List<Offset>): File? = runCatching {
    val p = pts.map { Offset(it.x * src.width, it.y * src.height) }
    val wTop = (p[1] - p[0]).getDistance(); val wBot = (p[2] - p[3]).getDistance()
    val hL = (p[3] - p[0]).getDistance(); val hR = (p[2] - p[1]).getDistance()
    val outW = maxOf(wTop, wBot).toInt().coerceIn(200, 3000); val outH = maxOf(hL, hR).toInt().coerceIn(200, 4000)
    val m = Matrix()
    m.setPolyToPoly(floatArrayOf(p[0].x, p[0].y, p[1].x, p[1].y, p[2].x, p[2].y, p[3].x, p[3].y), 0,
        floatArrayOf(0f, 0f, outW.toFloat(), 0f, outW.toFloat(), outH.toFloat(), 0f, outH.toFloat()), 0, 4)
    val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    c.drawColor(android.graphics.Color.WHITE)
    // greyscale, then strong contrast: paper goes white, ink goes black
    val cm = ColorMatrix().apply { setSaturation(0f) }
    val k = 1.7f; val t = -0.35f * 255
    cm.postConcat(ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, t, 0f, k, 0f, 0f, t, 0f, 0f, k, 0f, t, 0f, 0f, 0f, 1f, 0f)))
    c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) })
    val dir = File(android.os.Environment.getExternalStorageDirectory(), "Documents/Scans").apply { mkdirs() }
    val name = "Scan " + SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.UK).format(Date())
    File(dir, "$name.jpg").outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    val pdf = android.graphics.pdf.PdfDocument()
    // A4 at 72 dpi, the page fitted inside
    val pw = 595; val ph = 842
    val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(pw, ph, 1).create())
    val sc = minOf(pw.toFloat() / outW, ph.toFloat() / outH)
    page.canvas.drawBitmap(out, Matrix().apply { postScale(sc, sc); postTranslate((pw - outW * sc) / 2, (ph - outH * sc) / 2) }, Paint(Paint.FILTER_BITMAP_FLAG))
    pdf.finishPage(page)
    val f = File(dir, "$name.pdf")
    f.outputStream().use { pdf.writeTo(it) }
    pdf.close()
    f
}.getOrNull()

// =================================================================== Notifications settings

/**
 * Notifications, in Settings: the sound, the resting-clock glow, and a switch
 * per app. An app switched off has its notifications stopped by Android (root)
 * and anything it still posts is cleared by VoidOS.
 */
@Composable
fun NotificationsPane(store: Store) {
    val ctx = LocalContext.current
    var sounds by remember { mutableStateOf<List<Extras.Sound>>(emptyList()) }
    var current by remember { mutableStateOf<Uri?>(null) }
    var showSounds by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { sounds = Extras.notificationSounds(ctx); current = Extras.currentNotificationSound(ctx); apps = AppsRepo.all(ctx) }
    }
    val currentName = sounds.firstOrNull { it.uri != null && it.uri.toString().substringBefore('?') == current?.toString()?.substringBefore('?') }?.title
        ?: if (current == null) "None" else "Default"
    SetSection("Sound")
    SettingRow("Notification sound", currentName) { VButton(if (showSounds) "Done" else "Change", accent = V.Muted) { showSounds = !showSounds } }
    if (showSounds) sounds.forEach { snd ->
        val on = snd.title == currentName
        Row(
            Modifier.fillMaxWidth().clip(Radius.Card).background(if (on) V.Teal.copy(alpha = 0.14f) else Color.Transparent).tap {
                current = snd.uri
                snd.uri?.let { u -> runCatching { RingtoneManager.getRingtone(ctx, u)?.play() } }
                bgRun { Extras.setNotificationSound(snd.uri) }
            }.padding(horizontal = Grid.m, vertical = Grid.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VText(snd.title, size = Type.body, color = V.Text, modifier = Modifier.weight(1f), maxLines = 1)
            if (on) Glyph(G.CHECK, V.Teal, Modifier.size(20.dp))
        }
    }
    SettingRow("Glow on the resting clock", "When something new is waiting") { VToggle(store.notifyLight) { store.set("notifyLight", it) } }
    SetSection("Apps")
    apps.forEach { e ->
        SettingRow(e.label) {
            VToggle(e.pkg !in store.mutedApps) { on ->
                store.setMuted(e.pkg, !on)
                bgRun { Su.run("cmd appops set ${Su.q(e.pkg)} POST_NOTIFICATION ${if (on) "allow" else "ignore"}") }
                if (!on) com.andysocial.voidos.VoidListener.clearFrom(e.pkg)
            }
        }
    }
}

// =================================================================== Battery use

/** Battery use per app since the last full charge. */
@Composable
fun BatteryUseList() {
    val ctx = LocalContext.current
    var use by remember { mutableStateOf<List<Extras.Use>?>(null) }
    LaunchedEffect(Unit) { use = withContext(Dispatchers.IO) { Extras.batteryUse(ctx) } }
    SetSection("Use since the last full charge")
    val u = use
    when {
        u == null -> Note("Adding it up...")
        u.isEmpty() -> Note("Android hasn't counted any yet, or VoidOS needs root for this.")
        else -> {
            val total = u.sumOf { it.mah }.coerceAtLeast(0.01)
            u.take(12).forEach { a ->
                val pct = a.mah / total
                Column(Modifier.fillMaxWidth().padding(vertical = Grid.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        VText(a.label, size = Type.body, color = V.Text, modifier = Modifier.weight(1f), maxLines = 1)
                        VText("${(pct * 100).toInt()}%", size = Type.body, color = V.Muted)
                    }
                    Gap(h = Grid.xs)
                    Box(Modifier.fillMaxWidth().size(height = 6.dp, width = 0.dp).clip(Pill).background(Color.White.copy(alpha = 0.06f))) {
                        Box(Modifier.fillMaxWidth(pct.toFloat().coerceIn(0.02f, 1f)).size(height = 6.dp, width = 0.dp).clip(Pill).background(V.Green))
                    }
                }
            }
        }
    }
}

// =================================================================== Guest, Kids Space, assistant

/** Lock mode extras: guest mode and Kids Space. */
@Composable
fun GuestAndKids(store: Store) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { AppsRepo.all(ctx).filter { it.pkg in store.allowed } } }
    SetSection("Guest")
    SettingRow("Guest mode", "Only the apps below open. Notes, Settings and notifications are hidden. Your PIN turns it off.") {
        VToggle(store.guest) { store.set("guest", it) }
    }
    if (!store.guest) apps.forEach { e -> SettingRow("  " + e.label) { VToggle(e.pkg in store.guestApps) { store.setGuestApp(e.pkg, it) } } }
    SetSection("Kids")
    val kids = remember { Extras.installed(ctx, Extras.KIDS) }
    SettingRow("Kids Space", if (kids) "Google's space for children" else "Not on this tablet") {
        VButton("Open", accent = V.Muted, enabled = kids) { Extras.openSystemApp(ctx, store, Extras.KIDS) }
    }
}


// =================================================================== USB sheet

/** When a computer is plugged in: choose what USB does, right there. */
@Composable
fun UsbSheet(modifier: Modifier = Modifier) {
    val on by Extras.usbComputer
    val dismissed by Extras.usbDismissed
    AnimatedVisibility(on && !dismissed, modifier, enter = slideInVertically(Motion.springOffset) { it } + fadeIn(Motion.quick()), exit = slideOutVertically { it } + fadeOut(Motion.quick())) {
        Column(
            Modifier.padding(Grid.l).widthIn(max = 560.dp).liquidGlass(Radius.Panel, GlassLook(smoke = 1.25f, depth = 1.2f)).padding(Grid.l),
            verticalArrangement = Arrangement.spacedBy(Grid.m),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Glyph(G.USB, V.Teal, Modifier.size(24.dp)); Gap(w = Grid.m)
                VText("Connected to a computer", size = Type.title, color = V.Text, font = SansMedium, modifier = Modifier.weight(1f))
                RoundIcon(G.CLOSE, "Dismiss", size = 34.dp) { Extras.usbDismissed.value = true }
            }
            GlassActions(listOf(
                GlassAction("Charge only") { bgRun { Su.run("svc usb setFunctions") }; Extras.usbDismissed.value = true },
                GlassAction("Files", G.FILES) { bgRun { Su.run("svc usb setFunctions mtp") }; Extras.usbDismissed.value = true },
                GlassAction("Photos", G.CAMERA) { bgRun { Su.run("svc usb setFunctions ptp") }; Extras.usbDismissed.value = true },
            ))
        }
    }
}

// =================================================================== next event

/** One line: the next calendar event, for the lock screen and the calendar. Nothing when there's none. */
@Composable
fun NextEventLine(store: Store, color: Color = V.Muted) {
    val ctx = LocalContext.current
    var ev by remember { mutableStateOf<Extras.Event?>(null) }
    LaunchedEffect(Unit) {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
            withContext(Dispatchers.IO) { Policy.grantSelf(ctx, Manifest.permission.READ_CALENDAR) }
        while (true) { ev = withContext(Dispatchers.IO) { Extras.nextEvent(ctx) }; delay(300_000) }
    }
    ev?.let { e -> VText(eventText(e, store.use24h), size = Type.body, color = color, maxLines = 1) }
}

fun eventText(e: Extras.Event, use24h: Boolean, now: Long = System.currentTimeMillis()): String {
    val day = SimpleDateFormat("yyyyMMdd", Locale.UK)
    val today = day.format(Date(now)); val tomorrow = day.format(Date(now + 86_400_000)); val that = day.format(Date(e.begin))
    val dayWord = when (that) { today -> "Today"; tomorrow -> "Tomorrow"; else -> SimpleDateFormat("EEE d MMM", Locale.UK).format(Date(e.begin)) }
    val time = if (e.allDay) "" else " " + SimpleDateFormat(if (use24h) "HH:mm" else "h:mm a", Locale.UK).format(Date(e.begin))
    return "$dayWord$time  ·  ${e.title}"
}

/**
 * What guest mode shows in place of Notes and Settings, and when guest mode is
 * asked to turn off: the PIN pad. The right PIN ends guest mode.
 */
@Composable
fun GuestGate(store: Store, onDone: () -> Unit = {}) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        GlassPinPad(title = "Guest mode", subtitle = "Enter your PIN to leave it", length = store.pinLength(), check = { store.checkPin(it) }) {
            store.set("guest", false); onDone()
        }
    }
}

/** Dolby Atmos, when the tablet has Lenovo's Dolby app: opened from Display & sound. */
@Composable
fun DolbyRow(store: Store) {
    val ctx = LocalContext.current
    val dolby = remember { Extras.dolbyPackage(ctx) } ?: return
    Gap(h = Grid.xl)
    SettingRow("Dolby Atmos", "Sound profiles for films, music and games") {
        VButton("Open", accent = V.Muted) { if (!Extras.openSystemApp(ctx, store, dolby)) toast.value = "Dolby Atmos wouldn't open." }
    }
}

/** A short message from any screen; it floats at the bottom for a few seconds. */
val toast = mutableStateOf<String?>(null)

@Composable
fun ToastHost(modifier: Modifier = Modifier) {
    val m by toast
    LaunchedEffect(m) { if (m != null) { delay(4500); toast.value = null } }
    AnimatedVisibility(m != null, modifier, enter = fadeIn(Motion.quick()) + slideInVertically(Motion.springOffset) { it / 2 }, exit = fadeOut(Motion.quick())) {
        VText(m.orEmpty(), size = Type.body, color = V.Text, maxLines = 2,
            modifier = Modifier.padding(Grid.l).widthIn(max = 620.dp).liquidGlass(Pill, GlassLook(smoke = 1.25f, depth = 1.2f)).padding(horizontal = Grid.xl, vertical = Grid.m))
    }
}
