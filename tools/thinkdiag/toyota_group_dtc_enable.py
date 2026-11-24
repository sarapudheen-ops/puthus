#!/usr/bin/env python3
"""Stage 1d - per-system DTC read/clear enablement for the group-oracle-passed compile groups.

This module wires passing Stage 1c groups through the converter via the SAME
path as ECMECT's pinned DTC category. A system becomes enabled only when ALL of
these fail-closed gates hold:

  1. its group key matches a `schema/dtc-group-enable-v1.json` pin entry whose
     pinned properties (representative, dtcLabels, readRequest, clearRequest,
     layout, definition hashes) match the compiled group definition exactly;
  2. the system's OWN compile on its own SYSLIST row succeeds and its normalized
     `definitionHash` equals the pinned group hash (system bytes other than the
     caller mode byte 33 must not move the DTC definition; mismatches stay
     unresolved with a literal reason);
  3. `--read-artifacts` supplies the ECU-file review and that system's link
     projection (`linkStatus == 'proven'`) is proven for that exact
     (region, function, selector, transport) entry - exactly the per-system
     link gate the converter already applies;
  4. the constructed DTC-only data passes `validate_system` end to end.

Pinned ECMECT and 2007 Innova systems never take this path; their exact packs
stay byte-identical. Nothing here is transport, adapter or vehicle approval.
The compiled group artifacts are measured outputs of the immutable corpus
determinism contract; the group oracle of Stage 1c (PR #28, run 45226018714)
provides the compile-groups provenance, not physical validation.
"""
import copy
import hashlib
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from vehicle_bundle import canonical
from vehicle_system_validation import validate_system

SOURCE_SHA256 = (
    "f83583e3d2d02b811db3f982f8ce7a015d74c14433a22752a71d4e3064ed2f75"
)
PIN_FILE = "dtc-group-enable-v1.json"
CLEAR_UNRESOLVED_REASON = "clear_unresolved:read-only-slice-never-exposes-clearing"

REGION_IDS = {"EU": 1, "NA": 2, "JP": 3}

#: Stage 1c (PR #28, run 45226018714) passed exactly these four compile groups
#: (region, exact ECU data file, source function key, function selector). The
#: representative system and the per-system counts are the Stage 1c DATA
#: (`docs/toyota-progress.md`, Stage 1c table). Each group's compiled
#: definition hashes come from the deterministic rep compile and are pinned in
#: `schema/dtc-group-enable-v1.json` after the first CI measurement; a group
#: without a matching pin entry enables nothing.
TARGET_GROUPS = (
    {
        "region": "EU", "ecuFile": "EU/Ecu_Data_0081.BIN", "function": 61,
        "selector": 0x11, "representative": "SYSLIST_EU.BIN@0009d8f2",
        "systems": 79, "dtcLabels": 1414, "readRequest": "13B0",
        "clearRequest": "04", "layout": {
            "countIndex": 0, "recordStart": 1, "recordBytes": 2,
            "assemblyKind": 2, "countIsPresence": False, "modeLowBits": 1},
    },
    {
        "region": "EU", "ecuFile": "EU/Ecu_Data_0081.BIN", "function": 61,
        "selector": 0x51, "representative": "SYSLIST_EU.BIN@00023995",
        "systems": 79, "dtcLabels": 1414, "readRequest": "13B0",
        "clearRequest": "04", "layout": {
            "countIndex": 0, "recordStart": 1, "recordBytes": 2,
            "assemblyKind": 2, "countIsPresence": False, "modeLowBits": 1},
    },
    {
        "region": "JP", "ecuFile": "JP/Ecu_Data_0081.BIN", "function": 61,
        "selector": 0x11, "representative": "SYSLIST_JP.BIN@00003875",
        "systems": 30, "dtcLabels": 1111, "readRequest": "13B0",
        "clearRequest": "04", "layout": {
            "countIndex": 0, "recordStart": 1, "recordBytes": 2,
            "assemblyKind": 2, "countIsPresence": False, "modeLowBits": 1},
    },
    {
        "region": "NA", "ecuFile": "NA/Ecu_Data_0081.BIN", "function": 61,
        "selector": 0x51, "representative": "SYSLIST_NA.BIN@0002bf2a",
        "systems": 15, "dtcLabels": 1414, "readRequest": "13B0",
        "clearRequest": "04", "layout": {
            "countIndex": 0, "recordStart": 1, "recordBytes": 2,
            "assemblyKind": 2, "countIsPresence": False, "modeLowBits": 1},
    },
)

