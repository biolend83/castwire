package io.github.biolend83.castwire

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * A small HTTP server on the local network that hands media on to the TV.
 *
 * A TV fetches media itself, from its own address: it can't use links tied to this device's IP,
 * send cookies or headers, read files on this device, or play from servers without CORS headers.
 * The relay serves each of those under an address on this device and fetches the bytes for the TV
 * (remote media in [chunkBytes] ranges, which many CDNs serve at full speed where one long download
 * would be throttled). HLS playlists are rewritten so every segment goes through the relay too.
 *
 * Each address carries a random key, so nothing else on the network can guess one. [CastSession]
 * uses the relay on its own for sources marked `relay`; call it directly for anything else.
 */
class LocalRelay(
    private val fetcher: HttpFetcher = HttpFetcher.Default,
    /** how much of a remote file is asked for at once */
    private val chunkBytes: Long = 10L shl 20,
    private val log: ((String) -> Unit)? = null,
) : AutoCloseable {
    private class Item(
        val url: String? = null,
        val file: File? = null,
        val text: String? = null,
        val type: String = "application/octet-stream",
        val headers: Map<String, String> = emptyMap(),
        val base: String = "",
    )

    private val items = ConcurrentHashMap<String, Item>()
    /** secret per relay: the same thing gets the same address (a live playlist refetched every few seconds doesn't pile up entries), and no one can work an address out */
    private val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
    @Volatile private var server: ServerSocket? = null

    /** `http://<this device's address on the TV's network>:<port>`, for a TV at [deviceHost] */
    fun baseFor(deviceHost: String): String {
        val ip = DatagramSocket().use { it.connect(InetAddress.getByName(deviceHost), 8009); it.localAddress.hostAddress }
        return "http://$ip:${port()}"
    }

    /** an address on [base] serving [url], fetched with [headers] */
    fun url(base: String, url: String, headers: Map<String, String> = emptyMap()): String {
        val k = key("u" + url + headers)
        items[k] = Item(url = url, headers = headers, base = base)
        return "$base/r/$k${ext(url)}"
    }

    /** an address on [base] serving [file] as [contentType] */
    fun file(base: String, file: File, contentType: String = contentTypeOf(file.name)): String {
        val k = key("f" + file.absolutePath)
        items[k] = Item(file = file, type = contentType)
        return "$base/f/$k.${file.extension.ifEmpty { "bin" }}"
    }

    /** an address on [base] serving [text] (a manifest, subtitles) as [contentType] */
    fun text(base: String, text: String, contentType: String, extension: String = "txt"): String {
        val k = key("t" + text)
        items[k] = Item(text = text, type = contentType)
        return "$base/t/$k.$extension"
    }

    /** forgets every address handed out (they stop working) */
    fun clear() = items.clear()

    override fun close() {
        clear()
        runCatching { server?.close() }
        server = null
    }

    private fun key(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(salt)
        return md.digest(s.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }

    private fun ext(url: String): String {
        val path = runCatching { URI(url).path }.getOrNull() ?: return ""
        val e = path.substringAfterLast('/').substringAfterLast('.', "")
        return if (e.length in 2..5 && e.all { it.isLetterOrDigit() }) ".$e" else ""
    }

    @Synchronized private fun port(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val s = ServerSocket(0, 64)
        server = s
        thread(isDaemon = true, name = "castwire-relay") {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: IOException) { break }
                thread(isDaemon = true, name = "castwire-relay-conn") { serve(c) }
            }
        }
        return s.localPort
    }

    // ------------------------------------------------------------------ HTTP

    private fun serve(c: Socket) {
        try {
            c.use {
                c.soTimeout = 30_000
                c.tcpNoDelay = true
                val inp = BufferedInputStream(c.getInputStream())
                val first = line(inp) ?: return
                val parts = first.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore('?')
                val headers = HashMap<String, String>()
                while (true) {
                    val l = line(inp) ?: return
                    if (l.isEmpty()) break
                    val colon = l.indexOf(':')
                    if (colon > 0) headers[l.substring(0, colon).trim().lowercase()] = l.substring(colon + 1).trim()
                }
                val out = c.getOutputStream().buffered(64 * 1024)
                handle(method, path, headers, out)
                out.flush()
            }
        } catch (_: IOException) {
            // the TV dropped the connection (a seek, another quality): nothing to do
        }
    }

    private fun line(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 16 * 1024) return null
        }
    }

    private fun head(out: OutputStream, code: Int, headers: List<Pair<String, String>>) {
        val reason = when (code) { 200 -> "OK"; 204 -> "No Content"; 206 -> "Partial Content"; 404 -> "Not Found"; 416 -> "Range Not Satisfiable"; else -> "Error" }
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges, Content-Type\r\n")
        sb.append("Connection: close\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
    }

    private fun notFound(out: OutputStream) = head(out, 404, listOf("Content-Length" to "0"))

    private fun handle(method: String, path: String, h: Map<String, String>, out: OutputStream) {
        if (method == "OPTIONS") {
            // the TV's player asks first before sending a Range header from its page
            head(out, 204, listOf(
                "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
                "Access-Control-Allow-Headers" to (h["access-control-request-headers"] ?: "Range, Content-Type"),
                "Access-Control-Max-Age" to "86400",
                "Content-Length" to "0",
            ))
            return
        }
        val k = path.substringAfterLast('/').substringBefore('.')
        val item = items[k]
        if (item == null || (method != "GET" && method != "HEAD")) { notFound(out); return }
        val headOnly = method == "HEAD"
        when {
            item.text != null -> {
                val b = item.text.toByteArray(Charsets.UTF_8)
                head(out, 200, listOf("Content-Type" to item.type, "Content-Length" to b.size.toString(), "Cache-Control" to "no-cache"))
                if (!headOnly) out.write(b)
            }
            item.file != null -> file(item.file, item.type, h["range"], headOnly, out)
            isPlaylist(item.url!!) -> playlist(item, headOnly, out)
            else -> remote(item, h["range"], headOnly, out)
        }
    }

    /** "bytes=a-b" as (a, b or null); null for none, or the rare suffix form "bytes=-n" */
    private fun range(r: String?): Pair<Long, Long?>? {
        val v = r?.removePrefix("bytes=")?.substringBefore(',')?.trim() ?: return null
        val a = v.substringBefore('-').toLongOrNull() ?: return null
        return a to v.substringAfter('-', "").toLongOrNull()
    }

    private fun file(f: File, type: String, rangeHeader: String?, headOnly: Boolean, out: OutputStream) {
        if (!f.isFile) { notFound(out); return }
        val total = f.length()
        val r = range(rangeHeader)
        val from = r?.first ?: 0L
        if (from >= total && total > 0) { head(out, 416, listOf("Content-Range" to "bytes */$total", "Content-Length" to "0")); return }
        val end = min(r?.second ?: (total - 1), total - 1)
        val hs = mutableListOf("Content-Type" to type, "Accept-Ranges" to "bytes", "Content-Length" to (end - from + 1).toString())
        if (r != null) hs += "Content-Range" to "bytes $from-$end/$total"
        head(out, if (r != null) 206 else 200, hs)
        if (headOnly) return
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(from)
            val buf = ByteArray(256 * 1024)
            var left = end - from + 1
            while (left > 0) {
                val n = raf.read(buf, 0, min(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
    }

    private fun fetch(item: Item, from: Long, to: Long): HttpResponse {
        var last: IOException? = null
        repeat(3) {
            try {
                val r = fetcher.get(item.url!!, item.headers + ("Range" to "bytes=$from-$to"))
                if (r.code == 206 || r.code == 200) return r
                r.close()
                val e = IOException("HTTP ${r.code}")
                if (r.code in 400..499) throw e
                last = e
            } catch (e: IOException) {
                if (e.message?.startsWith("HTTP 4") == true) throw e
                last = e
            }
        }
        throw last ?: IOException("fetch failed")
    }

    /** a remote file, fetched in [chunkBytes] pieces and handed on as one response */
    private fun remote(item: Item, rangeHeader: String?, headOnly: Boolean, out: OutputStream) {
        val r = range(rangeHeader)
        val from = r?.first ?: 0L
        val firstEnd = if (r?.second != null) min(r.second!!, from + chunkBytes - 1) else from + chunkBytes - 1
        var piece = try { fetch(item, from, firstEnd) } catch (e: IOException) {
            log?.invoke("relay: ${e.message} for ${item.url}")
            head(out, if (e.message == "HTTP 416") 416 else 404, listOf("Content-Length" to "0")); return
        }
        val whole = piece.code == 200          // the server ignored the range: this is the whole file
        val total = if (whole) piece.contentLength.takeIf { it >= 0 }
            else piece.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
        val end = min(r?.second ?: Long.MAX_VALUE, (total ?: Long.MAX_VALUE) - 1)
        val type = piece.header("Content-Type") ?: "application/octet-stream"
        val hs = mutableListOf("Content-Type" to type, "Accept-Ranges" to "bytes")
        if (total != null) hs += "Content-Length" to (end - from + 1).toString()
        if (r != null && total != null) hs += "Content-Range" to "bytes $from-$end/$total"
        head(out, if (r != null && total != null) 206 else 200, hs)
        if (headOnly) { piece.close(); return }
        val buf = ByteArray(256 * 1024)
        var pos = from
        while (true) {
            piece.use { p ->
                val inp = p.body
                if (whole && from > 0) skip(inp, from)
                while (pos <= end) {
                    val n = inp.read(buf, 0, min(buf.size.toLong(), end - pos + 1).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    pos += n
                }
            }
            if (whole || pos > end || total == null) break
            piece = fetch(item, pos, min(pos + chunkBytes - 1, end))
        }
    }

    private fun skip(inp: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = inp.skip(left)
            if (s <= 0) { if (inp.read() < 0) return; left-- } else left -= s
        }
    }

    private fun isPlaylist(url: String) = ".m3u8" in url.substringBefore('?') || "/hls_playlist/" in url || "/hls_variant/" in url

    /** an HLS playlist, every address in it turned into one on this relay */
    private fun playlist(item: Item, headOnly: Boolean, out: OutputStream) {
        val text = try {
            fetcher.get(item.url!!, item.headers).use { r ->
                if (r.code !in 200..299) throw IOException("HTTP ${r.code}")
                r.body.readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: IOException) {
            log?.invoke("relay: playlist ${e.message}")
            notFound(out); return
        }
        val src = URI(item.url)
        val rewritten = text.lineSequence().joinToString("\n") { l ->
            when {
                l.isBlank() -> l
                l.startsWith("#") -> Regex("URI=\"([^\"]+)\"").replace(l) { m -> "URI=\"" + url(item.base, src.resolve(m.groupValues[1]).toString(), item.headers) + "\"" }
                else -> url(item.base, src.resolve(l.trim()).toString(), item.headers)
            }
        }
        val b = rewritten.toByteArray()
        head(out, 200, listOf("Content-Type" to "application/vnd.apple.mpegurl", "Content-Length" to b.size.toString(), "Cache-Control" to "no-cache"))
        if (!headOnly) out.write(b)
    }
}
