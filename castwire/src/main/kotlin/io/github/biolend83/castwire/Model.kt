package io.github.biolend83.castwire

import java.io.File

/**
 * A Cast device on the local network: a Chromecast, a Google TV, a TV or speaker with Cast built in,
 * or a speaker group.
 *
 * Found by [Castwire.scan] / [Castwire.discover], or made by hand with [at] when the address is known
 * (multicast blocked, a different subnet).
 */
data class CastDevice(
    /** stable id the device announces (survives address changes) */
    val id: String,
    /** the name the user gave it ("Living room TV") */
    val name: String,
    /** the model ("Chromecast", "Google TV Streamer", a TV maker's model) */
    val model: String = "",
    val host: String,
    val port: Int = 8009,
    /** the announced capability bits ("ca"), or -1 when unknown */
    val capabilities: Int = -1,
) {
    /** can show video (false for speakers and speaker groups); true when unknown */
    val hasVideo: Boolean get() = capabilities < 0 || capabilities and 1 != 0
    /** a speaker group (several speakers playing as one) */
    val isGroup: Boolean get() = capabilities >= 0 && capabilities and 32 != 0

    companion object {
        /** a device at a known address, without discovery */
        fun at(host: String, port: Int = 8009, name: String = host) = CastDevice("manual:$host:$port", name, "", host, port)
    }
}

/** What to play: where the media comes from, and what to show about it. */
data class CastMedia(
    val source: CastSource,
    val metadata: MediaMetadata = MediaMetadata(),
    /** BUFFERED for files and videos; LIVE for live streams (no seek bar on the TV) */
    val streamType: StreamType = StreamType.BUFFERED,
    /** length in seconds, when known (the TV shows it before it has read the file) */
    val durationSec: Double? = null,
    /** subtitle tracks the TV can switch between; turn them on with [CastSession.setActiveTracks] */
    val textTracks: List<TextTrack> = emptyList(),
    /** passed to a custom receiver as `customData` */
    val customData: Map<String, Any?>? = null,
)

/**
 * Where the TV gets the media from.
 *
 * A TV fetches media itself, from its own address, as a web page would: it can't send cookies or
 * headers, it gets no access to files on the phone, and it needs CORS headers some servers don't
 * send. Sources with `relay = true` (and every [File]) are served to the TV by this device instead,
 * by Castwire's relay (TubeHaus's CastServer); plain public URLs go to the TV directly.
 */
sealed class CastSource {
    /** a single file: MP4, WebM, MP3, AAC, an image... */
    data class Url(
        val url: String,
        val contentType: String,
        val relay: Boolean = false,
        /** request headers, used when relayed (cookies, authorization, a referer) */
        val headers: Map<String, String> = emptyMap(),
    ) : CastSource()

    /** a file on this device, always relayed */
    data class LocalFile(val file: File, val contentType: String = contentTypeOf(file.name)) : CastSource()

    /** an HLS playlist; when relayed, every address inside it is rewritten to go through the relay too */
    data class Hls(val url: String, val relay: Boolean = false, val headers: Map<String, String> = emptyMap()) : CastSource()

    /** a DASH manifest at a URL */
    data class DashUrl(val url: String, val relay: Boolean = false, val headers: Map<String, String> = emptyMap()) : CastSource()

    /**
     * Separate video and audio streams (as YouTube and most video sites serve them) joined into one
     * DASH manifest on this device; the TV picks the quality. See [DashTrack].
     */
    data class Dash(val tracks: List<DashTrack>, val relay: Boolean = true, val headers: Map<String, String> = emptyMap()) : CastSource()
}

enum class StreamType { BUFFERED, LIVE }

/** What the TV shows about the media. */
data class MediaMetadata(
    val title: String = "",
    /** the second line: a channel, an artist, an episode */
    val subtitle: String = "",
    val imageUrl: String? = null,
    val kind: Kind = Kind.GENERIC,
) {
    enum class Kind(internal val code: Int) { GENERIC(0), MOVIE(1), TV_SHOW(2), MUSIC(3), PHOTO(4) }
}

