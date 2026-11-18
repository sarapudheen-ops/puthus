package com.bod3.app.catalog

import com.bod3.app.datapack.DtcGroup
import com.bod3.app.datapack.ModuleNameEntry
import com.bod3.app.obd.connection.ConnectionProfile
import com.bod3.app.obd.protocol.ObdProtocol
import org.junit.Assert.*
import org.junit.Test

class CatalogInheritanceTest {
    @Test fun everyKindOfDataCanBeDeclaredAndInheritedAtEveryLevel() {
        val shared = CatalogData(ecus = listOf(engine), pids = listOf(pid()), dtcs = mapOf("P1234" to "Parent text"),
            moduleNames = listOf(ModuleNameEntry("7E0", "Test engine")), actuators = listOf(actuator()), coding = listOf(coding()), security = listOf(security()),
            connection = CatalogConnection(mapOf("requestTimeoutMs" to 7_000)), dtcGroups = listOf(DtcGroup("reference", listOf("Toyota"), mapOf("P1235" to "Scoped text"), "Test source")))
        for (level in listOf(makeNode, modelNode, generationNode, variantNode)) {
            val tree = CatalogTree(fixture(level.copy(data = shared)))
            assertEquals(emptyList<String>(), tree.problems())
            assertEquals(shared.ecus, tree.resolve("variant")!!.data.ecus)
            assertEquals(shared.pids, tree.resolve("variant")!!.data.pids)
            assertEquals(shared.dtcs, tree.resolve("variant")!!.data.dtcs)
            assertEquals(shared.moduleNames, tree.resolve("variant")!!.data.moduleNames)
            assertEquals(shared.actuators, tree.resolve("variant")!!.data.actuators)
            assertEquals(shared.coding, tree.resolve("variant")!!.data.coding)
            assertEquals(shared.security, tree.resolve("variant")!!.data.security)
            assertEquals(shared.dtcGroups, tree.resolve("variant")!!.data.dtcGroups)
            assertEquals(7_000L, tree.resolve("variant")!!.connection(ConnectionProfile.DEFAULT).requestTimeoutMs)
        }
    }

    @Test fun lowerLevelsOverrideByStableKeysWithoutMutatingParentsOrSiblings() {
        val parent = makeNode.copy(data = CatalogData(ecus = listOf(engine, brakes), pids = listOf(pid()), dtcs = mapOf("P1234" to "Make"), actuators = listOf(actuator()), coding = listOf(coding()), security = listOf(security())))
        val child = variantNode.copy(data = CatalogData(ecus = listOf(engine.copy(name = "Variant engine")), pids = listOf(pid().copy(formula = "A")),
            dtcs = mapOf("P1234" to "Variant"), actuators = listOf(actuator().copy(name = "Variant output")), coding = listOf(coding().copy(name = "Variant coding")), security = listOf(security().copy(name = "Variant access"))))
        val sibling = variantNode.copy(id = "other-variant", name = "Other variant", engine = "Other engine")
        val tree = CatalogTree(fixture(parent, child, sibling))
        val resolved = tree.resolve("variant")!!
        assertEquals(listOf("Variant engine", "Test brakes"), resolved.data.ecus.map { it.name })
        assertEquals("A", resolved.data.pids.single().formula)
        assertEquals("Variant", resolved.data.dtcs["P1234"])
        assertEquals("Variant output", resolved.data.actuators.single().name)
        assertEquals("Variant coding", resolved.data.coding.single().name)
        assertEquals("Variant access", resolved.data.security.single().name)
        assertEquals("Test engine", tree.resolve("other-variant")!!.data.ecus.first().name)
        assertEquals("Make", tree.resolve("make")!!.data.dtcs["P1234"])
        assertTrue(resolved.origins.getValue("ecus:7E0").contains("Test variant"))
        assertEquals(1, parent.data.pids.size)
    }

    @Test fun exclusionsSuppressInheritanceAndGrandchildrenMayRestoreAnItem() {
        val shared = CatalogData(ecus = listOf(engine), pids = listOf(pid()), dtcs = mapOf("P1234" to "Make"), actuators = listOf(actuator()), coding = listOf(coding()), security = listOf(security()))
        val suppressed = generationNode.copy(data = CatalogData(exclude = CatalogExclusions(ecus = setOf(engine.key), pids = setOf(pid().id), dtcs = setOf("P1234"), actuators = setOf(actuator().id), coding = setOf(coding().id), security = setOf(security().id))))
        val tree = CatalogTree(fixture(makeNode.copy(data = shared), suppressed))
        assertEquals(0, tree.resolve("generation")!!.data.count)
        assertEquals(0, tree.resolve("variant")!!.data.count)
        assertFalse(tree.resolve("variant")!!.origins.containsKey("ecus:7E0"))
        val restored = CatalogTree(fixture(makeNode.copy(data = shared), suppressed, variantNode.copy(data = CatalogData(ecus = listOf(engine.copy(name = "Restored"))))))
        assertEquals("Restored", restored.resolve("variant")!!.data.ecus.single().name)
        assertEquals(emptyList<Any>(), restored.resolve("variant")!!.data.pids)
    }

