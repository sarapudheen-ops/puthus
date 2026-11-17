package com.bod3.app.obd.scan

import com.bod3.app.obd.testing.SimModule
import com.bod3.app.obd.testing.SimulatedCanAdapter.Companion.didResponder

/** A simulated 11-bit CAN vehicle with modules that exercise every detection path. */
object TestVehicle {
    const val VIN = "WVWZZZ1KZAW000001"

    private val obdPids: (List<Int>) -> List<List<Int>> = { request ->
        when (request) {
            listOf(0x01, 0x00) -> listOf(listOf(0x41, 0x00, 0xBE, 0x3E, 0xA8, 0x13))
            listOf(0x3E, 0x00) -> listOf(listOf(0x7E, 0x00))
            else -> emptyList()
        }
    }

    /** Engine: OBD + UDS, two DTCs, VW coding, identification strings. */
    val engine = SimModule(
        '6', "7E0", "7E8", obd = true,
        respond = didResponder(
            values = mapOf(0xF187 to "03L906018JJ", 0xF189 to "1234", 0xF191 to "03L906018 ", 0xF197 to "R4 2.0L TDI", 0xF190 to VIN),
            rawValues = mapOf(0x0600 to listOf(0x01, 0x02, 0x03, 0xA4)),
        ) { request ->
            when {
                request == listOf(0x19, 0x02, 0xFF) ->
                    listOf(listOf(0x59, 0x02, 0xFF, 0x04, 0x20, 0x13, 0x2F, 0xC1, 0x00, 0x00, 0x09))
                request == listOf(0x09, 0x0A) -> listOf(listOf(0x49, 0x0A, 0x01) + "ECM-EngineControl".map { it.code } + listOf(0, 0, 0))
                else -> obdPids(request)
            }
        },
    )

    /** Transmission: OBD only, no UDS (rejects 22 and 19 with service-not-supported), one OBD DTC. */
    val transmission = SimModule('6', "7E1", "7E9", obd = true) { request ->
        when (request[0]) {
            0x01, 0x3E -> obdPids(request)
            0x03 -> listOf(listOf(0x43, 0x01, 0x07, 0x00))
            0x22, 0x19 -> listOf(listOf(0x7F, request[0], 0x11))
            else -> emptyList()
        }
    }

    /** ABS: answers TesterPresent only with a negative response. First DID read is "response pending". */
    var absPending = true
    val abs = SimModule('6', "713", "77D") { request ->
        when {
            request == listOf(0x3E, 0x00) -> listOf(listOf(0x7F, 0x3E, 0x11))
            request == listOf(0x22, 0xF1, 0x87) && absPending -> {
                absPending = false
                listOf(listOf(0x7F, 0x22, 0x78))
            }
            request == listOf(0x22, 0xF1, 0x87) -> listOf(listOf(0x62, 0xF1, 0x87) + "1K0907379".map { it.code })
            request[0] == 0x19 -> listOf(listOf(0x7F, 0x19, 0x22))
            request[0] == 0x22 -> listOf(listOf(0x7F, 0x22, 0x31))
            else -> emptyList()
        }
    }

    /** Body controller: ignores TesterPresent but answers the VIN read (multi-frame). */
    val body = SimModule(
        '6', "70E", "778",
        respond = didResponder(mapOf(0xF190 to VIN, 0xF197 to "BCM")) { emptyList() },
    )

    /** Gateway on 29-bit: answers TesterPresent; its VIN reply never completes (flow control lost). */
    val gateway29 = SimModule('7', "18DA19F1", "18DAF119", truncateMultiFrame = true) { request ->
        when (request[0]) {
            0x3E -> listOf(listOf(0x7E, 0x00))
            0x22 -> if (request == listOf(0x22, 0xF1, 0x90)) listOf(listOf(0x62, 0xF1, 0x90) + VIN.map { it.code }) else listOf(listOf(0x7F, 0x22, 0x31))
            0x19 -> listOf(listOf(0x59, 0x02, 0xFF))
            else -> emptyList()
        }
    }

    fun all(): List<SimModule> {
        absPending = true
        return listOf(engine, transmission, abs, body, gateway29)
    }
}
