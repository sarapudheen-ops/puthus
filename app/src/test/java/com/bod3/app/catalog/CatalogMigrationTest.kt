package com.bod3.app.catalog

import com.bod3.app.actuator.ActuatorPack
import com.bod3.app.actuator.ActuatorRecipeCodec
import com.bod3.app.coding.CodingPack
import com.bod3.app.coding.CodingRecipeCodec
import com.bod3.app.datapack.DataPack
import com.bod3.app.datapack.DataPackCodec
import com.bod3.app.datapack.DataPackStore
import com.bod3.app.profile.JsonWriter
import com.bod3.app.profile.ProfileExtras
import com.bod3.app.profile.ProfileExtrasCodec
import com.bod3.app.profile.ProfileExtrasStore
import org.junit.Assert.*
import org.junit.Test

class CatalogMigrationTest {
    private fun migratedAssets(): CatalogMigration.Result {
        val seed = CatalogCodec.decodeOrThrow(asset("catalog/starter.json").readText())
        val data = asset("datapacks/vw-group-mqb.json").parentFile!!.listFiles()!!.filter { it.extension == "json" }.map { DataPackCodec.decodeOrThrow(it.readText()) }
        val actuators = asset("actuators/vw-mqb-templates.json").parentFile!!.listFiles()!!.filter { it.extension == "json" }.map { ActuatorRecipeCodec.decodeOrThrow(it.readText()) }
        val coding = asset("coding/vw-mqb-coding.json").parentFile!!.listFiles()!!.filter { it.extension == "json" }.map { CodingRecipeCodec.decodeOrThrow(it.readText()) }
        return CatalogMigration.convert(seed, data, actuators, coding)
    }
    private fun emptyActuators() = ActuatorPack("user-actuators", "My tests", recipes = emptyList())
    private fun emptyCoding() = CodingPack("user-coding", "My coding", recipes = emptyList())

    @Test fun seedHasRequestedMakesAndInnovaGenerationsButNoDiagnosticDefinitions() {
        val seed = CatalogCodec.decodeOrThrow(asset("catalog/starter.json").readText())
        assertTrue(CatalogTree(seed).problems().isEmpty())
        val required = setOf("Toyota", "Hyundai", "Kia", "Suzuki", "Tata", "Mahindra", "Honda", "Nissan", "Ford", "Chevrolet", "Volkswagen", "Audi", "Škoda", "SEAT", "BMW", "Mercedes-Benz", "Renault", "Peugeot", "Citroën", "Mazda", "Mitsubishi", "Subaru", "Volvo", "Land Rover", "Jeep")
        assertTrue(seed.nodes.filter { it.level == CatalogLevel.MAKE }.map { it.name }.toSet().containsAll(required))
        val tree = CatalogTree(seed)
        val an40 = tree.search("Toyota Innova AN40").single { it.level == CatalogLevel.GENERATION }
        assertEquals(2004, an40.yearFrom)
        assertEquals(2015, an40.yearTo)
        assertTrue(tree.search("Toyota Innova AN140").isNotEmpty())
        assertTrue(tree.search("Toyota Innova AG10").isNotEmpty())
        assertTrue(seed.nodes.all { it.data.count == 0 && it.platformIds.isEmpty() })
        assertTrue(seed.platforms.isEmpty())
        assertEquals(0, seed.library.count)
        assertTrue(seed.nodes.filter { it.level == CatalogLevel.GENERATION }.all { it.yearFrom != null })
    }

    @Test fun existingMqbDefinitionsRemainExactAndSharedUnderTheirPlatform() {
        val result = migratedAssets()
        val old = DataPackCodec.decodeOrThrow(asset("datapacks/vw-group-mqb.json").readText())
        val tree = CatalogTree(result.pack)
        assertTrue(tree.problems().toString(), tree.problems().isEmpty())
        val platform = result.pack.platforms.single { it.id == CatalogMigration.MQB }
        assertEquals(11, platform.data.ecus.size)
        assertEquals(old.vehicles.first().ecus, platform.data.ecus)
        assertEquals(3, platform.data.actuators.size)
        assertEquals(3, platform.data.coding.size)
        old.vehicles.forEach { vehicle ->
            val id = result.legacyLinks.getValue(vehicle.key)
            val node = tree.node(id)!!
            assertEquals(CatalogLevel.GENERATION, node.level)
            assertTrue(node.data.ecus.isEmpty())
            assertTrue(CatalogMigration.MQB in node.platformIds)
            assertEquals(vehicle.ecus, tree.resolve(id)!!.data.ecus)
        }
        assertEquals(1, tree.search("Volkswagen Golf").count { it.level == CatalogLevel.MODEL })
        assertEquals(1, tree.search("Skoda Octavia 5E").count { it.level == CatalogLevel.GENERATION })
    }

