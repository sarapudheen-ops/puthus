package com.bod3.app.settings

import com.bod3.app.live.LiveValueDef
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** A linear unit change: shown = value × [factor] + [offset], with [extraDecimals] more decimals. */
data class UnitConversion(val from: String, val to: String, val factor: Double, val offset: Double = 0.0, val extraDecimals: Int = 0) {
    val isIdentity: Boolean get() = factor == 1.0 && offset == 0.0 && from == to
    fun apply(value: Double): Double = value * factor + offset

    companion object {
        fun identity(unit: String) = UnitConversion(unit, unit, 1.0)
    }
}

/**
 * Converts values from the units the vehicle reports (SAE J1979 / data packs: SI and metric) to the
 * user's preferred units. Units that are not recognised are shown unchanged.
 */
object Units {

    fun conversionFor(unit: String, prefs: UnitPreferences): UnitConversion {
        val u = unit.trim()
        val imperial = prefs.distance == DistanceUnit.IMPERIAL
        return when (u.lowercase(Locale.ROOT)) {
            "°c", "degc", "c" -> if (prefs.temperature == TemperatureUnit.FAHRENHEIT) UnitConversion(u, "°F", 1.8, 32.0) else UnitConversion.identity(u)
            "km/h", "kph" -> if (imperial) UnitConversion(u, "mph", KM_TO_MI) else UnitConversion.identity(u)
            "km" -> if (imperial) UnitConversion(u, "mi", KM_TO_MI) else UnitConversion.identity(u)
            "m" -> if (imperial) UnitConversion(u, "ft", M_TO_FT) else UnitConversion.identity(u)
            "l/h" -> if (imperial) UnitConversion(u, "gal/h", L_TO_USGAL, extraDecimals = 1) else UnitConversion.identity(u)
            "l" -> if (imperial) UnitConversion(u, "gal", L_TO_USGAL, extraDecimals = 1) else UnitConversion.identity(u)
            "nm" -> if (imperial) UnitConversion(u, "lb·ft", NM_TO_LBFT) else UnitConversion.identity(u)
            "g/s" -> UnitConversion.identity(u)
            "kpa" -> pressureFrom(u, 1.0, prefs)
            "bar" -> pressureFrom(u, 100.0, prefs)
            "mbar", "hpa" -> pressureFrom(u, 0.1, prefs)
            else -> UnitConversion.identity(u)
        }
    }

    private fun pressureFrom(unit: String, toKpa: Double, prefs: UnitPreferences): UnitConversion = when (prefs.pressure) {
        PressureUnit.KPA -> if (toKpa == 1.0) UnitConversion.identity(unit) else UnitConversion(unit, "kPa", toKpa)
        PressureUnit.BAR -> if (toKpa == 100.0) UnitConversion.identity(unit) else UnitConversion(unit, "bar", toKpa / 100.0, extraDecimals = 2)
        PressureUnit.PSI -> UnitConversion(unit, "psi", toKpa * KPA_TO_PSI, extraDecimals = 1)
    }

    /** [value] in [unit] formatted for display in the preferred units, e.g. `"194 °F"`. */
    fun format(value: Double, unit: String, decimals: Int, prefs: UnitPreferences): String {
        val c = conversionFor(unit, prefs)
        val text = formatNumber(c.apply(value), if (c.isIdentity) decimals else max(decimals, 0) + c.extraDecimals)
        return if (c.to.isBlank()) text else "$text ${c.to}"
    }

    fun formatNumber(value: Double, decimals: Int): String =
        if (decimals <= 0) value.roundToInt().toString() else "%.${decimals.coerceAtMost(6)}f".format(Locale.ROOT, value)

    /** A live value definition shown in the preferred units (same key and name). */
    fun display(def: LiveValueDef, prefs: UnitPreferences): DisplayValueDef = DisplayValueDef(def, conversionFor(def.unit, prefs))

    private const val KM_TO_MI = 0.621371
    private const val M_TO_FT = 3.28084
    private const val L_TO_USGAL = 0.264172
    private const val NM_TO_LBFT = 0.737562
    private const val KPA_TO_PSI = 0.145038
}

/**
 * [base] converted for display: [unit], [min] and [max] are in the preferred unit, and [format] takes a
 * value that was already converted with [convert].
 */
class DisplayValueDef(val base: LiveValueDef, val conversion: UnitConversion) {
    val key: String get() = base.key
    val name: String get() = base.name
    val unit: String get() = conversion.to
    val min: Double get() = minOf(conversion.apply(base.min), conversion.apply(base.max))
    val max: Double get() = maxOf(conversion.apply(base.min), conversion.apply(base.max))

    fun convert(value: Double): Double = conversion.apply(value)

    fun format(value: Double): String {
        if (conversion.isIdentity) return base.format(value)
        // Keep the base precision (derived from its formatting of a round number) plus the extra decimals.
        val baseDecimals = DECIMALS.find(base.format(1.0))?.groupValues?.get(1)?.length ?: 0
        return Units.formatNumber(value, baseDecimals + conversion.extraDecimals)
    }

    private companion object {
        val DECIMALS = Regex("[.,](\\d+)$")
    }
}
