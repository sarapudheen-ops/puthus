package com.bod3.app.profile

import com.bod3.app.obd.elm.RawExchange

/** Stored form of the profile header (everything except modules and log). Mirrors the Room entity. */
data class StoredProfile(
    val vin: String,
    val make: String?,
    val model: String?,
    val year: Int?,
    /** [DescriptionSource] name, or null when the vehicle has not been described yet. */
    val descriptionSource: String?,
    val descriptionJson: String?,
    val protocol: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val lastScanAtMs: Long?,
)

/** Persistence for vehicle profiles. Implemented with Room on Android and in memory for tests. */
interface ProfileRepository {
    suspend fun getProfile(vin: String): StoredProfile?
    suspend fun allProfiles(): List<StoredProfile>
    suspend fun saveProfile(profile: StoredProfile)
    suspend fun modules(vin: String): List<StoredModule>

    /** Replaces modules and raw log of [vin] in one transaction and updates the profile header. */
    suspend fun saveScan(profile: StoredProfile, modules: List<StoredModule>, rawLog: List<RawExchange>)
    suspend fun rawLog(vin: String): List<RawExchange>
    suspend fun deleteProfile(vin: String)
}

class InMemoryProfileRepository : ProfileRepository {
    private val profiles = LinkedHashMap<String, StoredProfile>()
    private val moduleRows = HashMap<String, List<StoredModule>>()
    private val logs = HashMap<String, List<RawExchange>>()

    override suspend fun getProfile(vin: String) = synchronized(this) { profiles[vin] }
    override suspend fun allProfiles() = synchronized(this) { profiles.values.sortedByDescending { it.updatedAtMs } }
    override suspend fun saveProfile(profile: StoredProfile) = synchronized(this) { profiles[profile.vin] = profile }
    override suspend fun modules(vin: String) = synchronized(this) { moduleRows[vin].orEmpty() }
    override suspend fun saveScan(profile: StoredProfile, modules: List<StoredModule>, rawLog: List<RawExchange>) =
        synchronized(this) {
            profiles[profile.vin] = profile
            moduleRows[profile.vin] = modules
            logs[profile.vin] = rawLog
        }
    override suspend fun rawLog(vin: String) = synchronized(this) { logs[vin].orEmpty() }
    override suspend fun deleteProfile(vin: String) = synchronized(this) {
        profiles.remove(vin)
        moduleRows.remove(vin)
        logs.remove(vin)
        Unit
    }
}
