package com.libexil.radarforge.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Plain HTTPS GETs (HttpURLConnection works the same on Android and the JVM).
 *
 * Connections are kept open and reused: a response that has been read to the end goes back to the
 * connection pool when its stream is closed, so the next request to the same server skips the TCP and TLS
 * handshakes (often 200-400 ms each on mobile data). Only a response that is abandoned part-way is
 * disconnected.
 */
object Net {
    var userAgent = "RadarForge-Android/1.0 (github.com/LibexiL)"

    init {
        // more idle connections kept per server (the default 5 is too few for parallel chunk downloads)
        try {
            if (System.getProperty("http.maxConnections") == null) System.setProperty("http.maxConnections", "12")
            System.setProperty("http.keepAlive", "true")
        } catch (_: Exception) {}
    }

    class HttpError(val code: Int, url: String) : IOException("HTTP $code for $url")

    fun open(url: String, timeoutMs: Int = 20_000, range: Long = -1, accept: String? = null): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", userAgent)
        // a byte range must refer to the file itself, not a compressed copy of it
        c.setRequestProperty("Accept-Encoding", if (range > 0) "identity" else "gzip")
        if (accept != null) c.setRequestProperty("Accept", accept)
        if (range > 0) c.setRequestProperty("Range", "bytes=$range-")
        val code = c.responseCode
        if (code !in 200..299) {
            drainError(c)
            throw HttpError(code, url)
        }
        return c
    }

    /** Reads a small error body to the end so the connection stays usable (a big one: just disconnect). */
    private fun drainError(c: HttpURLConnection) {
        try {
            val len = c.contentLengthLong
            val es = c.errorStream
            if (es != null && len in 0..65_536) es.use { it.readBytes() } else c.disconnect()
        } catch (_: Exception) {
            c.disconnect()
        }
    }

    /** The response body, unzipped if the server gzipped it. */
    fun stream(c: HttpURLConnection): InputStream {
        val s = c.inputStream
        return if ("gzip".equals(c.contentEncoding, true)) java.util.zip.GZIPInputStream(s) else s
    }

    fun getBytes(url: String, timeoutMs: Int = 30_000, accept: String? = null, attempts: Int = 3): ByteArray {
        var last: IOException? = null
        for (attempt in 0 until attempts) {
            try {
                val c = open(url, timeoutMs, accept = accept)
                try {
                    // read to the end and close: the connection is then reused for the next request
                    return stream(c).use { it.readBytes() }
                } catch (e: IOException) {
                    c.disconnect()
                    throw e
                }
            } catch (e: HttpError) {
                if (e.code in 400..499) throw e        // missing / forbidden: retrying won't help
                last = e
            } catch (e: IOException) {
                last = e
            }
            if (attempt + 1 < attempts) Thread.sleep(400L * (attempt + 1))
        }
        throw last ?: IOException("download failed: $url")
    }

    fun getText(url: String, timeoutMs: Int = 20_000, accept: String? = null, attempts: Int = 3): String =
        String(getBytes(url, timeoutMs, accept, attempts), Charsets.UTF_8)

    /** Bytes [start, end] of a file (end < 0: to the end), e.g. one field of a model file. */
    fun getRange(url: String, start: Long, end: Long, timeoutMs: Int = 60_000, attempts: Int = 3): ByteArray {
        var last: IOException? = null
        for (attempt in 0 until attempts) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs
                c.setRequestProperty("User-Agent", userAgent)
                c.setRequestProperty("Accept-Encoding", "identity")
                c.setRequestProperty("Range", "bytes=$start-" + if (end >= 0) "$end" else "")
                val code = c.responseCode
                if (code !in 200..299) {
                    c.disconnect()
                    throw HttpError(code, url)
                }
                try {
                    return c.inputStream.use { it.readBytes() }
                } catch (e: IOException) {
                    c.disconnect()
                    throw e
                }
            } catch (e: HttpError) {
                if (e.code in 400..499) throw e
                last = e
            } catch (e: IOException) {
                last = e
            }
            if (attempt + 1 < attempts) Thread.sleep(500L * (attempt + 1))
        }
        throw last ?: IOException("download failed: $url")
    }

    fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        input.copyTo(out)
        return out.toByteArray()
    }
}