    @Test fun toyotaReferenceRetainsAllMeaningsRestrictionsAndAttributionOnce() {
        val result = migratedAssets()
        val original = DataPackCodec.decodeOrThrow(asset("datapacks/toyota-lexus-dtcs.json").readText()).dtcGroups.single()
        val group = result.pack.platforms.flatMap { it.data.dtcGroups }.single()
        assertEquals(original, group)
        assertEquals(11_989, group.dtcs.size)
        assertTrue(result.pack.library.dtcs.keys.none { it == "P0301-00" })
        assertTrue(result.pack.nodes.all { it.data.dtcs.isEmpty() })
        val repo = CatalogRepository(result.pack, CatalogMemoryStore(), result.legacyLinks)
        val access = CatalogAccess(repo)
        assertFalse(access.descriptions().describe("P0301-00").source.contains("Toyota"))
        assertEquals("Cylinder 1 Misfire Detected", access.descriptions("Toyota").describe("P0301-00").text)
        assertEquals(original.source, access.descriptions("Lexus").describe("P0301-00").source)
        assertFalse(access.descriptions("Volkswagen").describe("P0301-00").source.contains("Toyota"))
        val toyota = CatalogTree(result.pack).search("Toyota Innova AN40").single { it.level == CatalogLevel.GENERATION }
        repo.select(toyota.id)
        assertEquals(original.source, access.descriptions().describe("P0301-00").source)
        assertTrue(repo.selected()!!.data.ecus.isEmpty()) // Names/reference text never fabricate an enhanced Toyota ECU list.
    }

    @Test fun manualLegacySelectionMigratesOnceWithoutAlteringAnyOldPreference() {
        val result = migratedAssets()
        val store = CatalogMemoryStore()
        val oldPack = DataPack.emptyUserPack().copy(pids = listOf(pid()), dtcs = mapOf("P1234" to "My note"))
        store.values[DataPackStore.KEY_USER_PACK] = DataPackCodec.encode(oldPack)
        store.values[DataPackStore.KEY_SELECTED_VEHICLE] = "Volkswagen|Tiguan (AD1)"
        store.values[DataPackStore.KEY_LIVE_SELECTION] = "{\"keys\":[\"custom:test-rpm\",\"010C\"]}"
        store.values["connection.active"] = "my-adapter"
        val original = store.values.toMap()
        val repo = CatalogRepository(result.pack, store, result.legacyLinks)
        repo.migrateLegacy(oldPack, original[DataPackStore.KEY_SELECTED_VEHICLE], emptyActuators(), emptyCoding())
        assertEquals(result.legacyLinks["Volkswagen|Tiguan (AD1)"], repo.state().selectedId)
        assertEquals(11, repo.selected()!!.data.ecus.size)
        original.forEach { (key, value) -> assertEquals(key, value, store.values[key]) }
        assertEquals(listOf(CatalogRepository.KEY), store.writes)
        val saved = store.values[CatalogRepository.KEY]
        repo.migrateLegacy(DataPack.emptyUserPack(), null, emptyActuators(), emptyCoding())
        assertEquals(saved, store.values[CatalogRepository.KEY])
        assertEquals(1, store.writes.size)
        assertTrue(repo.pack().library.pids.contains(pid()))
        val bundledPids = DataPackCodec.decodeOrThrow(asset("datapacks/generic-obd.json").readText()).pids
        assertTrue(repo.pack().library.pids.containsAll(bundledPids))
        assertTrue(repo.selected()!!.node.data.pids.isEmpty())
    }

