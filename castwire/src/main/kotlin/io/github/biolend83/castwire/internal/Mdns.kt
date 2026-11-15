package io.github.biolend83.castwire.internal

import io.github.biolend83.castwire.CastDevice
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * Finds Cast devices with one multicast DNS question, "who is `_googlecast._tcp`?". Each device
 * answers with its name, model, address and port.
 *
 * The question goes out from an ordinary port, so devices answer straight back to it (legacy
 * unicast, RFC 6762 §6.7) and port 5353 needn't be shared with the system's own responder. The group
 * is also listened to where possible, and the question is sent both plain and marked "answer me
 * directly" (QU): some devices (Google TVs among them) only take one of the two.
 */
internal object Mdns {
    private val GROUP: InetAddress = InetAddress.getByName("224.0.0.251")
    private const val PORT = 5353
    const val SERVICE = "_googlecast._tcp.local"

    /** asks for [ms] milliseconds; [found] runs once per device, on a background thread */
    fun discover(ms: Long, log: ((String) -> Unit)? = null, found: (CastDevice) -> Unit) {
        val seen = HashSet<String>()
        val lock = Any()
        val deliver = { p: DatagramPacket ->
            val got = parse(p.data, p.length, p.address.hostAddress ?: "")
            log?.invoke("mdns: ${p.length} bytes from ${p.address.hostAddress}:${p.port}, ${got.size} cast device(s)" + if (got.isEmpty()) " [" + names(p.data, p.length).take(6).joinToString() + "]" else "")
            for (d in got) {
                val fresh = synchronized(lock) { seen.add(d.id) }
                if (fresh) runCatching { found(d) }
            }
        }
        val nets = interfaces()
        log?.invoke("mdns: networks " + nets.joinToString { it.first.name + "=" + it.second.hostAddress }.ifEmpty { "none" })
        val sockets = ArrayList<MulticastSocket>()
        for ((nif, addr) in nets) runCatching {
            sockets += MulticastSocket(InetSocketAddress(addr, 0)).apply {
                networkInterface = nif
                timeToLive = 255
                soTimeout = 250
            }
        }
        val group = runCatching {
            MulticastSocket(null as SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
                soTimeout = 250
                timeToLive = 255
                for ((nif, _) in nets) runCatching { joinGroup(InetSocketAddress(GROUP, PORT), nif) }
            }
        }.onFailure { log?.invoke("mdns: port 5353 not available ($it); asking from ordinary ports only") }.getOrNull()
        log?.invoke("mdns: ${sockets.size} query socket(s), group socket " + if (group != null) "open" else "closed")
        if (sockets.isEmpty() && group == null) return
        val qm = query(false)
        val qu = query(true)
        val end = System.currentTimeMillis() + ms
        val readers = (sockets + listOfNotNull(group)).map { s ->
            thread(isDaemon = true, name = "castwire-mdns") {
                val buf = ByteArray(9000)
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        deliver(p)
                    } catch (_: SocketTimeoutException) {
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        }
        // three rounds a second apart: a sleeping device or a lost packet gets another chance
        repeat(3) { round ->
            for (s in sockets) {
                runCatching { s.send(DatagramPacket(qu, qu.size, GROUP, PORT)) }
                runCatching { s.send(DatagramPacket(qm, qm.size, GROUP, PORT)) }
            }
            if (group != null) for ((nif, _) in nets) runCatching {
                group.networkInterface = nif
                group.send(DatagramPacket(qm, qm.size, GROUP, PORT))
            }
            if (round < 2) Thread.sleep(minOf(1000L, (end - System.currentTimeMillis()).coerceAtLeast(0)))
        }
        for (t in readers) t.join(ms + 500)
        for (s in sockets) runCatching { s.close() }
        group?.let { runCatching { it.close() } }
    }

    /** Wi-Fi and Ethernet with private IPv4 addresses; not VPN tunnels or the loopback */
    private fun interfaces(): List<Pair<NetworkInterface, InetAddress>> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter {
            runCatching { it.isUp && !it.isLoopback && it.supportsMulticast() && !it.isVirtual && !it.isPointToPoint }.getOrDefault(false)
        }.flatMap { nif ->
            nif.inetAddresses.toList().filter { it is Inet4Address && it.isSiteLocalAddress }.map { nif to it }
        }
    }.getOrDefault(emptyList())

