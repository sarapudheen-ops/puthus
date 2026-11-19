package com.bod3.app.source

import com.bod3.app.profile.MiniJson

/** Source namespaces are deliberately separate from Catalog/EcuDefinition/CustomPid.
 * No SourceLocation, row or dictionary entry is accepted by a diagnostic engine. */
enum class SourceRegion { EU, JP, NA }

sealed interface SourceLocation {
    data object Overview : SourceLocation
    data class Menu(val node: Long? = null, val manualOnly: Boolean = true) : SourceLocation
    data class Vehicles(val region: SourceRegion) : SourceLocation
    data class Systems(val region: SourceRegion, val vehicle: Long) : SourceLocation
    data class Files(val filter: FileFilter = FileFilter.ALL) : SourceLocation
    data class FileDetail(val name: String) : SourceLocation
    data class FileBytes(val name: String, val start: Long = 0, val length: Long? = null) : SourceLocation
    data class Table(val id: String) : SourceLocation
    data object Libraries : SourceLocation
    data class Texts(val library: String) : SourceLocation
    data class TextRecord(val library: String, val id: String) : SourceLocation
    data class Frames(val region: SourceRegion) : SourceLocation
    data class Frame(val region: SourceRegion, val id: Long) : SourceLocation
    data class Commands(val region: SourceRegion) : SourceLocation
    data class Command(val region: SourceRegion, val id: Long) : SourceLocation
    data class Bindings(val region: SourceRegion, val frame: Long? = null) : SourceLocation
    data class Functions(val region: SourceRegion) : SourceLocation
    data class Function(val region: SourceRegion, val id: Long) : SourceLocation
    data object Inventory : SourceLocation
}

enum class FileFilter { ALL, ECUS, ISSUES }

/** Fixed, bounded pages: never materialize the large database as app state. */
data class SourceRequest(val location: SourceLocation = SourceLocation.Overview, val query: String = "", val offset: Long = 0) {
    init {
        require(query.length <= MAX_QUERY) { "Search is limited to $MAX_QUERY characters" }
        require(offset in 0..MAX_OFFSET) { "Source page is out of range" }
    }
    companion object {
        const val PAGE_SIZE = 40
        const val BYTE_PAGE = 512
        const val TEXT_PAGE = 2048
        const val MAX_QUERY = 120
        const val MAX_OFFSET = 512L * 1024 * 1024
    }
}

data class SourceLink(val title: String, val location: SourceLocation)
data class SourceField(val name: String, val value: String)
data class SourceRow(
    val key: String,
    val title: String,
    val detail: String = "",
    val destination: SourceLocation? = null,
    val links: List<SourceLink> = emptyList(),
    val warning: Boolean = false,
    val monospace: Boolean = false,
)
data class SourcePage(
    val title: String,
    val explanation: String,
    val fields: List<SourceField> = emptyList(),
    val rows: List<SourceRow> = emptyList(),
    val links: List<SourceLink> = emptyList(),
    val searchHint: String? = null,
    val hasNext: Boolean = false,
    val step: Int = SourceRequest.PAGE_SIZE,
    val rangeLabel: String? = null,
)

/** Only read-only SQL is supplied by SourceBrowser. All search/identity data is bound. */
fun interface SourceSql {
    fun query(sql: String, args: List<String>): List<SourceSqlRow>
}

class SourceSqlRow(private val cells: Map<String, Any?>) {
    fun text(name: String): String = cells[name] as? String ?: error("Missing text field: $name")
    fun textOrNull(name: String): String? = cells[name] as? String
    fun number(name: String): Long = (cells[name] as? Number)?.toLong() ?: error("Missing numeric field: $name")
    fun numberOrNull(name: String): Long? = (cells[name] as? Number)?.toLong()
    fun bytes(name: String): ByteArray = cells[name] as? ByteArray ?: error("Missing byte field: $name")
}

