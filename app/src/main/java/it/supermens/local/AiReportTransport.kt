// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

import java.io.IOException
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal object AiReportTransport {
    const val MAX_TEXT_BYTES = 16_000
    const val MAX_REQUEST_BYTES = 32_768
    private const val MAX_REPLY_BYTES = 4096

    fun endpointAllowed(value: String): Boolean = runCatching {
        val uri = URI(value)
        secure(uri) && uri.host == "script.google.com" && uri.rawQuery == null &&
            uri.rawPath.matches(Regex("/macros/s/[A-Za-z0-9_-]+/exec"))
    }.getOrDefault(false)

    private fun secure(uri: URI) = uri.scheme == "https" && uri.rawUserInfo == null &&
        uri.port == -1 && uri.rawFragment == null

    private fun contentUrl(current: URL, location: String): URL {
        val uri = current.toURI().resolve(location)
        if (!secure(uri) || uri.host != "script.googleusercontent.com" || uri.rawPath != "/macros/echo") {
            throw IOException("Disallowed report redirect")
        }
        return uri.toURL()
    }

    // The POST is performed once. ContentService's 302/303 is fetched with GET,
    // without the report body. Never forward a POST on 307/308 or to login hosts.
    fun submit(endpoint: String, body: ByteArray,
               open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection }): String {
        require(endpointAllowed(endpoint))
        require(body.isNotEmpty() && body.size <= MAX_REQUEST_BYTES)
        var url = URL(endpoint)
        var post = true
        repeat(4) { hop ->
            val connection = open(url)
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.requestMethod = if (post) "POST" else "GET"
                connection.setRequestProperty("Accept", "application/json")
                if (post) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }
                val code = connection.responseCode
                if (code in setOf(301, 302, 303, 307, 308)) {
                    if (hop == 3 || (post && code !in setOf(302, 303))) throw IOException("Invalid report redirect")
                    val location = connection.getHeaderField("Location") ?: throw IOException("Missing report redirect")
                    url = contentUrl(url, location)
                    post = false
                } else {
                    if (code != 200 || connection.contentType?.substringBefore(';')?.trim()?.lowercase() != "application/json") {
                        throw IOException("Invalid report reply")
                    }
                    if (connection.contentLengthLong > MAX_REPLY_BYTES) throw IOException("Oversized report reply")
                    val bytes = connection.inputStream.use { it.readNBytes(MAX_REPLY_BYTES + 1) }
                    if (bytes.isEmpty() || bytes.size > MAX_REPLY_BYTES) throw IOException("Invalid report reply size")
                    return bytes.toString(Charsets.UTF_8)
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many report redirects")
    }
}
