package com.bod3.app.obd.connection

import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.profile.JsonWriter
import com.bod3.app.profile.MiniJson

/** ISO-TP flow control the adapter sends for multi-frame replies. */
enum class FlowControlMode(val label: String) {
    /** The adapter's built-in flow control (`ATFCSM0`); right for almost every vehicle. */
    AUTO("Automatic (adapter default)"),

    /** Custom flow-control data (`ATFCSH`, `ATFCSD`, `ATFCSM1`) for every physical request. */
    CUSTOM_DATA("Custom data on every physical request"),
}

/**
 * How to connect to a vehicle: protocol choice, timeouts and CAN options. Saved by name; one profile
 * is active at a time.
 */
data class ConnectionProfile(
    val id: String,
    val name: String,
    /** Use only this protocol (plus the fallback list when [tryFallbacks] is on). Null = automatic. */
    val forcedProtocol: ObdProtocol? = null,
    /** When automatic detection fails, try `ATSP5, 4, 3, 6, 7, 8, 9` in turn. */
    val tryFallbacks: Boolean = true,
    /** Host-side timeout for ordinary requests. */
    val requestTimeoutMs: Long = 5_000,
    /** Host-side timeout for the first request of a protocol search (K-line init alone can take 3-5 s). */
    val searchTimeoutMs: Long = 20_000,
    /** Adapter reply timeout in ms (`ATST`, 4.096 ms steps), or null to keep the adapter default. */
    val adapterTimeoutMs: Int? = null,
    /** Adaptive timing `ATAT0/1/2`, or null to keep the default (1). */
    val adaptiveTiming: Int? = null,
    /** Set the reply timeout with the vLinker `VTTOST` command instead of `ATST` when the adapter supports VT. */
    val useVtTimeout: Boolean = false,
    /** Receive filter for broadcast requests as `pattern/mask` (e.g. `7E8`/`7F8`), or null for automatic. */
    val broadcastFilter: String? = null,
    val broadcastMask: String? = null,
    val flowControlMode: FlowControlMode = FlowControlMode.AUTO,
    /** Flow-control data bytes for [FlowControlMode.CUSTOM_DATA], e.g. `300000` (CTS, no block limit, STmin 0). */
    val flowControlData: String = "300000",
    /** ISO 15765-2 extended addressing target byte (`ATCEA hh`), or null for normal addressing. */
    val extendedAddress: String? = null,
) {
    init {
        require(name.isNotBlank()) { "Profile name must not be empty" }
        require(requestTimeoutMs in 500..60_000) { "Request timeout must be 500-60000 ms" }
        require(searchTimeoutMs in 1_000..60_000) { "Search timeout must be 1000-60000 ms" }
        require(adapterTimeoutMs == null || adapterTimeoutMs in 4..1_044) { "Adapter timeout must be 4-1044 ms" }
        require(adaptiveTiming == null || adaptiveTiming in 0..2) { "Adaptive timing must be 0, 1 or 2" }
        require(broadcastFilter == null || (broadcastFilter.isHexId() && broadcastMask?.isHexId() == true)) {
            "Broadcast filter needs a hex pattern and a hex mask"
        }
        require(flowControlData.length in 2..10 && flowControlData.length % 2 == 0 && flowControlData.isHexId()) {
            "Flow-control data must be 1-5 hex bytes"
        }
        require(extendedAddress == null || (extendedAddress.length == 2 && extendedAddress.isHexId())) {
            "Extended address must be one hex byte"
        }
    }

    fun sessionOptions() = com.bod3.app.obd.session.SessionOptions(
        extendedAddressing = extendedAddress != null,
        broadcastFilter = broadcastFilter,
        broadcastMask = broadcastMask,
        customFlowControl = flowControlMode == FlowControlMode.CUSTOM_DATA,
        flowControlData = flowControlData,
    )

    /** `ATST` argument (4.096 ms units), or null. */
    val adapterTimeoutUnits: Int? get() = adapterTimeoutMs?.let { ((it + 4.095) / 4.096).toInt().coerceIn(1, 0xFF) }

    companion object {
        const val DEFAULT_ID = "default"
        val DEFAULT = ConnectionProfile(id = DEFAULT_ID, name = "Automatic (recommended)")

        /** Example for K-line cars that need a slow, patient search. */
        val K_LINE = ConnectionProfile(
            id = "kline",
            name = "K-line (ISO 9141 / KWP2000)",
            forcedProtocol = ObdProtocol.ISO_14230_4_KWP_FAST,
            searchTimeoutMs = 30_000,
            requestTimeoutMs = 8_000,
        )

        val BUILT_IN = listOf(DEFAULT, K_LINE)
    }
}

