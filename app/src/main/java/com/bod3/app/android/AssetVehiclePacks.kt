package com.bod3.app.android

import android.content.Context
import com.bod3.app.vehiclepack.PackHttpSource
import com.bod3.app.vehiclepack.PackObjectStore
import com.bod3.app.vehiclepack.VehiclePackCatalog
import com.bod3.app.vehiclepack.VehiclePackIndex
import java.io.File

/** Only the small build-generated hash-tree index is in the APK. No downloaded index is trusted. */
object AssetVehiclePacks {
    fun load(context: Context): VehiclePackCatalog {
        val app = context.applicationContext
        val raw = app.assets.open("toyota-vehicle-packs/index.json").use { input ->
            val bytes = input.readBytesBounded(VehiclePackIndex.MAX_INDEX_BYTES)
            bytes.toString(Charsets.UTF_8)
        }
        return VehiclePackCatalog(VehiclePackIndex.fromBundledJson(raw),
            PackObjectStore(File(app.noBackupFilesDir, "toyota-vehicle-objects"), PackHttpSource()))
    }

    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer, 0, minOf(buffer.size, limit + 1 - result.size()))
            if (count < 0) break
            require(count > 0 && result.size() + count <= limit) { "Oversized bundled vehicle-pack index" }
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }
}
