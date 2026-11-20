package com.bod3.app.catalog

import com.bod3.app.datapack.DtcGroup
import com.bod3.app.obd.connection.ConnectionProfile
import org.junit.Assert.*
import org.junit.Test

/**
 * A manual selection must describe what the level really supplies. Names, years and DTC text are not
 * executable coverage, so the confirmation may never promise an ECU list or protocol overrides.
 */
class CatalogCoverageTest {
    private val starter = CatalogCodec.decodeOrThrow(asset("catalog/starter.json").readText())

    /** The shipped names-only starter path: Toyota → Innova → AN40 2004–2015. */
    private val innovaAn40: CatalogNode by lazy {
        val tree = CatalogTree(starter)
        val toyota = tree.children(null).single { it.name == "Toyota" }
        val innova = tree.children(toyota.id).single { it.name == "Innova" }
        tree.children(innova.id).single { it.name == "AN40" }
    }

    @Test fun namesOnlyNodeSelectsNoEcuPidOrOverrideAndSaysGenericObdOnly() {
        val repo = CatalogRepository(starter, CatalogMemoryStore())
        val access = CatalogAccess(repo)
        repo.select(innovaAn40.id)

        val selected = repo.selected()!!
        assertEquals(CatalogCoverage(0, 0, 0), selected.coverage)
        assertFalse(selected.coverage.executable)
        assertTrue(access.effective().ecus.isEmpty())
        assertTrue(access.effective().pids.isEmpty())
        assertTrue(access.effective().connection.values.isEmpty())
        assertTrue(access.scanEcus().isEmpty())
        // The active profile is used unchanged: no protocol is forced or suppressed by this selection.
        assertNull(repo.connection(ConnectionProfile.DEFAULT).forcedProtocol)
        assertTrue(repo.connection(ConnectionProfile.DEFAULT).tryFallbacks)

        assertEquals("Name/year only — no enhanced ECU or connection definitions; generic OBD only.", CatalogCoverage.NO_ENHANCED_DATA)
        assertTrue(selected.selectionMessage.startsWith("Selected Toyota / Innova / AN40"))
        assertTrue(selected.selectionMessage.endsWith(CatalogCoverage.NO_ENHANCED_DATA))
        assertFalse(selected.selectionMessage.contains("will be used"))
    }

    @Test fun everyStarterSeedLevelIsNamesAndYearsOnly() {
        val tree = CatalogTree(starter)
        assertTrue(starter.nodes.isNotEmpty())
        starter.nodes.forEach { node ->
            val resolved = tree.resolve(node.id)
            assertNotNull("${node.name} must resolve", resolved)
            assertFalse("${node.name} must not claim executable data", resolved!!.coverage.executable)
        }
    }

    @Test fun levelWithEcusPidsAndOverridesReportsCountsAndAppliesThemAsBefore() {
        val base = fixture(generationNode.copy(data = CatalogData(
            ecus = listOf(engine, brakes),
            pids = listOf(pid()),
            connection = CatalogConnection(mapOf("requestTimeoutMs" to 7_000, "tryFallbacks" to false)),
        )))
        val repo = CatalogRepository(base, CatalogMemoryStore())
        val access = CatalogAccess(repo)
        repo.select("generation")

        val selected = repo.selected()!!
        assertEquals(CatalogCoverage(ecus = 2, pids = 1, connectionOverrides = 2), selected.coverage)
        assertTrue(selected.coverage.executable)
        assertEquals("2 ECUs · 1 PID · 2 connection overrides", selected.coverage.counts)
        assertTrue(selected.selectionMessage.contains("2 ECUs · 1 PID · 2 connection overrides"))
        assertTrue(selected.selectionMessage.endsWith("will be used on the next connection."))

        // Unchanged behaviour: the same ECU list, PIDs and connection overrides still apply.
        assertEquals(7_000L, repo.connection(ConnectionProfile.DEFAULT).requestTimeoutMs)
        assertFalse(repo.connection(ConnectionProfile.DEFAULT).tryFallbacks)
        assertEquals(listOf("Test brakes", "Test engine"), access.scanEcus().map { it.name }.sorted())
        assertEquals(listOf("test-rpm"), access.customPids().map { it.id })
    }

    @Test fun dtcTextAloneIsNotExecutableCoverage() {
        val data = CatalogData(dtcs = mapOf("P1234" to "Documented meaning"),
            dtcGroups = listOf(DtcGroup("reference", listOf("Toyota"), mapOf("P1235" to "Scoped text"), "Test source")))
        assertTrue(data.count > 0)
        assertEquals(CatalogCoverage(0, 0, 0), data.coverage)
        assertFalse(data.coverage.executable)
        assertEquals(CatalogCoverage.NO_ENHANCED_DATA, data.coverage.label)

        val repo = CatalogRepository(fixture(generationNode.copy(data = data)), CatalogMemoryStore())
        repo.select("generation")
        assertTrue(repo.selected()!!.selectionMessage.endsWith(CatalogCoverage.NO_ENHANCED_DATA))
    }

    @Test fun countsReadNaturallyInSingularAndPlural() {
        assertEquals("1 ECU · 1 PID · 1 connection override", CatalogCoverage(1, 1, 1).counts)
        assertEquals("3 ECUs · 12 PIDs · 2 connection overrides", CatalogCoverage(3, 12, 2).counts)
        assertEquals(CatalogCoverage.NO_ENHANCED_DATA, CatalogCoverage(0, 0, 0).label)
        assertEquals("1 ECU · 0 PIDs · 0 connection overrides", CatalogCoverage(1, 0, 0).label)
    }
}
