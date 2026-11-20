package com.bod3.app.bringup

import com.bod3.app.packs.PackTextReader
import java.io.File
import java.io.FileOutputStream

/** App-private, bounded, atomic data files. Imported claims are kept in a DIFFERENT file. */
class VerifiedSetupStore(private val directory: File) {
    data class Saved(val report: BringUpReport, val transferred: Boolean)
    private val lock = Any()
    private fun read(file: File): String? {
        if (!file.exists()) return null
        require(file.isFile && file.length() <= BringUpCodec.MAX_BYTES) { "Oversized or invalid bring-up storage" }
        return file.inputStream().use { PackTextReader.read(it, BringUpCodec.MAX_BYTES) }
    }
    private fun file(name: String) = File(directory, name)
    private fun reports(name: String): List<BringUpReport> = read(file(name))?.let(BringUpCodec::readPack).orEmpty()
    private fun atomic(name: String, text: String) {
        val bytes = BringUpCodec.bounded(text).toByteArray(Charsets.UTF_8)
        require(directory.isDirectory || directory.mkdirs()) { "Could not create private bring-up storage" }
        val target = file(name); val temp = file("$name.partial")
        try {
            FileOutputStream(temp).use { output -> output.write(bytes); output.fd.sync() }
            require(temp.renameTo(target)) { "Could not atomically save bring-up evidence; old evidence was preserved" }
        } finally { temp.delete() }
    }
    fun all(): List<Saved> = synchronized(lock) {
        reports(LOCAL).map { Saved(it, false) } + reports(IMPORTED).map { Saved(it, true) }
    }
    fun matching(path: List<String>, systemId: String): List<Saved> = all().filter { it.report.scope.path == path && it.report.scope.systemId == systemId }
    fun lastReport(): BringUpReport? = synchronized(lock) { read(file(LAST))?.let(BringUpCodec::readReport) }

    /** Only the live controller uses this path, after the engine returns complete positive evidence. */
    internal fun saveLocal(report: BringUpReport) = synchronized(lock) {
        require(report.outcome == BringUpOutcome.WORKING && report.workingCandidate?.runnable == true)
        val updated = reports(LOCAL).filterNot { it.scope.key == report.scope.key } + report
        // Capacity failures are explicit. Never evict a vehicle's full evidence behind its back.
        atomic(LOCAL, BringUpCodec.packText(updated))
    }
    internal fun saveLast(report: BringUpReport) = synchronized(lock) { atomic(LAST, BringUpCodec.reportText(report)) }

    /** Atomic all-or-nothing import. It cannot overwrite locally observed evidence or select a vehicle. */
    fun importPack(text: String): Int = synchronized(lock) {
        val incoming = BringUpCodec.readPack(text)
        val updated = reports(IMPORTED).filterNot { old -> incoming.any { it.scope.key == old.scope.key } } + incoming
        atomic(IMPORTED, BringUpCodec.packText(updated))
        incoming.size
    }
    fun exportPack(): String = synchronized(lock) {
        // Prefer local evidence on matching scopes, retaining other vehicles and transferred setups.
        val byScope = linkedMapOf<String, BringUpReport>()
        reports(IMPORTED).forEach { byScope[it.scope.key] = it }
        reports(LOCAL).forEach { byScope[it.scope.key] = it }
        BringUpCodec.packText(byScope.values.toList())
    }
    companion object {
        private const val LOCAL = "locally-verified.json"
        private const val IMPORTED = "transferred-evidence.json"
        private const val LAST = "last-bring-up-report.json"
    }
}
