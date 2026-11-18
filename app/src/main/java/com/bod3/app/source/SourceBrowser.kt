package com.bod3.app.source

import com.bod3.app.profile.MiniJson
import kotlin.math.min

/** Bounded, read-only source queries. Never imports records into operational definitions.
 * Only reader-proven relationships have links. In particular, menu leaves are NOT joined
 * to vehicle keys, FUNCDATA offsets are NOT CAN IDs, and equal numeric namespaces are NOT joined. */
class SourceBrowser(private val db: SourceSql, private val manifest: SourceManifest) {
    private fun read(sql: String, vararg args: Any): List<SourceSqlRow> = db.query(sql, args.map(Any::toString))
    private fun one(sql: String, vararg args: Any): SourceSqlRow = read(sql, *args).singleOrNull() ?: error("Source record not found")
    private fun file(name: String) = one("SELECT name,bytes,sha256,status,issue FROM source_file WHERE name=?", name)
    private fun SourceSqlRow.field(name: String, label: String = name) = SourceField(label, text(name))
    private fun SourceSqlRow.numberField(name: String, label: String = name) = SourceField(label, number(name).toString())

    fun validateMetadata() {
        require(one("PRAGMA user_version").number("user_version") == SourceManifest.FORMAT_VERSION) { "Unsupported source database schema" }
        val metadata = read("SELECT key,value FROM metadata").associate { it.text("key") to MiniJson.parse(it.text("value")) }
        require(metadata["format"] == "bod3-toyota-source" && metadata["sourceSha256"] == SourceManifest.SOURCE_SHA256) { "Source database version mismatch" }
        require((metadata["executableDefinitions"] as? Number)?.toLong() == 0L && metadata["expressExecution"] == false && metadata["manualSelectionOnly"] == true) { "Source library is not passive" }
        require(metadata["processorSha256"] == manifest.processorSha256) { "Source processor fingerprint mismatch" }
        val counts = metadata["counts"] as? Map<*, *> ?: error("Source database census missing")
        require(manifest.counts.all { (key, value) -> (counts[key] as? Number)?.toLong() == value }) { "Source database census mismatch" }
    }

    fun load(request: SourceRequest): SourcePage = when (val loc = request.location) {
        SourceLocation.Overview -> overview()
        is SourceLocation.Menu -> menu(request, loc)
        is SourceLocation.Vehicles -> vehicles(request, loc.region)
        is SourceLocation.Systems -> systems(request, loc)
        is SourceLocation.Files -> files(request, loc.filter)
        is SourceLocation.FileDetail -> fileDetail(request, loc.name)
        is SourceLocation.FileBytes -> fileBytes(request, loc)
        is SourceLocation.Table -> table(request, loc.id)
        SourceLocation.Libraries -> libraries()
        is SourceLocation.Texts -> texts(request, loc.library)
        is SourceLocation.TextRecord -> textRecord(request, loc)
        is SourceLocation.Frames -> frames(request, loc.region)
        is SourceLocation.Frame -> frame(loc)
        is SourceLocation.Commands -> commands(request, loc.region)
        is SourceLocation.Command -> command(loc)
        is SourceLocation.Bindings -> bindings(request, loc)
        is SourceLocation.Functions -> functions(request, loc.region)
        is SourceLocation.Function -> function(loc)
        SourceLocation.Inventory -> inventory(request)
    }

