package com.bod3.app.vehiclepack

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bod3.app.android.AssetVehiclePacks
import com.bod3.app.android.SharedPrefsKeyValueStore
import com.bod3.app.catalog.CatalogRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Actual APK index, Android 24 TLS/range delivery, private cache and offline reopening.
 * No Bluetooth, Session, vendor execution, vehicle selection or ECU requests in these tests. */
@RunWith(AndroidJUnit4::class)
class VehiclePackInstrumentedTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun apkHasOnlyASmallPinnedIndexNotTheSourceCorpus() {
        assertArrayEquals(arrayOf("index.json"), app.assets.list("toyota-vehicle-packs"))
        assertTrue(app.assets.list("toyota-source").isNullOrEmpty())
        val bytes = app.assets.open("toyota-vehicle-packs/index.json").use { it.readBytes() }
        assertTrue(bytes.size <= VehiclePackIndex.MAX_INDEX_BYTES)
        val index = AssetVehiclePacks.load(app).index
        assertEquals(1307, (index.summary["ecuFiles"] as Number).toInt())
        assertEquals(24653, (index.summary["normalizedVehicleChoices"] as Number).toInt())
        assertEquals(9, (index.summary["unresolvedHierarchyChoices"] as Number).toInt())
        assertEquals(2890, (index.summary["executableDefinitions"] as Number).toInt())
        assertEquals(2, (index.summary["enabledSystems"] as Number).toInt())
        val sourceVerification = index.summary["sourceVerification"] as Map<*, *>
        assertEquals(true, (sourceVerification["verification"] as Map<*, *>)["verified"])
        assertTrue(index.containerBytes > 100 * 1024 * 1024L) // external corpus, not an APK asset
    }

    @Test fun exact2007SystemIsReadOnlyAndOtherVariantRemainsDisabled() {
        val index = AssetVehiclePacks.load(app).index
        val cache = File(app.noBackupFilesDir, "pack-2007-${UUID.randomUUID()}")
        try {
            val client = VehiclePackCatalog(index, PackObjectStore(cache, PackHttpSource()))
            fun select(generation: String, variant: String): PackVehicle {
                var page = client.root()
                for (name in listOf("Toyota", "Kijang Innova", generation)) {
                    page = client.open(page.children.single { it.name == name })
                }
                return client.vehicle(page.children.single { it.name == variant })
            }
            val vehicle = select("TGN · -2009.07", "MINI VAN · w/o VSC (Vehicle Stability Control)")
            val system = client.system(vehicle, vehicle.systems.single { it.id == "SYSLIST_EU.BIN@00023a75" })
            assertTrue(system.enabled && system.readOnly)
            assertEquals("1 ECU · 30 PIDs · 1,414 DTCs · 1 ECU info read", system.coverage)
            assertEquals(30, system.data.pids.size)
            assertEquals(1414, system.counts.getValue("dtcs").converted)
            assertNull(system.data.ecus.single().diagnostics!!.faults!!.clear)
            assertTrue(system.data.actuators.isEmpty() && system.data.coding.isEmpty())
            val other = select("TGN · 2009.07-2017.07", "MINI VAN · w/o VSC (Vehicle Stability Control)")
            val disabled = client.system(other, other.systems.single { it.id == "SYSLIST_EU.BIN@0009d732" })
            assertFalse(disabled.enabled)
            assertEquals(0, disabled.data.count)
        } finally { cache.deleteRecursively() }
    }

    @Test fun exactSelectedPathDownloadsByRangeThenWorksWithoutNetworkAndDoesNotSelectAVehicle() {
        val index = AssetVehiclePacks.load(app).index
        val cache = File(app.noBackupFilesDir, "pack-smoke-${UUID.randomUUID()}")
        val prefs = SharedPrefsKeyValueStore(app)
        val selectionBefore = prefs.get(CatalogRepository.KEY)
        var requests = 0
        var transferred = 0L
        try {
            val http = PackHttpSource()
            val client = VehiclePackCatalog(index, PackObjectStore(cache, PackRangeSource { container, total, ref, check ->
                requests++
                transferred += ref.bytes
                http.fetch(container, total, ref, check)
            }))
            var page = client.root()
            val ids = mutableListOf<String>()
            for ((expected, name) in listOf("make" to "Toyota", "model" to "Kijang Innova", "generation" to "TGN · 2009.07-2017.07")) {
                val child = page.children.single { it.name == name }
                assertEquals(expected, child.level)
                ids += child.id
                page = client.open(child)
            }
            val choice = page.children.single { it.name == "MINI VAN · w/ VSC (Vehicle Stability Control)" }
            assertEquals("variant", choice.level)
            ids += choice.id
            val vehicle = client.vehicle(choice)
            assertTrue(vehicle.hierarchyResolved)
            assertEquals(ids.last(), vehicle.variantId)
            assertEquals(6, requests) // Five hierarchy objects plus exact shared source-system descriptors.
            val selected = vehicle.systems.single { it.id == "SYSLIST_EU.BIN@0009d8f2" }
            val system = client.system(vehicle, selected)
            assertEquals(9, requests) // Selected system and its two shared ECU/function metadata objects.
            assertTrue(system.sourceRecords.isNotEmpty())
            if (system.enabled) {
                // The single pinned read-only ECMECT slice: 1 ECU · 30 PIDs · 1,414 DTCs · 1 calibration-ID read.
                assertTrue(system.readOnly)
                assertEquals("1 ECU · 30 PIDs · 1,414 DTCs · 1 ECU info read", system.coverage)
                assertEquals(1, system.data.ecus.size)
                assertEquals(30, system.data.pids.size)
                assertEquals(30, system.counts.getValue("pids").converted)
                assertEquals(1414, system.counts.getValue("dtcs").converted)
                assertEquals(1, system.counts.getValue("ecuInfo").converted)
                assertEquals(0, system.counts.getValue("tests").converted)
                assertEquals(0, system.counts.getValue("coding").converted)
                assertEquals(0, system.counts.getValue("specialFunctions").converted)
                assertTrue(system.data.actuators.isEmpty() && system.data.coding.isEmpty() && system.data.security.isEmpty())
                assertNull(system.data.ecus.single().diagnostics!!.faults!!.clear)
            } else {
                assertTrue(system.reasons.isNotEmpty())
                assertEquals(0, system.data.count)
                assertTrue(system.counts.values.all { it.converted == 0 })
            }
            system.unresolvedPages.firstOrNull()?.let {
                assertTrue(client.unresolved(it).isNotEmpty())
                assertEquals(10, requests)
            }
            val requestsBeforeCandidates = requests
            val candidates = client.bringUpCandidates(system)
            assertEquals(requestsBeforeCandidates + system.bringUpPages.size, requests)
            if (system.sourceRecords.any { it.startsWith("SYSTEMENTER.BIN@") }) assertTrue(candidates.isNotEmpty())
            if (system.enabled) {
                // The enabled slice ships exactly one runnable read-only translated candidate.
                val candidate = candidates.single()
                assertTrue(candidate.runnable && candidate.runtime != null && candidate.blockers.isEmpty() && candidate.sourceRecords.isNotEmpty())
            } else {
                assertTrue(candidates.all { !it.runnable && it.runtime == null && it.sourceRecords.isNotEmpty() })
                assertEquals(candidates.map { it.supportCount }.sortedDescending(), candidates.map { it.supportCount })
            }
            assertTrue("Only one selected path/system should transfer", transferred < 2 * 1024 * 1024)
            assertEquals(app.noBackupFilesDir.canonicalFile, cache.canonicalFile.parentFile)
            assertFalse(cache.listFiles().orEmpty().any { it.extension == "partial" })

            val offline = VehiclePackCatalog(index, PackObjectStore(cache, PackRangeSource { _, _, _, _ ->
                error("Offline reopen must not access the network")
            }))
            val cachedVehicle = offline.selectedVehicle(ids)
            val cachedSystem = offline.system(cachedVehicle, cachedVehicle.systems.single { it.id == selected.id })
            assertEquals(system.id, cachedSystem.id)
            assertEquals(system.counts, cachedSystem.counts)
            assertEquals(system.enabled, cachedSystem.enabled)
            assertEquals(candidates.map { it.fingerprint }, offline.bringUpCandidates(cachedSystem).map { it.fingerprint })
            assertEquals(selectionBefore, prefs.get(CatalogRepository.KEY))
        } finally { cache.deleteRecursively() }
    }
}