ORACLE_PROVENANCE = {
    "stage1cRun": "45226018714", "head": "c17d25e",
    "verdict": "4/4 compiled groups PASS; 203/203 compiled per-system rows; "
               "5353 labels",
    "note": "Group-oracle evidence proves the compile group only. It is NOT "
            "transport, adapter or vehicle approval, and no physical validation "
            "is claimed.",
}


def target_key(region, ecu_file, function, selector):
    return (region, ecu_file, function, selector)


TARGETS = {target_key(g["region"], g["ecuFile"], g["function"], g["selector"]):
           dict(g) for g in TARGET_GROUPS}
for _target in TARGETS.values():
    _target["groupKey"] = target_key(_target["region"], _target["ecuFile"],
                                     _target["function"], _target["selector"])


def strip_source_records(value):
    """Drop every `sourceRecords` list at any depth; everything else stays."""
    if isinstance(value, dict):
        return {k: strip_source_records(v) for k, v in value.items()
                if k != "sourceRecords"}
    if isinstance(value, list):
        return [strip_source_records(item) for item in value]
    return value


def strip_hash_exemptions(faults):
    """Normalization for `definition_hash`: the exact shipped DTC read surface.

    `sourceRecords` carry per-system identity and are excluded at any depth.
    `nativeMode` is the row's own caller-mode byte (byte 33), which the group
    key deliberately excludes, so it is recorded literally in the compiled
    definition but excluded from the hash comparison (Stage 1c design: the
    per-system oracle must not resample it). The `workflow` and `clear` objects
    are compiled and pinned separately through the representative's full
    canonical `definitionSha256`, but they are NOT part of the shipped
    read-only DTC slice: the workflow's session-changing 10 5F setup belongs to
    the separate confirmed source-diagnostic-session path (which requires fresh
    identification), and the fault-memory clear is never exposed in the
    read-only slice (`toyota_ecmect_pack.py:163`). Everything else - requests,
    layout and labels - is hash-covered, so any other per-system movement is a
    definition_hash_mismatch.
    """
    normalized = strip_source_records(faults)
    if isinstance(normalized, dict):
        normalized = {k: v for k, v in normalized.items() if k not in ("workflow", "clear")}
    return normalized


def definition_hash(faults):
    """Normalized content hash of a compiled P3/P4 faults section."""
    return hashlib.sha256(canonical(strip_hash_exemptions(faults))).hexdigest()


def definition_sha256(faults):
    """Full canonical hash of a compiled faults section (regression pin)."""
    return hashlib.sha256(canonical(faults)).hexdigest()


def pin_entry_checks(entry):
    """Literal, fail-closed validation of one `dtc-group-enable-v1.json` entry."""
    problems = []
    if not isinstance(entry, dict):
        return ["pin entry is not an object"]
    expected = {"region", "ecuFile", "function", "selector", "representative",
                "systems", "dtcLabels", "readRequest", "clearRequest", "layout",
                "definitionSha256", "definitionHash"}
    extra = sorted(set(entry) - expected)
    missing = sorted(expected - set(entry))
    if extra or missing:
        problems.append(f"pin entry keys mismatch; extra={extra} missing={missing}")
    key = (entry.get("region"), entry.get("ecuFile"), entry.get("function"),
           entry.get("selector"))
    if key not in TARGETS:
        problems.append(f"pin group {key!r} is not a Stage 1c target group")
        return problems
    target = TARGETS[key]
    for field in ("representative", "systems", "dtcLabels", "readRequest",
                  "clearRequest", "layout"):
        if entry.get(field) != target[field]:
            problems.append(
                f"pin {field} {entry.get(field)!r} != Stage 1c {target[field]!r}")
    for field in ("definitionSha256", "definitionHash"):
        value = entry.get(field)
        if not isinstance(value, str) or len(value) != 64:
            problems.append(f"pin {field} is not a sha256 hex digest")
    return problems