    /** [unicast]: the QU bit, asking for the answer straight back rather than to the group */
    fun query(unicast: Boolean): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))     // id 0, a query, one question
        for (label in SERVICE.split('.')) { val l = label.toByteArray(); b.write(l.size); b.write(l) }
        b.write(0)
        b.write(byteArrayOf(0, 12, if (unicast) 0x80.toByte() else 0, 1))   // PTR, class IN (and QU)
        return b.toByteArray()
    }

    /** the record names in a packet, for the diagnostics log */
    private fun names(p: ByteArray, n: Int): List<String> = runCatching {
        if (n < 12) return emptyList()
        val count = (((p[6].toInt() and 0xff) shl 8) or (p[7].toInt() and 0xff))
        var i = 12
        val qd = ((p[4].toInt() and 0xff) shl 8) or (p[5].toInt() and 0xff)
        repeat(qd) { i = name(p, n, i).second + 4 }
        val out = ArrayList<String>()
        repeat(count) {
            val (nm, next) = name(p, n, i)
            out += nm
            i = next + 10 + (((p[next + 8].toInt() and 0xff) shl 8) or (p[next + 9].toInt() and 0xff))
            if (i > n) return@runCatching out
        }
        out
    }.getOrDefault(emptyList())

    private class Rec(val name: String, val type: Int, val at: Int, val len: Int)

    /** the devices in one response; [from] is the sender's address, used when no A record came with it */
    fun parse(p: ByteArray, n: Int, from: String): List<CastDevice> = runCatching {
        if (n < 12) return emptyList()
        fun u16(i: Int) = ((p[i].toInt() and 0xff) shl 8) or (p[i + 1].toInt() and 0xff)
        val qd = u16(4)
        val count = u16(6) + u16(8) + u16(10)
        var i = 12
        repeat(qd) { i = name(p, n, i).second + 4 }
        val recs = ArrayList<Rec>()
        repeat(count) {
            val (nm, next) = name(p, n, i)
            val type = u16(next)
            val len = u16(next + 8)
            recs += Rec(nm.lowercase(), type, next + 10, len)
            i = next + 10 + len
            if (i > n) return@runCatching emptyList()
        }
        val addrs = recs.filter { it.type == 1 && it.len == 4 }.associate { r ->
            r.name to (0 until 4).joinToString(".") { (p[r.at + it].toInt() and 0xff).toString() }
        }
        val txt = recs.filter { it.type == 16 }.associate { r ->
            val m = HashMap<String, String>()
            var j = r.at
            while (j < r.at + r.len) {
                val l = p[j].toInt() and 0xff
                val kv = String(p, j + 1, minOf(l, r.at + r.len - j - 1), Charsets.UTF_8)
                if ('=' in kv) m[kv.substringBefore('=').lowercase()] = kv.substringAfter('=')
                j += 1 + l
            }
            r.name to m
        }
        recs.filter { it.type == 33 && it.name.endsWith(".$SERVICE") }.mapNotNull { r ->
            val port = u16(r.at + 4)
            val target = name(p, n, r.at + 6).first.lowercase()
            val t = txt[r.name] ?: emptyMap()
            val host = addrs[target] ?: from
            if (host.isEmpty() || port == 0) return@mapNotNull null
            val label = r.name.removeSuffix(".$SERVICE").substringBeforeLast('-').ifEmpty { r.name }
            CastDevice(
                id = t["id"] ?: r.name,
                name = t["fn"]?.takeIf { it.isNotBlank() } ?: label,
                model = t["md"] ?: "",
                host = host,
                port = port,
                capabilities = t["ca"]?.toIntOrNull() ?: -1,
            )
        }
    }.getOrDefault(emptyList())

    /** a DNS name at [start] (labels, or a pointer back into the packet) and where the record continues */
    private fun name(p: ByteArray, n: Int, start: Int): Pair<String, Int> {
        val parts = ArrayList<String>()
        var i = start
        var next = -1
        var jumps = 0
        while (i < n) {
            val l = p[i].toInt() and 0xff
            when {
                l == 0 -> { if (next < 0) next = i + 1; break }
                l and 0xc0 == 0xc0 -> {
                    if (next < 0) next = i + 2
                    i = ((l and 0x3f) shl 8) or (p[i + 1].toInt() and 0xff)
                    if (++jumps > 40) break
                }
                else -> { parts += String(p, i + 1, l, Charsets.UTF_8); i += 1 + l }
            }
        }
        return parts.joinToString(".") to (if (next < 0) n else next)
    }
}
