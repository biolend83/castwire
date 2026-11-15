package io.github.biolend83.castwire

import io.github.biolend83.castwire.internal.Frame
import io.github.biolend83.castwire.internal.Json
import io.github.biolend83.castwire.internal.Mdns
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JsonTest {
    @Test fun roundTrip() {
        val m = mapOf("type" to "LOAD", "n" to 3, "x" to 1.5, "ok" to true, "none" to null,
            "list" to listOf(1, "two", mapOf("k" to "v")), "esc" to "a\"b\\c\nd\u0001")
        val back = Json.parse(Json.encode(m))
        assertEquals(mapOf("type" to "LOAD", "n" to 3L, "x" to 1.5, "ok" to true, "none" to null,
            "list" to listOf(1L, "two", mapOf("k" to "v")), "esc" to "a\"b\\c\nd\u0001"), back)
    }

    @Test fun parsesDeviceStatus() {
        val s = """{"requestId":5,"status":{"applications":[{"appId":"CC1AD845","sessionId":"abc","transportId":"t-1"}],"volume":{"level":0.25,"muted":false}},"type":"RECEIVER_STATUS"}"""
        val m = Json.parseObject(s)!!
        assertEquals(5L, m["requestId"])
        @Suppress("UNCHECKED_CAST")
        val apps = (m["status"] as Map<String, Any?>)["applications"] as List<Map<String, Any?>>
        assertEquals("t-1", apps[0]["transportId"])
    }

    @Test fun unicodeEscapes() = assertEquals("한글 é", Json.parse("\"\\ud55c\\uae00 \\u00e9\""))

    @Test fun wholeNumbersStayIntegers() = assertEquals("""{"t":12}""", Json.encode(mapOf("t" to 12.0)))
}

class FrameTest {
    @Test fun roundTrip() {
        val payload = """{"type":"PING","text":"${"x".repeat(300)}"}"""
        val b = Frame.encode("sender-1", "receiver-0", "urn:x-cast:com.google.cast.tp.heartbeat", payload)
        val d = Frame.decode(b)!!
        assertEquals("sender-1", d.source)
        assertEquals("receiver-0", d.destination)
        assertEquals("urn:x-cast:com.google.cast.tp.heartbeat", d.namespace)
        assertEquals(payload, d.payload)
    }
}

class MdnsTest {
    /** a response as a Chromecast sends it: PTR, SRV, TXT and A records */
    private fun response(): ByteArray {
        val b = ByteArrayOutputStream()
        fun u16(v: Int) { b.write(v shr 8); b.write(v and 0xff) }
        fun name(vararg labels: String) { for (l in labels) { val x = l.toByteArray(); b.write(x.size); b.write(x) }; b.write(0) }
        fun rec(type: Int, data: ByteArray, vararg n: String) { name(*n); u16(type); u16(1); u16(0); u16(120); u16(data.size); b.write(data) }
        u16(0); u16(0x8400); u16(0); u16(4); u16(0); u16(0)
        val inst = arrayOf("Chromecast-abc123", "_googlecast", "_tcp", "local")
        fun nameBytes(vararg labels: String) = ByteArrayOutputStream().also { o -> for (l in labels) { val x = l.toByteArray(); o.write(x.size); o.write(x) }; o.write(0) }.toByteArray()
        rec(12, nameBytes(*inst), "_googlecast", "_tcp", "local")
        rec(33, byteArrayOf(0, 0, 0, 0, 0x1f, 0x49) + nameBytes("abc123", "local"), *inst)
        val txt = ByteArrayOutputStream().also { o -> for (kv in listOf("id=abc123", "md=Chromecast", "fn=Living room", "ca=201221")) { val x = kv.toByteArray(); o.write(x.size); o.write(x) } }.toByteArray()
        rec(16, txt, *inst)
        rec(1, byteArrayOf(192.toByte(), 168.toByte(), 1, 50), "abc123", "local")
        return b.toByteArray()
    }

    @Test fun parsesDevice() {
        val p = response()
        val d = Mdns.parse(p, p.size, "10.0.0.9").single()
        assertEquals("abc123", d.id)
        assertEquals("Living room", d.name)
        assertEquals("Chromecast", d.model)
        assertEquals("192.168.1.50", d.host)
        assertEquals(8009, d.port)
        assertTrue(d.hasVideo)
    }

