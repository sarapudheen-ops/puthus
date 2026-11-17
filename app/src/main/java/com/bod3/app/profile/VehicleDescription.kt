package com.bod3.app.profile

enum class DescriptionSource { VPIC, OFFLINE }

/** Make / model / year for a VIN. */
data class VehicleDescription(
    val make: String?,
    val model: String?,
    val year: Int?,
    val source: DescriptionSource,
    val extra: Map<String, String> = emptyMap(),
    /** Why the online lookup did not happen or failed, when [source] is [DescriptionSource.OFFLINE]. */
    val note: String? = null,
) {
    /** e.g. "2005 Dodge Durango", or null when nothing is known. */
    val title: String?
        get() = listOfNotNull(year?.toString(), make?.titleCase(), model).joinToString(" ").ifBlank { null }
}

/** "MERCEDES-BENZ" -> "Mercedes-Benz", "DODGE" -> "Dodge"; short all-caps words such as "BMW" stay as they are. */
fun String.titleCase(): String {
    val builder = StringBuilder(length)
    var word = StringBuilder()
    fun flush() {
        val w = word.toString()
        builder.append(if (w.length <= 3 && w.all { it.isUpperCase() || it.isDigit() }) w else w.lowercase().replaceFirstChar { it.uppercaseChar() })
        word = StringBuilder()
    }
    for (c in this) {
        if (c.isLetterOrDigit()) {
            word.append(c)
        } else {
            flush()
            builder.append(c)
        }
    }
    flush()
    return builder.toString()
}

/** Parses the NHTSA vPIC `DecodeVinValues` response. */
object VpicParser {

    class VpicException(message: String) : Exception(message)

    private val extraFields = listOf("Trim", "BodyClass", "DisplacementL", "FuelTypePrimary", "PlantCountry", "Manufacturer")

    fun parse(json: String): VehicleDescription {
        val root = MiniJson.parse(json) as? Map<*, *> ?: throw VpicException("Unexpected vPIC response")
        val results = root["Results"] as? List<*> ?: throw VpicException("vPIC response has no Results")
        val values = results.firstOrNull() as? Map<*, *> ?: throw VpicException("vPIC returned no data")

        fun field(name: String): String? = (values[name] as? String)?.trim()?.takeIf { it.isNotEmpty() && it != "Not Applicable" }

        val make = field("Make")
        val model = field("Model")
        val year = field("ModelYear")?.toIntOrNull()
        if (make == null && model == null && year == null) {
            throw VpicException(field("ErrorText") ?: "vPIC could not decode this VIN")
        }
        return VehicleDescription(
            make = make,
            model = model,
            year = year,
            source = DescriptionSource.VPIC,
            extra = extraFields.mapNotNull { name -> field(name)?.let { name to it } }.toMap(),
        )
    }
}