private fun String.isHexId(): Boolean = isNotEmpty() && all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }

/** JSON form of connection profiles (stored in preferences, exportable later as data packs). */
object ConnectionProfileCodec {

    fun encode(profile: ConnectionProfile): Map<String, Any?> = mapOf(
        "id" to profile.id,
        "name" to profile.name,
        "forcedProtocol" to profile.forcedProtocol?.name,
        "tryFallbacks" to profile.tryFallbacks,
        "requestTimeoutMs" to profile.requestTimeoutMs,
        "searchTimeoutMs" to profile.searchTimeoutMs,
        "adapterTimeoutMs" to profile.adapterTimeoutMs,
        "adaptiveTiming" to profile.adaptiveTiming,
        "useVtTimeout" to profile.useVtTimeout,
        "broadcastFilter" to profile.broadcastFilter,
        "broadcastMask" to profile.broadcastMask,
        "flowControlMode" to profile.flowControlMode.name,
        "flowControlData" to profile.flowControlData,
        "extendedAddress" to profile.extendedAddress,
    )

    fun decode(map: Map<*, *>): ConnectionProfile {
        fun string(key: String) = map[key] as? String
        fun number(key: String) = (map[key] as? Double)
        return ConnectionProfile(
            id = string("id") ?: throw IllegalArgumentException("Profile without id"),
            name = string("name") ?: throw IllegalArgumentException("Profile without name"),
            forcedProtocol = string("forcedProtocol")?.let { name ->
                ObdProtocol.entries.firstOrNull { it.name == name } ?: throw IllegalArgumentException("Unknown protocol $name")
            },
            tryFallbacks = map["tryFallbacks"] as? Boolean ?: true,
            requestTimeoutMs = number("requestTimeoutMs")?.toLong() ?: 5_000,
            searchTimeoutMs = number("searchTimeoutMs")?.toLong() ?: 20_000,
            adapterTimeoutMs = number("adapterTimeoutMs")?.toInt(),
            adaptiveTiming = number("adaptiveTiming")?.toInt(),
            useVtTimeout = map["useVtTimeout"] as? Boolean ?: false,
            broadcastFilter = string("broadcastFilter")?.uppercase(),
            broadcastMask = string("broadcastMask")?.uppercase(),
            flowControlMode = string("flowControlMode")?.let { FlowControlMode.valueOf(it) } ?: FlowControlMode.AUTO,
            flowControlData = string("flowControlData")?.uppercase() ?: "300000",
            extendedAddress = string("extendedAddress")?.uppercase(),
        )
    }

    fun encodeList(profiles: List<ConnectionProfile>): String = JsonWriter.write(profiles.map(::encode))

    /** Decodes a list, skipping (and reporting) entries that fail validation. */
    fun decodeList(json: String, onError: (String) -> Unit = {}): List<ConnectionProfile> {
        val items = runCatching { MiniJson.parse(json) as? List<*> }.getOrNull() ?: return emptyList()
        return items.mapNotNull { item ->
            try {
                decode(item as? Map<*, *> ?: throw IllegalArgumentException("Not an object"))
            } catch (e: IllegalArgumentException) {
                onError(e.message ?: "invalid profile")
                null
            }
        }
    }
}
