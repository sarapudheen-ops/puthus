"""Bounded, NON-EXECUTABLE review candidates for Toyota's direct-21 numeric class.

No vendor script is executed (not even with Python eval). FUNCDATA +26 is an
EXPRESS key, NOT monitor +50 (an LSB key). COMM GetKwpRecData retains the first
service byte; SendCmdAndGetReceive_EX @1593c copies output+1 and returns len-1.
Consequently FDATA sees [local ID, data...] for 21xx and [01, TLVs...] for A801.
CustomPid strips BOTH service and ID; native position 1 therefore means offset 0.

Applicability is NOT the first nonzero live sample. Conditional P4 rows need a
separate A801 record/window, the discovered-ID membership from template 15, the
command-table join, and native viewer exclusions. Missing inputs disable the
candidate; no defaults, familiar PID labels or similar function keys substitute.

This module never approves an ECU address/init, vehicle hierarchy, general CALC
language or an executable pack. Only two bounded numeric equivalences can carry
separate SHA-pinned native CALC review evidence. Each result is still explicitly
disabled and must NOT be copied into data.pids. The Android app never executes
vendor code/scripts. Separate owner-authorized, bounded offline native comparisons
are implemented by toyota_native_oracle.py; they are not ECU or full-language proof.
"""
from dataclasses import dataclass
from decimal import Decimal
from collections import Counter
import ast
import hashlib
import math
import re
import struct

from toyota_calc import NativeCalcEvidence
from toyota_structure import (audit_command_pool, frame_pool_references, function_index,
                              section_directory, typed_table, uint)


class UntranslatedLive(ValueError):
    def __init__(self, reason, detail):
        super().__init__(detail)
        self.reason = reason


@dataclass(frozen=True)
class Arithmetic:
    formula: str
    bytes_needed: int


def translate_arithmetic(script):
    """One y=...; assignment; unsigned x/x1..x26, constants and real + - * /.

    All grouping is emitted explicitly; no Python precedence, function names,
    control flow, indexing or executable content is carried into the APK.
    Other CALC language classes (string/switch/signed helpers/bitfields/etc.) are
    intentionally NOT treated as this arithmetic class.
    """
    if not isinstance(script, str) or len(script) > 2048:
        raise UntranslatedLive("formula_untranslated", "Missing or oversized EXPRESS arithmetic")
    assignment = re.fullmatch(r"\s*y\s*=\s*([^;]+);\s*", script)
    if assignment is None:
        raise UntranslatedLive("formula_untranslated", "Not one numeric y assignment")
    try:
        root = ast.parse(assignment[1].strip(), mode="eval")
    except (SyntaxError, ValueError, RecursionError) as error:
        raise UntranslatedLive("formula_untranslated", "Unsupported EXPRESS arithmetic syntax") from error
    nodes = list(ast.walk(root))
    if len(nodes) > 192:
        raise UntranslatedLive("formula_untranslated", "EXPRESS arithmetic exceeds the node limit")
    highest = -1

    def visit(node, depth=0):
        nonlocal highest
        if depth > 24:
            raise UntranslatedLive("formula_untranslated", "EXPRESS arithmetic exceeds the depth limit")
        if isinstance(node, ast.Constant) and type(node.value) in (int, float):
            if not math.isfinite(node.value) or abs(node.value) > 10**15:
                raise UntranslatedLive("formula_untranslated", "Unbounded numeric literal")
            # App Formula deliberately has no exponent notation.
            return format(Decimal(str(node.value)), "f") if type(node.value) is float else str(node.value)
        if isinstance(node, ast.Name) and re.fullmatch(r"[xX](?:[1-9][0-9]*)?", node.id):
            number = int(node.id[1:] or "1")
            if not 1 <= number <= 26:
                raise UntranslatedLive("formula_untranslated", "Byte variable exceeds the app's A-Z window")
            highest = max(highest, number - 1)
            return chr(ord("A") + number - 1)
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, (ast.UAdd, ast.USub)):
            return "(" + ("-" if isinstance(node.op, ast.USub) else "+") + visit(node.operand, depth + 1) + ")"
        if isinstance(node, ast.BinOp) and type(node.op) in (ast.Add, ast.Sub, ast.Mult, ast.Div):
            symbol = {ast.Add: "+", ast.Sub: "-", ast.Mult: "*", ast.Div: "/"}[type(node.op)]
            return "(" + visit(node.left, depth + 1) + symbol + visit(node.right, depth + 1) + ")"
        raise UntranslatedLive("formula_untranslated", f"Unsupported numeric node {type(node).__name__}")

    formula = visit(root.body)
    if highest < 0:
        raise UntranslatedLive("formula_untranslated", "Constant expression is not a sensor reading")
    return Arithmetic(formula, highest + 1)


