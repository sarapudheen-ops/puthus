package com.bod3.app.packs

import com.bod3.app.actuator.ActuatorLibrary
import com.bod3.app.actuator.ActuatorRecipeCodec
import com.bod3.app.coding.CodingLibrary
import com.bod3.app.coding.CodingRecipeCodec
import com.bod3.app.datapack.DataPack
import com.bod3.app.datapack.DataPackCodec
import com.bod3.app.datapack.DataPackStore
import com.bod3.app.datapack.VehicleDefinition
import com.bod3.app.profile.MiniJson
import com.bod3.app.profile.ProfileTransferCodec

/** The kinds of JSON file the app reads and writes. */
enum class PackKind(val label: String, val format: String) {
    DATA("Data pack (PIDs, ECU lists, DTC texts)", DataPackCodec.FORMAT),
    ACTUATORS("Bidirectional test recipes", ActuatorRecipeCodec.FORMAT),
    CODING("Coding recipes", CodingRecipeCodec.FORMAT),
    PROFILE("Saved vehicle profile", ProfileTransferCodec.FORMAT),
}

/** What a file is, or why it cannot be read at all. */
sealed interface Detection {
    data class Known(val kind: PackKind) : Detection
    data class Unreadable(val message: String) : Detection
}

object PackFiles {
    /** Larger files are refused before parsing. */
    const val MAX_BYTES = 2 * 1024 * 1024

    fun detect(text: String): Detection {
        val trimmed = text.trimStart('\uFEFF').trim()
        if (trimmed.isEmpty()) return Detection.Unreadable("The file is empty.")
        if (text.length > MAX_BYTES) return Detection.Unreadable("The file is larger than ${MAX_BYTES / 1024 / 1024} MB, so it is not a bod3 pack.")
        val root = try {
            MiniJson.parse(trimmed)
        } catch (e: IllegalArgumentException) {
            return Detection.Unreadable(jsonError(e.message, trimmed))
        }
        if (root is List<*>) return Detection.Unreadable("The file contains a JSON list. bod3 files are a JSON object starting with { and a \"format\" field.")
        if (root !is Map<*, *>) return Detection.Unreadable("The file does not contain a JSON object.")
        return when (val format = root["format"]) {
            null -> when {
                root["module"] != null && root["method"] != null -> Detection.Known(PackKind.CODING)
                listOf("pids", "vehicles", "dtcs", "moduleNames").any { root[it] != null } -> Detection.Known(PackKind.DATA)
                else -> Detection.Unreadable(
                    "The file has no \"format\" field, so its type is unknown. Add \"format\": one of ${formats()}.",
                )
            }
            is String -> PackKind.entries.firstOrNull { it.format == format }?.let { Detection.Known(it) }
                ?: Detection.Unreadable(
                    "\"$format\" files are not supported. bod3 reads ${formats()}." +
                        if (format.startsWith("bod3-")) " The file may come from a newer app version." else "",
                )
            else -> Detection.Unreadable("\"format\" must be text, e.g. \"${DataPackCodec.FORMAT}\".")
        }
    }

    private fun formats() = PackKind.entries.joinToString(", ") { "\"${it.format}\"" }