    @Test fun platformIsAppliedBeforeLocalDataAndSharedOnlyOnce() {
        val platform = CatalogPlatform("platform", "Known platform", CatalogData(ecus = listOf(engine, brakes), coding = listOf(coding())))
        val node = generationNode.copy(platformIds = listOf(platform.id), data = CatalogData(ecus = listOf(engine.copy(name = "Local override"))))
        val pack = fixture(node).copy(platforms = listOf(platform))
        val result = CatalogTree(pack).resolve("variant")!!
        assertEquals("Local override", result.data.ecus.first().name)
        assertEquals("Platform: Known platform", result.origins["ecus:${brakes.key}"])
        assertEquals("Test engine", platform.data.ecus.first().name)
        assertEquals(1, pack.platforms.single().data.coding.size)
        assertTrue(pack.nodes.filterNot { it.id == node.id }.all { it.data.count == 0 })
    }

    @Test fun connectionOverridesAreSparseAndNullReallyClearsAnOption() {
        val parent = makeNode.copy(data = CatalogData(connection = CatalogConnection(mapOf("requestTimeoutMs" to 8_000, "extendedAddress" to "01", "broadcastFilter" to "7E8", "broadcastMask" to "7F8"))))
        val child = variantNode.copy(data = CatalogData(connection = CatalogConnection(mapOf("searchTimeoutMs" to 30_000, "extendedAddress" to null, "forcedProtocol" to ObdProtocol.ISO_14230_4_KWP_FAST.name))))
        val adapter = ConnectionProfile.DEFAULT.copy(adaptiveTiming = 2, useVtTimeout = true, canBaudKbps = 250.0)
        val tree = CatalogTree(fixture(parent, child))
        assertTrue(tree.problems().isEmpty())
        val resolved = tree.resolve("variant")!!.connection(adapter)
        assertEquals(8_000L, resolved.requestTimeoutMs)
        assertEquals(30_000L, resolved.searchTimeoutMs)
        assertNull(resolved.extendedAddress)
        assertEquals(2, resolved.adaptiveTiming)
        assertTrue(resolved.useVtTimeout)
        assertEquals(250.0, resolved.canBaudKbps!!, 0.0)
        assertEquals(ObdProtocol.ISO_14230_4_KWP_FAST, resolved.forcedProtocol)
        assertEquals("01", tree.resolve("make")!!.connection(adapter).extendedAddress)
    }

    @Test fun sparseChildMaySupplyOnlyOneHalfOfAnInheritedFilterPair() {
        val root = makeNode.copy(data = CatalogData(connection = CatalogConnection(mapOf("broadcastMask" to "7F8"))))
        val child = generationNode.copy(data = CatalogData(connection = CatalogConnection(mapOf("broadcastFilter" to "7E8"))))
        val tree = CatalogTree(fixture(root, child))
        assertTrue(tree.problems().toString(), tree.problems().isEmpty())
        assertEquals("7E8", tree.resolve("variant")!!.connection(ConnectionProfile.DEFAULT).broadcastFilter)
    }

    @Test fun noSelectionMeansNoEcuOrConnectionFallbackEvenWithAFullLibrary() {
        val base = fixture().copy(library = CatalogData(ecus = listOf(engine), connection = CatalogConnection(mapOf("requestTimeoutMs" to 42_000)), pids = listOf(pid())))
        val repo = CatalogRepository(base, CatalogMemoryStore())
        assertNull(repo.selected())
        assertEquals(ConnectionProfile.DEFAULT, repo.connection(ConnectionProfile.DEFAULT))
        repo.select("variant")
        assertTrue(repo.selected()!!.data.ecus.isEmpty())
        assertEquals(5_000L, repo.connection(ConnectionProfile.DEFAULT).requestTimeoutMs)
        assertEquals(1, CatalogAccess(repo).customPids().size) // Preserved legacy PID, not a generic vehicle.
    }

    @Test fun setupCannotSmuggleDiagnosticOrMemoryWritesPastTheReadOnlyPolicy() {
        for (command in listOf("2701", "2E123401", "STPXh:70E,d:2E123401", "STPX2E123401", "ATSD1234", "ATPP01SV00", "ATMA", "VTFACT", "ATZ\r2701")) {
            assertFalse(command, CatalogConnection.isConfigurationCommand(command))
            assertTrue(command, CatalogConnection(mapOf("extraInitCommands" to listOf(command))).problems().isNotEmpty())
        }
        for (command in listOf("STP33", "STPBR500", "VTP291", "STCSEGT1", "VTTOST FF", "ATCEA 01", "ATAT2")) assertTrue(command, CatalogConnection.isConfigurationCommand(command))
    }

    @Test fun mergeDeclarationsRetainsSuppressionTombstonesForFutureChildren() {
        val local = CatalogData(pids = listOf(pid()), exclude = CatalogExclusions(ecus = setOf(engine.key)))
        val imported = local.mergeDeclarations(CatalogData(pids = listOf(pid("other"))))
        assertEquals(setOf(engine.key), imported.exclude.ecus)
        assertEquals(2, imported.pids.size)
        assertTrue(CatalogData(ecus = listOf(engine)).overlay(imported).ecus.isEmpty())
    }
}