def monitor_key(row):
    # GetShowDsItem_P4 @1db6c..1dbe4 and CalDataFromSignalLSB @1efe8..1f074.
    return (row[0x44:0x48] + row[0x63:0x65] + row[0x4c:0x4e] + row[0x58:0x5a]
            + row[0x48:0x4c] + row[0x61:0x63] + row[0x66:0x67])


class P4SupportFiles:
    """Exact regional shared-table joins; byte sequences alone never approve a transport."""
    def __init__(self, sources, region, function_key):
        if region not in ("EU", "JP", "NA"):
            raise UntranslatedLive("live_applicability_untranslated", "Missing exact source region")
        self.prefix = region + "/"
        self.function_key = function_key
        try:
            pool = sources[self.prefix + "Toyota_bin_00.BIN"]
            if uint(pool, 0, 2) != 0:
                raise ValueError("Wrong command-pool tag")
            self.pool = audit_command_pool(pool)
            raw = sources[self.prefix + "Toyota_bin_11.BIN"]
            typed_table(raw, 17)
            self.frames = frame_pool_references(raw, self.pool)
            self.bindings = self.rows(sources[self.prefix + "Toyota_bin_12.BIN"], 274, 16)
            self.exclusions = self.rows(sources[self.prefix + "Viewer_bin_12.BIN"], 18, 4)
        except (KeyError, ValueError) as error:
            raise UntranslatedLive("live_applicability_untranslated", "Missing or malformed native P4 support tables: " + str(error)) from error

    @staticmethod
    def rows(raw, tag, stride):
        table = typed_table(raw, tag)
        if table.count and table.stride != stride:
            raise ValueError(f"Unreviewed table stride for tag {tag}")
        return [(10 + i * stride, table.body[i * stride:(i + 1) * stride]) for i in range(table.count)]

    def frame(self, template, request, response):
        # COMM GetFuncCommFrameInfo @182e8 compares the full function/template and +8==0.
        matches = [(at, row) for at, row in self.bindings if uint(row, 0, 2) == self.function_key
                   and uint(row, 2, 2) == template and uint(row, 8, 2) == 0]
        if len(matches) != 1:
            raise UntranslatedLive("live_applicability_untranslated", f"No unique exact support binding for template {template}")
        binding_at, binding = matches[0]
        matches = [f for f in self.frames if f["frameId"] == uint(binding, 6, 2)]
        if len(matches) != 1:
            raise UntranslatedLive("service_reply_untranslated", "Missing/ambiguous bound support frame")
        frame = matches[0]
        refs = frame["referencesByFieldOffset"]
        if any("error" in refs[str(i)] for i in (2, 4, 6)):
            raise UntranslatedLive("service_reply_untranslated", "Support frame references an unbounded source command")
        if ([bytes.fromhex(refs[str(i)]["bytesHex"]) for i in (2, 4, 6)]
                != [request, b"", response]):
            raise UntranslatedLive("service_reply_untranslated", "Not the reviewed exact P4 support request/mask/reply class")
        sources = [f"{self.prefix}Toyota_bin_12.BIN@{binding_at:08x}",
                   f"{self.prefix}Toyota_bin_11.BIN@{frame['rowOffset']:08x}"]
        for field in (2, 6):
            index = refs[str(field)]["poolIndex"]
            entry = self.pool["entries"][index - 1]
            sources.extend((f"{self.prefix}Toyota_bin_00.BIN@{entry['descriptorOffset']:08x}#pool{index}",
                            f"{self.prefix}Toyota_bin_00.BIN@{entry['bytesOffset']:08x}"))
        return sources

    def not_excluded(self, signal):
        # FDATA GetDataMonitorListP4 @17b04..17b98. Viewer tables are separate
        # from Toyota_bin_*; a missing viewer table must never mean no exclusions.
        if 8000 <= signal < 10000 or 40000 <= signal < 42000 or any(
                uint(row, 0, 2) == self.function_key and uint(row, 2, 2) == signal for _, row in self.exclusions):
            raise UntranslatedLive("native_applicability_excluded", "Native P4 display filter excludes this signal")
        return self.prefix + "Viewer_bin_12.BIN@00000000"


