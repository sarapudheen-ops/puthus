package com.bod3.app.catalog

import com.bod3.app.datapack.DtcGroup
import com.bod3.app.datapack.EcuProtocol
import com.bod3.app.profile.JsonWriter
import com.bod3.app.profile.MiniJson
import org.junit.Assert.*
import org.junit.Test

class CatalogValidationTest {
    private val header = CatalogCsv.REQUIRED.joinToString(",")
    private fun csv(vararg rows: String) = header + "\r\n" + rows.joinToString("\r\n")
    private val row = "Toyota,Innova,AN40,2004,2015,Test engine,Diesel,Test engine ECU,7E0,7E8,11,UDS"

    @Test fun jsonRoundTripKeepsAllDataAndManufacturerSourceButNotVerificationClaims() {
        val data = CatalogData(ecus = listOf(engine), pids = listOf(pid()), actuators = listOf(actuator()), coding = listOf(coding()), security = listOf(security()),
            dtcGroups = listOf(DtcGroup("test-ref", listOf("Toyota"), mapOf("P1234-00" to "Documented test text"), "Original source")),
            connection = CatalogConnection(mapOf("extendedAddress" to null, "requestTimeoutMs" to 6_000)))
        val pack = fixture(generationNode.copy(data = data))
        val result = CatalogCodec.decode(CatalogCodec.encode(pack))
        assertTrue(result.errors.toString(), result.isValid)
        val restored = CatalogTree(result.pack!!).resolve("variant")!!.data
        assertEquals(data.ecus, restored.ecus)
        assertEquals(data.pids, restored.pids)
        assertEquals(data.dtcGroups, restored.dtcGroups)
        assertFalse(restored.actuators.single().verified)
        assertFalse(restored.coding.single().verified)
        assertFalse(restored.security.single().verified)
        assertTrue(restored.connection.values.containsKey("extendedAddress"))
        assertNull(restored.connection.values["extendedAddress"])
    }

    @Test fun jsonReportsErrorsAcrossAllNodesInsteadOfKeepingValidItems() {
        val one = generationNode.copy(data = CatalogData(ecus = listOf(engine), pids = listOf(pid().copy(request = "2E123401"))))
        val two = variantNode.copy(data = CatalogData(ecus = listOf(brakes.copy(requestId = "FFF")), coding = listOf(coding().copy(did = 0xF190))))
        val result = CatalogCodec.decode(CatalogCodec.encode(fixture(one, two)))
        assertNull(result.pack)
        assertTrue(result.errors.joinToString().contains("request"))
        assertTrue(result.errors.joinToString().contains("CAN ID range"))
        assertTrue(result.errors.joinToString().contains("identification record"))
    }

    @Test fun missingParentsCyclesAndWrongLevelParentsAreRejected() {
        for (bad in listOf(generationNode.copy(parentId = "missing"), generationNode.copy(parentId = "variant"), modelNode.copy(parentId = "variant"), makeNode.copy(parentId = "model"))) {
            val result = CatalogCodec.decode(CatalogCodec.encode(fixture(bad)))
            assertNull(result.pack)
            assertTrue(result.errors.toString(), result.errors.any { "parent" in it.lowercase() })
        }
    }

    @Test fun duplicateIdsAndDuplicateSiblingIdentitiesAreRejected() {
        val pack = fixture()
        assertFalse(CatalogCodec.decode(CatalogCodec.encode(pack.copy(nodes = pack.nodes + modelNode))).isValid)
        assertFalse(CatalogCodec.decode(CatalogCodec.encode(pack.copy(nodes = pack.nodes + modelNode.copy(id = "different", name = " INNOVA ")))).isValid)
    }

    @Test fun badVersionAndUnknownDataFieldsAreNotSilentlyIgnored() {
        val encoded = CatalogCodec.encode(fixture())
        assertFalse(CatalogCodec.decode(encoded.replace("\"formatVersion\":1", "\"formatVersion\":99")).isValid)
        assertFalse(CatalogCodec.decode(encoded.replaceFirst("\"ecus\":[]", "\"ecuTypo\":[]")).isValid)
        assertFalse(CatalogCodec.decode(encoded.replaceFirst("\"connection\":{}", "\"connection\":{\"expertMode\":true}")).isValid)
    }

