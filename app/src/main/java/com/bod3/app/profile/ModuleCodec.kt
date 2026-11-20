package com.bod3.app.profile

import com.bod3.app.obd.scan.Bus
import com.bod3.app.obd.scan.DetectionMethod
import com.bod3.app.obd.scan.DiscoveredModule
import com.bod3.app.obd.scan.ModuleAddress
import com.bod3.app.obd.scan.ModuleReport
import com.bod3.app.obd.uds.DidReading
import com.bod3.app.obd.uds.DidResult
import com.bod3.app.obd.uds.DtcReadResult
import com.bod3.app.obd.uds.DtcRecord
import com.bod3.app.obd.uds.DtcStatusMeaning
import com.bod3.app.obd.uds.ToyotaDtcState

/**
 * Flat, storage-friendly form of a [ModuleReport]: the summary fields the module list shows are
 * columns, the full identifier and DTC data is JSON. Mirrors the Room entity field for field.
 */
data class StoredModule(
    val vin: String,
    val bus: String,
    val requestHeader: String,
    val responseHeader: String,
    val name: String?,
    val detectedBy: String,
    val obd: Boolean,
    val negativeReply: Boolean,
    val firstReply: String?,
    val partNumber: String?,
    val softwareVersion: String?,
    val hardwareVersion: String?,
    val coding: String?,
    /** Null when DTCs could not be read. */
    val dtcCount: Int?,
    val identifiersJson: String,
    val dtcsJson: String,
    val obdEcuName: String?,
    val calibrationId: String?,
    val scannedAtMs: Long,
)

object ModuleCodec {

    fun encode(vin: String, report: ModuleReport, scannedAtMs: Long): StoredModule = StoredModule(
        vin = vin,
        bus = report.address.bus.name,
        requestHeader = report.address.requestHeader,
        responseHeader = report.address.responseHeader,
        name = report.name,
        detectedBy = report.module.detectedBy.name,
        obd = report.module.obd,
        negativeReply = report.module.negativeReply,
        firstReply = report.module.firstReply,
        partNumber = report.partNumber,
        softwareVersion = report.softwareVersion,
        hardwareVersion = report.hardwareVersion,
        coding = report.coding,
        dtcCount = report.dtcs.faultCount,
        identifiersJson = JsonWriter.write(report.identifiers.map(::encodeDid)),
        dtcsJson = JsonWriter.write(encodeDtcs(report.dtcs)),
        obdEcuName = report.obdEcuName,
        calibrationId = report.calibrationId,
        scannedAtMs = scannedAtMs,
    )

    fun decode(stored: StoredModule): ModuleReport {
        val bus = runCatching { Bus.valueOf(stored.bus) }.getOrDefault(Bus.CAN_11)
        val method = runCatching { DetectionMethod.valueOf(stored.detectedBy) }.getOrDefault(DetectionMethod.TESTER_PRESENT)
        val module = DiscoveredModule(
            address = ModuleAddress(bus, stored.requestHeader, stored.responseHeader),
            detectedBy = method,
            obd = stored.obd,
            negativeReply = stored.negativeReply,
            firstReply = stored.firstReply,
        )
        return ModuleReport(
            module = module,
            name = stored.name,
            identifiers = decodeDids(stored.identifiersJson),
            dtcs = decodeDtcs(stored.dtcsJson),
            obdEcuName = stored.obdEcuName,
            calibrationId = stored.calibrationId,
        )
    }

    private fun encodeDid(reading: DidReading): Map<String, Any?> = linkedMapOf<String, Any?>(
        "did" to reading.did, "definitionId" to reading.definitionId, "definitionName" to reading.definitionName,
        "request" to reading.request, "hexOnly" to reading.hexOnly, "translatedText" to reading.translatedText,
    ) + when (val r = reading.result) {
        is DidResult.Value -> mapOf("kind" to "value", "data" to r.data.toHexString())
        is DidResult.Negative -> mapOf("kind" to "negative", "nrc" to r.nrc)
        DidResult.NoResponse -> mapOf("kind" to "none")
        is DidResult.Failed -> mapOf("kind" to "failed", "message" to r.message)
    }