class Direct21Live:
    def __init__(self, function_file, function_bytes, function_key, ecu_file, ecu_bytes, streams, expressions,
                 *, support_files=None, calc_review=None):
        if calc_review is not None and not isinstance(calc_review, NativeCalcEvidence):
            raise ValueError("CALC review needs validated native data evidence")
        function_bytes, ecu_bytes = bytes(function_bytes), bytes(ecu_bytes)
        self.function_file = function_file
        self.function_key = function_key
        self.ecu_file = ecu_file
        self.streams = streams
        self.expressions = expressions
        self.monitors = {}
        self.dynamic_lsb_ids = set()
        self.support_files = support_files
        self.calc_review = calc_review
        self.support_groups = []
        self.command_info = []
        index, _ = function_index(function_bytes)
        rows = [row for row in index if row[0] == function_key]
        if len(rows) != 1:
            raise ValueError("Function does not have one exact FUNCDATA row")
        self.index_offset = 1 + 28 * index.index(rows[0])
        start = rows[0][3]
        if start in (0, 0xffffffff):
            raise ValueError("Function has no live-data block")
        pointers = {p for row in index for p in row[1:6] if p not in (0, 0xffffffff)} | {len(function_bytes)}
        stop = min(p for p in pointers if p > start)
        count, commands = uint(function_bytes, start, 2), uint(function_bytes, start + 2, 2)
        at = start + 4 + count * 30
        if at > stop:
            raise ValueError("Live rows exceed their indexed source block")
        self.rows = [(start + 4 + i * 30, function_bytes[start + 4 + i * 30:start + 4 + (i + 1) * 30]) for i in range(count)]
        self.commands = []
        for _ in range(commands):
            size = uint(function_bytes, at, 1)
            if at + 1 + size > stop:
                raise ValueError("Live command exceeds its indexed source block")
            self.commands.append((at, function_bytes[at + 1:at + 1 + size]))
            at += 1 + size
        if at != stop:
            # A different tail format must be translated separately, not skipped.
            raise UntranslatedLive("record_schema_untranslated", "Untranslated tail in the direct-21 live block")
        for section in section_directory(ecu_bytes):
            if section.identity not in (3, 6, 10, 38):
                continue
            table = typed_table(ecu_bytes[section.offset:section.end])
            tag, stride = {3: (3, 8), 6: (6, 8), 10: (10, 104), 38: (39, 20)}[section.identity]
            if table.identity != tag or (table.count and table.stride != stride):
                raise UntranslatedLive("record_schema_untranslated", "Unreviewed monitor/support/LSB table shape")
            for i in range(table.count):
                row = table.body[i * stride:(i + 1) * stride]
                offset = section.offset + 10 + i * stride
                if section.identity in (3, 6):
                    # GetEnablePidListP4 / GetDSCmdDataInfoP4 stop at the first nonzero u16.
                    if uint(row, 0, 2) != 0:
                        break
                    (self.support_groups if section.identity == 3 else self.command_info).append((offset, row))
                elif section.identity == 10:
                    # GetDataMonitorListP4 stops before a monitor with nonzero +5c.
                    if uint(row, 0x5c, 2) != 0:
                        break
                    self.monitors.setdefault(monitor_key(row), []).append((offset, row))
                else:
                    # Conservative: exclude even conditional/unreached LSB overrides.
                    self.dynamic_lsb_ids.add(uint(row, 8, 2))

    def prerequisites(self, local_id, start_bit, end_bit, monitor_at):
        context = self.support_files
        if not isinstance(context, P4SupportFiles) or context.function_key != self.function_key or not self.ecu_file.startswith(context.prefix):
            raise UntranslatedLive("live_applicability_untranslated", "Missing exact regional/function P4 support inputs")
        commands = [(at, row) for at, row in self.command_info if row[2] == local_id]
        groups = [(at, row) for at, row in self.support_groups if row[4] <= local_id <= row[4] + 31]
        if len(commands) != 1 or len(groups) != 1 or any(row[4] % 32 for _, row in self.support_groups):
            raise UntranslatedLive("live_applicability_untranslated", "No unique native command-table/discovery-group membership")
        # Native CaculateBit produces a 32-bit result. This bounded class only emits
        # byte-aligned windows <=32 bits, where sum(selected unsigned bytes)!=0 is equivalent.
        width = (end_bit - start_bit + 1) // 8
        if not 1 <= width <= 4:
            raise UntranslatedLive("live_applicability_untranslated", "Support window is outside the reviewed 32-bit class")
        # GetDSCmdDataInfoP4 @1aadc..1aaf0 uses nonzero command-row +3 as the
        # polling data length. With +3==0 it requires the same-ID A801 cache
        # entry and uses that entry's length (@1aaf4..1abd4); absence prunes it.
        # Conditional checks already require that record and the whole window.
        # Do not admit a signal outside a shorter, nonzero declared poll window.
        declared_length = commands[0][1][3]
        if declared_length and end_bit >= declared_length * 8:
            raise UntranslatedLive("live_applicability_untranslated", "Signal exceeds the native command-row polling length")
        snapshot_sources = context.frame(19, b"\xa8\x01", b"\xe8\x01")
        discovery_sources = context.frame(15, b"\x21\x00", b"\x61")
        group_at, group = groups[0]
        bit = local_id - group[4] - 1
        # The group itself is admitted by ANY nonempty reply. Bit 31 is a continuation
        # bit, not membership of the next group; that group is queried independently.
        membership = "A*0+1" if bit == -1 else f"bit({chr(ord('A') + bit // 8)},{7 - bit % 8})"
        return [
            {"request": "A801", "recordId": local_id, "dataOffset": start_bit // 8,
             "formula": "+".join(chr(ord("A") + i) for i in range(width)),
             "sourceRecords": [f"{self.ecu_file}@{monitor_at:08x}#monitor", *snapshot_sources]},
            {"request": "21" + f"{group[4]:02X}", "formula": membership,
             "sourceRecords": [f"{self.ecu_file}@{group_at:08x}#discovery-group",
                               f"{self.ecu_file}@{commands[0][0]:08x}#command-info", *discovery_sources]},
        ]

    def translate(self, row_index):
        if type(row_index) is not int or not 0 <= row_index < len(self.rows):
            raise ValueError("Live row index is outside the source table")
        at, row = self.rows[row_index]
        matches = self.monitors.get(row[4:8] + row[10:23], [])
        if len(matches) != 1:
            raise UntranslatedLive("live_applicability_untranslated", "No unique exact source-to-monitor join")
        monitor_at, monitor = matches[0]
        if not (monitor[0x62] & 1) or row[20] != 0 or row[21:23] != b"\x03\x00" or monitor[0x60] != 2:
            raise UntranslatedLive("live_applicability_untranslated", "Not the reviewed conditional numeric P4 support/dialect class")
        if uint(row, 12, 2) in self.dynamic_lsb_ids:
            raise UntranslatedLive("dynamic_scaling_untranslated", "Native dynamic LSB can override EXPRESS")
        if row[24:26] != b"\xff\x02":
            raise UntranslatedLive("formula_untranslated", "Bitfield/non-numeric live class is not yet translated")
        command_index = uint(row, 8, 2)
        if command_index >= len(self.commands):
            raise UntranslatedLive("service_reply_untranslated", "Command index is outside the declared live pool")
        command_at, request = self.commands[command_index]
        if len(request) != 2 or request[0] != 0x21 or uint(row, 10, 2) != request[1]:
            raise UntranslatedLive("service_reply_untranslated", "Not a direct local-ID read with an exact identifier join")
        start_bit, end_bit = uint(monitor, 0x52, 2), uint(monitor, 0x54, 2)
        if start_bit % 8 != 0 or end_bit % 8 != 7 or not start_bit <= end_bit:
            raise UntranslatedLive("formula_untranslated", "Not a complete byte-aligned native signal window")
        byte_count = (end_bit - start_bit + 1) // 8
        if row[23] != start_bit // 8 + 1 or not 1 <= byte_count <= 26:
            raise UntranslatedLive("service_reply_untranslated", "Native position does not match the exact reply-data window")
        calc_id = uint(row, 26, 4)
        script = self.expressions.get(f"{calc_id:08X}")
        arithmetic = translate_arithmetic(script)
        if arithmetic.bytes_needed != byte_count:
            raise UntranslatedLive("formula_untranslated", "EXPRESS and monitor signal widths disagree")
        label_id = uint(row, 0, 4)
        label = self.streams.get(f"{label_id:08X}")
        if not isinstance(label, str) or not label.strip() or label.count("\x00") > 1:
            raise UntranslatedLive("record_schema_untranslated", "Missing or unsupported live label/unit")
        name, _, unit = label.partition("\x00")
        # These are native display bounds, not an invented physical validity clamp.
        maximum, minimum = struct.unpack_from("<dd", monitor, 0x18)
        if not all(math.isfinite(v) for v in (maximum, minimum)) or minimum >= maximum:
            raise UntranslatedLive("record_schema_untranslated", "Invalid native numeric display bounds")
        identity = f"{self.function_file}@{at:08x}#function{self.function_key}:live{row_index}"
        checks = self.prerequisites(request[1], start_bit, end_bit, monitor_at)
        exclusion_source = self.support_files.not_excluded(uint(row, 12, 2))
        calc = self.calc_review.describe(script, arithmetic.formula, arithmetic.bytes_needed) if self.calc_review else None
        return {
            "id": identity, "kind": "numeric-live-review", "enabled": False,
            "reasons": ["ecu_entry_untranslated"] + ([] if calc else ["calc_semantics_unreviewed"]),
            **({"calcReview": calc} if calc else {}),
            "name": name.strip(), "unit": unit.strip(), "request": request.hex().upper(),
            "formula": arithmetic.formula, "dataOffset": row[23] - 1,
            "supportChecks": checks, "min": minimum, "max": maximum, "decimals": 3,
            "sourceRecords": [identity, f"{self.function_file}@{self.index_offset:08x}",
                f"{self.function_file}@{command_at:08x}#live-command{command_index}",
                f"{self.ecu_file}@{monitor_at:08x}#monitor", f"DSTREAM_EN.GAG#{label_id:08X}",
                f"EXPRESS.GAG#{calc_id:08X}", exclusion_source] + (calc["sourceRecords"] if calc else []),
        }


