package com.andysocial.voidos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * adb-only escape hatch (the manifest requires the sender to hold DUMP, which
 * only the adb shell and the system have).
 *
 *  UNLOCK  - turns lock mode off and brings the Android bars back.
 *  RELEASE - undoes every VoidOS policy and removes it as device owner.
 */
class ReleaseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = (context.applicationContext as VoidApp).store
        when (intent.action) {
            "com.andysocial.voidos.action.UNLOCK" -> {
                store.set("kiosk", false)
                store.set("hideBars", false)
                Policy.applyAll(context)
                VoidApp.releaseSignal.value++
                Log.i("VoidOS", "UNLOCK via adb: lock mode off, bars back")
                resultData = "VoidOS unlocked"
            }
            "com.andysocial.voidos.action.RELEASE" -> {
                VoidApp.releaseSignal.value++
                val ok = Policy.release(context)
                Log.i("VoidOS", "RELEASE via adb: device owner removed=$ok")
                resultData = if (ok) "VoidOS released: no longer device owner" else "VoidOS was not device owner"
            }
        }
    }
}
