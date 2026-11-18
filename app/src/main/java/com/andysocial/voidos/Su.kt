package com.andysocial.voidos

import java.util.concurrent.TimeUnit

/**
 * Root, through Magisk's su. Every call blocks, so run it off the main thread.
 * The first call makes Magisk ask the user to allow VoidOS once.
 */
object Su {
    data class Out(val code: Int, val text: String, val bytes: ByteArray = ByteArray(0)) {
        val ok get() = code == 0
    }

    /** Tests swap the shell out. */
    @Volatile var fake: ((String) -> Out)? = null

    /** Runs [cmd] as root. code -1: no su (or refused to start), -2: timed out. */
    fun run(cmd: String, timeoutS: Long = 10): Out {
        fake?.let { return it(cmd) }
        return runCatching {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            var bytes = ByteArray(0)
            val reader = Thread { runCatching { bytes = p.inputStream.readBytes() } }.apply { isDaemon = true; start() }
            if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) { p.destroy(); return Out(-2, "") }
            reader.join(1000)
            Out(p.exitValue(), String(bytes).trim(), bytes)
        }.getOrDefault(Out(-1, ""))
    }

    fun ok(cmd: String, timeoutS: Long = 10) = run(cmd, timeoutS).ok

    /** Single-quotes [s] for the shell. */
    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
