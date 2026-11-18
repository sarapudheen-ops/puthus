package com.bod3.app.catalog

import com.bod3.app.datapack.DataPack
import com.bod3.app.datapack.VehicleDefinition
import com.bod3.app.obd.connection.ConnectionProfile
import org.junit.Assert.*
import org.junit.Test

class CatalogRepositoryTest {
    @Test fun importsAreOneAtomicDocumentAndDoNotSelectAVehicle() {
        val store = CatalogMemoryStore()
        val repo = CatalogRepository(fixture(), store)
        val incoming = fixture(generationNode.copy(data = CatalogData(ecus = listOf(engine), coding = listOf(coding()))))
        assertTrue(repo.preview(incoming).isEmpty())
        assertTrue(store.writes.isEmpty())
        repo.import(incoming)
        assertEquals(listOf(CatalogRepository.KEY), store.writes)
        assertNull(repo.selected())
        assertEquals(listOf(engine), repo.tree().resolve("variant")!!.data.ecus)
        assertFalse(repo.tree().resolve("variant")!!.data.coding.single().verified)
    }

    @Test fun aSingleInvalidObjectCausesNoWritesAtAll() {
        val store = CatalogMemoryStore()
        val repo = CatalogRepository(fixture(), store)
        val pack = fixture(modelNode.copy(data = CatalogData(ecus = listOf(engine))), variantNode.copy(data = CatalogData(pids = listOf(pid().copy(request = "2E123401")))))
        assertTrue(repo.preview(pack).isNotEmpty())
        rejected { repo.import(pack) }
        assertTrue(store.values.isEmpty())
        assertTrue(repo.tree().resolve("variant")!!.data.ecus.isEmpty())
    }

    @Test fun aFailedStorageWriteCannotChangeTheCacheOrExistingSelection() {
        val store = CatalogMemoryStore()
        val repo = CatalogRepository(fixture(), store)
        repo.select("generation")
        val before = store.values.toMap()
        store.fail = true
        rejected { repo.import(fixture(generationNode.copy(data = CatalogData(ecus = listOf(engine))))) }
        assertEquals(before, store.values)
        assertEquals("generation", repo.state().selectedId)
        assertTrue(repo.selected()!!.data.ecus.isEmpty())
    }

    @Test fun sparseImportsDoNotEraseAncestorDefinitionsOrOtherLocalItems() {
        val root = makeNode.copy(data = CatalogData(ecus = listOf(engine), pids = listOf(pid()), dtcs = mapOf("P1234" to "Root note")))
        val repo = CatalogRepository(fixture(root), CatalogMemoryStore())
        val csv = CatalogCsv.REQUIRED.joinToString(",") + "\nToyota,Innova,AN40,2004,2015,,,New ECU,713,77D,11,UDS"
        val imported = CatalogCsv.decode(csv).pack!!
        // CSV name-derived IDs are deliberately the same across seed/import; fixture IDs here differ.
        val update = fixture(modelNode.copy(data = CatalogData(pids = listOf(pid("new")))))
        repo.import(update)
        assertEquals(listOf(pid(), pid("new")), repo.tree().resolve("variant")!!.data.pids)
        assertEquals(listOf(engine), repo.tree().resolve("variant")!!.data.ecus)
        assertEquals("Root note", repo.tree().resolve("variant")!!.data.dtcs["P1234"])
        assertTrue(imported.nodes.isNotEmpty())
    }

    @Test fun sameKeyImportOverridesOnlyThatItemAndKeepsSuppressionDeclarations() {
        val node = generationNode.copy(data = CatalogData(ecus = listOf(engine, brakes), exclude = CatalogExclusions(pids = setOf("suppressed"))))
        val repo = CatalogRepository(fixture(node), CatalogMemoryStore())
        repo.import(CatalogPack("update", "Update", nodes = listOf(node.copy(data = CatalogData(ecus = listOf(engine.copy(name = "Updated")))))))
        val data = repo.tree().node(node.id)!!.data
        assertEquals(listOf("Updated", brakes.name), data.ecus.map { it.name })
        assertEquals(setOf("suppressed"), data.exclude.pids)
    }

    @Test fun exactEditorSaveCanRemoveLocalOverridesAndRestoreTheParent() {
        val root = makeNode.copy(data = CatalogData(ecus = listOf(engine)))
        val child = generationNode.copy(data = CatalogData(ecus = listOf(engine.copy(name = "Override"))))
        val repo = CatalogRepository(fixture(root, child), CatalogMemoryStore())
        repo.saveNode(child.copy(data = CatalogData()))
        assertEquals("Test engine", repo.tree().resolve("variant")!!.data.ecus.single().name)
        assertEquals(0, repo.tree().node(child.id)!!.data.count)
    }

    @Test fun duplicateMakesANewBranchWithNewIdsAndNoVerificationClaimsOrSelection() {
        val original = generationNode.copy(data = CatalogData(actuators = listOf(actuator()), coding = listOf(coding()), security = listOf(security())))
        val repo = CatalogRepository(fixture(original), CatalogMemoryStore())
        val id = repo.duplicate("generation", "AN40 test copy")
        assertNotEquals("generation", id)
        val copy = repo.tree().node(id)!!
        assertEquals("model", copy.parentId)
        val child = repo.tree().children(id).single()
        assertNotEquals("variant", child.id)
        assertEquals(variantNode.engine, child.engine)
        assertEquals(variantNode.transmission, child.transmission)
        assertFalse(repo.tree().resolve(child.id)!!.data.coding.single().verified)
        assertFalse(repo.tree().resolve(child.id)!!.data.actuators.single().verified)
        assertFalse(repo.tree().resolve(child.id)!!.data.security.single().verified)
        assertNull(repo.selected())
    }