    @Test fun nonIntegerYearsBadRangesAndWrongConnectionTypesAreRejected() {
        val invalid = listOf(
            fixture(generationNode.copy(yearFrom = 2020, yearTo = 2010)),
            fixture(modelNode.copy(yearFrom = 2000)),
            fixture(variantNode.copy(data = CatalogData(connection = CatalogConnection(mapOf("requestTimeoutMs" to "5000"))))),
            fixture(variantNode.copy(data = CatalogData(connection = CatalogConnection(mapOf("forcedProtocol" to "guessed"))))),
            fixture(variantNode.copy(data = CatalogData(connection = CatalogConnection(mapOf("requestTimeoutMs" to 5000.5))))),
        )
        invalid.forEach { assertFalse(CatalogCodec.decode(CatalogCodec.encode(it)).isValid) }
        val json = CatalogCodec.encode(fixture()).replace("\"yearFrom\":2004", "\"yearFrom\":2004.5")
        assertFalse(CatalogCodec.decode(json).isValid)
    }

    @Test fun explicitAddressingMustMatchBothIdsAndFitTheCanBitWidth() {
        fun json(req: String, resp: String, addressing: String): String = CatalogCodec.encode(fixture(modelNode.copy(data = CatalogData(ecus = listOf(engine.copy(requestId = req, responseId = resp))))))
            .replace("\"addressing\":\"${if (req.length == 8) 29 else 11}\"", "\"addressing\":\"$addressing\"")
        assertFalse(CatalogCodec.decode(json("7E0", "7E8", "29")).isValid)
        assertFalse(CatalogCodec.decode(json("FFF", "FFE", "11")).isValid)
        assertFalse(CatalogCodec.decode(json("FFFFFFFF", "FFFFFFFE", "29")).isValid)
        assertTrue(CatalogCodec.decode(json("18DA10F1", "18DAF110", "29")).isValid)
    }

    @Test fun csvAcceptsRequiredColumnsAndBuildsManualHierarchyWithVariants() {
        val result = CatalogCsv.decode(csv(row))
        assertTrue(result.errors.toString(), result.isValid)
        val nodes = result.pack!!.nodes
        assertEquals(CatalogLevel.entries.toList(), nodes.map { it.level })
        val variant = nodes.last()
        assertEquals("Test engine", variant.engine)
        assertEquals("Diesel", variant.fuel)
        assertEquals(1, CatalogTree(result.pack!!).resolve(variant.id)!!.data.ecus.size)
    }

    @Test fun csvAllowsDataAtMakeModelAndGenerationLevelsWithoutInventedVariants() {
        val result = CatalogCsv.decode(csv(
            "Toyota,,,,,,,Make ECU,7E0,7E8,11,OBD",
            "Toyota,Innova,,,,,,Model ECU,713,77D,11,UDS",
            "Toyota,Innova,AN40,2004,2015,,,Generation ECU,70E,778,11,KWP"))
        assertTrue(result.errors.toString(), result.isValid)
        assertEquals(3, result.pack!!.nodes.size)
        val generation = result.pack!!.nodes.last()
        assertEquals(3, CatalogTree(result.pack!!).resolve(generation.id)!!.data.ecus.size)
        assertEquals(EcuProtocol.KWP, CatalogTree(result.pack!!).resolve(generation.id)!!.data.ecus.last().protocol)
    }

    @Test fun csvQuotesCommasNewlinesEscapedQuotesBomAndCrLf() {
        val result = CatalogCsv.decode("\uFEFF" + csv("Toyota,\"Model, special\",AN40,2004,2015,,,\"Test \"\"ECU\"\"\nname\",7E0,7E8,11,UDS"))
        assertTrue(result.errors.toString(), result.isValid)
        assertEquals("Model, special", result.pack!!.nodes.first { it.level == CatalogLevel.MODEL }.name)
        assertEquals("Test \"ECU\"\nname", result.pack!!.nodes.last().data.ecus.single().name)
        assertTrue(CatalogCsv.decode(CatalogCsv.encode(result.pack!!)).isValid)
    }

    @Test fun csvValidatesTheLastRowAndRejectsTheEntireFile() {
        val result = CatalogCsv.decode(csv(row, "Toyota,Innova,AN40,2004,2015,Test engine,Diesel,Bad ECU,XYZ,7E8,11,UDS"))
        assertNull(result.pack)
        assertTrue(result.errors.toString(), result.errors.any { "line 3" in it })
    }

    @Test fun csvReportsIndependentErrorsAcrossRows() {
        val result = CatalogCsv.decode(csv(
            "Toyota,Innova,AN40,not-a-year,2015,,,Engine,7E0,7E8,11,UDS",
            "Toyota,Innova,AN40,2015,2004,,,Engine,7E0,7E8,29,FAKE"))
        assertNull(result.pack)
        assertTrue(result.errors.toString(), result.errors.any { "line 2" in it })
        assertTrue(result.errors.toString(), result.errors.any { "line 3" in it })
    }

