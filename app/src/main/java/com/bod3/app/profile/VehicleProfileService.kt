package com.bod3.app.profile

import com.bod3.app.obd.elm.RawExchange
import com.bod3.app.obd.scan.ModuleReport

/**
 * Loads, creates and updates vehicle profiles.
 *
 * - [onConnected]: on every connection with a known VIN; returns the saved profile (with its modules)
 *   or creates one. The vehicle is (re)described via vPIC when it was never described or only offline.
 * - [saveScan]: stores the result of a module scan, replacing the previous modules and raw log.
 */
class VehicleProfileService(
    private val repository: ProfileRepository,
    private val describer: VinDescriber,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    data class LoadResult(val profile: VehicleProfile, val isNew: Boolean)

    suspend fun onConnected(vin: String, protocol: String?): LoadResult {
        val now = clock()
        val existing = repository.getProfile(vin)
        var stored = existing ?: StoredProfile(
            vin = vin, make = null, model = null, year = null, descriptionSource = null, descriptionJson = null,
            protocol = protocol, createdAtMs = now, updatedAtMs = now, lastScanAtMs = null,
        )
        if (stored.descriptionSource != DescriptionSource.VPIC.name) {
            val description = describer.describe(vin)
            // Keep an earlier description if the new one knows less (e.g. offline after an online decode).
            if (stored.descriptionSource == null || description.source == DescriptionSource.VPIC) {
                stored = stored.withDescription(description)
            }
        }
        stored = stored.copy(protocol = protocol ?: stored.protocol, updatedAtMs = now)
        repository.saveProfile(stored)
        return LoadResult(assemble(stored, repository.modules(vin)), isNew = existing == null)
    }

    suspend fun load(vin: String): VehicleProfile? {
        val stored = repository.getProfile(vin) ?: return null
        return assemble(stored, repository.modules(vin))
    }

    suspend fun allProfiles(): List<VehicleProfile> = repository.allProfiles().map { assemble(it, emptyList()) }

    suspend fun saveScan(vin: String, protocol: String?, reports: List<ModuleReport>, rawLog: List<RawExchange>): VehicleProfile {
        val now = clock()
        val base = repository.getProfile(vin) ?: StoredProfile(
            vin = vin, make = null, model = null, year = null, descriptionSource = null, descriptionJson = null,
            protocol = protocol, createdAtMs = now, updatedAtMs = now, lastScanAtMs = null,
        )
        val updated = base.copy(protocol = protocol ?: base.protocol, updatedAtMs = now, lastScanAtMs = now)
        val modules = reports.map { ModuleCodec.encode(vin, it, now) }
        repository.saveScan(updated, modules, rawLog)
        return assemble(updated, modules)
    }

    suspend fun rawLog(vin: String): List<RawExchange> = repository.rawLog(vin)

    private fun assemble(stored: StoredProfile, modules: List<StoredModule>) = VehicleProfile(
        vin = stored.vin,
        description = stored.description(),
        protocol = stored.protocol,
        createdAtMs = stored.createdAtMs,
        updatedAtMs = stored.updatedAtMs,
        lastScanAtMs = stored.lastScanAtMs,
        modules = modules.map(ModuleCodec::decode),
    )

    companion object {
        internal fun StoredProfile.withDescription(d: VehicleDescription) = copy(
            make = d.make,
            model = d.model,
            year = d.year,
            descriptionSource = d.source.name,
            descriptionJson = JsonWriter.write(mapOf("extra" to d.extra, "note" to d.note)),
        )

        internal fun StoredProfile.description(): VehicleDescription? {
            val source = descriptionSource?.let { runCatching { DescriptionSource.valueOf(it) }.getOrNull() } ?: return null
            val json = descriptionJson?.let { runCatching { MiniJson.parse(it) as? Map<*, *> }.getOrNull() }
            val extra = (json?.get("extra") as? Map<*, *>).orEmpty()
                .mapNotNull { (k, v) -> (v as? String)?.let { k.toString() to it } }.toMap()
            return VehicleDescription(make, model, year, source, extra, json?.get("note") as? String)
        }
    }
}
