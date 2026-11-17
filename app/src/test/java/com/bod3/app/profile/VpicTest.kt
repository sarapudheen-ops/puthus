package com.bod3.app.profile

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VpicTest {

    private val sample = """
        {"Count":1,"Message":"Results returned successfully. NOTE: Any missing decoded values should be interpreted as NHTSA does not have data on the specific variable.","SearchCriteria":"VIN:1D4GP24R45B123456",
         "Results":[{"ABS":"","BodyClass":"Sport Utility Vehicle (SUV)/Multi-Purpose Vehicle (MPV)","DisplacementL":"4.7",
         "ErrorCode":"0","ErrorText":"0 - VIN decoded clean. Check Digit (9th position) is correct","FuelTypePrimary":"Gasoline",
         "Make":"DODGE","Manufacturer":"CHRYSLER CORPORATION LLC","Model":"Durango","ModelYear":"2005","PlantCountry":"UNITED STATES (USA)",
         "Trim":"SLT","Note":"Caf\u00e9 \"quoted\"","Series":null}]}
    """.trimIndent()

    @Test
    fun parsesDecodeVinValues() {
        val d = VpicParser.parse(sample)
        assertEquals("DODGE", d.make)
        assertEquals("Durango", d.model)
        assertEquals(2005, d.year)
        assertEquals(DescriptionSource.VPIC, d.source)
        assertEquals("SLT", d.extra["Trim"])
        assertEquals("4.7", d.extra["DisplacementL"])
        assertEquals("2005 Dodge Durango", d.title)
    }

    @Test
    fun undecodableVinIsAnError() {
        val json = """{"Count":1,"Results":[{"Make":"","Model":"","ModelYear":"","ErrorText":"11 - Incorrect Model Year"}]}"""
        val e = assertThrows(VpicParser.VpicException::class.java) { VpicParser.parse(json) }
        assertEquals("11 - Incorrect Model Year", e.message)
        assertThrows(VpicParser.VpicException::class.java) { VpicParser.parse("""{"Results":[]}""") }
    }

    @Test
    fun clientUsesVpicWhenOnline() = runBlocking<Unit> {
        var url: String? = null
        val client = VpicClient(currentYear = { 2026 }, fetch = { url = it; sample })
        val d = client.describe("1D4GP24R45B123456")
        assertEquals("https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValues/1D4GP24R45B123456?format=json", url)
        assertEquals(DescriptionSource.VPIC, d.source)
        assertEquals("Durango", d.model)
    }

    @Test
    fun clientFallsBackOfflineWhenNetworkFails() = runBlocking<Unit> {
        val client = VpicClient(currentYear = { 2026 }, fetch = { throw IOException("Unable to resolve host") })
        val d = client.describe("WVWZZZ1KZAW000001")
        assertEquals(DescriptionSource.OFFLINE, d.source)
        assertEquals("Volkswagen", d.make)
        assertEquals(2010, d.year)
        assertNull(d.model)
        assertTrue(d.note!!.contains("Unable to resolve host"))
        assertEquals("2010 Volkswagen", d.title)
    }

    @Test
    fun clientFallsBackOnGarbageResponse() = runBlocking<Unit> {
        val client = VpicClient(currentYear = { 2026 }, fetch = { "<html>maintenance</html>" })
        assertEquals(DescriptionSource.OFFLINE, client.describe("1D4GP24R45B123456").source)
    }

    @Test
    fun offlineModelYear() {
        assertEquals(2005, OfflineVinDecoder.modelYear("1D4GP24R45B123456", 2026)) // position 7 digit -> 1980-2009
        assertEquals(2015, OfflineVinDecoder.modelYear("1FTFW1EG4FFA00001", 2026)) // position 7 letter -> 2010+
        assertEquals(2024, OfflineVinDecoder.modelYear("WBA12345XR0000001", 2026))
        assertEquals(2025, OfflineVinDecoder.modelYear("WBA12345XS0000001", 2026)) // S = 1995 or 2025: most recent
        assertEquals(1999, OfflineVinDecoder.modelYear("WBA12345XX0000001", 2026)) // X = 1999 or 2029: 2029 is future
        assertNull(OfflineVinDecoder.modelYear("SHORT", 2026))
    }

    @Test
    fun manufacturers() {
        assertEquals("BMW", OfflineVinDecoder.manufacturer("WBA12345XR0000001"))
        assertEquals("Dodge", OfflineVinDecoder.manufacturer("1D4GP24R45B123456"))
        assertNull(OfflineVinDecoder.manufacturer("ZZZ"))
        assertEquals("Mercedes-Benz", "MERCEDES-BENZ".titleCase())
        assertEquals("BMW", "BMW".titleCase())
        assertEquals("Land Rover", "LAND ROVER".titleCase())
    }

    @Test
    fun miniJson() {
        val value = MiniJson.parse("""{"a":[1,2.5,-3e2,true,false,null],"b":{"c":"x\ny\u0041"}}""") as Map<*, *>
        assertEquals(listOf(1.0, 2.5, -300.0, true, false, null), value["a"])
        assertEquals("x\nyA", (value["b"] as Map<*, *>)["c"])
        assertThrows(MiniJson.ParseException::class.java) { MiniJson.parse("{\"a\":}") }
        assertThrows(MiniJson.ParseException::class.java) { MiniJson.parse("[1,2") }
        assertEquals("""{"k":"a\"b\\","n":[1,null,true]}""", JsonWriter.write(mapOf("k" to "a\"b\\", "n" to listOf(1, null, true))))
    }
}
