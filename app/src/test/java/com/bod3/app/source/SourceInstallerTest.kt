package com.bod3.app.source

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SourceInstallerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun installsExactBytesAndReusesVerifiedCacheWithoutReopeningAsset() {
        val pack = pack()
        val installer = SourceInstaller(temporary.root)
        val file = installer.ensure(pack.manifest, { ByteArrayInputStream(pack.zip) })
        assertArrayEquals(pack.database, file.readBytes())
        assertEquals("${pack.manifest.databaseSha256}.db", file.name)
        assertEquals(file, installer.ensure(pack.manifest, { error("Cache should not open the asset") }))
        assertFalse(File(temporary.root, "install.partial").exists())
    }

    @Test fun newProcessRehashesCacheAndRepairsSameSizeCorruption() {
        val pack = pack()
        val file = SourceInstaller(temporary.root).ensure(pack.manifest, { ByteArrayInputStream(pack.zip) })
        val damaged = file.readBytes(); damaged[50] = 5
        file.writeBytes(damaged)
        SourceInstaller(temporary.root).ensure(pack.manifest, { ByteArrayInputStream(pack.zip) })
        assertArrayEquals(pack.database, file.readBytes())
    }

    @Test fun failedNewVersionKeepsOldVersionAndRemovesPartialInstallation() {
        val old = pack()
        val oldFile = SourceInstaller(temporary.root).ensure(old.manifest, { ByteArrayInputStream(old.zip) })
        val next = pack(fill = 7)
        expectFailure { SourceInstaller(temporary.root).ensure(next.manifest.copy(databaseSha256 = "d".repeat(64)), { ByteArrayInputStream(next.zip) }) }
        assertArrayEquals(old.database, oldFile.readBytes())
        assertFalse(File(temporary.root, "install.partial").exists())
        assertEquals(1, temporary.root.listFiles()!!.size)
    }

    @Test fun rejectsPathTraversalExtraEntriesAndNonDatabasePayloads() {
        for (pack in listOf(pack(name = "../outside.db"), pack(extra = true), pack(header = "not a SQLite db!"))) {
            expectFailure { SourceInstaller(temporary.root).ensure(pack.manifest, { ByteArrayInputStream(pack.zip) }) }
            assertFalse(File(temporary.root, "install.partial").exists())
            assertTrue(temporary.root.listFiles()!!.isEmpty())
        }
        assertFalse(File(temporary.root.parentFile, "outside.db").exists())
    }

    @Test fun enforcesCompressedAndUncompressedBoundsAndAllChecksums() {
        val pack = pack()
        for (manifest in listOf(
            pack.manifest.copy(databaseBytes = 4000),
            pack.manifest.copy(databaseBytes = 5000),
            pack.manifest.copy(archiveBytes = pack.zip.size - 1L),
            pack.manifest.copy(archiveBytes = pack.zip.size + 1L),
            pack.manifest.copy(archiveSha256 = "f".repeat(64)),
        )) {
            expectFailure { SourceInstaller(temporary.root).ensure(manifest, { ByteArrayInputStream(pack.zip) }) }
            assertTrue(temporary.root.listFiles()!!.isEmpty())
        }
    }

    @Test fun cancellationCannotPublishPartialDatabase() {
        val pack = pack()
        var checks = 0
        try {
            SourceInstaller(temporary.root).ensure(pack.manifest, { ByteArrayInputStream(pack.zip) }) {
                if (++checks >= 3) throw CancellationException("Cancelled test")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun successfulUpgradePrunesOnlyOurPreviousImmutableVersions() {
        val first = pack()
        SourceInstaller(temporary.root).ensure(first.manifest, { ByteArrayInputStream(first.zip) })
        val user = File(temporary.root, "user-notes.txt").apply { writeText("keep") }
        val second = pack(fill = 7)
        val target = SourceInstaller(temporary.root).ensure(second.manifest, { ByteArrayInputStream(second.zip) })
        assertTrue(target.exists())
        assertFalse(File(temporary.root, "${first.manifest.databaseSha256}.db").exists())
        assertEquals("keep", user.readText())
    }

    private data class Pack(val database: ByteArray, val zip: ByteArray, val manifest: SourceManifest)
    private fun pack(fill: Int = 0, name: String = SourceManifest.DATABASE_NAME, extra: Boolean = false, header: String = "SQLite format 3\u0000"): Pack {
        val database = ByteArray(4096) { fill.toByte() }
        header.toByteArray().copyInto(database)
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(name)); zip.write(database); zip.closeEntry()
            if (extra) { zip.putNextEntry(ZipEntry("extra.txt")); zip.write("not allowed".toByteArray()); zip.closeEntry() }
        }
        val bytes = output.toByteArray()
        return Pack(database, bytes, testManifest().copy(databaseBytes = database.size.toLong(), databaseSha256 = sha(database), archiveBytes = bytes.size.toLong(), archiveSha256 = sha(bytes)))
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
