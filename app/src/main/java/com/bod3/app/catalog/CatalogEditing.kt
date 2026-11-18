package com.bod3.app.catalog

import com.bod3.app.profile.JsonWriter
import com.bod3.app.profile.MiniJson

/** Sections share one validator in the UI and in file imports. */
enum class CatalogSection(val label: String, val key: String) {
    ECUS("ECUs", "ecus"), PIDS("PIDs", "pids"), DTCS("DTC text", "dtcs"),
    ACTUATORS("Actuator tests", "actuators"), CODING("Coding recipes", "coding"),
    CONNECTION("Connection settings", "connection"), SECURITY("Security access", "security"), MODULE_NAMES("Module names", "moduleNames"),
}

object CatalogEditing {
    /** Replace only the section being edited, retaining other data and unrelated exclusions. */
    fun applySection(previous: CatalogData, section: CatalogSection, json: String): CatalogData {
        val raw = MiniJson.parse(json) as? Map<*, *> ?: throw IllegalArgumentException("Definitions must be a JSON object")
        val fields = setOf(section.key) + if (section == CatalogSection.DTCS) setOf("dtcGroups") else emptySet()
        require(raw.keys.all { it in fields || it == "exclude" }) { "This editor changes only ${fields.joinToString()} and their exclusions" }
        require(section.key in raw) { "${section.key}: missing section" }
        val exclusions = raw["exclude"]?.let { value ->
            val map = value as? Map<*, *> ?: throw IllegalArgumentException("exclude: must be an object")
            require(map.keys.all { it in fields && section != CatalogSection.CONNECTION }) { "exclude: only this section's keys can be changed" }
            map.entries.associate { it.key.toString() to it.value }
        }
        val original = CatalogCodec.dataMap(previous)
        val patch = raw.entries.associate { it.key.toString() to it.value }
        val oldExclusions = (original["exclude"] as Map<*, *>).entries.associate { it.key.toString() to it.value }
        val merged = if (exclusions == null) original + (patch - "exclude") else original + patch + ("exclude" to oldExclusions + exclusions)
        return CatalogCodec.dataOrThrow(JsonWriter.write(merged))
    }

    /** Intentionally invalid until filled from documentation. No guessed addresses, DIDs or payloads. */
    fun blank(section: CatalogSection): Map<String, Any?> {
        val module = mapOf("name" to "", "requestId" to "", "responseId" to "")
        return when (section) {
            CatalogSection.ACTUATORS -> mapOf("id" to "", "name" to "", "description" to "", "module" to module,
                "start" to "", "stop" to "", "safetyNotes" to listOf("Unverified; confirm documented requests and safety precautions."), "verified" to false)
            CatalogSection.CODING -> mapOf("id" to "", "name" to "", "description" to "", "module" to module,
                "method" to "did_value", "did" to "", "byte" to 0, "length" to 1,
                "values" to listOf(mapOf("value" to "", "label" to "")),
                "notes" to "Unverified; fill the documented field and value map. Back up before writing.", "verified" to false)
            CatalogSection.SECURITY -> mapOf("id" to "", "name" to "", "level" to "", "algorithm" to "STATIC_KEY", "constant" to "", "verified" to false)
            CatalogSection.PIDS -> mapOf("id" to "", "name" to "", "request" to "", "formula" to "A")
            CatalogSection.MODULE_NAMES -> mapOf("name" to "", "requestId" to "")
            else -> emptyMap()
        }
    }
}