def load_pin(directory):
    """Load and strictly verify the group enable pin; unknown fields fail."""
    path = pathlib.Path(directory) / PIN_FILE
    raw = json.loads(path.read_text())
    problems = []
    if not isinstance(raw, dict):
        raise ValueError(f"{path}: pin must be a JSON object")
    expected_keys = {"kind", "formatVersion", "sourceSha256", "note",
                     "oracleProvenance", "groups"}
    extra = sorted(set(raw) - expected_keys)
    missing = sorted(expected_keys - set(raw))
    if extra or missing:
        raise ValueError(f"{path}: pin keys mismatch; extra={extra} missing={missing}")
    if raw["kind"] != "compiled-dtc-group-enable-pin":
        raise ValueError(f"{path}: kind mismatch")
    if type(raw["formatVersion"]) is not int or raw["formatVersion"] != 1:
        raise ValueError(f"{path}: formatVersion mismatch")
    if raw["sourceSha256"] != SOURCE_SHA256:
        raise ValueError(f"{path}: sourceSha256 mismatch")
    if not isinstance(raw["groups"], list):
        raise ValueError(f"{path}: groups must be a list")
    seen_keys = set()
    for entry in raw["groups"]:
        entry_problems = pin_entry_checks(entry)
        key = (entry.get("region"), entry.get("ecuFile"),
               entry.get("function"), entry.get("selector"))
        if key in seen_keys:
            entry_problems.append(f"duplicate pin group {key!r}")
        seen_keys.add(key)
        problems.extend(entry_problems)
    if problems:
        raise ValueError(f"{path}: " + "; ".join(sorted(set(problems))))
    return {"path": str(path),
            "groups": {target_key(e["region"], e["ecuFile"], e["function"],
                                  e["selector"]): e for e in raw["groups"]},
            "raw": raw}


def faults_projection(compiled):
    """The shipped DTC-only faults section of one compiled P3/P4 definition."""
    faults = compiled.get("faults") if isinstance(compiled, dict) else None
    return faults if isinstance(faults, dict) else None


def match_pinned_properties(faults, target):
    """Literal comparison of the compiled rep definition to the Stage 1c DATA."""
    problems = []
    layout = faults.get("layout", {})
    labels = layout.get("labels", [])
    if len(labels) != target["dtcLabels"]:
        problems.append(f"dtcLabels:{len(labels)}!={target['dtcLabels']}")
    read = faults.get("read", {})
    if read.get("request") != target["readRequest"]:
        problems.append(
            f"readRequest:{read.get('request')}!={target['readRequest']}")
    clear = faults.get("clear", {})
    if clear.get("request") != target["clearRequest"]:
        problems.append(
            f"clearRequest:{clear.get('request')}!={target['clearRequest']}")
    for field, expected in target["layout"].items():
        actual = layout.get(field)
        if type(actual) is not type(expected) or actual != expected:
            problems.append(f"layout.{field}:{actual!r}!={expected!r}")
    return problems


def system_row_bytes(sources, region, system_id):
    """Fetch one SYSLIST row's exact source bytes by its ID, fail-closed."""
    from toyota_runtime_compile import source_id
    from toyota_structure import system_lists
    name = f"SYSLIST_{region}.BIN"
    raw = sources.get(name)
    if raw is None:
        raise ValueError(f"missing {name}")
    _index, blocks = system_lists(raw)
    for offset, rows in sorted(blocks.items()):
        for position, row in enumerate(rows):
            if source_id(name, offset + 2 + 37 * position) == system_id:
                return row
    raise ValueError(f"{system_id} not present in {name}")


def entry_for(sources, region, function, selector):
    from toyota_entries import EntryIndex
    return EntryIndex(sources["SYSTEMENTER.BIN"]).select(
        REGION_IDS[region], function, selector)


