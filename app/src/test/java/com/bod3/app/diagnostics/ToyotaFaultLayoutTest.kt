package com.bod3.app.diagnostics

import kotlinx.coroutines.runBlocking
import org.junit.Test

class ToyotaFaultLayoutTest {
    @Test fun nativeStateVectors() = ToyotaFaultScenarios.everyNativeStateMaskVectorMatchesTheNativeDigest()
    @Test fun nativeRecordVectors() = ToyotaFaultScenarios.everyNativeAssemblyVectorMatchesTheNativeDigest()
    @Test fun nativeP5RecordVectors() = ToyotaFaultScenarios.everyNativeP5ProjectionVectorMatchesTheNativeDigest()
    @Test fun strictP5SchemaAndWorkbench() = runBlocking { ToyotaFaultScenarios.p5SchemaAndWorkbenchKeepSourceOriginAndMaskWithoutP3P4Rules() }
    @Test fun sourceLabelIsolation() = ToyotaFaultScenarios.exactLabelsAndSourceStatesNeverBecomeGenericOrOtherSystemCodes()
    @Test fun sourceFallbackVectors() = ToyotaFaultScenarios.sourceNumericFallbackMatchesNativeWidthVectorsWithoutLosingTheKey()
    @Test fun exactKeyByteMergeVectors() = ToyotaFaultScenarios.exactKeyByteMergesMatchAllNativeVectorsAndHardenCountWrap()
    @Test fun strictNativeSchema() = ToyotaFaultScenarios.nativeSchemaRoundTripsAndRejectsMalformedImportsAtomically()
    @Test fun truncationAndCountsFailAtomically() = ToyotaFaultScenarios.partialReplyAndInvalidCountsNeverReturnAnyFaults()
    @Test fun selectedWorkbenchDecoder() = runBlocking { ToyotaFaultScenarios.workbenchUsesTheExactSelectedNativeLayoutOnly() }
}
