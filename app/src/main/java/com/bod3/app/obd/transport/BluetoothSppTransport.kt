package com.bod3.app.obd.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bluetooth Classic RFCOMM link using the Serial Port Profile, which is how ELM327/MIC3313 adapters
 * expose their UART.
 *
 * Callers must hold `BLUETOOTH_CONNECT` on Android 12+ (checked by
 * [com.bod3.app.bluetooth.BluetoothPermissions] before a connection is attempted), hence the
 * MissingPermission suppression. Discovery is never used, so `BLUETOOTH_SCAN` is not needed.
 */
@SuppressLint("MissingPermission")
class BluetoothSppTransport(private val device: BluetoothDevice) : Transport {

    @Volatile
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override val description: String = "Bluetooth SPP ${device.address}"

    override val isOpen: Boolean get() = socket?.isConnected == true

    override suspend fun open() = withContext(Dispatchers.IO) {
        var lastError: IOException? = null
        // Most adapters accept a secure (authenticated) socket; some cheap ones only accept insecure.
        val factories = listOf<() -> BluetoothSocket>(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
        )
        for (factory in factories) {
            var candidate: BluetoothSocket? = null
            try {
                candidate = factory()
                candidate.connect()
                socket = candidate
                input = candidate.inputStream
                output = candidate.outputStream
                return@withContext
            } catch (e: IOException) {
                lastError = e
                runCatching { candidate?.close() }
            } catch (e: SecurityException) {
                throw TransportException("Bluetooth permission missing", e)
            }
        }
        throw TransportException("Could not connect to ${device.address}: ${lastError?.message ?: "unknown error"}", lastError)
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val stream = output ?: throw TransportException("Not connected")
        try {
            stream.write(data)
            stream.flush()
        } catch (e: IOException) {
            throw TransportException("Write failed: ${e.message}", e)
        }
    }

    override suspend fun read(buffer: ByteArray): Int = withContext(Dispatchers.IO) {
        val stream = input ?: throw TransportException("Not connected")
        try {
            stream.read(buffer)
        } catch (e: IOException) {
            throw TransportException("Read failed: ${e.message}", e)
        }
    }

    override fun close() {
        val current = socket
        socket = null
        input = null
        output = null
        runCatching { current?.close() }
    }

    companion object {
        /** Well-known Serial Port Profile service UUID. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