    private fun decodeDids(json: String): List<DidReading> {
        val items = runCatching { MiniJson.parse(json) as? List<*> }.getOrNull() ?: return emptyList()
        return items.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val did = (map["did"] as? Double)?.toInt() ?: return@mapNotNull null
            val result = when (map["kind"]) {
                "value" -> DidResult.Value(parseHex(map["data"] as? String))
                "negative" -> DidResult.Negative((map["nrc"] as? Double)?.toInt() ?: 0)
                "none" -> DidResult.NoResponse
                else -> DidResult.Failed(map["message"] as? String ?: "unknown")
            }
            DidReading(did, result, definitionId = map["definitionId"] as? String,
                definitionName = map["definitionName"] as? String, request = map["request"] as? String,
                hexOnly = map["hexOnly"] as? Boolean ?: false, translatedText = map["translatedText"] as? String)
        }
    }

    private fun encodeDtcs(result: DtcReadResult): Map<String, Any?> = when (result) {
        is DtcReadResult.Read -> mapOf(
            "kind" to "read",
            "source" to result.source,
            "mask" to result.availabilityMask,
            "records" to result.records.map { mapOf("code" to it.code, "status" to it.status, "raw" to it.raw.toHexString(),
                "statusMeaning" to it.statusMeaning.name, "nativeCode" to it.nativeCode?.let { key -> "%08X".format(key) },
                "toyotaState" to it.toyotaState?.name, "sourceDescription" to it.sourceDescription) },
        )
        is DtcReadResult.Negative -> mapOf("kind" to "negative", "nrc" to result.nrc)
        DtcReadResult.NoResponse -> mapOf("kind" to "none")
        is DtcReadResult.Failed -> mapOf("kind" to "failed", "message" to result.message)
    }

    private fun decodeDtcs(json: String): DtcReadResult {
        val map = runCatching { MiniJson.parse(json) as? Map<*, *> }.getOrNull()
            ?: return DtcReadResult.Failed("unreadable saved data")
        return when (map["kind"]) {
            "read" -> DtcReadResult.Read(
                source = map["source"] as? String ?: "",
                records = (map["records"] as? List<*>).orEmpty().mapNotNull { item ->
                    val record = item as? Map<*, *> ?: return@mapNotNull null
                    DtcRecord(
                        code = record["code"] as? String ?: return@mapNotNull null,
                        status = (record["status"] as? Double)?.toInt(),
                        raw = parseHex(record["raw"] as? String),
                        // Legacy rows omitted statusMeaning and remain UDS/OBD. Unknown
                        // future values MUST stay opaque, never acquire UDS bit meanings.
                        statusMeaning = if ("statusMeaning" !in record) DtcStatusMeaning.UDS
                            else DtcStatusMeaning.entries.firstOrNull { it.name == record["statusMeaning"] } ?: DtcStatusMeaning.RAW,
                        nativeCode = (record["nativeCode"] as? String)?.takeIf { it.matches(Regex("[0-9A-F]{8}")) }?.toLongOrNull(16),
                        toyotaState = ToyotaDtcState.entries.firstOrNull { it.name == record["toyotaState"] },
                        sourceDescription = record["sourceDescription"] as? String,
                    )
                },
                availabilityMask = (map["mask"] as? Double)?.toInt(),
            )
            "negative" -> DtcReadResult.Negative((map["nrc"] as? Double)?.toInt() ?: 0)
            "none" -> DtcReadResult.NoResponse
            else -> DtcReadResult.Failed(map["message"] as? String ?: "unknown")
        }
    }

    private fun List<Int>.toHexString(): String = joinToString("") { "%02X".format(it) }

    private fun parseHex(text: String?): List<Int> =
        text.orEmpty().chunked(2).mapNotNull { it.toIntOrNull(16) }
}

/** Minimal JSON writer for maps, lists, strings, numbers, booleans and null. */
object JsonWriter {
    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> string(out, value)
            is Boolean -> out.append(value)
            is Int, is Long -> out.append(value)
            is Number -> {
                val d = value.toDouble()
                if (d == Math.floor(d) && !d.isInfinite() && kotlin.math.abs(d) < 1e15) out.append(d.toLong()) else out.append(d)
            }
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    string(out, k.toString())
                    out.append(':')
                    append(out, v)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    append(out, v)
                }
                out.append(']')
            }
            else -> string(out, value.toString())
        }
    }

    private fun string(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
    }
}