    @Test fun stableIdsAndSavedLinksSurviveRenaming() {
        val repo = CatalogRepository(fixture(), CatalogMemoryStore())
        repo.select("generation")
        repo.favorite("generation")
        repo.saveNode(generationNode.copy(name = "AN40 (my label)"))
        assertEquals("generation", repo.state().selectedId)
        assertTrue("generation" in repo.state().favorites)
        assertEquals("AN40 (my label)", repo.selected()!!.node.name)
        assertEquals("generation", repo.tree().node("variant")!!.parentId)
    }

    @Test fun deleteRemovesTheWholeBranchAndClearsItsSelectionFavoritesAndRecentsOnly() {
        val store = CatalogMemoryStore()
        store.values["profile.extras.VIN"] = "original profile metadata"
        val repo = CatalogRepository(fixture(), store)
        repo.select("variant"); repo.favorite("variant")
        repo.deleteNode("generation")
        assertNull(repo.tree().node("generation")); assertNull(repo.tree().node("variant"))
        assertNotNull(repo.tree().node("model"))
        assertNull(repo.selected())
        assertTrue(repo.state().favorites.isEmpty()); assertTrue(repo.state().recents.isEmpty())
        assertEquals("original profile metadata", store.values["profile.extras.VIN"])
        val reloaded = CatalogRepository(fixture(), store)
        assertNull(reloaded.tree().node("variant"))
    }

    @Test fun exportOfAnyLevelRetainsNeededAncestorsAndPlatformsButNeverSiblings() {
        val platform = CatalogPlatform("shared", "Shared", CatalogData(ecus = listOf(engine), coding = listOf(coding())))
        val sibling = modelNode.copy(id = "other-model", name = "Other model")
        val repo = CatalogRepository(fixture(generationNode.copy(platformIds = listOf("shared")), sibling).copy(platforms = listOf(platform)), CatalogMemoryStore())
        for (id in listOf("make", "model", "generation", "variant")) {
            val exported = repo.export(id)
            val decoded = CatalogCodec.decodeOrThrow(CatalogCodec.encode(exported))
            assertTrue(CatalogTree(decoded).problems().isEmpty())
            assertEquals(listOf(engine), CatalogTree(decoded).resolve("variant")!!.data.ecus)
            assertEquals(1, decoded.platforms.size)
            if (id != "make") assertFalse(decoded.nodes.any { it.id == "other-model" })
        }
    }

    @Test fun unreadableSnapshotsArePreservedRatherThanResetOrReplacedByAGenericVehicle() {
        val store = CatalogMemoryStore()
        store.values[CatalogRepository.KEY] = "{broken"
        val repo = CatalogRepository(fixture(), store)
        assertTrue(rejected { repo.state() }.contains("preserved"))
        assertEquals("{broken", store.values[CatalogRepository.KEY])
        assertEquals("{broken", store.values[CatalogRepository.BROKEN])
    }

    @Test fun recentsAreBoundedDeduplicatedAndFavoritesPersistAcrossRestarts() {
        val variants = (1..25).map { variantNode.copy(id = "v$it", name = "Variant $it", engine = "Test $it") }
        val base = fixture().copy(nodes = fixture().nodes + variants)
        val store = CatalogMemoryStore(); val repo = CatalogRepository(base, store)
        variants.forEach { repo.select(it.id) }
        repo.select("v25"); repo.favorite("v1")
        assertEquals(20, repo.state().recents.size)
        assertEquals("v25", repo.state().recents.first())
        assertEquals(1, repo.state().recents.count { it == "v25" })
        val reloaded = CatalogRepository(base, store)
        assertEquals(repo.state().recents, reloaded.state().recents)
        assertEquals(setOf("v1"), reloaded.state().favorites)
        reloaded.favorite("v1"); assertTrue(reloaded.state().favorites.isEmpty())
    }

    @Test fun selectingAnUnknownItemOrAMakeDoesNotChangeTheSavedSelection() {
        val repo = CatalogRepository(fixture(), CatalogMemoryStore())
        repo.select("generation")
        rejected { repo.select("missing") }; rejected { repo.select("make") }
        assertEquals("generation", repo.state().selectedId)
        repo.select(null); assertNull(repo.selected())
        assertEquals(ConnectionProfile.DEFAULT, repo.connection(ConnectionProfile.DEFAULT))
    }

    @Test fun duplicateItemKeysAreRejectedBeforeAnImportCanMergeThemAway() {
        val repo = CatalogRepository(fixture(), CatalogMemoryStore())
        val duplicate = generationNode.copy(data = CatalogData(ecus = listOf(engine, engine.copy(name = "Conflict"))))
        rejected { repo.import(fixture(duplicate)) }
        assertTrue(repo.tree().resolve("generation")!!.data.ecus.isEmpty())
    }

    @Test fun importingLegacyPackIsOneWriteAndPreservesItsStableLinkMappingWithoutSelectingIt() {
        val store = CatalogMemoryStore(); val repo = CatalogRepository(CatalogPack("base", "Base"), store)
        val old = VehicleDefinition("Toyota", "Test model", "2004-2015", listOf(engine))
        repo.importLegacy(DataPack("old", "Legacy file", vehicles = listOf(old), pids = listOf(pid())))
        assertEquals(listOf(CatalogRepository.KEY), store.writes)
        val id = repo.legacyId(old.key)
        assertNotNull(id)
        assertEquals(listOf(engine), repo.tree().resolve(id)!!.data.ecus)
        assertNull(repo.selected())
    }
}
