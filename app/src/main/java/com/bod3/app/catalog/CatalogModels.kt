package com.bod3.app.catalog

import com.bod3.app.actuator.ActuatorRecipe
import com.bod3.app.coding.CodingRecipe
import com.bod3.app.datapack.CustomPid
import com.bod3.app.datapack.DataPack
import com.bod3.app.datapack.DtcGroup
import com.bod3.app.datapack.EcuDefinition
import com.bod3.app.datapack.ModuleNameEntry
import com.bod3.app.obd.connection.ConnectionProfile
import com.bod3.app.obd.uds.SecurityAccessSpec
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** Chosen by the driver, never inferred from a VIN. VIN profiles are observations, not configuration. */
enum class CatalogLevel(val label: String) {
    MAKE("Make"), MODEL("Model"), GENERATION("Generation"), VARIANT("Variant");
    val child: CatalogLevel? get() = entries.getOrNull(ordinal + 1)
}

/** A reusable definition, not a permission to perform security access. */
data class CatalogSecurity(val id: String, val name: String, val spec: SecurityAccessSpec, val verified: Boolean = false)

/** Keys suppressed here. A lower-level declaration may explicitly restore a key. */
data class CatalogExclusions(
    val ecus: Set<String> = emptySet(), val pids: Set<String> = emptySet(), val dtcs: Set<String> = emptySet(),
    val actuators: Set<String> = emptySet(), val coding: Set<String> = emptySet(), val security: Set<String> = emptySet(),
    val dtcGroups: Set<String> = emptySet(), val moduleNames: Set<String> = emptySet(),
)

/**
 * What a selected level actually contributes to a connection: ECUs, PIDs and connection overrides.
 * Passive DTC reference text is deliberately not coverage — it changes no ECU list and no protocol.
 */
data class CatalogCoverage(val ecus: Int, val pids: Int, val connectionOverrides: Int) {
    val executable: Boolean get() = ecus > 0 || pids > 0 || connectionOverrides > 0

    /** Meaningful counts, e.g. "3 ECUs · 12 PIDs · 2 connection overrides". */
    val counts: String get() = listOf(
        item(ecus, "ECU", "ECUs"), item(pids, "PID", "PIDs"),
        item(connectionOverrides, "connection override", "connection overrides"),
    ).joinToString(" · ")

    /** Counts when the level supplies executable data, otherwise the explicit no-coverage statement. */
    val label: String get() = if (executable) counts else NO_ENHANCED_DATA

    companion object {
        /** Truthful description of a names/years-only level; it promises no ECU or protocol override. */
        const val NO_ENHANCED_DATA = "Name/year only — no enhanced ECU or connection definitions; generic OBD only."
        private fun item(value: Int, singular: String, plural: String) = "$value ${if (value == 1) singular else plural}"
    }
}

/** Local declarations only. Saving a child never copies its inherited definitions. */
data class CatalogData(
    val ecus: List<EcuDefinition> = emptyList(),
    val pids: List<CustomPid> = emptyList(),
    val dtcs: Map<String, String> = emptyMap(),
    val moduleNames: List<ModuleNameEntry> = emptyList(),
    val actuators: List<ActuatorRecipe> = emptyList(),
    val coding: List<CodingRecipe> = emptyList(),
    val security: List<CatalogSecurity> = emptyList(),
    val connection: CatalogConnection = CatalogConnection(),
    val exclude: CatalogExclusions = CatalogExclusions(),
    /** Keep manufacturer restrictions and source attribution, including the existing Toyota reference. */
    val dtcGroups: List<DtcGroup> = emptyList(),
) {
    /** File flags, duplication and editor saves cannot establish physical verification. */
    fun unverified() = copy(actuators = actuators.map { it.copy(verified = false) }, coding = coding.map { it.copy(verified = false) }, security = security.map { it.copy(verified = false) })

    /** Effective-data overlay; suppressions are consumed, not passed on as local declarations. */
    fun overlay(child: CatalogData): CatalogData {
        fun <T> merge(parent: List<T>, incoming: List<T>, removed: Set<String>, key: (T) -> String): List<T> {
            val map = linkedMapOf<String, T>()
            parent.filterNot { key(it) in removed }.forEach { map[key(it)] = it }
            incoming.forEach { map[key(it)] = it }
            return map.values.toList()
        }
        return CatalogData(
            ecus = merge(ecus, child.ecus, child.exclude.ecus) { it.key },
            pids = merge(pids, child.pids, child.exclude.pids) { it.id },
            dtcs = (dtcs - child.exclude.dtcs) + child.dtcs,
            moduleNames = merge(moduleNames, child.moduleNames, child.exclude.moduleNames, ::moduleKey),
            actuators = merge(actuators, child.actuators, child.exclude.actuators) { it.id },
            coding = merge(coding, child.coding, child.exclude.coding) { it.id },
            security = merge(security, child.security, child.exclude.security) { it.id },
            connection = CatalogConnection(connection.values + child.connection.values),
            dtcGroups = merge(dtcGroups, child.dtcGroups, child.exclude.dtcGroups) { it.id },
        )
    }

    /** Additive import at the SAME level: retain unrelated declarations and inheritance suppressions. */
    fun mergeDeclarations(incoming: CatalogData): CatalogData = overlay(incoming).copy(exclude = CatalogExclusions(
        ecus = exclude.ecus + incoming.exclude.ecus,
        pids = exclude.pids + incoming.exclude.pids,
        dtcs = exclude.dtcs + incoming.exclude.dtcs,
        actuators = exclude.actuators + incoming.exclude.actuators,
        coding = exclude.coding + incoming.exclude.coding,
        security = exclude.security + incoming.exclude.security,
        dtcGroups = exclude.dtcGroups + incoming.exclude.dtcGroups,
        moduleNames = exclude.moduleNames + incoming.exclude.moduleNames,
    ))

    fun problems(): List<String> = buildList {
        addAll(DataPack("catalog-data", "Catalog data", pids = pids, dtcs = dtcs, moduleNames = moduleNames, dtcGroups = dtcGroups).problems())
        ecus.forEachIndexed { i, e -> e.problems().forEach { add("ecus[$i].$it") } }
        dtcs.forEach { (k, v) -> if (v.isBlank()) add("dtcs.$k: must not be blank") }
        actuators.forEachIndexed { i, r -> r.problems().forEach { add("actuators[$i].$it") } }
        coding.forEachIndexed { i, r -> r.problems().forEach { add("coding[$i].$it") } }
        security.forEachIndexed { i, r ->
            if (r.id.isBlank() || r.name.isBlank()) add("security[$i]: id and name are required")
            r.spec.problems().forEach { add("security[$i].$it") }
        }
        fun duplicates(keys: List<String>, field: String) { keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { add("$field: duplicate key $it") } }
        duplicates(ecus.map { it.key }, "ecus"); duplicates(actuators.map { it.id }, "actuators")
        duplicates(coding.map { it.id }, "coding"); duplicates(security.map { it.id }, "security")
        duplicates(moduleNames.map(::moduleKey), "moduleNames")
        if (exclude.dtcs.any { !DataPack.DTC_KEY.matches(it) }) add("exclude.dtcs: invalid DTC key")
        addAll(connection.problems().map { "connection.$it" })
    }

    val dtcCount: Int get() = dtcs.size + dtcGroups.sumOf { it.dtcs.size }
    val count: Int get() = ecus.size + pids.size + dtcCount + moduleNames.size + actuators.size + coding.size + security.size + connection.values.size
    val summary: String get() = "${ecus.size} ECUs · ${pids.size} PIDs · $dtcCount DTC texts · ${actuators.size} tests · ${coding.size} coding recipes"

    /** Executable enhanced coverage of this data alone. DTC text, tests and coding are not part of it. */
    val coverage: CatalogCoverage get() = CatalogCoverage(ecus.size, pids.size, connection.values.size)

    companion object {
        fun moduleKey(m: ModuleNameEntry): String = "${m.requestId.uppercase(Locale.ROOT)}/${m.responseId?.uppercase(Locale.ROOT).orEmpty()}/${m.makes.map(CatalogIds::normalize).sorted().joinToString(",")}"
    }
}

