package com.bod3.app.diagnostics

import com.bod3.app.datapack.EcuDefinition
import com.bod3.app.datapack.EcuProtocol
import com.bod3.app.obd.elm.AdapterException
import com.bod3.app.obd.session.ClearConfirmation
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.uds.DidReading
import com.bod3.app.obd.uds.DidResult
import com.bod3.app.obd.uds.DtcReadResult
import com.bod3.app.obd.uds.DtcRecord
import com.bod3.app.obd.uds.DtcRecordResult
import com.bod3.app.obd.uds.KnownDids
import com.bod3.app.obd.uds.Nrc
import com.bod3.app.obd.uds.UdsClearResult
import com.bod3.app.obd.uds.UdsClient

/** Extended data (19 06) and snapshot (19 04) of one fault code. */
data class DtcRecords(val extended: DtcRecordResult, val snapshot: DtcRecordResult)

/**
 * Reads only this ECU's explicit definitions when present (KWP on K-line OR CAN, or UDS).
 * Legacy UDS/OBD behavior is retained only for ordinary declarations without a definition set.
 * Missing converted features never cause a generic UDS/emissions request to be substituted.
 */
class EcuWorkbench(private val session: Session, private val client: UdsClient = UdsClient(session)) {
    private val prerequisites = com.bod3.app.obd.session.ReadPrerequisites(session)

    suspend fun readIdentification(ecu: EcuDefinition, onReading: (DidReading) -> Unit = {}): List<DidReading> {
        ecu.diagnostics?.let { definitions ->
            validate(ecu)
            val readings = mutableListOf<DidReading>()
            for (definition in definitions.identification) {
                val r = definition.read
                val id = r.requestBytes.drop(1).fold(0) { a, b -> a * 256 + b }
                val result = read(ecu, r).let { value ->
                    if (value is DidResult.Value && !definition.accepts(value.data))
                        DidResult.Failed("Incomplete or invalid identification record; no partial text accepted") else value
                }
                val reading = DidReading(id, result, r.id, r.name, r.request, definition.encoding != IdentificationEncoding.ASCII,
                    (result as? DidResult.Value)?.let { definition.display(it.data) })
                readings += reading; onReading(reading)
                if (readings.size == 1 && reading.result is DidResult.NoResponse) break
            }
            return readings
        }
        if (ecu.protocol != EcuProtocol.UDS) return emptyList()
        val readings = mutableListOf<DidReading>()
        for (definition in KnownDids.identification) {
            val reading = DidReading(definition.id, client.readDid(ecu.target, definition.id))
            readings += reading; onReading(reading)
            if (readings.size == 1 && reading.result is DidResult.NoResponse) break
        }
        return readings
    }

    suspend fun readDtcs(ecu: EcuDefinition, diagnosticSession: com.bod3.app.obd.session.DiagnosticSessionConfirmation? = null): DtcReadResult {
        ecu.diagnostics?.let { definitions ->
            validate(ecu)
            val d = definitions.faults ?: return DtcReadResult.Failed("This ECU has no resolved fault-read definition; no generic request was sent.")
            if (d.workflow != null) return ToyotaFaultWorkflowReader(session).read(ecu, d, diagnosticSession)
            require(diagnosticSession == null) { "No source diagnostic-session setup belongs to this ECU" }
            return when (val result = read(ecu, d.read)) {
                is DidResult.Value -> d.layout.decode(result.data, "${ecu.protocol.label} ${d.read.request} · ${d.read.name}")
                is DidResult.Negative -> DtcReadResult.Negative(result.nrc)
                DidResult.NoResponse -> DtcReadResult.NoResponse
                is DidResult.Failed -> DtcReadResult.Failed(result.message)
            }
        }
        return when (ecu.protocol) {
            EcuProtocol.UDS -> client.readDtcs(ecu.target)
            EcuProtocol.OBD -> client.readObdStoredDtcs(ecu.target)
            EcuProtocol.KWP -> DtcReadResult.Failed("Enhanced KWP fault reads need a documented definition; no UDS request was sent. Use the OBD fault screen for standard emissions codes.")
        }
    }

    /**
     * Entry point ONLY for the operation-specific Expert-mode confirmation UI.
     * Fresh preparation/identity guarding precedes the epoch-bound capability;
     * no token is retained across the dialog, connection reset or bus wait.
     * A source-read lease cannot authorize a clear, write, coding or active test.
     */
    suspend fun readDtcsWithConfirmedSourceSession(ecu: EcuDefinition, expertModeEnabled: Boolean): DtcReadResult {
        require(expertModeEnabled) { "Source diagnostic-session setup needs Expert mode and an explicit confirmation" }
        validate(ecu)
        val definitions = requireNotNull(ecu.diagnostics)
        val workflow = requireNotNull(definitions.faults?.workflow)
        require(workflow.requiresSessionConfirmation) { "This ECU has no source diagnostic-session setup" }
        return session.withSourceReadSessionOperation(ecu.target) {
            val identities = readIdentification(ecu)
            if (identities.isEmpty() || identities.any { (it.result as? DidResult.Value)?.data.isNullOrEmpty() })
                return@withSourceReadSessionOperation DtcReadResult.Failed("Fresh selected-ECU identification failed; no diagnostic session was changed")
            val confirmation = com.bod3.app.obd.session.DiagnosticSessionConfirmation.confirmed(
                session, ecu.target, workflow, expertModeEnabled)
            try { readDtcs(ecu, confirmation) } finally { confirmation.revoke() }
        }
    }

    private fun validate(ecu: EcuDefinition) {
        val errors = ecu.problems()
        require(errors.isEmpty()) { errors.joinToString("\n") }
    }