    private fun overview(): SourcePage = SourcePage(
        "Toyota recovered data", PASSIVE,
        fields = listOf(SourceField("Pinned release", "Toyota V10.62 · EU / JP / NA"),
            SourceField("Decoded files / tables", "${manifest.counts["source_file"]} / ${manifest.counts["data_table"]}"),
            SourceField("Menu / system rows", "${manifest.counts["menu"]} / ${manifest.counts["system"]}"),
            SourceField("English indexed records", "${manifest.counts["text_record"]} (4,502 non-UTF-8 records retained)"),
            SourceField("Offline storage", "${manifest.databaseBytes / 1_048_576} MiB; installed from the APK only when opened"),
            SourceField("Source SHA-256", SourceManifest.SOURCE_SHA256)),
        rows = listOf(
            SourceRow("menu", "Manual vehicle source menu", "Browse model, chassis, engine and date choices. These are source paths, not a selected diagnostic configuration.", SourceLocation.Menu()),
            SourceRow("vehicles", "Regional vehicle → system → ECU relationships", "Numeric vehicle keys retain their own namespace. The menu-leaf → vehicle-key binding is unresolved.",
                links = SourceRegion.entries.map { SourceLink(it.name, SourceLocation.Vehicles(it)) }),
            SourceRow("files", "All 1,410 decoded source files", "Includes all 1,307 ECU directories and every shared/opaque file. Every original decoded byte is retained.", SourceLocation.Files()),
            SourceRow("issues", "Opaque and damaged source records", "Eight unresolved whole-file schemas and three damaged terminal command descriptors are explicit, not removed or padded.", SourceLocation.Files(FileFilter.ISSUES), warning = true),
            SourceRow("texts", "All eight English text libraries", "Live-value labels, DTCs, UI text, expressions and vehicle/function records. Expressions are displayed as text and NEVER evaluated.", SourceLocation.Libraries),
            SourceRow("frames", "Frames → byte dictionaries", "Reader-proven cross-table references, not approved requests. Field roles stay named by source offset.",
                links = SourceRegion.entries.map { SourceLink(it.name, SourceLocation.Frames(it)) }),
            SourceRow("bindings", "Function/frame bindings", "Only offset-8 selector = 0 is a validated frame relationship. Other records remain visible without a guessed join.",
                links = SourceRegion.entries.map { SourceLink(it.name, SourceLocation.Bindings(it)) }),
            SourceRow("functions", "FUNCDATA directories", "Directory pointer fields are file offsets, not CAN identifiers. All variable payloads are available for byte inspection.",
                links = SourceRegion.entries.map { SourceLink(it.name, SourceLocation.Functions(it)) }),
            SourceRow("commands", "Complete byte dictionary audits", "Includes invalid descriptor 3,258 for each region; invalid data has no manufactured byte sequence.",
                links = SourceRegion.entries.map { SourceLink(it.name, SourceLocation.Commands(it)) }),
            SourceRow("inventory", "Full archive inventory", "Metadata for all 1,604 source/nested entries. Vendor libraries, executables, images and manuals are NOT shipped in this data library.", SourceLocation.Inventory),
            SourceRow("all-menu", "All original menu branches (inspection only)", "Includes automatic/VIN-labelled source branches as passive records. Opening them sends nothing and never chooses a vehicle.", SourceLocation.Menu(0, false)),
        ),
    )

    private fun menu(req: SourceRequest, loc: SourceLocation.Menu): SourcePage {
        val id = loc.node ?: if (loc.manualOnly) one("SELECT id FROM menu WHERE parent=0 AND manual=1 ORDER BY ordinal LIMIT 1").number("id") else 0L
        val node = one("SELECT * FROM menu WHERE id=?", id)
        require(!loc.manualOnly || node.number("manual") == 1L) { "Node is outside the manual source branch" }
        val condition: String
        val args: Array<Any>
        if (req.query.isBlank()) {
            condition = "parent=?"
            args = arrayOf(id)
        } else {
            condition = "(instr(lower(path),lower(?))>0 OR CAST(id AS TEXT)=?)" + if (loc.manualOnly) " AND manual=1" else ""
            args = arrayOf(req.query.trim(), req.query.trim())
        }
        val rows = read("SELECT id,title,path,children,manual FROM menu WHERE $condition ORDER BY ordinal,id LIMIT 41 OFFSET ?", *args, req.offset)
        val edge = read("SELECT flags,tag,parameters FROM menu_edge WHERE target=? LIMIT 1", id).firstOrNull()
        val links = mutableListOf(SourceLink("MENU.BIN bytes", SourceLocation.FileBytes("MENU.BIN", id)))
        val parent = node.numberOrNull("parent")
        if (parent != null && (!loc.manualOnly || parent != 0L)) links.add(0, SourceLink("↑ Parent source menu", SourceLocation.Menu(parent, loc.manualOnly)))
        return paged(req, rows, "Vehicle source menu", MENU_WARNING,
            fields = listOf(node.field("path", "Source path"), SourceField("Node offset (not vehicle key)", id.toString()),
                SourceField("Header / opaque u16 @34", "${node.text("header_hex")} / ${node.number("word34")}")) +
                listOfNotNull(edge?.let { SourceField("Incoming saved parameters (opaque)", "flags=${it.number("flags")}, tag=${it.number("tag")}, ${it.text("parameters")}") }),
            links = links, search = "Search all ${if (loc.manualOnly) "manual " else ""}paths: Innova, engine, date…") { row ->
            SourceRow(row.number("id").toString(), sourceDisplay(row.text("title")),
                "${sourceDisplay(row.text("path"))}\n${row.number("children")} child choices · source offset ${row.number("id")}" +
                    if (row.number("manual") == 0L) " · INSPECTION ONLY" else "",
                SourceLocation.Menu(row.number("id"), loc.manualOnly))
        }
    }

