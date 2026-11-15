package io.github.biolend83.castwire

/**
 * Joins separate video and audio streams into one DASH manifest (the on-demand profile: each stream a
 * single file with an index), which the TV's player reads to switch quality on its own.
 */
object DashManifest {
    /**
     * Video codecs every Cast device decodes, best supported first. Chromecasts before the Ultra and
     * many TVs have no VP9 above 1080p and no AV1, so H.264 comes first.
     */
    val CAST_VIDEO_CODECS = listOf("avc1", "vp09", "vp9", "hev1", "hvc1", "av01")

    /**
     * Picks what a Cast device plays well from [tracks]: video in one codec family (the first of
     * [preferCodecs] present) no taller than [maxHeight], and AAC audio when there is any (Opus only
     * without it). Tracks without an index can't go in a manifest and are left out.
     */
    fun castable(tracks: List<DashTrack>, maxHeight: Int = 1080, preferCodecs: List<String> = CAST_VIDEO_CODECS): List<DashTrack> {
        val video = tracks.filter { it.isVideo }
        val family = preferCodecs.firstOrNull { c -> video.any { it.codecs.startsWith(c) } }
        val v = video.filter { family == null || it.codecs.startsWith(family) }
        val fits = v.filter { it.height <= maxHeight }.ifEmpty { v.sortedBy { it.height }.take(1) }
        val audio = tracks.filter { !it.isVideo }
        val aac = audio.filter { it.codecs.startsWith("mp4a") }
        return fits + aac.ifEmpty { audio }
    }

    fun build(tracks: List<DashTrack>, durationSec: Double): String {
        val dur = maxOf(durationSec, 1.0)
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" minBufferTime="PT1.5S" mediaPresentationDuration="PT${"%.3f".format(java.util.Locale.ROOT, dur)}S">""")
        sb.append("<Period>")
        var set = 0
        var rep = 0
        for ((mime, list) in tracks.filter { it.isVideo }.groupBy { it.mimeType }) {
            sb.append("""<AdaptationSet id="${set++}" contentType="video" mimeType="${x(mime)}" subsegmentAlignment="true" startWithSAP="1">""")
            for (t in list.sortedBy { it.bandwidth }) {
                sb.append("""<Representation id="${x(t.id ?: "v${rep}")}" codecs="${x(t.codecs)}" bandwidth="${t.bandwidth}"""")
                rep++
                if (t.width > 0) sb.append(""" width="${t.width}"""")
                if (t.height > 0) sb.append(""" height="${t.height}"""")
                if (t.frameRate > 0) sb.append(""" frameRate="${t.frameRate}"""")
                sb.append(">").append(base(t)).append("</Representation>")
            }
            sb.append("</AdaptationSet>")
        }
        // one adaptation set per container and language, so the player can pick a dub
        for ((key, list) in tracks.filter { !it.isVideo }.groupBy { it.mimeType to (it.language ?: "") }) {
            val (mime, lang) = key
            val first = list.first()
            sb.append("""<AdaptationSet id="${set++}" contentType="audio" mimeType="${x(mime)}" subsegmentAlignment="true"""")
            if (lang.isNotEmpty()) sb.append(""" lang="${x(lang)}"""")
            first.label?.let { sb.append(""" label="${x(it)}"""") }
            sb.append(">")
            sb.append("""<Role schemeIdUri="urn:mpeg:dash:role:2011" value="${if (first.isDefaultAudio) "main" else "dub"}"/>""")
            for (t in list.sortedBy { it.bandwidth }) {
                sb.append("""<Representation id="${x(t.id ?: "a${rep}")}" codecs="${x(t.codecs)}" bandwidth="${t.bandwidth}"""")
                rep++
                if (t.audioSampleRate > 0) sb.append(""" audioSamplingRate="${t.audioSampleRate}"""")
                sb.append(">")
                sb.append("""<AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="${t.audioChannels}"/>""")
                sb.append(base(t)).append("</Representation>")
            }
            sb.append("</AdaptationSet>")
        }
        sb.append("</Period></MPD>")
        return sb.toString()
    }

    private fun base(t: DashTrack): String =
        "<BaseURL>${x(t.url)}</BaseURL>" +
            """<SegmentBase indexRange="${t.indexRange.first}-${t.indexRange.last}">""" +
            (t.initRange?.let { """<Initialization range="${it.first}-${it.last}"/>""" } ?: "") +
            "</SegmentBase>"

    private fun x(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
