package io.github.biolend83.castwire.android

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import io.github.biolend83.castwire.Castwire
import io.github.biolend83.castwire.CastwireConfig
import okhttp3.OkHttpClient

/**
 * Castwire set up for Android (what TubeHaus's Android app passes to its GoogleCast):
 *
 * - discovery holds a `MulticastLock` while it searches (Android drops multicast packets without one);
 * - while a session is open, a Wi-Fi lock and a partial wake lock keep the phone serving the TV with
 *   the screen off (relayed media comes from this phone).
 *
 * The permissions are in this library's manifest. Keep the app alive in the background yourself while
 * casting (a foreground service of type `mediaPlayback`, as any media app does).
 *
 * ```
 * val castwire = AndroidCastwire.create(applicationContext)
 * ```
 */
object AndroidCastwire {
    fun create(context: Context, httpClient: OkHttpClient = OkHttpClient(), log: ((String) -> Unit)? = null): Castwire =
        Castwire(config(context, httpClient, log))

    /** the hooks alone, for a [CastwireConfig] of your own */
    @SuppressLint("WakelockTimeout")
    fun config(context: Context, httpClient: OkHttpClient = OkHttpClient(), log: ((String) -> Unit)? = null): CastwireConfig {
        val app = context.applicationContext
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val multicastLock = wifi.createMulticastLock("castwire:discovery").apply { setReferenceCounted(true) }
        @Suppress("DEPRECATION")
        val wifiLock = wifi.createWifiLock(
            if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "castwire:casting",
        ).apply { setReferenceCounted(false) }
        val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "castwire:casting").apply { setReferenceCounted(false) }
        return CastwireConfig(
            multicast = { on ->
                runCatching { if (on) multicastLock.acquire() else if (multicastLock.isHeld) multicastLock.release() }
            },
            casting = { on ->
                runCatching {
                    if (on) { wifiLock.acquire(); wakeLock.acquire() }
                    else { if (wifiLock.isHeld) wifiLock.release(); if (wakeLock.isHeld) wakeLock.release() }
                }
            },
            httpClient = httpClient,
            log = log,
        )
    }
}
