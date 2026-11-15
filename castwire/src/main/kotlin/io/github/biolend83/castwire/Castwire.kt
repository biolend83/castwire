package io.github.biolend83.castwire

import io.github.biolend83.castwire.internal.CastServer
import io.github.biolend83.castwire.internal.Mdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicInteger

/**
 * Google Cast without Google's SDK (TubeHaus's GoogleCast): finds Cast devices on the local network
 * and plays media on them, from plain Kotlin/Java on Android, Windows, macOS and Linux. No Play Services.
 *
 * ```
 * val cast = Castwire()
 * val tv = cast.discover().first()
 * val session = cast.connect(tv)
 * session.load(CastMedia(CastSource.Url("https://example.com/video.mp4", "video/mp4"), MediaMetadata("A video")))
 * ```
 *
 * One instance per app is enough. On Android, create it with `AndroidCastwire.create(context)`
 * (castwire-android), which handles the multicast and Wi-Fi locks.
 */
class Castwire(private val config: CastwireConfig = CastwireConfig()) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** serves the TV what it can't fetch itself (files here, links needing headers, live playlists) */
    internal val server = CastServer(config.httpClient)

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    /** the devices found by the last [scan], in name order; updated as they answer */
    val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val sessions = AtomicInteger(0)
    private var scanJob: Job? = null

    /** looks for devices for [durationMs] in the background, filling [devices] as they answer */
    fun scan(durationMs: Long = 3500) {
        if (_scanning.value) return
        _scanning.value = true
        scanJob = scope.launch {
            val found = LinkedHashMap<String, CastDevice>()
            runCatching { config.multicast(true) }
            try {
                Mdns.discover(durationMs, config.log) { d ->
                    synchronized(found) {
                        found[d.id] = d
                        _devices.value = found.values.sortedBy { it.name.lowercase() }
                    }
                }
            } catch (e: Exception) {
                config.log?.invoke("discovery failed: $e")
            } finally {
                runCatching { config.multicast(false) }
                _devices.value = synchronized(found) { found.values.sortedBy { it.name.lowercase() } }
                _scanning.value = false
            }
        }
    }

    /** looks for devices for [durationMs] and returns what answered, in name order */
    suspend fun discover(durationMs: Long = 3500): List<CastDevice> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, CastDevice>()
        runCatching { config.multicast(true) }
        try {
            Mdns.discover(durationMs, config.log) { d -> synchronized(found) { found[d.id] = d } }
        } catch (e: Exception) {
            config.log?.invoke("discovery failed: $e")
        } finally {
            runCatching { config.multicast(false) }
        }
        synchronized(found) { found.values.sortedBy { it.name.lowercase() } }
    }

    /**
     * Connects to [device] and starts [appId] on it: Google's Default Media Receiver unless you have
     * your own receiver app.
     *
     * @throws CastException when the device can't be reached or won't start the app
     */
    suspend fun connect(device: CastDevice, appId: String = CastSession.DEFAULT_MEDIA_RECEIVER): CastSession = withContext(Dispatchers.IO) {
        if (sessions.getAndIncrement() == 0) runCatching { config.casting(true) }
        val p = CastSession(device, scope, server, config) { closed() }
        try {
            p.open(appId)
        } catch (e: Exception) {
            p.close(stopReceiver = false)
            throw CastException("Couldn't play on ${device.name}: ${e.message}", e)
        }
        p
    }

    private fun closed() {
        if (sessions.decrementAndGet() == 0) {
            runCatching { config.casting(false) }
            server.forget()
        }
    }

    /** stops the scan and the background work; open sessions should be closed first */
    override fun close() {
        scanJob?.cancel()
        scope.cancel()
        server.forget()
    }
}

/**
 * Hooks into the platform. The defaults suit desktops; `AndroidCastwire.config(context)` fills them
 * in for Android.
 */
data class CastwireConfig(
    /** true before a search, false after: Android drops multicast packets unless a MulticastLock is held */
    val multicast: (Boolean) -> Unit = {},
    /** true when the first session opens, false when the last closes: keep the app and Wi-Fi awake meanwhile */
    val casting: (Boolean) -> Unit = {},
    /** how media the TV can't fetch itself is fetched for it: pass your app's client to share its pool */
    val httpClient: OkHttpClient = OkHttpClient(),
    /** how often a playing session asks the device for its status, in milliseconds */
    val statusIntervalMs: Long = 1000,
    /** diagnostics, for your logger */
    val log: ((String) -> Unit)? = null,
)