    private suspend fun read(ecu: EcuDefinition, definition: DiagnosticRead): DidResult {
        if (definition.supportChecks.isEmpty()) return readSupported(ecu, definition)
        return try {
            prerequisites.withSupported(ecu.target, definition.supportChecks, timeout(ecu)) { readSupported(ecu, definition) }
                ?: DidResult.Failed("Current ECU prerequisites were not satisfied; no substitute read was sent")
        } catch (e: AdapterException) { DidResult.Failed(e.message ?: "adapter error") }
    }

    private suspend fun readSupported(ecu: EcuDefinition, definition: DiagnosticRead): DidResult {
        val request = definition.requestBytes
        val response = try { session.request(ecu.target, request, timeout(ecu)) }
        catch (e: AdapterException) { return DidResult.Failed(e.message ?: "adapter error") }
        if (!response.complete || (response.messages.isNotEmpty() && response.negativeResponses.isNotEmpty()))
            return DidResult.Failed("Incomplete, mismatched or mixed positive/negative ECU read; no partial result accepted")
        if (response.messages.size > 1) return DidResult.Failed("Multiple positive messages for one ECU read; no ambiguous result accepted")
        response.messages.firstOrNull()?.let {
            return definition.data(it.payload)?.let(DidResult::Value)
                ?: DidResult.Failed("Reply does not match the exact positive prefix/data bounds for ${definition.name}")
        }
        val codes = response.negativeResponses.mapNotNull { it.payload.getOrNull(2) }
        val nrc = codes.firstOrNull { it != Nrc.RESPONSE_PENDING } ?: codes.firstOrNull()
        return nrc?.let(DidResult::Negative) ?: DidResult.NoResponse
    }

    suspend fun readRecords(ecu: EcuDefinition, dtc: DtcRecord): DtcRecords {
        require(canReadRecords(ecu)) { "This ECU has no documented extended-data/snapshot definition" }
        return DtcRecords(extended = client.readExtendedData(ecu.target, dtc), snapshot = client.readSnapshot(ecu.target, dtc))
    }

    /** Exact bytes must equal those the user confirmed; no retry, alternate group, or dialect fallback. */
    suspend fun clear(ecu: EcuDefinition, confirmation: ClearConfirmation): UdsClearResult {
        val definition = requireNotNull(clearDefinition(ecu)) { "This ECU has no documented clear definition" }
        validate(ecu)
        require(confirmation.request == definition.requestBytes && confirmation.target == ecu.target) { "Confirmation is for a different clear request or ECU" }
        if (ecu.diagnostics == null) return client.clearDtcs(ecu.target, confirmation)
        val response = try { session.request(ecu.target, definition.requestBytes, timeout(ecu), confirmation = confirmation) }
        catch (e: AdapterException) { return UdsClearResult.Failed(e.message ?: "adapter error") }
        if (!response.complete || (response.messages.isNotEmpty() && response.negativeResponses.isNotEmpty()))
            return UdsClearResult.Failed("Incomplete, mismatched or mixed positive/negative clear replies; result is unknown")
        if (response.messages.size > 1) return UdsClearResult.Failed("Multiple positive clear replies; result is ambiguous")
        response.messages.firstOrNull()?.let {
            return if (it.payload == definition.prefixBytes) UdsClearResult.Cleared
            else UdsClearResult.Failed("Clear reply did not match its documented positive prefix")
        }
        val codes = response.negativeResponses.mapNotNull { it.payload.getOrNull(2) }
        val nrc = codes.firstOrNull { it != Nrc.RESPONSE_PENDING } ?: codes.firstOrNull() ?: return UdsClearResult.NoResponse
        return if (nrc == Nrc.RESPONSE_PENDING) UdsClearResult.Pending else UdsClearResult.Negative(nrc)
    }

    companion object {
        private fun timeout(ecu: EcuDefinition): Long = ecu.link?.kLine?.p2StarMs?.toLong() ?: 5000L
        fun canReadIdentification(ecu: EcuDefinition): Boolean = ecu.diagnostics?.identification?.isNotEmpty() ?: (ecu.protocol == EcuProtocol.UDS)
        fun canReadDtcs(ecu: EcuDefinition): Boolean = ecu.diagnostics?.let { it.faults != null } ?: (ecu.protocol != EcuProtocol.KWP)
        // Converted definitions must explicitly supply any future freeze-frame variant; no UDS fallback.
        fun canReadRecords(ecu: EcuDefinition): Boolean = ecu.diagnostics == null && ecu.protocol == EcuProtocol.UDS
        fun canClear(ecu: EcuDefinition): Boolean = clearDefinition(ecu) != null
        fun clearRequestLabel(ecu: EcuDefinition): String? = clearDefinition(ecu)?.request
        private fun clearDefinition(ecu: EcuDefinition): DiagnosticClear? = ecu.diagnostics?.let { it.faults?.clear }
            ?: if (ecu.diagnostics == null && ecu.protocol == EcuProtocol.UDS) DiagnosticClear("14 FF FF FF", "54") else null

        /** Call only after the user confirmed the dialog displaying [clearRequestLabel]. */
        fun confirmation(ecu: EcuDefinition): ClearConfirmation {
            val definition = clearDefinition(ecu) ?: error("This ECU has no documented clear definition")
            require(ecu.problems().isEmpty()) { "Invalid ECU/clear definition" }
            val group = definition.requestBytes.drop(1).fold(0) { a, b -> a * 256 + b }
            return if (ecu.protocol == EcuProtocol.KWP) {
                if (definition.requestBytes == listOf(0x04)) ClearConfirmation.kwpClear04(ecu.target)
                else ClearConfirmation.kwpClear(ecu.target, group)
            } else ClearConfirmation.udsClear(ecu.target, group)
        }
    }
}