    private fun vehicles(req: SourceRequest, region: SourceRegion): SourcePage {
        val rows = read("SELECT id,block FROM vehicle WHERE region=? AND (?='' OR instr(CAST(id AS TEXT),?)>0) ORDER BY id LIMIT 41 OFFSET ?",
            region, req.query, req.query, req.offset)
        return paged(req, rows, "$region vehicle keys", "A source key is not a model, VIN or CAN address. No menu-to-key mapping is inferred. Open a key to inspect its validated SYSLIST system block.",
            links = listOf(SourceLink("SYSLIST source", SourceLocation.FileDetail("SYSLIST_$region.BIN"))), search = "Numeric vehicle key") { row ->
            SourceRow(row.number("id").toString(), "Vehicle key ${row.number("id")}", "SYSLIST block @${row.number("block")}", SourceLocation.Systems(region, row.number("id")))
        }
    }

    private fun systems(req: SourceRequest, loc: SourceLocation.Systems): SourcePage {
        val block = one("SELECT block FROM vehicle WHERE region=? AND id=?", loc.region, loc.vehicle).number("block")
        val rows = read("""SELECT s.*,f.name AS resolved_file,i.id AS resolved_function FROM system s
            LEFT JOIN source_file f ON f.name=(s.region||'/'||s.ecu_file) COLLATE NOCASE
            LEFT JOIN function_index i ON i.region=s.region AND i.id=s.function_key
            WHERE s.region=? AND s.block=? AND (?='' OR instr(lower(s.name||' '||s.description||' '||s.ecu_file||' '||s.transport),lower(?))>0)
            ORDER BY s.ordinal LIMIT 41 OFFSET ?""".trimIndent(), loc.region, block, req.query, req.query, req.offset)
        return paged(req, rows, "${loc.region} · vehicle key ${loc.vehicle}", "These system → function-directory / ECU-file links are structurally validated. Source transport labels are NOT ATSP settings; no request/response IDs or sessions are inferred.",
            links = listOf(SourceLink("Source block bytes", SourceLocation.FileBytes("SYSLIST_${loc.region}.BIN", block))), search = "System name, source transport, ECU filename") { row ->
            val resolvedFile = row.textOrNull("resolved_file")
            val resolvedFunction = row.numberOrNull("resolved_function")
            SourceRow(row.number("ordinal").toString(), sourceDisplay(row.text("name")).ifBlank { "System row ${row.number("ordinal")}" },
                "${sourceDisplay(row.text("description"))}\nSource transport: ${sourceDisplay(row.text("transport"))}\n" +
                    "ECU file: ${sourceDisplay(row.text("ecu_file"))}${if (resolvedFile == null) " — UNRESOLVED" else " — linked"}\n" +
                    "Function key ${row.number("function_key")}: ${if (resolvedFunction == null) "UNRESOLVED" else "directory linked"}\n" +
                    "SYSLIST @${row.number("source_offset")}\n${row.text("raw_hex")}",
                destination = resolvedFile?.let { SourceLocation.FileDetail(it) },
                links = listOfNotNull(resolvedFunction?.let { SourceLink("Function-directory entry $it", SourceLocation.Function(loc.region, it)) }),
                warning = resolvedFile == null || resolvedFunction == null)
        }
    }

