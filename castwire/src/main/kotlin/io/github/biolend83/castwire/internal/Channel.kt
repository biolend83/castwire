package io.github.biolend83.castwire.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

internal const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
internal const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
internal const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
internal const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
internal const val PLATFORM = "receiver-0"

/**
 * One Cast v2 connection: a TLS socket to the device (port 8009) carrying framed messages, each a
 * 4-byte big-endian length and a protobuf `CastMessage` whose payload is a JSON string.
 *
 * The device's certificate is signed by Google's device CA and proves nothing a sender needs, so it is
 * accepted as it is (as every sender does for the socket itself). Writes go through one background
 * thread, so callers on any thread (Android's main thread included) can [send]; a ping every five
 * seconds keeps the device from closing the connection, and twenty seconds of silence closes it here.
 */
internal class Channel(host: String, port: Int, private val log: ((String) -> Unit)?) {
    val sourceId = "sender-" + (SecureRandom().nextInt() and 0xffffff).toString(16)
    private val socket: SSLSocket
    private val out: DataOutputStream
    private val inp: DataInputStream
    private val writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "castwire-write").apply { isDaemon = true } }
    private val ids = AtomicInteger(1)
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<Map<String, Any?>>>()
    @Volatile private var heard = System.currentTimeMillis()
    @Volatile var closed = false; private set

    /** every message: namespace, source id, the JSON object */
    @Volatile var onMessage: (String, String, Map<String, Any?>) -> Unit = { _, _, _ -> }
    /** once, when the connection ends for any reason other than [close] */
    @Volatile var onClosed: (String) -> Unit = {}

    init {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(TrustAny), SecureRandom())
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(host, port), 6000)
            raw.tcpNoDelay = true
            socket = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            socket.soTimeout = 6000
            socket.startHandshake()
            socket.soTimeout = 0
        } catch (e: Exception) {
            runCatching { raw.close() }
            writer.shutdownNow()
            throw e
        }
        out = DataOutputStream(socket.outputStream.buffered())
        inp = DataInputStream(socket.inputStream.buffered())
        thread(isDaemon = true, name = "castwire-read") { readLoop() }
        writer.scheduleWithFixedDelay({
            if (System.currentTimeMillis() - heard > 20_000) fail("The device stopped answering")
            else write(NS_HEARTBEAT, PLATFORM, """{"type":"PING"}""")
        }, 5, 5, TimeUnit.SECONDS)
    }

    /** sends [payload] to [dest]; with [withId] it carries a fresh requestId */
    fun send(ns: String, dest: String, payload: Map<String, Any?>, withId: Boolean = true) {
        val body = if (withId && "requestId" !in payload) payload + ("requestId" to ids.getAndIncrement()) else payload
        val text = Json.encode(body)
        runCatching { writer.execute { write(ns, dest, text) } }
    }

    /** sends and waits for the reply (the message carrying the same requestId) */
    suspend fun request(ns: String, dest: String, payload: Map<String, Any?>, timeoutMs: Long = 10_000): Map<String, Any?> {
        val id = ids.getAndIncrement()
        val d = CompletableDeferred<Map<String, Any?>>()
        waiting[id] = d
        try {
            send(ns, dest, payload + ("requestId" to id))
            return withTimeout(timeoutMs) { d.await() }
        } finally {
            waiting.remove(id)
        }
    }

    /** a raw string payload (custom namespaces whose receivers don't speak JSON objects) */
    fun sendRaw(ns: String, dest: String, payload: String) {
        runCatching { writer.execute { write(ns, dest, payload) } }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { writer.execute { runCatching { socket.close() } } }
        writer.shutdown()
        waiting.values.forEach { it.completeExceptionally(IOException("closed")) }
    }

    private fun fail(why: String) {
        if (closed) return
        log?.invoke("connection lost: $why")
        close()
        runCatching { onClosed(why) }
    }

    private fun write(ns: String, dest: String, payload: String) {
        if (socket.isClosed) return     // not [closed]: what was queued just before close() still goes out
        try {
            val bytes = Frame.encode(sourceId, dest, ns, payload)
            out.writeInt(bytes.size)
            out.write(bytes)
            out.flush()
        } catch (e: IOException) {
            fail("The connection to the device broke")
        }
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val len = inp.readInt()
                if (len <= 0 || len > (4 shl 20)) throw IOException("bad frame length $len")
                val p = ByteArray(len)
                inp.readFully(p)
                heard = System.currentTimeMillis()
                handle(p)
            }
        } catch (_: Exception) {
            fail("The connection to the device broke")
        }
    }

    private fun handle(p: ByteArray) {
        val f = Frame.decode(p) ?: return
        val msg = Json.parseObject(f.payload)
        if (msg == null) { runCatching { onMessage(f.namespace, f.source, mapOf("raw" to f.payload)) }; return }
        val type = msg.str("type")
        if (f.namespace == NS_HEARTBEAT && type == "PING") { runCatching { writer.execute { write(NS_HEARTBEAT, f.source, """{"type":"PONG"}""") } }; return }
        if (f.namespace == NS_CONNECTION && type == "CLOSE" && f.source == PLATFORM) { fail("The device closed the connection"); return }
        runCatching { onMessage(f.namespace, f.source, msg) }
        val id = msg.num("requestId")?.toInt()
        if (id != null && id != 0) waiting.remove(id)?.complete(msg)
    }

    private object TrustAny : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

/** The protobuf `CastMessage`, the few fields a sender uses, written and read by hand. */
internal object Frame {
    class Decoded(val source: String, val destination: String, val namespace: String, val payload: String)

    fun encode(source: String, dest: String, ns: String, payload: String): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(0x08); b.write(0)             // protocol_version = CASTV2_1_0
        field(b, 0x12, source)                // source_id
        field(b, 0x1a, dest)                  // destination_id
        field(b, 0x22, ns)                    // namespace
        b.write(0x28); b.write(0)             // payload_type = STRING
        field(b, 0x32, payload)               // payload_utf8
        return b.toByteArray()
    }

    private fun field(b: ByteArrayOutputStream, tag: Int, s: String) {
        val x = s.toByteArray(Charsets.UTF_8)
        b.write(tag)
        var v = x.size
        while (v >= 0x80) { b.write((v and 0x7f) or 0x80); v = v ushr 7 }
        b.write(v)
        b.write(x)
    }

    fun decode(p: ByteArray): Decoded? {
        var source = ""
        var dest = ""
        var ns = ""
        var payload: String? = null
        var i = 0
        fun varint(): Long {
            var r = 0L
            var shift = 0
            while (i < p.size) {
                val b = p[i++].toInt() and 0xff
                r = r or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return r
        }
        while (i < p.size) {
            val tag = varint().toInt()
            when (tag and 7) {
                0 -> varint()
                1 -> i += 8
                5 -> i += 4
                2 -> {
                    val l = varint().toInt()
                    if (l < 0 || i + l > p.size) return null
                    val s = String(p, i, l, Charsets.UTF_8)
                    when (tag ushr 3) { 2 -> source = s; 3 -> dest = s; 4 -> ns = s; 6 -> payload = s }
                    i += l
                }
                else -> return null
            }
        }
        return Decoded(source, dest, ns, payload ?: return null)
    }
}
