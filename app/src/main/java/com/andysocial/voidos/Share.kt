package com.andysocial.voidos

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Environment
import android.os.Looper
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * Send files over Wi-Fi Direct. Phones have no common way to receive files over
 * Wi-Fi Direct from a tablet, so VoidOS makes its own: the tablet starts a
 * Wi-Fi Direct group (its own little network, no router needed) and a small
 * web page on it. The phone scans a QR code to join the network, then a second
 * one opens the page, where it downloads the files you picked and can send
 * files back. On the same Wi-Fi as the tablet the page works without the group.
 * Files sent to the tablet land in Download/VoidOS.
 */
object Share {
    const val PORT = 8765
    data class Group(val ssid: String, val pass: String)

    @Volatile var files: List<File> = emptyList()
    @Volatile var received: List<String> = emptyList(); private set
    private var server: ServerSocket? = null
    private var p2p: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null

    val inbox: File get() = File(Environment.getExternalStorageDirectory(), "Download/VoidOS").apply { mkdirs() }

    val running get() = server?.isClosed == false

    /** Start the page (and keep the files list as it is). */
    fun startServer() {
        if (running) return
        val s = runCatching { ServerSocket(PORT) }.getOrNull() ?: return
        server = s
        thread(name = "voidos-share", isDaemon = true) {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { c.use { handle(it) } } }
            }
        }
    }

    fun stopServer() { runCatching { server?.close() }; server = null }

    /** Start a Wi-Fi Direct group; [done] gets its name and password, or null if Android refused. */
    @SuppressLint("MissingPermission")
    fun startGroup(ctx: Context, done: (Group?) -> Unit) {
        val m = ctx.applicationContext.getSystemService(WifiP2pManager::class.java) ?: return done(null)
        val ch = channel ?: m.initialize(ctx.applicationContext, Looper.getMainLooper(), null)
        p2p = m; channel = ch
        fun ask(tries: Int) {
            m.requestGroupInfo(ch) { g: WifiP2pGroup? ->
                if (g != null && g.passphrase != null) done(Group(g.networkName, g.passphrase))
                else if (tries > 0) android.os.Handler(Looper.getMainLooper()).postDelayed({ ask(tries - 1) }, 700)
                else done(null)
            }
        }
        m.requestGroupInfo(ch) { g ->
            if (g != null && g.isGroupOwner) ask(0)
            else runCatching {
                m.createGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = ask(8)
                    override fun onFailure(reason: Int) = done(null)
                })
            }.onFailure { done(null) }
        }
    }

    fun stopGroup() {
        val m = p2p ?: return; val ch = channel ?: return
        runCatching { m.removeGroup(ch, null) }
    }

    fun stopAll() { stopServer(); stopGroup() }

    /** The tablet's addresses: the Wi-Fi Direct one (p2p, usually 192.168.49.1) first, then Wi-Fi. */
    fun addresses(): List<Pair<String, String>> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { ni ->
            ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { ni.name to it.hostAddress!! }
        }.sortedBy { if (it.first.startsWith("p2p")) 0 else 1 }
    }.getOrDefault(emptyList())

    /** Pure: the QR text that joins a WPA network. */
    fun wifiQr(ssid: String, pass: String): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"")
        return "WIFI:T:WPA;S:${esc(ssid)};P:${esc(pass)};;"
    }

    /** Pure: a file name that's safe to save (no folders, no odd characters). */
    fun safeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._ ()-]"), "_").trim().trimStart('.')
        return base.ifEmpty { "file" }.take(120)
    }

    // ------------------------------------------------------------------ the page

    private fun handle(sock: Socket) {
        sock.soTimeout = 30_000
        val input = BufferedInputStream(sock.getInputStream())
        val out = sock.getOutputStream()
        val request = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val l = readLine(input) ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':'); if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val parts = request.split(' ')
        if (parts.size < 2) return
        val method = parts[0]; val path = parts[1]
        when {
            method == "GET" && (path == "/" || path.startsWith("/?")) -> page(out)
            method == "GET" && path.startsWith("/f/") -> {
                val idx = path.removePrefix("/f/").substringBefore('/').toIntOrNull()
                val f = idx?.let { files.getOrNull(it) }
                if (f == null || !f.isFile) status(out, 404, "Not found") else sendFile(out, f)
            }
            method == "PUT" && path.startsWith("/up/") -> {
                val name = safeName(URLDecoder.decode(path.removePrefix("/up/"), "UTF-8"))
                val len = headers["content-length"]?.toLongOrNull() ?: -1L
                var target = File(inbox, name)
                var n = 1
                while (target.exists()) { target = File(inbox, name.substringBeforeLast('.') + " ($n)" + (if ('.' in name) "." + name.substringAfterLast('.') else "")); n++ }
                target.outputStream().use { o -> copy(input, o, len) }
                received = (listOf(target.name) + received).take(50)
                status(out, 200, "Saved")
            }
            else -> status(out, 404, "Not found")
        }
    }

    private fun page(out: OutputStream) {
        val rows = files.mapIndexed { i, f ->
            "<a class=f href=\"/f/$i/${URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")}\" download>${html(f.name)}<span>${size(f.length())}</span></a>"
        }.joinToString("")
        val body = """<!doctype html><html><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1"><title>VoidOS</title>
<style>body{margin:0;background:#0b0b0d;color:#f2f2f5;font-family:system-ui,sans-serif;padding:24px}h1{font-weight:300;font-size:28px;margin:0 0 20px}
.f{display:flex;justify-content:space-between;padding:16px 20px;margin:8px 0;border-radius:22px;background:rgba(255,255,255,.07);color:#f2f2f5;text-decoration:none}
.f span{color:#8e8e95}label{display:block;text-align:center;margin-top:28px;padding:16px;border-radius:22px;background:#52d4dc;color:#0b0b0d;font-weight:600}
input{display:none}#s{color:#8e8e95;text-align:center;margin-top:12px}</style></head><body><h1>void</h1>
${if (rows.isEmpty()) "<p style=color:#8e8e95>Nothing picked to send yet.</p>" else rows}
<label>Send files to the tablet<input id=u type=file multiple></label><div id=s></div>
<script>u.onchange=async()=>{for(const f of u.files){s.textContent='Sending '+f.name+'...';
const r=await fetch('/up/'+encodeURIComponent(f.name),{method:'PUT',body:f});s.textContent=r.ok?'Sent '+f.name:'Could not send '+f.name}}</script></body></html>"""
        val bytes = body.toByteArray()
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes); out.flush()
    }

    private fun sendFile(out: OutputStream, f: File) {
        val name = URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${f.length()}\r\n" +
            "Content-Disposition: attachment; filename*=UTF-8''$name\r\nConnection: close\r\n\r\n").toByteArray())
        f.inputStream().use { copy(it, out, -1) }
        out.flush()
    }

    private fun status(out: OutputStream, code: Int, text: String) {
        val b = text.toByteArray()
        out.write("HTTP/1.1 $code $text\r\nContent-Type: text/plain\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(b); out.flush()
    }

    private fun copy(i: InputStream, o: OutputStream, len: Long) {
        val buf = ByteArray(64 * 1024); var left = len
        while (left != 0L) {
            val n = i.read(buf, 0, if (left < 0) buf.size else minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            o.write(buf, 0, n); if (left > 0) left -= n
        }
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar()); if (sb.length > 8192) return null
        }
    }

    private fun html(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    fun size(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> "%d KB".format(b / (1L shl 10))
        else -> "$b B"
    }
}
