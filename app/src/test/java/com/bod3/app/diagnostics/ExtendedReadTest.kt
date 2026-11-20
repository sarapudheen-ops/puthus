package com.bod3.app.diagnostics

import kotlinx.coroutines.runBlocking
import org.junit.Test

class ExtendedReadTest {
    @Test fun selectedWidthEnumAndSchemaVersionAreExact() = ExtendedReadScenarios.exactSelectedWidthAndEnumVersionCannotBeDiscarded()
    @Test fun identificationPrerequisitesAreOrderedAndEcuScoped() = runBlocking { ExtendedReadScenarios.identificationUsesCurrentOrderedSupportAndNeverSensorNonzeroOrSiblingEcu() }
    @Test fun kind14CalibrationRecordsAreExactCompleteAndNotFuelType() = runBlocking { ExtendedReadScenarios.calibrationRecordsUse0904WithoutRelabelingFuelTypeOrIgnoringATruncatedId() }
    @Test fun readLeaseCannotEscalateOrChangeEcu() = runBlocking { ExtendedReadScenarios.supportReadLeaseCannotConsumeGrantsConfirmationsOrCrossTargets() }
    @Test fun selectedKeepaliveVariantsNeverExpandTheGeneralWriteOrReadPolicy() = runBlocking { ExtendedReadScenarios.onlyExactSelectedKeepaliveVariantsAndCompleteRepliesAreAccepted() }
}
