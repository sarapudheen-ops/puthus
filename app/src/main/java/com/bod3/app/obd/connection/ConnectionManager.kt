package com.bod3.app.obd.connection

import com.bod3.app.obd.elm.AdapterDriver
import com.bod3.app.obd.elm.AdapterInfo
import com.bod3.app.obd.transport.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** Everything learned while connecting. [negotiation] may have failed (e.g. ignition off). */
data class ConnectionResult(
    val driver: AdapterDriver,
    val adapter: AdapterInfo,
    val profile: ConnectionProfile,
    val setup: List<SetupStep>,
    val negotiation: NegotiationResult,
)

/**
 * Connects to an adapter: opens the transport, initialises the adapter (capability detection included),
 * applies the connection profile, and negotiates the vehicle protocol. Remembers the protocol that worked
 * for this adapter so the next connection (and every reconnect) tries it first.
 *
 * A failed protocol negotiation does not fail the connection: the adapter stays connected so the user
 * can turn the ignition on and retry with [detectProtocol].
 */
class ConnectionManager(
    private val scope: CoroutineScope,
    private val store: ConnectionStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logger: (String) -> Unit = {},
) {

    suspend fun connect(
        transport: Transport,
        adapterAddress: String,
        profile: ConnectionProfile,
        onStep: (String) -> Unit = {},
    ): ConnectionResult {
        val driver = AdapterDriver(transport, scope, ioDispatcher, logger)
        try {
            onStep("Opening Bluetooth connection…")
            driver.open()
            onStep("Initialising adapter…")
            val adapter = driver.initialize()
            val setup = AdapterSetup.apply(driver, profile, adapter.capabilities, logger)
            onStep("Detecting vehicle protocol…")
            val negotiation = detectProtocol(driver, adapterAddress, profile)
            return ConnectionResult(driver, adapter, profile, setup, negotiation)
        } catch (e: CancellationException) {
            driver.close()
            throw e
        } catch (e: Exception) {
            driver.close()
            throw e
        }
    }

    suspend fun detectProtocol(driver: AdapterDriver, adapterAddress: String, profile: ConnectionProfile): NegotiationResult {
        val remembered = store.rememberedProtocol(adapterAddress)?.protocol
        if (remembered != null) logger("Trying the protocol that last worked with this adapter first: ${remembered.displayName}")
        val result = ProtocolNegotiator(driver, logger).negotiate(profile, remembered)
        result.protocol?.let { store.rememberProtocol(adapterAddress, it.protocol, it.description) }
        return result
    }
}
