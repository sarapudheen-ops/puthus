package com.bod3.app.datapack

/**
 * Manufacturer text must follow the loaded vehicle, not a global ECU-list selection left over from
 * another car. Only a known profile make, or a vehicle explicitly linked to that profile, is evidence.
 * A decoded profile make takes precedence over a conflicting linked list. Unknown stays generic.
 */
object DtcReferenceContext {
    fun make(profileMake: String?, linkedVehicleMake: String?): String? =
        profileMake?.trim()?.takeIf { it.isNotEmpty() }
            ?: linkedVehicleMake?.trim()?.takeIf { it.isNotEmpty() }
}
