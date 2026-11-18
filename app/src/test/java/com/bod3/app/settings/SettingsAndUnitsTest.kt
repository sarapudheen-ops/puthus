package com.bod3.app.settings

import com.bod3.app.datapack.CustomPid
import com.bod3.app.live.LiveValueDef
import com.bod3.app.obd.connection.InMemoryKeyValueStore
import com.bod3.app.obd.pid.Pids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsAndUnitsTest {

    @Test
    fun settingsDefaultAndRoundTrip() {
        val kv = InMemoryKeyValueStore()
        val store = AppSettingsStore(kv)
        val defaults = store.load()
        assertEquals(AppSettings(), defaults)
        assertFalse(defaults.onboardingDone)
        assertTrue(defaults.units.isDefault)

        val changed = AppSettings(ThemeMode.DARK, keepScreenOn = true, units = UnitPreferences.IMPERIAL, onboardingDone = true)
        store.save(changed)
        assertEquals(changed, AppSettingsStore(kv).load())
        assertEquals(ThemeMode.LIGHT, store.update { it.copy(theme = ThemeMode.LIGHT) }.theme)
        assertEquals(ThemeMode.LIGHT, store.load().theme)
    }

    @Test
    fun unknownStoredValuesFallBackToDefaults() {
        val kv = InMemoryKeyValueStore()
        kv.put(AppSettingsStore.KEY_THEME, "NEON")
        kv.put(AppSettingsStore.KEY_PRESSURE, "atm")
        val loaded = AppSettingsStore(kv).load()
        assertEquals(ThemeMode.SYSTEM, loaded.theme)
        assertEquals(PressureUnit.KPA, loaded.units.pressure)
    }

    @Test
    fun metricIsUnchanged() {
        val prefs = UnitPreferences()
        listOf("°C", "km/h", "km", "kPa", "L/h", "Nm", "rpm", "%", "V").forEach { unit ->
            assertTrue(unit, Units.conversionFor(unit, prefs).isIdentity)
        }
        assertEquals("90 °C", Units.format(90.0, "°C", 0, prefs))
    }

    @Test
    fun imperialConversions() {
        val us = UnitPreferences.IMPERIAL
        assertEquals("194 °F", Units.format(90.0, "°C", 0, us))
        assertEquals("-40 °F", Units.format(-40.0, "°C", 0, us))
        assertEquals("62 mph", Units.format(100.0, "km/h", 0, us))
        assertEquals("62 mi", Units.format(100.0, "km", 0, us))
        assertEquals("14.5 psi", Units.format(100.0, "kPa", 0, us))
        assertEquals("2.6 gal/h", Units.format(10.0, "L/h", 0, us))
        assertEquals("74 lb·ft", Units.format(100.0, "Nm", 0, us))
        assertEquals("850 rpm", Units.format(850.0, "rpm", 0, us))
        assertEquals("17.40 psi", Units.format(1.2, "bar", 1, us))
    }

    @Test
    fun pressureCanUseBarIndependently() {
        val prefs = UnitPreferences(pressure = PressureUnit.BAR)
        assertEquals("1.01 bar", Units.format(101.0, "kPa", 0, prefs))
        assertTrue(Units.conversionFor("bar", prefs).isIdentity)
        assertEquals("1200 kPa", Units.format(12.0, "bar", 0, UnitPreferences()))
        assertTrue("temperature stays °C", Units.conversionFor("°C", prefs).isIdentity)
    }

    @Test
    fun displayDefinitionConvertsValueRangeAndUnit() {
        val coolant = LiveValueDef.Standard(Pids.all.first { it.pid == 0x05 })
        val shown = Units.display(coolant, UnitPreferences.IMPERIAL)
        assertEquals("°F", shown.unit)
        assertEquals(-40.0, shown.min, 0.001)
        assertEquals(coolant.key, shown.key)
        assertEquals(194.0, shown.convert(90.0), 0.001)
        assertEquals("194", shown.format(shown.convert(90.0)))

        val speed = Units.display(LiveValueDef.Standard(Pids.all.first { it.pid == 0x0D }), UnitPreferences.IMPERIAL)
        assertEquals("mph", speed.unit)
        assertTrue(speed.max < 255.0)

        val metric = Units.display(coolant, UnitPreferences())
        assertEquals("°C", metric.unit)
        assertEquals(coolant.format(90.0), metric.format(90.0))
    }

    @Test
    fun customPidUnitsAreConvertedToo() {
        val boost = LiveValueDef.Custom(CustomPid("boost", "Boost", "22F40C", "(A*256+B)/100", unit = "bar", min = 0.0, max = 3.0, decimals = 2))
        val psi = Units.display(boost, UnitPreferences(pressure = PressureUnit.PSI))
        assertEquals("psi", psi.unit)
        assertEquals(14.5, psi.convert(1.0), 0.01)
        assertEquals("14.504", psi.format(psi.convert(1.0)))
        val unknown = Units.display(LiveValueDef.Custom(CustomPid("x", "X", "22F40D", "A", unit = "steps")), UnitPreferences.IMPERIAL)
        assertEquals("steps", unknown.unit)
    }
}
