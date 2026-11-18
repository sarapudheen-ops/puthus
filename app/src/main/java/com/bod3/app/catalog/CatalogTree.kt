package com.bod3.app.catalog

/** Pure tree validation, inheritance, subtree export and search. No storage, VIN lookup or bus IO. */
class CatalogTree(val pack: CatalogPack) {
    private val byId = pack.nodes.associateBy { it.id }
    private val platforms = pack.platforms.associateBy { it.id }
    fun node(id: String?): CatalogNode? = byId[id]
    fun children(parent: String?): List<CatalogNode> = pack.nodes.filter { it.parentId == parent }
        .sortedWith(compareBy({ CatalogIds.normalize(it.name) }, { it.yearFrom ?: 0 }, { it.engine.orEmpty() }, { it.id }))

    fun path(id: String?): List<CatalogNode> {
        var current = node(id) ?: return emptyList()
        val result = mutableListOf<CatalogNode>()
        val seen = mutableSetOf<String>()
        // A valid path has at most four levels. Invalid imports must not turn validation into an
        // unbounded/quadratic traversal of a hostile parent chain before its errors can be shown.
        while (result.size < CatalogLevel.entries.size && seen.add(current.id)) {
            result += current
            current = node(current.parentId) ?: break
        }
        return result.asReversed()
    }

    fun resolve(id: String?): ResolvedCatalog? {
        val path = path(id)
        if (path.isEmpty() || path.first().level != CatalogLevel.MAKE) return null
        var data = CatalogData()
        val origins = linkedMapOf<String, String>()
        fun apply(layer: CatalogData, label: String) {
            data = data.overlay(layer)
            val removed = mapOf("ecus" to layer.exclude.ecus, "pids" to layer.exclude.pids, "dtcs" to layer.exclude.dtcs,
                "actuators" to layer.exclude.actuators, "coding" to layer.exclude.coding, "security" to layer.exclude.security,
                "dtcGroups" to layer.exclude.dtcGroups, "moduleNames" to layer.exclude.moduleNames)
            removed.forEach { (kind, keys) -> keys.forEach { origins.remove("$kind:$it") } }
            layer.dtcGroups.forEach { origins["dtcGroups:${it.id}"] = label }
            layer.moduleNames.forEach { origins["moduleNames:${CatalogData.moduleKey(it)}"] = label }
            layer.connection.values.forEach { (key, _) -> origins["connection:$key"] = label }
            layer.ecus.forEach { origins["ecus:${it.key}"] = label }
            layer.pids.forEach { origins["pids:${it.id}"] = label }
            layer.dtcs.forEach { (key, _) -> origins["dtcs:$key"] = label }
            layer.actuators.forEach { origins["actuators:${it.id}"] = label }
            layer.coding.forEach { origins["coding:${it.id}"] = label }
            layer.security.forEach { origins["security:${it.id}"] = label }
        }
        path.forEach { n ->
            n.platformIds.mapNotNull(platforms::get).forEach { apply(it.data, "Platform: ${it.name}") }
            apply(n.data, path.takeWhile { it.id != n.id }.plus(n).joinToString(" / ") { it.name })
        }
        return ResolvedCatalog(path, data, origins)
    }

