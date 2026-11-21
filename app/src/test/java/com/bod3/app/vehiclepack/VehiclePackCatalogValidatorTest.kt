package com.bod3.app.vehiclepack

import org.junit.Test

/** Focused link/category and write-proof guards exercised through the production validator. */
class VehiclePackCatalogValidatorTest {
    @Test fun provenExecutableCategoriesPassAndUnmarkedWritesStayUnverified() =
        VehiclePackScenarios.trustedConversionNotImportedFlags()

    @Test fun enabledUnprovenLinkFailsWithExplicitValidationError() =
        VehiclePackScenarios.unprovenEnabledSystemIsRejectedWithExplicitLinkError()
}
