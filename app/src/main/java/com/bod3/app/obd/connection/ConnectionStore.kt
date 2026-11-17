package com.bod3.app.obd.connection

import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.profile.JsonWriter
import com.bod3.app.profile.MiniJson

/** Minimal string key-value storage: SharedPreferences on Android, a map in tests. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

class InMemoryKeyValueStore : KeyValueStore {
    private val values = HashMap<String, String>()
    override fun get(key: String) = synchronized(values) { values[key] }
    override fun put(key: String, value: String?) {
        synchronized(values) { if (value == null) values.remove(key) else values[key] = value }
    }
}

/** A protocol that worked for an adapter, remembered so the next connection tries it first. */
data class RememberedProtocol(val adapterAddress: String, val protocol: ObdProtocol, val atdp: String?, val savedAtMs: Long)

/**
 * Connection profiles, the active profile, the protocol that last worked per adapter, and the Expert
 * mode switch.
 */
class ConnectionStore(private val store: KeyValueStore, private val clock: () -> Long = System::currentTimeMillis) {

    fun profiles(): List<ConnectionProfile> {
        val saved = store.get(KEY_PROFILES)?.let { ConnectionProfileCodec.decodeList(it) }.orEmpty()
        // Built-in profiles always exist; a saved profile with the same id overrides it.
        val builtIns = ConnectionProfile.BUILT_IN.filter { builtIn -> saved.none { it.id == builtIn.id } }
        return builtIns + saved
    }

    fun profile(id: String): ConnectionProfile? = profiles().firstOrNull { it.id == id }

    fun saveProfile(profile: ConnectionProfile) {
        val saved = store.get(KEY_PROFILES)?.let { ConnectionProfileCodec.decodeList(it) }.orEmpty()
        val updated = saved.filterNot { it.id == profile.id } + profile
        store.put(KEY_PROFILES, ConnectionProfileCodec.encodeList(updated))
    }

    fun deleteProfile(id: String) {
        require(id != ConnectionProfile.DEFAULT_ID) { "The default profile cannot be deleted" }
        val saved = store.get(KEY_PROFILES)?.let { ConnectionProfileCodec.decodeList(it) }.orEmpty()
        store.put(KEY_PROFILES, ConnectionProfileCodec.encodeList(saved.filterNot { it.id == id }))
        if (activeProfileId() == id) setActiveProfile(ConnectionProfile.DEFAULT_ID)
    }

    fun activeProfileId(): String = store.get(KEY_ACTIVE)?.takeIf { profile(it) != null } ?: ConnectionProfile.DEFAULT_ID

    fun activeProfile(): ConnectionProfile = profile(activeProfileId()) ?: ConnectionProfile.DEFAULT

    fun setActiveProfile(id: String) {
        require(profile(id) != null) { "Unknown profile $id" }
        store.put(KEY_ACTIVE, id)
    }

    fun rememberedProtocol(adapterAddress: String): RememberedProtocol? = remembered().firstOrNull { it.adapterAddress == adapterAddress }

    fun rememberProtocol(adapterAddress: String, protocol: ObdProtocol, atdp: String?) {
        val updated = remembered().filterNot { it.adapterAddress == adapterAddress } +
            RememberedProtocol(adapterAddress, protocol, atdp, clock())
        writeRemembered(updated)
    }

    fun forgetProtocol(adapterAddress: String) = writeRemembered(remembered().filterNot { it.adapterAddress == adapterAddress })

    fun remembered(): List<RememberedProtocol> {
        val items = store.get(KEY_REMEMBERED)?.let { runCatching { MiniJson.parse(it) as? List<*> }.getOrNull() }.orEmpty()
        return items.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val protocol = ObdProtocol.entries.firstOrNull { it.name == map["protocol"] } ?: return@mapNotNull null
            RememberedProtocol(
                adapterAddress = map["adapter"] as? String ?: return@mapNotNull null,
                protocol = protocol,
                atdp = map["atdp"] as? String,
                savedAtMs = (map["savedAt"] as? Double)?.toLong() ?: 0L,
            )
        }
    }

    private fun writeRemembered(items: List<RememberedProtocol>) {
        store.put(
            KEY_REMEMBERED,
            JsonWriter.write(items.map { mapOf("adapter" to it.adapterAddress, "protocol" to it.protocol.name, "atdp" to it.atdp, "savedAt" to it.savedAtMs) }),
        )
    }

    // ---- Expert mode ----

    /** Expert mode is off by default and can only be turned on after acknowledging the risks. */
    val expertModeEnabled: Boolean get() = store.get(KEY_EXPERT) == "on" && expertRiskAcknowledgedAtMs != null

    val expertRiskAcknowledgedAtMs: Long? get() = store.get(KEY_EXPERT_ACK)?.toLongOrNull()

    fun enableExpertMode(riskAcknowledged: Boolean) {
        require(riskAcknowledged) { "Expert mode requires acknowledging the risks" }
        store.put(KEY_EXPERT_ACK, clock().toString())
        store.put(KEY_EXPERT, "on")
    }

    fun disableExpertMode() = store.put(KEY_EXPERT, null)

    private companion object {
        const val KEY_PROFILES = "connection.profiles"
        const val KEY_ACTIVE = "connection.active"
        const val KEY_REMEMBERED = "connection.rememberedProtocols"
        const val KEY_EXPERT = "expert.enabled"
        const val KEY_EXPERT_ACK = "expert.acknowledgedAt"
    }
}
