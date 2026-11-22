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
    private class Fixture(enabled: Boolean = false, crossEcu: Boolean = false, explicitReads: Boolean = true, supportProvenance: Boolean? = null, bringUp: Boolean = false,
                          referenced: Boolean = false, corruptReference: String? = null, corruptExecutable: String? = null,
                          linkOverride: String? = null, categoryOverride: Map<String, String>? = null) {
        private val container = ByteArrayOutputStream().also { it.write("BOD3PACKS1\n".toByteArray()) }
        private fun add(value: Any?): Map<String, Any> {
            val json = JsonWriter.write(value).toByteArray(); val data = packed(json); val at = container.size()
            container.write(data)
            return mapOf("offset" to at, "bytes" to data.size, "jsonBytes" to json.size, "sha256" to sha256(data))
        }
        private val why = if (enabled) emptyList() else listOf("mapping_missing")
        private val link = com.bod3.app.obd.kwp.DiagnosticLink(com.bod3.app.obd.protocol.ObdProtocol.ISO_15765_4_CAN_11_500)
        private val reads = com.bod3.app.diagnostics.EcuReadDefinitions(
            identification = if (corruptExecutable == "empty-id") emptyList() else listOf(com.bod3.app.diagnostics.IdentificationRead(
                com.bod3.app.diagnostics.DiagnosticRead("synthetic-id", "Synthetic identity", "221234", "621234", minBytes = 1, maxBytes = 16,
                    sourceRecords = listOf("synthetic:id")))),
            faults = if (corruptExecutable == "empty-fault") null else com.bod3.app.diagnostics.FaultRead(
                com.bod3.app.diagnostics.DiagnosticRead("synthetic-fault", "Synthetic faults", "1902FF", "5902", minBytes = 0, maxBytes = 255,
                    sourceRecords = listOf("synthetic:fault")),
                com.bod3.app.diagnostics.ToyotaFaultLayout(255, 0, 2, 2, false, 0, 1, 0,
                    labels = if (corruptExecutable == "empty-labels") emptyList() else listOf(com.bod3.app.diagnostics.ToyotaFaultLabel(
                        "00001234", "P1234", "Synthetic fault, not vehicle data", listOf("synthetic:label"))),
                    sourceRecords = listOf("synthetic:layout"))))
        val original = if (enabled || corruptExecutable == "disabled-data") CatalogData(
            ecus = listOf(EcuDefinition(name = "Synthetic ECU", requestId = "7E0", responseId = "7E8", link = link, sourceRecords = listOf("synthetic:ecu"),
                diagnostics = if (explicitReads) reads else null)),
            pids = if (corruptExecutable == "empty-live") emptyList() else listOf(CustomPid("synthetic-rpm", "Synthetic RPM", "221234", "(A*256+B)/4", header = if (crossEcu) "7E1" else "7E0", responseHeader = "7E8", link = link, sourceRecords = listOf("synthetic:pid"),
                supportChecks = supportProvenance?.let { listOf(com.bod3.app.datapack.PidSupportCheck("220001", "A",
                    sourceRecords = if (it) listOf("synthetic:prerequisite") else emptyList())) }.orEmpty())),
            actuators = listOf(ActuatorRecipe("synthetic-io", "Synthetic output", "Not vehicle data",
                ActuatorModule("Synthetic ECU", requestId = "7E0", responseId = "7E8", link = link),
                start = listOf(0x2F, 0x12, 0x34, 3, 1), stop = listOf(0x2F, 0x12, 0x34, 0),
                safetyNotes = listOf("Synthetic only"), sourceRecords = listOf("synthetic:test"))),
            coding = listOf(CodingRecipe("synthetic-coding", "Synthetic coding", module = ActuatorModule("Synthetic ECU", requestId = "7E0", responseId = "7E8", link = link),
                method = CodingMethod.DID_VALUE, did = 0x1234, byteIndex = 0,
                options = listOf(com.bod3.app.coding.CodingOption(0, "Off"), com.bod3.app.coding.CodingOption(1, "On")),
                sourceRecords = listOf("synthetic:coding"))),
        ) else CatalogData()
        private val counts = listOf("pids", "dtcs", "tests", "coding", "specialFunctions", "ecuInfo").associateWith {
            mapOf("converted" to if (enabled && it in setOf("pids", "tests", "coding", "dtcs", "ecuInfo")) (if (corruptExecutable == "counts" && it == "dtcs") 2 else 1) else 0, "unresolved" to if (enabled) 0 else null)
        }
        private val bringUpPage = if (bringUp) add(mapOf("kind" to "bring-up-candidates", "candidates" to listOf(
            com.bod3.app.bringup.BringUpCodec.candidateMap(com.bod3.app.bringup.BringUpScenarios.candidate())))) else null
        private val unresolvedPage = if (referenced) add(mapOf("kind" to "disabled-items", "items" to listOf(
            mapOf("id" to "synthetic:row", "enabled" to false, "reasons" to listOf("mapping_missing"))))) else null
        private val ecuSource = if (referenced) mapOf("sourceFile" to "synthetic.bin", "sha256" to "a".repeat(64),
            "object" to add(mapOf("kind" to "ecu-source", "sourceFile" to if (corruptReference == "ecu") "another.bin" else "synthetic.bin",
                "sha256" to "a".repeat(64), "pages" to listOf(unresolvedPage)))) else null
        private val functionSource = if (referenced) mapOf("sourceRecord" to "synthetic:function", "functionKey" to 1, "declaredLiveRows" to 1,
            "object" to add(mapOf("kind" to "function-source", "sourceRecord" to "synthetic:function",
                "functionKey" to if (corruptReference == "function") 2 else 1, "declaredLiveRows" to 1, "livePages" to emptyList<Any>()))) else null
        private val linkStatus = linkOverride ?: if (enabled) "proven" else "unproven"
        private val linkReasons = if (linkStatus == "proven") emptyList<String>() else (why.ifEmpty { listOf("ecu_entry_untranslated") })
        private val categoryStatus = categoryOverride ?: listOf("pids", "dtcs", "ecuInfo", "actuators", "coding", "specialFunctions").associateWith { category ->
            if (enabled && category != "specialFunctions") "executable" else "unknown"
        }
        private val system = add(mapOf("kind" to "vehicle-system", "id" to "synthetic-system", "name" to "Synthetic ECU", "enabled" to enabled,
            "linkStatus" to linkStatus, "linkReasons" to linkReasons, "categoryStatus" to categoryStatus,
            "reasons" to why, "sourceVerified" to true, "sourceRecords" to listOf("synthetic:system"), "counts" to counts,
            "ecuSource" to ecuSource, "functionSource" to functionSource,
            "readOnly" to (enabled && corruptExecutable != "writable"),
            "bringUpPages" to listOfNotNull(bringUpPage), "data" to CatalogCodec.dataMap(original)))
        private val systemChoice = mapOf("id" to "synthetic-system", "name" to "Synthetic ECU", "enabled" to enabled,
            "linkStatus" to linkStatus, "linkReasons" to linkReasons, "categoryStatus" to categoryStatus, "reasons" to why, "counts" to counts,
            "readOnly" to enabled, "object" to system)
        val path = CatalogPack("synthetic-path", "Synthetic path", nodes = listOf(
            CatalogNode("make", null, CatalogLevel.MAKE, "Synthetic make"), CatalogNode("model", "make", CatalogLevel.MODEL, "Synthetic model"),
            CatalogNode("generation", "model", CatalogLevel.GENERATION, "Synthetic generation"), CatalogNode("variant", "generation", CatalogLevel.VARIANT, "Synthetic variant")))
        private val systemsSource = if (referenced) add(mapOf("kind" to "manual-vehicle", "id" to "synthetic:source-leaf", "hierarchyResolved" to true,
            "selection" to listOf(mapOf("id" to "synthetic:source-leaf", "name" to if (corruptReference == "path") "Another source path" else "Synthetic source path")),
            "systems" to listOf(systemChoice))) else null
        private var root: Map<String, Any> = add(mapOf("kind" to "converted-vehicle", "level" to "variant", "id" to "variant", "name" to "Synthetic variant",
            "catalog" to CatalogCodec.packMap(path), "sourceSelection" to listOf("Synthetic source path"), "hierarchyResolved" to true, "reasons" to emptyList<String>()) +
            if (referenced) mapOf("systemsSource" to systemsSource, "systemsSourceId" to "synthetic:source-leaf", "sourceRecords" to listOf("synthetic:source-leaf"))
            else mapOf("systems" to listOf(systemChoice)))
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

    fun cacheLruStaysBoundedAcrossDownloadsAndReopening() = temporary { cache ->
        val random = java.util.Random(714L)
        val objects = linkedMapOf<String, ByteArray>()
        var at = PackRange.MAGIC_BYTES
        val refs = (0..4).map { i ->
            val noise = ByteArray(900_000).also(random::nextBytes)
            val raw = JsonWriter.write(mapOf("kind" to "cache-test", "id" to i, "data" to java.util.Base64.getEncoder().encodeToString(noise))).toByteArray()
            val data = packed(raw); val digest = sha256(data); objects[digest] = data
            PackRange(at, data.size, raw.size, digest).also { at += data.size }
        }
        val container = "toyota-${"0".repeat(64)}.bin"
        val budget = PackRange.MAX_PACKED_BYTES.toLong(); var requests = 0
        val store = PackObjectStore(cache, PackRangeSource { _, _, ref, _ -> requests++; objects.getValue(ref.sha256) }, budget)
        refs.take(4).forEach { store.load(container, at, it) }
        check(requests == 4)
        store.load(container, at, refs[0]) // Keep 0 newer than 1 in the LRU, even if file timestamps tie.
        store.load(container, at, refs[4])
        check(cache.listFiles()!!.sumOf { it.length() } <= budget)
        check(File(cache, refs[0].sha256 + ".gz").isFile && !File(cache, refs[1].sha256 + ".gz").exists())
        val offline = PackObjectStore(cache, PackRangeSource { _, _, _, _ -> error("Unexpected network") }, budget)
        check(offline.load(container, at, refs[0])["id"] == 0.0)
        check(cache.listFiles()!!.sumOf { it.length() } <= budget && requests == 5)
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

    fun referencedSourcesAreScopedAndReopenOffline() = temporary { cache ->
        val fixture = Fixture(referenced = true); val client = fixture.client(cache)
        val ids = listOf("make", "model", "generation", "variant")
        val vehicle = client.selectedVehicle(ids)
        check(fixture.fetches == 6) // Exact path plus its shared source-system descriptors, no ECU metadata yet.
        val system = client.system(vehicle, vehicle.systems.single())
        check(fixture.fetches == 9 && system.unresolvedPages.size == 1)
        check(client.unresolved(system.unresolvedPages.single()).single()["id"] == "synthetic:row")
        val count = fixture.fetches
        val offline = VehiclePackCatalog(fixture.index, PackObjectStore(cache, PackRangeSource { _, _, _, _ -> error("Unexpected network") }))
        val restored = offline.selectedVehicle(ids)
        check(offline.system(restored, restored.systems.single()).unresolvedPages == system.unresolvedPages)
        check(fixture.fetches == count)
    }

    fun referencedSourcesCannotCrossVehicleEcuOrFunction() = temporary { cache ->
        for (bad in listOf("path", "ecu", "function")) {
            val client = Fixture(referenced = true, corruptReference = bad).client(cache)
            rejects {
                val vehicle = client.selectedVehicle(listOf("make", "model", "generation", "variant"))
                client.system(vehicle, vehicle.systems.single())
            }
        }
    }

    fun trustedConversionNotImportedFlags() = temporary { cache ->
        val f = Fixture(enabled = true); check(f.original.problems().isEmpty()) { f.original.problems().toString() }
        val c = f.client(cache); val v = c.selectedVehicle(listOf("make", "model", "generation", "variant")); val s = c.system(v, v.systems.single())
        check(s.enabled && !s.data.actuators.single().verified && !s.data.coding.single().verified)
        check(s.data.pids.single().decode(listOf(0x62, 0x12, 0x34, 0x10, 0)) == 1024.0)
        val imported = CatalogCodec.dataOrThrow(JsonWriter.write(CatalogCodec.dataMap(s.data)))
        check(!imported.actuators.single().verified && !imported.coding.single().verified)
        val supplied = com.bod3.app.catalog.CatalogData(security = listOf(com.bod3.app.catalog.CatalogSecurity(
            "unmarked-security", "Synthetic", com.bod3.app.obd.uds.SecurityAccessSpec(1,
                com.bod3.app.obd.uds.SecurityAlgorithm.STATIC_KEY, listOf(1)), verified = true,
            provenMarker = "corpus-assertion", reviewHash = "0".repeat(64))))
        val checked = CatalogCodec.dataOrThrow(JsonWriter.write(CatalogCodec.dataMap(supplied)))
        check(!checked.security.single().verified)
    }

    fun unprovenEnabledSystemIsRejectedWithExplicitLinkError() = temporary { cache ->
        val f = Fixture(enabled = true, linkOverride = "unproven")
        val c = f.client(cache)
        try {
            val v = c.selectedVehicle(listOf("make", "model", "generation", "variant"))
            c.system(v, v.systems.single())
            error("Expected unproven enabled system to fail validation")
        } catch (expected: IllegalArgumentException) {
            check(expected.message.orEmpty().contains("Enabled status disagrees") || expected.message.orEmpty().contains("Enabled system requires linkStatus proven"))
        }
    }

    fun disabledProvenLinkWithUnresolvedCategoriesIsAccepted() = temporary { cache ->
        val categories = listOf("pids", "dtcs", "ecuInfo", "actuators", "coding", "specialFunctions")
            .associateWith { "unresolved" }
        val fixture = Fixture(enabled = false, linkOverride = "proven", categoryOverride = categories)
        val client = fixture.client(cache)
        val vehicle = client.selectedVehicle(listOf("make", "model", "generation", "variant"))
        val choice = vehicle.systems.single()
        val system = client.system(vehicle, choice)
        check(!system.enabled && choice.linkStatus == "proven" && system.data.count == 0)
        check(choice.categoryStatus == categories && system.reasons.isNotEmpty())
    }

    fun pinnedBringUpCandidatesAreSeparateFromExecutableSystemFlags() = temporary { cache ->
        val fixture = Fixture(bringUp = true); val client = fixture.client(cache)
        val vehicle = client.selectedVehicle(listOf("make", "model", "generation", "variant"))
        val system = client.system(vehicle, vehicle.systems.single())
        val candidates = client.bringUpCandidates(system)
        check(!system.enabled && system.data.count == 0 && candidates.single().runnable)
        check(candidates.single().runtime!!.data.actuators.all { it.verified })
        val imported = com.bod3.app.bringup.BringUpCodec.candidate(
            com.bod3.app.bringup.BringUpCodec.candidateMap(candidates.single()))
        check(!imported.runnable && imported.runtime!!.data.actuators.none { it.verified })
        val count = fixture.fetches
        client.bringUpCandidates(system)
        check(fixture.fetches == count) // Exact selected-system candidate pages are cached for offline use.
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

    fun enabledCannotMeanEmptyOrMiscalculatedAndDisabledCannotContainData() = temporary { cache ->
        for (bad in listOf("empty-live", "empty-id", "empty-fault", "empty-labels", "counts", "disabled-data")) {
            val f = Fixture(enabled = bad != "disabled-data", corruptExecutable = bad)
            val c = f.client(cache); val v = c.selectedVehicle(listOf("make", "model", "generation", "variant"))
            rejects { c.system(v, v.systems.single()) }
        }
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

    fun enabledSystemReportsExactCoverageWithoutReadOnlyFlagCoupling() = temporary { cache ->
        val f = Fixture(enabled = true); val c = f.client(cache)
        val v = c.selectedVehicle(listOf("make", "model", "generation", "variant"))
        val s = c.system(v, v.systems.single())
        check(s.enabled && s.readOnly && s.coverage == "1 ECU · 1 PIDs · 1 DTCs · 1 ECU info read") { s.coverage ?: "null coverage" }
        // `readOnly` is no longer a substitute for independently validated link/category status.
        val withoutLegacyFlag = Fixture(enabled = true, corruptExecutable = "writable").client(cache)
        val wv = withoutLegacyFlag.selectedVehicle(listOf("make", "model", "generation", "variant"))
        check(withoutLegacyFlag.system(wv, wv.systems.single()).enabled)
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
        var recoveredCandidates = 0
        val checkedSystems = mutableSetOf<String>()
        val checkedPages = mutableSetOf<String>()
        val checkedCandidates = mutableSetOf<List<String>>()
        val enabledSystems = mutableListOf<PackSystem>()
        fun visit(level: PackLevel, ids: List<String>) {
            for (choice in level.children) {
                if (choice.level != "variant") visit(client.open(choice), ids + choice.id)
                else {
                    val vehicle = client.vehicle(choice)
                    check(client.selectedVehicle(ids + choice.id).variantId == vehicle.variantId)
                    for (system in vehicle.systems) {
                        if (!checkedSystems.add(system.id)) continue // Reused exact source record, not another ECU mapping.
                        val loaded = client.system(vehicle, system)
                        if (loaded.enabled) {
                            // Exactly one pinned read-only slice; asserted exhaustively below.
                            check(loaded.readOnly && loaded.data.count > 0)
                            enabledSystems += loaded
                            continue
                        }
                        check(loaded.data.count == 0)
                        loaded.unresolvedPages.firstOrNull()?.let { if (checkedPages.add(it.sha256)) check(client.unresolved(it).isNotEmpty()) }
                        if (!checkedCandidates.add(loaded.bringUpPages.map { it.sha256 })) continue
                        val candidates = client.bringUpCandidates(loaded)
                        check(candidates.all { !it.runnable && it.runtime == null && it.sourceRecords.isNotEmpty() })
                        check(candidates.map { it.supportCount } == candidates.map { it.supportCount }.sortedDescending())
                        check(candidates.all { it.metadata.keys.containsAll(listOf("protocol", "baudRaw", "init", "addresses", "timing", "session")) })
                        if (loaded.sourceRecords.any { it.startsWith("SYSTEMENTER.BIN@") }) check(candidates.isNotEmpty())
                        recoveredCandidates += candidates.size
                    }
                    variants++
                }
            }
        }
        visit(client.root(), emptyList())
        check(variants == (index.summary["normalizedVehicleChoices"] as Number).toInt() && recoveredCandidates > 0)
        check(enabledSystems.map { it.id }.toSet() == setOf("SYSLIST_EU.BIN@0009d8f2", "SYSLIST_EU.BIN@00023a75"))
        for (enabled in enabledSystems) {
            check(enabled.readOnly)
            check(enabled.coverage == "1 ECU · 30 PIDs · 1,414 DTCs · 1 ECU info read") { enabled.coverage ?: "null coverage" }
            val ecu = enabled.data.ecus.single()
            check(enabled.data.pids.size == 30 && enabled.counts.getValue("pids").converted == 30)
            check(enabled.counts.getValue("dtcs").converted == 1414 && enabled.counts.getValue("ecuInfo").converted == 1)
            check(enabled.counts.getValue("tests").converted == 0 && enabled.counts.getValue("coding").converted == 0 &&
                enabled.counts.getValue("specialFunctions").converted == 0)
            check(enabled.data.actuators.isEmpty() && enabled.data.coding.isEmpty() && enabled.data.security.isEmpty())
            val faults = checkNotNull(ecu.diagnostics?.faults)
            check(faults.clear == null && ecu.diagnostics!!.identification.size == 1)
            val candidate = client.bringUpCandidates(enabled).single()
            check(candidate.runnable && candidate.runtime != null && candidate.blockers.isEmpty() && candidate.sourceRecords.isNotEmpty())
        }
    }
}
