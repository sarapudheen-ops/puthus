package com.bod3.app.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime permission handling for Bluetooth Classic.
 *
 * - Android 12+ (API 31): `BLUETOOTH_CONNECT` is a runtime permission and is needed to list bonded
 *   devices, read their names and open RFCOMM sockets. We never scan, so `BLUETOOTH_SCAN` (and
 *   location) are not requested.
 * - Android 7-11: the legacy install-time `BLUETOOTH` permission (declared with maxSdkVersion 30) is enough.
 */
object BluetoothPermissions {

    val isRuntimePermissionRequired: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Permissions to request at runtime on this device; empty below Android 12. */
    @get:SuppressLint("InlinedApi")
    val runtimePermissions: Array<String>
        get() = if (isRuntimePermissionRequired) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()

    fun hasConnectPermission(context: Context): Boolean =
        runtimePermissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
}
