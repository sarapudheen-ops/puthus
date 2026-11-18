package com.bod3.app.source

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/** Actual SQLite queries against the SAME schema used by the recovery tool.
 * Synthetic bytes only; no vendor data, adapter or Android runtime needed. */
class SourceBrowserTest {
    private lateinit var connection: Connection
    private lateinit var browser: SourceBrowser

    @Before fun setup() {
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        javaClass.getResourceAsStream("/app_schema.sql")!!.bufferedReader().use { it.readText() }.split(';').filter { it.isNotBlank() }.forEach { sql ->
            connection.createStatement().use { it.execute(sql) }
        }
        browser = SourceBrowser(SourceSql { sql, args ->
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { i, value -> statement.setString(i + 1, value) }
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(SourceSqlRow((1..rows.metaData.columnCount).associate { rows.metaData.getColumnLabel(it) to rows.getObject(it) }))
                    }
                }
            }
        }, testManifest())
        source("MENU.BIN", 512, "menu-graph")
        source("SYSLIST_EU.BIN", 512, "vehicle-system-index")
        source("FUNCDATA_EU.BIN", 512, "function-directory")
        source("EU/Ecu_Data_00aB.BIN", 1024, "ecu-sections")
        source("SYSTEMENTER.BIN", 600, "opaque", "Unknown schema; no runnable definition")
        source("EU/Toyota_bin_00.BIN", 33376, "command-audit", "2 missing bytes; 1 invalid descriptor")
        source("EU/Toyota_bin_11.BIN", 64, "shared-table")
        source("EU/Toyota_bin_12.BIN", 128, "shared-table")
        insert("INSERT INTO data_table VALUES(?,?,?,?,?,?,?,?)", "EU/Ecu_Data_00aB.BIN#23", "EU/Ecu_Data_00aB.BIN", 23, 62, 2, 4, 20, 8)
        insert("INSERT INTO menu VALUES(?,?,?,?,?,?,?,?,?,?)", 0, null, 0, "00000001", "Show Menu", "Show Menu", 0, 2, 0, "00")
        insert("INSERT INTO menu VALUES(?,?,?,?,?,?,?,?,?,?)", 10, 0, 0, "00000002", "Manual Select", "Show Menu / Manual Select", 1, 1, 0, "00")
        insert("INSERT INTO menu VALUES(?,?,?,?,?,?,?,?,?,?)", 20, 10, 0, "00000003", "Innova", "Show Menu / Manual Select / Innova / 1GD-FTV", 1, 0, 126, "01 02")
        insert("INSERT INTO menu VALUES(?,?,?,?,?,?,?,?,?,?)", 30, 0, 1, "00000004", "VIN Search", "Show Menu / VIN Search / Innova", 0, 0, 0, "00")
        insert("INSERT INTO menu_edge VALUES(?,?,?,?,?,?)", 0, 0, 10, 1, 2, "0001")
        insert("INSERT INTO menu_edge VALUES(?,?,?,?,?,?)", 0, 1, 30, 1, 2, "0001")
        insert("INSERT INTO menu_edge VALUES(?,?,?,?,?,?)", 10, 0, 20, 1, 2, "0001")
        insert("INSERT INTO vehicle VALUES(?,?,?)", "EU", 861, 12)
        insert("INSERT INTO system VALUES(?,?,?,?,?,?,?,?,?,?)", "EU", 12, 0, 1, "Engine", "Diesel", "Ecu_Data_00ab.BIN", "Can1", 14, "01 00")
        insert("INSERT INTO system VALUES(?,?,?,?,?,?,?,?,?,?)", "EU", 12, 1, 999, "Flash", "", "Unresolved filename text", "FLASH", 51, "ff 00")
        insert("INSERT INTO function_index VALUES(?,?,?)", "EU", 1, "[1,100,104,0,4294967295,120,1]")
        for ((name, count) in listOf("TCODE_EN.GAG" to 1, "EXPRESS.GAG" to 1, "VEHICLEADAS.GAG" to 1)) {
            insert("INSERT INTO text_library VALUES(?,?,?,?)", name, count, if (name == "VEHICLEADAS.GAG") 1 else 0, "0".repeat(64))
        }
        insert("INSERT INTO text_record VALUES(?,?,?,?)", "TCODE_EN.GAG", "04012345", "P1234\u0000Engine speed\u0000", byteArrayOf())
        insert("INSERT INTO text_record VALUES(?,?,?,?)", "EXPRESS.GAG", "00000001", "ID(ABCD); return 1 + 2;", byteArrayOf())
        insert("INSERT INTO text_record VALUES(?,?,?,?)", "VEHICLEADAS.GAG", "D0000001", null, byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0))
        insert("INSERT INTO command VALUES(?,?,?,?,?,?)", "EU", 1, "21 00", null, 100, 2)
        insert("INSERT INTO command VALUES(?,?,?,?,?,?)", "EU", 2, "ff ff", null, 102, 2)
        insert("INSERT INTO command VALUES(?,?,?,?,?,?)", "EU", 3, "61 00", null, 104, 2)
        insert("INSERT INTO command VALUES(?,?,?,?,?,?)", "EU", 3258, null, "Byte range outside available/declared command blob", 33376, 2)
        insert("INSERT INTO frame VALUES(?,?,?,?,?,?)", "EU", 10001, 10001, 2, 10003, 18)
        insert("INSERT INTO function_frame VALUES(?,?,?,?,?,?,?,?)", "EU", 0, 1, 2, 3, 10001, 0, "00 ".repeat(16))
        insert("INSERT INTO function_frame VALUES(?,?,?,?,?,?,?,?)", "EU", 1, 1, 2, 3, 999, 1, "00 ".repeat(16))
        insert("INSERT INTO archive_entry VALUES(?,?,?,?)", "source/libDIAG.so", 1024, "ELF (excluded)", "0".repeat(64))
    }

    @After fun close() { connection.close() }

    @Test fun overviewExposesEveryRecoveredFamilyWithoutOperationalActions() {
        val page = load(SourceLocation.Overview)
        assertTrue(page.rows.any { it.destination == SourceLocation.Files(FileFilter.ISSUES) })
        assertTrue(page.rows.any { it.destination == SourceLocation.Libraries })
        assertTrue(page.explanation.contains("does not send"))
        assertTrue(page.rows.flatMap { it.links }.any { it.location == SourceLocation.Bindings(SourceRegion.EU) })
    }

    @Test fun defaultMenuUsesManualBranchOnlyAndCannotAutoselectFromVin() {
        val page = load(SourceLocation.Menu())
        assertEquals(listOf("Innova"), page.rows.map { it.title })
        assertFalse(page.rows.any { it.title.contains("VIN") })
        assertTrue(page.explanation.contains("unresolved"))
        assertEquals(1, load(SourceLocation.Menu(), "Innova").rows.size)
        assertEquals(2, load(SourceLocation.Menu(0, false), "Innova").rows.size)
        expectFailure { load(SourceLocation.Menu(30, true)) }
    }

    @Test fun menuLeafKeepsOpaqueParametersAndDoesNotInventVehicleKey() {
        val page = load(SourceLocation.Menu(20))
        assertTrue(page.rows.isEmpty())
        assertTrue(page.fields.any { it.name.contains("not vehicle key") && it.value == "20" })
        assertTrue(page.fields.any { it.name.contains("parameters") && it.value.contains("0001") })
        assertTrue(page.links.none { it.location is SourceLocation.Systems })
    }

    @Test fun validatedSystemLinksAreCaseInsensitiveButFlashAnomaliesStayVisible() {
        val page = load(SourceLocation.Systems(SourceRegion.EU, 861))
        assertEquals(2, page.rows.size)
        assertEquals(SourceLocation.FileDetail("EU/Ecu_Data_00aB.BIN"), page.rows[0].destination)
        assertEquals(SourceLocation.Function(SourceRegion.EU, 1), page.rows[0].links.single().location)
        assertTrue(page.rows[1].warning)
        assertNull(page.rows[1].destination)
        assertTrue(page.rows[1].links.isEmpty())
        assertEquals(1, load(SourceLocation.Systems(SourceRegion.EU, 861), "FLASH").rows.size)
    }

    @Test fun vehicleKeysAreExplicitNumericSourceKeysNotModelNames() {
        val page = load(SourceLocation.Vehicles(SourceRegion.EU), "861")
        assertEquals("Vehicle key 861", page.rows.single().title)
        assertEquals(SourceLocation.Systems(SourceRegion.EU, 861), page.rows.single().destination)
    }

    @Test fun ecuSlotsAndSchemaTagsAreNeverConflatedAndAllRowBytesAreReachable() {
        val page = load(SourceLocation.FileDetail("EU/Ecu_Data_00aB.BIN"))
        assertEquals("Section slot 23", page.rows.single().title)
        assertTrue(page.rows.single().detail.contains("Schema tag 62"))
        val table = load(page.rows.single().destination!!)
        assertEquals(2, table.rows.size)
        assertEquals("14 15 16 17", table.rows[0].detail)
        assertEquals("18 19 1a 1b", table.rows[1].detail)
        assertEquals(SourceLocation.FileBytes("EU/Ecu_Data_00aB.BIN", 20, 4), table.rows[0].destination)
        assertFalse(table.hasNext)
        expectFailure { load(SourceLocation.Table("EU/Ecu_Data_00aB.BIN#23"), offset = 3) }
    }

    @Test fun nonIntegralStrideNeverInventsRows() {
        insert("INSERT INTO data_table VALUES(?,?,?,?,?,?,?,?)", "SYSTEMENTER.BIN#5", "SYSTEMENTER.BIN", 5, 5, 2, null, 10, 3)
        val page = load(SourceLocation.Table("SYSTEMENTER.BIN#5"))
        assertTrue(page.rows.isEmpty())
        assertTrue(page.explanation.contains("No integral"))
        assertEquals(SourceLocation.FileBytes("SYSTEMENTER.BIN", 10, 3), page.links.last().location)
    }

    @Test fun byteViewsAreBoundedPagedAndPreserveUnknownPayloads() {
        val first = load(SourceLocation.FileBytes("SYSTEMENTER.BIN"))
        assertEquals(32, first.rows.size)
        assertTrue(first.hasNext)
        assertEquals(512, first.step)
        val last = load(SourceLocation.FileBytes("SYSTEMENTER.BIN"), offset = 512)
        assertEquals(6, last.rows.size)
        assertFalse(last.hasNext)
        expectFailure { load(SourceLocation.FileBytes("SYSTEMENTER.BIN", -1)) }
        expectFailure { load(SourceLocation.FileBytes("SYSTEMENTER.BIN", 599, 2)) }
        expectFailure { load(SourceLocation.FileBytes("SYSTEMENTER.BIN"), offset = 601) }
    }

    @Test fun textSearchSeesDescriptionsAfterNulAndRecordViewIsLossless() {
        val results = load(SourceLocation.Texts("TCODE_EN.GAG"), "engine")
        assertEquals(1, results.rows.size)
        assertTrue(results.rows.single().detail.contains("P1234\\0Engine speed\\0"))
        val record = load(SourceLocation.TextRecord("TCODE_EN.GAG", "04012345"))
        assertEquals("P1234\\0Engine speed\\0", record.rows.first().detail)
        assertTrue(record.rows.last().detail.contains("00 45 6e 67"))
    }

    @Test fun nonUtf8AndExpressRecordsRemainInertAndInspectable() {
        val raw = load(SourceLocation.TextRecord("VEHICLEADAS.GAG", "D0000001"))
        assertEquals("ff fe 00", raw.rows.single().detail)
        assertTrue(raw.explanation.contains("no guessed"))
        val express = load(SourceLocation.TextRecord("EXPRESS.GAG", "00000001"))
        assertTrue(express.rows.first().detail.contains("ID(ABCD)"))
        assertTrue(express.explanation.contains("inert"))
        assertTrue(express.rows.all { it.links.isEmpty() && it.destination == null })
    }

    @Test fun longTextRecordsUseBytePaginationNotWholeLibraryLoading() {
        insert("UPDATE text_record SET value=? WHERE library=?", "long ".repeat(1000), "EXPRESS.GAG")
        val page = load(SourceLocation.TextRecord("EXPRESS.GAG", "00000001"))
        assertEquals(2048, page.rows.first().detail.length)
        assertTrue(page.hasNext)
        assertEquals(2048, page.step)
        assertFalse(load(SourceLocation.TextRecord("EXPRESS.GAG", "00000001"), offset = 4096).hasNext)
    }

    @Test fun frameJoinsFollowNativeBiasAndDoNotAssignUnprovenFieldRoles() {
        val page = load(SourceLocation.Frame(SourceRegion.EU, 10001))
        assertEquals(listOf("21 00", "ff ff", "61 00"), page.rows.map { it.detail })
        assertEquals(SourceLocation.Command(SourceRegion.EU, 1), page.rows.first().destination)
        assertTrue(page.rows.none { it.title.contains("Request") || it.title.contains("Response") })
        assertEquals(0L, SourceBrowser.normalizeDictionaryId(0))
        assertEquals(10000L, SourceBrowser.normalizeDictionaryId(10000))
        assertEquals(1L, SourceBrowser.normalizeDictionaryId(10001))
        expectFailure { SourceBrowser.normalizeDictionaryId(65536) }
    }

    @Test fun malformedTerminalDescriptorHasNoPaddedOrSendableBytes() {
        val page = load(SourceLocation.Command(SourceRegion.EU, 3258))
        assertTrue(page.rows.single().warning)
        assertTrue(page.rows.single().title.contains("INVALID"))
        assertTrue(page.rows.single().detail.contains("outside"))
        assertTrue(page.explanation.contains("no Send"))
        val all = load(SourceLocation.Commands(SourceRegion.EU))
        assertEquals(4, all.rows.size)
        assertTrue(all.rows.last().warning)
    }

    @Test fun onlyEligibleFunctionFrameRowsGetLinksNoNumericNamespaceGuessing() {
        val all = load(SourceLocation.Bindings(SourceRegion.EU))
        assertEquals(SourceLocation.Frame(SourceRegion.EU, 10001), all.rows[0].destination)
        assertNull(all.rows[1].destination)
        assertTrue(all.rows[1].warning)
        assertEquals(1, load(SourceLocation.Bindings(SourceRegion.EU, 10001)).rows.size)
        assertTrue(all.rows.none { row -> row.links.any { it.location is SourceLocation.Function } })
    }

    @Test fun functionPointersAreFileOffsetsNeverCanIdentifiers() {
        val page = load(SourceLocation.Function(SourceRegion.EU, 1))
        assertEquals(SourceLocation.FileBytes("FUNCDATA_EU.BIN", 100), page.rows[0].destination)
        assertNull(page.rows[2].destination)
        assertNull(page.rows[3].destination)
        assertTrue(page.explanation.contains("No CAN IDs"))
        assertEquals(1, load(SourceLocation.Functions(SourceRegion.EU)).rows.size)
    }

    @Test fun searchParametersAreLiteralNotSqlOrWildcardPrograms() {
        assertTrue(load(SourceLocation.Files(), "' OR 1=1 --").rows.isEmpty())
        assertTrue(load(SourceLocation.Texts("TCODE_EN.GAG"), "%").rows.isEmpty())
        assertTrue(load(SourceLocation.Menu(), "' OR 1=1 --").rows.isEmpty())
        expectFailure { load(SourceLocation.FileDetail("' OR 1=1 --")) }
        assertEquals(8, load(SourceLocation.Files()).rows.size)
    }

    @Test fun issueFiltersRetainBothOpaqueAndDamagedFiles() {
        val page = load(SourceLocation.Files(FileFilter.ISSUES))
        assertEquals(2, page.rows.size)
        assertTrue(page.rows.all { it.warning })
        assertEquals(1, load(SourceLocation.Files(FileFilter.ECUS)).rows.size)
        assertEquals(3, load(SourceLocation.Libraries).rows.size)
        assertTrue(load(SourceLocation.Inventory).rows.single().detail.contains("ELF (excluded)"))
    }

    @Test fun listsUseFixedPagesWithNoDuplicatesAcrossOffsets() {
        repeat(45) { source("Extra$it.BIN", 16, "opaque") }
        val first = load(SourceLocation.Files(), "Extra")
        assertEquals(40, first.rows.size)
        assertTrue(first.hasNext)
        val last = load(SourceLocation.Files(), "Extra", 40)
        assertEquals(5, last.rows.size)
        assertFalse(last.hasNext)
        assertTrue((first.rows.map { it.key }.toSet() intersect last.rows.map { it.key }.toSet()).isEmpty())
    }

    @Test fun sharedTableLinksLeadToValidatedViews() {
        assertTrue(load(SourceLocation.FileDetail("EU/Toyota_bin_11.BIN")).links.any { it.location is SourceLocation.Frames })
        assertTrue(load(SourceLocation.FileDetail("EU/Toyota_bin_12.BIN")).links.any { it.location is SourceLocation.Bindings })
        assertTrue(load(SourceLocation.FileDetail("EU/Toyota_bin_00.BIN")).links.any { it.location is SourceLocation.Commands })
        assertEquals(1, load(SourceLocation.Frames(SourceRegion.EU), "10001").rows.size)
    }

    @Test fun metadataRequiresExactPinnedPassiveContract() {
        val meta = mapOf("format" to "\"bod3-toyota-source\"", "sourceSha256" to "\"${SourceManifest.SOURCE_SHA256}\"",
            "processorSha256" to "\"${testManifest().processorSha256}\"", "counts" to censusJson(),
            "executableDefinitions" to "0", "expressExecution" to "false", "manualSelectionOnly" to "true")
        meta.forEach { (k, v) -> insert("INSERT INTO metadata VALUES(?,?)", k, v) }
        browser.validateMetadata()
        insert("UPDATE metadata SET value='true' WHERE key='expressExecution'")
        expectFailure { browser.validateMetadata() }
    }

    private fun load(location: SourceLocation, query: String = "", offset: Long = 0) = browser.load(SourceRequest(location, query, offset))
    private fun source(name: String, bytes: Int, status: String, issue: String? = null) {
        insert("INSERT INTO source_file VALUES(?,?,?,?,?,?)", name, bytes, "0".repeat(64), status, issue, ByteArray(bytes) { (it % 256).toByte() })
    }
    private fun insert(sql: String, vararg args: Any?) {
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
            statement.executeUpdate()
        }
    }
}

internal fun testManifest() = SourceManifest(4096, "a".repeat(64), 100, "b".repeat(64), SourceManifest.EXPECTED_COUNTS, "c".repeat(64))
internal fun censusJson() = SourceManifest.EXPECTED_COUNTS.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
internal fun expectFailure(block: () -> Unit) {
    try { block(); fail("Expected a rejected source operation") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
}
