"""Lossless manual hierarchy for every regional vehicle branch in MENU.BIN.

The native manual tree is Region -> communication menu -> model -> detail
branches, NOT a database of inferred chassis generations, fuels or gearboxes.
We present its first detail as a *source generation/group* and preserve ALL
remaining details plus region/menu in the variant. A missing detail is labelled
as absent, not filled with another vehicle's specification. Exact source-node
IDs distinguish even identical labels; no VIN or saved profile is an input.

The four previously reviewed Innova paths keep their existing IDs unchanged.
"""
from reviewed_hierarchy import node_id, normalize as reviewed_normalize

REGIONAL_ROOTS = {
    "EU": "16PIN DLC (Europe and Other)",
    "NA": "16PIN DLC (North America)",
    "JP": "16PIN DLC (Japan)",
}


def normalize_source_vehicle(record):
    reviewed = reviewed_normalize(record)
    if reviewed is not None:
        return reviewed
    path = record.get("path")
    region = record.get("region")
    if (region not in REGIONAL_ROOTS or not isinstance(path, list) or len(path) < 3
            or path[0] != REGIONAL_ROOTS[region] or any(not isinstance(n, str) or not n.strip() for n in path)):
        return None  # Non-vehicle manual tasks are retained in the source tree.
    identity = record.get("id")
    if not isinstance(identity, str) or not identity.startswith("MENU.BIN@"):
        raise ValueError("Manual vehicle needs its exact source-node ID")
    # Grouping is deliberately positional in the source tree. We DO NOT classify
    # an engine code, a year, 'Others', or a steering-side label as a known chassis.
    model = path[2]
    generation = path[3] if len(path) > 3 else "Not specified in source"
    details = path[4:]
    variant = " · ".join(details + [path[0], path[1]])
    note = ("Exact manual source selection: " + " / ".join(path) + ". Source: " + identity +
            ". Generation is the first source detail/group, not an inferred chassis generation. "
            "No engine, fuel, transmission or model-year range is inferred. "
            "The region, communication-menu branch and every source detail remain part of this variant's scope.")
    nodes, parent = [], None
    for level, name in (("make", "Toyota"), ("model", model), ("generation", generation), ("variant", variant)):
        key = name if level != "variant" else name + "|source:" + identity + "|||"
        current = node_id(level, parent, key)
        nodes.append({"id": current, "parentId": parent, "level": level, "name": name, "notes": note, "data": {}})
        parent = current
    return {"format": "bod3-vehicle-catalog", "formatVersion": 1, "id": "source-toyota-" + identity,
            "name": " / ".join(n["name"] for n in nodes), "version": 1,
            "nodes": nodes, "platforms": [], "library": {}}
