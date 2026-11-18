package com.andysocial.voidos

import android.content.Context
import android.os.Binder
import android.os.IBinder

/**
 * Cast the screen to a TV (Miracast / Wi-Fi Display), in VoidOS. Android keeps
 * the Wi-Fi Display controls for its own Settings app, so VoidOS runs a tiny
 * helper as root (the same app, started with app_process) which talks to
 * Android's display service directly. The helper prints what it finds and the
 * screen below reads it.
 */
object Cast {
    data class Tv(val address: String, val name: String, val available: Boolean, val remembered: Boolean)
    data class State(val feature: Int, val active: Int, val activeAddress: String?, val tvs: List<Tv>, val error: String? = null) {
        /** feature: 0 the tablet can't, 1 turned off by the system, 2 off, 3 on. active: 0 none, 1 connecting, 2 connected. */
        val supported get() = feature != 0
    }

    private fun helper(ctx: Context, args: String, timeoutS: Long): Su.Out {
        val apk = ctx.applicationInfo.sourceDir
        return Su.run("CLASSPATH=${Su.q(apk)} app_process /system/bin ${CastCli::class.java.name} $args", timeoutS)
    }

    /** Turns Wi-Fi Display on and looks for TVs for a few seconds. Blocks: call off the main thread. */
    fun scan(ctx: Context): State {
        Su.run("settings put global wifi_display_on 1")
        val out = helper(ctx, "scan", 25)
        return parse(out)
    }

    fun status(ctx: Context): State = parse(helper(ctx, "status", 15))
    fun connect(ctx: Context, address: String): Boolean = helper(ctx, "connect ${Su.q(address)}", 20).ok
    fun disconnect(ctx: Context): Boolean = helper(ctx, "disconnect", 15).ok
    fun forget(ctx: Context, address: String): Boolean = helper(ctx, "forget ${Su.q(address)}", 15).ok

    /** Pure: reads the helper's lines. */
    fun parse(out: Su.Out): State {
        if (out.code == -1) return State(0, 0, null, emptyList(), "VoidOS needs root for this. Allow it in Magisk.")
        var feature = 0; var active = 0; var addr: String? = null; var err: String? = null
        val tvs = mutableListOf<Tv>()
        out.text.lines().forEach { l ->
            val p = l.split('\t')
            when (p[0]) {
                "STATE" -> if (p.size >= 4) { feature = p[1].toIntOrNull() ?: 0; active = p[2].toIntOrNull() ?: 0; addr = p[3].ifEmpty { null } }
                "TV" -> if (p.size >= 5) tvs += Tv(p[1], p[2].ifEmpty { p[1] }, p[3] == "1", p[4] == "1")
                "ERR" -> err = p.getOrNull(1)
            }
        }
        if (!out.ok && err == null) err = "Android didn't answer."
        return State(feature, active, addr, tvs.distinctBy { it.address }.sortedByDescending { it.available }, err)
    }
}

/** Runs as root through app_process; see [Cast]. Prints STATE, TV and ERR lines. */
object CastCli {
    @JvmStatic fun main(args: Array<String>) {
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val b = sm.getMethod("getService", String::class.java).invoke(null, "display") as IBinder
            val dm = Class.forName("android.hardware.display.IDisplayManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, b)!!
            val c = dm.javaClass
            fun call(name: String, vararg a: Any) = c.methods.first { it.name == name && it.parameterTypes.size == a.size }.invoke(dm, *a)
            when (args.getOrNull(0)) {
                "scan" -> {
                    // Android only lets a process scan once it has registered for display events
                    val cb = Class.forName("android.hardware.display.IDisplayManagerCallback\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, Binder())!!
                    call("registerCallback", cb)
                    call("startWifiDisplayScan")
                    Thread.sleep(9000)
                    print(call("getWifiDisplayStatus"))
                    runCatching { call("stopWifiDisplayScan") }
                }
                "connect" -> { call("connectWifiDisplay", args[1]); Thread.sleep(1500); print(call("getWifiDisplayStatus")) }
                "disconnect" -> { call("disconnectWifiDisplay"); print(call("getWifiDisplayStatus")) }
                "forget" -> { call("forgetWifiDisplay", args[1]); print(call("getWifiDisplayStatus")) }
                else -> print(call("getWifiDisplayStatus"))
            }
            System.exit(0)
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            println("ERR\t${cause.javaClass.simpleName}: ${cause.message ?: ""}".replace('\n', ' '))
            System.exit(1)
        }
    }

    private fun print(status: Any?) {
        if (status == null) { println("STATE\t0\t0\t"); return }
        val k = status.javaClass
        fun <T> get(n: String) = runCatching { @Suppress("UNCHECKED_CAST") (k.getMethod(n).invoke(status) as T) }.getOrNull()
        val active = get<Any?>("getActiveDisplay")
        val activeAddr = active?.let { a -> runCatching { a.javaClass.getMethod("getDeviceAddress").invoke(a) as String }.getOrNull() }.orEmpty()
        println("STATE\t${get<Int>("getFeatureState") ?: 0}\t${get<Int>("getActiveDisplayState") ?: 0}\t$activeAddr")
        val list = (get<Array<Any>>("getDisplays") ?: emptyArray())
        for (d in list) {
            val dk = d.javaClass
            fun s(n: String) = runCatching { dk.getMethod(n).invoke(d)?.toString() }.getOrNull().orEmpty()
            val name = s("getFriendlyDisplayName").ifEmpty { s("getDeviceName") }.replace('\t', ' ')
            println("TV\t${s("getDeviceAddress")}\t$name\t${if (s("isAvailable") == "true") 1 else 0}\t${if (s("isRemembered") == "true") 1 else 0}")
        }
    }
}