    private fun files(req: SourceRequest, filter: FileFilter): SourcePage {
        val condition = when (filter) { FileFilter.ALL -> "1=1"; FileFilter.ECUS -> "status='ecu-sections'"; FileFilter.ISSUES -> "(status='opaque' OR issue IS NOT NULL)" }
        val rows = read("SELECT name,bytes,status,issue FROM source_file WHERE $condition AND (?='' OR instr(lower(name),lower(?))>0) ORDER BY name LIMIT 41 OFFSET ?", req.query, req.query, req.offset)
        return paged(req, rows, when (filter) { FileFilter.ALL -> "Decoded source files"; FileFilter.ECUS -> "ECU source files"; FileFilter.ISSUES -> "Unresolved / damaged source files" },
            "All decoded bytes are retained, including unknown fields and padding. A framed table is not an executable PID, actuator or coding definition.",
            links = listOf(SourceLink("All files", SourceLocation.Files()), SourceLink("ECU directories", SourceLocation.Files(FileFilter.ECUS)), SourceLink("Issues", SourceLocation.Files(FileFilter.ISSUES))),
            search = "Filename or region (EU/, JP/, NA/)") { row ->
            SourceRow(row.text("name"), row.text("name"), "${row.number("bytes")} bytes · ${row.text("status")}\n${row.textOrNull("issue").orEmpty()}", SourceLocation.FileDetail(row.text("name")), warning = row.textOrNull("issue") != null)
        }
    }

    private fun fileDetail(req: SourceRequest, name: String): SourcePage {
        val source = file(name)
        val rows = read("SELECT id,slot,tag,row_count,stride,source_offset,body_bytes FROM data_table WHERE file=? ORDER BY slot LIMIT 41 OFFSET ?", name, req.offset)
        val links = mutableListOf(SourceLink("Inspect every source byte", SourceLocation.FileBytes(name)))
        val region = SourceRegion.entries.firstOrNull { name.startsWith("$it/") }
        if (region != null && name.endsWith("Toyota_bin_00.BIN")) links += SourceLink("Full descriptor audit (including invalid)", SourceLocation.Commands(region))
        if (region != null && name.endsWith("Toyota_bin_11.BIN")) links += SourceLink("Resolved frame references", SourceLocation.Frames(region))
        if (region != null && name.endsWith("Toyota_bin_12.BIN")) links += SourceLink("Function/frame bindings", SourceLocation.Bindings(region))
        SourceRegion.entries.firstOrNull { name == "SYSLIST_$it.BIN" }?.let { links += SourceLink("Vehicle / system index", SourceLocation.Vehicles(it)) }
        SourceRegion.entries.firstOrNull { name == "FUNCDATA_$it.BIN" }?.let { links += SourceLink("Function directory", SourceLocation.Functions(it)) }
        if (name == "MENU.BIN") links += SourceLink("Manual menu", SourceLocation.Menu())
        return paged(req, rows, name, source.textOrNull("issue") ?: "Source data only. Section slot and embedded schema tag are different namespaces. Request/response IDs, formulas and applicability are not inferred from them.",
            fields = listOf(source.numberField("bytes", "Decoded bytes"), source.field("status", "Parser status"), source.field("sha256", "Decoded SHA-256")), links = links) { row ->
            SourceRow(row.text("id"), if (row.number("slot") == -1L) "Shared typed table" else "Section slot ${row.number("slot")}",
                "Schema tag ${row.number("tag")} · ${row.number("row_count")} rows · stride ${row.numberOrNull("stride") ?: "unresolved"}\n" +
                    "Payload @${row.number("source_offset")} · ${row.number("body_bytes")} bytes", SourceLocation.Table(row.text("id")))
        }
    }

    private fun table(req: SourceRequest, identity: String): SourcePage {
        val table = one("SELECT * FROM data_table WHERE id=?", identity)
        val name = table.text("file")
        val start = table.number("source_offset")
        val size = table.number("body_bytes")
        val sourceSize = file(name).number("bytes")
        require(start >= 10 && size >= 0 && start <= sourceSize && size <= sourceSize - start) { "Source table outside file" }
        val count = table.number("row_count")
        val stride = table.numberOrNull("stride")
        val fields = listOf(SourceField("Source file", name), table.numberField("slot", "Directory slot (-1 = shared)"),
            table.numberField("tag", "Embedded schema tag"), SourceField("Rows / payload bytes / stride", "$count / $size / ${stride ?: "unresolved"}"))
        val links = listOf(SourceLink("Source file", SourceLocation.FileDetail(name)), SourceLink("Full table bytes", SourceLocation.FileBytes(name, start, size)))
        if (stride == null) return SourcePage(identity, "No integral row stride is established. The complete payload is available without inventing row boundaries.", fields, links = links)
        require(stride > 0 && stride * count == size) { "Source row framing mismatch" }
        require(req.offset <= count) { "Row is outside the source table" }
        val end = min(count, req.offset + SourceRequest.PAGE_SIZE)
        val rows = (req.offset until end).map { index ->
            val position = start + index * stride
            val bytes = one("SELECT substr(payload,?,?) AS data FROM source_file WHERE name=?", position + 1, min(stride, 256), name).bytes("data")
            SourceRow(index.toString(), "Row $index · @$position · $stride bytes", sourceHex(bytes) + if (stride > 256) "\nPrefix only — open for all bytes" else "",
                SourceLocation.FileBytes(name, position, stride), monospace = true)
        }
        return SourcePage(identity, "Zero-based source rows. Only framing is validated; hex data is not converted to a runnable definition.", fields, rows, links,
            hasNext = end < count, rangeLabel = "Rows ${req.offset}–${if (end > req.offset) end - 1 else req.offset} of $count")
    }

