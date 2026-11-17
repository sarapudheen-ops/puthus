package com.bod3.app.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context

/** A bonded Bluetooth device the user can pick as their OBD adapter. */
data class PairedAdapter(val name: String, val address: String, val looksLikeObd: Boolean)

/**
 * Access to the Bluetooth adapter and bonded devices. Every method that needs `BLUETOOTH_CONNECT`
 * checks [BluetoothPermissions.hasConnectPermission] first and degrades to an empty/null result.
 */
class BluetoothRepository(private val context: Context) {

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    val isAvailable: Boolean get() = adapter != null

    val hasPermission: Boolean get() = BluetoothPermissions.hasConnectPermission(context)

    val isEnabled: Boolean
        get() = try {
            adapter?.isEnabled == true
        } catch (e: SecurityException) {
            false
        }

    @SuppressLint("MissingPermission") // guarded by hasPermission
    fun pairedAdapters(): List<PairedAdapter> {
        val bluetooth = adapter ?: return emptyList()
        if (!hasPermission || !isEnabled) return emptyList()
        val devices = try {
            bluetooth.bondedDevices.orEmpty()
        } catch (e: SecurityException) {
            return emptyList()
        }
        return devices
            .map { device ->
                val name = device.name?.takeIf { it.isNotBlank() } ?: device.address
                PairedAdapter(name = name, address = device.address, looksLikeObd = looksLikeObd(name))
            }
            .sortedWith(compareByDescending<PairedAdapter> { it.looksLikeObd }.thenBy { it.name.lowercase() })
    }

    fun device(address: String): BluetoothDevice? {
        val bluetooth = adapter ?: return null
        return if (BluetoothAdapter.checkBluetoothAddress(address)) bluetooth.getRemoteDevice(address) else null
    }

    private fun looksLikeObd(name: String): Boolean {
        val lower = name.lowercase()
        return OBD_NAME_HINTS.any { lower.contains(it) }
    }

    private companion object {
        val OBD_NAME_HINTS = listOf("obd", "elm", "vlink", "v-link", "vgate", "icar", "mic3313", "konnwei", "veepeak", "scan")
    }
}
