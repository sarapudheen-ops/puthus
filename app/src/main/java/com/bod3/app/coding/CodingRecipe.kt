package com.bod3.app.coding

import com.bod3.app.actuator.ActuatorModule
import com.bod3.app.actuator.Applicability
import com.bod3.app.obd.session.WritePolicy
import com.bod3.app.obd.uds.SecurityAccessSpec

/**
 * How a coding value is stored. All three are read with `22 <DID>` and written back with
 * `2E <DID> <all bytes>`; they differ in how the value sits inside the identifier's data.
 */
enum class CodingMethod(val label: String) {
    /** Long coding (VW `0600`): one byte, selected bits ([CodingRecipe.mask]). */
    LONG_CODING("Long coding (byte/bit)"),

    /** Adaptation channel: a numeric value of 1-4 bytes (big-endian), optionally scaled for display. */
    ADAPTATION("Adaptation channel"),

    /** Raw identifier value: 1-4 bytes (or selected bits of one byte) chosen from a value map. */
    DID_VALUE("Identifier value (22/2E)"),
}

/** One entry of the value map, e.g. `1` → "On". */
data class CodingOption(val value: Long, val label: String)

/** Safety conditions for a write. Defaults follow the spec: battery ≥ 12.0 V and engine off. */
data class CodingPreconditions(
    val minVoltage: Double = 12.0,
    val allowEngineRunning: Boolean = false,
)

/**
 * One coding change in one module.
 *
 * The value lives at [byteIndex] of identifier [did]: either the bits of [mask] in that byte, or
 * [length] bytes big-endian from there. [options] maps values to names; without options any value
 * between [min] and [max] may be written. Display value = raw × [factor] + [offset] [unit].
 */
data class CodingRecipe(
    val id: String,
    val name: String,
    val description: String = "",
    val module: ActuatorModule,
    val method: CodingMethod,
    val did: Int,
    val byteIndex: Int,
    val mask: Int? = null,
    val length: Int = 1,
    val options: List<CodingOption> = emptyList(),
    val min: Long? = null,
    val max: Long? = null,
    val factor: Double = 1.0,
    val offset: Double = 0.0,
    val unit: String? = null,
    val session: Int = WritePolicy.EXTENDED_SESSION,
    val security: SecurityAccessSpec? = null,
    val preconditions: CodingPreconditions = CodingPreconditions(),
    val applicability: Applicability = Applicability(),
    val source: String? = null,
    val notes: String? = null,
    val verified: Boolean = false,
    val readService: Int = 0x22,
    val writeService: Int = 0x2E,
    val sourceRecords: List<String> = emptyList(),
    val defaultSession: Int = WritePolicy.DEFAULT_SESSION,
    val provenMarker: String? = null,
    val reviewHash: String? = null,
) {
    val identifierBytes: List<Int> get() = if (readService == 0x21) listOf(did) else listOf(did shr 8, did and 255)
    val readRequest: List<Int> get() = listOf(readService) + identifierBytes
    val field: CodingField get() = CodingField(byteIndex, length, mask)

    /** Largest raw value the field can hold. */
    val fieldMax: Long get() = this.field.maxValue

    fun optionLabel(value: Long): String? = options.firstOrNull { it.value == value }?.label

    /** Raw value → text for the UI: option label, or scaled number with unit. */
    fun describe(value: Long): String {
        optionLabel(value)?.let { return "$it ($value)" }
        if (factor == 1.0 && offset == 0.0) return value.toString() + (unit?.let { " $it" } ?: "")
        val scaled = value * factor + offset
        val text = if (scaled % 1.0 == 0.0) scaled.toLong().toString() else "%.3f".format(scaled).trimEnd('0').trimEnd('.')
        return text + (unit?.let { " $it" } ?: "") + " (raw $value)"
    }

    /** Why [value] cannot be written, or null when it is allowed. */
    fun valueProblem(value: Long): String? = when {
        value < 0 || value > fieldMax -> "Value $value does not fit the field (0-$fieldMax)"
        options.isNotEmpty() && options.none { it.value == value } -> "Value $value is not one of the recipe's options"
        options.isEmpty() && min != null && value < min -> "Value $value is below the minimum $min"
        options.isEmpty() && max != null && value > max -> "Value $value is above the maximum $max"
        else -> null
    }

    fun problems(): List<String> = buildList {
        if (!ID.matches(id)) add("id: use lowercase letters, digits, '.', '_' or '-' (got \"$id\")")
        if (name.isBlank()) add("name: must not be empty")
        addAll(module.problems().map { "module: $it" })
        val local = readService == 0x21 && writeService == 0x3B
        if (!(readService == 0x22 && writeService == 0x2E) && !(local && module.dialect == com.bod3.app.obd.kwp.DiagnosticDialect.KWP2000)) add("readService/writeService: needs 22/2E or explicit KWP 21/3B")
        if (local && did !in 0..255) add("did: local identifier must be one byte")
        if (did !in 0..0xFFFF) add("did: must be 0000-FFFF")
        if (did in PROTECTED_DIDS) add("did: %04X is an identification record and is never written".format(did))
        if (byteIndex !in 0..MAX_BYTE_INDEX) add("byte: must be 0-$MAX_BYTE_INDEX")
        if (length !in 1..4) add("length: must be 1-4 bytes")
        if (mask != null) {
            if (mask !in 1..0xFF) add("mask: must be a non-zero byte mask (01-FF)")
            if (length != 1) add("mask: only allowed with length 1")
        }
        if (method == CodingMethod.LONG_CODING && mask == null) add("mask: long coding needs a bit mask, e.g. \"01\"")
        if (!WritePolicy.isAllowed(com.bod3.app.obd.session.WritePurpose.CODING, listOf(0x10, session), module.dialect)) add("session: unsupported session")
        if (!WritePolicy.isAllowed(com.bod3.app.obd.session.WritePurpose.CODING, listOf(0x10, defaultSession), module.dialect)) add("defaultSession: unsupported session")
        security?.problems()?.forEach { add("securityAccess: $it") }
        if (preconditions.minVoltage !in 9.0..16.0) add("preconditions.minVoltage: must be 9-16 V")
        if (factor == 0.0) add("factor: must not be 0")
        val fieldOk = length in 1..4 && (mask == null || mask in 1..0xFF)
        if (fieldOk) {
            options.forEachIndexed { i, o ->
                if (o.label.isBlank()) add("values[$i].label: must not be empty")
                if (o.value !in 0..fieldMax) add("values[$i].value: ${o.value} does not fit the field (0-$fieldMax)")
            }
            options.groupBy { it.value }.filterValues { it.size > 1 }.keys.forEach { add("values: value $it is listed more than once") }
            if (min != null && min !in 0..fieldMax) add("min: must be 0-$fieldMax")
            if (max != null && max !in 0..fieldMax) add("max: must be 0-$fieldMax")
            if (min != null && max != null && min > max) add("min: must not be greater than max")
            if (method == CodingMethod.DID_VALUE && options.isEmpty()) add("values: an identifier value needs a value map")
        }
    }

    companion object {
        const val MAX_BYTE_INDEX = 255
        val ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        private val HEX_ID = Regex("[0-9A-F]{3}|[0-9A-F]{8}")

        /** ISO 14229 identification records (F180-F19F) except repair-shop code and programming date. */
        val PROTECTED_DIDS: Set<Int> = (0xF180..0xF19F).toSet() - setOf(0xF198, 0xF199)
    }
}

