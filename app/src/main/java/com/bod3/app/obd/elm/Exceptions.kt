package com.bod3.app.obd.elm

import java.io.IOException

open class AdapterException(message: String, cause: Throwable? = null) : IOException(message, cause)

class CommandTimeoutException(val command: String, val timeoutMs: Long, val partialResponse: String = "") :
    AdapterException("No reply to '$command' within $timeoutMs ms")

class AdapterInitException(val command: String, val response: ElmResponse?) :
    AdapterException(
        "Adapter rejected '$command'" +
            (response?.let { r -> ": " + (r.error?.description ?: r.lines.joinToString(" ")) } ?: ""),
    )

/** Thrown when an ST/VT command is sent before capability detection confirmed the adapter supports it. */
class UnsupportedCommandException(val command: String, reason: String) :
    AdapterException("'$command' not sent: $reason")