data class CatalogNode(
    val id: String,
    val parentId: String?,
    val level: CatalogLevel,
    val name: String,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val engine: String? = null,
    val fuel: String? = null,
    val transmission: String? = null,
    /** Reusable platform data is applied BEFORE this node's local declarations. */
    val platformIds: List<String> = emptyList(),
    val data: CatalogData = CatalogData(),
    val notes: String? = null,
) {
    val years: String? get() = yearFrom?.let { "$it–${yearTo ?: "present"}" }
    val label: String get() = listOfNotNull(name, years, engine, fuel, transmission).filter { it.isNotBlank() }.distinct().joinToString(" · ")
}

data class CatalogPlatform(val id: String, val name: String, val data: CatalogData, val notes: String? = null)

data class CatalogPack(
    val id: String, val name: String, val version: Int = 1,
    val nodes: List<CatalogNode> = emptyList(), val platforms: List<CatalogPlatform> = emptyList(),
    /** Unscoped legacy data/templates, not a vehicle and never an ECU/connection fallback. */
    val library: CatalogData = CatalogData(), val description: String? = null,
) {
    fun unverified() = copy(nodes = nodes.map { it.copy(data = it.data.unverified()) }, platforms = platforms.map { it.copy(data = it.data.unverified()) }, library = library.unverified())
}

data class ResolvedCatalog(val path: List<CatalogNode>, val data: CatalogData, val origins: Map<String, String>) {
    val node: CatalogNode get() = path.last()
    val title: String get() = path.joinToString(" / ") { it.label }
    val make: String get() = path.first().name
    val model: String? get() = path.firstOrNull { it.level == CatalogLevel.MODEL }?.name

    /** Executable coverage of the resolved path, never of names, years or DTC text. */
    val coverage: CatalogCoverage get() = data.coverage

    /** Confirmation that states counts, or admits that only names/years are selected. */
    val selectionMessage: String
        get() = if (coverage.executable) "Selected $title. ${coverage.counts} will be used on the next connection."
            else "Selected $title. ${CatalogCoverage.NO_ENHANCED_DATA}"

    fun connection(base: ConnectionProfile): ConnectionProfile = data.connection.apply(base).copy(id = "catalog-${node.id}", name = title)
}

/** Stable name-derived IDs for seed/CSV/migration; editor-created IDs survive renaming. */
object CatalogIds {
    fun normalize(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    fun makeName(value: String): String = when (normalize(value)) {
        "vw", "volkswagen" -> "Volkswagen"
        "skoda" -> "Škoda"
        "citroen" -> "Citroën"
        "mercedes", "mercedes benz", "mercedes-benz" -> "Mercedes-Benz"
        "toyota thailand" -> "Toyota"
        else -> value.trim()
    }
    fun node(level: CatalogLevel, parent: String?, identity: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest("${parent.orEmpty()}|${normalize(identity)}".toByteArray(Charsets.UTF_8))
        return level.name.lowercase(Locale.ROOT) + "-" + hash.take(10).joinToString("") { "%02x".format(it) }
    }
    fun variantIdentity(name: String, engine: String?, fuel: String?, transmission: String?) = listOf(name, engine.orEmpty(), fuel.orEmpty(), transmission.orEmpty()).joinToString("|")
}
