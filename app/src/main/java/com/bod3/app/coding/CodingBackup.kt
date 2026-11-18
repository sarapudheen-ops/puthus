package com.bod3.app.coding

import com.bod3.app.actuator.ActuatorModule
import com.bod3.app.obd.connection.KeyValueStore
import com.bod3.app.obd.uds.SecurityAccessSpec
import com.bod3.app.obd.uds.SecurityAlgorithm
import com.bod3.app.profile.JsonWriter
import com.bod3.app.profile.MiniJson
import com.bod3.app.recipe.Hex

enum class BackupStatus(val label: String) {
    /** Read and saved; nothing written yet. */
    SAVED("Saved before writing"),
    WRITTEN("New value written and verified"),
    WRITE_FAILED("Write failed or did not verify"),
    WRITE_UNKNOWN("Write result unknown"),
    ROLLED_BACK("Rolled back to this value"),
}

/**
 * The complete identifier data read from a module before a coding write, with everything needed to
 * write it back later (module, session, security), even if the recipe has been deleted since.
 */
data class CodingBackup(
    val id: String,
    val createdAtMs: Long,
    val recipeId: String,
    val recipeName: String,
    val vin: String?,
    val module: ActuatorModule,
    val session: Int,
    val security: SecurityAccessSpec?,
    val preconditions: CodingPreconditions,
    val did: Int,
    val data: List<Int>,
    val partNumber: String?,
    val softwareVersion: String?,
    val status: BackupStatus = BackupStatus.SAVED,
    val updatedAtMs: Long = createdAtMs,
)

/** Backups, newest last, capped at [maxBackups]. Never deleted by the app otherwise. */
class CodingBackupStore(private val store: KeyValueStore, private val maxBackups: Int = 300) {

    fun all(): List<CodingBackup> {
        val list = store.get(KEY)?.let { runCatching { MiniJson.parse(it) as? List<*> }.getOrNull() }.orEmpty()
        return list.mapNotNull { (it as? Map<*, *>)?.let(::decode) }
    }

    fun get(id: String): CodingBackup? = all().firstOrNull { it.id == id }

    fun add(backup: CodingBackup) = save((all().filterNot { it.id == backup.id } + backup).takeLast(maxBackups))

    fun updateStatus(id: String, status: BackupStatus, atMs: Long) =
        save(all().map { if (it.id == id) it.copy(status = status, updatedAtMs = atMs) else it })

    private fun save(list: List<CodingBackup>) = store.put(KEY, JsonWriter.write(list.map(::encode)))

    private fun encode(b: CodingBackup): Map<String, Any?> = linkedMapOf(
        "id" to b.id, "createdAt" to b.createdAtMs, "updatedAt" to b.updatedAtMs, "recipeId" to b.recipeId, "recipeName" to b.recipeName,
        "vin" to b.vin,
        "module" to linkedMapOf("name" to b.module.name, "address" to b.module.address, "requestId" to b.module.requestId, "responseId" to b.module.responseId),
        "session" to b.session,
        "security" to b.security?.let { linkedMapOf("level" to it.level, "algorithm" to it.algorithm.name, "constant" to Hex.format(it.constant, "")) },
        "minVoltage" to b.preconditions.minVoltage, "allowEngineRunning" to b.preconditions.allowEngineRunning,
        "did" to b.did, "data" to Hex.format(b.data, ""), "partNumber" to b.partNumber, "softwareVersion" to b.softwareVersion,
        "status" to b.status.name,
    )

    private fun decode(m: Map<*, *>): CodingBackup? {
        val module = m["module"] as? Map<*, *> ?: return null
        val sec = m["security"] as? Map<*, *>
        return CodingBackup(
            id = m["id"] as? String ?: return null,
            createdAtMs = (m["createdAt"] as? Number)?.toLong() ?: 0L,
            updatedAtMs = (m["updatedAt"] as? Number)?.toLong() ?: 0L,
            recipeId = m["recipeId"] as? String ?: "",
            recipeName = m["recipeName"] as? String ?: "",
            vin = m["vin"] as? String,
            module = ActuatorModule(
                module["name"] as? String ?: "", module["address"] as? String,
                module["requestId"] as? String ?: return null, module["responseId"] as? String ?: return null,
            ),
            session = (m["session"] as? Number)?.toInt() ?: 0x03,
            security = sec?.let { s ->
                val algorithm = SecurityAlgorithm.entries.firstOrNull { it.name == s["algorithm"] } ?: return null
                SecurityAccessSpec((s["level"] as? Number)?.toInt() ?: return null, algorithm, Hex.parse(s["constant"] as? String ?: "") ?: return null)
            },
            preconditions = CodingPreconditions((m["minVoltage"] as? Number)?.toDouble() ?: 12.0, m["allowEngineRunning"] as? Boolean ?: false),
            did = (m["did"] as? Number)?.toInt() ?: return null,
            data = Hex.parse(m["data"] as? String ?: "") ?: return null,
            partNumber = m["partNumber"] as? String,
            softwareVersion = m["softwareVersion"] as? String,
            status = BackupStatus.entries.firstOrNull { it.name == m["status"] } ?: BackupStatus.SAVED,
        )
    }

    companion object {
        const val KEY = "coding.backups"
    }
}
