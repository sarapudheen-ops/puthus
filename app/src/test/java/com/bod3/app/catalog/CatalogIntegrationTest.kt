package com.bod3.app.catalog

import com.bod3.app.actuator.ActuatorLibrary
import com.bod3.app.actuator.ActuatorPack
import com.bod3.app.actuator.ActuatorRecipeCodec
import com.bod3.app.coding.CodingLibrary
import com.bod3.app.coding.CodingPack
import com.bod3.app.coding.CodingRecipeCodec
import com.bod3.app.datapack.DataPack
import com.bod3.app.datapack.DataPackCodec
import com.bod3.app.datapack.DataPackStore
import com.bod3.app.datapack.DtcGroup
import com.bod3.app.datapack.DtcReferenceContext
import com.bod3.app.datapack.EcuProtocol
import com.bod3.app.datapack.ModuleNameEntry
import com.bod3.app.datapack.VehicleDefinition
import com.bod3.app.packs.PackKind
import com.bod3.app.packs.PackTransfer
import org.junit.Assert.*
import org.junit.Test

class CatalogIntegrationTest {
    private class Setup(base: CatalogPack = fixture()) {
        val store = CatalogMemoryStore()
        val repo = CatalogRepository(base, store)
        val access = CatalogAccess(repo)
        val actuators = ActuatorLibrary(emptyList(), store, access)
        val coding = CodingLibrary(emptyList(), store, access)
        val transfer = PackTransfer(emptyList(), DataPackStore(store), actuators, coding, repo)
    }

    @Test fun controllersSeeOnlyInheritedSelectedRecipesPlusPreservedUnscopedTemplates() {
        val base = fixture(makeNode.copy(data = CatalogData(actuators = listOf(actuator()), coding = listOf(coding()))))
            .copy(library = CatalogData(actuators = listOf(actuator("legacy-template"))))
        val s = Setup(base)
        assertEquals(listOf("legacy-template"), s.actuators.entries().map { it.recipe.id })
        assertTrue(s.coding.entries().isEmpty())
        s.repo.select("variant")
        assertEquals(setOf("legacy-template", "test-output"), s.actuators.entries().map { it.recipe.id }.toSet())
        assertEquals(listOf("test-coding"), s.coding.entries().map { it.recipe.id })
        s.repo.select(null)
        assertTrue(s.coding.entries().isEmpty())
    }

    @Test fun importedRecipeFlagsCannotEstablishVerificationEvenInTheLegacyImportApis() {
        val store = CatalogMemoryStore()
        val actuators = ActuatorLibrary(emptyList(), store)
        val coding = CodingLibrary(emptyList(), store)
        actuators.import(ActuatorPack("import", "Import", recipes = listOf(actuator())))
        coding.import(CodingPack("import", "Import", recipes = listOf(coding())))
        assertFalse(actuators.entry("test-output")!!.recipe.verified)
        assertFalse(coding.entry("test-coding")!!.recipe.verified)
        coding.save(coding().copy(verified = true))
        assertFalse(coding.entry("test-coding")!!.recipe.verified)
    }

    @Test fun catalogJsonAndCsvAlwaysPreviewBeforeAnyWriteAndLeaveSelectionUntouched() {
        val s = Setup()
        val json = CatalogCodec.encode(fixture(generationNode.copy(data = CatalogData(ecus = listOf(engine)))))
        val preview = s.transfer.preview(json)
        assertTrue(preview.errors.toString(), preview.canImport)
        assertEquals(PackKind.CATALOG, preview.kind)
        assertTrue(preview.summary.any { it.contains("1 ECU") })
        assertTrue(s.store.writes.isEmpty())
        s.transfer.apply(preview)
        assertEquals(listOf(CatalogRepository.KEY), s.store.writes)
        assertNull(s.repo.selected())
        val csv = CatalogCsv.encode(fixture())
        val csvPreview = s.transfer.preview(csv)
        assertTrue(csvPreview.errors.toString(), csvPreview.canImport)
        assertTrue(csvPreview.warnings.any { it.contains("CSV contains hierarchy and ECU definitions only") })
        assertEquals(1, s.store.writes.size)
    }

