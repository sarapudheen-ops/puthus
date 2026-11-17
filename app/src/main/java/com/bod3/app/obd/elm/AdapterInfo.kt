package com.bod3.app.obd.elm

/** Parsed `ATI` reply, e.g. `ELM327 v1.5` -> chip `ELM327`, version `1.5`. */
data class AdapterIdentity(val raw: String, val chip: String?, val version: String?) {
    companion object {
        private val pattern = Regex("([A-Za-z][A-Za-z0-9-]*)\\s+v?(\\d+(?:\\.\\d+)*[A-Za-z]?)")

        fun parse(text: String): AdapterIdentity {
            val trimmed = text.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: ""
            val match = pattern.find(trimmed)
            return AdapterIdentity(
                raw = trimmed,
                chip = match?.groupValues?.get(1),
                version = match?.groupValues?.get(2),
            )
        }
    }
}

/**
 * Vendor extensions detected on the adapter.
 *
 * - **VT** commands (e.g. `VTVERS`) are Vgate/vLinker extensions found on MIC3313-based adapters.
 * - **ST** commands (e.g. `STI`) are OBDLink/STN extensions.
 *
 * Until detection has run, both are considered unsupported and [AdapterDriver] refuses to send them.
 */
data class AdapterCapabilities(val vtVersion: String?, val stVersion: String?) {
    val supportsVt: Boolean get() = vtVersion != null
    val supportsSt: Boolean get() = stVersion != null

    companion object {
        val NONE = AdapterCapabilities(vtVersion = null, stVersion = null)

        /**
         * Interprets the reply to a capability probe (`VTVERS`, `STI`) conservatively: only a clean,
         * non-empty text reply counts as support. `?`, any adapter error, or a bare `OK` (some clones
         * acknowledge everything) all mean "not supported".
         */
        fun interpretProbe(response: ElmResponse): String? {
            if (!response.isSuccess) return null
            val text = response.lines.joinToString(" ").trim()
            if (text.isEmpty() || text.equals("OK", ignoreCase = true)) return null
            return text
        }
    }
}

/** Everything learned about the adapter during [AdapterDriver.initialize]. */
data class AdapterInfo(
    val identity: AdapterIdentity,
    val description: String?,
    val capabilities: AdapterCapabilities,
)

object VoltageParser {
    private val pattern = Regex("(\\d{1,2}(?:\\.\\d{1,2})?)\\s*V", RegexOption.IGNORE_CASE)

    /** Parses an `ATRV` reply such as `12.6V`. Returns null for anything that is not a plausible voltage. */
    fun parse(text: String): Double? {
        val value = pattern.find(text)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        return value.takeIf { it in 0.0..40.0 }
    }
}