    /** Turns a parser message like `Unexpected 'x' at 120` into one with line and column. */
    fun jsonError(message: String?, text: String): String {
        val position = message?.let { Regex("at (\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val where = position?.let { p ->
            val before = text.take(p.coerceAtMost(text.length))
            val line = before.count { it == '\n' } + 1
            val column = p - (before.lastIndexOf('\n') + 1) + 1
            " (line $line, column $column)"
        }.orEmpty()
        val detail = message?.replace(Regex(" at \\d+"), "")?.trim().orEmpty()
        return "This is not valid JSON$where: $detail. Common causes: a missing comma or quote, a trailing comma, or the file was cut off."
    }
}

/** What importing a file would do, shown before anything is saved. */
data class ImportPreview(
    val kind: PackKind?,
    val title: String,
    val summary: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    internal val apply: (() -> String)? = null,
) {
    val canImport: Boolean get() = errors.isEmpty() && apply != null
}

/** Which of the user's data to export as a data pack. */
enum class DataExport(val label: String, val fileName: String) {
    PIDS("My custom PIDs", "pids"),
    VEHICLES("My vehicles and ECU lists", "ecus"),
    ALL("All my data (PIDs, ECU lists, module names, DTC texts)", "data"),
}

/**
 * Imports any bod3 file (detecting its kind) into the right library, and exports the user's data,
 * recipes and bundled samples. Every import is validated completely first; nothing is saved when a
 * file has a single problem.
 */
class PackTransfer(
    private val bundledData: List<DataPack>,
    private val dataStore: DataPackStore,
    private val actuators: ActuatorLibrary,
    private val coding: CodingLibrary,
) {

    fun preview(text: String): ImportPreview = when (val detection = PackFiles.detect(text)) {
        is Detection.Unreadable -> ImportPreview(null, "Cannot import this file", errors = listOf(detection.message))
        is Detection.Known -> when (detection.kind) {
            PackKind.DATA -> previewData(text)
            PackKind.ACTUATORS -> previewActuators(text)
            PackKind.CODING -> previewCoding(text)
            PackKind.PROFILE -> ImportPreview(
                PackKind.PROFILE, "Saved vehicle profile",
                summary = listOf("A vehicle profile (modules, identification, scan log). It is imported into Saved vehicles."),
            )
        }
    }

    /** Imports a previewed file; returns a one-line result. */
    fun apply(preview: ImportPreview): String {
        require(preview.canImport) { preview.errors.firstOrNull() ?: "Nothing to import" }
        return preview.apply!!.invoke()
    }

    private fun header(errors: List<String>) =
        if (errors.size == 1) "1 problem – nothing was imported:" else "${errors.size} problems – nothing was imported:"

    private fun previewData(text: String): ImportPreview {
        val result = DataPackCodec.decode(text)
        val pack = result.pack
        if (pack == null || result.errors.isNotEmpty()) {
            return ImportPreview(PackKind.DATA, "Data pack", errors = listOf(header(result.errors)) + result.errors.ifEmpty { listOf("Unknown problem") })
        }
        val user = dataStore.userPack()
        val bundledPids = bundledData.flatMap { it.pids }.map { it.id }.toSet()
        val bundledVehicles = bundledData.flatMap { it.vehicles }.map { it.key }.toSet()
        val userPids = user.pids.map { it.id }.toSet()
        val userVehicles = user.vehicles.map { it.key }.toSet()
        val summary = buildList {
            if (pack.pids.isNotEmpty()) {
                val replaced = pack.pids.count { it.id in userPids || it.id in bundledPids }
                add("${pack.pids.size} custom PID(s)" + if (replaced > 0) ", $replaced replacing existing ones" else "")
            }
            if (pack.vehicles.isNotEmpty()) {
                val replaced = pack.vehicles.count { it.key in userVehicles || it.key in bundledVehicles }
                add("${pack.vehicles.size} vehicle(s) with ${pack.vehicles.sumOf { it.ecus.size }} ECU definitions" + if (replaced > 0) ", $replaced replacing existing lists" else "")
            }
            if (pack.moduleNames.isNotEmpty()) add("${pack.moduleNames.size} module name(s)")
            if (pack.dtcs.isNotEmpty()) add("${pack.dtcs.size} DTC description(s)")
        }
        if (summary.isEmpty()) return ImportPreview(PackKind.DATA, pack.name, errors = listOf("The pack contains no PIDs, vehicles, module names or DTC descriptions."))
        val warnings = buildList {
            if (pack.id == DataPack.USER_PACK_ID) add("This is an export of someone's own additions; entries with the same id or make/model replace yours.")
            if (pack.pids.any { it.header == null }) add("PIDs without a header are sent to all ECUs (broadcast).")
        }
        return ImportPreview(PackKind.DATA, pack.name + " (v${pack.version})", summary, warnings, apply = { importData(pack) })
    }

    /** Merges [pack] into the user pack: same PID id, vehicle make/model, module request ID or DTC code is replaced. */
    fun importData(pack: DataPack): String {
        val user = dataStore.userPack()
        val incomingPids = pack.pids.map { it.id }.toSet()
        val incomingVehicles = pack.vehicles.map { it.key }.toSet()
        val moduleKey = { m: com.bod3.app.datapack.ModuleNameEntry -> m.requestId.uppercase() + "/" + m.responseId?.uppercase() + "/" + m.makes.map { it.lowercase() }.sorted() }
        val incomingModules = pack.moduleNames.map(moduleKey).toSet()
        val merged = user.copy(
            pids = user.pids.filterNot { it.id in incomingPids } + pack.pids,
            vehicles = user.vehicles.filterNot { it.key in incomingVehicles } + pack.vehicles,
            moduleNames = user.moduleNames.filterNot { moduleKey(it) in incomingModules } + pack.moduleNames,
            dtcs = user.dtcs + pack.dtcs,
        )
        dataStore.saveUserPack(merged)
        val parts = listOfNotNull(
            pack.pids.size.takeIf { it > 0 }?.let { "$it PID(s)" },
            pack.vehicles.size.takeIf { it > 0 }?.let { "$it vehicle ECU list(s)" },
            pack.moduleNames.size.takeIf { it > 0 }?.let { "$it module name(s)" },
            pack.dtcs.size.takeIf { it > 0 }?.let { "$it DTC description(s)" },
        )
        return "Imported ${parts.joinToString(", ")} from \"${pack.name}\""
    }

    private fun previewActuators(text: String): ImportPreview {
        val result = ActuatorRecipeCodec.decode(text)
        val pack = result.pack
        if (pack == null || !result.isValid) {
            return ImportPreview(PackKind.ACTUATORS, "Bidirectional test recipes", errors = listOf(header(result.errors)) + result.errors.ifEmpty { listOf("Unknown problem") })
        }
        val existing = actuators.entries().map { it.recipe.id }.toSet()
        val replaced = pack.recipes.count { it.id in existing }
        return ImportPreview(
            PackKind.ACTUATORS, pack.name,
            summary = listOf("${pack.recipes.size} test recipe(s)" + if (replaced > 0) ", $replaced replacing existing ones" else ""),
            warnings = listOfNotNull(
                pack.recipes.count { !it.verified }.takeIf { it > 0 }?.let { "$it recipe(s) are marked unverified." },
                "Running tests needs Expert mode.",
            ),
            apply = {
                val r = actuators.import(pack)
                "Imported ${r.added.size} new and ${r.replaced.size} replaced test recipe(s) from \"${pack.name}\""
            },
        )
    }

    private fun previewCoding(text: String): ImportPreview {
        val result = CodingRecipeCodec.decode(text)
        val pack = result.pack
        if (pack == null || !result.isValid) {
            return ImportPreview(PackKind.CODING, "Coding recipes", errors = listOf(header(result.errors)) + result.errors.ifEmpty { listOf("Unknown problem") })
        }
        val existing = coding.entries().map { it.recipe.id }.toSet()
        val replaced = pack.recipes.count { it.id in existing }
        return ImportPreview(
            PackKind.CODING, pack.name,
            summary = listOf("${pack.recipes.size} coding recipe(s)" + if (replaced > 0) ", $replaced replacing existing ones" else ""),
            warnings = listOfNotNull(
                pack.recipes.count { !it.verified }.takeIf { it > 0 }?.let { "$it recipe(s) are marked unverified." },
                "Writing needs Expert mode; every write is backed up and verified.",
            ),
            apply = {
                val r = coding.import(pack)
                "Imported ${r.added.size} new and ${r.replaced.size} replaced coding recipe(s) from \"${pack.name}\""
            },
        )
    }

    // ---- export -----------------------------------------------------------------------------------

    /** The user's data as a data pack, or null when there is nothing of that kind. */
    fun exportData(scope: DataExport): String? {
        val user = dataStore.userPack()
        val pack = when (scope) {
            DataExport.PIDS -> DataPack("my-pids", "My custom PIDs", pids = user.pids).takeIf { it.pids.isNotEmpty() }
            DataExport.VEHICLES -> DataPack("my-ecus", "My vehicles and ECU lists", vehicles = user.vehicles, moduleNames = user.moduleNames)
                .takeIf { it.vehicles.isNotEmpty() || it.moduleNames.isNotEmpty() }
            DataExport.ALL -> user.copy(id = "my-data", name = "My bod3 data")
                .takeIf { it.pids.isNotEmpty() || it.vehicles.isNotEmpty() || it.dtcs.isNotEmpty() || it.moduleNames.isNotEmpty() }
        }
        return pack?.let(DataPackCodec::encode)
    }

    /** One vehicle's ECU definitions (bundled or the user's) as a data pack. */
    fun exportVehicle(vehicle: VehicleDefinition): String {
        val id = "ecus-" + vehicle.key.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return DataPackCodec.encode(DataPack(id, vehicle.title, vehicles = listOf(vehicle)))
    }

    fun exportBundledData(packId: String): String? = bundledData.firstOrNull { it.id == packId }?.let(DataPackCodec::encode)

    fun exportActuators(): String? = actuators.userPack().recipes.takeIf { it.isNotEmpty() }?.let { actuators.export(it) }

    fun exportBundledActuators(packId: String): String? =
        actuators.bundledPacks.firstOrNull { it.id == packId }?.let { ActuatorRecipeCodec.encode(it) }

    fun exportCoding(): String? = coding.userPack().recipes.takeIf { it.isNotEmpty() }?.let { coding.export(it) }

    fun exportBundledCoding(packId: String): String? =
        coding.bundledPacks.firstOrNull { it.id == packId }?.let { CodingRecipeCodec.encode(it) }

    /** Bundled packs of every kind, for the sample list. */
    fun bundled(): List<BundledPack> =
        bundledData.map { BundledPack(PackKind.DATA, it.id, it.name, it.version, it.description, dataCounts(it)) } +
            actuators.bundledPacks.map { BundledPack(PackKind.ACTUATORS, it.id, it.name, it.version, it.description, "${it.recipes.size} test recipe(s)") } +
            coding.bundledPacks.map { BundledPack(PackKind.CODING, it.id, it.name, it.version, it.description, "${it.recipes.size} coding recipe(s)") }

    fun exportBundled(pack: BundledPack): String? = when (pack.kind) {
        PackKind.DATA -> exportBundledData(pack.id)
        PackKind.ACTUATORS -> exportBundledActuators(pack.id)
        PackKind.CODING -> exportBundledCoding(pack.id)
        PackKind.PROFILE -> null
    }

    /** Counts of the user's own items, for the screen. */
    fun userCounts(): UserCounts {
        val user = dataStore.userPack()
        return UserCounts(
            pids = user.pids.size, vehicles = user.vehicles.size, moduleNames = user.moduleNames.size, dtcs = user.dtcs.size,
            actuatorRecipes = actuators.userPack().recipes.size, codingRecipes = coding.userPack().recipes.size,
        )
    }

    private fun dataCounts(p: DataPack) = listOfNotNull(
        p.pids.size.takeIf { it > 0 }?.let { "$it PIDs" },
        p.vehicles.size.takeIf { it > 0 }?.let { "$it vehicles" },
        p.moduleNames.size.takeIf { it > 0 }?.let { "$it module names" },
        p.dtcs.size.takeIf { it > 0 }?.let { "$it DTC texts" },
    ).joinToString(" · ").ifEmpty { "empty" }
}

data class BundledPack(val kind: PackKind, val id: String, val name: String, val version: Int, val description: String?, val contents: String) {
    val fileName: String get() = "$id.json"
}

data class UserCounts(
    val pids: Int = 0,
    val vehicles: Int = 0,
    val moduleNames: Int = 0,
    val dtcs: Int = 0,
    val actuatorRecipes: Int = 0,
    val codingRecipes: Int = 0,
)