    @Test fun badRowOrRecipeDoesNotEnableConfirmationOrWriteAnything() {
        val s = Setup()
        val bad = CatalogCodec.encode(fixture(generationNode.copy(data = CatalogData(ecus = listOf(engine), coding = listOf(coding().copy(did = 0xF190))))))
        val preview = s.transfer.preview(bad)
        assertFalse(preview.canImport)
        assertTrue(preview.errors.isNotEmpty())
        rejected { s.transfer.apply(preview) }
        val badCsv = CatalogCsv.REQUIRED.joinToString(",") + "\nToyota,Innova,AN40,2004,2015,,,Engine,7E0,7E8,11,UDS\nToyota,Innova,AN40,2004,2015,,,Bad,XYZ,7E8,11,UDS"
        assertFalse(s.transfer.preview(badCsv).canImport)
        assertTrue(s.store.writes.isEmpty())
    }

    @Test fun actuatorAndCodingImportsGoToTheExplicitManualScopeAndStayUnverified() {
        val s = Setup(); s.repo.select("generation")
        val actuatorPreview = s.transfer.preview(ActuatorRecipeCodec.encode(ActuatorPack("incoming", "Incoming", recipes = listOf(actuator()))))
        val codingPreview = s.transfer.preview(CodingRecipeCodec.encode(CodingPack("incoming", "Incoming", recipes = listOf(coding()))))
        assertTrue(actuatorPreview.warnings.any { "unverified" in it })
        assertTrue(codingPreview.warnings.any { "unverified" in it })
        s.transfer.apply(actuatorPreview); s.transfer.apply(codingPreview)
        assertFalse(s.repo.tree().node("generation")!!.data.actuators.single().verified)
        assertFalse(s.repo.tree().node("generation")!!.data.coding.single().verified)
        assertTrue(s.repo.pack().library.actuators.isEmpty())
        assertTrue(s.repo.tree().node("variant")!!.data.coding.isEmpty())
        assertEquals(1, s.repo.tree().resolve("variant")!!.data.coding.size)
    }

    @Test fun recipePreviewCannotImportIntoADifferentSelectionAtConfirmationTime() {
        val s = Setup(); s.repo.select("generation")
        val preview = s.transfer.preview(CodingRecipeCodec.encode(CodingPack("incoming", "Incoming", recipes = listOf(coding()))))
        s.repo.select("variant")
        val before = s.store.values.toMap()
        assertTrue(rejected { s.transfer.apply(preview) }.contains("selection changed"))
        assertEquals(before, s.store.values)
    }

    @Test fun legacyDataPacksArePreviewedAndAtomicallyConvertedNotWrittenIntoParallelStores() {
        val s = Setup(CatalogPack("empty", "Empty catalog"))
        val old = DataPack("legacy", "Legacy file", vehicles = listOf(VehicleDefinition("Toyota", "Innova", "2004-2015", listOf(engine))), pids = listOf(pid()),
            dtcGroups = listOf(DtcGroup("reference", listOf("Toyota"), mapOf("P1234" to "Source text"), "Original source")))
        val preview = s.transfer.preview(DataPackCodec.encode(old))
        assertTrue(preview.errors.toString(), preview.canImport)
        assertTrue(s.store.writes.isEmpty())
        s.transfer.apply(preview)
        assertEquals(listOf(CatalogRepository.KEY), s.store.writes)
        assertNull(s.store.values[DataPackStore.KEY_USER_PACK])
        assertEquals(listOf(pid()), s.repo.pack().library.pids)
        assertNull(s.repo.selected())
        assertEquals("Original source", s.access.descriptions("Toyota").describe("P1234").source)
        assertNotEquals("Source text", s.access.descriptions("Volkswagen").describe("P1234").text)
    }

    @Test fun editingAPidOrRecipeAtAChildDoesNotRewriteItsParent() {
        val s = Setup(fixture(makeNode.copy(data = CatalogData(pids = listOf(pid()), coding = listOf(coding())))))
        s.repo.select("variant")
        s.access.edit { it.copy(pids = listOf(pid().copy(formula = "A"))) }
        s.coding.save(coding().copy(name = "Child coding"))
        assertEquals("(A*256+B)/4", s.repo.tree().node("make")!!.data.pids.single().formula)
        assertEquals("A", s.access.customPids().single().formula)
        assertEquals("Test coding", s.repo.tree().node("make")!!.data.coding.single().name)
        assertEquals("Child coding", s.repo.selected()!!.data.coding.single().name)
        assertFalse(s.repo.selected()!!.data.coding.single().verified)
    }