class GroupQualifier:
    """Per-converter group qualification: compile the rep, then every member.

    Qualification runs and reports even without a matching pin entry so the
    first CI measurement can produce the exact pin hashes; enablement stays
    impossible until a pin entry matches. Nothing here enables a system.
    """

    def __init__(self, sources, libraries, pin):
        self.sources = sources
        self.libraries = libraries
        self.pin = pin
        self._groups = {}
        self.records = {}

    def _compile(self, region, system_id, system_bytes, native_transport):
        from toyota_runtime_compile import compile_p3p4_diagnostics
        return compile_p3p4_diagnostics(
            self.sources, self.libraries, region=region, system_id=system_id,
            system_bytes=system_bytes, native_transport=native_transport)

    def pinned_path(self, region, key, selector, ecu_file, identity):
        """Record one byte-identical pinned-path outcome (ECMECT/Innova slices).

        The two pinned read-only slices belong to targeted groups but keep
        their own verified enablements and byte-identical packs; their literal
        per-system outcome is `pinned-path`, never a group enablement.
        """
        group_key = target_key(region, ecu_file, key, selector)
        if group_key not in TARGETS:
            return None
        record = {
            "system": identity,
            "group": [region, ecu_file, key, selector],
            "status": "pinned-path",
            "reason": None,
            "definitionHash": None,
            "groupDefinitionHash": None,
            "groupDefinitionSha256": None,
            "pinMatched": False,
            "_compiled": None,
        }
        self.records.setdefault(group_key, []).append(record)
        return record

    def qualify(self, region, key, selector, ecu_file, identity, raw,
                native_transport):
        """Qualify one SYSLIST row and record its report outcome; enables nothing.

        Record `status` is one of `enabled-ready` (every gate except the
        per-system link projection passed and a pin entry matched),
        `unresolved`/`unresolved-pin` (a gate failed; `reason` is literal), or
        `pinned-path` (ECMECT/Innova keep their byte-identical packs).
        """
        group_key = target_key(region, ecu_file, key, selector)
        target = TARGETS.get(group_key)
        if target is None:
            return None
        record = {
            "system": identity,
            "group": [region, ecu_file, key, selector],
            "status": None,
            "reason": None,
            "definitionHash": None,
            "groupDefinitionHash": None,
            "groupDefinitionSha256": None,
            "pinMatched": False,
            "_compiled": None,
        }
        self.records.setdefault(group_key, []).append(record)
        group = self._groups.get(group_key)
        if group is None:
            group = self._build_group(group_key, target, identity, raw,
                                      native_transport)
            self._groups[group_key] = group
        record["groupDefinitionHash"] = group.get("definitionHash")
        record["groupDefinitionSha256"] = group.get("definitionSha256")
        record["pinMatched"] = group["pinMatched"]
        if not group["ok"]:
            record["status"] = "unresolved"
            record["reason"] = f"group_compile_rejected:{group['reason']}"
            return record
        if identity == target["representative"]:
            compiled = group["compiled"]
        else:
            try:
                compiled = self._compile(region, identity, raw,
                                         native_transport)
            except Exception as error:
                record["status"] = "unresolved"
                record["reason"] = (
                    f"group_compile_rejected:{type(error).__name__}: {error}")
                return record
        faults = faults_projection(compiled)
        if faults is None:
            record["status"] = "unresolved"
            record["reason"] = "group_compile_rejected:missing faults projection"
            return record
        record["definitionHash"] = definition_hash(faults)
        if record["definitionHash"] != group["definitionHash"]:
            record["status"] = "unresolved"
            record["reason"] = (
                f"definition_hash_mismatch:{group['definitionHash']}:"
                f"{record['definitionHash']}")
            return record
        if not group["pinMatched"]:
            record["status"] = "unresolved-pin"
            record["reason"] = f"group_pin_absent_or_mismatched:{group['pinReason']}"
            return record
        record["status"] = "enabled-ready"
        record["_compiled"] = compiled
        return record

    def _build_group(self, group_key, target, first_identity, first_raw,
                     first_transport):
        group = {
            "ok": False, "reason": None, "pinMatched": False,
            "pinReason": None, "definitionHash": None,
            "definitionSha256": None, "compiled": None,
        }
        # The pinned representative's compile decides the whole group's
        # definition hashes; every member must hash-match it.
        try:
            rep_row = system_row_bytes(self.sources, target["region"],
                                       target["representative"])
            if target["representative"] == first_identity:
                rep_transport = first_transport
            else:
                entry = entry_for(self.sources, target["region"],
                                  target["function"], target["selector"])
                if entry is None:
                    group["reason"] = "missing SYSTEMENTER entry"
                    return group
                rep_transport = entry.transport
            compiled = self._compile(target["region"], target["representative"],
                                     rep_row, rep_transport)
        except Exception as error:
            group["reason"] = f"{type(error).__name__}: {error}"
            return group
        faults = faults_projection(compiled)
        if faults is None:
            group["reason"] = "missing faults projection"
            return group
        group["compiled"] = compiled
        group["definitionHash"] = definition_hash(faults)
        group["definitionSha256"] = definition_sha256(faults)
        property_problems = match_pinned_properties(faults, target)
        if property_problems:
            group["reason"] = "pinned_properties_mismatch:" + ",".join(
                property_problems)
            return group
        group["ok"] = True
        pin = self.pin["groups"].get(group_key)
        if pin is None:
            group["pinReason"] = "no pin entry"
            return group
        pin_problems = []
        for field in ("definitionSha256", "definitionHash"):
            if pin[field] != group[field]:
                pin_problems.append(
                    f"pin {field} {pin[field]} != compiled {group[field]}")
        if pin_problems:
            group["pinReason"] = "; ".join(pin_problems)
            return group
        group["pinMatched"] = True
        return group

    def groups(self):
        return self._groups


