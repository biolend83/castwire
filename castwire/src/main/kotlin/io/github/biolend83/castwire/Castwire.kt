package io.github.biolend83.castwire

import io.github.biolend83.castwire.internal.Channel
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Google Cast without Google's SDK: finds Cast devices on the local network and plays media on them,
 * from plain Kotlin/Java on Android, Windows, macOS and Linux. No Play Services.
 *
 * ```
 * val cast = Castwire()
 * val tv = cast.discover().first()
 * val session = cast.connect(tv)
 * session.load(CastMedia(CastSource.Url("https://example.com/video.mp4", "video/mp4"), MediaMetadata("A video")))
 * ```
 *
 * One instance per app is enough; it owns a background scope and the [relay]. On Android, create it
 * with `AndroidCastwire.create(context)` (castwire-android), which handles the multicast and Wi-Fi locks.
 */
class Castwire(private val config: CastwireConfig = CastwireConfig()) : AutoCloseable {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** serves media to the TV from this device (see [LocalRelay]); started on first use */
    val relay: LocalRelay by lazy { LocalRelay(config.fetcher, log = config.log) }

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    /** the devices found by the last [scan], in name order; updated as they answer */
    val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val sessions = AtomicInteger(0)
    private var scanJob: Job? = null

    /**
     * Looks for devices for [durationMs] in the background, filling [devices] as they answer. Devices
     * from the previous scan stay listed until this one ends without them. Does nothing while a scan
     * is running.
     */
    fun scan(durationMs: Long = 4000) {
        if (_scanning.value) return
        _scanning.value = true
        scanJob = scope.launch {
            val found = LinkedHashMap<String, CastDevice>()
            val before = _devices.value
            try {
                discoverInto(durationMs) { d ->
                    synchronized(found) {
                        found[d.id] = d
                        _devices.value = (before.filter { it.id !in found } + found.values).sortedBy { it.name.lowercase() }
                    }
                }
            } finally {
                _devices.value = synchronized(found) { found.values.sortedBy { it.name.lowercase() } }
                _scanning.value = false
            }
        }
    }

    /** looks for devices for [durationMs] and returns what answered, in name order */
    suspend fun discover(durationMs: Long = 4000): List<CastDevice> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, CastDevice>()
        discoverInto(durationMs) { d -> synchronized(found) { found[d.id] = d } }
        synchronized(found) { found.values.sortedBy { it.name.lowercase() } }
    }

    private fun discoverInto(durationMs: Long, found: (CastDevice) -> Unit) {
        runCatching { config.multicast(true) }
        try {
            Mdns.discover(durationMs, found)
        } catch (e: Exception) {
            config.log?.invoke("discovery failed: $e")
        } finally {
            runCatching { config.multicast(false) }
        }
    }

    /**
     * Connects to [device] and starts [appId] on it: Google's Default Media Receiver unless you have
     * your own receiver app. Joins the app instead of restarting it when it is already running.
     *
     * @throws CastException when the device can't be reached or won't start the app
     */
    suspend fun connect(device: CastDevice, appId: String = CastSession.DEFAULT_MEDIA_RECEIVER): CastSession = withContext(Dispatchers.IO) {
        val ch = try {
            Channel(device.host, device.port, config.log)
        } catch (e: Exception) {
            throw CastException("Couldn't connect to ${device.name}: ${e.message}", e)
        }
        val s = CastSession(device, ch, this@Castwire, appId, config)
        if (sessions.getAndIncrement() == 0) runCatching { config.casting(true) }
        try {
            s.open()
        } catch (e: Exception) {
            s.close(stopReceiver = false)
            throw if (e is CastException) e else CastException("${device.name} didn't start the player: ${e.message}", e)
        }
        s
    }

    internal fun sessionClosed() {
        if (sessions.decrementAndGet() == 0) {
            runCatching { config.casting(false) }
            // nothing plays any more: the addresses handed to TVs stop working
            runCatching { relay.clear() }
        }
    }

    /** stops the scan, the relay and the background work; open sessions should be closed first */
    override fun close() {
        scanJob?.cancel()
        scope.cancel()
        runCatching { relay.close() }
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
    /** how the relay fetches remote media (your HTTP client, your user agent) */
    val fetcher: HttpFetcher = HttpFetcher.Default,
    /** how often a playing session asks the device for its status, in milliseconds */
    val statusIntervalMs: Long = 1000,
    /** diagnostics, for your logger */
    val log: ((String) -> Unit)? = null,
)