    private fun fileBytes(req: SourceRequest, loc: SourceLocation.FileBytes): SourcePage {
        val source = file(loc.name)
        val size = source.number("bytes")
        require(loc.start in 0..size) { "Source span starts outside file" }
        val length = loc.length ?: (size - loc.start)
        require(length in 0..(size - loc.start) && req.offset <= length) { "Source span is outside file" }
        val position = loc.start + req.offset
        val bytes = one("SELECT substr(payload,?,?) AS data FROM source_file WHERE name=?", position + 1, min(SourceRequest.BYTE_PAGE.toLong(), length - req.offset), loc.name).bytes("data")
        return SourcePage(loc.name, "Complete decoded bytes, paged by absolute file offset. Opaque data stays opaque. Nothing on this screen is sent or executed.",
            fields = listOf(source.field("sha256", "Decoded SHA-256"), SourceField("Span", "@${loc.start}, $length bytes; file has $size bytes")),
            rows = hexRows(bytes, position), links = listOf(SourceLink("File details", SourceLocation.FileDetail(loc.name))),
            hasNext = req.offset + bytes.size < length, step = SourceRequest.BYTE_PAGE, rangeLabel = "Bytes $position–${position + bytes.size} (end exclusive)")
    }

    private fun libraries(): SourcePage = SourcePage("English source libraries", "All indexed records are retained with their original library and ID. Duplicate numbers in different libraries are NOT automatically linked. EXPRESS is inert source text, never evaluated.",
        rows = read("SELECT name,records,non_utf8 FROM text_library ORDER BY name").map { row ->
            SourceRow(row.text("name"), row.text("name"), "${row.number("records")} records · ${row.number("non_utf8")} non-UTF-8 (raw bytes retained)", SourceLocation.Texts(row.text("name")))
        })

    private fun texts(req: SourceRequest, library: String): SourcePage {
        val info = one("SELECT * FROM text_library WHERE name=?", library)
        // instr() unlike SQLite LIKE sees content after embedded NULs (code\0description).
        val rows = read("""SELECT id,value IS NULL AS opaque,
            CASE WHEN value IS NULL THEN substr(raw,1,64) ELSE substr(CAST(value AS BLOB),1,240) END AS preview
            FROM text_record WHERE library=? AND (?='' OR instr(lower(id),lower(?))>0 OR instr(lower(value),lower(?))>0)
            ORDER BY id LIMIT 41 OFFSET ?""".trimIndent(), library, req.query, req.query, req.query, req.offset)
        return paged(req, rows, library, "Records are source data, not runnable formulas/recipes. Text previews are bounded; open a record for every byte. Embedded NULs are shown as \\0, not hidden.",
            fields = listOf(info.numberField("records", "Indexed records"), info.numberField("non_utf8", "Non-UTF-8 retained"), info.field("source_sha256", "Original member SHA-256")),
            search = "Record ID or text (including after NULs)") { row ->
            val opaque = row.number("opaque") == 1L
            SourceRow(row.text("id"), row.text("id"), if (opaque) "Non-UTF-8 — ${sourceHex(row.bytes("preview"))}" else sourceDisplay(row.bytes("preview").toString(Charsets.UTF_8)),
                SourceLocation.TextRecord(library, row.text("id")), warning = opaque, monospace = library == "EXPRESS.GAG")
        }
    }

