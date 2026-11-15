package io.github.biolend83.castwire

import io.github.biolend83.castwire.internal.CastChannel
import io.github.biolend83.castwire.internal.CastServer
import io.github.biolend83.castwire.internal.NS_CONNECTION
import io.github.biolend83.castwire.internal.NS_MEDIA
import io.github.biolend83.castwire.internal.NS_RECEIVER
import io.github.biolend83.castwire.internal.num
import io.github.biolend83.castwire.internal.obj
import io.github.biolend83.castwire.internal.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException

/**
 * What plays on one Cast device, from [Castwire.connect]: TubeHaus's CastPlayer (GoogleCast.kt), with
 * any media instead of a YouTube stream.
 *
 * Control calls ([play], [pause], [seek]...) can be made from any thread and return at once; what the
 * TV then does arrives in [status]. [events] reports what isn't a status: the connection dropping, the
 * TV leaving the app, the media ending. [close] when done.
 */
class CastSession internal constructor(
    val device: CastDevice,
    private val scope: CoroutineScope,
    private val server: CastServer,
    private val config: CastwireConfig,
    private val onClosed: () -> Unit,
) : AutoCloseable {
    companion object {
        /** Google's Default Media Receiver: the player every Cast device has, no app of our own needed */
        const val DEFAULT_MEDIA_RECEIVER = "CC1AD845"
    }

    private val st = MutableStateFlow(MediaStatus())
    /** what the TV is doing, from its last report */
    val status: StateFlow<MediaStatus> = st.asStateFlow()

    private val vol = MutableStateFlow(Volume())
    /** the device's own volume (what its remote changes too) */
    val volume: StateFlow<Volume> = vol.asStateFlow()

    private val _connected = MutableStateFlow(true)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _events = MutableSharedFlow<CastEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<CastEvent> = _events.asSharedFlow()

    private val _messages = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 64)

    /** the receiver app: Google's Default Media Receiver unless another was asked for */
    var appId: String = DEFAULT_MEDIA_RECEIVER
        private set

    private var ch: CastChannel? = null
    @Volatile private var transport: String? = null
    @Volatile private var session: String? = null
    @Volatile private var media: Int? = null
    @Volatile private var left = false
    private var poll: Job? = null
    /** while a LOAD is on its way, statuses about what played before are ignored */
    @Volatile private var loading = false
    private val loadLock = Mutex()

    internal suspend fun open(app: String) {
        appId = app
        val c = CastChannel(device.host, device.port)
        ch = c
        c.onMessage = ::onMessage
        c.onClosed = { why -> if (!left) { _connected.value = false; _events.tryEmit(CastEvent.Disconnected(why)) } }
        c.send(NS_CONNECTION, "receiver-0", json { put("type", "CONNECT") }, withId = false)
        val r = c.request(NS_RECEIVER, "receiver-0", json { put("type", "LAUNCH"); put("appId", appId) }, 20_000)
        if (!takeApp(r)) {
            // some devices answer the launch before the player is up: ask again
            delay(1500)
            if (!takeApp(c.request(NS_RECEIVER, "receiver-0", json { put("type", "GET_STATUS") }))) throw IOException("the device didn't start its player")
        }
        c.send(NS_CONNECTION, transport!!, json { put("type", "CONNECT") }, withId = false)
        poll = scope.launch(Dispatchers.IO) {
            while (isActive && !left) {
                delay(config.statusIntervalMs)
                val m = media
                val t = transport
                if (m != null && t != null && !loading) ch?.send(NS_MEDIA, t, json { put("type", "GET_STATUS"); put("mediaSessionId", m) })
            }
        }
    }

    /** our player app in a receiver status: remember where to talk to it; false when it isn't running */
    private fun takeApp(r: JsonObject): Boolean {
        val s = r.obj("status") ?: return false
        s.obj("volume")?.let { v -> vol.value = Volume(v.num("level")?.toFloat() ?: vol.value.level, (v["muted"] as? JsonPrimitive)?.content == "true") }
        val apps = s["applications"] as? JsonArray ?: return false
        val app = apps.mapNotNull { it as? JsonObject }.firstOrNull { it.str("appId") == appId } ?: return false
        transport = app.str("transportId") ?: return false
        session = app.str("sessionId")
        return true
    }

    private fun onMessage(ns: String, source: String, m: JsonObject) {
        when (ns) {
            NS_RECEIVER -> if (m.str("type") == "RECEIVER_STATUS") {
                val had = session
                if (!takeApp(m) && had != null && transport != null && !left) {
                    // the TV went back to its home screen, or another phone started something else
                    _events.tryEmit(CastEvent.ReceiverStopped)
                }
            }
            NS_MEDIA -> when (m.str("type")) {
                "MEDIA_STATUS" -> (m["status"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.let(::status) }
                "LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST", "ERROR" -> if (!loading) failed(m.str("type") ?: "ERROR")
            }
            NS_CONNECTION -> if (m.str("type") == "CLOSE" && source == transport && !left) _events.tryEmit(CastEvent.ReceiverStopped)
        }
        if (source == transport) _messages.tryEmit(ns to m.toString())
    }

    private fun failed(why: String) {
        _events.tryEmit(CastEvent.PlaybackError(why))
    }

    /** a MEDIA_STATUS of what plays now */
    private fun status(m: JsonObject) {
        val id = m.num("mediaSessionId")?.toInt()
        if (loading) return
        if (id != null) media = id
        val ps = m.str("playerState") ?: return
        val idle = m.str("idleReason")
        if (ps == "IDLE" && idle == "ERROR") failed("the receiver stopped with an error")
        val pos = m.num("currentTime")?.let { (it * 1000).toLong() }
        val dur = m.obj("media")?.num("duration")?.takeIf { it > 0 }?.let { (it * 1000).toLong() }
        val ended = ps == "IDLE" && idle == "FINISHED"
        val cur = st.value
        val wasEnded = cur.isFinished
        st.value = cur.copy(
            playerState = when (ps) {
                "PLAYING" -> PlayerState.PLAYING; "PAUSED" -> PlayerState.PAUSED; "BUFFERING" -> PlayerState.BUFFERING
                "LOADING" -> PlayerState.LOADING; else -> PlayerState.IDLE
            },
            idleReason = if (ps != "IDLE") null else when (idle) {
                "FINISHED" -> IdleReason.FINISHED; "CANCELLED" -> IdleReason.CANCELLED
                "INTERRUPTED" -> IdleReason.INTERRUPTED; "ERROR" -> IdleReason.ERROR; else -> null
            },
            positionMs = if (ended) cur.durationMs.takeIf { it > 0 } ?: pos ?: cur.positionMs else pos ?: cur.positionMs,
            durationMs = dur ?: cur.durationMs,
            playbackRate = m.num("playbackRate") ?: cur.playbackRate,
            activeTrackIds = (m["activeTrackIds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() } ?: cur.activeTrackIds,
            mediaSessionId = media,
            receivedAtMs = nowMs(),
        )
        if (ended && !wasEnded) _events.tryEmit(CastEvent.Finished)
    }

    /** the position now, moved on from the last report by the time since it while playing */
    fun positionMs(): Long {
        val s = st.value
        if (s.playerState != PlayerState.PLAYING) return s.positionMs
        val p = s.positionMs + ((nowMs() - s.receivedAtMs) * s.playbackRate).toLong()
        return if (s.durationMs > 0) p.coerceAtMost(s.durationMs) else p
    }

    // ------------------------------------------------------------------ loading

    /**
     * Plays [media] from [startPositionMs], replacing what played before. Sources marked `relay` (and
     * local files) are served to the TV from this device. Returns the first status of the new media.
     *
     * @throws CastException when the device rejects the media or doesn't answer
     */
    suspend fun load(
        media: CastMedia,
        startPositionMs: Long = 0,
        autoplay: Boolean = true,
        activeTrackIds: List<Int> = emptyList(),
    ): MediaStatus = withContext(Dispatchers.IO) {
        loadLock.withLock {
            val t = transport ?: throw CastException("not connected")
            loading = true
            try {
                val base = server.base(device.host)
                val info = mediaInfo(media, base)
                val r = ch!!.request(NS_MEDIA, t, json {
                    put("type", "LOAD")
                    session?.let { put("sessionId", it) }
                    put("media", info)
                    put("autoplay", autoplay)
                    if (media.streamType != StreamType.LIVE) put("currentTime", startPositionMs / 1000.0)
                    putJsonArray("activeTrackIds") { for (id in activeTrackIds) add(id) }
                    media.customData?.let { put("customData", toJson(it)) }
                }, 30_000)
                loading = false
                when (r.str("type")) {
                    "MEDIA_STATUS" -> (r["status"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.let(::status) }
                    else -> throw CastException("${device.name} couldn't play this: ${r.str("type")}${r.str("detailedErrorCode")?.let { " ($it)" } ?: ""}")
                }
                st.value
            } catch (e: CastException) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                throw CastException("${device.name} didn't answer the load: ${e.message}", e)
            } finally {
                loading = false
            }
        }
    }

    private fun mediaInfo(m: CastMedia, base: String): JsonObject = buildJsonObject {
        fun via(url: String, relay: Boolean, headers: Map<String, String>) = if (relay) server.proxy(base, url, headers) else url
        val (id, type) = when (val s = m.source) {
            is CastSource.Url -> via(s.url, s.relay, s.headers) to s.contentType
            is CastSource.LocalFile -> server.proxy(base, s.file.toURI().toString()) to s.contentType
            is CastSource.Hls -> via(s.url, s.relay, s.headers) to "application/x-mpegURL"
            is CastSource.DashUrl -> via(s.url, s.relay, s.headers) to "application/dash+xml"
            is CastSource.Dash -> {
                val tracks = s.tracks.map { it.copy(url = via(it.url, s.relay, s.headers)) }
                server.text(base, DashManifest.build(tracks, m.durationSec ?: 0.0), "application/dash+xml", "mpd") to "application/dash+xml"
            }
        }
        put("contentId", id)
        put("contentType", type)
        put("streamType", if (m.streamType == StreamType.LIVE) "LIVE" else "BUFFERED")
        m.durationSec?.let { put("duration", it) }
        putJsonObject("metadata") {
            put("metadataType", m.metadata.kind.code)
            put("title", m.metadata.title)
            put("subtitle", m.metadata.subtitle)
            if (m.metadata.kind == MediaMetadata.Kind.MUSIC) put("artist", m.metadata.subtitle)
            m.metadata.imageUrl?.let { u -> putJsonArray("images") { addJsonObject { put("url", u) } } }
        }
        if (m.textTracks.isNotEmpty()) putJsonArray("tracks") {
            for (t in m.textTracks) addJsonObject {
                put("trackId", t.id)
                put("type", "TEXT")
                put("subtype", t.kind.name)
                put("trackContentId", via(t.url, t.relay, t.headers))
                put("trackContentType", t.contentType)
                put("language", t.language)
                put("name", t.name)
            }
        }
    }

    // ------------------------------------------------------------------ controls

    private fun cmd(type: String, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) {
        val t = transport ?: return
        val m = media ?: return
        ch?.send(NS_MEDIA, t, json { put("type", type); put("mediaSessionId", m); extra() })
    }

    fun play() {
        if (st.value.isFinished) { seek(0); cmd("PLAY") } else cmd("PLAY")
        st.value = st.value.copy(playerState = PlayerState.PLAYING, idleReason = null, positionMs = positionMs(), receivedAtMs = nowMs())
    }

    fun pause() {
        val p = positionMs()
        cmd("PAUSE")
        st.value = st.value.copy(playerState = PlayerState.PAUSED, positionMs = p, receivedAtMs = nowMs())
    }

    fun seek(positionMs: Long) {
        cmd("SEEK") { put("currentTime", positionMs / 1000.0) }
        st.value = st.value.copy(positionMs = positionMs, receivedAtMs = nowMs())
    }

    /** 0.5 to 2.0 on most devices */
    fun setPlaybackRate(rate: Double) {
        cmd("SET_PLAYBACK_RATE") { put("playbackRate", rate) }
        st.value = st.value.copy(positionMs = positionMs(), playbackRate = rate, receivedAtMs = nowMs())
    }

    /** shows the text tracks with these ids (from [CastMedia.textTracks]); empty hides them all */
    fun setActiveTracks(ids: List<Int>) {
        cmd("EDIT_TRACKS_INFO") { putJsonArray("activeTrackIds") { for (id in ids) add(id) } }
        st.value = st.value.copy(activeTrackIds = ids)
    }

    /** stops the media; the player stays open on the TV for the next [load] */
    fun stop() {
        loading = false
        cmd("STOP")
        media = null
        st.value = MediaStatus(receivedAtMs = nowMs())
    }

    /** the device's own volume, 0 to 1 */
    fun setVolume(level: Float) {
        val x = level.coerceIn(0f, 1f)
        ch?.send(NS_RECEIVER, "receiver-0", json { put("type", "SET_VOLUME"); putJsonObject("volume") { put("level", x.toDouble()) } })
        vol.value = vol.value.copy(level = x)
    }

    fun setMuted(muted: Boolean) {
        ch?.send(NS_RECEIVER, "receiver-0", json { put("type", "SET_VOLUME"); putJsonObject("volume") { put("muted", muted) } })
        vol.value = vol.value.copy(muted = muted)
    }

    // ------------------------------------------------------------------ custom receivers

    /** a JSON message on [namespace] to the receiver app (for your own receiver) */
    fun send(namespace: String, message: Map<String, Any?>) {
        val t = transport ?: return
        ch?.send(namespace, t, toJson(message) as JsonObject, withId = false)
    }

    /** the receiver app's messages on [namespace], as JSON text */
    fun messages(namespace: String): Flow<String> = _messages.filter { it.first == namespace }.map { it.second }

    // ------------------------------------------------------------------ ending

    /** leaves the device: the TV leaves the player and goes back to its own screen */
    override fun close() = close(stopReceiver = true)

    /** [stopReceiver] false leaves the player (and what it plays) running on the TV */
    fun close(stopReceiver: Boolean) {
        if (left) return
        left = true
        poll?.cancel()
        _connected.value = false
        val c = ch
        if (c != null) {
            if (stopReceiver) session?.let { sid -> c.send(NS_RECEIVER, "receiver-0", json { put("type", "STOP"); put("sessionId", sid) }) }
            transport?.let { t -> c.send(NS_CONNECTION, t, json { put("type", "CLOSE") }, withId = false) }
            c.send(NS_CONNECTION, "receiver-0", json { put("type", "CLOSE") }, withId = false)
            c.close()
        }
        onClosed()
    }
}

private inline fun json(b: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(b)

private fun nowMs() = System.nanoTime() / 1_000_000

/** maps, lists, strings, numbers and booleans as JSON */
internal fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
    is Iterable<*> -> JsonArray(v.map { toJson(it) })
    else -> JsonPrimitive(v.toString())
}
