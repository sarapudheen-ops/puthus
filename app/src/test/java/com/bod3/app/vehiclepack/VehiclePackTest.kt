package com.bod3.app.vehiclepack

import java.io.File
import org.junit.Test

class VehiclePackTest {
    @Test fun boundedGzipAndChecksum() = VehiclePackScenarios.boundedGzip()
    @Test fun exactHttpRangeAndRedirectContract() = VehiclePackScenarios.rangeHttpContract()
    @Test fun onlyChosenPathIsFetchedAndThenWorksOffline() = VehiclePackScenarios.onlySelectedPathAndCache()
    @Test fun onlyPinnedConversionMayEnableWriteDefinitions() = VehiclePackScenarios.trustedConversionNotImportedFlags()
    @Test fun crossEcuDefinitionIsRejectedAtomically() = VehiclePackScenarios.crossEcuDefinitionRejected()
    @Test fun convertedEcuCannotSubstituteLegacyGenericReads() = VehiclePackScenarios.convertedEcuCannotSubstituteLegacyGenericReads()
    @Test fun convertedPrerequisitesNeedTheirOwnProvenanceAndNeverGrantImportTrust() = VehiclePackScenarios.convertedPrerequisitesMustCarryTheirOwnProvenance()
    @Test fun cancellationNeverInstallsPartialData() = VehiclePackScenarios.cancelledDownloadNeverInstalls()
    @Test fun downloadedPathAndSelectionAreOneAtomicNamesOnlyTransaction() = VehiclePackScenarios.manualPathIsOneAtomicNamesOnlyCommit()
    @Test fun systemScopeAndSavedLiveChoicesNeverFallBackOrCrossVehicles() = VehiclePackScenarios.closedSystemScopeNeverMixesGlobalPidsAndSelections()
    @Test fun selectedSystemConnectionDoesNotLeakAcrossScopesOrUseLibraryFallbacks() = VehiclePackScenarios.connectionUsesOnlyTheManualPathAndCurrentSystemWithSparseOverrides()
    @Test fun pickerAndDownloadsCannotAutomaticallyChooseVehicleConfiguration() = VehiclePackScenarios.controllerDownloadsNeverSelectWithoutExplicitManualConfirmation()
    @Test fun actionsBundleUsesTheProductionKotlinValidator() {
        val directory = System.getProperty("toyota.pack.directory") ?: return
        VehiclePackScenarios.generatedBundle(File(directory))
    }
}
