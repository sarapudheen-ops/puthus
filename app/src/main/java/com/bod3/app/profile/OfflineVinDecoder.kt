package com.bod3.app.profile

/**
 * Best-effort VIN decoding without network access: manufacturer from the WMI (first three characters)
 * and model year from the 10th character. Used when vPIC cannot be reached.
 */
object OfflineVinDecoder {

    private const val YEAR_CODES = "ABCDEFGHJKLMNPRSTVWXY123456789"

    /** Common world manufacturer identifiers. Three-character entries take precedence over two. */
    private val manufacturers = mapOf(
        "1C" to "Chrysler", "1D" to "Dodge", "1F" to "Ford", "1G" to "General Motors", "1GC" to "Chevrolet",
        "1G1" to "Chevrolet", "1GT" to "GMC", "1HG" to "Honda", "1J" to "Jeep", "1L" to "Lincoln",
        "1N" to "Nissan", "1VW" to "Volkswagen", "2C" to "Chrysler", "2F" to "Ford", "2G" to "General Motors",
        "2HG" to "Honda", "2T" to "Toyota", "3F" to "Ford", "3G" to "General Motors", "3VW" to "Volkswagen",
        "4T" to "Toyota", "5N" to "Hyundai", "5Y" to "Toyota", "5YJ" to "Tesla", "JA" to "Isuzu",
        "JF" to "Subaru", "JH" to "Honda", "JM" to "Mazda", "JN" to "Nissan", "JS" to "Suzuki",
        "JT" to "Toyota", "KL" to "Daewoo / GM Korea", "KM" to "Hyundai", "KN" to "Kia", "LFV" to "FAW-Volkswagen",
        "LSV" to "SAIC Volkswagen", "MA" to "Mahindra", "MR" to "Toyota Thailand", "NM" to "Ford Turkey",
        "SAL" to "Land Rover", "SAJ" to "Jaguar", "SCC" to "Lotus", "TMB" to "Škoda", "TRU" to "Audi",
        "VF1" to "Renault", "VF3" to "Peugeot", "VF7" to "Citroën", "VSS" to "SEAT", "WAU" to "Audi",
        "WBA" to "BMW", "WBS" to "BMW M", "WDB" to "Mercedes-Benz", "WDD" to "Mercedes-Benz", "WF0" to "Ford Germany",
        "WMW" to "MINI", "WP0" to "Porsche", "WVW" to "Volkswagen", "WVG" to "Volkswagen", "W0L" to "Opel",
        "YV1" to "Volvo", "YS3" to "Saab", "ZAR" to "Alfa Romeo", "ZFA" to "Fiat", "ZFF" to "Ferrari",
    )

    fun manufacturer(vin: String): String? {
        val upper = vin.uppercase()
        if (upper.length < 3) return null
        return manufacturers[upper.substring(0, 3)] ?: manufacturers[upper.substring(0, 2)]
    }

    /**
     * Model year from position 10. The code repeats every 30 years; for North American VINs a letter in
     * position 7 means 2010 or later, a digit means 1980-2009. Otherwise the most recent year that is
     * not in the future is used.
     */
    fun modelYear(vin: String, currentYear: Int): Int? {
        val upper = vin.uppercase()
        if (upper.length != 17) return null
        val index = YEAR_CODES.indexOf(upper[9])
        if (index < 0) return null
        val early = 1980 + index
        val late = early + 30
        val position7 = upper[6]
        val lateIsPossible = late <= currentYear + 1
        return when {
            !lateIsPossible -> early
            upper[0] in '1'..'5' && position7.isDigit() -> early
            else -> late
        }
    }

    fun decode(vin: String, currentYear: Int, note: String? = null): VehicleDescription =
        VehicleDescription(
            make = manufacturer(vin),
            model = null,
            year = modelYear(vin, currentYear),
            source = DescriptionSource.OFFLINE,
            note = note,
        )
}
