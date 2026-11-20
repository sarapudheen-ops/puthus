package com.bod3.app.obd.kwp

import com.bod3.app.obd.elm.CommandExecutor
import com.bod3.app.obd.elm.ElmError
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.session.EcuTarget
import com.bod3.app.obd.session.ObdRequestException
import com.bod3.app.recipe.Hex

/** MIC3313 supports the ST/VT command sets: send directly, log '?' and continue, never probe firmware. */
object KLineAdapter {
    fun commands(target: EcuTarget.Physical, responseTimeoutCeilingMs: Int = 65535): List<String> {
        val link = requireNotNull(target.link); val k = requireNotNull(link.kLine)
        val responseTimeout = k.p2StarMs.coerceAtMost(responseTimeoutCeilingMs)
        require(responseTimeout > 0)
        require(link.problems().isEmpty() && link.addressProblems(target.requestHeader, target.responseHeader.orEmpty()).isEmpty())
        return buildList {
            add("ATPC")
            addAll(link.protocol.selectCommands())
            add("ATH1"); add("ATSW00"); add("ATKW0")
            // P2 base fallback, followed by millisecond-precision ST timeout (including P2*).
            add("ATST %02X".format(((responseTimeout + 3) / 4).coerceIn(1, 255)))
            add("ATAT0"); add("STPBR ${k.baud}")
            add("STIAT0"); add("STIP1X ${k.p1MaxMs}")
            add("STPTO $responseTimeout"); add("STPTRQ ${k.p3MinMs}"); add("STIP4 ${k.p4Ms}")
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

    suspend fun initialize(executor: CommandExecutor, target: EcuTarget.Physical, logger: (String) -> Unit, strictBringUp: Boolean = false) {
        // Leave Bluetooth/prompt headroom inside the 1-second host probe timeout. This is a
        // deliberately short observation ceiling, not a newly inferred source P2* value.
        for (command in commands(target, if (strictBringUp) 500 else 65535)) {
            val response = executor.execute(command, if (strictBringUp) 1000 else 3000)
            if (!response.isOk) {
                if (!strictBringUp && (command.startsWith("ST") || command.startsWith("VT") || command == "ATKW0")) {
                    logger("Adapter rejected $command (${response.text}); continuing")
                } else throw ObdRequestException(response.error ?: ElmError.UNKNOWN_COMMAND, response)
            }
        }
        val init = initCommand(target)
        val response = executor.execute(init, if (strictBringUp) {
            if (target.link?.kLine?.init == KLineInit.FIVE_BAUD) 6000 else 2000
        } else 15000)
        // A rejected custom physical init must never fall back to functional address 33.
        if (!response.isSuccess) throw ObdRequestException(response.error ?: ElmError.BUS_INIT_ERROR, response)
        if ((!response.isOk || strictBringUp) && init != "ATSI") {
            val rx = requireNotNull(Hex.parse(target.responseHeader.orEmpty()))
            val iso = target.link?.protocol == ObdProtocol.ISO_9141_2
            val payloads = response.lines.mapNotNull { KLineFrames.decode(it, rx, iso)?.payload }
            require(if (strictBringUp) payloads.size == 1 && payloads.size == response.lines.size && payloads.single().firstOrNull() == 0xC1
                else payloads.any { it.firstOrNull() == 0xC1 }) { "No complete valid startCommunication reply from the selected ECU" }
        }
    }
}
