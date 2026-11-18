package com.bod3.app.datapack

import com.bod3.app.actuator.ActuatorLibrary
import com.bod3.app.coding.CodingLibrary
import com.bod3.app.obd.connection.InMemoryKeyValueStore
import com.bod3.app.packs.DataExport
import com.bod3.app.packs.PackFiles
import com.bod3.app.packs.PackTransfer
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the actual converted asset, not just synthetic pack fixtures. */
class ToyotaReferenceTest {
    private fun asset(name: String): File = listOf("src/main/assets/datapacks/$name", "app/src/main/assets/datapacks/$name")
        .map(::File).first { it.isFile }

    private fun pack(): DataPack = DataPackCodec.decodeOrThrow(asset("toyota-lexus-dtcs.json").readText())

    @Test
    fun bundledConversionIsPinnedAndContainsOnlyPassiveReferenceData() {
        val file = asset("toyota-lexus-dtcs.json")
        val sha256 = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("00c23401fcadca510317bc7230c5b3a34eee4cc10976bbc85ee69b20b1ed9682", sha256)
        val result = DataPackCodec.decode(file.readText())
        assertTrue(result.errors.toString(), result.isValid)
        val pack = result.pack!!
        assertEquals("toyota-lexus-dtcs", pack.id)
        assertEquals(11989, pack.dtcDescriptionCount)
        assertEquals(4538, pack.dtcGroups.single().dtcs.keys.count { '-' !in it })
        assertEquals(7451, pack.dtcGroups.single().dtcs.keys.count { '-' in it })
        assertTrue(pack.dtcs.isEmpty())
        assertTrue(pack.pids.isEmpty())
        assertTrue(pack.vehicles.isEmpty())
        assertTrue(pack.moduleNames.isEmpty())
        assertEquals(listOf("Toyota", "Lexus", "Toyota Thailand"), pack.dtcGroups.single().makes)
        assertTrue(file.length() < PackFiles.MAX_BYTES)
    }

    @Test
    fun explicitMeaningsWorkWhileConflictsAndPlaceholdersRemainExcluded() {
        val pack = pack()
        val dtcs = pack.dtcGroups.single().dtcs
        assertEquals("Cylinder 1 Misfire Detected", dtcs["P0301-00"])
        assertEquals("Engine Position System Performance (Bank 1)", dtcs["P0008"])
        assertEquals("Internal Control Module EEPROM Error", dtcs["P062F-00"])
        assertEquals("Driver Frontal Stage 1 Deployment Control Circuit Open", dtcs["B0001-13"])
        // These base/extended codes had conflicting wording in the source. Never choose a variant or
        // promote an accepted subtype into its absent base just to increase coverage.
        for (code in listOf("P0301", "P0420", "P1234", "P062F-44", "B0000", "P3216")) assertNull("Unexpected $code", dtcs[code])
        val repo = DataRepository(listOf(pack), DataPackStore(InMemoryKeyValueStore()))
        assertEquals("ThinkDiag Toyota/Lexus V10.62 - TCODE_EN.GAG", repo.dtcDescriptions("Toyota").describe("P0301-00").source)
        assertEquals("Generic (code structure)", repo.dtcDescriptions("Toyota").describe("P0301").source)
    }

    @Test
    fun noRecoveredCodeLeaksToAnUnknownOrDifferentMakeAndGenericFallbackStillWorks() {
        val pack = pack()
        val generic = DataPackCodec.decodeOrThrow(asset("generic-obd.json").readText())
        val repo = DataRepository(listOf(generic, pack), DataPackStore(InMemoryKeyValueStore()))
        val accepted = pack.dtcGroups.single().dtcs
        for (make in listOf(null, "", "Volkswagen", "Ford", "Toyota-like")) {
            val lookup = repo.dtcDescriptions(make)
            for (code in accepted.keys) {
                assertFalse("$code leaked to $make", lookup.describe(code).source.startsWith("ThinkDiag"))
            }
        }
        assertEquals("Cylinder 1 Misfire Detected", repo.dtcDescriptions("Lexus").describe("P0301-00").text)
        assertEquals("Generic OBD-II (SAE J2012)", repo.dtcDescriptions().describe("P0301").source)
    }

    @Test
    fun fullPackPreviewImportAndExportRetainMakeRestrictionsAndOrigin() {
        val pack = pack()
        val store = DataPackStore(InMemoryKeyValueStore())
        val transfer = PackTransfer(emptyList(), store, ActuatorLibrary(emptyList(), InMemoryKeyValueStore()), CodingLibrary(emptyList(), InMemoryKeyValueStore()))
        val preview = transfer.preview(asset("toyota-lexus-dtcs.json").readText())
        assertTrue(preview.errors.toString(), preview.canImport)
        assertTrue(preview.summary.single().contains("11989"))
        assertTrue(preview.summary.single().contains("Toyota / Lexus / Toyota Thailand only"))
        transfer.apply(preview)
        val exported = transfer.exportData(DataExport.ALL)!!
        assertTrue(exported.toByteArray(Charsets.UTF_8).size < PackFiles.MAX_BYTES)
        val exportedPack = DataPackCodec.decodeOrThrow(exported)
        assertEquals(pack.dtcGroups, exportedPack.dtcGroups)
        assertTrue(exportedPack.dtcs.isEmpty())
        val repo = DataRepository(emptyList(), store)
        assertEquals("Imported: ThinkDiag Toyota/Lexus V10.62 - TCODE_EN.GAG", repo.dtcDescriptions("Toyota").describe("P0008").source)
        assertEquals("Generic (code structure)", repo.dtcDescriptions("Volkswagen").describe("P0008").source)
    }
}
