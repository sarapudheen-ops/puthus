package com.bod3.app.report

import com.bod3.app.obd.connection.ConnectionProfile
import com.bod3.app.obd.connection.ProtocolAttempt
import com.bod3.app.obd.connection.SetupStep
import com.bod3.app.obd.connection.VoltageMonitor
import com.bod3.app.obd.connection.VoltageSample
import com.bod3.app.obd.dtc.DtcReport
import com.bod3.app.obd.elm.AdapterInfo
import com.bod3.app.obd.elm.RawExchange
import com.bod3.app.profile.RawLogFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Everything that goes into a shareable diagnostic report. Every field is optional. */
data class ReportData(
    val generatedAtMs: Long,
    val appVersion: String,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val adapter: AdapterInfo? = null,
    val profile: ConnectionProfile? = null,
    val setup: List<SetupStep> = emptyList(),
    val attempts: List<ProtocolAttempt> = emptyList(),
    val protocol: String? = null,
    val vin: String? = null,
    val vehicle: String? = null,
    val ecus: Map<String, Set<Int>> = emptyMap(),
    val voltage: List<VoltageSample> = emptyList(),
    val dtcs: List<DtcReport> = emptyList(),
    val modules: List<String> = emptyList(),
    val reconnects: List<String> = emptyList(),
    val adapterLog: List<String> = emptyList(),
    val rawLog: List<RawExchange> = emptyList(),
    val terminal: String? = null,
)

/** Builds the plain-text "Share diagnostic report" file. */
object DiagnosticReport {

    fun build(data: ReportData): String = buildString {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        appendLine("bod3 diagnostic report")
        appendLine("Generated: ${time.format(Date(data.generatedAtMs))} · app ${data.appVersion}")
        appendLine()

        section("Adapter") {
            line("Device", listOfNotNull(data.deviceName, data.deviceAddress).joinToString(" · ").ifEmpty { "—" })
            val adapter = data.adapter
            if (adapter == null) {
                appendLine("  Not initialised")
            } else {
                line("ATI", adapter.identity.raw.ifEmpty { "—" })
                adapter.description?.let { line("AT@1", it) }
                line("VT extensions", adapter.capabilities.vtVersion ?: "not supported")
                line("ST extensions", adapter.capabilities.stVersion ?: "not supported")
            }
        }

        data.profile?.let { p ->
            section("Connection profile") {
                line("Name", p.name)
                line("Protocol", p.forcedProtocol?.displayName ?: "automatic")
                line("Fallbacks", if (p.tryFallbacks) "ATSP5, 4, 3, 6, 7, 8, 9" else "off")
                line("Timeouts", "request ${p.requestTimeoutMs} ms, search ${p.searchTimeoutMs} ms, adapter ${p.adapterTimeoutMs?.let { "$it ms" } ?: "default"}")
                p.adaptiveTiming?.let { line("Adaptive timing", "ATAT$it") }
                p.broadcastFilter?.let { line("Broadcast filter", "$it / ${p.broadcastMask}") }
                line("Flow control", "${p.flowControlMode.label} (${p.flowControlData})")
                p.extendedAddress?.let { line("Extended address", it) }
            }
        }

        if (data.setup.isNotEmpty()) {
            section("Adapter setup") {
                data.setup.forEach { appendLine("  ${if (it.accepted) "OK " else "ERR"} ${it.command} -> ${it.reply}") }
            }
        }

        section("Protocol detection") {
            if (data.attempts.isEmpty()) appendLine("  No attempts recorded")
            data.attempts.forEachIndexed { index, a ->
                appendLine("  ${index + 1}. ${a.selectCommand} (${a.label}) – ${if (a.success) "OK" else "FAILED"} in ${a.durationMs} ms")
                appendLine("     0100 -> ${a.result}")
                a.atdp?.let { appendLine("     ATDP -> $it") }
                a.reason?.takeIf { !a.success }?.let { appendLine("     reason: $it") }
            }
            line("Result", data.protocol ?: "no protocol")
        }

        section("Vehicle") {
            line("VIN", data.vin ?: "—")
            data.vehicle?.let { line("Vehicle", it) }
            data.ecus.forEach { (ecu, pids) -> line("ECU $ecu", "${pids.size} supported PIDs") }
        }

        if (data.voltage.isNotEmpty()) {
            section("Battery voltage") {
                val latest = data.voltage.last()
                line("Latest", "%.2f V (%s)".format(Locale.US, latest.volts, VoltageMonitor.classify(latest.volts).label))
                line("Min / max", "%.2f V / %.2f V over %d samples".format(Locale.US, data.voltage.minOf { it.volts }, data.voltage.maxOf { it.volts }, data.voltage.size))
            }
        }

        if (data.dtcs.isNotEmpty()) {
            section("Fault codes (OBD)") {
                data.dtcs.forEach { report ->
                    val codes = if (report.noData) "no data" else report.codes.joinToString(", ").ifEmpty { "none" }
                    line(report.kind.label, codes)
                }
            }
        }

        if (data.modules.isNotEmpty()) section("Modules") { data.modules.forEach { appendLine("  $it") } }
        if (data.reconnects.isNotEmpty()) section("Reconnects") { data.reconnects.forEach { appendLine("  $it") } }

        section("Adapter log (${data.adapterLog.size} lines)") { data.adapterLog.forEach { appendLine("  $it") } }

        if (data.rawLog.isNotEmpty()) {
            section("Raw request/response log (${data.rawLog.size} exchanges)") { append(RawLogFormatter.format(data.rawLog)) }
        }
        data.terminal?.takeIf { it.isNotBlank() }?.let { section("Terminal") { appendLine(it) } }
    }

    private fun StringBuilder.section(title: String, body: StringBuilder.() -> Unit) {
        appendLine("== $title ==")
        body()
        appendLine()
    }

    private fun StringBuilder.line(label: String, value: String) {
        append("  ").append(label).append(": ").appendLine(value)
    }

    fun fileName(generatedAtMs: Long, vin: String?): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(generatedAtMs))
        return "bod3-report-" + (vin?.let { "$it-" } ?: "") + stamp + ".txt"
    }
}
