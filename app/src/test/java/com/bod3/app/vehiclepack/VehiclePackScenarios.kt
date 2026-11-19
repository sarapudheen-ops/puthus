package com.bod3.app.vehiclepack

import com.bod3.app.actuator.ActuatorModule
import com.bod3.app.actuator.ActuatorRecipe
import com.bod3.app.catalog.CatalogCodec
import com.bod3.app.catalog.CatalogData
import com.bod3.app.catalog.CatalogLevel
import com.bod3.app.catalog.CatalogNode
import com.bod3.app.catalog.CatalogPack
import com.bod3.app.coding.CodingMethod
import com.bod3.app.coding.CodingRecipe
import com.bod3.app.datapack.CustomPid
import com.bod3.app.datapack.EcuDefinition
import com.bod3.app.profile.JsonWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.URL
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import com.bod3.app.catalog.CatalogAccess
import com.bod3.app.catalog.CatalogRepository
import com.bod3.app.datapack.DataPackStore
import com.bod3.app.datapack.LiveDisplay
import com.bod3.app.datapack.LiveSelection
import com.bod3.app.live.LiveCatalog
import com.bod3.app.obd.connection.KeyValueStore
import com.bod3.app.obd.session.EcuTarget
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** All addresses/recipes in these tests are synthetic, not recovered vehicle mappings. */
object VehiclePackScenarios {
    private fun packed(raw: ByteArray): ByteArray = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(raw) } }.toByteArray()
    private inline fun rejects(block: () -> Unit) {
        try { block() } catch (_: IllegalArgumentException) { return } catch (_: IllegalStateException) { return }
        error("Invalid vehicle-pack data was accepted")
    }
    private inline fun temporary(block: (File) -> Unit) {
        val folder = Files.createTempDirectory("bod3-pack-test").toFile()
        try { block(folder) } finally { folder.deleteRecursively() }
    }
    private class Fixture(enabled: Boolean = false, crossEcu: Boolean = false, explicitReads: Boolean = true, supportProvenance: Boolean? = null) {
        private val container = ByteArrayOutputStream().also { it.write("BOD3PACKS1\n".toByteArray()) }
        private fun add(value: Any?): Map<String, Any> {
            val json = JsonWriter.write(value).toByteArray(); val data = packed(json); val at = container.size()
            container.write(data)
            return mapOf("offset" to at, "bytes" to data.size, "jsonBytes" to json.size, "sha256" to sha256(data))
        }
        private val why = if (enabled) emptyList() else listOf("mapping_missing")
        val original = if (enabled) CatalogData(
            ecus = listOf(EcuDefinition(name = "Synthetic ECU", requestId = "7E0", responseId = "7E8", sourceRecords = listOf("synthetic:ecu"),
                diagnostics = if (explicitReads) com.bod3.app.diagnostics.EcuReadDefinitions() else null)),
            pids = listOf(CustomPid("synthetic-rpm", "Synthetic RPM", "221234", "(A*256+B)/4", header = if (crossEcu) "7E1" else "7E0", responseHeader = "7E8", sourceRecords = listOf("synthetic:pid"),
                supportChecks = supportProvenance?.let { listOf(com.bod3.app.datapack.PidSupportCheck("220001", "A",
                    sourceRecords = if (it) listOf("synthetic:prerequisite") else emptyList())) }.orEmpty())),
            actuators = listOf(ActuatorRecipe("synthetic-io", "Synthetic output", "Not vehicle data",
                ActuatorModule("Synthetic ECU", requestId = "7E0", responseId = "7E8"),
                start = listOf(0x2F, 0x12, 0x34, 3, 1), stop = listOf(0x2F, 0x12, 0x34, 0),
                safetyNotes = listOf("Synthetic only"), sourceRecords = listOf("synthetic:test"))),
            coding = listOf(CodingRecipe("synthetic-coding", "Synthetic coding", module = ActuatorModule("Synthetic ECU", requestId = "7E0", responseId = "7E8"),
                method = CodingMethod.DID_VALUE, did = 0x1234, byteIndex = 0,
                options = listOf(com.bod3.app.coding.CodingOption(0, "Off"), com.bod3.app.coding.CodingOption(1, "On")),
                sourceRecords = listOf("synthetic:coding"))),
        ) else CatalogData()
        private val counts = listOf("pids", "dtcs", "tests", "coding", "specialFunctions", "ecuInfo").associateWith {
            mapOf("converted" to if (enabled && it in setOf("pids", "tests", "coding")) 1 else 0, "unresolved" to if (enabled) 0 else null)
        }
        private val system = add(mapOf("kind" to "vehicle-system", "id" to "synthetic-system", "name" to "Synthetic ECU", "enabled" to enabled,
            "reasons" to why, "sourceVerified" to true, "sourceRecords" to listOf("synthetic:system"), "counts" to counts,
            "data" to CatalogCodec.dataMap(original)))
        private val systemChoice = mapOf("id" to "synthetic-system", "name" to "Synthetic ECU", "enabled" to enabled, "reasons" to why, "counts" to counts, "object" to system)
        val path = CatalogPack("synthetic-path", "Synthetic path", nodes = listOf(
            CatalogNode("make", null, CatalogLevel.MAKE, "Synthetic make"), CatalogNode("model", "make", CatalogLevel.MODEL, "Synthetic model"),
            CatalogNode("generation", "model", CatalogLevel.GENERATION, "Synthetic generation"), CatalogNode("variant", "generation", CatalogLevel.VARIANT, "Synthetic variant")))
        private var root: Map<String, Any> = add(mapOf("kind" to "converted-vehicle", "level" to "variant", "id" to "variant", "name" to "Synthetic variant",
            "catalog" to CatalogCodec.packMap(path), "sourceSelection" to listOf("Synthetic source path"), "hierarchyResolved" to true, "reasons" to emptyList<String>(), "systems" to listOf(systemChoice)))
        init {
            var id = "variant"
            for ((parent, level) in listOf("generation" to "generation", "model" to "model", "make" to "make", "root" to "root")) {
                root = add(mapOf("kind" to "catalog-level", "level" to level, "id" to parent, "name" to parent,
                    "children" to listOf(mapOf("id" to id, "name" to id, "level" to if (id == "variant") "variant" else id, "object" to root))))
                id = parent
            }
        }
        val bytes: ByteArray get() = container.toByteArray()
        val index: VehiclePackIndex get() {
            val digest = sha256(bytes)
            return VehiclePackIndex.fromBundledJson(JsonWriter.write(mapOf("format" to "bod3-toyota-vehicle-index", "formatVersion" to 1,
                "converterVersion" to 1, "sourceSha256" to VehiclePackIndex.SOURCE_SHA256, "manualSelectionOnly" to true,
                "container" to "toyota-$digest.bin", "containerSha256" to digest, "containerBytes" to bytes.size, "root" to root,
                "catalogRoot" to root, "summary" to mapOf("complete" to false, "reasons" to mapOf("mapping_missing" to "Synthetic missing mapping")))))
        }
        var fetches = 0
        val source = PackRangeSource { _, _, range, check ->
            check(); fetches++; bytes.copyOfRange(range.offset.toInt(), range.offset.toInt() + range.bytes)
        }
        fun client(cache: File) = VehiclePackCatalog(index, PackObjectStore(cache, source))
    }

    fun boundedGzip() {
        val raw = "{\"kind\":\"synthetic\",\"value\":\"雪\"}".toByteArray(); val data = packed(raw)
        val range = PackRange(11, data.size, raw.size, sha256(data))
        check(PackCompression.decode(data, range).contentEquals(raw))
        check(PackCompression.json(data, range)["value"] == "雪")
        rejects { PackCompression.decode(data, range.copy(sha256 = "0".repeat(64))) }
        rejects { PackCompression.decode(data, range.copy(jsonBytes = 1)) }
        rejects { PackCompression.decode(data, range.copy(jsonBytes = raw.size + 1)) }
        val extra = data + packed("{}".toByteArray())
        rejects { PackCompression.decode(extra, range.copy(bytes = extra.size, sha256 = sha256(extra))) }
        val corrupt = data.copyOf().also { it[it.lastIndex - 4] = 0 }
        rejects { PackCompression.decode(corrupt, range.copy(sha256 = sha256(corrupt))) }
        rejects { range.copy(offset = Long.MAX_VALUE).validate(1000) }
        rejects { PackRange.parse(mapOf("offset" to 11, "bytes" to true, "jsonBytes" to raw.size, "sha256" to range.sha256), 1000) }
    }

    fun rangeHttpContract() {
        val range = PackRange(100, 20, 30, "0".repeat(64))
        PackHttpSource.checkResponse(206, "bytes 100-119/1000", 20, null, 1000, range)
        rejects { PackHttpSource.checkResponse(200, null, 1000, null, 1000, range) }
        rejects { PackHttpSource.checkResponse(206, "bytes 101-120/1000", 20, null, 1000, range) }
        rejects { PackHttpSource.checkResponse(206, "bytes 100-119/999", 20, null, 1000, range) }
        rejects { PackHttpSource.checkResponse(206, "bytes 100-119/1000", 20, "gzip", 1000, range) }
        rejects { PackHttpSource.checkUrl(URL("http://github.com/unsafe")) }
        rejects { PackHttpSource.checkUrl(URL("https://github.com.evil.example/unsafe")) }
        rejects { PackHttpSource.checkUrl(URL("https://user@github.com/unsafe")) }
        PackHttpSource.checkUrl(URL("https://release-assets.githubusercontent.com/trusted-redirect"))
    }

    fun onlySelectedPathAndCache() = temporary { cache ->
        val fixture = Fixture(); val client = fixture.client(cache)
        val vehicle = client.selectedVehicle(listOf("make", "model", "generation", "variant"))
        check(fixture.fetches == 5) // Four navigation levels, one selected variant; no systems/whole corpus.
        check(vehicle.systems.single().counts.getValue("pids").unresolved == null)
        val system = client.system(vehicle, vehicle.systems.single())
        check(!system.enabled && system.data.count == 0 && system.reasons == listOf("mapping_missing"))
        check(fixture.fetches == 6)
        client.selectedVehicle(listOf("make", "model", "generation", "variant"))
        client.system(vehicle, vehicle.systems.single())
        check(fixture.fetches == 6) // Available fully offline after the selected objects were cached.
        rejects { client.selectedVehicle(listOf("make", "wrong", "generation", "variant")) }
        rejects { client.selectedVehicle(listOf("VIN-does-not-select-a-vehicle")) }
        val first = cache.listFiles()!!.first(); first.writeBytes(byteArrayOf(0))
        client.selectedVehicle(listOf("make", "model", "generation", "variant"))
        client.system(vehicle, vehicle.systems.single())
        check(fixture.fetches == 7)
        check(cache.listFiles()!!.none { it.extension == "partial" })
    }

    fun trustedConversionNotImportedFlags() = temporary { cache ->
        val f = Fixture(enabled = true); check(f.original.problems().isEmpty()) { f.original.problems().toString() }
        val c = f.client(cache); val v = c.selectedVehicle(listOf("make", "model", "generation", "variant")); val s = c.system(v, v.systems.single())
        check(s.enabled && s.data.actuators.single().verified && s.data.coding.single().verified)
        check(s.data.pids.single().decode(listOf(0x62, 0x12, 0x34, 0x10, 0)) == 1024.0)
        val imported = CatalogCodec.dataOrThrow(JsonWriter.write(CatalogCodec.dataMap(s.data)))
        check(!imported.actuators.single().verified && !imported.coding.single().verified)
    }

    fun crossEcuDefinitionRejected() = temporary { cache ->
        val c = Fixture(enabled = true, crossEcu = true).client(cache)
        val v = c.selectedVehicle(listOf("make", "model", "generation", "variant"))
        rejects { c.system(v, v.systems.single()) }
    }

    fun convertedEcuCannotSubstituteLegacyGenericReads() = temporary { cache ->
        val c = Fixture(enabled = true, explicitReads = false).client(cache)
        val v = c.selectedVehicle(listOf("make", "model", "generation", "variant"))
        rejects { c.system(v, v.systems.single()) }
    }

    fun convertedPrerequisitesMustCarryTheirOwnProvenance() = temporary { cache ->
        for (hasSources in listOf(false, true)) {
            val f = Fixture(enabled = true, supportProvenance = hasSources)
            val c = f.client(cache); val v = c.selectedVehicle(listOf("make", "model", "generation", "variant"))
            if (!hasSources) rejects { c.system(v, v.systems.single()) }
            else {
                val data = c.system(v, v.systems.single()).data
                check(data.pids.single().supportChecks.single().sourceRecords == listOf("synthetic:prerequisite"))
                // Even fully preserved prerequisite provenance is NOT write trust on ordinary import.
                val imported = CatalogCodec.dataOrThrow(JsonWriter.write(CatalogCodec.dataMap(data)))
                check(imported.pids == data.pids && imported.actuators.none { it.verified } && imported.coding.none { it.verified })
            }
        }
    }

    fun cancelledDownloadNeverInstalls() = temporary { cache ->
        val f = Fixture(); var checks = 0
        rejects { f.client(cache).root { checks++; error("Synthetic cancellation") } }
        check(checks == 1 && f.fetches == 0 && cache.listFiles().orEmpty().isEmpty())
    }

    private class MemoryStore : KeyValueStore {
        val values = mutableMapOf<String, String>(); var writes = 0
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String?) { writes++; if (value == null) values.remove(key) else values[key] = value }
    }

    fun manualPathIsOneAtomicNamesOnlyCommit() {
        val store = MemoryStore(); val fixture = Fixture()
        val repo = CatalogRepository(CatalogPack("base", "Base"), store)
        rejects { repo.selectDownloadedPath(fixture.path.copy(nodes = fixture.path.nodes.dropLast(1))) }
        rejects { repo.selectDownloadedPath(fixture.path.copy(nodes = fixture.path.nodes.mapIndexed { i, n ->
            if (i == 3) n.copy(data = CatalogData(dtcs = mapOf("P0001" to "Not names-only"))) else n
        })) }
        check(store.writes == 0 && repo.state().selectedId == null)
        repo.selectDownloadedPath(fixture.path)
        check(store.writes == 1 && repo.state().selectedId == "variant" && repo.state().recents == listOf("variant"))
        check(repo.state().downloadedSelections == setOf("variant") && repo.pack().nodes.all { it.data.count == 0 })
        val restored = CatalogRepository(CatalogPack("base", "Base"), store)
        check(restored.state().downloadedSelections == setOf("variant") && restored.selected()?.path?.size == 4)
        val before = store.values.toMap()
        rejects { restored.selectDownloadedPath(fixture.path.copy(nodes = fixture.path.nodes.mapIndexed { i, n ->
            if (i == 2) n.copy(parentId = "wrong-parent") else n
        })) }
        check(store.values == before && store.writes == 1)
        // A saved profile link and an ordinary pack import cannot activate a downloaded vehicle.
        restored.select(null)
        restored.import(fixture.path)
        check(restored.state().selectedId == null)
        val imported = CatalogRepository(CatalogPack("base", "Base"), MemoryStore())
        imported.import(fixture.path)
        check(imported.state().downloadedSelections.isEmpty() && imported.state().selectedId == null)
    }

    fun closedSystemScopeNeverMixesGlobalPidsAndSelections() {
        val f = Fixture(enabled = true)
        val global = CustomPid("global", "Unscoped global", "010C", "A")
        val repo = CatalogRepository(CatalogPack("base", "Base", library = CatalogData(pids = listOf(global))), MemoryStore())
        repo.selectDownloadedPath(f.path)
        var scope: CatalogData? = null
        val access = CatalogAccess(repo) { scope }
        check(access.customPids() == listOf(global)) // ordinary manual behavior is unchanged
        scope = CatalogData()
        check(access.customPids().isEmpty() && access.scanEcus().isEmpty() && access.actuators().isEmpty() && access.coding().isEmpty())
        scope = f.original
        check(access.customPids() == f.original.pids && access.scanEcus() == f.original.ecus)
        val values = LiveCatalog.forSystem(f.original.ecus.single(), f.original.pids + global)
        check(values.size == 1 && values.all { it is com.bod3.app.live.LiveValueDef.Custom && it.pid.target != EcuTarget.Broadcast })
        val store = DataPackStore(MemoryStore())
        val standard = LiveSelection(listOf("global"), emptyMap())
        val selectedA = LiveSelection(listOf("synthetic-rpm"), mapOf("synthetic-rpm" to LiveDisplay.GRAPH))
        store.saveLiveSelection(standard)
        store.saveLiveSelection(selectedA, "vehicle-A/system-A")
        check(store.liveSelection() == standard && store.liveSelection("vehicle-A/system-A") == selectedA)
        check(store.liveSelection("vehicle-A/system-B") == null && store.liveSelection("vehicle-B/system-A") == null)
        scope = CatalogData()
        check(access.customPids().isEmpty()) // no stale selected-system/legacy fallback
    }

    fun connectionUsesOnlyTheManualPathAndCurrentSystemWithSparseOverrides() {
        val fixture = Fixture()
        fun connection(vararg pairs: Pair<String, Any?>) = com.bod3.app.catalog.CatalogConnection(mapOf(*pairs))
        val base = CatalogPack("base", "Base", nodes = fixture.path.nodes.mapIndexed { i, node ->
            node.copy(data = CatalogData(connection = when (i) {
                0 -> connection("requestTimeoutMs" to 6000)
                1 -> connection("searchTimeoutMs" to 21000)
                2 -> connection("requestTimeoutMs" to 7000, "forcedProtocol" to "ISO_14230_4_KWP_FAST")
                else -> connection("requestTimeoutMs" to 8000, "extendedAddress" to null)
            }))
        }, library = CatalogData(connection = connection("requestTimeoutMs" to 30000, "extraInitCommands" to listOf("ATSH 7E1"))))
        val repo = CatalogRepository(base, MemoryStore())
        val adapter = com.bod3.app.obd.connection.ConnectionProfile("adapter", "My adapter", extendedAddress = "F1")
        var scope: CatalogData? = null
        val access = CatalogAccess(repo) { scope }
        check(access.connection(adapter) == adapter) // No selection: never use the legacy library.
        repo.select("variant", adapter)
        val normal = access.connection(adapter)
        check(normal.requestTimeoutMs == 8000L && normal.searchTimeoutMs == 21000L)
        check(normal.extendedAddress == null && normal.extraInitCommands.isEmpty())
        check(normal.forcedProtocol == com.bod3.app.obd.protocol.ObdProtocol.ISO_14230_4_KWP_FAST)
        scope = CatalogData() // Downloaded selection unloaded/disabled: CLOSED, not a local fallback.
        check(access.connection(adapter).copy(id = adapter.id, name = adapter.name) == adapter)
        val source = CatalogData(connection = connection("requestTimeoutMs" to 9000, "searchTimeoutMs" to 23000,
            "useVtTimeout" to true, "extendedAddress" to "A1", "forcedProtocol" to "ISO_9141_2"))
        scope = source.overlay(repo.selected()!!.data)
        val first = access.connection(adapter)
        check(first.requestTimeoutMs == 8000L && first.searchTimeoutMs == 21000L && first.useVtTimeout)
        check(first.extendedAddress == null) // An explicit child null clears the source/base setting.
        check(first.forcedProtocol == normal.forcedProtocol)
        check(first.id == "catalog-variant" && first.extraInitCommands.isEmpty())
        scope = source.copy(connection = connection("requestTimeoutMs" to 10000, "useVtTimeout" to false))
        check(access.connection(adapter).requestTimeoutMs == 10000L && !access.connection(adapter).useVtTimeout)
        scope = CatalogData()
        check(access.connection(adapter).copy(id = adapter.id, name = adapter.name) == adapter) // No stale ECU settings.
        repo.select(null)
        scope = source
        check(access.connection(adapter) == adapter) // A cached pack may not select configuration.
        scope = null
        check(access.connection(adapter) == adapter)
        check(adapter.requestTimeoutMs == 5000L && adapter.extendedAddress == "F1") // Adapter profile unchanged.
    }

    fun controllerDownloadsNeverSelectWithoutExplicitManualConfirmation() = runBlocking {
        temporary { cache ->
            val fixture = Fixture(enabled = true); val client = fixture.client(cache)
            val selected = AtomicReference<String?>(null)
            var changesAllowed = false
            var notifications = 0
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val controller = VehiclePacksController(owner, { client }, { selected.get() },
                { selected.set(it.variantId) }, { require(changesAllowed) { "Synthetic busy/disconnect safeguard" } }, { notifications++ })
            suspend fun settled(): VehiclePacksState = withTimeout(10_000) { controller.state.first { !it.busy } }
            try {
                controller.picker(); var state = settled()
                check(selected.get() == null && controller.selectedData().count == 0)
                repeat(4) {
                    controller.browse(state.page!!.children.single()); state = settled()
                    check(selected.get() == null && notifications == 0)
                }
                check(state.preview != null)
                controller.selectPreview()
                check(selected.get() == null && controller.state.value.preview != null && controller.state.value.error != null)
                changesAllowed = true
                controller.selectPreview()
                check(selected.get() == "variant" && controller.state.value.view == PackView.SYSTEMS && notifications == 1)
                check(controller.selectedData().count == 0) // no system has been loaded
                val choice = controller.state.value.vehicle!!.systems.single()
                changesAllowed = false
                controller.openSystem(choice)
                check(controller.state.value.system == null && controller.state.value.error != null)
                changesAllowed = true
                controller.openSystem(choice); state = settled()
                check(state.view == PackView.FEATURES && controller.selectedSystem()?.id == choice.id)
                check(controller.selectedData().pids.size == 1)
                selected.set("another-explicit-vehicle")
                check(controller.selectedSystem() == null && controller.selectedData().count == 0)
            } finally { owner.cancel() }
        }
    }

    /** CI supplies the full Actions output, using the exact production Kotlin validator. */
    fun generatedBundle(directory: File) = temporary { cache ->
        val index = VehiclePackIndex.fromBundledJson(File(directory, "index.json").readText())
        val file = File(directory, index.container)
        val client = VehiclePackCatalog(index, PackObjectStore(cache, PackRangeSource { _, _, ref, check ->
            check(); ByteArray(ref.bytes).also { RandomAccessFile(file, "r").use { input -> input.seek(ref.offset); input.readFully(it) } }
        }))
        var variants = 0
        fun visit(level: PackLevel, ids: List<String>) {
            for (choice in level.children) {
                if (choice.level != "variant") visit(client.open(choice), ids + choice.id)
                else {
                    val vehicle = client.vehicle(choice)
                    check(client.selectedVehicle(ids + choice.id).variantId == vehicle.variantId)
                    for (system in vehicle.systems) {
                        val loaded = client.system(vehicle, system)
                        check(!loaded.enabled && loaded.data.count == 0)
                        loaded.unresolvedPages.firstOrNull()?.let { check(client.unresolved(it).isNotEmpty()) }
                    }
                    variants++
                }
            }
        }
        visit(client.root(), emptyList())
        check(variants == (index.summary["normalizedVehicleChoices"] as Number).toInt())
    }
}
