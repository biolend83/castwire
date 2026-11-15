package io.github.biolend83.castwire

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * How [LocalRelay] fetches what it hands on to the TV. The default uses `HttpURLConnection`; pass
 * your own to reuse your app's HTTP client (its connection pool, proxy, cookies, user agent).
 *
 * With OkHttp:
 * ```
 * val fetcher = HttpFetcher { url, headers ->
 *     val r = client.newCall(Request.Builder().url(url).headers(headers.toHeaders()).build()).execute()
 *     HttpResponse(r.code, { r.header(it) }, r.body!!.contentLength(), r.body!!.byteStream(), r)
 * }
 * ```
 */
fun interface HttpFetcher {
    /** a GET of [url] with [headers] (a `Range` among them, when the TV asked for part of the file) */
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String>): HttpResponse

    companion object {
        val Default: HttpFetcher = HttpFetcher { url, headers ->
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = true
            for ((k, v) in headers) c.setRequestProperty(k, v)
            val code = c.responseCode
            val body = if (code >= 400) c.errorStream ?: java.io.ByteArrayInputStream(ByteArray(0)) else c.inputStream
            HttpResponse(code, { c.getHeaderField(it) }, c.contentLengthLong, body) { c.disconnect() }
        }
    }
}

/** A response from an [HttpFetcher]; [close] releases it. */
class HttpResponse(
    val code: Int,
    /** a response header by name (any case), or null */
    val header: (String) -> String?,
    /** the body's length, or -1 when unknown */
    val contentLength: Long,
    val body: InputStream,
    private val onClose: Closeable? = null,
) : Closeable {
    override fun close() {
        runCatching { body.close() }
        runCatching { onClose?.close() }
    }
}