    @Test fun clearingAnEditedGlobalDtcNoteRestoresTheOriginalScopedReference() {
        val reference = DtcGroup("ref", listOf("Toyota"), mapOf("P1234" to "Reference meaning"), "Source")
        val s = Setup(fixture(makeNode.copy(data = CatalogData(dtcGroups = listOf(reference)))))
        s.repo.editUserLibrary { it.copy(dtcs = mapOf("P1234" to "My override")) }
        assertEquals("My override", s.access.descriptions("Toyota").describe("P1234").text)
        assertTrue(s.access.descriptions("Toyota").describe("P1234").isUserDefined)
        s.repo.editUserLibrary { it.copy(dtcs = emptyMap()) }
        assertEquals("Reference meaning", s.access.descriptions("Toyota").describe("P1234").text)
        assertEquals("Source", s.access.descriptions("Toyota").describe("P1234").source)
        assertNull(s.repo.selected())
    }

    @Test fun csvReimportUsesExistingRenamedEditorIdsAndPreservesOtherDeclarations() {
        val root = makeNode.copy(data = CatalogData(pids = listOf(pid()), dtcs = mapOf("P1234" to "Parent note")))
        val s = Setup(fixture(root))
        s.repo.saveNode(generationNode.copy(name = "AN40 reviewed"))
        s.repo.select("variant")
        val nodes = s.repo.pack().nodes
        val csv = CatalogCsv.COLUMNS.joinToString(",") + "\n" +
            "Toyota,Innova,AN40 reviewed,2004,2015,Test engine,Diesel,Engine CSV,7E0,7E8,11,OBD,Manual,Test variant,"
        val preview = s.transfer.preview(csv)
        assertTrue(preview.errors.toString(), preview.canImport)
        s.transfer.apply(preview)
        assertEquals(nodes.map { it.id }.toSet(), s.repo.pack().nodes.map { it.id }.toSet())
        assertEquals("variant", s.repo.state().selectedId)
        assertEquals("generation", s.repo.tree().node("variant")!!.parentId)
        assertEquals("AN40 reviewed", s.repo.tree().node("generation")!!.name)
        assertEquals(2004, s.repo.tree().node("generation")!!.yearFrom)
        assertEquals("Engine CSV", s.repo.selected()!!.data.ecus.single().name)
        assertEquals(root.data.pids, s.repo.selected()!!.data.pids)
        assertEquals(root.data.dtcs, s.repo.selected()!!.data.dtcs)
    }

    @Test fun aLibraryRecipeIsNotMislabelledAsDeletableFromAnUnrelatedSelectedLevel() {
        val s = Setup()
        s.coding.save(coding())
        s.actuators.import(ActuatorPack("incoming", "Incoming", recipes = listOf(actuator())))
        assertTrue(s.coding.entry("test-coding")!!.isUser)
        assertTrue(s.actuators.entry("test-output")!!.isUser)
        s.repo.select("variant")
        assertFalse(s.coding.entry("test-coding")!!.isUser)
        assertFalse(s.actuators.entry("test-output")!!.isUser)
        s.coding.save(coding().copy(name = "Local override"))
        assertTrue(s.coding.entry("test-coding")!!.isUser)
        s.coding.deleteUserRecipe("test-coding")
        assertEquals("Test coding", s.coding.entry("test-coding")!!.recipe.name)
        assertFalse(s.coding.entry("test-coding")!!.isUser)
    }

