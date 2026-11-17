package com.bod3.app.profile.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.bod3.app.obd.elm.RawExchange
import com.bod3.app.profile.StoredModule
import com.bod3.app.profile.StoredProfile

@Entity(tableName = "vehicle_profiles")
data class VehicleProfileEntity(
    @PrimaryKey val vin: String,
    val make: String?,
    val model: String?,
    val year: Int?,
    @ColumnInfo(name = "description_source") val descriptionSource: String?,
    @ColumnInfo(name = "description_json") val descriptionJson: String?,
    val protocol: String?,
    @ColumnInfo(name = "created_at") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at") val updatedAtMs: Long,
    @ColumnInfo(name = "last_scan_at") val lastScanAtMs: Long?,
) {
    fun toStored() = StoredProfile(vin, make, model, year, descriptionSource, descriptionJson, protocol, createdAtMs, updatedAtMs, lastScanAtMs)

    companion object {
        fun from(p: StoredProfile) = VehicleProfileEntity(
            p.vin, p.make, p.model, p.year, p.descriptionSource, p.descriptionJson, p.protocol, p.createdAtMs, p.updatedAtMs, p.lastScanAtMs,
        )
    }
}

@Entity(
    tableName = "modules",
    foreignKeys = [
        ForeignKey(entity = VehicleProfileEntity::class, parentColumns = ["vin"], childColumns = ["vin"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("vin"), Index(value = ["vin", "bus", "request_header", "response_header"], unique = true)],
)
data class ModuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vin: String,
    val bus: String,
    @ColumnInfo(name = "request_header") val requestHeader: String,
    @ColumnInfo(name = "response_header") val responseHeader: String,
    val name: String?,
    @ColumnInfo(name = "detected_by") val detectedBy: String,
    val obd: Boolean,
    @ColumnInfo(name = "negative_reply") val negativeReply: Boolean,
    @ColumnInfo(name = "first_reply") val firstReply: String?,
    @ColumnInfo(name = "part_number") val partNumber: String?,
    @ColumnInfo(name = "software_version") val softwareVersion: String?,
    @ColumnInfo(name = "hardware_version") val hardwareVersion: String?,
    val coding: String?,
    @ColumnInfo(name = "dtc_count") val dtcCount: Int?,
    @ColumnInfo(name = "identifiers_json") val identifiersJson: String,
    @ColumnInfo(name = "dtcs_json") val dtcsJson: String,
    @ColumnInfo(name = "obd_ecu_name") val obdEcuName: String?,
    @ColumnInfo(name = "calibration_id") val calibrationId: String?,
    @ColumnInfo(name = "scanned_at") val scannedAtMs: Long,
) {
    fun toStored() = StoredModule(
        vin, bus, requestHeader, responseHeader, name, detectedBy, obd, negativeReply, firstReply, partNumber,
        softwareVersion, hardwareVersion, coding, dtcCount, identifiersJson, dtcsJson, obdEcuName, calibrationId, scannedAtMs,
    )

    companion object {
        fun from(m: StoredModule) = ModuleEntity(
            vin = m.vin, bus = m.bus, requestHeader = m.requestHeader, responseHeader = m.responseHeader, name = m.name,
            detectedBy = m.detectedBy, obd = m.obd, negativeReply = m.negativeReply, firstReply = m.firstReply,
            partNumber = m.partNumber, softwareVersion = m.softwareVersion, hardwareVersion = m.hardwareVersion,
            coding = m.coding, dtcCount = m.dtcCount, identifiersJson = m.identifiersJson, dtcsJson = m.dtcsJson,
            obdEcuName = m.obdEcuName, calibrationId = m.calibrationId, scannedAtMs = m.scannedAtMs,
        )
    }
}

@Entity(
    tableName = "raw_log",
    foreignKeys = [
        ForeignKey(entity = VehicleProfileEntity::class, parentColumns = ["vin"], childColumns = ["vin"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("vin")],
)
data class RawLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vin: String,
    val sequence: Int,
    @ColumnInfo(name = "timestamp") val timestampMs: Long,
    val header: String?,
    val command: String,
    val response: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    val error: String?,
) {
    fun toExchange() = RawExchange(sequence, timestampMs, header, command, response, durationMs, error)

    companion object {
        fun from(vin: String, e: RawExchange) =
            RawLogEntity(vin = vin, sequence = e.sequence, timestampMs = e.timestampMs, header = e.header, command = e.command, response = e.response, durationMs = e.durationMs, error = e.error)
    }
}
