package com.bod3.app.obd.kwp

import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Source API contracts use scripted I/O, never a vehicle/adapter validation claim. */
class SourceKwpLifecycleTest {
    @Test fun allSourceFastRetryAndIsoContracts() = runBlocking { SourceKwpLifecycleScenarios.actual284EntryFastRetryAndIsoContractsMatchPinnedNativeDomains() }
    @Test fun allSourceKeepaliveAndExitBindings() = runBlocking { SourceKwpLifecycleScenarios.actual284KeepaliveAndExitBindingsNeverUseUniversalTesterPresentOrP3Defaults() }
    @Test fun initializationRequiresExactCompleteC1() = runBlocking { SourceKwpLifecycleScenarios.completeC1ProofRejectsAckZeroPartialDuplicateWrongEcuChecksumServiceAndMixedEvidence() }
    @Test fun onlyExactTransport5FailureDispatchesIso() = runBlocking { SourceKwpLifecycleScenarios.onlyTransport5ExhaustedAdapterFailureDispatchesSameEntryIsoAndNeverReplays105F() }
    @Test fun explicitPinsAbiAndImmutableOperationSnapshot() = runBlocking { SourceKwpLifecycleScenarios.syntheticMultiplePinsAlternateConnectorAndInputMutationRetainExactAbiAndBounds() }
    @Test fun reentryRequiresInitializationAndRestoresContext() = runBlocking { SourceKwpLifecycleScenarios.failedReentryCannotRetryAReadAndRestoresContextWithoutEnteringDiagnosticSession() }
    @Test fun sourceSessionLeaseNeverEscalatesOrUsesGenericFallback() = runBlocking { SourceKwpLifecycleScenarios.sessionSourceLeaseUsesOnlyTheSourcePortAndCannotEscalateOrFallBackToGenericKline() }
    @Test fun sourceSessionCancellationAndRejectedConfiguration() = runBlocking { SourceKwpLifecycleScenarios.sessionSourceCancellationAndConfigurationRejectionNeverCreateProofOrReconnectForCleanup() }
    @Test fun sourceSessionLeaseIsExclusive() = runBlocking { SourceKwpLifecycleScenarios.sourceLifecycleAndReadLeaseAreExclusiveAgainstOtherRequestsAndReleaseAfterCancellation() }
    @Test fun actualMandatoryWorkbenchSourceReadReentry() = runBlocking { SourceKwpLifecycleScenarios.actualMandatoryWorkbenchUsesSourceExitEnterRestoreAndIdenticalReadOnceWithout105FReplay() }
    @Test fun strictSourceProjectionCodec() = SourceKwpLifecycleScenarios.sourceProjectionCodecRejectsMissingTruncatedCrossRegionUnknownBooleanAndSessionSmuggling()
}
