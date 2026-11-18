package com.bod3.app.obd.kwp

import com.bod3.app.obd.elm.CommandExecutor
import com.bod3.app.obd.elm.ElmError
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.session.EcuTarget
import com.bod3.app.obd.session.ObdRequestException
import com.bod3.app.recipe.Hex

/** MIC3313 supports the ST/VT command sets: send directly, log '?' and continue, never probe firmware. */
object KLineAdapter {
    fun commands(target: EcuTarget.Physical): List<String> {
        val link = requireNotNull(target.link); val k = requireNotNull(link.kLine)
        require(link.problems().isEmpty() && link.addressProblems(target.requestHeader, target.responseHeader.orEmpty()).isEmpty())
        return buildList {
            add("ATPC")
            addAll(link.protocol.selectCommands())
            add("ATH1"); add("ATSW00"); add("ATKW0")
            // P2 base fallback, followed by millisecond-precision ST timeout (including P2*).
            add("ATST %02X".format(((k.p2StarMs + 3) / 4).coerceIn(1, 255)))
            add("ATAT0"); add("STPBR ${k.baud}")
            add("STIAT0"); add("STIP1X ${k.p1MaxMs}")
            add("STPTO ${k.p2StarMs}"); add("STPTRQ ${k.p3MinMs}"); add("STIP4 ${k.p4Ms}")
            add("STIIAP ${k.initParity.name}"); add("STIP ${k.dataParity.name}")
            if (k.slowInitTimingsMs.isNotEmpty()) add("STITW " + k.slowInitTimingsMs.joinToString(","))
            add("ATIIA %02X".format(k.initAddress))
            add("ATSH ${target.requestHeader}")
        }
    }

    fun initCommand(target: EcuTarget.Physical): String {
        val k = requireNotNull(target.link?.kLine)
        if (k.init == KLineInit.FIVE_BAUD) return "ATSI"
        val frame = KLineFrames.encode(requireNotNull(Hex.parse(target.requestHeader)), listOf(0x81))
        return "STIFI ${k.fastLowMs},${k.fastHighMs},${Hex.format(frame, "")}"
    }

    suspend fun initialize(executor: CommandExecutor, target: EcuTarget.Physical, logger: (String) -> Unit) {
        for (command in commands(target)) {
            val response = executor.execute(command, 3000)
            if (!response.isOk) {
                if (command.startsWith("ST") || command.startsWith("VT") || command == "ATKW0") {
                    logger("Adapter rejected $command (${response.text}); continuing")
                } else throw ObdRequestException(response.error ?: ElmError.UNKNOWN_COMMAND, response)
            }
        }
        val init = initCommand(target)
        val response = executor.execute(init, 15000)
        // A rejected custom physical init must never fall back to functional address 33.
        if (!response.isSuccess) throw ObdRequestException(response.error ?: ElmError.BUS_INIT_ERROR, response)
        if (!response.isOk && init != "ATSI") {
            val rx = requireNotNull(Hex.parse(target.responseHeader.orEmpty()))
            val iso = target.link?.protocol == ObdProtocol.ISO_9141_2
            val payloads = response.lines.mapNotNull { KLineFrames.decode(it, rx, iso)?.payload }
            require(payloads.any { it.firstOrNull() == 0xC1 }) { "No valid startCommunication reply from the selected ECU" }
        }
    }
}