    @Test fun probeListsUseOnlyManualSelectionAndExplicitManualEcusNeverTheLibraryOrProfileLink() {
        val kwp = brakes.copy(protocol = EcuProtocol.KWP)
        val s = Setup(fixture(generationNode.copy(data = CatalogData(ecus = listOf(engine, kwp))))
            .copy(library = CatalogData(ecus = listOf(engine), connection = CatalogConnection(mapOf("requestTimeoutMs" to 9000)))))
        assertTrue(s.access.scanEcus().isEmpty())
        val linked = s.repo.migrateProfile(com.bod3.app.profile.ProfileExtras(catalogNodeId = "generation"))
        assertEquals("generation", linked.catalogNodeId)
        assertNull(s.repo.selected())
        assertTrue(s.access.scanEcus().isEmpty())
        assertEquals(com.bod3.app.obd.connection.ConnectionProfile.DEFAULT, s.repo.connection(com.bod3.app.obd.connection.ConnectionProfile.DEFAULT))
        s.repo.select("variant")
        assertEquals(listOf(engine), s.access.scanEcus())
        val manual = engine.copy(name = "Saved manual override", responseId = "7E9")
        assertEquals(listOf(manual), s.access.scanEcus(listOf(manual, kwp)))
        s.repo.select(null)
        assertEquals(listOf(manual), s.access.scanEcus(listOf(manual)))
    }

    @Test fun offlineLinkedModuleNamesAreMetadataOnlyAndCannotActivateConfiguration() {
        val name = ModuleNameEntry("70E", "Named offline module")
        val s = Setup(fixture(generationNode.copy(data = CatalogData(moduleNames = listOf(name), ecus = listOf(engine)))))
        assertTrue(s.access.moduleNames().isEmpty())
        assertEquals(listOf(name), s.access.moduleNames(s.repo.tree().resolve("variant")).map { it.first })
        assertNull(s.repo.selected())
        assertTrue(s.access.scanEcus().isEmpty())
    }

    @Test fun knownProfileMakeWinsOverAStaleConflictingCatalogForPassiveFaultTextOnly() {
        val reference = DtcGroup("toyota-reference", listOf("Toyota", "Lexus"), mapOf("P1234" to "Toyota source text"), "Toyota source")
        val toyota = makeNode.copy(data = CatalogData(dtcGroups = listOf(reference)))
        val vw = CatalogNode("vw", null, CatalogLevel.MAKE, "Volkswagen")
        val vwModel = CatalogNode("vw-model", "vw", CatalogLevel.MODEL, "Test model", data = CatalogData(ecus = listOf(brakes)))
        val s = Setup(fixture(toyota).copy(nodes = fixture(toyota).nodes + listOf(vw, vwModel)))
        s.repo.select("generation")
        assertEquals("Toyota source text", s.access.descriptions().describe("P1234").text)
        assertNotEquals("Toyota source text", s.access.descriptions("Volkswagen").describe("P1234").text)
        assertEquals("generation", s.repo.state().selectedId)
        s.repo.select("vw-model")
        val before = s.store.values.toMap()
        assertEquals("Toyota source text", s.access.descriptions("Toyota").describe("P1234").text)
        assertEquals("Toyota source", s.access.descriptions("Toyota").describe("P1234").source)
        assertEquals("vw-model", s.repo.state().selectedId)
        assertEquals(listOf(brakes), s.access.scanEcus())
        assertEquals(before, s.store.values)
    }

    @Test fun knownProfileMakeAlsoBeatsAConflictingSavedLinkWithoutActivatingEither() {
        val reference = DtcGroup("toyota-reference", listOf("Toyota"), mapOf("P1234" to "Toyota source text"), "Toyota source")
        val s = Setup(fixture(makeNode.copy(data = CatalogData(dtcGroups = listOf(reference)))))
        val linkedMake = s.repo.tree().resolve("variant")!!.make
        assertNotEquals("Toyota source text", s.access.descriptions(DtcReferenceContext.make("Volkswagen", linkedMake)).describe("P1234").text)
        assertEquals("Toyota source text", s.access.descriptions(DtcReferenceContext.make(null, linkedMake)).describe("P1234").text)
        assertNull(s.repo.selected())
        assertTrue(s.access.scanEcus().isEmpty())
    }

    @Test fun connectionAndExpertSettingsCannotBeImportedAsCatalogPermissions() {
        val s = Setup()
        val json = CatalogCodec.encode(fixture(variantNode.copy(data = CatalogData(connection = CatalogConnection(mapOf("expertMode" to true))))))
        assertFalse(s.transfer.preview(json).canImport)
        assertTrue(s.store.writes.isEmpty())
    }
}
