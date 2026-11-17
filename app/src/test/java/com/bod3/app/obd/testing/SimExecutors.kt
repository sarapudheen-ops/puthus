package com.bod3.app.obd.testing

import com.bod3.app.obd.elm.CommandExecutor
import com.bod3.app.obd.elm.ElmResponseParser

/** Talks to a [SimulatedVehicleElm] directly (no driver/transport), like an initialised adapter. */
fun SimulatedVehicleElm.executor(): CommandExecutor = CommandExecutor { command, _ ->
    ElmResponseParser.parse(command, respond(command) ?: "NO DATA")
}
