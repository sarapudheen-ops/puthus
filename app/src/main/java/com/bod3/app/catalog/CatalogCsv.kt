package com.bod3.app.catalog

import com.bod3.app.datapack.EcuDefinition
import com.bod3.app.datapack.EcuProtocol
import java.util.Locale

/** RFC-4180 reader/writer. CSV covers hierarchy and ECU declarations; JSON preserves every data type. */
object CatalogCsv {
    val REQUIRED = listOf("make", "model", "generation", "year_from", "year_to", "engine", "fuel", "ecu_name", "req_id", "resp_id", "addressing", "protocol")
    val COLUMNS = REQUIRED + listOf("transmission", "variant", "exclude_ecus")
    data class Record(val line: Int, val fields: List<String>)

    fun looksLikeCsv(text: String): Boolean = text.trimStart('\uFEFF', ' ', '\n', '\r').takeWhile { it != '\n' }.lowercase(Locale.ROOT)
        .let { it.contains("make") && it.contains("model") && it.contains(',') && !it.startsWith('{') }

    fun records(text: String): List<Record> {
        val result = mutableListOf<Record>(); val fields = mutableListOf<String>(); val value = StringBuilder()
        val input = text.removePrefix("\uFEFF"); var i = 0; var line = 1; var startLine = 1
        var quoted = false; var closed = false
        fun field() { fields += value.toString(); value.setLength(0); closed = false }
        fun row() { field(); if (fields.any { it.isNotBlank() }) result += Record(startLine, fields.toList()); fields.clear() }
        while (i < input.length) {
            val c = input[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < input.length && input[i + 1] == '"') { value.append('"'); i++ } else { quoted = false; closed = true }
                } else { value.append(c); if (c == '\n') line++ }
            } else when (c) {
                '"' -> { require(value.isEmpty() && !closed) { "CSV line $line: quote inside an unquoted field" }; quoted = true }
                ',' -> field()
                '\r', '\n' -> {
                    if (c == '\r' && i + 1 < input.length && input[i + 1] == '\n') i++
                    row(); line++; startLine = line
                }
                else -> { require(!closed) { "CSV line $line: unexpected character after closing quote" }; value.append(c) }
            }
            i++
        }
        require(!quoted) { "CSV line $startLine: unterminated quoted field" }
        if (value.isNotEmpty() || fields.isNotEmpty() || closed) row()
        return result
    }

    fun decode(text: String): CatalogResult {
        if (text.toByteArray(Charsets.UTF_8).size > CatalogCodec.MAX_BYTES) return CatalogResult(null, listOf("CSV exceeds 16 MiB"))
        val rows = try { records(text) } catch (e: IllegalArgumentException) { return CatalogResult(null, listOf(e.message ?: "Invalid CSV")) }
        if (rows.isEmpty()) return CatalogResult(null, listOf("CSV is empty"))
        val header = rows.first().fields.map { it.trim().lowercase(Locale.ROOT) }
        val errors = mutableListOf<String>()
        REQUIRED.filterNot { it in header }.forEach { errors += "CSV header: missing $it" }
        if (header.distinct().size != header.size) errors += "CSV header: duplicate column"
        header.filterNot { it in COLUMNS }.forEach { errors += "CSV header: unsupported column $it" }
        if (errors.isNotEmpty()) return CatalogResult(null, errors)
        val nodes = linkedMapOf<String, CatalogNode>()
        rows.drop(1).forEach { row ->
            val p = "CSV line ${row.line}"
            if (row.fields.size != header.size) { errors += "$p: expected ${header.size} columns, got ${row.fields.size}"; return@forEach }
            val m = header.zip(row.fields.map(String::trim)).toMap()
            fun text(k: String) = m[k]?.takeIf { it.isNotEmpty() }
            val make = text("make")?.let(CatalogIds::makeName)
            if (make == null) { errors += "$p.make: required"; return@forEach }
            val model = text("model"); val gen = text("generation")
            val engine = text("engine"); val fuel = text("fuel"); val transmission = text("transmission")
            val variant = text("variant") ?: listOfNotNull(engine, fuel, transmission).takeIf { it.isNotEmpty() }?.joinToString(" / ")
            if (model == null && gen != null) errors += "$p.generation: needs model"
            if (gen == null && listOf(variant, engine, fuel, transmission, text("year_from"), text("year_to")).any { it != null }) errors += "$p: years/variants require a generation"
            fun year(key: String): Int? = text(key)?.let { it.toIntOrNull().also { number -> if (number == null) errors += "$p.$key: must be a whole year or empty" } }
            val from = year("year_from"); val to = year("year_to")
            fun ensure(level: CatalogLevel, parent: String?, name: String): CatalogNode {
                val identity = if (level == CatalogLevel.VARIANT) CatalogIds.variantIdentity(name, engine, fuel, transmission) else name
                val id = CatalogIds.node(level, parent, identity)
                val candidate = CatalogNode(id, parent, level, name, if (level == CatalogLevel.GENERATION) from else null,
                    if (level == CatalogLevel.GENERATION) to else null, if (level == CatalogLevel.VARIANT) engine else null,
                    if (level == CatalogLevel.VARIANT) fuel else null, if (level == CatalogLevel.VARIANT) transmission else null)
                val old = nodes[id]
                if (old != null && (old.yearFrom != candidate.yearFrom || old.yearTo != candidate.yearTo)) errors += "$p: conflicting year range for $name"
                return old ?: candidate.also { nodes[id] = it }
            }
            var target = ensure(CatalogLevel.MAKE, null, make)
            if (model != null) target = ensure(CatalogLevel.MODEL, target.id, model)
            if (gen != null) target = ensure(CatalogLevel.GENERATION, target.id, gen)
            if (variant != null && gen != null) target = ensure(CatalogLevel.VARIANT, target.id, variant)
            text("exclude_ecus")?.let { raw ->
                val excluded = raw.split(';').map { it.trim().uppercase(Locale.ROOT) }.toSet()
                if (excluded.any { !Regex("(?:[0-7][0-9A-F]{2}|[01][0-9A-F]{7})").matches(it) || (it.toLongOrNull(16) ?: Long.MAX_VALUE) > 0x1FFFFFFF }) errors += "$p.exclude_ecus: use semicolon-separated 11/29-bit hex IDs"
                target = target.copy(data = target.data.copy(exclude = target.data.exclude.copy(ecus = target.data.exclude.ecus + excluded)))
                nodes[target.id] = target
            }
            if (listOf("ecu_name", "req_id", "resp_id", "addressing", "protocol").any { text(it) != null }) {
                val name = text("ecu_name"); val req = text("req_id")?.uppercase(Locale.ROOT)?.removePrefix("0X")
                val resp = text("resp_id")?.uppercase(Locale.ROOT)?.removePrefix("0X")
                val bits = when (text("addressing")?.lowercase(Locale.ROOT)) { "11", "11-bit" -> 11; "29", "29-bit" -> 29; else -> null }
                val protocol = EcuProtocol.entries.firstOrNull { it.name.equals(text("protocol"), true) }
                if (name == null || req == null || resp == null || bits == null || protocol == null) {
                    errors += "$p: ECU needs ecu_name, req_id, resp_id, addressing (11/29) and protocol (UDS/OBD/KWP)"
                } else {
                    val ecu = EcuDefinition(name = name, requestId = req, responseId = resp, protocol = protocol)
                    ecu.problems().forEach { errors += "$p.$it" }
                    val length = if (bits == 11) 3 else 8
                    if (req.length != length || resp.length != length) errors += "$p.addressing: ID length does not match $bits-bit addressing"
                    val old = target.data.ecus.firstOrNull { it.key == ecu.key }
                    if (old != null && old != ecu) errors += "$p: conflicting ECU ${ecu.key} at ${target.name}"
                    else if (old == null) nodes[target.id] = target.copy(data = target.data.copy(ecus = target.data.ecus + ecu))
                }
            }
        }
        if (rows.size < 2) errors += "CSV contains no data rows"
        val pack = CatalogPack("csv-import", "CSV vehicle catalog", nodes = nodes.values.toList())
        errors += CatalogTree(pack).problems()
        return CatalogResult(pack.takeIf { errors.isEmpty() }, errors.distinct())
    }

    /** Match CSV paths to existing stable editor IDs; JSON IDs are never remapped this way. */
    fun reconcile(incoming: CatalogPack, current: CatalogPack): CatalogPack {
        val tree = CatalogTree(current)
        val mapping = linkedMapOf<String, String>()
        val nodes = incoming.nodes.sortedBy { it.level.ordinal }.map { n ->
            val parent = mapping[n.parentId] ?: n.parentId
            val identity = CatalogIds.normalize(CatalogIds.variantIdentity(n.name, n.engine, n.fuel, n.transmission))
            val existing = tree.children(parent).firstOrNull { candidate -> candidate.level == n.level &&
                CatalogIds.normalize(CatalogIds.variantIdentity(candidate.name, candidate.engine, candidate.fuel, candidate.transmission)) == identity }
            val id = existing?.id ?: n.id
            mapping[n.id] = id
            n.copy(id = id, parentId = parent)
        }
        return incoming.copy(nodes = nodes)
    }

    fun encode(pack: CatalogPack): String {
        require((pack.nodes.map { it.data } + pack.platforms.map { it.data } + pack.library).none { d -> d.ecus.any { it.link != null || it.diagnostics != null } }) {
            "CSV cannot preserve enhanced ECU links/read definitions. Export JSON instead; nothing was exported or changed."
        }
        val tree = CatalogTree(pack)
        val rows = mutableListOf(COLUMNS)
        fun quoted(value: String): String = if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${value.replace("\"", "\"\"")}\"" else value
        pack.nodes.sortedWith(compareBy({ tree.path(it.id).joinToString("/") { n -> n.name } }, { it.level.ordinal })).forEach { n ->
            val path = tree.path(n.id)
            fun at(level: CatalogLevel) = path.firstOrNull { it.level == level }
            val gen = at(CatalogLevel.GENERATION); val variant = at(CatalogLevel.VARIANT)
            // CSV cannot express platforms; materialize their existing ECU declarations, not guessed IDs.
            val platformEcus = n.platformIds.mapNotNull { id -> pack.platforms.firstOrNull { it.id == id } }.flatMap { it.data.ecus }
            val ecus = (platformEcus.filterNot { it.key in n.data.exclude.ecus } + n.data.ecus).associateBy { it.key }.values
            val base = listOf(at(CatalogLevel.MAKE)?.name.orEmpty(), at(CatalogLevel.MODEL)?.name.orEmpty(), gen?.name.orEmpty(),
                gen?.yearFrom?.toString().orEmpty(), gen?.yearTo?.toString().orEmpty(), variant?.engine.orEmpty(), variant?.fuel.orEmpty())
            val tail = listOf(variant?.transmission.orEmpty(), variant?.name.orEmpty(), n.data.exclude.ecus.sorted().joinToString(";"))
            if (ecus.isEmpty()) rows += base + List(5) { "" } + tail
            else ecus.forEach { e -> rows += base + listOf(e.name, e.requestId, e.responseId, if (e.is29Bit) "29" else "11", e.protocol.name) + tail }
        }
        return rows.joinToString("\r\n", postfix = "\r\n") { row -> row.joinToString(",", transform = ::quoted) }
    }
}