/** Trusted bundled manifest, not a user import or download endpoint. Reject missing/partial packs. */
data class SourceManifest(
    val databaseBytes: Long,
    val databaseSha256: String,
    val archiveBytes: Long,
    val archiveSha256: String,
    val counts: Map<String, Long>,
    val processorSha256: String,
) {
    companion object {
        const val SOURCE_SHA256 = "26c77a39070e751cb58a86db970e8f14991e21611eb4fb0dfffa2cd2cddfe05a"
        const val FORMAT_VERSION = 1L
        const val MAX_DB_BYTES = 384L * 1024 * 1024
        const val MAX_ARCHIVE_BYTES = 60L * 1024 * 1024
        const val DATABASE_NAME = "toyota-source.db"
        val EXPECTED_COUNTS = mapOf("archive_entry" to 1604L, "source_file" to 1410L, "data_table" to 15215L,
            "text_library" to 8L, "text_record" to 198091L, "menu" to 149731L, "menu_edge" to 149730L,
            "vehicle" to 10144L, "system" to 287030L, "function_index" to 1275L, "command" to 9774L,
            "frame" to 8775L, "function_frame" to 36997L, "invalidCommands" to 3L, "opaqueFiles" to 8L)
        private val SHA = Regex("[a-f0-9]{64}")

        fun parse(text: String): SourceManifest {
            require(text.length <= 16_384) { "Source manifest is too large" }
            val fields = MiniJson.parse(text) as? Map<*, *> ?: error("Invalid source manifest")
            fun number(key: String): Long = integral(fields[key], key)
            fun hash(key: String): String = (fields[key] as? String)?.takeIf { SHA.matches(it) }
                ?: error("Invalid manifest hash: $key")
            require(fields["format"] == "bod3-toyota-source" && number("formatVersion") == FORMAT_VERSION) { "Unsupported source format" }
            require(fields["sourceSha256"] == SOURCE_SHA256) { "Unexpected Toyota source version" }
            require(number("executableDefinitions") == 0L && fields["expressExecution"] == false && fields["manualSelectionOnly"] == true) {
                "Source library must be passive and manually browsed"
            }
            val rawCounts = fields["counts"] as? Map<*, *> ?: error("Missing source census")
            val counts = rawCounts.entries.associate { (key, value) ->
                (key as? String ?: error("Invalid census key")) to integral(value, key.toString())
            }
            require(EXPECTED_COUNTS.all { (key, value) -> counts[key] == value }) { "Incomplete Toyota source library" }
            return SourceManifest(number("databaseBytes").also { require(it in 4096..MAX_DB_BYTES) }, hash("databaseSha256"),
                number("archiveBytes").also { require(it in 1..MAX_ARCHIVE_BYTES) }, hash("archiveSha256"), counts, hash("processorSha256"))
        }

        private fun integral(value: Any?, field: String): Long {
            val n = (value as? Number)?.toDouble() ?: error("Missing manifest number: $field")
            require(n.isFinite() && n >= 0 && n <= MAX_DB_BYTES.toDouble() && n == n.toLong().toDouble()) { "Invalid manifest number: $field" }
            return n.toLong()
        }
    }
}

/** Visible escaping prevents hidden NULs/control characters from hiding source content. */
fun sourceDisplay(text: String): String = buildString {
    text.forEach { c ->
        when {
            c == '\u0000' -> append("\\0")
            c == '\n' || c == '\t' -> append(c)
            c == '\r' -> append("\\r")
            c < ' ' || c in '\u007f'..'\u009f' || c in '\u202a'..'\u202e' || c in '\u2066'..'\u2069' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
}

fun sourceHex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it.toInt() and 255) }

fun SourcePage.report(request: SourceRequest): String = buildString {
    appendLine("bod3 — Toyota V10.62 offline SOURCE EVIDENCE (not executable)")
    appendLine("Source SHA-256: ${SourceManifest.SOURCE_SHA256}")
    appendLine("$title\n$explanation")
    appendLine("Location: ${request.location}; search: ${sourceDisplay(request.query)}; offset: ${request.offset}")
    fields.forEach { appendLine("${it.name}: ${it.value}") }
    rows.forEach { row -> appendLine("\n${row.title}\n${row.detail}"); row.links.forEach { appendLine("Reference: ${it.title} -> ${it.location}") } }
    if (hasNext) appendLine("\nMore records follow. This export contains only the displayed page, not the whole library.")
}
