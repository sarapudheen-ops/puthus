"""Conservative, exact source-path normalization. Not vehicle/ECU inference.

This module retains the original IDs for four independently traced MENU -> EU
SYSLIST paths. toyota_hierarchy supplies lossless regional source-tree grouping
for the remaining vehicle paths without inferring chassis generations. Do not
assign FEBA labels heuristically or relabel these TGN branches as AN40 diesel.
Names/years here come only from these literal source paths. Missing engine,
fuel, transmission and an open start date stay absent, not guessed.
"""
import hashlib
import re
import unicodedata

PREFIX = ("16PIN DLC (Europe and Other)", "Others", "Kijang Innova", "TGN", "MINI VAN")
CHOICES = {
    ("2009.07-2017.07", "w/ VSC (Vehicle Stability Control)"): 1538,
    ("2009.07-2017.07", "w/o VSC (Vehicle Stability Control)"): 1536,
    ("-2009.07", "w/ VSC (Vehicle Stability Control)"): 938,
    ("-2009.07", "w/o VSC (Vehicle Stability Control)"): 433,
}


def node_id(level, parent, name):
    name = "".join(c for c in unicodedata.normalize("NFD", name.strip()) if not unicodedata.combining(c))
    normalized = re.sub(r"\s+", " ", name.lower())
    return f"{level}-" + hashlib.sha256(f"{parent or ''}|{normalized}".encode()).hexdigest()[:20]


def normalize(record):
    path = tuple(record["path"])
    if record["region"] != "EU" or path[:len(PREFIX)] != PREFIX or len(path) != 7:
        return None
    expected = CHOICES.get(path[-2:])
    if expected is None:
        return None
    if record["sourceKey"] != expected:
        raise ValueError("Reviewed source path changed its SYSLIST task; re-review applicability")
    generation = "TGN · " + path[-2]
    variant = "MINI VAN · " + path[-1]
    nodes = []
    parent = None
    note = ("Exact recovered source branch, Europe and Other / Others. Date boundary is in months as shown in the name. "
            "Engine, fuel and transmission are not specified by this branch. Do not infer AN40 or diesel applicability. "
            "Source: " + record["id"])
    for level, name in (("make", "Toyota"), ("model", "Kijang Innova"), ("generation", generation), ("variant", variant)):
        identity = node_id(level, parent, name + ("|||" if level == "variant" else ""))
        node = {"id": identity, "parentId": parent, "level": level, "name": name, "data": {}, "notes": note}
        if level == "generation" and path[-2] == "2009.07-2017.07":
            node.update(yearFrom=2009, yearTo=2017)
        nodes.append(node)
        parent = identity
    return {"format": "bod3-vehicle-catalog", "formatVersion": 1, "id": "recovered-toyota-" + record["id"],
            "name": " / ".join(n["name"] for n in nodes), "version": 1, "nodes": nodes, "platforms": [], "library": {}}
