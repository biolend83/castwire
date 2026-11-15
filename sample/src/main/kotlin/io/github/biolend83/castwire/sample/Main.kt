package io.github.biolend83.castwire.sample

import io.github.biolend83.castwire.CastDevice
import io.github.biolend83.castwire.CastEvent
import io.github.biolend83.castwire.CastMedia
import io.github.biolend83.castwire.CastSource
import io.github.biolend83.castwire.Castwire
import io.github.biolend83.castwire.CastwireConfig
import io.github.biolend83.castwire.MediaMetadata
import io.github.biolend83.castwire.StreamType
import io.github.biolend83.castwire.contentTypeOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/**
 * castwire-sample: the library from the command line.
 *
 *   devices                        list the Cast devices on this network
 *   play <device> <url|file> [title]  play a URL (or a file on this computer, served by the relay)
 *
 * <device> is a name (or part of one) from `devices`, or an IP address. While playing, type
 * p (pause/play), s <seconds> (seek), v <0-100> (volume), q (stop and quit).
 */
fun main(args: Array<String>): Unit = runBlocking {
    val verbose = "-v" in args
    val a = args.filter { it != "-v" }
    val cast = Castwire(CastwireConfig(log = if (verbose) { m -> System.err.println("[castwire] $m") } else null))
    when (a.firstOrNull()) {
        "devices" -> {
            println("Looking for Cast devices...")
            val list = cast.discover()
            if (list.isEmpty()) println("None found. (Same network? Multicast allowed by the router and firewall?)")
            for (d in list) println("  ${d.name}  -  ${d.model.ifEmpty { "?" }}  ${d.host}:${d.port}${if (d.isGroup) "  (group)" else ""}")
        }
        "play" -> {
            val target = a.getOrNull(1) ?: usage()
            val what = a.getOrNull(2) ?: usage()
            val title = a.getOrNull(3) ?: what.substringAfterLast('/').substringAfterLast('\\')
            val device = findDevice(cast, target)
            println("Connecting to ${device.name}...")
            val session = cast.connect(device)
            launch(Dispatchers.IO) {
                session.events.collect { e ->
                    when (e) {
                        is CastEvent.Disconnected -> { println("Disconnected: ${e.reason}"); exitProcess(1) }
                        CastEvent.ReceiverStopped -> { println("The TV left the player."); exitProcess(0) }
                        CastEvent.Finished -> println("Finished.")
                        is CastEvent.PlaybackError -> println("Playback error: ${e.detail}")
                    }
                }
            }
            val file = File(what)
            val source = when {
                file.isFile -> CastSource.LocalFile(file)
                ".m3u8" in what -> CastSource.Hls(what)
                else -> CastSource.Url(what, contentTypeOf(what.substringBefore('?')).takeIf { it != "application/octet-stream" } ?: "video/mp4")
            }
            session.load(CastMedia(source, MediaMetadata(title), if (source is CastSource.Hls) StreamType.LIVE else StreamType.BUFFERED))
            println("Sent to ${device.name}. p = pause/play, s <sec> = seek, v <0-100> = volume, q = quit")
            while (true) {
                val line = readLine()?.trim() ?: break
                when {
                    line == "p" -> if (session.status.value.isPlaying) session.pause() else session.play()
                    line.startsWith("s ") -> line.substring(2).trim().toLongOrNull()?.let { session.seek(it * 1000) }
                    line.startsWith("v ") -> line.substring(2).trim().toIntOrNull()?.let { session.setVolume(it / 100f) }
                    line == "q" -> break
                    line.isEmpty() -> println("  ${session.status.value.playerState} ${session.positionMs() / 1000}s / ${session.status.value.durationMs / 1000}s")
                }
            }
            session.close()
        }
        else -> usage()
    }
    cast.close()
    exitProcess(0)
}

private suspend fun findDevice(cast: Castwire, target: String): CastDevice {
    if (Regex("""\d+\.\d+\.\d+\.\d+""").matches(target)) return CastDevice.at(target)
    val list = cast.discover()
    return list.firstOrNull { it.name.equals(target, true) } ?: list.firstOrNull { it.name.contains(target, true) }
        ?: run { System.err.println("No device named \"$target\". Found: ${list.joinToString { it.name }.ifEmpty { "none" }}"); exitProcess(2) }
}

private fun usage(): Nothing {
    System.err.println("usage: castwire-sample devices | play <device> <url|file> [title]   (-v for diagnostics)")
    exitProcess(2)
}
