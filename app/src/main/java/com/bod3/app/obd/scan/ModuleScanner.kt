package com.bod3.app.obd.scan

import com.bod3.app.obd.elm.AdapterException
import com.bod3.app.obd.elm.CommandExecutor
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.session.EcuTarget
import com.bod3.app.obd.session.ObdResponse
import com.bod3.app.obd.session.Session
import kotlin.math.ceil
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Discovers diagnostic modules on CAN using only read-only requests.
 *
 * For each bus (the vehicle's own identifier length first, then optionally the other one):
 * 1. OBD broadcast `01 00` on `7DF` / `18DB33F1`.
 * 2. The OBD physical addresses `7E0`-`7E7` / `18DA10F1`-`18DA17F1` with `3E 00`, falling back to `01 00`.
 * 3. The UDS address range `700`-`7FF` / `18DA00F1`-`18DAFFF1` with `3E 00`, falling back to `22 F190`.
 * 4. Listed ECUs ([ScanOptions.listedModules]: the vehicle's manual list and data-pack ECUs) not found
 *    yet, probed with their exact reply ID, so modules with unusual reply IDs or that only answer on
 *    their own pair are still found. With [ScanOptions.rangeScan] off only this step runs (quick scan).
 *
 * Any reply, including a negative response (`7F`), marks a module as present. During probing the
 * adapter accepts replies from any ID in the range (`ATCF`/`ATCM`), so modules whose reply ID is not
 * request + 8 are still found, and the adapter reply timeout is shortened (`ATST`) so silent addresses
 * cost about 100 ms each.
 *
 * Skipped addresses: the functional ID (`7DF`), the OBD reply IDs (`7E8`-`7EF`), reply IDs of modules
 * already found, and the tester's own 29-bit address (`F1`).
 *
 * Cancellation is checked before every probe; adapter settings are restored even when cancelled.
 */
class ModuleScanner(
    private val session: Session,
    private val executor: CommandExecutor,
) {

    private data class Probe(val request: List<Int>, val method: DetectionMethod)

    private data class Step(val bus: Bus, val phase: ScanPhase, val requestHeader: String?, val responseHeader: String? = null)

    suspend fun scan(
        options: ScanOptions = ScanOptions(),
        onProgress: (ScanProgress) -> Unit = {},
        onModuleFound: (DiscoveredModule) -> Unit = {},
    ): List<DiscoveredModule> {
        val nativeProtocol = session.protocol
        val nativeBus = nativeProtocol.bus()
            ?: throw ScanAbortedException("Module scan needs a CAN vehicle (this one uses ${nativeProtocol.displayName})")

        val other = counterpart(nativeProtocol)
        val passes = buildList {
            add(nativeProtocol)
            if (other != null && (options.includeOtherBus || options.listedModules.any { it.bus == other.bus() })) add(other)
        }
        val listed = options.listedModules.distinctBy { it.key }
        val steps = passes.flatMap { protocol ->
            val bus = protocol.bus()!!
            val ranges = if (options.rangeScan && (protocol == nativeProtocol || options.includeOtherBus)) plan(bus) else emptyList()
            ranges + listed.filter { it.bus == bus }.map { Step(bus, ScanPhase.LISTED_ADDRESSES, it.requestHeader, it.responseHeader) }
        }
        val found = LinkedHashMap<String, DiscoveredModule>()
        var completed = 0
        var consecutiveFailures = 0

        fun report(step: Step?) = onProgress(
            ScanProgress(
                phase = step?.phase ?: ScanPhase.PREPARING,
                bus = step?.bus,
                completed = completed,
                total = steps.size,
                current = step?.requestHeader,
                modulesFound = found.size,
            ),
        )

        fun add(module: DiscoveredModule) {
            if (found.containsKey(module.address.key)) return
            found[module.address.key] = module
            onModuleFound(module)
        }

        suspend fun probe(target: EcuTarget, probe: Probe): ObdResponse? {
            return try {
                session.request(target, probe.request, options.probeTimeoutMs).also { consecutiveFailures = 0 }
            } catch (e: AdapterException) {
                consecutiveFailures++
                if (consecutiveFailures >= options.maxConsecutiveFailures) {
                    throw ScanAbortedException("The adapter stopped answering (${e.message})")
                }
                null
            }
        }

        report(null)
        try {
            setAdapterTimeout(options.adapterTimeoutMs)
            for (protocol in passes) {
                currentCoroutineContext().ensureActive()
                session.switchProtocol(protocol)
                val bus = protocol.bus()!!
                for (step in steps.filter { it.bus == bus }) {
                    currentCoroutineContext().ensureActive()
                    report(step)
                    when (step.phase) {
                        ScanPhase.OBD_BROADCAST -> {
                            val response = probe(EcuTarget.Broadcast, Probe(listOf(0x01, 0x00), DetectionMethod.OBD_BROADCAST))
                            response?.responders?.forEach { responder ->
                                val request = EcuTarget.physicalFor(responder, bus.format)?.requestHeader ?: return@forEach
                                add(module(bus, request, responder, DetectionMethod.OBD_BROADCAST, response))
                            }
                        }

                        ScanPhase.LISTED_ADDRESSES -> {
                            val request = step.requestHeader!!
                            val response = step.responseHeader!!
                            val known = found.values.any { it.address.bus == bus && it.address.requestHeader.equals(request, ignoreCase = true) }
                            if (!known) {
                                val target = EcuTarget.Physical(request, response, flowControl = false)
                                for (candidate in listOf(Probe(listOf(0x3E, 0x00), DetectionMethod.MANUAL_LIST), Probe(listOf(0x22, 0xF1, 0x90), DetectionMethod.MANUAL_LIST), Probe(listOf(0x01, 0x00), DetectionMethod.MANUAL_LIST))) {
                                    val reply = probe(target, candidate) ?: continue
                                    if (reply.responders.none { it.equals(response, ignoreCase = true) }) continue
                                    val obd = candidate.request.first() == 0x01
                                    add(module(bus, request, response, candidate.method, reply).copy(obd = obd))
                                    break
                                }
                            }
                        }

                        else -> {
                            val request = step.requestHeader!!
                            val skip = found.values.any { it.address.bus == bus && (it.address.requestHeader == request || it.address.responseHeader == request) }
                            if (!skip) {
                                val target = EcuTarget.Physical(request, scanFilter(bus), scanMask(bus), flowControl = false)
                                for (candidate in probesFor(step.phase, options)) {
                                    val response = probe(target, candidate) ?: continue
                                    if (response.responders.isEmpty()) continue
                                    response.responders.forEach { add(module(bus, request, it, candidate.method, response)) }
                                    break
                                }
                            }
                        }
                    }
                    completed++
                }
            }
            report(Step(nativeBus, ScanPhase.DONE, null))
        } finally {
            withContext(NonCancellable) {
                runCatching { session.switchProtocol(nativeProtocol) }
                runCatching { executor.execute(DEFAULT_ADAPTER_TIMEOUT, 2_000L) }
            }
        }
        return found.values.toList()
    }

    private fun plan(bus: Bus): List<Step> = buildList {
        add(Step(bus, ScanPhase.OBD_BROADCAST, null))
        obdRequestHeaders(bus).forEach { add(Step(bus, ScanPhase.OBD_ADDRESSES, it)) }
        udsRequestHeaders(bus).forEach { add(Step(bus, ScanPhase.UDS_ADDRESSES, it)) }
    }

    private fun probesFor(phase: ScanPhase, options: ScanOptions): List<Probe> = buildList {
        add(Probe(listOf(0x3E, 0x00), DetectionMethod.TESTER_PRESENT))
        if (phase == ScanPhase.OBD_ADDRESSES) {
            add(Probe(listOf(0x01, 0x00), DetectionMethod.OBD_REQUEST))
        } else if (options.vinFallback) {
            add(Probe(listOf(0x22, 0xF1, 0x90), DetectionMethod.VIN_READ))
        }
    }

    private fun module(bus: Bus, request: String, responder: String, method: DetectionMethod, response: ObdResponse) =
        DiscoveredModule(
            address = ModuleAddress(bus, request, responder),
            detectedBy = method,
            obd = method == DetectionMethod.OBD_BROADCAST || method == DetectionMethod.OBD_REQUEST,
            negativeReply = response.messages.none { it.header == responder } &&
                response.negativeResponses.any { it.header == responder },
            firstReply = response.elm.lines.firstOrNull(),
        )

    private suspend fun setAdapterTimeout(timeoutMs: Int) {
        // ATST counts in units of 4.096 ms.
        val value = ceil(timeoutMs / 4.096).toInt().coerceIn(1, 0xFF)
        executor.execute("ATST %02X".format(value), 2_000L)
    }

    companion object {
        /** Adapter default reply timeout, restored after a scan (`ATST 32` = ~200 ms). */
        const val DEFAULT_ADAPTER_TIMEOUT = "ATST 32"

        fun obdRequestHeaders(bus: Bus): List<String> = when (bus) {
            Bus.CAN_11 -> (0x7E0..0x7E7).map { "%03X".format(it) }
            Bus.CAN_29 -> (0x10..0x17).map { "18DA%02XF1".format(it) }
        }

        fun udsRequestHeaders(bus: Bus): List<String> = when (bus) {
            Bus.CAN_11 -> (0x700..0x7FF)
                .filterNot { it == 0x7DF || it in 0x7E0..0x7EF }
                .map { "%03X".format(it) }
            Bus.CAN_29 -> (0x00..0xFF)
                .filterNot { it == 0xF1 || it in 0x10..0x17 }
                .map { "18DA%02XF1".format(it) }
        }

        /** Receive filter/mask that accepts every reply ID in the scanned range. */
        fun scanFilter(bus: Bus): String = if (bus == Bus.CAN_11) "700" else "18DAF100"
        fun scanMask(bus: Bus): String = if (bus == Bus.CAN_11) "700" else "1FFFFF00"

        /** The same bus speed with the other identifier length, if there is one. */
        fun counterpart(protocol: ObdProtocol): ObdProtocol? = when (protocol) {
            ObdProtocol.ISO_15765_4_CAN_11_500 -> ObdProtocol.ISO_15765_4_CAN_29_500
            ObdProtocol.ISO_15765_4_CAN_29_500 -> ObdProtocol.ISO_15765_4_CAN_11_500
            ObdProtocol.ISO_15765_4_CAN_11_250 -> ObdProtocol.ISO_15765_4_CAN_29_250
            ObdProtocol.ISO_15765_4_CAN_29_250 -> ObdProtocol.ISO_15765_4_CAN_11_250
            else -> null
        }
    }
}
