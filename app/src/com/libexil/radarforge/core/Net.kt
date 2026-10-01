package com.libexil.radarforge.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Plain HTTPS GETs (HttpURLConnection works the same on Android and the JVM). */
object Net {
    var userAgent = "RadarForge-Android/1.0 (github.com/LibexiL)"

    class HttpError(val code: Int, url: String) : IOException("HTTP $code for $url")

    fun open(url: String, timeoutMs: Int = 20_000, range: Long = -1, accept: String? = null): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", userAgent)
        c.setRequestProperty("Accept-Encoding", "gzip")
        if (accept != null) c.setRequestProperty("Accept", accept)
        if (range > 0) c.setRequestProperty("Range", "bytes=$range-")
        val code = c.responseCode
        if (code !in 200..299) {
            c.disconnect()
            throw HttpError(code, url)
        }
        return c
    }

    /** The response body, unzipped if the server gzipped it. */
    fun stream(c: HttpURLConnection): InputStream {
        val s = c.inputStream
        return if ("gzip".equals(c.contentEncoding, true)) java.util.zip.GZIPInputStream(s) else s
    }

    fun getBytes(url: String, timeoutMs: Int = 30_000, accept: String? = null): ByteArray {
        var last: IOException? = null
        for (attempt in 0 until 3) {
            try {
                val c = open(url, timeoutMs, accept = accept)
                try {
                    return stream(c).use { it.readBytes() }
                } finally {
                    c.disconnect()
                }
            } catch (e: HttpError) {
                if (e.code in 400..499) throw e        // missing / forbidden: retrying won't help
                last = e
            } catch (e: IOException) {
                last = e
            }
            Thread.sleep(400L * (attempt + 1))
        }
        throw last ?: IOException("download failed: $url")
    }

    fun getText(url: String, timeoutMs: Int = 20_000, accept: String? = null): String =
        String(getBytes(url, timeoutMs, accept), Charsets.UTF_8)

    fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        input.copyTo(out)
        return out.toByteArray()
    }
}
