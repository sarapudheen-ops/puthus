"""No-write Stage 1 category translation using only existing verified compilers.

Mode A is deliberately pinned to the existing ECMECT compiler artifact. Results
are translation status, not oracle approval or enablement. Candidate-parse is a
separate, non-enabling structural path in the CI probe.
"""
from copy import deepcopy

from convert_vehicle_packs import category_definitions
from toyota_ecmect_pack import MANDATORY_SYSTEM, MANDATORY_ECU, data_for
from toyota_runtime_compile import verify_source_read_review
from vehicle_bundle import canonical

CATEGORIES = ("pids", "dtcs", "ecuInfo", "actuators", "coding", "specialFunctions")
FIELDS = {"pids": "pids", "dtcs": "ecus", "ecuInfo": "ecus", "actuators": "actuators", "coding": "coding", "specialFunctions": "specialFunctions"}


def normalize_ecu_file(region, filename):
    """Convert the systems.jsonl.gz bare text-index name to its exact region path."""
    if not isinstance(filename, str) or not filename:
        return filename
    return filename if "/" in filename else f"{region}/{filename}"


def translate_categories(region, function, selector, system_row, *, ecmect_review=None,
                         reference_data=None):
    """Translate one exact source system without mutation or IO."""
    if region not in ("EU", "NA", "JP") or type(function) is not int or type(selector) is not int:
        raise ValueError("exact_region_function_selector_required")
    if not isinstance(system_row, dict):
        raise TypeError("system_row_must_be_object")
    if (system_row.get("region"), system_row.get("functionKey"), system_row.get("entrySelector")) != (region, function, selector):
        raise ValueError("system_identity_mismatch")
    row_file = normalize_ecu_file(region, system_row.get("ecuFile"))

    exact_ecmect = (region, function, selector, system_row.get("id"), row_file) == (
        "EU", 61, 0x11, MANDATORY_SYSTEM, MANDATORY_ECU)
    translated = {}
    statuses, reasons, counts = {}, {}, {}
    if exact_ecmect and ecmect_review is not None:
        verify_source_read_review(ecmect_review)
        data = data_for(ecmect_review)
        for cat in CATEGORIES:
            defs = category_definitions(data, cat, FIELDS[cat])
            translated[cat] = deepcopy(defs)
            counts[cat] = len(defs)
            if defs:
                statuses[cat] = "executable"
            else:
                statuses[cat] = "rejected"
                reasons[cat] = "ecmect_compiler_returned_no_definitions"
    else:
        for cat in CATEGORIES:
            translated[cat] = []
            counts[cat] = 0
            statuses[cat] = "rejected"
            if cat in ("pids", "dtcs", "ecuInfo"):
                dep = {"pids": "mandatory_system_and_live_read_review", "dtcs": "mandatory_system_and_p3p4_workflow",
                       "ecuInfo": "mandatory_system_and_ecu_identification"}[cat]
                reasons[cat] = f"ecmect_specific:{dep}"
            else:
                reasons[cat] = f"no_generic_translator:{cat}"

    byte_match = None
    if reference_data is not None and exact_ecmect and ecmect_review is not None:
        byte_match = all(canonical(category_definitions(data, c, FIELDS[c])) ==
                         canonical(category_definitions(reference_data, c, FIELDS[c]))
                         for c in ("pids", "dtcs", "ecuInfo"))
    return {"categories": statuses, "reasons": reasons, "counts": counts,
            "definitions": translated, "ecmectControlByteIdentical": byte_match,
            "mode": "A-strict-pinned", "oracleApproved": False}
