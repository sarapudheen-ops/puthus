package com.bod3.app.datapack

import com.bod3.app.obd.connection.KeyValueStore
import com.bod3.app.profile.JsonWriter
import com.bod3.app.profile.MiniJson

/** How a live value is shown. */
enum class LiveDisplay(val label: String) { VALUE("Value"), GAUGE("Gauge"), GRAPH("Graph") }

/** The user's live data selection: value keys (`010C`, `custom:boost`) in display order, with display modes. */
data class LiveSelection(val keys: List<String>, val display: Map<String, LiveDisplay> = emptyMap()) {
    fun displayOf(key: String): LiveDisplay = display[key] ?: LiveDisplay.VALUE
}

/**
 * Persists the user's own data pack (custom PIDs, edited ECU lists, DTC descriptions), the selected
 * vehicle and the live data selection. Every change is validated before it is stored.
 */
class DataPackStore(private val store: KeyValueStore) {

    fun userPack(): DataPack {
        val json = store.get(KEY_USER_PACK) ?: return DataPack.emptyUserPack()
        return DataPackCodec.decode(json).pack ?: DataPack.emptyUserPack().also {
            // Keep the unreadable data so nothing is silently lost.
            store.put(KEY_USER_PACK_BROKEN, json)
        }
    }

    fun saveUserPack(pack: DataPack) {
        require(pack.id == DataPack.USER_PACK_ID) { "Only the user pack can be saved here" }
        val problems = pack.problems()
        require(problems.isEmpty()) { problems.joinToString("\n") }
        store.put(KEY_USER_PACK, DataPackCodec.encode(pack))
    }

    private fun update(change: (DataPack) -> DataPack) = saveUserPack(change(userPack()))

    fun savePid(pid: CustomPid) {
        val problems = pid.problems()
        require(problems.isEmpty()) { problems.joinToString("\n") }
        update { pack -> pack.copy(pids = pack.pids.filterNot { it.id == pid.id } + pid) }
    }

    fun deletePid(id: String) = update { pack -> pack.copy(pids = pack.pids.filterNot { it.id == id }) }

    /** Sets (or with a blank [text], removes) the user's description for [code]. */
    fun setDtcDescription(code: String, text: String?) {
        val key = code.trim().uppercase()
        require(DataPack.DTC_KEY.matches(key)) { "\"$code\" is not a DTC like P0420 or P0420-13" }
        update { pack ->
            pack.copy(dtcs = if (text.isNullOrBlank()) pack.dtcs - key else pack.dtcs + (key to text.trim()))
        }
    }

    /** Saves the user's ECU list for a vehicle; it replaces a bundled list with the same make/model. */
    fun saveVehicle(vehicle: VehicleDefinition) {
        val problems = vehicle.problems()
        require(problems.isEmpty()) { problems.joinToString("\n") }
        update { pack -> pack.copy(vehicles = pack.vehicles.filterNot { it.key == vehicle.key } + vehicle) }
    }

    fun deleteVehicle(key: String) = update { pack -> pack.copy(vehicles = pack.vehicles.filterNot { it.key == key }) }

    var selectedVehicleKey: String?
        get() = store.get(KEY_SELECTED_VEHICLE)
        set(value) = store.put(KEY_SELECTED_VEHICLE, value)

    fun liveSelection(): LiveSelection? {
        val map = store.get(KEY_LIVE_SELECTION)?.let { runCatching { MiniJson.parse(it) as? Map<*, *> }.getOrNull() } ?: return null
        val keys = (map["keys"] as? List<*>)?.filterIsInstance<String>() ?: return null
        val display = (map["display"] as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
            val mode = LiveDisplay.entries.firstOrNull { it.name == v } ?: return@mapNotNull null
            (k as? String)?.let { it to mode }
        }?.toMap().orEmpty()
        return LiveSelection(keys, display)
    }

    fun saveLiveSelection(selection: LiveSelection) {
        store.put(
            KEY_LIVE_SELECTION,
            JsonWriter.write(mapOf("keys" to selection.keys, "display" to selection.display.mapValues { it.value.name })),
        )
    }

    companion object {
        const val KEY_USER_PACK = "datapack.user"
        const val KEY_USER_PACK_BROKEN = "datapack.user.unreadable"
        const val KEY_SELECTED_VEHICLE = "vehicle.selected"
        const val KEY_LIVE_SELECTION = "live.selection"
    }
}

/** Where a vehicle definition comes from. */
enum class VehicleSource { BUNDLED, USER, USER_MODIFIED }

data class VehicleEntry(val vehicle: VehicleDefinition, val source: VehicleSource, val packName: String)

/** Bundled packs plus the user pack, merged. The user's entries win over bundled ones with the same key. */
class DataRepository(private val bundled: List<DataPack>, private val store: DataPackStore) {

    val bundledPacks: List<DataPack> get() = bundled

    fun userPack(): DataPack = store.userPack()

    fun vehicles(): List<VehicleEntry> {
        val user = store.userPack().vehicles.associateBy { it.key }
        val fromBundles = bundled.flatMap { pack -> pack.vehicles.map { it to pack.name } }
        val bundledKeys = fromBundles.map { it.first.key }.toSet()
        val merged = fromBundles.distinctBy { it.first.key }.map { (vehicle, packName) ->
            user[vehicle.key]?.let { VehicleEntry(it, VehicleSource.USER_MODIFIED, "My additions") }
                ?: VehicleEntry(vehicle, VehicleSource.BUNDLED, packName)
        } + user.values.filter { it.key !in bundledKeys }.map { VehicleEntry(it, VehicleSource.USER, "My additions") }
        return merged.sortedWith(compareBy({ it.vehicle.make.lowercase() }, { it.vehicle.model.lowercase() }))
    }

    fun vehicle(key: String?): VehicleEntry? = key?.let { k -> vehicles().firstOrNull { it.vehicle.key == k } }

    fun bundledVehicle(key: String): VehicleDefinition? = bundled.flatMap { it.vehicles }.firstOrNull { it.key == key }

    /** Custom PIDs from bundled packs and the user pack; user PIDs replace bundled ones with the same id. */
    fun customPids(): List<CustomPid> {
        val user = store.userPack().pids
        val userIds = user.map { it.id }.toSet()
        return bundled.flatMap { it.pids }.filterNot { it.id in userIds }.distinctBy { it.id } + user
    }

    /** Generic module names: the user pack's first, then bundled packs', each with its pack name. */
    fun moduleNames(): List<Pair<ModuleNameEntry, String>> =
        store.userPack().moduleNames.map { it to "My additions" } + bundled.flatMap { pack -> pack.moduleNames.map { it to pack.name } }

    fun isUserPid(id: String): Boolean = store.userPack().pids.any { it.id == id }

    /** Manufacturer references never apply to an unknown or different make. User edits still win. */
    fun dtcDescriptions(make: String? = null): DtcDescriptions {
        val user = store.userPack()
        val userGroupIds = user.dtcGroups.map { it.id }.toSet()
        val scopedUser = user.dtcGroups.filter { it.appliesTo(make) }.map { "Imported: ${it.source ?: it.id}" to it.dtcs }
        val scopedBundled = bundled.flatMap { pack ->
            pack.dtcGroups.filter { it.id !in userGroupIds && it.appliesTo(make) }.map { (it.source ?: pack.name) to it.dtcs }
        }
        return DtcDescriptions(
            user = user.dtcs,
            packs = scopedUser + scopedBundled + bundled.filter { it.dtcs.isNotEmpty() }.map { it.name to it.dtcs },
        )
    }
}
