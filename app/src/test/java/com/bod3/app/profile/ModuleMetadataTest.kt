package com.bod3.app.profile

import org.junit.Test

class ModuleMetadataTest {
    @Test fun exactSourceReadoutRoundTrip() = ModuleMetadataScenarios.sourceReadoutRoundTripRetainsExactKeysLabelsStatesAndIdentificationRendering()
    @Test fun legacyAndUnknownSchemas() = ModuleMetadataScenarios.legacySavedReadoutsRemainCompatibleAndUnknownStatusSchemasStayOpaque()
}
