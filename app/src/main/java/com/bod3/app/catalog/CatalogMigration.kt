package com.bod3.app.catalog

import com.bod3.app.actuator.ActuatorPack
import com.bod3.app.actuator.Applicability
import com.bod3.app.coding.CodingPack
import com.bod3.app.datapack.DataPack
import com.bod3.app.datapack.VehicleDefinition

/** Non-destructive conversion. Original preferences, profile observations, scans and logs stay intact. */
object CatalogMigration {
    const val MQB = "vw-group-mqb"
    data class Result(val pack: CatalogPack, val legacyLinks: Map<String, String>)

    fun delta(before: CatalogPack, after: CatalogPack, id: String, name: String): CatalogPack = CatalogPack(id, name,
        nodes = after.nodes.filter { n -> before.nodes.none { it == n } },
        platforms = after.platforms.filter { p -> before.platforms.none { it == p } },
        library = if (before.library != after.library) after.library else CatalogData())

    fun convert(base: CatalogPack, dataPacks: List<DataPack>, actuatorPacks: List<ActuatorPack> = emptyList(), codingPacks: List<CodingPack> = emptyList()): Result {
        val nodes = base.nodes.associateByTo(linkedMapOf()) { it.id }
        val platforms = base.platforms.associateByTo(linkedMapOf()) { it.id }
        var library = base.library
        val links = linkedMapOf<String, String>()
        fun ensure(level: CatalogLevel, parent: String?, name: String, from: Int? = null, to: Int? = null): CatalogNode {
            val normalized = CatalogIds.normalize(name)
            return nodes.values.firstOrNull { it.parentId == parent && it.level == level && CatalogIds.normalize(it.name) == normalized }
                ?: CatalogNode(CatalogIds.node(level, parent, name), parent, level, name, from, to).also { nodes[it.id] = it }
        }
        fun make(name: String) = ensure(CatalogLevel.MAKE, null, CatalogIds.makeName(name))
        fun add(id: String?, data: CatalogData) {
            if (id == null) library = library.mergeDeclarations(data).unverified()
            else nodes[id]?.let { nodes[id] = it.copy(data = it.data.mergeDeclarations(data).unverified()) }
        }
        fun platform(id: String, name: String, data: CatalogData, notes: String? = null) {
            val old = platforms[id]
            platforms[id] = CatalogPlatform(id, name, old?.data?.mergeDeclarations(data) ?: data, notes ?: old?.notes)
        }
        fun vehicle(v: VehicleDefinition, isMqb: Boolean) {
            // Keep these exact legacy definitions explicitly copyable, but never create a fallback vehicle.
            if (CatalogIds.normalize(v.make) == "generic") {
                platform("legacy-${CatalogIds.node(CatalogLevel.MODEL, null, v.key)}", v.title, CatalogData(ecus = v.ecus), v.notes)
                return
            }
            val root = make(v.make)
            val known = when (v.model) {
                "Tiguan (AD1)" -> "Tiguan" to "AD1"
                "Golf (5G, Mk7)", "Golf 7 (5G)" -> "Golf" to "5G / Mk7"
                "Passat (B8)", "Passat B8 (3G)" -> "Passat" to "B8"
                "A3 (8V)" -> "A3" to "8V"
                "Octavia (5E)" -> "Octavia" to "5E / Mk3"
                "Leon (5F)" -> "Leon" to "5F / Mk3"
                else -> null
            }
            val match = Regex("^(.*?)\\s*\\(([^)]+)\\)$").matchEntire(v.model)
            val modelName = known?.first ?: match?.groupValues?.get(1)?.trim() ?: v.model
            val genName = known?.second ?: match?.groupValues?.get(2)?.trim()
            val model = ensure(CatalogLevel.MODEL, root.id, modelName)
            val years = v.years?.let { Regex("(\\d{4})(?:\\D+(\\d{4}))?").find(it) }
            val from = years?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1886..2200 }
            val to = years?.groupValues?.getOrNull(2)?.toIntOrNull()?.takeIf { from != null && it in from..2200 }
            var target = if (genName != null || v.years != null) ensure(CatalogLevel.GENERATION, model.id, genName ?: "Imported ${v.years}", from, to) else model
            if (isMqb) {
                if (platforms[MQB] == null) platform(MQB, "Volkswagen Group MQB", CatalogData(ecus = v.ecus))
                val shared = platforms.getValue(MQB).data.ecus
                val current = v.ecus.associateBy { it.key }
                val declared = v.ecus.filter { e -> shared.none { it == e } }
                val excluded = shared.filterNot { it.key in current }.map { it.key }.toSet()
                target = target.copy(platformIds = (target.platformIds + MQB).distinct(),
                    data = target.data.mergeDeclarations(CatalogData(ecus = declared, exclude = CatalogExclusions(ecus = excluded))))
            } else {
                // A saved flat ECU list was a complete replacement, not an additive declaration.
                val inherited = CatalogTree(base.copy(nodes = nodes.values.toList(), platforms = platforms.values.toList())).resolve(target.id)?.data?.ecus.orEmpty()
                val excluded = inherited.filterNot { old -> v.ecus.any { it.key == old.key } }.map { it.key }.toSet()
                target = target.copy(data = target.data.copy(ecus = v.ecus, exclude = target.data.exclude.copy(ecus = excluded)))
            }
            nodes[target.id] = target.copy(notes = listOfNotNull(v.notes, v.years?.let { "Legacy year label: $it" }).takeIf { it.isNotEmpty() }?.joinToString("\n") ?: target.notes)
            links[v.key] = target.id
        }
        dataPacks.forEach { pack ->
            pack.vehicles.forEach { vehicle(it, pack.id == MQB) }
            val ordinary = CatalogData(pids = pack.pids, dtcs = pack.dtcs, moduleNames = pack.moduleNames.map { m -> m.copy(makes = m.makes.map(CatalogIds::makeName).distinct()) })
            if (pack.id == MQB) platform(MQB, "Volkswagen Group MQB", ordinary, pack.description) else add(null, ordinary)
            pack.dtcGroups.forEach { group ->
                // Share the original restricted group once; do not flatten 11,989 Toyota meanings globally.
                val id = "reference-${group.id}"
                platform(id, group.source ?: group.id, CatalogData(dtcGroups = listOf(group)))
                group.makes.map(CatalogIds::makeName).distinct().forEach { name ->
                    val root = make(name)
                    nodes[root.id] = root.copy(platformIds = (root.platformIds + id).distinct())
                }
            }
        }
        fun destinations(applicability: Applicability): List<String?> {
            if (applicability.makes.isEmpty()) return listOf(null)
            return applicability.makes.flatMap { name ->
                val root = make(name)
                if (applicability.models.isEmpty()) listOf(root.id) else applicability.models.map { model ->
                    nodes.values.firstOrNull { it.parentId == root.id && it.level == CatalogLevel.MODEL && CatalogIds.normalize(it.name) == CatalogIds.normalize(model) }?.id
                        ?: ensure(CatalogLevel.MODEL, root.id, model).id
                }
            }.distinct()
        }
        actuatorPacks.forEach { p -> p.recipes.forEach { recipe ->
            val d = CatalogData(actuators = listOf(recipe.copy(verified = false)))
            if (p.id.contains("mqb", true)) platform(MQB, "Volkswagen Group MQB", d) else destinations(recipe.applicability).forEach { add(it, d) }
        } }
        codingPacks.forEach { p -> p.recipes.forEach { recipe ->
            val d = CatalogData(coding = listOf(recipe.copy(verified = false)))
            if (p.id.contains("mqb", true)) platform(MQB, "Volkswagen Group MQB", d) else destinations(recipe.applicability).forEach { add(it, d) }
        } }
        return Result(base.copy(nodes = nodes.values.toList(), platforms = platforms.values.map { it.copy(data = it.data.unverified()) }, library = library.unverified()), links)
    }
}

/** Sparse storage overlay: a user's node replaces that node's LOCAL declarations, not its ancestors. */
fun mergeCatalog(base: CatalogPack, incoming: CatalogPack): CatalogPack = base.copy(
    nodes = (base.nodes.associateBy { it.id } + incoming.nodes.associateBy { it.id }).values.toList(),
    platforms = (base.platforms.associateBy { it.id } + incoming.platforms.associateBy { it.id }).values.toList(),
    library = base.library.mergeDeclarations(incoming.library),
)