    private fun textRecord(req: SourceRequest, loc: SourceLocation.TextRecord): SourcePage {
        val row = one("SELECT value IS NULL AS opaque,length(CASE WHEN value IS NULL THEN raw ELSE CAST(value AS BLOB) END) AS bytes FROM text_record WHERE library=? AND id=?", loc.library, loc.id)
        val size = row.number("bytes")
        require(req.offset <= size) { "Record byte offset is outside the source" }
        val bytes = one("SELECT substr(CASE WHEN value IS NULL THEN raw ELSE CAST(value AS BLOB) END,?,?) AS data FROM text_record WHERE library=? AND id=?",
            req.offset + 1, SourceRequest.TEXT_PAGE, loc.library, loc.id).bytes("data")
        val opaque = row.number("opaque") == 1L
        val records = if (opaque) hexRows(bytes, req.offset) else listOf(SourceRow("text", "Source text (not executed)", sourceDisplay(bytes.toString(Charsets.UTF_8)), monospace = true),
            SourceRow("hex", "Exact byte representation", sourceHex(bytes), monospace = true))
        return SourcePage("${loc.library} · ${loc.id}", "${if (opaque) "Unknown encoding: no guessed text decoding. " else "UTF-8 source text. "}" +
            "Expressions, instructions and apparent requests are inert records, not app actions. Byte paging can split a UTF-8 character; hex is authoritative.",
            fields = listOf(SourceField("Original record bytes", size.toString()), SourceField("Library / ID", "${loc.library} / ${loc.id}")), rows = records,
            links = listOf(SourceLink("Library", SourceLocation.Texts(loc.library))), hasNext = req.offset + bytes.size < size,
            step = SourceRequest.TEXT_PAGE, rangeLabel = "Record bytes ${req.offset}–${req.offset + bytes.size} of $size (end exclusive)")
    }

    private fun frames(req: SourceRequest, region: SourceRegion): SourcePage {
        val rows = read("SELECT * FROM frame WHERE region=? AND (?='' OR instr(CAST(id AS TEXT),?)>0) ORDER BY id LIMIT 41 OFFSET ?", region, req.query, req.query, req.offset)
        return paged(req, rows, "$region communication frames", FRAME_WARNING,
            links = listOf(SourceLink("Shared table", SourceLocation.FileDetail("$region/Toyota_bin_11.BIN"))), search = "Frame key (e.g. 10001)") { row ->
            SourceRow(row.number("id").toString(), "Frame ${row.number("id")}", "Source @${row.number("source_offset")} · dictionary fields @2/4/6: ${row.number("ref2")} / ${row.number("ref4")} / ${row.number("ref6")}", SourceLocation.Frame(region, row.number("id")))
        }
    }

    private fun frame(loc: SourceLocation.Frame): SourcePage {
        val row = one("SELECT * FROM frame WHERE region=? AND id=?", loc.region, loc.id)
        val refs = listOf(2, 4, 6).map { offset ->
            val sourceId = row.number("ref$offset")
            val index = normalizeDictionaryId(sourceId)
            val command = if (index == 0L) null else read("SELECT bytes_hex,error FROM command WHERE region=? AND id=?", loc.region, index).firstOrNull()
            val error = if (index == 0L) null else command?.textOrNull("error") ?: if (command == null) "Unresolved dictionary reference" else null
            SourceRow(offset.toString(), "Field @$offset: source ID $sourceId → pool index $index", if (index == 0L) "Empty sequence (native zero reference)" else error ?: command!!.text("bytes_hex"),
                if (index == 0L || command == null) null else SourceLocation.Command(loc.region, index), warning = error != null, monospace = true)
        }
        return SourcePage("${loc.region} frame ${loc.id}", FRAME_WARNING,
            fields = listOf(row.numberField("source_offset", "Toyota_bin_11 source offset")), rows = refs,
            links = listOf(SourceLink("Eligible function/frame references", SourceLocation.Bindings(loc.region, loc.id)),
                SourceLink("Frame source bytes", SourceLocation.FileBytes("${loc.region}/Toyota_bin_11.BIN", row.number("source_offset"), 8))))
    }

