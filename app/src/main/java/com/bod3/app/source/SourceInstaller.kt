package com.bod3.app.source

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Installs only the APK's data-only DB, on demand. No network, dynamic SQL, vendor code or scripts.
 * A failed extraction never replaces a working version. Files live in noBackupFilesDir on Android.
 * Call on an IO dispatcher. At most 64 KiB is buffered while unpacking/hashing. */
class SourceInstaller(private val directory: File) {
    private var verifiedPath: String? = null

    @Synchronized
    fun ensure(manifest: SourceManifest, openArchive: () -> InputStream, checkCancelled: () -> Unit = {}): File {
        checkCancelled()
        require(directory.isDirectory || directory.mkdirs()) { "Cannot create source-library storage" }
        val target = File(directory, "${manifest.databaseSha256}.db")
        if (target.isFile && target.length() == manifest.databaseBytes) {
            if (verifiedPath == target.absolutePath || digest(target, checkCancelled) == manifest.databaseSha256) {
                verifiedPath = target.absolutePath
                return target
            }
        }
        require(directory.usableSpace > manifest.databaseBytes + 1_048_576L) { "Not enough free storage for the offline library (${manifest.databaseBytes / 1_048_576} MiB required)" }
        val temporary = File(directory, "install.partial")
        try {
            val databaseDigest = MessageDigest.getInstance("SHA-256")
            openArchive().use { input ->
                val archive = BoundedDigestStream(input, manifest.archiveBytes)
                val zip = ZipInputStream(archive)
                try {
                    val entry = zip.nextEntry ?: error("Empty source archive")
                    require(entry.name == SourceManifest.DATABASE_NAME && !entry.isDirectory) { "Unexpected source archive entry" }
                    if (entry.size >= 0) require(entry.size == manifest.databaseBytes) { "Source database size mismatch" }
                    var size = 0L
                    val buffer = ByteArray(64 * 1024)
                    temporary.outputStream().use { output ->
                        while (true) {
                            checkCancelled()
                            val n = zip.read(buffer)
                            if (n < 0) break
                            size += n
                            require(size <= manifest.databaseBytes) { "Source database expands beyond declared size" }
                            databaseDigest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                        }
                        output.fd.sync()
                    }
                    require(size == manifest.databaseBytes && databaseDigest.hex() == manifest.databaseSha256) { "Source database checksum mismatch" }
                    require(zip.nextEntry == null) { "Source archive contains extra entries" }
                    // ZipInputStream can stop before the central directory. Hash all remaining
                    // compressed bytes too; bytes already buffered by the inflater were counted.
                    while (true) {
                        checkCancelled()
                        if (archive.read(buffer) < 0) break
                    }
                    require(archive.count == manifest.archiveBytes && archive.hash() == manifest.archiveSha256) { "Source archive checksum mismatch" }
                } finally {
                    zip.close()
                }
            }
            val header = ByteArray(16)
            temporary.inputStream().use { require(it.read(header) == 16) { "Truncated source database" } }
            require(header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))) { "Not a SQLite source database" }
            checkCancelled()
            require(temporary.renameTo(target)) { "Cannot install source library atomically" }
            verifiedPath = target.absolutePath
            // Only our immutable, content-addressed previous versions may be pruned.
            directory.listFiles()?.filter { it != target && it.name.matches(Regex("[a-f0-9]{64}\\.db")) }?.forEach { it.delete() }
            return target
        } finally {
            temporary.delete()
        }
    }

    private fun digest(file: File, checkCancelled: () -> Unit): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkCancelled()
                val n = input.read(buffer)
                if (n < 0) break
                hash.update(buffer, 0, n)
            }
        }
        return hash.hex()
    }

    private class BoundedDigestStream(private val input: InputStream, private val maximum: Long) : InputStream() {
        var count = 0L
            private set
        private val digest = MessageDigest.getInstance("SHA-256")
        override fun read(): Int {
            val b = input.read()
            if (b >= 0) {
                require(++count <= maximum) { "Source archive exceeds declared bound" }
                digest.update(b.toByte())
            }
            return b
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val n = input.read(buffer, offset, length)
            if (n > 0) {
                count += n
                require(count <= maximum) { "Source archive exceeds declared bound" }
                digest.update(buffer, offset, n)
            }
            return n
        }
        fun hash(): String = digest.hex()
        override fun close() = input.close()
    }
}

private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it.toInt() and 255) }
