package com.bod3.app.profile.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
abstract class ProfileDao {
    @Query("SELECT * FROM vehicle_profiles WHERE vin = :vin")
    abstract suspend fun profile(vin: String): VehicleProfileEntity?

    @Query("SELECT * FROM vehicle_profiles ORDER BY updated_at DESC")
    abstract suspend fun profiles(): List<VehicleProfileEntity>

    /** Upsert (not REPLACE): REPLACE would delete the row and cascade-delete its modules and log. */
    @Upsert
    abstract suspend fun upsertProfile(profile: VehicleProfileEntity)

    @Query("SELECT * FROM modules WHERE vin = :vin ORDER BY bus, request_header")
    abstract suspend fun modules(vin: String): List<ModuleEntity>

    @Query("DELETE FROM modules WHERE vin = :vin")
    abstract suspend fun deleteModules(vin: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertModules(modules: List<ModuleEntity>)

    @Query("SELECT * FROM raw_log WHERE vin = :vin ORDER BY sequence")
    abstract suspend fun rawLog(vin: String): List<RawLogEntity>

    @Query("DELETE FROM raw_log WHERE vin = :vin")
    abstract suspend fun deleteRawLog(vin: String)

    @Insert
    abstract suspend fun insertRawLog(entries: List<RawLogEntity>)

    @Query("DELETE FROM vehicle_profiles WHERE vin = :vin")
    abstract suspend fun deleteProfile(vin: String)

    /** Saves a scan atomically: the profile header, then its modules and raw log replace the old ones. */
    @Transaction
    open suspend fun saveScan(profile: VehicleProfileEntity, modules: List<ModuleEntity>, rawLog: List<RawLogEntity>) {
        upsertProfile(profile)
        deleteModules(profile.vin)
        insertModules(modules)
        deleteRawLog(profile.vin)
        insertRawLog(rawLog)
    }
}