    @Test fun queryAsksForPtr() {
        val q = Mdns.query(true)
        assertEquals(0x80, q[q.size - 2].toInt() and 0xff)       // QU bit
        assertEquals(12, q[q.size - 3].toInt())                  // PTR
    }

    @Test fun garbageIsIgnored() = assertEquals(emptyList(), Mdns.parse(ByteArray(40) { it.toByte() }, 40, "1.2.3.4"))
}

class DashTest {
    private val v720 = DashTrack("https://x/v720?a=1&b=2", "video/mp4", "avc1.4d401f", 1_500_000, 700L..1200L, 0L..699L, 1280, 720, 30)
    private val v1080 = DashTrack("https://x/v1080", "video/mp4", "avc1.640028", 3_000_000, 700L..1300L, 0L..699L, 1920, 1080, 30)
    private val v4k = DashTrack("https://x/v4k", "video/webm", "vp9", 12_000_000, 300L..900L, 0L..299L, 3840, 2160, 30)
    private val aac = DashTrack("https://x/a", "audio/mp4", "mp4a.40.2", 128_000, 600L..900L, 0L..599L, audioSampleRate = 44100)
    private val opus = DashTrack("https://x/o", "audio/webm", "opus", 140_000, 300L..400L, audioSampleRate = 48000)

    @Test fun castablePrefersH264AndAac() {
        val c = DashManifest.castable(listOf(v720, v1080, v4k, aac, opus))
        assertEquals(listOf(v720, v1080, aac), c)
    }

    @Test fun manifest() {
        val mpd = DashManifest.build(listOf(v720, aac), 212.5)
        assertTrue("mediaPresentationDuration=\"PT212.500S\"" in mpd)
        assertTrue("https://x/v720?a=1&amp;b=2" in mpd)
        assertTrue("""<SegmentBase indexRange="700-1200"><Initialization range="0-699"/></SegmentBase>""" in mpd)
        assertTrue("""contentType="audio"""" in mpd)
    }
}

class RelayTest {
    @Test fun servesTextAndFileRanges() {
        LocalRelay().use { relay ->
            val base = relay.baseFor("127.0.0.1")
            val t = relay.text(base, "#EXTM3U", "application/x-mpegURL", "m3u8")
            val c = URL(t).openConnection() as HttpURLConnection
            assertEquals(200, c.responseCode)
            assertEquals("*", c.getHeaderField("Access-Control-Allow-Origin"))
            assertEquals("#EXTM3U", c.inputStream.readBytes().toString(Charsets.UTF_8))

            val f = File.createTempFile("castwire", ".mp4").apply { writeBytes(ByteArray(1000) { it.toByte() }); deleteOnExit() }
            val u = relay.file(base, f)
            val r = URL(u).openConnection() as HttpURLConnection
            r.setRequestProperty("Range", "bytes=10-19")
            assertEquals(206, r.responseCode)
            assertEquals("bytes 10-19/1000", r.getHeaderField("Content-Range"))
            assertEquals((10..19).map { it.toByte() }, r.inputStream.readBytes().toList())
            assertEquals(u, relay.file(base, f), "the same file keeps its address")
        }
    }

    @Test fun unknownAddressIs404() {
        LocalRelay().use { relay ->
            val c = URL(relay.baseFor("127.0.0.1") + "/r/0123456789ab").openConnection() as HttpURLConnection
            assertEquals(404, c.responseCode)
        }
    }

    @Test fun relaysRemoteRanges() {
        // a remote server, played by a second relay serving a file
        LocalRelay().use { origin ->
            val f = File.createTempFile("castwire", ".bin").apply { writeBytes(ByteArray(5000) { (it % 251).toByte() }); deleteOnExit() }
            val src = origin.file(origin.baseFor("127.0.0.1"), f)
            LocalRelay(chunkBytes = 1000).use { relay ->
                val u = relay.url(relay.baseFor("127.0.0.1"), src)
                val c = URL(u).openConnection() as HttpURLConnection
                c.setRequestProperty("Range", "bytes=500-")
                assertEquals(206, c.responseCode)
                val got = c.inputStream.readBytes()
                assertEquals(4500, got.size, "read across several chunks")
                assertNotNull(got)
                assertEquals((500 until 5000).map { (it % 251).toByte() }, got.toList())
            }
        }
    }
}