/** Where a value sits in an identifier's data. */
data class CodingField(val byteIndex: Int, val length: Int = 1, val mask: Int? = null) {
    private val shift: Int get() = mask?.let { Integer.numberOfTrailingZeros(it) } ?: 0

    val maxValue: Long get() = mask?.let { ((it shr shift).toLong()) } ?: ((1L shl (8 * length)) - 1)

    /** Bytes of the identifier this field occupies. */
    val bytes: IntRange get() = byteIndex until byteIndex + length

    fun fits(data: List<Int>): Boolean = byteIndex + length <= data.size

    /** Reads the raw value from [data]; throws when the data is too short. */
    fun read(data: List<Int>): Long {
        require(fits(data)) { "The identifier has ${data.size} byte(s); byte ${byteIndex + length - 1} is outside it" }
        return if (mask != null) {
            ((data[byteIndex] and mask) shr shift).toLong()
        } else {
            bytes.fold(0L) { acc, i -> (acc shl 8) or data[i].toLong() }
        }
    }

    /** Returns [data] with [value] stored in this field; every other bit is left as it was. */
    fun write(data: List<Int>, value: Long): List<Int> {
        require(fits(data)) { "The identifier has ${data.size} byte(s); byte ${byteIndex + length - 1} is outside it" }
        require(value in 0..maxValue) { "Value $value does not fit the field (0-$maxValue)" }
        val out = data.toMutableList()
        if (mask != null) {
            out[byteIndex] = (data[byteIndex] and mask.inv() and 0xFF) or ((value.toInt() shl shift) and mask)
        } else {
            for (k in 0 until length) out[byteIndex + k] = ((value shr (8 * (length - 1 - k))) and 0xFF).toInt()
        }
        return out
    }
}