/** A subtitle track: WebVTT by default, which every Cast device reads. */
data class TextTrack(
    /** your id for it, used by [CastSession.setActiveTracks]; any positive number */
    val id: Int,
    val url: String,
    /** BCP 47 language ("en", "ko", "pt-BR") */
    val language: String,
    val name: String = language,
    val contentType: String = "text/vtt",
    val kind: Kind = Kind.SUBTITLES,
    val relay: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
) {
    enum class Kind { SUBTITLES, CAPTIONS, DESCRIPTIONS, CHAPTERS, METADATA }
}

/**
 * One stream in a [CastSource.Dash]: a single file holding one quality of video or audio, with an
 * index (the on-demand DASH profile, what YouTube's adaptive formats are).
 */
data class DashTrack(
    val url: String,
    /** "video/mp4", "audio/mp4", "video/webm"... */
    val mimeType: String,
    /** "avc1.640028", "mp4a.40.2", "vp9"... */
    val codecs: String,
    /** bits per second */
    val bandwidth: Int,
    /** the byte range of the index (sidx / cues) */
    val indexRange: LongRange,
    /** the byte range of the initialization segment, when known */
    val initRange: LongRange? = null,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Int = 0,
    val audioSampleRate: Int = 0,
    val audioChannels: Int = 2,
    /** an audio track's language, for videos dubbed in several */
    val language: String? = null,
    val label: String? = null,
    /** the original audio track (false for dubs) */
    val isDefaultAudio: Boolean = true,
    val id: String? = null,
) {
    val isVideo: Boolean get() = mimeType.startsWith("video")
}

/** What the TV is doing, from its last status report. */
data class MediaStatus(
    val playerState: PlayerState = PlayerState.IDLE,
    /** why it is idle, when it is */
    val idleReason: IdleReason? = null,
    /** the position at [receivedAtMs]; use [CastSession.positionMs] for the position now */
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playbackRate: Double = 1.0,
    val activeTrackIds: List<Int> = emptyList(),
    /** the receiver's id for the media; null when nothing is loaded */
    val mediaSessionId: Int? = null,
    /** `System.nanoTime() / 1e6` when this status arrived */
    val receivedAtMs: Long = 0,
) {
    val isPlaying: Boolean get() = playerState == PlayerState.PLAYING
    val isFinished: Boolean get() = playerState == PlayerState.IDLE && idleReason == IdleReason.FINISHED
}

enum class PlayerState { IDLE, LOADING, BUFFERING, PLAYING, PAUSED }
enum class IdleReason { FINISHED, CANCELLED, INTERRUPTED, ERROR }

data class Volume(val level: Float = 1f, val muted: Boolean = false)

/** Things that happen to a [CastSession] that aren't a status change. */
sealed class CastEvent {
    /** the connection ended: network, the device turned off, or it closed the connection */
    data class Disconnected(val reason: String) : CastEvent()
    /** the receiver app stopped: the TV went back to its home screen, or another sender took it over */
    object ReceiverStopped : CastEvent()
    /** the media played to its end */
    object Finished : CastEvent()
    /** the TV couldn't play the media */
    data class PlaybackError(val detail: String) : CastEvent()
}

class CastException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** a content type from a file name (what [CastSource.LocalFile] uses when none is given) */
fun contentTypeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "mp4", "m4v" -> "video/mp4"
    "webm" -> "video/webm"
    "mkv" -> "video/x-matroska"
    "m4a" -> "audio/mp4"
    "aac" -> "audio/aac"
    "mp3" -> "audio/mpeg"
    "ogg", "oga" -> "audio/ogg"
    "opus" -> "audio/ogg"
    "flac" -> "audio/flac"
    "wav" -> "audio/wav"
    "weba" -> "audio/webm"
    "vtt" -> "text/vtt"
    "srt" -> "application/x-subrip"
    "m3u8" -> "application/x-mpegURL"
    "mpd" -> "application/dash+xml"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    else -> "application/octet-stream"
}
