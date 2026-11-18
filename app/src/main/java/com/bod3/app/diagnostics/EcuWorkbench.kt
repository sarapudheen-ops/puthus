package com.bod3.app.diagnostics

import com.bod3.app.datapack.EcuDefinition
import com.bod3.app.datapack.EcuProtocol
import com.bod3.app.obd.session.ClearConfirmation
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.uds.DidReading
import com.bod3.app.obd.uds.DidResult
import com.bod3.app.obd.uds.DtcReadResult
import com.bod3.app.obd.uds.DtcRecord
import com.bod3.app.obd.uds.DtcRecordResult
import com.bod3.app.obd.uds.KnownDids
import com.bod3.app.obd.uds.UdsClearResult
import com.bod3.app.obd.uds.UdsClient

/** Extended data (19 06) and snapshot (19 04) of one fault code. */
data class DtcRecords(val extended: DtcRecordResult, val snapshot: DtcRecordResult)

/**
 * Per-ECU diagnostics for an ECU from the vehicle list: identification, fault codes with their
 * records, and a confirmed fault-memory clear. Only UDS ECUs support records and clearing; OBD-only
 * ECUs are read with service 03 and cleared from the OBD fault code screen.
 */
class EcuWorkbench(session: Session, private val client: UdsClient = UdsClient(session)) {

    /**
     * Reads the identification DIDs one by one ([onReading] after each). Stops after the first one
     * when the ECU does not answer at all, so an absent module costs one timeout, not twenty-five.
     */
    suspend fun readIdentification(ecu: EcuDefinition, onReading: (DidReading) -> Unit = {}): List<DidReading> {
        if (ecu.protocol != EcuProtocol.UDS) return emptyList()
        val readings = mutableListOf<DidReading>()
        for (definition in KnownDids.identification) {
            val reading = DidReading(definition.id, client.readDid(ecu.target, definition.id))
            readings += reading
            onReading(reading)
            if (readings.size == 1 && reading.result is DidResult.NoResponse) break
        }
        return readings
    }

    suspend fun readDtcs(ecu: EcuDefinition): DtcReadResult = when (ecu.protocol) {
        EcuProtocol.UDS -> client.readDtcs(ecu.target)
        EcuProtocol.OBD -> client.readObdStoredDtcs(ecu.target)
        EcuProtocol.KWP -> DtcReadResult.Failed("Enhanced KWP fault reads need a documented definition; no UDS request was sent. Use the OBD fault screen for standard emissions codes.")
    }

    suspend fun readRecords(ecu: EcuDefinition, dtc: DtcRecord): DtcRecords {
        require(ecu.protocol == EcuProtocol.UDS) { "Extended data and snapshots need a UDS module" }
        return DtcRecords(extended = client.readExtendedData(ecu.target, dtc), snapshot = client.readSnapshot(ecu.target, dtc))
    }

    /** Sends `14 FF FF FF` once. [confirmation] must come from the user's confirmation for this ECU. */
    suspend fun clear(ecu: EcuDefinition, confirmation: ClearConfirmation): UdsClearResult {
        require(ecu.protocol == EcuProtocol.UDS) { "Use the OBD fault code screen to clear OBD-only ECUs" }
        return client.clearDtcs(ecu.target, confirmation)
    }
}
