package com.bod3.app.diagnostics

import kotlinx.coroutines.runBlocking
import org.junit.Test

/** This is a schema/runtime test with synthetic ECUs, not recovered Toyota coverage. */
class EcuReadDefinitionsTest {
    @Test fun roundTripsAndVersionGates() = EcuDefinitionScenarios.roundTripsAndVersionGates()
    @Test fun invalidDefinitionsRejectWholeDocument() = EcuDefinitionScenarios.invalidDefinitionsAreAtomic()
    @Test fun explicitReplyWindowsAndFaultLayouts() = EcuDefinitionScenarios.exactDataWindowsAndFaultLayouts()
    @Test fun kwpReadsAndExactOneShotClear() = runBlocking { EcuDefinitionScenarios.kwpReadsAndExactOneShotClear() }
    @Test fun nativeFaultVariantsRemainExactAndClearNeedsSameEcuProof() = runBlocking { EcuDefinitionScenarios.native12And13ReadsAnd04ClearNeverBecome18Or14OrBroadcast() }
    @Test fun explicitEmptyDefinitionsNeverFallBack() = runBlocking { EcuDefinitionScenarios.explicitEmptyDefinitionsNeverFallBack() }
    @Test fun kwpOverCanAndUdsUseOnlySelectedDefinitions() = runBlocking { EcuDefinitionScenarios.kwpOverCanAndUdsUseOnlySelectedDefinitions() }
    @Test fun malformedPositiveAndNegativeRepliesFailClosed() = runBlocking { EcuDefinitionScenarios.malformedPositiveAndNrcNeverBecomeSuccess() }
}