def request_pair(lifecycle):
    """The exact KWP routing pair for this entry's initial template.

    `requestId` is `80` + the wire target/source words (`convention`, the same
    request-header rule the pinned ECMECT pack carries); `responseId` is the
    only address-swap pair `validate_system`/`DiagnosticLink` accept for it.
    The wire-trace response-header claim stays omitted from `linkSource`, as
    before.
    """
    wire = lifecycle.entry.packet(lifecycle.initial.request)
    request_id = bytes([0x80, wire[1], wire[2]]).hex().upper()
    response_id = bytes([0x80, wire[2], wire[1]]).hex().upper()
    return request_id, response_id


def enable_system(value, compiled, target, system_record, lifecycle):
    """Attach the DTC-read-only definitions to one qualified system (fail-closed).

    The shipped slice is exactly `read` + `layout` (with labels): the group's
    compiled `workflow` (session-changing 10 5F setup) and `clear` are pinned
    through the representative's `definitionSha256` but never shipped here, on
    the same rule as the pinned ECMECT read-only slice
    (`toyota_ecmect_pack.py:163`, `:258-259`). Clear stays unresolved with the
    literal reason `CLEAR_UNRESOLVED_REASON`.

    Every mutation runs on a trial copy first and must pass `validate_system`
    with the exact counts the app's bookkeeping expects; on any problem the
    system is left untouched and the caller receives the literal reason.
    """
    faults = faults_projection(compiled)
    if faults is None:
        return "group_data_rejected:missing faults projection"
    faults = {k: v for k, v in faults.items() if k not in ("workflow", "clear")}
    from toyota_ecmect_pack import link_for
    identity = value["sourceRecords"][0]
    entry_source = value["sourceRecords"][-1]
    function_source = value["functionSource"]["sourceRecord"]
    ecu_file = target["ecuFile"]
    address = ecu_file.rsplit("_", 1)[-1].split(".", 1)[0]
    request_id, response_id = request_pair(lifecycle)
    link = link_for(lifecycle)["link"]
    trial = copy.deepcopy(value)
    trial["enabled"] = True
    trial["reasons"] = []
    trial["data"] = {
        "pids": [],
        "ecus": [{
            "address": address,
            "name": value["name"],
            "protocol": "KWP",
            "requestId": request_id,
            "responseId": response_id,
            "link": link,
            "sourceRecords": [identity, function_source, entry_source],
            "diagnostics": {"identification": [], "faults": faults},
            "notes": ["Compiled P3/P4 DTC read slice joined from the "
                      "exact system row; group-oracle PASS (Stage 1c run "
                      "45226018714) proves the compile group only. NOT "
                      "transport, adapter or vehicle approval; no physical "
                      "validation is claimed. " + CLEAR_UNRESOLVED_REASON + "."],
        }],
    }
    trial["counts"] = dict(trial["counts"])
    trial["counts"]["dtcs"] = {
        "converted": len(faults["layout"]["labels"]), "unresolved": None}
    try:
        validate_system(trial)
    except Exception as error:
        return f"group_data_rejected:{type(error).__name__}: {error}"
    value["data"] = trial["data"]
    value["counts"] = trial["counts"]
    value["reasons"] = []
    value["readOnly"] = True
    value["groupDtcEnable"] = {
        "group": list(target["groupKey"]),
        "representative": target["representative"],
        "groupDefinitionSha256": system_record["groupDefinitionSha256"],
        "definitionHash": system_record["definitionHash"],
        "dtcLabels": len(faults["layout"]["labels"]),
        "readRequest": target["readRequest"],
        "clearRequest": target["clearRequest"],
        "clearShipped": False,
        "clearReason": CLEAR_UNRESOLVED_REASON,
        "provenance": {
            "requestId": "convention (link_for request-header rule)",
            "responseId": "convention (required address-swap pair)",
            "address": "corpus ECU file name",
        },
        "sourceRecords": [f"{PIN_FILE}#groups", "stage1c#45226018714"],
    }
    return None


