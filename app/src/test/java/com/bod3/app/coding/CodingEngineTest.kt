package com.bod3.app.coding

import com.bod3.app.actuator.ActuatorModule
import com.bod3.app.actuator.Applicability
import com.bod3.app.actuator.VehicleContext
import com.bod3.app.actuator.VehicleStateProbe
import com.bod3.app.audit.AuditKind
import com.bod3.app.audit.AuditStore
import com.bod3.app.obd.connection.InMemoryKeyValueStore
import com.bod3.app.obd.protocol.ObdProtocol
import com.bod3.app.obd.session.EcuTarget
import com.bod3.app.obd.session.Session
import com.bod3.app.obd.testing.SimModule
import com.bod3.app.obd.testing.SimUdsEcu
import com.bod3.app.obd.testing.SimulatedCanAdapter
import com.bod3.app.obd.uds.KnownDids
import com.bod3.app.obd.uds.SecurityAccessSpec
import com.bod3.app.obd.uds.SecurityAlgorithm
import com.bod3.app.obd.transport.TransportException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodingEngineTest {

    class FakeProbe(
        var volts: Double? = 12.6,
        var rpm: Int? = 0,
        var speed: Int? = 0,
        var ids: Map<Int, String> = mapOf(KnownDids.SPARE_PART_NUMBER.id to "5Q0937084AB", KnownDids.SOFTWARE_VERSION.id to "0123"),
    ) : VehicleStateProbe {
        override suspend fun batteryVoltage() = volts
        override suspend fun engineRpm() = rpm
        override suspend fun vehicleSpeedKmh() = speed
        override suspend fun readIdentification(target: EcuTarget.Physical, did: Int) = ids[did]
    }

    private val key = SecurityAccessSpec(0x03, SecurityAlgorithm.SEED_PLUS_CONSTANT, listOf(0x00, 0x01, 0x23, 0x45))
    private val original = listOf(0x00, 0x1B, 0x0C, 0x01, 0x80, 0xFF)

    private val comfort = CodingRecipe(
        id = "vw-09-comfort-closing", name = "Comfort closing",
        module = ActuatorModule("Central electronics", "09", "70E", "778"), method = CodingMethod.LONG_CODING,
        did = 0x0600, byteIndex = 3, mask = 0x04, options = listOf(CodingOption(0, "Off"), CodingOption(1, "On")),
        security = key, applicability = Applicability(partNumbers = listOf("5Q0937084")),
    )

    private val vehicle = VehicleContext("Volkswagen", "Tiguan", "WVGZZZ5NZJW000001")

    private inner class Bench(
        val ecu: SimUdsEcu = SimUdsEcu(
            dids = mutableMapOf(0x0600 to original), writableDids = setOf(0x0600), security = mapOf(0x03 to key), requiredLevel = 0x03,
        ),
        var expert: Boolean = true,
        val probe: FakeProbe = FakeProbe(),
    ) {
        var now = 1_000_000L
        var ids = 0
        val adapter = SimulatedCanAdapter(listOf(SimModule('6', "70E", "778", respond = ecu::respond)))
        val session = Session(adapter, ObdProtocol.ISO_15765_4_CAN_11_500)
        val audit = AuditStore(InMemoryKeyValueStore())
        val backups = CodingBackupStore(InMemoryKeyValueStore())
        val steps = mutableListOf<CodingStep>()
        val engine = CodingEngine(session, probe, { expert }, audit, backups, { now }, { "id-${++ids}" })

        fun read(recipe: CodingRecipe = comfort, override: Boolean = false): CodingReading =
            when (val r = runBlocking { engine.read(recipe, vehicle, override) }) {
                is ReadResult.Ready -> r.reading
                is ReadResult.Refused -> throw AssertionError("read refused: ${r.message}")
            }

        fun write(reading: CodingReading, value: Long, confirmation: CodingConfirmation = CodingConfirmation.forWrite(reading, value) { now }): CodingResult =
            runBlocking { engine.write(reading, value, confirmation, vehicle) { steps += it } }

        fun rollback(backup: CodingBackup): CodingResult =
            runBlocking { engine.rollback(backup, CodingConfirmation.forRollback(backup) { now }, vehicle) }

        fun sent(): List<List<Int>> = adapter.requests.filter { it.header == "70E" }.map { it.payload }
        fun writeRequests(): List<List<Int>> = sent().filter { it[0] == 0x2E }
        fun stored(): List<Int> = ecu.dids.getValue(0x0600)
    }

    private val turnedOn = original.toMutableList().also { it[3] = 0x05 }

    @Test
    fun readBacksUpThenWriteVerifiesAndChangesOnlyTheBit() {
        val bench = Bench()
        val reading = bench.read()
        assertEquals(0L, reading.value)
        assertEquals(original, reading.data)
        assertEquals("5Q0937084AB", reading.partNumber)
        assertEquals(original, bench.backups.get(reading.backup.id)!!.data)
        assertEquals(BackupStatus.SAVED, bench.backups.get(reading.backup.id)!!.status)
        assertTrue("reading does not write", bench.adapter.writes.isEmpty())

        val diff = bench.engine.diff(reading, 1)
        assertEquals(listOf(3), diff.changedBytes)
        assertEquals("Off (0)", diff.beforeText)
        assertEquals("On (1)", diff.afterText)

        val result = bench.write(reading, 1)
        assertEquals(result.message, CodingOutcome.VERIFIED, result.outcome)
        assertEquals(turnedOn, bench.stored())
        assertEquals(turnedOn, result.readBack)
        assertTrue(result.canRollback)
        assertEquals(
            listOf(
                listOf(0x22, 0x06, 0x00), listOf(0x22, 0x06, 0x00), listOf(0x10, 0x03), listOf(0x27, 0x03), listOf(0x27, 0x04, 0x12, 0x35, 0x79, 0xBD),
                listOf(0x2E, 0x06, 0x00) + turnedOn, listOf(0x22, 0x06, 0x00), listOf(0x10, 0x01),
            ),
            bench.sent(),
        )
        assertEquals(0x01, bench.ecu.session)
        assertTrue(bench.adapter.violations.isEmpty())
        assertEquals(listOf(CodingStep.CHECKS, CodingStep.REREAD, CodingStep.SESSION, CodingStep.SECURITY, CodingStep.WRITE, CodingStep.VERIFY, CodingStep.CLOSE), bench.steps)

        val record = bench.audit.records().single()
        assertEquals(AuditKind.CODING_WRITE, record.kind)
        assertTrue(record.success)
        assertEquals(vehicle.vin, record.vin)
        assertEquals("00 1B 0C 01 80 FF", record.details["Before"])
        assertEquals(reading.backup.id, record.details["Backup"])
        assertTrue(record.steps.any { it.request?.startsWith("2E 06 00") == true })
        assertEquals(BackupStatus.WRITTEN, bench.backups.get(reading.backup.id)!!.status)
    }

    @Test
    fun rollbackWritesTheBackupBack() {
        val bench = Bench()
        val reading = bench.read()
        assertEquals(CodingOutcome.VERIFIED, bench.write(reading, 1).outcome)
        val result = bench.rollback(bench.backups.get(reading.backup.id)!!)
        assertEquals(result.message, CodingOutcome.VERIFIED, result.outcome)
        assertEquals(original, bench.stored())
        assertEquals(turnedOn, result.before)
        assertEquals(listOf(0x2E, 0x06, 0x00) + original, bench.writeRequests().last())
        assertEquals(BackupStatus.ROLLED_BACK, bench.backups.get(reading.backup.id)!!.status)
        assertEquals(listOf(AuditKind.CODING_WRITE, AuditKind.CODING_ROLLBACK), bench.audit.records().map { it.kind })
        assertEquals(0x01, bench.ecu.session)
    }

    @Test
    fun rollbackWorksAfterRecipeIsGoneUsingBackupDetailsOnly() {
        val bench = Bench()
        val reading = bench.read()
        bench.write(reading, 1)
        // A fresh store with the persisted backup (e.g. after an app restart).
        val backup = bench.backups.all().single()
        assertEquals(key, backup.security)
        assertEquals(CodingOutcome.VERIFIED, bench.rollback(backup).outcome)
        assertEquals(original, bench.stored())
    }

    @Test
    fun refusedOutsideExpertModeWithoutSendingAnything() {
        val bench = Bench(expert = false)
        val reading = bench.read()
        val before = bench.sent().size
        val result = bench.write(reading, 1)
        assertEquals(CodingOutcome.REFUSED, result.outcome)
        assertTrue(result.message.contains("Expert mode"))
        assertEquals(before, bench.sent().size)
        assertTrue(bench.adapter.writes.isEmpty())
        assertEquals(original, bench.stored())
        val record = bench.audit.records().single()
        assertFalse(record.success)
        assertTrue(record.outcome.startsWith("Not written"))
        val rollback = bench.rollback(reading.backup)
        assertEquals(CodingOutcome.REFUSED, rollback.outcome)
        assertTrue(bench.adapter.writes.isEmpty())
    }

    @Test
    fun refusedOnLowBattery() {
        val bench = Bench(probe = FakeProbe(volts = 11.8))
        val result = bench.write(bench.read(), 1)
        assertEquals(CodingOutcome.REFUSED, result.outcome)
        assertTrue(result.message, result.message.contains("11.8 V"))
        assertTrue(bench.adapter.writes.isEmpty())
        assertFalse(bench.audit.records().single().success)
    }

    @Test
    fun refusedWhenBatteryVoltageUnknown() {
        val bench = Bench(probe = FakeProbe(volts = null))
        assertEquals(CodingOutcome.REFUSED, bench.write(bench.read(), 1).outcome)
        assertTrue(bench.adapter.writes.isEmpty())
    }

    @Test
    fun refusedWithEngineRunningUnlessRecipeAllowsIt() {
        val bench = Bench(probe = FakeProbe(rpm = 780))
        val result = bench.write(bench.read(), 1)
        assertEquals(CodingOutcome.REFUSED, result.outcome)
        assertTrue(result.message, result.message.contains("engine running"))
        assertTrue(bench.adapter.writes.isEmpty())

        val allowed = comfort.copy(preconditions = CodingPreconditions(allowEngineRunning = true))
        assertEquals(CodingOutcome.VERIFIED, bench.write(bench.read(allowed), 1).outcome)
    }

    @Test
    fun recipeMinimumVoltageIsHonoured() {
        val bench = Bench(probe = FakeProbe(volts = 12.3))
        val strict = comfort.copy(preconditions = CodingPreconditions(minVoltage = 12.5))
        assertEquals(CodingOutcome.REFUSED, bench.write(bench.read(strict), 1).outcome)
        assertEquals(CodingOutcome.VERIFIED, bench.write(bench.read(), 1).outcome)
    }

    @Test
    fun versionMismatchRefusesReadUnlessOverridden() {
        val bench = Bench(probe = FakeProbe(ids = mapOf(KnownDids.SPARE_PART_NUMBER.id to "3G0937084", KnownDids.SOFTWARE_VERSION.id to "0999")))
        val refused = runBlocking { bench.engine.read(comfort, vehicle) }
        assertTrue(refused is ReadResult.Refused)
        assertTrue((refused as ReadResult.Refused).message.contains("3G0937084"))
        assertTrue(bench.backups.all().isEmpty())
        assertTrue("nothing read from 0600", bench.sent().none { it == listOf(0x22, 0x06, 0x00) })

        val reading = bench.read(override = true)
        assertTrue(reading.versionOverridden)
        val result = bench.write(reading, 1)
        assertEquals(CodingOutcome.VERIFIED, result.outcome)
        assertEquals("yes", bench.audit.records().single().details["Version override"])
    }

    @Test
    fun unreadablePartNumberCountsAsMismatch() {
        val bench = Bench(probe = FakeProbe(ids = emptyMap()))
        assertTrue(runBlocking { bench.engine.read(comfort, vehicle) } is ReadResult.Refused)
    }

    @Test
    fun wrongSecurityKeyAbortsBeforeWriting() {
        val bench = Bench()
        val wrong = comfort.copy(security = key.copy(constant = listOf(0x00, 0x00, 0x00, 0x01)))
        val result = bench.write(bench.read(wrong), 1)
        assertEquals(CodingOutcome.ABORTED, result.outcome)
        assertTrue(result.message, result.message.contains("Security access failed"))
        assertTrue(bench.writeRequests().isEmpty())
        assertEquals(original, bench.stored())
        assertEquals(listOf(0x10, 0x01), bench.sent().last())
        assertEquals(0x01, bench.ecu.session)
        assertFalse(result.canRollback)
    }

    @Test
    fun negativeResponseOnWriteIsReportedAndNotRetried() {
        val bench = Bench()
        bench.ecu.nrcFor[0x2E] = 0x22
        val result = bench.write(bench.read(), 1)
        assertEquals(CodingOutcome.REJECTED, result.outcome)
        assertTrue(result.message, result.message.contains("refused"))
        assertEquals(1, bench.writeRequests().size)
        assertEquals(original, bench.stored())
        assertEquals(listOf(0x10, 0x01), bench.sent().last())
        assertEquals(BackupStatus.WRITE_FAILED, bench.backups.all().single().status)
    }

    @Test
    fun sessionRefusalAbortsCleanly() {
        val bench = Bench()
        bench.ecu.nrcFor[0x10] = 0x22
        bench.ecu.nrcTimes = 1
        val result = bench.write(bench.read(), 1)
        assertEquals(CodingOutcome.ABORTED, result.outcome)
        assertTrue(bench.writeRequests().isEmpty())
        assertTrue("no default-session request when the session never opened", bench.sent().none { it == listOf(0x10, 0x01) })
    }

    @Test
    fun connectionLossDuringWriteReportsUnknownAndOffersRollback() {
        val bench = Bench()
        val reading = bench.read()
        bench.adapter.onRequest = { if (it.payload[0] == 0x2E) bench.adapter.disconnected = true }
        val result = bench.write(reading, 1)
        assertEquals(CodingOutcome.UNKNOWN, result.outcome)
        assertTrue(result.message, result.message.contains("connection dropped"))
        assertTrue(result.canRollback)
        assertEquals("written once, never re-sent", 1, bench.writeRequests().size)
        assertEquals(BackupStatus.WRITE_UNKNOWN, bench.backups.all().single().status)
        val record = bench.audit.records().single()
        assertFalse(record.success)

        // Reconnect: rollback restores the original (module applied nothing here, so it is a no-op).
        bench.adapter.onRequest = {}
        bench.adapter.disconnected = false
        val rollback = bench.rollback(bench.backups.all().single())
        assertEquals(CodingOutcome.NO_CHANGE, rollback.outcome)
        assertEquals(original, bench.stored())
    }

    @Test
    fun connectionLossAfterModuleAppliedWriteThenRollbackRestores() {
        val ecu = SimUdsEcu(dids = mutableMapOf(0x0600 to original), writableDids = setOf(0x0600), security = mapOf(0x03 to key), requiredLevel = 0x03)
        val bench = Bench(ecu = ecu)
        val reading = bench.read()
        // The module applies the write but the reply never arrives.
        bench.adapter.onRequest = { req ->
            if (req.payload[0] == 0x2E) {
                ecu.respond(req.payload)
                throw TransportException("Bluetooth link lost")
            }
        }
        val result = bench.write(reading, 1)
        assertEquals(CodingOutcome.UNKNOWN, result.outcome)
        assertEquals(turnedOn, bench.stored())
        bench.adapter.onRequest = {}
        val rollback = bench.rollback(bench.backups.all().single())
        assertEquals(rollback.message, CodingOutcome.VERIFIED, rollback.outcome)
        assertEquals(original, bench.stored())
    }

    @Test
    fun readBackMismatchIsReportedWithRollback() {
        val bench = Bench()
        bench.ecu.corruptWrite = { data -> data.toMutableList().also { it[5] = 0x00 } }
        val reading = bench.read()
        val result = bench.write(reading, 1)
        assertEquals(CodingOutcome.VERIFY_FAILED, result.outcome)
        assertTrue(result.canRollback)
        assertEquals(1, bench.writeRequests().size)
        assertEquals(BackupStatus.WRITE_FAILED, bench.backups.all().single().status)
        assertFalse(bench.audit.records().single().success)

        bench.ecu.corruptWrite = null
        assertEquals(CodingOutcome.VERIFIED, bench.rollback(bench.backups.all().single()).outcome)
        assertEquals(original, bench.stored())
    }

    @Test
    fun silentlyIgnoredWriteIsRejected() {
        val bench = Bench()
        bench.ecu.corruptWrite = { original }
        val result = bench.write(bench.read(), 1)
        assertEquals(CodingOutcome.REJECTED, result.outcome)
    }

    @Test
    fun confirmationIsSingleUseBoundAndExpires() {
        val bench = Bench()
        val reading = bench.read()
        val confirmation = CodingConfirmation.forWrite(reading, 1) { bench.now }
        assertEquals(CodingOutcome.VERIFIED, bench.write(reading, 1, confirmation).outcome)

        val reread = bench.read()
        val reused = bench.write(reread, 0, confirmation)
        assertEquals(CodingOutcome.REFUSED, reused.outcome)
        assertTrue(reused.message, reused.message.contains("different change"))

        val forOther = CodingConfirmation.forWrite(reread, 1) { bench.now }
        assertEquals(CodingOutcome.REFUSED, bench.write(reread, 0, forOther).outcome)

        val fresh = CodingConfirmation.forWrite(reread, 0) { bench.now }
        bench.now += CodingConfirmation.VALID_FOR_MS + 1
        val expired = bench.write(reread, 0, fresh)
        assertTrue(expired.message, expired.message.contains("expired"))

        val again = CodingConfirmation.forWrite(reread, 0) { bench.now }
        assertEquals(CodingOutcome.VERIFIED, bench.write(reread, 0, again).outcome)
        val twice = bench.write(reread, 0, again)
        assertTrue(twice.message, twice.message.contains("already used"))
        assertEquals(original, bench.stored())
        assertEquals(2, bench.writeRequests().size)
    }

    @Test
    fun valueChangedSinceReadAbortsWithoutWriting() {
        val bench = Bench()
        val reading = bench.read()
        bench.ecu.dids[0x0600] = original.toMutableList().also { it[0] = 0x42 }
        val result = bench.write(reading, 1)
        assertEquals(CodingOutcome.ABORTED, result.outcome)
        assertTrue(result.message.contains("changed since it was read"))
        assertTrue(bench.adapter.writes.isEmpty())
    }

    @Test
    fun sameValueWritesNothing() {
        val bench = Bench()
        val result = bench.write(bench.read(), 0)
        assertEquals(CodingOutcome.NO_CHANGE, result.outcome)
        assertTrue(bench.adapter.writes.isEmpty())
    }

    @Test
    fun invalidValueIsRefused() {
        val bench = Bench()
        val result = bench.write(bench.read(), 2)
        assertEquals(CodingOutcome.REFUSED, result.outcome)
        assertTrue(bench.adapter.writes.isEmpty())
    }

    @Test
    fun unreadableOrShortIdentifierRefusesRead() {
        val bench = Bench()
        val missing = runBlocking { bench.engine.read(comfort.copy(did = 0x0601), vehicle) }
        assertTrue(missing is ReadResult.Refused)
        assertTrue((missing as ReadResult.Refused).message.contains("0601"))
        val short = runBlocking { bench.engine.read(comfort.copy(byteIndex = 9), vehicle) }
        assertTrue(short is ReadResult.Refused)
        assertTrue(bench.backups.all().isEmpty())
    }

    @Test
    fun adaptationValueWithoutSecurityUsesExtendedSession() {
        val ecu = SimUdsEcu(dids = mutableMapOf(0x2203 to listOf(0x3A, 0x98)), writableDids = setOf(0x2203))
        val bench = Bench(ecu = ecu)
        val interval = comfort.copy(
            id = "interval", method = CodingMethod.ADAPTATION, did = 0x2203, byteIndex = 0, mask = null, length = 2,
            options = emptyList(), min = 0, max = 30000, security = null, applicability = Applicability(),
        )
        val reading = bench.read(interval)
        assertEquals(15000L, reading.value)
        val result = bench.write(reading, 30000)
        assertEquals(result.message, CodingOutcome.VERIFIED, result.outcome)
        assertEquals(listOf(0x75, 0x30), ecu.dids[0x2203])
        assertTrue(bench.sent().none { it[0] == 0x27 })
        assertNotNull(bench.audit.records().single())
    }
}
