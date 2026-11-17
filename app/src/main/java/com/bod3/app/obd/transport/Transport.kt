package com.bod3.app.obd.transport

import java.io.IOException

/**
 * A byte pipe to an OBD adapter.
 *
 * Phase 1 ships [BluetoothSppTransport] (Bluetooth Classic, Serial Port Profile). BLE, Wi-Fi (TCP) and
 * USB transports can be added later by implementing this interface; nothing above this layer knows
 * which physical link is in use.
 */
interface Transport {
    /** Human-readable description of the link, e.g. a MAC address. Used for logging only. */
    val description: String

    val isOpen: Boolean

    /** Opens the link. Must be called once before [read] / [write]. */
    @Throws(TransportException::class)
    suspend fun open()

    /** Writes all bytes of [data] to the adapter. */
    @Throws(TransportException::class)
    suspend fun write(data: ByteArray)

    /**
     * Reads available bytes into [buffer], suspending until at least one byte arrives.
     *
     * @return the number of bytes read, or -1 when the remote side closed the link.
     */
    @Throws(TransportException::class)
    suspend fun read(buffer: ByteArray): Int

    /**
     * Closes the link. Must be safe to call more than once and from any thread, and must unblock a
     * pending [read].
     */
    fun close()
}

class TransportException(message: String, cause: Throwable? = null) : IOException(message, cause)
