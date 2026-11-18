package com.bod3.app.obd.kwp

import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Every service is its own CI case; the fixtures are synthetic, never source-derived car mappings. */
class KwpTest {
    @Test fun frameBoundariesChecksumsAndHeaders() = KwpScenarios.framing()
    @Test fun startCommunication81() = runBlocking { KwpScenarios.reads(0x81) }
    @Test fun identification1A() = runBlocking { KwpScenarios.reads(0x1A) }
    @Test fun localRead21() = runBlocking { KwpScenarios.reads(0x21) }
    @Test fun identifierRead22() = runBlocking { KwpScenarios.reads(0x22) }
    @Test fun dtcRead18() = runBlocking { KwpScenarios.reads(0x18) }
    @Test fun dtcRead19() = runBlocking { KwpScenarios.reads(0x19) }
    @Test fun testerPresent3E() = runBlocking { KwpScenarios.reads(0x3E) }
    @Test fun diagnosticSession10() = runBlocking { KwpScenarios.writes(0x10) }
    @Test fun security27() = runBlocking { KwpScenarios.writes(0x27) }
    @Test fun localIo30() = runBlocking { KwpScenarios.writes(0x30) }
    @Test fun routineStart31() = runBlocking { KwpScenarios.writes(0x31) }
    @Test fun routineStop32() = runBlocking { KwpScenarios.writes(0x32) }
    @Test fun routineResults33() = runBlocking { KwpScenarios.writes(0x33) }
    @Test fun localWrite3B() = runBlocking { KwpScenarios.writes(0x3B) }
    @Test fun identifierWrite2E() = runBlocking { KwpScenarios.writes(0x2E) }
    @Test fun identifierEcuScopeAndConfirmedClear14() = runBlocking { KwpScenarios.scopeAndClear() }
    @Test fun initTimingExpiryBadChecksumWrongEcuAndRejection() = runBlocking { KwpScenarios.initExpiryAndRejection() }
    @Test fun fiveBaudIso9141And14230() = runBlocking { KwpScenarios.fiveBaudAndIso9141() }
    @Test fun kwpServicesOnCan() = runBlocking { KwpScenarios.kwpOverCan() }
    @Test fun routingLeaseIsExclusiveAndCancellationReleasesIt() = runBlocking { KwpScenarios.lease() }
    @Test fun codingBackupDiffWriteVerifyRollbackAndSingleUseConfirmation() = runBlocking { KwpScenarios.coding() }
    @Test fun outputAndRoutineStopAndReturnToDefaultSession() = runBlocking { KwpScenarios.actuator() }
    @Test fun cancellationStillStopsAndAuditsAcrossRoutingContext() = runBlocking { KwpScenarios.actuatorCancellation() }
    @Test fun localFormulaByteOffsetAndEcho() = KwpScenarios.formulaOffset()
}
