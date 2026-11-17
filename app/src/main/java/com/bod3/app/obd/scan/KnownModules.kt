package com.bod3.app.obd.scan

/** Generic names for well-known diagnostic addresses, used when a module does not report its own name. */
object KnownModules {
    private val names11 = mapOf(
        "7E0" to "Engine (ECM)",
        "7E1" to "Transmission (TCM)",
        "7E2" to "Hybrid / EV controller",
        "7E3" to "OBD module 7E3",
        "7E4" to "Battery / chassis",
        "7E5" to "OBD module 7E5",
        "7E6" to "OBD module 7E6",
        "7E7" to "OBD module 7E7",
    )

    private val names29 = mapOf(
        "10" to "Engine (ECM)",
        "11" to "Engine #2",
        "17" to "Instrument / gateway",
        "18" to "Transmission (TCM)",
        "19" to "Transmission #2",
        "28" to "Brakes (ABS)",
    )

    fun name(address: ModuleAddress): String? = when (address.bus) {
        Bus.CAN_11 -> names11[address.requestHeader]
        Bus.CAN_29 -> names29[address.requestHeader.substring(4, 6)]
    }

    /**
     * Picks the best name for a module: its own system name (F197), the OBD ECU name (09 0A), or a
     * generic name for its address.
     */
    fun resolve(address: ModuleAddress, systemName: String?, obdEcuName: String?): String? =
        systemName?.takeIf { it.isNotBlank() }
            ?: obdEcuName?.takeIf { it.isNotBlank() }
            ?: name(address)
}
