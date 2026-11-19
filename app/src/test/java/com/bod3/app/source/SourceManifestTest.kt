package com.bod3.app.source

import org.junit.Assert.*
import org.junit.Test

class SourceManifestTest {
    @Test fun requiresCompletePinnedPassiveLibrary() {
        val manifest = SourceManifest.parse(json())
        assertEquals(1410L, manifest.counts["source_file"])
        assertEquals(198091L, manifest.counts["text_record"])
        assertEquals(4096L, manifest.databaseBytes)
    }

    @Test fun rejectsPartialWrongVersionUnsafeAndUnboundedManifests() {
        for (bad in listOf(
            json().replace("\"source_file\":1410", "\"source_file\":1409"),
            json().replace(SourceManifest.SOURCE_SHA256, "0".repeat(64)),
            json().replace("\"formatVersion\":1", "\"formatVersion\":2"),
            json().replace("\"expressExecution\":false", "\"expressExecution\":true"),
            json().replace("\"executableDefinitions\":0", "\"executableDefinitions\":1"),
            json().replace("\"manualSelectionOnly\":true", "\"manualSelectionOnly\":false"),
            json().replace("\"databaseBytes\":4096", "\"databaseBytes\":4096.5"),
            json().replace("\"databaseBytes\":4096", "\"databaseBytes\":999999999"),
            json().replace("\"archiveBytes\":100", "\"archiveBytes\":0"),
            json().replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1"),
            " ".repeat(17000) + json(),
        )) expectFailure { SourceManifest.parse(bad) }
    }

    @Test fun requestLimitsPreventUnboundedSearchOrOffsets() {
        expectFailure { SourceRequest(query = "x".repeat(121)) }
        expectFailure { SourceRequest(offset = -1) }
        expectFailure { SourceRequest(offset = SourceRequest.MAX_OFFSET + 1) }
    }

    @Test fun displayEscapesNulsControlsAndBidirectionalOverrides() {
        assertEquals("code\\0description\\r\n\\u0001\\u202e", sourceDisplay("code\u0000description\r\n\u0001\u202e"))
        assertEquals("ff 00 7f", sourceHex(byteArrayOf(0xff.toByte(), 0, 0x7f)))
    }

    @Test fun pageExportIsExplicitlyOnlyPassiveEvidenceNotAnOperationalPack() {
        val report = SourcePage("Test", "Read only", rows = listOf(SourceRow("x", "Record", "data")), hasNext = true).report(SourceRequest())
        assertTrue(report.contains("not executable"))
        assertTrue(report.contains(SourceManifest.SOURCE_SHA256))
        assertTrue(report.contains("only the displayed page"))
    }

    private fun json() = """{
        "format":"bod3-toyota-source","formatVersion":1,"sourceSha256":"${SourceManifest.SOURCE_SHA256}",
        "executableDefinitions":0,"expressExecution":false,"manualSelectionOnly":true,
        "databaseBytes":4096,"archiveBytes":100,"databaseSha256":"${"a".repeat(64)}",
        "archiveSha256":"${"b".repeat(64)}","processorSha256":"${"c".repeat(64)}","counts":${censusJson()}
    }"""
}
