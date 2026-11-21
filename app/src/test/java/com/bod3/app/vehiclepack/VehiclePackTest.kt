package com.bod3.app.vehiclepack

import java.io.File
import org.junit.Test

class VehiclePackTest {
    @Test fun boundedGzipAndChecksum() = VehiclePackScenarios.boundedGzip()
    @Test fun exactHttpRangeAndRedirectContract() = VehiclePackScenarios.rangeHttpContract()
    @Test fun contentCacheLruStaysBoundedAcrossTouchInstallAndRestart() = VehiclePackScenarios.cacheLruStaysBoundedAcrossDownloadsAndReopening()
    @Test fun onlyChosenPathIsFetchedAndThenWorksOffline() = VehiclePackScenarios.onlySelectedPathAndCache()
    @Test fun sharedSourceMetadataIsLazyScopedAndOffline() = VehiclePackScenarios.referencedSourcesAreScopedAndReopenOffline()
    @Test fun wrongVehicleEcuOrFunctionReferenceIsRejected() = VehiclePackScenarios.referencedSourcesCannotCrossVehicleEcuOrFunction()
    @Test fun onlyPinnedConversionMayEnableWriteDefinitions() = VehiclePackScenarios.trustedConversionNotImportedFlags()
    @Test fun bringUpCandidatesHaveIndependentPinnedProvenanceNotImportTrust() = VehiclePackScenarios.pinnedBringUpCandidatesAreSeparateFromExecutableSystemFlags()
    @Test fun crossEcuDefinitionIsRejectedAtomically() = VehiclePackScenarios.crossEcuDefinitionRejected()
    @Test fun convertedEcuCannotSubstituteLegacyGenericReads() = VehiclePackScenarios.convertedEcuCannotSubstituteLegacyGenericReads()
    @Test fun convertedPrerequisitesNeedTheirOwnProvenanceAndNeverGrantImportTrust() = VehiclePackScenarios.convertedPrerequisitesMustCarryTheirOwnProvenance()
    @Test fun enabledIsNonemptyAndTruthfullyCountedAndDisabledContainsNoData() = VehiclePackScenarios.enabledCannotMeanEmptyOrMiscalculatedAndDisabledCannotContainData()
    @Test fun enabledSystemIsReadOnlyWithExactCoverage() = VehiclePackScenarios.enabledSystemMustBeReadOnlyAndReportsExactCoverage()
    @Test fun cancellationNeverInstallsPartialData() = VehiclePackScenarios.cancelledDownloadNeverInstalls()
    @Test fun downloadedPathAndSelectionAreOneAtomicNamesOnlyTransaction() = VehiclePackScenarios.manualPathIsOneAtomicNamesOnlyCommit()
    @Test fun systemScopeAndSavedLiveChoicesNeverFallBackOrCrossVehicles() = VehiclePackScenarios.closedSystemScopeNeverMixesGlobalPidsAndSelections()
    @Test fun selectedSystemConnectionDoesNotLeakAcrossScopesOrUseLibraryFallbacks() = VehiclePackScenarios.connectionUsesOnlyTheManualPathAndCurrentSystemWithSparseOverrides()
    @Test fun pickerAndDownloadsCannotAutomaticallyChooseVehicleConfiguration() = VehiclePackScenarios.controllerDownloadsNeverSelectWithoutExplicitManualConfirmation()
    @Test fun explicitCarSelectionLoadsOnlyItsEnabledSystem() = VehiclePackScenarios.explicitCarSelectionLoadsOnlyItsEnabledSystem()
    @Test fun actionsBundleUsesTheProductionKotlinValidator() {
        val directory = System.getProperty("toyota.pack.directory") ?: return
        VehiclePackScenarios.generatedBundle(File(directory))
    }
}
