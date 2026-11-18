package com.andysocial.voidos

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Where the tablet is, for the weather. Coarse location is enough: a Wi-Fi
 * tablet knows its town from the network. As device owner VoidOS grants
 * itself the permission, so there is no prompt; otherwise the Sky screen asks.
 */
object Locator {

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Device owner only: grant ourselves coarse location without a dialog. False if we can't. */
    fun selfGrant(ctx: Context): Boolean {
        if (hasPermission(ctx)) return true
        return runCatching {
            val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
            if (!dpm.isDeviceOwnerApp(ctx.packageName)) return false
            val admin = ComponentName(ctx, VoidAdmin::class.java)
            dpm.setPermissionGrantState(admin, ctx.packageName, Manifest.permission.ACCESS_COARSE_LOCATION, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        }.getOrDefault(false) && hasPermission(ctx)
    }

    /** The best recent fix, or one fresh network fix within 10 seconds. Null when location is off or denied. */
    @SuppressLint("MissingPermission")
    suspend fun fix(ctx: Context): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
        val last = providers.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 6 * 3_600_000L) return last
        val p = LocationManager.NETWORK_PROVIDER
        if (runCatching { lm.isProviderEnabled(p) }.getOrDefault(false)) {
            val fresh = withTimeoutOrNull(10_000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val l = object : LocationListener {
                        override fun onLocationChanged(loc: Location) { lm.removeUpdates(this); if (cont.isActive) cont.resume(loc) }
                        @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }
                    runCatching { lm.requestSingleUpdate(p, l, Looper.getMainLooper()) }.onFailure { if (cont.isActive) cont.resume(null) }
                    cont.invokeOnCancellation { lm.removeUpdates(l) }
                }
            }
            if (fresh != null) return fresh
        }
        return last
    }

    /** A town name for a fix: Android's geocoder first, then Open-Meteo's free lookup, then a plain label. */
    suspend fun name(ctx: Context, lat: Double, lon: Double): String = withContext(Dispatchers.IO) {
        runCatching {
            @Suppress("DEPRECATION")
            Geocoder(ctx, Locale.UK).getFromLocation(lat, lon, 1)?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
        }.getOrNull()
            ?: runCatching {
                val c = URL("https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=$lat&longitude=$lon&localityLanguage=en").openConnection() as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 6000
                val o = try { JSONObject(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
                o.optString("city").ifBlank { o.optString("locality") }.ifBlank { null }
            }.getOrNull()
            ?: "Here"
    }

    /**
     * Point the weather at where the tablet is. Returns true when the place
     * changed. Leaves a city picked by hand alone.
     */
    suspend fun locate(ctx: Context, store: Store): Boolean {
        if (!store.autoPlace) return false
        selfGrant(ctx)
        val loc = fix(ctx) ?: return false
        val lat = loc.latitude.toFloat(); val lon = loc.longitude.toFloat()
        // within ~3 km of where we already are: keep the name, skip the lookup
        val moved = Math.abs(lat - store.lat) > 0.03f || Math.abs(lon - store.lon) > 0.03f
        if (!moved) return false
        // a place was picked while the fix was coming in: leave it alone
        if (!store.autoPlace) return false
        store.setPlace(name(ctx, loc.latitude, loc.longitude), lat, lon)
        return true
    }
}
