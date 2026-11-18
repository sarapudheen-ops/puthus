package com.bod3.app.obd.session

import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.testing.SimModule
import com.bod3.app.obd.testing.SimUdsEcu
import com.bod3.app.obd.testing.SimulatedCanAdapter
import com.bod3.app.obd.uds.SecurityAccessSpec
import com.bod3.app.obd.uds.SecurityAlgorithm
import com.bod3.app.obd.uds.UdsOutcome
import com.bod3.app.obd.uds.UdsWriteClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WritePolicyTest {

    private val bcm = EcuTarget.Physical("70E", "778")
    private val other = EcuTarget.Physical("714", "77E")

    @Test
    fun actuatorWhitelist() {
        val ok = listOf(
            listOf(0x10, 0x03), listOf(0x10, 0x01), listOf(0x27, 0x03), listOf(0x27, 0x04, 1, 2, 3, 4), listOf(0x3E, 0x00),
            listOf(0x2F, 0x05, 0x10, 0x03, 0x01), listOf(0x2F, 0x05, 0x10, 0x00), listOf(0x31, 0x01, 0x02, 0x00), listOf(0x31, 0x03, 0x02, 0x00),
        )
        ok.forEach { assertTrue(it.toString(), WritePolicy.isAllowed(WritePurpose.ACTUATOR_TEST, it)) }
        val refused = listOf(
            listOf(0x10, 0x02), // programming session
            listOf(0x10, 0x60), // manufacturer session
            listOf(0x11, 0x01), // ECU reset
            listOf(0x28, 0x03, 0x01), // communication control
            listOf(0x85, 0x02), // DTC setting off
            listOf(0x34, 0x00, 0x44), // request download
            listOf(0x3D, 0x14, 0, 0, 0), // write memory
            listOf(0x2E, 0x06, 0x00, 0x01), // write DID: coding only
            listOf(0x2F, 0x05, 0x10), // no ioControlParameter
            listOf(0x2F, 0x05, 0x10, 0x04), // reserved parameter
            listOf(0x31, 0x04, 0x02, 0x00), // reserved sub-function
            listOf(0x27, 0x00),
        )
        refused.forEach { assertFalse(it.toString(), WritePolicy.isAllowed(WritePurpose.ACTUATOR_TEST, it)) }
        assertTrue(WritePolicy.isAllowed(WritePurpose.CODING, listOf(0x2E, 0x06, 0x00, 0x01)))
        assertFalse(WritePolicy.isAllowed(WritePurpose.CODING, listOf(0x2F, 0x05, 0x10, 0x03, 0x01)))
        assertFalse(WritePolicy.isAllowed(WritePurpose.CODING, listOf(0x31, 0x01, 0x02, 0x00)))
    }

    @Test
    fun gateRefusesWithoutExpertMode() {
        assertThrows(ExpertModeRequiredException::class.java) {
            WriteGate.issue(WritePurpose.ACTUATOR_TEST, bcm, expertModeEnabled = false)
        }
    }

    @Test
    fun grantIsBoundToTargetAndExpires() {
        var now = 0L
        val grant = WriteGate.issue(WritePurpose.ACTUATOR_TEST, bcm, true, { now }, maxDurationMs = 1_000)
        grant.check(listOf(0x10, 0x03), bcm)
        assertThrows(WriteNotPermittedException::class.java) { grant.check(listOf(0x10, 0x03), other) }
        assertThrows(WriteNotPermittedException::class.java) { grant.check(listOf(0x10, 0x02), bcm) }
        now = 1_001
        assertFalse(grant.isActive)
        assertThrows(WriteNotPermittedException::class.java) { grant.check(listOf(0x10, 0x03), bcm) }
        val second = WriteGate.issue(WritePurpose.ACTUATOR_TEST, bcm, true, { now })
        second.revoke()
        assertThrows(WriteNotPermittedException::class.java) { second.check(listOf(0x10, 0x03), bcm) }
    }

    private fun bench(ecu: SimUdsEcu): Pair<SimulatedCanAdapter, Session> {
        val adapter = SimulatedCanAdapter(listOf(SimModule('6', "70E", "778", respond = ecu::respond)))
        return adapter to Session(adapter, ObdProtocol.ISO_15765_4_CAN_11_500)
    }

    @Test
    fun sessionRefusesWritesWithoutGrant() = runBlocking<Unit> {
        val (adapter, session) = bench(SimUdsEcu(ioDids = setOf(0x0510)))
        for (request in listOf(listOf(0x10, 0x03), listOf(0x2F, 0x05, 0x10, 0x03, 0x01), listOf(0x2E, 0x06, 0x00, 0x01), listOf(0x27, 0x03))) {
            assertThrows(ReadOnlyViolationException::class.java) { runBlocking { session.request(bcm, request) } }
        }
        assertTrue(adapter.writes.isEmpty())
        assertTrue(adapter.violations.isEmpty())
    }

    @Test
    fun grantOnlyWorksForItsModule() = runBlocking<Unit> {
        val (adapter, session) = bench(SimUdsEcu(ioDids = setOf(0x0510)))
        val grant = WriteGate.issue(WritePurpose.ACTUATOR_TEST, bcm, true)
        assertThrows(WriteNotPermittedException::class.java) { runBlocking { session.request(other, listOf(0x10, 0x03), grant = grant) } }
        assertThrows(WriteNotPermittedException::class.java) { runBlocking { session.request(bcm, listOf(0x11, 0x01), grant = grant) } }
        assertTrue(adapter.writes.isEmpty())
        val response = session.request(bcm, listOf(0x10, 0x03), grant = grant)
        assertEquals(0x50, response.messages.single().payload[0])
        assertEquals(1, adapter.writes.size)
    }

    @Test
    fun securityAccessSeedPlusConstant() = runBlocking<Unit> {
        val spec = SecurityAccessSpec(0x03, SecurityAlgorithm.SEED_PLUS_CONSTANT, listOf(0x00, 0x01, 0x23, 0x45))
        assertEquals(listOf(0x12, 0x35, 0x79, 0xBD), spec.keyFor(listOf(0x12, 0x34, 0x56, 0x78)))
        assertEquals(listOf(0x00, 0x00), SecurityAccessSpec(0x01, SecurityAlgorithm.SEED_PLUS_CONSTANT, listOf(0x01)).keyFor(listOf(0xFF, 0xFF)))
        assertEquals(listOf(0x12 xor 0xAA, 0x34 xor 0x55), SecurityAccessSpec(0x01, SecurityAlgorithm.SEED_XOR_CONSTANT, listOf(0xAA, 0x55)).keyFor(listOf(0x12, 0x34)))

        val ecu = SimUdsEcu(security = mapOf(0x03 to spec), ioDids = setOf(0x0510), requiredLevel = 0x03)
        val (_, session) = bench(ecu)
        val client = UdsWriteClient(session, WriteGate.issue(WritePurpose.ACTUATOR_TEST, bcm, true))
        assertTrue(client.enterSession(0x03).isPositive)
        assertTrue(client.unlock(spec).isPositive)
        assertEquals(0x03, ecu.unlockedLevel)

        assertTrue(client.enterSession(0x03).isPositive) // re-entering the session locks the module again
        val wrong = SecurityAccessSpec(0x03, SecurityAlgorithm.STATIC_KEY, listOf(1, 2, 3, 4))
        assertEquals(UdsOutcome.Negative(0x35), client.unlock(wrong))
    }

    @Test
    fun responsePendingThenPositiveAndPendingOnly() = runBlocking<Unit> {
        val ecu = SimUdsEcu(ioDids = setOf(0x0510))
        val (_, session) = bench(ecu)
        val client = UdsWriteClient(session, WriteGate.issue(WritePurpose.ACTUATOR_TEST, bcm, true))
        ecu.pendingFirst += 0x10
        assertTrue(client.enterSession(0x03).isPositive)
        ecu.pendingOnly += 0x2F
        assertEquals(UdsOutcome.Pending, client.send(listOf(0x2F, 0x05, 0x10, 0x03, 0x01)))
        assertEquals(1, ecu.received.count { it[0] == 0x2F }) // never re-sent automatically
    }

    @Test
    fun connectionLossIsAnOutcome() = runBlocking<Unit> {
        val (adapter, session) = bench(SimUdsEcu())
        val client = UdsWriteClient(session, WriteGate.issue(WritePurpose.ACTUATOR_TEST, bcm, true))
        adapter.disconnected = true
        assertTrue(client.enterSession(0x03) is UdsOutcome.ConnectionLost)
        adapter.disconnected = false
        adapter.failWith = "CAN ERROR"
        session.invalidateRouting()
        assertTrue(client.enterSession(0x03) is UdsOutcome.AdapterError)
    }
}
