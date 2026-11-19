package com.bod3.app.bringup

import com.bod3.app.obd.testing.SimulatedBringUpAdapter.Mode
import kotlinx.coroutines.runBlocking
import org.junit.Test

class BringUpTest {
    @Test fun fullEvidenceAndOnlyThreeReadProbes() = runBlocking { BringUpScenarios.working() }
    @Test fun silentEcuNeverVerifies() = runBlocking { BringUpScenarios.modeFails(Mode.SILENT) }
    @Test fun validChecksumButWrongFramingNeverVerifies() = runBlocking { BringUpScenarios.modeFails(Mode.WRONG_FRAMING) }
    @Test fun wrongChecksumNeverVerifies() = runBlocking { BringUpScenarios.modeFails(Mode.WRONG_CHECKSUM) }
    @Test fun wrongEcuNeverVerifies() = runBlocking { BringUpScenarios.modeFails(Mode.WRONG_ECU) }
    @Test fun wrongIdentifierEchoNeverVerifies() = runBlocking { BringUpScenarios.modeFails(Mode.WRONG_ECHO) }
    @Test fun pendingMixedWithPositiveIsNotProof() = runBlocking { BringUpScenarios.modeFails(Mode.MIXED) }
    @Test fun liveByteBoundsMustPass() = runBlocking { BringUpScenarios.modeFails(Mode.SHORT_LIVE) }
    @Test fun dtcLayoutMustPass() = runBlocking { BringUpScenarios.modeFails(Mode.BAD_DTC) }
    @Test fun rankedOrderStopsAtFirstWorkingAndNeverWrites() = runBlocking { BringUpScenarios.rankAndStop() }
    @Test fun manualCandidateDoesNotTryOtherSetups() = runBlocking { BringUpScenarios.manualSelection() }
    @Test fun unresolvedSourceFieldsAreListedWithoutGuessingTraffic() = runBlocking { BringUpScenarios.blockedNoIo() }
    @Test fun rejectedAdapterTimingCommandCannotVerifyAnotherSetup() = runBlocking { BringUpScenarios.strictSetup() }
    @Test fun emptyDtcMemoryStillProvesARead() = runBlocking { BringUpScenarios.emptyDtcIsValid() }
    @Test fun unsafeSessionCrossEcuAndMalformedCandidatesFailBeforeIo() = runBlocking { BringUpScenarios.malformedCandidates() }
    @Test fun transfersRetainFullEvidenceButNotSourceOrWriteTrust() = runBlocking { BringUpScenarios.transferDoesNotTrustFlags() }
    @Test fun corruptTransfersAreAtomicAndDifferentCarsStaySeparate() = runBlocking { BringUpScenarios.transferAtomicValidation() }
    @Test fun storageNeverSilentlyEvictsAnotherVehicle() = runBlocking { BringUpScenarios.capacityNoSilentEviction() }
    @Test fun evidenceCountLimitStopsRatherThanDroppingOldEntries() = runBlocking { BringUpScenarios.evidenceLimit(true) }
    @Test fun evidenceByteLimitCannotVerify() = runBlocking { BringUpScenarios.evidenceLimit(false) }
    @Test fun cancellationRetainsAReportAndRemovesTheObserver() = runBlocking { BringUpScenarios.cancellationAndLease() }
    @Test fun backgroundRequestsCannotInterleaveWithAProbeSequence() = runBlocking { BringUpScenarios.noInterleaving() }
    @Test fun readOnlyLeaseCannotEscalateEvenWithAValidGrant() = runBlocking { BringUpScenarios.readOnlyCannotEscalate() }
    @Test fun sameEcuSerialIsRecheckedBeforeWritesIncludingDirectGrantedCalls() = runBlocking { BringUpScenarios.controllerSameEcu() }
    @Test fun partNumberOnlyIdentityCannotUnlockWrites() = runBlocking { BringUpScenarios.controllerSameEcu(nonUnique = true) }
    @Test fun transferredLocalHistoryReconnectAndOtherCarsCannotReuseCurrentProof() = runBlocking { BringUpScenarios.controllerDoesNotReuseAcrossCarsOrPhones() }
    @Test fun customProfileCannotSilentlyChangeARecoveredCandidate() = runBlocking { BringUpScenarios.nonDefaultProfileCannotAlterCandidate() }
    @Test fun canFramingAndMultiframeBringUp() = runBlocking { BringUpScenarios.canBringUp(false) }
    @Test fun truncatedCanFramingCannotVerify() = runBlocking { BringUpScenarios.canBringUp(true) }
    @Test fun packPickerPreviewAndConfirmationUseTransferredEvidenceOnly() = runBlocking { BringUpScenarios.transferPreview() }
    @Test fun fingerprintsBindFormulaAndSetupNotImportedFlags() = runBlocking { BringUpScenarios.fingerprintBindsDefinitions() }
}
