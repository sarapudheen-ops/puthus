package com.bod3.app.profile

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Turns a VIN into make / model / year. */
fun interface VinDescriber {
    suspend fun describe(vin: String): VehicleDescription
}

/**
 * Decodes VINs with the NHTSA vPIC API. Never throws: when the phone is offline, the request fails
 * or vPIC cannot decode the VIN, it falls back to [OfflineVinDecoder] and says why in
 * [VehicleDescription.note]. A missing year in the vPIC answer is filled in from the VIN.
 */
class VpicClient(
    private val currentYear: () -> Int,
    private val fetch: suspend (String) -> String = ::httpGet,
) : VinDescriber {

    override suspend fun describe(vin: String): VehicleDescription {
        val offline = { note: String -> OfflineVinDecoder.decode(vin, currentYear(), note) }
        if (vin.length != 17) return offline("Not a 17-character VIN")
        val body = try {
            fetch(urlFor(vin))
        } catch (e: IOException) {
            return offline("vPIC unreachable (${e.message ?: e.javaClass.simpleName})")
        } catch (e: SecurityException) {
            return offline("No network permission")
        }
        return try {
            val online = VpicParser.parse(body)
            if (online.year == null) online.copy(year = OfflineVinDecoder.modelYear(vin, currentYear())) else online
        } catch (e: VpicParser.VpicException) {
            offline(e.message ?: "vPIC could not decode this VIN")
        } catch (e: IllegalArgumentException) {
            offline("Unexpected vPIC response")
        }
    }

    companion object {
        const val BASE_URL = "https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValues/"

        fun urlFor(vin: String): String = BASE_URL + URLEncoder.encode(vin, "UTF-8") + "?format=json"

        suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.setRequestProperty("Accept", "application/json")
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
    }
}