    private fun commands(req: SourceRequest, region: SourceRegion): SourcePage {
        val rows = read("SELECT id,bytes_hex,error,source_offset,length FROM command WHERE region=? AND (?='' OR instr(CAST(id AS TEXT),?)>0 OR instr(lower(bytes_hex),lower(?))>0) ORDER BY id LIMIT 41 OFFSET ?",
            region, req.query, req.query, req.query, req.offset)
        return paged(req, rows, "$region byte dictionary", "3,257 bounded sequences plus one invalid terminal descriptor. A sequence may be a request, response pattern, mask or fragment. It is NOT a send recipe. No missing bytes are manufactured.",
            links = listOf(SourceLink("Original anomalous source", SourceLocation.FileDetail("$region/Toyota_bin_00.BIN"))), search = "Pool index or hex bytes") { row ->
            SourceRow(row.number("id").toString(), "Pool index ${row.number("id")}", row.textOrNull("error") ?: row.text("bytes_hex"), SourceLocation.Command(region, row.number("id")), warning = row.textOrNull("error") != null, monospace = true)
        }
    }

    private fun command(loc: SourceLocation.Command): SourcePage {
        val row = one("SELECT * FROM command WHERE region=? AND id=?", loc.region, loc.id)
        return SourcePage("${loc.region} dictionary ${loc.id}", "INSPECTION ONLY. Dictionary bytes do not establish service safety, ECU addressing, sessions, applicability, response validation or extraction. There is deliberately no Send / Run / Import-as-command action.",
            fields = listOf(row.numberField("source_offset", "Referenced byte offset"), row.numberField("length", "Declared byte length")),
            rows = listOf(SourceRow("value", if (row.textOrNull("error") != null) "INVALID — no byte sequence" else "Bounded source bytes", row.textOrNull("error") ?: row.text("bytes_hex"), warning = row.textOrNull("error") != null, monospace = true)),
            links = listOf(SourceLink("Descriptor source bytes", SourceLocation.FileBytes("${loc.region}/Toyota_bin_00.BIN", 10 + (loc.id - 1) * 6, 6))))
    }

    private fun bindings(req: SourceRequest, loc: SourceLocation.Bindings): SourcePage {
        val condition = if (loc.frame == null) "1=1" else "frame=? AND selector8=0"
        val args = mutableListOf<Any>(loc.region)
        loc.frame?.let { args.add(it) }
        args.addAll(listOf(req.query, req.query, req.query, req.query, req.offset))
        val rows = read("SELECT * FROM function_frame WHERE region=? AND $condition AND (?='' OR instr(CAST(key0 AS TEXT),?)>0 OR instr(CAST(key2 AS TEXT),?)>0 OR instr(CAST(frame AS TEXT),?)>0) ORDER BY ordinal LIMIT 41 OFFSET ?", *args.toTypedArray())
        return paged(req, rows, "${loc.region} function/frame source rows", "Selector fields retain their source offsets. The frame join is reader-backed ONLY when selector @8 = 0. Selector values are not matched to unrelated FUNCDATA/text IDs just because the numbers overlap.",
            links = listOf(SourceLink("Source table", SourceLocation.FileDetail("${loc.region}/Toyota_bin_12.BIN"))), search = "Selector @0 / @2 or frame key") { row ->
            val eligible = row.number("selector8") == 0L
            SourceRow(row.number("ordinal").toString(), "Row ${row.number("ordinal")} · selectors ${row.number("key0")} / ${row.number("key2")}",
                "@4=${row.number("key4")}, @6=${row.number("frame")}, @8=${row.number("selector8")}\n${if (eligible) "Reader-backed frame relationship" else "Other selector mode — no inferred frame link"}\n${row.text("raw_hex")}",
                if (eligible) SourceLocation.Frame(loc.region, row.number("frame")) else null, warning = !eligible, monospace = true)
        }
    }

    private fun functions(req: SourceRequest, region: SourceRegion): SourcePage {
        val rows = read("SELECT id,fields FROM function_index WHERE region=? AND (?='' OR instr(CAST(id AS TEXT),?)>0) ORDER BY id LIMIT 41 OFFSET ?", region, req.query, req.query, req.offset)
        return paged(req, rows, "$region FUNCDATA directory", "Seven LE32 fields per directory entry. Fields 1–5 contain bounded FILE OFFSETS, not CAN request/response identifiers. Variable payloads remain non-executable.",
            links = listOf(SourceLink("Full FUNCDATA source", SourceLocation.FileDetail("FUNCDATA_$region.BIN"))), search = "Function-directory key") { row ->
            SourceRow(row.number("id").toString(), "Function key ${row.number("id")}", row.text("fields"), SourceLocation.Function(region, row.number("id")), monospace = true)
        }
    }

