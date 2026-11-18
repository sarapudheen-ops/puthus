package com.bod3.app.settings

import com.bod3.app.obd.connection.KeyValueStore

enum class ThemeMode(val label: String) { SYSTEM("Follow system"), LIGHT("Light"), DARK("Dark") }

enum class TemperatureUnit(val label: String, val symbol: String) { CELSIUS("Celsius (°C)", "°C"), FAHRENHEIT("Fahrenheit (°F)", "°F") }

/** Distance and everything derived from it: speed, fuel rate, torque. */
enum class DistanceUnit(val label: String) { METRIC("Metric (km, km/h, L, Nm)"), IMPERIAL("Imperial US (mi, mph, gal, lb·ft)") }

enum class PressureUnit(val label: String, val symbol: String) { KPA("kPa", "kPa"), BAR("bar", "bar"), PSI("psi", "psi") }

/** How values are shown. Recordings (CSV) and data packs always stay in the original (SI/metric) units. */
data class UnitPreferences(
    val temperature: TemperatureUnit = TemperatureUnit.CELSIUS,
    val distance: DistanceUnit = DistanceUnit.METRIC,
    val pressure: PressureUnit = PressureUnit.KPA,
) {
    val isDefault: Boolean get() = this == UnitPreferences()

    companion object {
        val IMPERIAL = UnitPreferences(TemperatureUnit.FAHRENHEIT, DistanceUnit.IMPERIAL, PressureUnit.PSI)
    }
}

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val keepScreenOn: Boolean = false,
    val units: UnitPreferences = UnitPreferences(),
    /** False until the user finished (or skipped) the introduction. */
    val onboardingDone: Boolean = false,
)

/** App-wide preferences (not connection settings). Unknown stored values fall back to the defaults. */
class AppSettingsStore(private val store: KeyValueStore) {

    fun load(): AppSettings = AppSettings(
        theme = enumOr(KEY_THEME, ThemeMode.SYSTEM),
        keepScreenOn = store.get(KEY_KEEP_SCREEN_ON) == "true",
        units = UnitPreferences(
            temperature = enumOr(KEY_TEMPERATURE, TemperatureUnit.CELSIUS),
            distance = enumOr(KEY_DISTANCE, DistanceUnit.METRIC),
            pressure = enumOr(KEY_PRESSURE, PressureUnit.KPA),
        ),
        onboardingDone = store.get(KEY_ONBOARDING_DONE) == "true",
    )

    fun save(settings: AppSettings) {
        store.put(KEY_THEME, settings.theme.name)
        store.put(KEY_KEEP_SCREEN_ON, settings.keepScreenOn.toString())
        store.put(KEY_TEMPERATURE, settings.units.temperature.name)
        store.put(KEY_DISTANCE, settings.units.distance.name)
        store.put(KEY_PRESSURE, settings.units.pressure.name)
        store.put(KEY_ONBOARDING_DONE, settings.onboardingDone.toString())
    }

    fun update(change: (AppSettings) -> AppSettings): AppSettings = change(load()).also(::save)

    private inline fun <reified E : Enum<E>> enumOr(key: String, default: E): E =
        store.get(key)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    companion object {
        const val KEY_THEME = "app.theme"
        const val KEY_KEEP_SCREEN_ON = "app.keepScreenOn"
        const val KEY_TEMPERATURE = "app.units.temperature"
        const val KEY_DISTANCE = "app.units.distance"
        const val KEY_PRESSURE = "app.units.pressure"
        const val KEY_ONBOARDING_DONE = "app.onboardingDone"
    }
}
