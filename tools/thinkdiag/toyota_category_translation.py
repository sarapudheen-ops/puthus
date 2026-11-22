"""No-write classifier for source-bound Toyota category compiler candidates.

This module never creates pack objects, interprets raw source as commands, or
approves a category from counts alone. `oracle_comparisons` must carry exact
region/function/full-selector identity, a successful same-row result and a
SHA-256 evidence digest for the category before any candidate is executable.
"""
import re
from copy import deepcopy

from convert_vehicle_packs import category_definitions

CATEGORY_FIELDS = {
    "pids": ("pids", "pids"),
    "dtcs": ("dtcs", "dtcs"),
    "ecuInfo": ("ecus", "ecuInfo"),
    "actuators": ("actuators", "tests"),
    "coding": ("coding", "coding"),
    "specialFunctions": ("specialFunctions", "specialFunctions"),
}


def translate_categories(region, function, selector, system_row, *, oracle_comparisons=None):
    """Classify one exact system row and return candidate definitions, without mutation."""
    if region not in ("EU", "NA", "JP") or type(function) is not int or type(selector) is not int:
        raise ValueError("exact_region_function_selector_required")
    if not isinstance(system_row, dict):
        raise TypeError("system_row_must_be_object")
    row_region = system_row.get("region", region)
    if (row_region, system_row.get("functionKey"), system_row.get("entrySelector")) != (region, function, selector):
        raise ValueError("system_identity_mismatch")
    data = system_row.get("data", {})
    if not isinstance(data, dict):
        raise TypeError("system_data_must_be_object")
    comparisons = oracle_comparisons or {}
    if not isinstance(comparisons, dict):
        raise TypeError("oracle_comparisons_must_be_object")

    statuses, reasons, definitions_by_category = {}, {}, {}
    for category, (field, count_key) in CATEGORY_FIELDS.items():
        definitions = category_definitions(data, category, field)
        definitions_by_category[category] = deepcopy(definitions)
        if not definitions:
            statuses[category] = "rejected"
            reasons[category] = "no_translated_definitions"
            continue
        evidence = comparisons.get(category)
        valid = (isinstance(evidence, dict)
            and evidence.get("region") == region
            and evidence.get("function") == function
            and evidence.get("selector") == selector
            and evidence.get("category") == category
            and evidence.get("conclusion") == "success"
            and isinstance(evidence.get("evidenceSha256"), str)
            and re.fullmatch(r"[0-9a-f]{64}", evidence["evidenceSha256"]) is not None)
        if valid:
            statuses[category] = "executable"
        else:
            statuses[category] = "rejected"
            reasons[category] = "oracle_record_comparison_missing_or_mismatched"
    return {"categories": statuses, "reasons": reasons, "definitions": definitions_by_category}