    private fun function(loc: SourceLocation.Function): SourcePage {
        val row = one("SELECT fields FROM function_index WHERE region=? AND id=?", loc.region, loc.id)
        val fields = (MiniJson.parse(row.text("fields")) as? List<*>)?.map { (it as? Number)?.toLong() ?: error("Invalid function directory") }
            ?: error("Invalid function directory")
        require(fields.size == 7 && fields[0] == loc.id) { "Function key mismatch" }
        val name = "FUNCDATA_${loc.region}.BIN"
        val size = file(name).number("bytes")
        val rows = (1..5).map { i ->
            val position = fields[i]
            val empty = position == 0L || position == 0xFFFFFFFFL
            require(empty || position in 1 until size) { "Function pointer is outside file" }
            SourceRow(i.toString(), "Directory field $i (LE32 @${i * 4})", if (empty) "Empty / sentinel: $position" else "File offset $position — payload schema still source-only",
                if (empty) null else SourceLocation.FileBytes(name, position))
        }
        return SourcePage("${loc.region} function key ${loc.id}", "These are source payload pointers. No CAN IDs, transport/session settings, formula associations or runnable functions are invented.",
            fields = listOf(SourceField("Seven directory fields", row.text("fields"))), rows = rows,
            links = listOf(SourceLink("Source file", SourceLocation.FileDetail(name))))
    }

    private fun inventory(req: SourceRequest): SourcePage {
        val rows = read("SELECT * FROM archive_entry WHERE ?='' OR instr(lower(path),lower(?))>0 OR instr(lower(kind),lower(?))>0 ORDER BY path LIMIT 41 OFFSET ?", req.query, req.query, req.query, req.offset)
        return paged(req, rows, "Complete archive inventory", "Metadata only for 1,604 archive/nested entries. Vendor ELF/native code, archives, pictures and PDFs are excluded from the APK source library. All 1,410 recovered decoded payloads and all English records are available elsewhere in this browser.", search = "Source path or type") { row ->
            SourceRow(row.text("path"), row.text("path"), "${row.number("bytes")} bytes · ${row.text("kind")}\nSHA-256 ${row.text("sha256")}")
        }
    }

    private fun paged(req: SourceRequest, rows: List<SourceSqlRow>, title: String, explanation: String,
                      fields: List<SourceField> = emptyList(), links: List<SourceLink> = emptyList(), search: String? = null,
                      map: (SourceSqlRow) -> SourceRow): SourcePage = SourcePage(title, explanation, fields,
        rows.take(SourceRequest.PAGE_SIZE).map(map), links, search, rows.size > SourceRequest.PAGE_SIZE,
        rangeLabel = if (rows.isEmpty()) "No matching records" else "Records ${req.offset + 1}–${req.offset + min(rows.size, SourceRequest.PAGE_SIZE)}${if (rows.size > SourceRequest.PAGE_SIZE) " · more available" else ""}")

    private fun hexRows(bytes: ByteArray, start: Long): List<SourceRow> = bytes.asList().chunked(16).mapIndexed { i, chunk ->
        SourceRow((start + i * 16).toString(), "@${start + i * 16} (0x${(start + i * 16).toString(16)})", sourceHex(chunk.toByteArray()), monospace = true)
    }

    companion object {
        const val PASSIVE = "Complete recovered source data, available offline. This browser does not send commands, evaluate EXPRESS, select a vehicle or enable enhanced Toyota reads/writes. Existing diagnostic safeguards and manually configured catalog selections are unchanged."
        const val MENU_WARNING = "Manual inspection only — never automatic vehicle selection. Menu leaf → vehicle-key binding is unresolved. Browsing a path does not configure an ECU, choose CAN identifiers or make a diagnostic function runnable."
        const val FRAME_WARNING = "COMM reader-backed frame → dictionary links. IDs >10000 subtract 10000; 10000 is unchanged; 0 is empty. The three fields are named by offset, not assumed request/mask/response roles. No operational recipe is inferred."
        fun normalizeDictionaryId(id: Long): Long {
            require(id in 0..65535) { "Invalid source dictionary ID" }
            return if (id > 10000) id - 10000 else id
        }
    }
}