def verify_group_dtc_system(system, pin_groups):
    """Verifier-side check of one group-enabled system (fail-closed)."""
    problems = []
    enable = system.get("groupDtcEnable")
    if not isinstance(enable, dict):
        return ["missing groupDtcEnable record"]
    key = tuple(enable.get("group") or [])
    if key not in pin_groups:
        problems.append(f"groupDtcEnable.group {key!r} has no pin entry")
        return problems
    pin = pin_groups[key]
    if key not in TARGETS:
        problems.append(f"groupDtcEnable.group {key!r} is not a target group")
        return problems
    if enable.get("representative") != pin.get("representative"):
        problems.append("groupDtcEnable.representative does not match pin")
    if enable.get("groupDefinitionSha256") != pin.get("definitionSha256"):
        problems.append(
            "groupDtcEnable.groupDefinitionSha256 does not match pin")
    data = system.get("data")
    if not isinstance(data, dict) or not set(data) <= {"ecus", "pids"}:
        problems.append("group DTC system data must contain only 'ecus' and empty 'pids'")
        return problems
    ecus = data["ecus"]
    if not isinstance(ecus, list) or len(ecus) != 1:
        problems.append("group DTC system must carry exactly one ECU mapping")
        return problems
    diagnostics = ecus[0].get("diagnostics")
    if not isinstance(diagnostics, dict) or set(diagnostics) != {"identification",
                                                                 "faults"}:
        problems.append("group DTC ecu diagnostics must be identification+faults")
        return problems
    if diagnostics["identification"] != []:
        problems.append("group DTC system identification must stay unresolved")
    faults = diagnostics.get("faults")
    if not isinstance(faults, dict):
        problems.append("group DTC system faults missing")
        return problems
    if set(faults) - {"read", "layout", "sourceRecords"}:
        problems.append(
            "group DTC read-only slice must not ship a workflow or clear "
            f"({CLEAR_UNRESOLVED_REASON})")
    if enable.get("clearShipped") is not False or enable.get("clearReason") != CLEAR_UNRESOLVED_REASON:
        problems.append("group DTC system must record the unresolved clear reason")
    if data.get("pids"):
        problems.append("group DTC system pids must stay unresolved")
    actual_hash = definition_hash(faults)
    if actual_hash != pin.get("definitionHash"):
        problems.append(
            f"group definitionHash mismatch: system {actual_hash} "
            f"!= pin {pin.get('definitionHash')}")
    if enable.get("definitionHash") != actual_hash:
        problems.append("groupDtcEnable.definitionHash does not match the data")
    counts = system.get("counts", {})
    labels = faults.get("layout", {}).get("labels", [])
    if counts.get("dtcs", {}).get("converted") != len(labels):
        problems.append("dtcs counts disagree with the label list")
    if counts.get("pids", {}).get("converted") not in (0, None):
        problems.append("pids counts must stay unresolved")
    if counts.get("ecuInfo", {}).get("converted") not in (0, None):
        problems.append("ecuInfo counts must stay unresolved")
    if system.get("enabled") is not True or system.get("reasons") != []:
        problems.append("group DTC system must be enabled with no reasons")
    if system.get("categoryStatus", {}).get("dtcs") != "executable":
        problems.append("group DTC system needs executable dtcs category")
    if system.get("categoryStatus", {}).get("pids") != "unresolved" or \
            system.get("categoryStatus", {}).get("ecuInfo") != "unresolved":
        problems.append("group DTC system pids/ecuInfo must stay unresolved")
    for category in ("tests", "actuators", "coding", "specialFunctions"):
        if system.get("categoryStatus", {}).get(category) == "executable":
            problems.append(f"group DTC system must not mark write category {category} executable")
    try:
        validate_system(system)
    except Exception as error:
        problems.append(
            f"validate_system rejected: {type(error).__name__}: {error}")
    return problems


