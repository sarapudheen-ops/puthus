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
        assertEquals(4, (index.summary["normalizedVehicleChoices"] as Number).toInt())
        assertTrue(index.containerBytes > 100 * 1024 * 1024L) // external corpus, not an APK asset
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
            for (expected in listOf("make", "model", "generation")) {
                val child = page.children.first()
                assertEquals(expected, child.level)
                ids += child.id
                page = client.open(child)
            }
            val choice = page.children.first()
            assertEquals("variant", choice.level)
            ids += choice.id
            val vehicle = client.vehicle(choice)
            assertTrue(vehicle.hierarchyResolved)
            assertEquals(ids.last(), vehicle.variantId)
            assertEquals(5, requests)
            val system = client.system(vehicle, vehicle.systems.first())
            assertEquals(6, requests)
            assertTrue(system.sourceRecords.isNotEmpty())
            if (!system.enabled) {
                assertTrue(system.reasons.isNotEmpty())
                assertEquals(0, system.data.count)
                assertTrue(system.counts.values.all { it.converted == 0 })
            }
            system.unresolvedPages.firstOrNull()?.let {
                assertTrue(client.unresolved(it).isNotEmpty())
                assertEquals(7, requests)
            }
            assertTrue("Only one selected path/system should transfer", transferred < 2 * 1024 * 1024)
            assertEquals(app.noBackupFilesDir.canonicalFile, cache.canonicalFile.parentFile)
            assertFalse(cache.listFiles().orEmpty().any { it.extension == "partial" })

            val offline = VehiclePackCatalog(index, PackObjectStore(cache, PackRangeSource { _, _, _, _ ->
                error("Offline reopen must not access the network")
            }))
            val cachedVehicle = offline.selectedVehicle(ids)
            val cachedSystem = offline.system(cachedVehicle, cachedVehicle.systems.first())
            assertEquals(system.id, cachedSystem.id)
            assertEquals(system.counts, cachedSystem.counts)
            assertEquals(system.enabled, cachedSystem.enabled)
            assertEquals(selectionBefore, prefs.get(CatalogRepository.KEY))
        } finally { cache.deleteRecursively() }
    }
}
