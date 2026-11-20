package com.bod3.app.vehiclepack

import com.bod3.app.profile.MiniJson
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Inflater

/** A single independent JSON object, not an executable file or a whole-corpus download. */
data class PackRange(val offset: Long, val bytes: Int, val jsonBytes: Int, val sha256: String) {
    fun validate(containerBytes: Long) {
        require(offset >= MAGIC_BYTES && bytes in 1..MAX_PACKED_BYTES && jsonBytes in 1..MAX_JSON_BYTES &&
            containerBytes in MAGIC_BYTES..MAX_CONTAINER_BYTES && offset <= containerBytes - bytes && HASH.matches(sha256)) {
            "Invalid vehicle-pack byte range"
        }
    }
    companion object {
        const val MAGIC_BYTES = 11L // BOD3PACKS1 + newline
        const val MAX_PACKED_BYTES = 4 * 1024 * 1024
        const val MAX_JSON_BYTES = 8 * 1024 * 1024
        const val MAX_CONTAINER_BYTES = 512L * 1024 * 1024
        val HASH = Regex("[0-9a-f]{64}")
        fun parse(raw: Any?, containerBytes: Long): PackRange {
            val m = raw as? Map<*, *> ?: error("Missing vehicle-pack range")
            require(m.keys == setOf("offset", "bytes", "jsonBytes", "sha256")) { "Unknown vehicle-pack range field" }
            fun number(key: String, max: Long): Long = (m[key] as? Number)?.toDouble()?.let {
                require(it.isFinite() && it % 1.0 == 0.0 && it in 0.0..max.toDouble()) { "Invalid range $key" }; it.toLong()
            } ?: error("Missing range $key")
            return PackRange(number("offset", MAX_CONTAINER_BYTES), number("bytes", MAX_PACKED_BYTES.toLong()).toInt(),
                number("jsonBytes", MAX_JSON_BYTES.toLong()).toInt(), m["sha256"] as? String ?: "").also { it.validate(containerBytes) }
        }
    }
}

internal fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Exactly the deterministic gzip format emitted by vehicle_bundle.py. Reject extra members/bombs. */
object PackCompression {
    fun decode(packed: ByteArray, range: PackRange): ByteArray {
        require(range.bytes in 1..PackRange.MAX_PACKED_BYTES && packed.size == range.bytes && PackRange.HASH.matches(range.sha256) &&
            sha256(packed) == range.sha256) { "Vehicle-pack checksum mismatch" }
        require(packed.size >= 18 && packed[0] == 0x1f.toByte() && packed[1] == 0x8b.toByte() && packed[2] == 8.toByte() && packed[3] == 0.toByte()) {
            "Unsupported vehicle-pack gzip header"
        }
        require(range.jsonBytes in 1..PackRange.MAX_JSON_BYTES) { "Oversized vehicle-pack JSON" }
        val result = ByteArray(range.jsonBytes)
        val inflater = Inflater(true)
        try {
            inflater.setInput(packed, 10, packed.size - 18)
            var count = 0
            while (!inflater.finished() && count < result.size) {
                val read = inflater.inflate(result, count, result.size - count)
                require(read > 0) { "Truncated vehicle-pack gzip" }
                count += read
            }
            // The end marker may require a zero-output inflate after exactly filling the buffer.
            if (!inflater.finished()) {
                val extra = ByteArray(1)
                require(inflater.inflate(extra) == 0 && inflater.finished()) { "Vehicle-pack expansion exceeds its bound" }
            }
            require(count == result.size && inflater.remaining == 0) { "Truncated or concatenated vehicle-pack gzip" }
        } finally { inflater.end() }
        fun little(at: Int): Long = (0..3).fold(0L) { v, i -> v or ((packed[at + i].toLong() and 255) shl (i * 8)) }
        require(little(packed.size - 8) == CRC32().also { it.update(result) }.value && little(packed.size - 4) == result.size.toLong()) {
            "Vehicle-pack gzip trailer mismatch"
        }
        return result
    }

    fun json(packed: ByteArray, range: PackRange): Map<*, *> {
        val raw = decode(packed, range)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
        return MiniJson.parse(text) as? Map<*, *> ?: error("Vehicle-pack document must be a JSON object")
    }
}

/** Testable network boundary. An implementation must require HTTP 206 + exact Content-Range. */
fun interface PackRangeSource {
    fun fetch(container: String, total: Long, range: PackRange, checkCancelled: () -> Unit): ByteArray
}

/** Content-addressed, bounded private cache. All JSON is rehashed and validated before use. */
class PackObjectStore(
    directory: File,
    private val source: PackRangeSource,
    private val maxCacheBytes: Long = 128L * 1024 * 1024,
) {
    private val directory = directory.canonicalFile
    private val cached = linkedMapOf<File, Long>() // Oldest first; initialized once, not an O(n²) directory scan per object.
    private var tracked = false
    private var cachedBytes = 0L
    init { require(maxCacheBytes >= PackRange.MAX_PACKED_BYTES) }

    private fun remember(file: File) {
        if (!tracked) {
            directory.listFiles().orEmpty().filter { it.isFile && it.name.matches(Regex("[0-9a-f]{64}\\.gz")) }
                .sortedBy { it.lastModified() }.forEach { cached[it] = it.length() }
            cachedBytes = cached.values.sum(); tracked = true
        }
        cachedBytes -= cached.remove(file) ?: 0L
        cached[file] = file.length(); cachedBytes += file.length()
        file.setLastModified(System.currentTimeMillis())
        val oldest = cached.entries.iterator()
        while (cachedBytes > maxCacheBytes && oldest.hasNext()) {
            val entry = oldest.next()
            if (entry.key != file && (!entry.key.exists() || entry.key.delete())) {
                cachedBytes -= entry.value; oldest.remove()
            }
        }
    }

    @Synchronized
    fun load(container: String, total: Long, range: PackRange, checkCancelled: () -> Unit = {}): Map<*, *> {
        range.validate(total)
        require(Regex("toyota-[0-9a-f]{64}\\.bin").matches(container)) { "Unsafe vehicle-pack container name" }
        checkCancelled()
        require(directory.isDirectory || directory.mkdirs()) { "Cannot create private vehicle-pack cache" }
        val file = File(directory, range.sha256 + ".gz")
        require(file.canonicalFile.parentFile == directory.canonicalFile) { "Vehicle-pack object escapes private storage" }
        if (file.isFile && file.length() == range.bytes.toLong()) {
            val cached = try { PackCompression.json(file.readBytes(), range) } catch (_: Exception) { null }
            if (cached != null) {
                checkCancelled(); remember(file); return cached
            }
        }
        val bytes = source.fetch(container, total, range, checkCancelled)
        checkCancelled()
        val document = PackCompression.json(bytes, range) // Whole object validated before any write.
        checkCancelled()
        val staging = File.createTempFile("object-", ".partial", directory)
        try {
            FileOutputStream(staging).use { it.write(bytes); it.fd.sync() }
            checkCancelled()
            require(staging.renameTo(file)) { "Cannot atomically install the vehicle-pack object" }
        } finally { staging.delete() }
        remember(file)
        return document
    }
}
