package com.bod3.app.profile

import com.bod3.app.datapack.DataPackCodec
import com.bod3.app.datapack.EcuDefinition
import com.bod3.app.obd.connection.KeyValueStore
import com.bod3.app.obd.scan.Bus
import com.bod3.app.obd.scan.ModuleAddress

/**
 * What the user added to one vehicle profile (VIN) by hand: ECUs the scan does not find (or that
 * should always be probed), their own names for modules, and a note. Kept next to the Room profile
 * so the scan tables stay a pure record of what the vehicle reported.
 */
data class ProfileExtras(
    val manualEcus: List<EcuDefinition> = emptyList(),
    /** Request ID (upper case) → the user's name for that module. */
    val moduleNames: Map<String, String> = emptyMap(),
    /** Data-pack vehicle (make|model key) chosen for this VIN, or null to use the global selection. */
    val vehicleKey: String? = null,
    val notes: String? = null,
) {
    val isEmpty: Boolean get() = manualEcus.isEmpty() && moduleNames.isEmpty() && vehicleKey == null && notes.isNullOrBlank()

    fun problems(): List<String> = buildList {
        manualEcus.forEachIndexed { i, ecu -> ecu.problems().forEach { add("manualEcus[$i].$it") } }
        manualEcus.groupBy { it.key }.filter { it.value.size > 1 }.keys.forEach { add("manualEcus: request ID $it is used twice") }
        moduleNames.forEach { (id, name) ->
            if (!(id.length == 3 || id.length == 8) || !id.all { it in '0'..'9' || it in 'A'..'F' }) add("moduleNames.$id: key must be a 3- or 8-digit upper-case hex request ID")
            if (name.isBlank()) add("moduleNames.$id: name must not be empty")
        }
    }

    fun withEcu(ecu: EcuDefinition, replacingKey: String? = null): ProfileExtras =
        copy(manualEcus = manualEcus.filterNot { it.key == ecu.key || it.key == replacingKey } + ecu)

    fun withoutEcu(key: String): ProfileExtras = copy(manualEcus = manualEcus.filterNot { it.key == key })

    /** Sets or (blank [name]) removes the user's name for a module. */
    fun withName(requestId: String, name: String?): ProfileExtras {
        val id = requestId.uppercase()
        return copy(moduleNames = if (name.isNullOrBlank()) moduleNames - id else moduleNames + (id to name.trim()))
    }
}

fun EcuDefinition.moduleAddress(): ModuleAddress =
    ModuleAddress(if (is29Bit) Bus.CAN_29 else Bus.CAN_11, requestId.uppercase(), responseId.uppercase())

object ProfileExtrasCodec {
    fun encode(extras: ProfileExtras): Map<String, Any?> = linkedMapOf(
        "manualEcus" to extras.manualEcus.map(DataPackCodec::encodeEcu),
        "moduleNames" to extras.moduleNames.toSortedMap(),
        "vehicleKey" to extras.vehicleKey,
        "notes" to extras.notes,
    ).filterValues { it != null }

    /** Decodes; problems are added to [errors] prefixed with [path]. */
    fun decode(raw: Any?, path: String, errors: MutableList<String>): ProfileExtras {
        if (raw == null) return ProfileExtras()
        val map = raw as? Map<*, *> ?: run { errors += "$path: must be an object"; return ProfileExtras() }
        val ecus = when (val list = map["manualEcus"]) {
            null -> emptyList()
            is List<*> -> list.mapIndexedNotNull { i, item -> DataPackCodec.decodeEcu(item, "$path.manualEcus[$i]", errors) }
            else -> { errors += "$path.manualEcus: must be a list"; emptyList() }
        }
        val names = when (val m = map["moduleNames"]) {
            null -> emptyMap()
            is Map<*, *> -> m.entries.mapNotNull { (k, v) ->
                val name = v as? String
                if (k !is String || name == null) { errors += "$path.moduleNames.$k: must be text"; null } else k.trim().uppercase() to name.trim()
            }.toMap()
            else -> { errors += "$path.moduleNames: must be an object"; emptyMap() }
        }
        val extras = ProfileExtras(ecus, names, map["vehicleKey"] as? String, map["notes"] as? String)
        extras.problems().forEach { errors += "$path.$it" }
        return extras
    }
}

/** Per-VIN [ProfileExtras] in a key-value store. Every save is validated. */
class ProfileExtrasStore(private val store: KeyValueStore) {

    fun get(vin: String): ProfileExtras {
        val json = store.get(key(vin)) ?: return ProfileExtras()
        val parsed = runCatching { MiniJson.parse(json) }.getOrNull()
        val errors = mutableListOf<String>()
        val extras = ProfileExtrasCodec.decode(parsed, "extras", errors)
        return extras
    }

    fun save(vin: String, extras: ProfileExtras) {
        val problems = extras.problems()
        require(problems.isEmpty()) { problems.joinToString("\n") }
        store.put(key(vin), if (extras.isEmpty) null else JsonWriter.write(ProfileExtrasCodec.encode(extras)))
    }

    fun update(vin: String, change: (ProfileExtras) -> ProfileExtras): ProfileExtras = change(get(vin)).also { save(vin, it) }

    fun delete(vin: String) = store.put(key(vin), null)

    private fun key(vin: String) = "profile.extras.${vin.uppercase()}"
}
