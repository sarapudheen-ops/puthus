package com.bod3.app.obd.scan

import com.bod3.app.obd.elm.CommandExecutor
import com.bod3.app.obd.session.Session
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Callbacks for a full module scan. All are invoked on the scanning coroutine. */
interface ScanListener {
    fun onProgress(progress: ScanProgress) {}
    fun onModuleFound(module: DiscoveredModule) {}
    fun onModuleRead(report: ModuleReport) {}
}

/**
 * Full scan: discovery ([ModuleScanner]) followed by reading every module found ([ModuleReader]).
 * Modules are read bus by bus so the adapter switches protocol at most twice. Results are delivered
 * through [ScanListener] as they arrive, so a cancelled scan still leaves usable partial results.
 */
class ScanCoordinator(
    private val session: Session,
    private val executor: CommandExecutor,
    private val scanner: ModuleScanner = ModuleScanner(session, executor),
    private val reader: ModuleReader = ModuleReader(session),
) {

    suspend fun run(options: ScanOptions = ScanOptions(), listener: ScanListener = object : ScanListener {}): List<ModuleReport> {
        val discovered = scanner.scan(options, listener::onProgress, listener::onModuleFound)
        val nativeProtocol = session.protocol
        val ordered = discovered.sortedWith(
            compareBy<DiscoveredModule> { it.address.bus.format != nativeProtocol.headerFormat }
                .thenBy { it.address.requestHeader },
        )

        val reports = mutableListOf<ModuleReport>()
        try {
            ordered.forEachIndexed { index, module ->
                currentCoroutineContext().ensureActive()
                val protocol = protocolFor(module.address.bus, nativeProtocol)
                if (protocol != null) session.switchProtocol(protocol)
                val progress = ScanProgress(
                    phase = ScanPhase.READING_MODULES,
                    bus = module.address.bus,
                    completed = index,
                    total = ordered.size,
                    current = module.address.requestHeader,
                    modulesFound = ordered.size,
                )
                listener.onProgress(progress)
                val report = reader.read(module, options.dids) { step ->
                    listener.onProgress(progress.copy(current = "${module.address.requestHeader} · $step"))
                }
                reports += report
                listener.onModuleRead(report)
            }
            listener.onProgress(ScanProgress(ScanPhase.DONE, null, ordered.size, ordered.size, null, ordered.size))
        } finally {
            withContext(NonCancellable) { runCatching { session.switchProtocol(nativeProtocol) } }
        }
        return reports
    }

    private fun protocolFor(bus: Bus, native: com.bod3.app.obd.protocol.ObdProtocol) =
        if (native.bus() == bus) native else ModuleScanner.counterpart(native)
}
