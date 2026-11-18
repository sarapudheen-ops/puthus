package com.bod3.app.datapack

import com.bod3.app.actuator.ActuatorLibrary
import com.bod3.app.coding.CodingLibrary
import com.bod3.app.obd.connection.InMemoryKeyValueStore
import com.bod3.app.packs.DataExport
import com.bod3.app.packs.PackTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopedDtcTest {
    private val group = DtcGroup("toyota-dtcs", listOf("Toyota", "Lexus"), mapOf("P1234" to "Toyota meaning", "P1234-13" to "Toyota exact subtype"), source = "Toyota source V1")
    private val reference = DataPack("reference", "Scoped reference", dtcGroups = listOf(group))
    private val generic = DataPack("generic", "Generic reference", dtcs = mapOf("P1234" to "Generic meaning"))

    @Test
    fun scopedReferencesRequireTheExactKnownMake() {
        val repo = DataRepository(listOf(generic, reference), DataPackStore(InMemoryKeyValueStore()))
        assertEquals("Toyota meaning", repo.dtcDescriptions("Toyota").describe("P1234").text)
        assertEquals("Toyota meaning", repo.dtcDescriptions(" LEXUS ").describe("P1234").text)
        for (make in listOf(null, "", "Volkswagen", "Toyota-like")) {
            assertEquals("Generic meaning", repo.dtcDescriptions(make).describe("P1234").text)
        }
    }

    @Test
    fun exactSubtypeWinsAndIsNotPromotedToABaseCode() {
        val onlyExact = reference.copy(dtcGroups = listOf(group.copy(dtcs = mapOf("P1234-13" to "Exact only"))))
        val repo = DataRepository(listOf(generic, onlyExact), DataPackStore(InMemoryKeyValueStore()))
        assertEquals("Exact only", repo.dtcDescriptions("Toyota").describe("P1234-13").text)
        assertEquals("Generic meaning", repo.dtcDescriptions("Toyota").describe("P1234").text)
        assertEquals("Generic meaning", repo.dtcDescriptions("Volkswagen").describe("P1234-13").text)
    }

    @Test
    fun userNotesOverrideScopedAndGenericReferences() {
        val store = DataPackStore(InMemoryKeyValueStore())
        val repo = DataRepository(listOf(generic, reference), store)
        store.setDtcDescription("P1234", "My note")
        assertEquals("My note", repo.dtcDescriptions("Toyota").describe("P1234-13").text)
        assertTrue(repo.dtcDescriptions("Toyota").describe("P1234").isUserDefined)
    }

    @Test
    fun newFieldsRoundTripWithFormatVersionTwo() {
        val encoded = DataPackCodec.encode(reference)
        assertTrue(encoded.contains("\"formatVersion\":2"))
        val decoded = DataPackCodec.decode(encoded)
        assertTrue(decoded.errors.toString(), decoded.isValid)
        assertEquals(reference, decoded.pack)
        assertTrue(DataPackCodec.encode(generic).contains("\"formatVersion\":1"))
    }

    @Test
    fun scopingCannotBeSilentlyLostThroughALegacyFormat() {
        val json = DataPackCodec.encode(reference).replace("\"formatVersion\":2", "\"formatVersion\":1")
        val result = DataPackCodec.decode(json)
        assertFalse(result.isValid)
        assertTrue(result.errors.toString(), result.errors.any { "requires formatVersion 2" in it })
    }

    @Test
    fun invalidGroupsReportPathsAndNeverProduceAPack() {
        val json = """{"formatVersion":2,"id":"bad","name":"Bad","dtcGroups":[
            {"id":"x","makes":[],"dtcs":{"BAD":"x","P1234":" "}},
            {"id":"x","makes":["Toyota","TOYOTA"],"dtcs":{"P1234":"fine"}}]}"""
        val result = DataPackCodec.decode(json)
        assertFalse(result.isValid)
        val text = result.errors.joinToString()
        listOf("dtcGroups[0].makes", "dtcGroups[0].dtcs.BAD", "description must not be empty", "duplicate manufacturer", "used twice").forEach {
            assertTrue("Missing $it: $text", text.contains(it))
        }
    }

    @Test
    fun wrongFieldTypesAreNotIgnored() {
        val result = DataPackCodec.decode("""{"formatVersion":2,"id":"bad","name":"Bad","dtcGroups":[{"id":"x","makes":[3],"dtcs":{"P1234":7}}]}""")
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "dtcGroups[0].makes[0]" in it })
        assertTrue(result.errors.any { "dtcGroups[0].dtcs.P1234" in it })
    }

    @Test
    fun scopedOnlyPacksPreviewImportPersistAndExportWithoutBecomingGlobal() {
        val store = DataPackStore(InMemoryKeyValueStore())
        val transfer = PackTransfer(emptyList(), store, ActuatorLibrary(emptyList(), InMemoryKeyValueStore()), CodingLibrary(emptyList(), InMemoryKeyValueStore()))
        val preview = transfer.preview(DataPackCodec.encode(reference))
        assertTrue(preview.errors.toString(), preview.canImport)
        assertTrue(preview.summary.toString(), preview.summary.single().contains("Toyota / Lexus only"))
        transfer.apply(preview)
        assertTrue(store.userPack().dtcs.isEmpty())
        assertEquals(listOf(group), store.userPack().dtcGroups)
        assertEquals(2, transfer.userCounts().dtcs)
        val exported = transfer.exportData(DataExport.ALL)
        assertNotNull(exported)
        assertEquals(listOf(group), DataPackCodec.decodeOrThrow(exported!!).dtcGroups)
        val repo = DataRepository(emptyList(), store)
        assertEquals("Toyota meaning", repo.dtcDescriptions("Toyota").describe("P1234").text)
        assertTrue(repo.dtcDescriptions("Volkswagen").describe("P1234").text.contains("manufacturer-specific"))
    }

    @Test
    fun importedReferencesKeepTheirOriginAndDoNotMasqueradeAsEditableUserNotes() {
        val store = DataPackStore(InMemoryKeyValueStore())
        store.saveUserPack(DataPack.emptyUserPack().copy(dtcGroups = listOf(group)))
        val repo = DataRepository(emptyList(), store)
        val imported = repo.dtcDescriptions("Toyota").describe("P1234")
        assertEquals("Imported: Toyota source V1", imported.source)
        assertFalse(imported.isUserDefined)
        store.setDtcDescription("P1234", "My note")
        assertTrue(repo.dtcDescriptions("Toyota").describe("P1234").isUserDefined)
        store.setDtcDescription("P1234", "")
        assertEquals(imported, repo.dtcDescriptions("Toyota").describe("P1234"))
    }

    @Test
    fun referenceMakeFollowsTheLoadedProfileOrItsExplicitLinkNotAGlobalSelection() {
        // The global ECU-list choice is deliberately not an input to this resolver.
        assertEquals("Volkswagen", DtcReferenceContext.make(" Volkswagen ", "Toyota"))
        assertEquals("Lexus", DtcReferenceContext.make(null, " Lexus "))
        assertEquals("Toyota", DtcReferenceContext.make("", "Toyota"))
        assertEquals(null, DtcReferenceContext.make(null, null))
        assertEquals(null, DtcReferenceContext.make(" ", " "))
    }

    @Test
    fun bundledReferenceShowsItsProvenance() {
        val repo = DataRepository(listOf(reference), DataPackStore(InMemoryKeyValueStore()))
        assertEquals("Toyota source V1", repo.dtcDescriptions("Toyota").describe("P1234").source)
    }

    @Test
    fun importingAnUpdatedGroupReplacesOnlyThatGroup() {
        val store = DataPackStore(InMemoryKeyValueStore())
        val other = DtcGroup("other", listOf("Ford"), mapOf("P1234" to "Ford meaning"))
        store.saveUserPack(DataPack.emptyUserPack().copy(dtcGroups = listOf(group, other)))
        val transfer = PackTransfer(emptyList(), store, ActuatorLibrary(emptyList(), InMemoryKeyValueStore()), CodingLibrary(emptyList(), InMemoryKeyValueStore()))
        val replacement = group.copy(dtcs = mapOf("P1234" to "Updated Toyota meaning"))
        transfer.apply(transfer.preview(DataPackCodec.encode(reference.copy(dtcGroups = listOf(replacement)))))
        assertEquals(2, store.userPack().dtcGroups.size)
        assertEquals(other, store.userPack().dtcGroups.first { it.id == "other" })
        assertEquals("Updated Toyota meaning", DataRepository(listOf(reference), store).dtcDescriptions("Toyota").describe("P1234").text)
    }
}