    @Test fun aLegacyEditedEcuListStillCompletelyReplacesItsBundledList() {
        val result = migratedAssets()
        val bundled = DataPackCodec.decodeOrThrow(asset("datapacks/vw-group-mqb.json").readText()).vehicles.first()
        val changed = bundled.copy(ecus = bundled.ecus.drop(1).mapIndexed { i, e -> if (i == 0) e.copy(name = "My renamed module") else e }, notes = "Keep this note")
        val repo = CatalogRepository(result.pack, CatalogMemoryStore(), result.legacyLinks)
        repo.migrateLegacy(DataPack.emptyUserPack().copy(vehicles = listOf(changed)), changed.key, emptyActuators(), emptyCoding())
        assertEquals(changed.ecus, repo.selected()!!.data.ecus)
        assertTrue(repo.selected()!!.node.data.exclude.ecus.contains(bundled.ecus.first().key))
        assertTrue(repo.selected()!!.node.notes!!.contains("Keep this note"))
        assertEquals(11, result.pack.platforms.single { it.id == CatalogMigration.MQB }.data.ecus.size)
    }

    @Test fun profileLinkConversionKeepsManualEcusNamesNotesAndLegacyLinkButNeverSelects() {
        val result = migratedAssets()
        val store = CatalogMemoryStore()
        val repo = CatalogRepository(result.pack, store, result.legacyLinks)
        repo.migrateLegacy(DataPack.emptyUserPack(), null, emptyActuators(), emptyCoding())
        val extras = ProfileExtras(manualEcus = listOf(engine), moduleNames = mapOf("7E0" to "My name"), vehicleKey = "Volkswagen|Golf (5G, Mk7)", notes = "Saved VIN note")
        val vin = "WVWZZZ1KZAW000001"
        store.values["profile.extras.$vin"] = JsonWriter.write(ProfileExtrasCodec.encode(extras))
        val extrasStore = ProfileExtrasStore(store, repo::migrateProfile)
        val migrated = extrasStore.get(vin)
        assertEquals(extras.copy(catalogNodeId = result.legacyLinks[extras.vehicleKey]), migrated)
        assertNull(repo.selected())
        val after = store.values.toMap()
        assertEquals(migrated, extrasStore.get(vin))
        assertEquals(after, store.values)
        val decodedErrors = mutableListOf<String>()
        assertEquals(migrated, ProfileExtrasCodec.decode(ProfileExtrasCodec.encode(migrated), "extras", decodedErrors))
        assertTrue(decodedErrors.isEmpty())
    }

    @Test fun aPreviouslySelectedGenericListDoesNotBecomeAFallbackVehicle() {
        val result = migratedAssets()
        val repo = CatalogRepository(result.pack, CatalogMemoryStore(), result.legacyLinks)
        repo.migrateLegacy(DataPack.emptyUserPack(), "Generic|OBD-II, 11-bit CAN", emptyActuators(), emptyCoding())
        assertNull(repo.selected())
        assertTrue(repo.tree().children(null).none { it.name == "Generic" })
        assertEquals(2, repo.pack().platforms.count { it.id.startsWith("legacy-") })
        val original = DataPackCodec.decodeOrThrow(asset("datapacks/generic-obd.json").readText())
        assertEquals(original.vehicles.sumOf { it.ecus.size }, repo.pack().platforms.filter { it.id.startsWith("legacy-") }.sumOf { it.data.ecus.size })
    }

    @Test fun oldUserRecipesArePreservedButLoseUntrustedVerificationFlags() {
        val repo = CatalogRepository(fixture(), CatalogMemoryStore())
        repo.migrateLegacy(DataPack.emptyUserPack(), null,
            ActuatorPack("my-tests", "Old tests", recipes = listOf(actuator())), CodingPack("my-coding", "Old coding", recipes = listOf(coding())))
        assertEquals(actuator().copy(verified = false), repo.pack().library.actuators.single())
        assertEquals(coding().copy(verified = false), repo.pack().library.coding.single())
        assertNull(repo.selected())
    }

    @Test fun unknownSavedLinksAreRetainedRatherThanGuessedFromTheirVin() {
        val repo = CatalogRepository(fixture(), CatalogMemoryStore())
        val extras = ProfileExtras(vehicleKey = "Unknown|Car", notes = "Keep link for later recovery")
        assertEquals(extras, repo.migrateProfile(extras))
        assertNull(repo.selected())
    }
}