    fun problems(): List<String> = buildList {
        if (pack.id.isBlank() || pack.name.isBlank()) add("id/name: must not be empty")
        if (pack.version < 1) add("version: must be at least 1")
        fun duplicates(keys: List<String>, field: String) { keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { add("$field: duplicate id $it") } }
        duplicates(pack.nodes.map { it.id }, "nodes"); duplicates(pack.platforms.map { it.id }, "platforms")
        pack.nodes.forEachIndexed { i, n ->
            val p = "nodes[$i] (${n.name})"
            if (n.id.isBlank() || n.name.isBlank()) add("$p.id/name: must not be empty")
            val parent = node(n.parentId)
            if (n.level == CatalogLevel.MAKE && n.parentId != null) add("$p.parentId: a make must be a root")
            if (n.level != CatalogLevel.MAKE && (parent == null || parent.level.ordinal + 1 != n.level.ordinal)) add("$p.parentId: ${n.level.label} must have a ${CatalogLevel.entries[n.level.ordinal - 1].label} parent")
            if (n.yearFrom != null && n.yearFrom !in 1886..2200) add("$p.yearFrom: must be 1886-2200")
            if (n.yearTo != null && n.yearTo !in 1886..2200) add("$p.yearTo: must be 1886-2200")
            if (n.yearTo != null && (n.yearFrom == null || n.yearTo < n.yearFrom)) add("$p.yearTo: needs yearFrom and must not be earlier")
            if (n.level != CatalogLevel.GENERATION && (n.yearFrom != null || n.yearTo != null)) add("$p: year ranges belong to generations")
            if (n.level != CatalogLevel.VARIANT && listOf(n.engine, n.fuel, n.transmission).any { it != null }) add("$p: engine/fuel/transmission belong to variants")
            if (n.platformIds.distinct().size != n.platformIds.size) add("$p.platformIds: duplicate platform")
            n.platformIds.filterNot { it in platforms }.forEach { add("$p.platformIds: unknown platform $it") }
            n.data.problems().forEach { add("$p.data.$it") }
            // Only resolve connection fields here, not thousands of passive DTC texts for every child.
            val values = linkedMapOf<String, Any?>()
            path(n.id).forEach { ancestor ->
                ancestor.platformIds.mapNotNull(platforms::get).forEach { values.putAll(it.data.connection.values) }
                values.putAll(ancestor.data.connection.values)
            }
            runCatching { CatalogConnection(values).apply(com.bod3.app.obd.connection.ConnectionProfile.DEFAULT) }
                .exceptionOrNull()?.let { add("$p.resolvedConnection: ${it.message}") }
        }
        pack.nodes.groupBy { Triple(it.parentId, it.level, CatalogIds.normalize(CatalogIds.variantIdentity(it.name, it.engine, it.fuel, it.transmission))) }
            .filterValues { it.size > 1 }.values.forEach { add("nodes: duplicate sibling ${it.first().name}") }
        pack.platforms.forEachIndexed { i, platform ->
            if (platform.id.isBlank() || platform.name.isBlank()) add("platforms[$i]: id and name are required")
            platform.data.problems().forEach { add("platforms[$i].data.$it") }
        }
        pack.library.problems().forEach { add("library.$it") }
    }

    fun search(query: String): List<CatalogNode> {
        val tokens = CatalogIds.normalize(query).split(Regex("\\s+")).filter { it.isNotBlank() }.map { if (it == "vw") "volkswagen" else it }
        if (tokens.isEmpty()) return children(null)
        return pack.nodes.filter { n ->
            val path = path(n.id)
            val haystack = CatalogIds.normalize(path.joinToString(" ") { "${it.label} ${it.level.label}" })
            val generation = path.firstOrNull { it.level == CatalogLevel.GENERATION }
            tokens.all { token -> haystack.contains(token) || token.toIntOrNull()?.takeIf { token.length == 4 }?.let { year ->
                generation?.yearFrom?.let { year in it..(generation.yearTo ?: 2200) }
            } == true }
        }.sortedWith(compareBy({ path(it.id).joinToString("/") { p -> CatalogIds.normalize(p.name) } }, { it.level.ordinal }))
    }

    /** Include ancestor declarations and referenced platforms so inheritance survives an isolated export. */
    fun export(id: String?): CatalogPack {
        if (id == null) return pack
        require(node(id) != null) { "Unknown catalog node $id" }
        val ids = path(id).map { it.id }.toSet() + pack.nodes.filter { n -> path(n.id).any { it.id == id } }.map { it.id }
        val nodes = pack.nodes.filter { it.id in ids }
        val referenced = nodes.flatMap { it.platformIds }.toSet()
        return CatalogPack("export-$id", path(id).joinToString(" / ") { it.name }, nodes = nodes,
            platforms = pack.platforms.filter { it.id in referenced }, description = "Includes ancestor declarations needed for inheritance. No sibling branches are included.")
    }
}
