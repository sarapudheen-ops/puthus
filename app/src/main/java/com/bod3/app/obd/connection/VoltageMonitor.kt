package com.bod3.app.obd.connection

import com.bod3.app.obd.elm.AdapterException
import com.bod3.app.obd.elm.CommandExecutor
import com.bod3.app.obd.elm.VoltageParser

data class VoltageSample(val timestampMs: Long, val volts: Double)

enum class VoltageStatus(val label: String) {
    UNKNOWN("No reading"),
    CRITICAL("Critically low – charge the battery"),
    LOW("Low – below 12.0 V"),
    OK("OK (engine off)"),
    CHARGING("Charging (engine running)"),
    HIGH("Too high – check charging system"),
}

/**
 * Battery voltage measured by the adapter at the OBD socket (`ATRV`), sampled periodically.
 * Keeps a bounded history for the graph and classifies the latest value.
 */
class VoltageMonitor(
    private val executor: CommandExecutor,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxSamples: Int = 240,
) {
    private val samples = ArrayDeque<VoltageSample>()

    val history: List<VoltageSample> get() = synchronized(samples) { samples.toList() }

    val latest: VoltageSample? get() = synchronized(samples) { samples.lastOrNull() }

    /** Reads `ATRV` once; returns null (and records nothing) when there is no plausible reading. */
    suspend fun sample(): VoltageSample? {
        val volts = try {
            executor.execute("ATRV", 2_000L).takeIf { it.isSuccess }?.let { VoltageParser.parse(it.text) }
        } catch (e: AdapterException) {
            null
        } ?: return null
        return VoltageSample(clock(), volts).also(::restore)
    }

    /** Adds an earlier sample, e.g. to keep the history across a reconnect. */
    fun restore(sample: VoltageSample) {
        synchronized(samples) {
            samples.addLast(sample)
            while (samples.size > maxSamples) samples.removeFirst()
        }
    }

    val status: VoltageStatus get() = classify(latest?.volts)

    val min: Double? get() = history.minOfOrNull { it.volts }
    val max: Double? get() = history.maxOfOrNull { it.volts }

    companion object {
        const val CRITICAL_BELOW = 11.5
        const val LOW_BELOW = 12.0
        const val CHARGING_ABOVE = 13.2
        const val HIGH_ABOVE = 15.2

        fun classify(volts: Double?): VoltageStatus = when {
            volts == null -> VoltageStatus.UNKNOWN
            volts < CRITICAL_BELOW -> VoltageStatus.CRITICAL
            volts < LOW_BELOW -> VoltageStatus.LOW
            volts > HIGH_ABOVE -> VoltageStatus.HIGH
            volts > CHARGING_ABOVE -> VoltageStatus.CHARGING
            else -> VoltageStatus.OK
        }
    }
}