    @Test fun csvHeadersAreRequiredUniqueAndKnownAndRowsHaveExactColumnCounts() {
        assertFalse(CatalogCsv.decode(header.replace("resp_id", "typo") + "\n" + row).isValid)
        assertFalse(CatalogCsv.decode(header + ",make\n" + row + ",Toyota").isValid)
        assertFalse(CatalogCsv.decode(csv(row + ",extra")).isValid)
        assertFalse(CatalogCsv.decode(header).isValid)
        assertFalse(CatalogCsv.decode("").isValid)
    }

    @Test fun csvRejectsPartialEcusAddressWidthMismatchAndUnclosedQuotes() {
        for (bad in listOf(row.replace(",7E8,11,UDS", ",,11,UDS"), row.replace(",11,UDS", ",29,UDS"), row.replace(",7E0,", ",FFF,"), row.replace(",7E0,", ",FFFFFFFF,"), row.replace("Test engine ECU", "\"not closed"))) {
            assertFalse(bad, CatalogCsv.decode(csv(bad)).isValid)
        }
        assertFalse(CatalogCsv.decode(csv(row.replace("Toyota", "\"Toyota\"x"))).isValid)
    }

    @Test fun csvConflictingYearsOrEcusRejectAllRowsButExactRepeatedRowsAreIdempotent() {
        assertTrue(CatalogCsv.decode(csv(row, row)).isValid)
        assertFalse(CatalogCsv.decode(csv(row, row.replace("2015", "2016"))).isValid)
        assertFalse(CatalogCsv.decode(csv(row, row.replace("Test engine ECU", "Different ECU"))).isValid)
    }

    @Test fun csvRoundTripRetainsSuppressedInheritedEcus() {
        val original = fixture(makeNode.copy(data = CatalogData(ecus = listOf(engine, brakes))),
            generationNode.copy(data = CatalogData(exclude = CatalogExclusions(ecus = setOf(engine.key)))))
        val result = CatalogCsv.decode(CatalogCsv.encode(original))
        assertTrue(result.errors.toString(), result.isValid)
        val leaf = result.pack!!.nodes.first { it.level == CatalogLevel.VARIANT }
        assertEquals(listOf(brakes.key), CatalogTree(result.pack!!).resolve(leaf.id)!!.data.ecus.map { it.key })
    }

    @Test fun strictJsonRejectsDuplicateKeysNonFiniteNumbersRawControlsAndDeepNesting() {
        for (json in listOf("{\"id\":\"one\",\"id\":\"two\"}", "1e9999", "01", "+1", "{\"x\":\"raw\nnewline\"}", "[".repeat(130) + "0" + "]".repeat(130))) {
            assertTrue(json.take(40), rejected { MiniJson.parse(json) }.isNotBlank())
        }
        assertEquals(listOf(100.0, -0.5), MiniJson.parse("[1e2,-0.5]"))
    }

    @Test fun unknownSecurityAlgorithmOrInvalidLevelRejectsTheWholePack() {
        val valid = CatalogCodec.encode(fixture(variantNode.copy(data = CatalogData(security = listOf(security())))))
        assertFalse(CatalogCodec.decode(valid.replace("STATIC_KEY", "IMPORTED_SCRIPT")).isValid)
        assertFalse(CatalogCodec.decode(valid.replace("\"level\":\"01\"", "\"level\":\"02\"")).isValid)
    }

    @Test fun sectionsCannotSilentlyChangeTheReadOnlyServicePolicy() {
        assertTrue(rejected { CatalogCodec.dataOrThrow(JsonWriter.write(mapOf("pids" to listOf(mapOf("id" to "write", "name" to "bad", "request" to "2701", "formula" to "A"))))) }.contains("read service"))
    }

    @Test fun invalidDeepParentChainsHaveBoundedTraversalAndRejectTheWholePack() {
        val chain = (0 until 200).map { i -> CatalogNode("chain-$i", if (i == 0) null else "chain-${i - 1}", CatalogLevel.MODEL, "Invalid $i") }
        val pack = CatalogPack("bad-chain", "Bad graph", nodes = chain)
        val tree = CatalogTree(pack)
        assertTrue(tree.path("chain-199").size <= CatalogLevel.entries.size)
        assertNull(tree.resolve("chain-199"))
        assertFalse(CatalogCodec.decode(CatalogCodec.encode(pack)).isValid)
    }

}
