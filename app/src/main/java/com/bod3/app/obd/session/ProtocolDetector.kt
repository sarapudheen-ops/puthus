package com.bod3.app.obd.session

import com.bod3.app.obd.elm.AdapterException
import com.bod3.app.obd.elm.CommandExecutor
import com.bod3.app.obd.elm.ElmResponse
import com.bod3.app.obd.protocol.ObdProtocol

/** The vehicle protocol the adapter settled on. */
data class ProtocolInfo(
    val protocol: ObdProtocol,
    /** `ATDP` text, e.g. `AUTO, ISO 15765-4 (CAN 11/500)`. */
    val description: String,
)

class ProtocolDetectionException(message: String, val response: ElmResponse? = null) : AdapterException(message)

object ProtocolDetector {

    /** The first request after `ATSP0` makes the adapter search every protocol, which can take a while. */
    const val SEARCH_TIMEOUT_MS = 20_000L

    /**
     * Sends `0100` (a read-only "supported PIDs" request) to trigger the automatic protocol search, then
     * asks the adapter which protocol it found (`ATDPN`, `ATDP`).
     */
    suspend fun detect(executor: CommandExecutor): ProtocolInfo {
        val search = executor.execute("0100", SEARCH_TIMEOUT_MS)
        search.error?.let { throw ProtocolDetectionException(it.description, search) }

        val numberReply = executor.execute("ATDPN", 2_000L)
        val protocol = ObdProtocol.fromProtocolNumber(numberReply.text)
            ?: throw ProtocolDetectionException("Unrecognised protocol number '${numberReply.text}'", numberReply)
        val description = executor.execute("ATDP", 2_000L).text.ifBlank { protocol.displayName }
        return ProtocolInfo(protocol, description)
    }
}
