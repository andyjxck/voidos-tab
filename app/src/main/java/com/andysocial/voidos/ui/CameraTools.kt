package com.andysocial.voidos.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.andysocial.voidos.Policy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import java.util.concurrent.Executors

/*
 * Tools that stand in for hardware the tablet doesn't have: it has no flash and no rear
 * barcode reader, so the screen is the torch and ring light, and the cameras become a
 * QR scanner, a mirror and a magnifier.
 */

/** Builds the text a phone reads from a Wi-Fi QR code (the standard WIFI: format). Pure. */
fun wifiQrText(ssid: String, password: String?, secure: Boolean): String {
    fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"")
    val t = if (secure && !password.isNullOrEmpty()) "WPA" else "nopass"
    val p = if (t == "WPA") "P:${esc(password!!)};" else ""
    return "WIFI:T:$t;S:${esc(ssid)};$p;"
}

/** A QR code as a white-on-clear bitmap, for glass. */
fun qrBitmap(text: String, size: Int = 512): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
fun QrImage(text: String, modifier: Modifier = Modifier) {
    val bmp = remember(text) { qrBitmap(text) }
    Image(bmp.asImageBitmap(), null, modifier.clip(Radius.Card))
}

/** The screen as a light: torch (white, full brightness) or ring light (warm to cool, round the edge). */
@Composable
fun LightPane(edge: Boolean = false) {
    val ctx = LocalContext.current
    var warm by remember { mutableFloatStateOf(0.35f) }
    var level by remember { mutableFloatStateOf(1f) }
    val colour = lerp(Color(0xFFDDEBFF), Color(0xFFFFD6A0), warm)
    DisposableEffect(level) {
        val w = (ctx as? Activity)?.window
        w?.attributes = w?.attributes?.apply { screenBrightness = level.coerceIn(0.05f, 1f) }
        w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            w?.attributes = w?.attributes?.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    Box(Modifier.fillMaxSize().then(if (edge) Modifier else Modifier.clip(Radius.Panel))) {
        Box(Modifier.fillMaxSize().background(colour))
        Column(
            Modifier.align(Alignment.BottomCenter).padding(Grid.xl).fillMaxWidth(0.7f),
            verticalArrangement = Arrangement.spacedBy(Grid.m),
        ) {
            GlassSlider("Warmth", warm) { warm = it }
            GlassSlider("Brightness", level) { level = it }
        }
    }
}

@Composable
private fun GlassSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column(Modifier.glass(Radius.Control, 0.6f).padding(horizontal = Grid.l, vertical = Grid.s)) {
        VText(label, size = Type.label, color = V.Muted)
        VSlider(value, Modifier.fillMaxWidth(), onChange = onChange)
    }
}

enum class CamMode { SCAN, MIRROR, ZOOM }

/** One camera screen for the QR scanner (rear camera), mirror (front, flipped) and magnifier (rear, zoomed). */
@Composable
fun CameraPane(mode: CamMode, edge: Boolean = false) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var allowed by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var found by remember { mutableStateOf<String?>(null) }
    var zoom by remember { mutableFloatStateOf(0.6f) }
    if (!allowed) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.glass(Pill, 0.7f).clickable {
                Thread { val ok = Policy.grantSelf(ctx, Manifest.permission.CAMERA); if (ok) allowed = true }.start()
            }.padding(horizontal = Grid.xl, vertical = Grid.m)) { VText("Use the camera", size = Type.body, color = V.Text) }
        }
        return
    }
    val exec = remember { Executors.newSingleThreadExecutor() }
    var cam by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    Box(Modifier.fillMaxSize().then(if (edge) Modifier else Modifier.clip(Radius.Panel)).background(Color.Black)) {
        AndroidView(
            factory = { c ->
                PreviewView(c).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    val fut = ProcessCameraProvider.getInstance(c)
                    fut.addListener({
                        val prov = fut.get()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }
                        val front = mode == CamMode.MIRROR
                        val sel = if (front && prov.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) CameraSelector.DEFAULT_FRONT_CAMERA
                            else if (prov.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA
                            else CameraSelector.DEFAULT_FRONT_CAMERA
                        val uses = mutableListOf<androidx.camera.core.UseCase>(preview)
                        if (mode == CamMode.SCAN) {
                            val reader = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.TRY_HARDER to true)) }
                            val an = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                            an.setAnalyzer(exec) { img ->
                                runCatching {
                                    val pl = img.planes[0]; val buf = pl.buffer
                                    val data = ByteArray(buf.remaining()).also { buf.get(it) }
                                    val src = PlanarYUVLuminanceSource(data, pl.rowStride, img.height, 0, 0, img.width, img.height, false)
                                    val r = reader.decodeWithState(BinaryBitmap(HybridBinarizer(src)))
                                    found = r.text
                                }
                                reader.reset(); img.close()
                            }
                            uses += an
                        }
                        runCatching { prov.unbindAll(); cam = prov.bindToLifecycle(owner, sel, *uses.toTypedArray()) }
                    }, ContextCompat.getMainExecutor(c))
                }
            },
            modifier = Modifier.fillMaxSize().graphicsLayer { if (mode == CamMode.MIRROR) scaleX = -1f },
        )
        if (mode == CamMode.ZOOM) {
            DisposableEffect(zoom, cam) { cam?.cameraControl?.setLinearZoom(zoom); onDispose { } }
            Box(Modifier.align(Alignment.BottomCenter).padding(Grid.xl).fillMaxWidth(0.6f)) { GlassSlider("Zoom", zoom) { zoom = it } }
        }
        found?.let { text ->
            Column(
                Modifier.align(Alignment.BottomCenter).padding(Grid.xl).fillMaxWidth(0.8f).glass(Radius.Card, 0.75f).padding(Grid.l),
                verticalArrangement = Arrangement.spacedBy(Grid.s),
            ) {
                VText(text, size = Type.body, color = V.Text, maxLines = 4)
                Row(horizontalArrangement = Arrangement.spacedBy(Grid.s)) {
                    Box(Modifier.glass(Pill, 0.6f).clickable { copy(ctx, text) }.padding(horizontal = Grid.l, vertical = Grid.s)) { VText("Copy", size = Type.label, color = V.Text) }
                    Box(Modifier.glass(Pill, 0.6f).clickable { found = null }.padding(horizontal = Grid.l, vertical = Grid.s)) { VText("Scan again", size = Type.label, color = V.Text) }
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { exec.shutdown() } }
}

private fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
    cm?.setPrimaryClip(android.content.ClipData.newPlainText("QR", text))
}
