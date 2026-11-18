package com.bod3.app.catalog

import com.bod3.app.actuator.ActuatorPack
import com.bod3.app.coding.CodingPack
import com.bod3.app.datapack.DataPack
import com.bod3.app.obd.connection.ConnectionProfile
import com.bod3.app.obd.connection.KeyValueStore
import com.bod3.app.profile.JsonWriter
import com.bod3.app.profile.MiniJson
import com.bod3.app.profile.ProfileExtras
import java.util.UUID

/** One atomic document holds user declarations, selection and navigation metadata. No VIN selector. */
class CatalogRepository(
    val base: CatalogPack,
    private val store: KeyValueStore,
    private val legacyBaseLinks: Map<String, String> = emptyMap(),
) {
    data class State(
        val user: CatalogPack = CatalogPack("user-catalog", "My catalog"),
        val selectedId: String? = null, val favorites: Set<String> = emptySet(), val recents: List<String> = emptyList(),
        val migrated: Boolean = false, val legacyLinks: Map<String, String> = emptyMap(), val hidden: Set<String> = emptySet(),
        /** Recorded only by the explicit pack picker, never by catalog imports or VIN profiles. */
        val downloadedSelections: Set<String> = emptySet(),
    )
    private var cachedRaw: String? = null
    private var cached: State? = null

    @Synchronized
    fun state(): State {
        val raw = store.get(KEY)
        cached?.takeIf { raw == cachedRaw }?.let { return it }
        if (raw == null) return State().also { cachedRaw = null; cached = it }
        try {
            val root = MiniJson.parse(raw) as? Map<*, *> ?: error("Catalog state is not an object")
            require(root["formatVersion"] == 1.0) { "Unknown saved catalog version" }
            fun strings(key: String) = (root[key] as? List<*>)?.map { it as? String ?: error("$key contains non-text") }.orEmpty()
            val hidden = strings("hidden").toSet()
            val context = base.copy(nodes = base.nodes.filterNot { it.id in hidden })
            val user = CatalogCodec.decodeOrThrow(JsonWriter.write(root["catalog"]), context = context)
            val links = (root["legacyLinks"] as? Map<*, *>)?.entries?.associate { it.key.toString() to (it.value as? String ?: error("Invalid legacy link")) }.orEmpty()
            return State(user, root["selectedId"] as? String, strings("favorites").toSet(), strings("recents"), root["migrated"] == true, links, hidden,
                downloadedSelections = strings("downloadedSelections").toSet())
                .also { cachedRaw = raw; cached = it }
        } catch (e: Exception) {
            store.put(BROKEN, raw)
            throw IllegalArgumentException("Saved catalog could not be read; the original was preserved. ${e.message}", e)
        }
    }

    private fun pack(s: State): CatalogPack = mergeCatalog(base, s.user).let { it.copy(nodes = it.nodes.filterNot { n -> n.id in s.hidden }) }
    fun pack(): CatalogPack = pack(state())
    fun tree(): CatalogTree = CatalogTree(pack())
    fun selected(): ResolvedCatalog? = state().selectedId?.let { id -> tree().resolve(id) }
    fun legacyId(key: String?): String? = key?.let { state().legacyLinks[it] ?: legacyBaseLinks[it] }
    fun connection(adapter: ConnectionProfile): ConnectionProfile = selected()?.connection(adapter) ?: adapter

    /** Only explicit user actions call this; loading/importing a VIN profile never calls it. */
    @Synchronized
    fun select(id: String?, adapter: ConnectionProfile = ConnectionProfile.DEFAULT) {
        val old = state()
        if (id == null) return commit(old.copy(selectedId = null))
        val selected = tree().resolve(id) ?: throw IllegalArgumentException("Unknown catalog vehicle")
        require(selected.node.level != CatalogLevel.MAKE) { "Choose a model, generation or variant" }
        selected.connection(adapter)
        commit(old.copy(selectedId = id, recents = (listOf(id) + old.recents).distinct().take(20)))
    }

    /** Explicit pack-picker action. Only names/years are stored; downloaded definitions stay outside
     * the 16 MiB user document. One commit installs the exact path AND records manual selection. */
    @Synchronized
    internal fun selectDownloadedPath(path: CatalogPack, adapter: ConnectionProfile = ConnectionProfile.DEFAULT) {
        require(path.nodes.size == 4 && path.platforms.isEmpty() && path.library.count == 0 &&
            path.nodes.map { it.level } == CatalogLevel.entries && path.nodes.all { it.data == CatalogData() }) {
            "Downloaded vehicle path must contain exactly four name-only levels"
        }
        require(CatalogTree(path).problems().isEmpty()) { "Invalid downloaded hierarchy" }
        val old = state()
        val current = pack().nodes.associateBy { it.id }
        for (n in path.nodes) current[n.id]?.let { existing ->
            require(n.level == existing.level && n.parentId == existing.parentId) { "Source path collides with a different catalog level" }
        }
        val additions = path.nodes.filter { it.id !in current }
        val id = path.nodes.last().id
        val candidate = old.copy(user = old.user.copy(nodes = old.user.nodes + additions),
            hidden = old.hidden - path.nodes.map { it.id }.toSet(), selectedId = id,
            downloadedSelections = old.downloadedSelections + id,
            recents = (listOf(id) + old.recents).distinct().take(20))
        val selected = CatalogTree(pack(candidate)).resolve(id) ?: error("Downloaded vehicle path cannot be resolved")
        selected.connection(adapter)
        commit(candidate)
    }

    @Synchronized
    fun favorite(id: String) {
        require(tree().node(id) != null) { "Unknown catalog item" }
        val s = state(); commit(s.copy(favorites = if (id in s.favorites) s.favorites - id else s.favorites + id))
    }

    private fun imported(pack: CatalogPack): State {
        val old = state(); val current = this.pack()
        val existing = current.nodes.associateBy { it.id }; val oldPlatforms = current.platforms.associateBy { it.id }
        require(pack.id.isNotBlank() && pack.name.isNotBlank() && pack.version > 0) { "Pack id/name/version are required" }
        val localProblems = (pack.nodes.map { it.data } + pack.platforms.map { it.data } + pack.library).flatMap { it.problems() }
        require(localProblems.isEmpty()) { localProblems.joinToString("\n") }
        require(pack.nodes.map { it.id }.distinct().size == pack.nodes.size) { "Duplicate node IDs" }
        require(pack.platforms.map { it.id }.distinct().size == pack.platforms.size) { "Duplicate platform IDs" }
        val nodes = pack.nodes.map { incoming ->
            existing[incoming.id]?.let { previous ->
                require(previous.level == incoming.level && previous.parentId == incoming.parentId) { "${incoming.id}: importing cannot move a stable ID to a different level/parent" }
                incoming.copy(data = previous.data.mergeDeclarations(incoming.data), platformIds = (previous.platformIds + incoming.platformIds).distinct())
            } ?: incoming
        }
        val platforms = pack.platforms.map { incoming -> oldPlatforms[incoming.id]?.let { incoming.copy(data = it.data.mergeDeclarations(incoming.data)) } ?: incoming }
        val incoming = pack.copy(nodes = nodes, platforms = platforms).unverified()
        return old.copy(user = mergeCatalog(old.user, incoming), hidden = old.hidden - nodes.map { it.id }.toSet())
    }

    @Synchronized
    fun preview(pack: CatalogPack): List<String> = try { serialize(imported(pack)); emptyList() } catch (e: IllegalArgumentException) { e.message.orEmpty().lines() }

    /** Revalidated on confirmation. Exactly one persistence write; never a row-by-row/partial import. */
    @Synchronized
    fun import(pack: CatalogPack) = commit(imported(pack))

    /** Editors replace LOCAL data, while imports merge declarations. This permits removing an override. */
    @Synchronized
    fun saveNode(node: CatalogNode) {
        val old = state()
        val safe = node.copy(name = node.name.trim(), data = node.data.unverified())
        commit(old.copy(user = old.user.copy(nodes = (old.user.nodes.associateBy { it.id } + (safe.id to safe)).values.toList()), hidden = old.hidden - safe.id))
    }

    @Synchronized
    fun editData(id: String, transform: (CatalogData) -> CatalogData) {
        val node = tree().node(id) ?: throw IllegalArgumentException("Unknown catalog level")
        saveNode(node.copy(data = transform(node.data).unverified()))
    }
    @Synchronized
    fun editSelected(transform: (CatalogData) -> CatalogData) {
        val id = selected()?.node?.id ?: throw IllegalArgumentException("Select a catalog vehicle first")
        editData(id, transform)
    }

    /** Unscoped legacy PIDs/notes remain available; its ECUs/settings never become a vehicle fallback. */
    @Synchronized
    fun editLibrary(transform: (CatalogData) -> CatalogData) {
        val old = state(); val current = pack(old).library; val updated = transform(current).unverified()
        val exclusions = updated.exclude.copy(
            pids = updated.exclude.pids + (base.library.pids.map { it.id }.toSet() - updated.pids.map { it.id }.toSet()),
            actuators = updated.exclude.actuators + (base.library.actuators.map { it.id }.toSet() - updated.actuators.map { it.id }.toSet()),
            coding = updated.exclude.coding + (base.library.coding.map { it.id }.toSet() - updated.coding.map { it.id }.toSet()),
            dtcs = updated.exclude.dtcs + (base.library.dtcs.keys - updated.dtcs.keys),
        )
        commit(old.copy(user = old.user.copy(library = updated.copy(exclude = exclusions))))
    }

    @Synchronized
    fun editUserLibrary(transform: (CatalogData) -> CatalogData) {
        val old = state()
        commit(old.copy(user = old.user.copy(library = transform(old.user.library).unverified())))
    }

    /** A whole legacy pack is converted and stored with its explicit link mappings in one transaction. */
    @Synchronized
    fun importLegacy(data: DataPack): CatalogMigration.Result {
        require(data.problems().isEmpty()) { data.problems().joinToString("\n") }
        val current = pack()
        val converted = CatalogMigration.convert(current, listOf(data))
        val delta = CatalogMigration.delta(current, converted.pack, data.id, data.name)
        val candidate = imported(delta)
        commit(candidate.copy(legacyLinks = candidate.legacyLinks + converted.legacyLinks))
        return converted
    }

    /** Original bundled declarations are immutable. Removal hides a subtree; original profiles survive. */
    @Synchronized
    fun deleteNode(id: String) {
        val old = state(); val tree = tree()
        require(tree.node(id) != null) { "Unknown catalog item" }
        val ids = tree.pack.nodes.filter { n -> tree.path(n.id).any { it.id == id } }.map { it.id }.toSet()
        commit(old.copy(user = old.user.copy(nodes = old.user.nodes.filterNot { it.id in ids }), hidden = old.hidden + ids,
            selectedId = old.selectedId?.takeUnless { it in ids }, favorites = old.favorites - ids, recents = old.recents.filterNot { it in ids }))
    }

    @Synchronized
    fun duplicate(id: String, newName: String): String {
        val tree = tree(); val source = tree.node(id) ?: throw IllegalArgumentException("Unknown catalog item")
        require(newName.isNotBlank()) { "Name is required" }
        val branch = tree.pack.nodes.filter { n -> tree.path(n.id).any { it.id == id } }
        val ids = branch.associate { it.id to "custom-${UUID.randomUUID()}" }
        val copies = branch.map { n -> n.copy(id = ids.getValue(n.id), parentId = ids[n.parentId] ?: n.parentId,
            name = if (n.id == id) newName.trim() else n.name, data = n.data.unverified()) }
        import(CatalogPack("duplicate", "Duplicate ${source.name}", nodes = copies))
        return ids.getValue(id)
    }

    fun export(id: String? = null): CatalogPack = tree().export(id)

    /** Translate old explicit selections only. No VIN description participates in this migration. */
    @Synchronized
    fun migrateLegacy(data: DataPack, selectedLegacyKey: String?, actuators: ActuatorPack, coding: CodingPack) {
        val old = state(); if (old.migrated) return
        val result = CatalogMigration.convert(pack(), listOf(data), listOf(actuators), listOf(coding))
        val baseline = base.nodes.associateBy { it.id }; val basePlatforms = base.platforms.associateBy { it.id }
        val user = old.user.copy(nodes = result.pack.nodes.filter { baseline[it.id] != it },
            platforms = result.pack.platforms.filter { basePlatforms[it.id] != it },
            library = if (result.pack.library != base.library) result.pack.library else CatalogData())
        val selected = (result.legacyLinks[selectedLegacyKey] ?: legacyId(selectedLegacyKey))?.takeIf { id -> result.pack.nodes.any { it.id == id } }
        commit(old.copy(user = user, selectedId = selected, migrated = true, legacyLinks = result.legacyLinks,
            recents = selected?.let { listOf(it) }.orEmpty()))
    }

    /** Pure link conversion; callers may persist it lazily. It never activates the linked catalog. */
    fun migrateProfile(extras: ProfileExtras): ProfileExtras =
        if (extras.catalogNodeId != null) extras else extras.copy(catalogNodeId = legacyId(extras.vehicleKey))

    private fun validate(s: State) {
        val problems = CatalogTree(pack(s)).problems()
        require(problems.isEmpty()) { problems.joinToString("\n") }
        require(s.selectedId == null || pack(s).nodes.any { it.id == s.selectedId && it.level != CatalogLevel.MAKE }) { "Selected catalog node no longer exists" }
    }

    private fun serialize(state: State): String {
        validate(state)
        val raw = JsonWriter.write(linkedMapOf("formatVersion" to 1, "catalog" to CatalogCodec.packMap(state.user),
            "selectedId" to state.selectedId, "favorites" to state.favorites.sorted(), "recents" to state.recents,
            "migrated" to state.migrated, "legacyLinks" to state.legacyLinks, "hidden" to state.hidden.sorted(),
            "downloadedSelections" to state.downloadedSelections.sorted()))
        require(raw.toByteArray(Charsets.UTF_8).size <= CatalogCodec.MAX_BYTES) { "Catalog storage exceeds 16 MiB; export/archive unused data first" }
        return raw
    }

    private fun commit(state: State) {
        val raw = serialize(state)
        store.put(KEY, raw) // A failed write cannot change the cache or another preference.
        cachedRaw = raw; cached = state
    }

    companion object {
        const val KEY = "vehicle.catalog.v1"
        const val BROKEN = "vehicle.catalog.unreadable"
    }
}
