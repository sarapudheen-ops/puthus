package com.bod3.app.profile.db

import com.bod3.app.obd.elm.RawExchange
import com.bod3.app.profile.ProfileRepository
import com.bod3.app.profile.StoredModule
import com.bod3.app.profile.StoredProfile

/** [ProfileRepository] backed by Room. Room runs suspend DAO calls on its own background executor. */
class RoomProfileRepository(private val dao: ProfileDao) : ProfileRepository {
    override suspend fun getProfile(vin: String) = dao.profile(vin)?.toStored()
    override suspend fun allProfiles() = dao.profiles().map { it.toStored() }
    override suspend fun saveProfile(profile: StoredProfile) = dao.upsertProfile(VehicleProfileEntity.from(profile))
    override suspend fun modules(vin: String) = dao.modules(vin).map { it.toStored() }

    override suspend fun saveScan(profile: StoredProfile, modules: List<StoredModule>, rawLog: List<RawExchange>) =
        dao.saveScan(
            VehicleProfileEntity.from(profile),
            modules.map(ModuleEntity::from),
            rawLog.map { RawLogEntity.from(profile.vin, it) },
        )

    override suspend fun rawLog(vin: String) = dao.rawLog(vin).map { it.toExchange() }
    override suspend fun deleteProfile(vin: String) = dao.deleteProfile(vin)
}
