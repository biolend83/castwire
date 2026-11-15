package io.github.biolend83.castwire

import io.github.biolend83.castwire.internal.Channel
import io.github.biolend83.castwire.internal.NS_CONNECTION
import io.github.biolend83.castwire.internal.NS_MEDIA
import io.github.biolend83.castwire.internal.NS_RECEIVER
import io.github.biolend83.castwire.internal.PLATFORM
import io.github.biolend83.castwire.internal.bool
import io.github.biolend83.castwire.internal.list
import io.github.biolend83.castwire.internal.num
import io.github.biolend83.castwire.internal.obj
import io.github.biolend83.castwire.internal.str
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.Dispatchers

/**
 * A connection to one device with a receiver app running on it, from [Castwire.connect].
 *
 * Control calls ([play], [pause], [seek]...) can be made from any thread and return at once; what the
 * TV then does arrives in [status]. [events] reports what isn't a status: the connection dropping, the
 * TV leaving the app, the media ending. [close] when done.
 */
class CastSession internal constructor(
    val device: CastDevice,
    private val ch: Channel,
    private val owner: Castwire,
    val appId: String,
    private val config: CastwireConfig,
) : AutoCloseable {
    companion object {
        /** Google's Default Media Receiver: the player every Cast device has, no receiver app of your own needed */
        const val DEFAULT_MEDIA_RECEIVER = "CC1AD845"
    }

    private val _status = MutableStateFlow(MediaStatus())
    /** what the TV is doing, from its last report */
    val status: StateFlow<MediaStatus> = _status.asStateFlow()

    private val _volume = MutableStateFlow(Volume())
    /** the device's own volume (what its remote changes too) */
    val volume: StateFlow<Volume> = _volume.asStateFlow()

    private val _connected = MutableStateFlow(true)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _events = MutableSharedFlow<CastEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<CastEvent> = _events.asSharedFlow()

    private val _messages = MutableSharedFlow<Pair<String, Map<String, Any?>>>(extraBufferCapacity = 64)

    @Volatile private var transport: String? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var mediaSessionId: Int? = null
    @Volatile private var closed = false
    /** while a LOAD is on its way, statuses about what played before are ignored */
    @Volatile private var loading = false
    private val loadLock = Mutex()
    private var poll: Job? = null

    internal suspend fun open() {
        ch.onMessage = ::onMessage
        ch.onClosed = { why -> lost(CastEvent.Disconnected(why)) }
        ch.send(NS_CONNECTION, PLATFORM, mapOf("type" to "CONNECT"), withId = false)
        // join the app when it already runs (another phone started it, or this app reconnects)
        val now = ch.request(NS_RECEIVER, PLATFORM, mapOf("type" to "GET_STATUS"))
        if (!takeApp(now)) {
            val r = ch.request(NS_RECEIVER, PLATFORM, mapOf("type" to "LAUNCH", "appId" to appId), 20_000)
            if (r.str("type") == "LAUNCH_ERROR") throw CastException("${device.name} refused to start $appId: ${r.str("reason") ?: "unknown reason"}")
            if (!takeApp(r)) {
                // some devices answer before the app is up: ask again
                delay(1500)
                if (!takeApp(ch.request(NS_RECEIVER, PLATFORM, mapOf("type" to "GET_STATUS")))) throw CastException("${device.name} didn't start $appId")
            }
        }
        ch.send(NS_CONNECTION, transport!!, mapOf("type" to "CONNECT"), withId = false)
        if (appId == DEFAULT_MEDIA_RECEIVER) ch.send(NS_MEDIA, transport!!, mapOf("type" to "GET_STATUS"))
        poll = owner.scope.launch {
            while (isActive && !closed) {
                delay(config.statusIntervalMs)
                val m = mediaSessionId
                val t = transport
                val st = _status.value.playerState
                if (m != null && t != null && !loading && st != PlayerState.IDLE) ch.send(NS_MEDIA, t, mapOf("type" to "GET_STATUS", "mediaSessionId" to m))
            }
        }
    }

    /** the app in a receiver status; false when it isn't running */
    private fun takeApp(r: Map<String, Any?>): Boolean {
        val s = r.obj("status") ?: return false
        s.obj("volume")?.let { v -> _volume.value = Volume(v.num("level")?.toFloat() ?: _volume.value.level, v.bool("muted") ?: _volume.value.muted) }
        val app = s.list("applications").filterIsInstance<Map<String, Any?>>().firstOrNull { it.str("appId") == appId } ?: return false
        transport = app.str("transportId") ?: return false
        sessionId = app.str("sessionId")
        return true
    }

    private fun onMessage(ns: String, source: String, m: Map<String, Any?>) {
        when (ns) {
            NS_RECEIVER -> if (m.str("type") == "RECEIVER_STATUS") {
                val had = sessionId
                if (!takeApp(m) && had != null && transport != null && !closed) lost(CastEvent.ReceiverStopped)
            }
            NS_MEDIA -> when (m.str("type")) {
                "MEDIA_STATUS" -> m.list("status").firstOrNull()?.let { @Suppress("UNCHECKED_CAST") (it as? Map<String, Any?>)?.let(::takeStatus) }
                "LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST", "ERROR" ->
                    if (!loading) _events.tryEmit(CastEvent.PlaybackError(m.str("detailedErrorCode")?.let { "${m.str("type")} $it" } ?: m.str("type")!!))
            }
            NS_CONNECTION -> if (m.str("type") == "CLOSE" && source == transport && !closed) lost(CastEvent.ReceiverStopped)
        }
        if (source == transport) _messages.tryEmit(ns to m)
    }

    private fun takeStatus(m: Map<String, Any?>) {
        if (loading) return
        m.num("mediaSessionId")?.toInt()?.let { mediaSessionId = it }
        m.obj("volume")?.let { v -> _volume.value = Volume(v.num("level")?.toFloat() ?: _volume.value.level, v.bool("muted") ?: _volume.value.muted) }
        val ps = when (m.str("playerState")) {
            "PLAYING" -> PlayerState.PLAYING; "PAUSED" -> PlayerState.PAUSED; "BUFFERING" -> PlayerState.BUFFERING
            "LOADING" -> PlayerState.LOADING; "IDLE" -> PlayerState.IDLE; else -> return
        }
        val idle = when (m.str("idleReason")) {
            "FINISHED" -> IdleReason.FINISHED; "CANCELLED" -> IdleReason.CANCELLED
            "INTERRUPTED" -> IdleReason.INTERRUPTED; "ERROR" -> IdleReason.ERROR; else -> null
        }
        val cur = _status.value
        val dur = m.obj("media")?.num("duration")?.takeIf { it > 0 }?.let { (it * 1000).toLong() } ?: cur.durationMs
        val pos = m.num("currentTime")?.let { (it * 1000).toLong() } ?: cur.positionMs
        val next = MediaStatus(
            playerState = ps,
            idleReason = if (ps == PlayerState.IDLE) idle else null,
            positionMs = if (ps == PlayerState.IDLE && idle == IdleReason.FINISHED && dur > 0) dur else pos,
            durationMs = dur,
            playbackRate = m.num("playbackRate") ?: cur.playbackRate,
            activeTrackIds = (m["activeTrackIds"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: cur.activeTrackIds,
            mediaSessionId = if (ps == PlayerState.IDLE && idle != null) null else mediaSessionId,
            receivedAtMs = nowMs(),
        )
        val wasFinished = cur.isFinished
        _status.value = next
        if (ps == PlayerState.IDLE && idle == IdleReason.FINISHED && !wasFinished) _events.tryEmit(CastEvent.Finished)
        if (ps == PlayerState.IDLE && idle == IdleReason.ERROR) _events.tryEmit(CastEvent.PlaybackError("the receiver stopped with an error"))
    }

    private fun lost(e: CastEvent) {
        if (closed) return
        if (e is CastEvent.Disconnected) _connected.value = false
        _events.tryEmit(e)
    }

    /** the position now, moved on from the last report by the time since it while playing */
    fun positionMs(): Long {
        val s = _status.value
        if (s.playerState != PlayerState.PLAYING) return s.positionMs
        val p = s.positionMs + ((nowMs() - s.receivedAtMs) * s.playbackRate).toLong()
        return if (s.durationMs > 0) p.coerceAtMost(s.durationMs) else p
    }

    // ------------------------------------------------------------------ loading

    /**
     * Plays [media] from [startPositionMs], replacing what played before. Sources marked `relay` (and
     * local files) are served through [Castwire.relay]. Returns the first status of the new media.
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
                val info = mediaInfo(media)
                val r = ch.request(NS_MEDIA, t, buildMap {
                    put("type", "LOAD")
                    sessionId?.let { put("sessionId", it) }
                    put("media", info)
                    put("autoplay", autoplay)
                    if (media.streamType != StreamType.LIVE) put("currentTime", startPositionMs / 1000.0)
                    put("activeTrackIds", activeTrackIds)
                    media.customData?.let { put("customData", it) }
                }, 30_000)
                loading = false
                if (r.str("type") != "MEDIA_STATUS") {
                    throw CastException("${device.name} couldn't play this: ${r.str("type")}${r.str("detailedErrorCode")?.let { " ($it)" } ?: ""}")
                }
                @Suppress("UNCHECKED_CAST")
                (r.list("status").firstOrNull() as? Map<String, Any?>)?.let(::takeStatus)
                _status.value
            } catch (e: CancellationException) {
                throw e
            } catch (e: CastException) {
                throw e
            } catch (e: Exception) {
                throw CastException("${device.name} didn't answer the load: ${e.message}", e)
            } finally {
                loading = false
            }
        }
    }

    private fun mediaInfo(m: CastMedia): Map<String, Any?> {
        val needsRelay = when (val s = m.source) {
            is CastSource.Url -> s.relay; is CastSource.LocalFile -> true; is CastSource.Hls -> s.relay
            is CastSource.DashUrl -> s.relay; is CastSource.Dash -> true
        } || m.textTracks.any { it.relay }
        val base = if (needsRelay) owner.relay.baseFor(device.host) else ""
        val relay = owner.relay
        fun via(url: String, on: Boolean, headers: Map<String, String>) = if (on) relay.url(base, url, headers) else url
        val (contentId, contentType) = when (val s = m.source) {
            is CastSource.Url -> via(s.url, s.relay, s.headers) to s.contentType
            is CastSource.LocalFile -> relay.file(base, s.file, s.contentType) to s.contentType
            is CastSource.Hls -> via(s.url, s.relay, s.headers) to "application/x-mpegURL"
            is CastSource.DashUrl -> via(s.url, s.relay, s.headers) to "application/dash+xml"
            is CastSource.Dash -> {
                val tracks = s.tracks.map { it.copy(url = via(it.url, s.relay, s.headers)) }
                val mpd = DashManifest.build(tracks, m.durationSec ?: 0.0)
                relay.text(base, mpd, "application/dash+xml", "mpd") to "application/dash+xml"
            }
        }
        return buildMap {
            put("contentId", contentId)
            put("contentUrl", contentId)
            put("contentType", contentType)
            put("streamType", if (m.streamType == StreamType.LIVE) "LIVE" else "BUFFERED")
            m.durationSec?.let { put("duration", it) }
            put("metadata", buildMap {
                val md = m.metadata
                put("metadataType", md.kind.code)
                put("title", md.title)
                if (md.kind == MediaMetadata.Kind.MUSIC) { put("artist", md.subtitle); put("subtitle", md.subtitle) } else put("subtitle", md.subtitle)
                md.imageUrl?.let { put("images", listOf(mapOf("url" to it))) }
            })
            if (m.textTracks.isNotEmpty()) put("tracks", m.textTracks.map { t ->
                mapOf(
                    "trackId" to t.id,
                    "type" to "TEXT",
                    "subtype" to t.kind.name,
                    "trackContentId" to via(t.url, t.relay, t.headers),
                    "trackContentType" to t.contentType,
                    "language" to t.language,
                    "name" to t.name,
                )
            })
        }
    }

    // ------------------------------------------------------------------ controls

    private fun cmd(type: String, extra: Map<String, Any?> = emptyMap()) {
        val t = transport ?: return
        val m = mediaSessionId ?: return
        ch.send(NS_MEDIA, t, mapOf("type" to type, "mediaSessionId" to m) + extra)
    }

    fun play() {
        cmd("PLAY")
        _status.value = _status.value.let { it.copy(playerState = PlayerState.PLAYING, positionMs = positionMs(), receivedAtMs = nowMs()) }
    }

    fun pause() {
        val p = positionMs()
        cmd("PAUSE")
        _status.value = _status.value.copy(playerState = PlayerState.PAUSED, positionMs = p, receivedAtMs = nowMs())
    }

    fun seek(positionMs: Long) {
        cmd("SEEK", mapOf("currentTime" to positionMs / 1000.0))
        _status.value = _status.value.copy(positionMs = positionMs, receivedAtMs = nowMs())
    }

    /** 0.5 to 2.0 on most devices */
    fun setPlaybackRate(rate: Double) {
        cmd("SET_PLAYBACK_RATE", mapOf("playbackRate" to rate))
        _status.value = _status.value.copy(positionMs = positionMs(), playbackRate = rate, receivedAtMs = nowMs())
    }

    /** shows the text tracks with these ids (from [CastMedia.textTracks]); empty hides them all */
    fun setActiveTracks(ids: List<Int>) {
        cmd("EDIT_TRACKS_INFO", mapOf("activeTrackIds" to ids))
        _status.value = _status.value.copy(activeTrackIds = ids)
    }

    /** stops the media; the receiver app stays open for the next [load] */
    fun stop() {
        cmd("STOP")
        mediaSessionId = null
        _status.value = MediaStatus(receivedAtMs = nowMs())
    }

    /** the device's own volume, 0 to 1 */
    fun setVolume(level: Float) {
        val x = level.coerceIn(0f, 1f)
        ch.send(NS_RECEIVER, PLATFORM, mapOf("type" to "SET_VOLUME", "volume" to mapOf("level" to x.toDouble())))
        _volume.value = _volume.value.copy(level = x)
    }

    fun setMuted(muted: Boolean) {
        ch.send(NS_RECEIVER, PLATFORM, mapOf("type" to "SET_VOLUME", "volume" to mapOf("muted" to muted)))
        _volume.value = _volume.value.copy(muted = muted)
    }

    // ------------------------------------------------------------------ custom receivers

    /** a JSON message on [namespace] to the receiver app (for your own receiver) */
    fun send(namespace: String, message: Map<String, Any?>) {
        val t = transport ?: return
        ch.send(namespace, t, message, withId = false)
    }

    /** a raw string message on [namespace] */
    fun sendRaw(namespace: String, message: String) {
        val t = transport ?: return
        ch.sendRaw(namespace, t, message)
    }

    /** the receiver app's messages on [namespace] */
    fun messages(namespace: String): Flow<Map<String, Any?>> = _messages.filter { it.first == namespace }.map { it.second }

    // ------------------------------------------------------------------ ending

    /** leaves the device: the receiver app is stopped, so the TV goes back to its home screen */
    override fun close() = close(stopReceiver = true)

    /** [stopReceiver] false leaves the receiver app (and what it plays) running, for another sender to join */
    fun close(stopReceiver: Boolean) {
        if (closed) return
        closed = true
        poll?.cancel()
        if (stopReceiver) sessionId?.let { sid -> ch.send(NS_RECEIVER, PLATFORM, mapOf("type" to "STOP", "sessionId" to sid)) }
        transport?.let { t -> ch.send(NS_CONNECTION, t, mapOf("type" to "CLOSE"), withId = false) }
        ch.send(NS_CONNECTION, PLATFORM, mapOf("type" to "CLOSE"), withId = false)
        ch.close()
        _connected.value = false
        owner.sessionClosed()
    }
}

private fun nowMs() = System.nanoTime() / 1_000_000
