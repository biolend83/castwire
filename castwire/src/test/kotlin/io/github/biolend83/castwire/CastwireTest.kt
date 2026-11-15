package io.github.biolend83.castwire

import io.github.biolend83.castwire.internal.CastServer
import io.github.biolend83.castwire.internal.Mdns
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MdnsTest {
    /** a response as a Chromecast sends it: PTR, SRV, TXT and A records */
    private fun response(): ByteArray {
        val b = ByteArrayOutputStream()
        fun u16(v: Int) { b.write(v shr 8); b.write(v and 0xff) }
        fun nameBytes(vararg labels: String) = ByteArrayOutputStream().also { o -> for (l in labels) { val x = l.toByteArray(); o.write(x.size); o.write(x) }; o.write(0) }.toByteArray()
        fun rec(type: Int, data: ByteArray, vararg n: String) { b.write(nameBytes(*n)); u16(type); u16(1); u16(0); u16(120); u16(data.size); b.write(data) }
        u16(0); u16(0x8400); u16(0); u16(4); u16(0); u16(0)
        val inst = arrayOf("Chromecast-abc123", "_googlecast", "_tcp", "local")
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

    @Test fun garbageIsIgnored() = assertEquals(emptyList(), Mdns.parse(ByteArray(40) { it.toByte() }, 40, "1.2.3.4"))
}

class DashTest {
    private val v720 = DashTrack("https://x/v720?a=1&b=2", "video/mp4", "avc1.4d401f", 1_500_000, 700L..1200L, 0L..699L, 1280, 720, 30)
    private val v1080 = DashTrack("https://x/v1080", "video/mp4", "avc1.640028", 3_000_000, 700L..1300L, 0L..699L, 1920, 1080, 30)
    private val v4k = DashTrack("https://x/v4k", "video/webm", "vp9", 12_000_000, 300L..900L, 0L..299L, 3840, 2160, 30)
    private val aac = DashTrack("https://x/a", "audio/mp4", "mp4a.40.2", 128_000, 600L..900L, 0L..599L, audioSampleRate = 44100)
    private val opus = DashTrack("https://x/o", "audio/webm", "opus", 140_000, 300L..400L, audioSampleRate = 48000)

    @Test fun castablePrefersH264AndAac() = assertEquals(listOf(v720, v1080, aac), DashManifest.castable(listOf(v720, v1080, v4k, aac, opus)))

    @Test fun manifest() {
        val mpd = DashManifest.build(listOf(v720, aac), 212.5)
        assertTrue("mediaPresentationDuration=\"PT212.500S\"" in mpd)
        assertTrue("https://x/v720?a=1&amp;b=2" in mpd)
        assertTrue("""<SegmentBase indexRange="700-1200"><Initialization range="0-699"/></SegmentBase>""" in mpd)
    }
}

class RelayTest {
    private val server = CastServer(OkHttpClient())

    @Test fun servesTextAndFileRanges() {
        val base = server.base("127.0.0.1")
        val t = server.text(base, "#EXTM3U", "application/x-mpegURL", "m3u8")
        val c = URL(t).openConnection() as HttpURLConnection
        assertEquals(200, c.responseCode)
        assertEquals("*", c.getHeaderField("Access-Control-Allow-Origin"))
        assertEquals("#EXTM3U", c.inputStream.readBytes().toString(Charsets.UTF_8))

        val f = File.createTempFile("castwire", ".mp4").apply { writeBytes(ByteArray(1000) { it.toByte() }); deleteOnExit() }
        val u = server.proxy(base, f.toURI().toString())
        val r = URL(u).openConnection() as HttpURLConnection
        r.setRequestProperty("Range", "bytes=10-19")
        assertEquals(206, r.responseCode)
        assertEquals("bytes 10-19/1000", r.getHeaderField("Content-Range"))
        assertEquals((10..19).map { it.toByte() }, r.inputStream.readBytes().toList())
    }

    @Test fun relaysRemoteRanges() {
        val base = server.base("127.0.0.1")
        val f = File.createTempFile("castwire", ".bin").apply { writeBytes(ByteArray(5000) { (it % 251).toByte() }); deleteOnExit() }
        val src = server.proxy(base, f.toURI().toString())
        val u = server.proxy(base, src)        // a remote address, fetched over HTTP
        val c = URL(u).openConnection() as HttpURLConnection
        c.setRequestProperty("Range", "bytes=500-")
        assertEquals(206, c.responseCode)
        assertEquals((500 until 5000).map { (it % 251).toByte() }, c.inputStream.readBytes().toList())
    }
}
