package com.bod3.app.obd.uds

/** A UDS data identifier read with service 0x22. */
data class DidDefinition(val id: Int, val label: String) {
    val hex: String get() = "%04X".format(id)
}

/** Identification data identifiers read from every module (ISO 14229-1 Annex C, plus VW coding). */
object KnownDids {
    val SPARE_PART_NUMBER = DidDefinition(0xF187, "Spare part number")
    val SOFTWARE_NUMBER = DidDefinition(0xF188, "ECU software number")
    val SOFTWARE_VERSION = DidDefinition(0xF189, "Software version")
    val SUPPLIER = DidDefinition(0xF18A, "System supplier")
    val SERIAL_NUMBER = DidDefinition(0xF18C, "ECU serial number")
    val VIN = DidDefinition(0xF190, "VIN")
    val HARDWARE_NUMBER = DidDefinition(0xF191, "Hardware number")
    val SUPPLIER_HARDWARE_VERSION = DidDefinition(0xF193, "Supplier hardware version")
    val SUPPLIER_SOFTWARE_VERSION = DidDefinition(0xF195, "Supplier software version")
    val SYSTEM_NAME = DidDefinition(0xF197, "System name")
    val ODX_FILE = DidDefinition(0xF19E, "ODX file / coding data set")
    val VW_CODING = DidDefinition(0x0600, "Coding (VW long coding)")

    val default: List<DidDefinition> = listOf(
        SYSTEM_NAME, SPARE_PART_NUMBER, SOFTWARE_NUMBER, SOFTWARE_VERSION, HARDWARE_NUMBER,
        SUPPLIER_HARDWARE_VERSION, SUPPLIER_SOFTWARE_VERSION, SUPPLIER, SERIAL_NUMBER, VIN, ODX_FILE, VW_CODING,
    )

    /** Every standard identification DID of ISO 14229-1 (F180-F19E), read on the ECU detail screen. */
    val identification: List<DidDefinition> = listOf(
        DidDefinition(0xF180, "Boot software identification"),
        DidDefinition(0xF181, "Application software identification"),
        DidDefinition(0xF182, "Application data identification"),
        DidDefinition(0xF183, "Boot software fingerprint"),
        DidDefinition(0xF184, "Application software fingerprint"),
        DidDefinition(0xF185, "Application data fingerprint"),
        DidDefinition(0xF186, "Active diagnostic session"),
        SPARE_PART_NUMBER, SOFTWARE_NUMBER, SOFTWARE_VERSION, SUPPLIER,
        DidDefinition(0xF18B, "ECU manufacturing date"),
        SERIAL_NUMBER,
        DidDefinition(0xF18D, "Supported functional units"),
        DidDefinition(0xF18E, "Kit assembly part number"),
        VIN, HARDWARE_NUMBER,
        DidDefinition(0xF192, "Supplier hardware number"),
        SUPPLIER_HARDWARE_VERSION,
        DidDefinition(0xF194, "Supplier software number"),
        SUPPLIER_SOFTWARE_VERSION,
        DidDefinition(0xF196, "Exhaust regulation / type approval number"),
        SYSTEM_NAME,
        DidDefinition(0xF198, "Repair shop code / tester serial number"),
        DidDefinition(0xF199, "Programming date"),
        DidDefinition(0xF19D, "ECU installation date"),
        ODX_FILE, VW_CODING,
    )

    fun label(id: Int): String = (default + identification).firstOrNull { it.id == id }?.label ?: "Identifier %04X".format(id)
}

/** Outcome of reading one identifier. Negative responses are a normal result, not an error. */
sealed interface DidResult {
    data class Value(val data: List<Int>) : DidResult {
        val hex: String get() = data.toHex()
        val text: String? get() = DidText.decode(data)
    }

    data class Negative(val nrc: Int) : DidResult

    data object NoResponse : DidResult

    data class Failed(val message: String) : DidResult
}

data class DidReading(val did: Int, val result: DidResult, val definitionId: String? = null,
                      val definitionName: String? = null, val request: String? = null, val hexOnly: Boolean = false) {
    val label: String get() = definitionName ?: KnownDids.label(did)

    /** Human-readable value: decoded text, hex, or why it is missing. */
    val display: String
        get() = when (result) {
            is DidResult.Value -> (if (hexOnly) null else result.text) ?: result.hex.ifEmpty { "(empty)" }
            is DidResult.Negative ->
                if (Nrc.meansNotSupported(result.nrc)) "Not supported" else "Not available: ${Nrc.describe(result.nrc)}"
            DidResult.NoResponse -> "Not supported (no response)"
            is DidResult.Failed -> "Not read: ${result.message}"
        }
}

object DidText {
    /**
     * Returns the value as text when it is printable ASCII (identification strings usually are), with
     * padding (`00`, `FF`, spaces) trimmed. Returns null for binary data, which is then shown as hex.
     */
    fun decode(data: List<Int>): String? {
        val trimmed = data.dropLastWhile { it == 0x00 || it == 0xFF || it == 0x20 }.dropWhile { it == 0x00 || it == 0x20 }
        if (trimmed.isEmpty()) return null
        if (!trimmed.all { it in 0x20..0x7E }) return null
        return trimmed.map { it.toChar() }.joinToString("")
    }
}

fun List<Int>.toHex(separator: String = " "): String = joinToString(separator) { "%02X".format(it) }