def qualification_report(qualifier, pin):
    """(markdown, json) evidence for every targeted group and every member."""
    groups = []
    totals = {"groups": 0, "members": 0, "enabledReady": 0, "enabled": 0,
              "pinnedPath": 0, "unresolved": 0}
    for key, target in TARGETS.items():
        group = qualifier.groups().get(key)
        entry = {
            "group": {"region": target["region"], "ecuFile": target["ecuFile"],
                      "function": target["function"],
                      "selector": target["selector"]},
            "representative": target["representative"],
            "expectedSystems": target["systems"],
            "members": [],
        }
        if group is not None:
            entry.update({
                "compiled": group["ok"],
                "compileReason": group.get("reason"),
                "definitionHash": group.get("definitionHash"),
                "definitionSha256": group.get("definitionSha256"),
                "pinMatched": group.get("pinMatched"),
                "pinReason": group.get("pinReason"),
            })
        for record in qualifier.records.get(key, []):
            member = {k: v for k, v in record.items()
                      if not k.startswith("_") and v is not None}
            entry["members"].append(member)
            totals["members"] += 1
            if record["status"] == "pinned-path":
                totals["pinnedPath"] += 1
            elif record["status"] in ("enabled-ready", "enabled"):
                totals["enabledReady"] += 1
            else:
                totals["unresolved"] += 1
            totals["enabled"] += record["status"] == "enabled"
        groups.append(entry)
        totals["groups"] += 1
    payload = {
        "kind": "group-dtc-enable-qualification",
        "sourceSha256": SOURCE_SHA256,
        "oracleProvenance": ORACLE_PROVENANCE,
        "pin": {"path": pin["path"],
                "pinnedGroups": sorted(
                    f"{k[0]}|{k[1]}|{k[2]}|{k[3]:#04x}" for k in pin["groups"])},
        "totals": totals,
        "groups": groups,
    }
    lines = ["# Stage 1d group DTC qualification", "",
             "Group-oracle evidence proves the compile group only. It is NOT "
             "transport, adapter or vehicle approval.", ""]
    lines.append(f"- Pinned groups: {len(pin['groups'])} "
                 f"({', '.join(payload['pin']['pinnedGroups']) or 'none'})")
    lines.append(f"- Groups: {totals['groups']}; members: {totals['members']}; "
                 f"qualified: {totals['enabledReady']}; "
                 f"enabled: {totals['enabled']}; "
                 f"unresolved: {totals['unresolved']}; "
                 f"pinned-path: {totals['pinnedPath']}")
    for entry in groups:
        g = entry["group"]
        lines.append("")
        lines.append(f"## {g['region']} {g['ecuFile']} function {g['function']} "
                     f"selector {g['selector']:#04x} "
                     f"({len(entry['members'])} members; "
                     f"expected {entry['expectedSystems']})")
        lines.append(f"- representative: {entry['representative']}; "
                     f"compiled: {entry.get('compiled')}; "
                     f"definitionHash: {entry.get('definitionHash')}; "
                     f"definitionSha256: {entry.get('definitionSha256')}")
        pin_line = f"- pin matched: {entry.get('pinMatched')}"
        if entry.get("pinReason"):
            pin_line += f" ({entry['pinReason']})"
        lines.append(pin_line)
        for member in entry["members"]:
            reason = member.get("reason")
            lines.append(f"- {member['system']}: {member['status']}"
                         + (f" - {reason}" if reason else ""))
    return "\n".join(lines) + "\n", json.dumps(payload, indent=2,
                                               sort_keys=True) + "\n"
