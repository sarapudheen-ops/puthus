package com.bod3.app.obd.scan

import com.bod3.app.obd.session.Session
import com.bod3.app.obd.uds.DidReading
import com.bod3.app.obd.uds.DidResult
import com.bod3.app.obd.uds.DtcReadResult
import com.bod3.app.obd.uds.KnownDids
import com.bod3.app.obd.uds.UdsClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Reads identification data and DTCs from one module with read-only requests:
 * UDS `22 <DID>` for each identifier and `19 02 FF` for DTCs, plus OBD `09 0A` / `09 04` / `03` for
 * modules that answered OBD requests. Unsupported items come back as "not supported" values.
 *
 * The session must already be on the module's bus (see [ScanCoordinator]).
 */
class ModuleReader(
    private val session: Session,
    private val uds: UdsClient = UdsClient(session),
) {

    suspend fun read(
        module: DiscoveredModule,
        dids: List<Int> = KnownDids.default.map { it.id },
        onStep: (String) -> Unit = {},
    ): ModuleReport {
        val target = module.address.target()

        val identifiers = mutableListOf<DidReading>()
        for (did in dids) {
            currentCoroutineContext().ensureActive()
            onStep("22 %04X".format(did))
            identifiers += DidReading(did, uds.readDid(target, did))
        }

        currentCoroutineContext().ensureActive()
        onStep("19 02")
        var dtcs = uds.readDtcs(target)

        var ecuName: String? = null
        var calibrationId: String? = null
        if (module.obd) {
            currentCoroutineContext().ensureActive()
            onStep("09 0A")
            ecuName = uds.readObdText(target, 0x0A)
            onStep("09 04")
            calibrationId = uds.readObdText(target, 0x04)
            if (dtcs !is DtcReadResult.Read) {
                onStep("03")
                val obdDtcs = uds.readObdStoredDtcs(target)
                if (obdDtcs is DtcReadResult.Read) dtcs = obdDtcs
            }
        }

        val systemName = (identifiers.firstOrNull { it.did == KnownDids.SYSTEM_NAME.id }?.result as? DidResult.Value)?.text
        return ModuleReport(
            module = module,
            name = KnownModules.resolve(module.address, systemName, ecuName),
            identifiers = identifiers,
            dtcs = dtcs,
            obdEcuName = ecuName,
            calibrationId = calibrationId,
        )
    }
}