def review_direct21(sources, streams, expressions, *, region, function_key, ecu_file, calc_evidence=None):
    """Account for EVERY row of one explicitly chosen source function/ECU join.

    This is an inspection artifact, not a catalog, datapack or enabled-system
    producer. Never infer other ECU/region/vehicle applicability from this pair.
    Invalid/missing input framing rejects the review as a whole; untranslated
    row semantics retain the exact row ID and first blocking reason, not a
    silently shortened list of apparently working signals.
    """
    context = P4SupportFiles(sources, region, function_key)
    if not ecu_file.startswith(context.prefix):
        raise ValueError("Review ECU is not in the exact requested source region")
    function_file = f"FUNCDATA_{region}.BIN"
    parser = Direct21Live(function_file, sources[function_file], function_key, ecu_file, sources[ecu_file],
                          streams, expressions, support_files=context,
                          calc_review=NativeCalcEvidence(calc_evidence) if calc_evidence is not None else None)
    items, rejected = [], Counter()
    candidates = 0
    for i, (at, _) in enumerate(parser.rows):
        try:
            item = parser.translate(i)
            candidates += 1
        except UntranslatedLive as error:
            identity = f"{function_file}@{at:08x}#function{function_key}:live{i}"
            rejected[error.reason] += 1
            item = {"id": identity, "kind": "untranslated-live-review", "enabled": False,
                    "reasons": [error.reason], "detail": str(error), "sourceRecords": [identity]}
        items.append(item)
    names = [function_file, ecu_file] + [context.prefix + n for n in
             ("Toyota_bin_00.BIN", "Toyota_bin_11.BIN", "Toyota_bin_12.BIN", "Viewer_bin_12.BIN")]
    return {"kind": "disabled-live-review", "region": region, "functionKey": function_key, "ecuFile": ecu_file,
            "sourceRows": len(parser.rows), "disabledReviewCandidates": candidates,
            "calcReviewedCandidates": sum("calcReview" in item for item in items),
            "rejectedRows": sum(rejected.values()), "rejectionsByFirstReason": dict(sorted(rejected.items())),
            "executableDefinitions": 0, "items": items,
            "inputs": [{"file": n, "bytes": len(sources[n]), "sha256": hashlib.sha256(sources[n]).hexdigest()} for n in names]}
