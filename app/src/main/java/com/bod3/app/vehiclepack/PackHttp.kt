package com.bod3.app.vehiclepack

import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Public, data-only release assets. No tokens, WebView, vendor code, or whole-file fallback. */
class PackHttpSource : PackRangeSource {
    override fun fetch(container: String, total: Long, range: PackRange, checkCancelled: () -> Unit): ByteArray {
        range.validate(total)
        require(Regex("[a-z0-9-]+-[0-9a-f]{64}\\.bin").matches(container))
        var url = URL("$RELEASE_URL/$container")
        repeat(4) { redirect ->
            checkCancelled()
            checkUrl(url)
            val connection = url.openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Range", "bytes=${range.offset}-${range.offset + range.bytes - 1}")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", "bod3-vehicle-packs/1")
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    require(redirect < 3) { "Too many vehicle-pack redirects" }
                    val location = connection.getHeaderField("Location") ?: error("Missing vehicle-pack redirect")
                    url = URL(url, location)
                } else {
                    checkResponse(status, connection.getHeaderField("Content-Range"), connection.contentLengthLong,
                        connection.getHeaderField("Content-Encoding"), total, range)
                    val bytes = ByteArray(range.bytes)
                    connection.inputStream.use { input ->
                        var at = 0
                        while (at < bytes.size) {
                            checkCancelled()
                            val read = input.read(bytes, at, minOf(16_384, bytes.size - at))
                            require(read > 0) { "Truncated vehicle-pack download" }
                            at += read
                        }
                        checkCancelled()
                        require(input.read() == -1) { "Vehicle-pack server exceeded the requested range" }
                    }
                    return bytes
                }
            } finally { connection.disconnect() }
        }
        error("Vehicle-pack download did not resolve")
    }

    companion object {
        const val RELEASE_URL = "https://github.com/sarapudheen-ops/puthus/releases/download/toyota-vehicle-packs-v1"
        internal fun checkUrl(url: URL) {
            require(url.protocol == "https" && url.userInfo == null && url.port in setOf(-1, 443) &&
                url.host.lowercase() in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) {
                "Untrusted vehicle-pack redirect"
            }
        }
        internal fun checkResponse(status: Int, contentRange: String?, contentLength: Long, encoding: String?, total: Long, range: PackRange) {
            require(status == 206) { "Vehicle-pack host must support byte ranges (HTTP $status); the full corpus was not downloaded" }
            require(contentRange == "bytes ${range.offset}-${range.offset + range.bytes - 1}/$total") { "Vehicle-pack server returned a different byte range" }
            require(contentLength == -1L || contentLength == range.bytes.toLong()) { "Vehicle-pack range length mismatch" }
            require(encoding == null || encoding.equals("identity", true)) { "Vehicle-pack range must not be HTTP-compressed" }
        }
    }
}
