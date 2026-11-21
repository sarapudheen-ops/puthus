#!/usr/bin/env python3
"""Produce a bounded observational Markdown report for generated Toyota packs."""
import argparse
import json
import random
from collections import Counter
from pathlib import Path

from vehicle_bundle import read_object

CATEGORIES = ("pids", "dtcs", "tests", "coding", "specialFunctions", "ecuInfo")
MAX_REPORT = 150_000


def report(directory: Path) -> str:
    index = json.loads((directory / "index.json").read_text())
    container = directory / index["container"]
    objects, refs = {}, {}

    def visit(ref):
        key = ref["sha256"]
        if key in objects:
            return objects[key]
        value = read_object(container, ref)
        objects[key] = value
        def walk(v):
            if isinstance(v, dict):
                if set(v) == {"offset", "bytes", "jsonBytes", "sha256"}:
                    refs[v["sha256"]] = v
                    visit(v)
                else:
                    for child in v.values(): walk(child)
            elif isinstance(v, list):
                for child in v: walk(child)
        walk(value)
        return value

    for key in ("root", "catalogRoot", "sourceInventory"):
        if index.get(key): visit(index[key])
    for ref in list(refs.values()): visit(ref)
    kinds = Counter(o.get("kind", "<missing>") for o in objects.values())
    systems = [o for o in objects.values() if o.get("kind") == "vehicle-system"]
    variants = [o for o in objects.values() if o.get("kind") == "converted-vehicle"]
    # Catalog hierarchy documents carry the level and children; object inventory is deduplicated.
    levels = Counter(o.get("level") for o in objects.values() if o.get("kind") == "catalog-level")
    makes = levels.get("make", 0)
    models = levels.get("model", 0)
    generations = levels.get("generation", 0)
    enabled = [s for s in systems if s.get("enabled") is True]
    disabled = [s for s in systems if s.get("enabled") is False]
    reasons = Counter(r for s in systems for r in s.get("reasons", []))
    reason_example = {}
    for s in systems:
        for reason in s.get("reasons", []): reason_example.setdefault(reason, s.get("id", "?"))
    lines = ["# Toyota generated pack report", "", "## Index", "", f"- Keys: `{', '.join(sorted(index))}`",
             f"- Container bytes: {index.get('containerBytes', 'unknown')}", f"- Unique referenced objects: {len(objects)}", "",
             "## Catalog counts", "", f"- Makes: {makes}; models: {models}; generations: {generations}; variants: {len(variants)}",
             f"- Systems: {len(systems)} total / {len(enabled)} enabled / {len(disabled)} disabled", "",
             "## Disabled/unresolved reasons (top 40)", "", "| Reason | Systems | Example system ID |", "|---|---:|---|"]
    lines += [f"| `{r}` | {n} | `{reason_example.get(r, '?')}` |" for r, n in reasons.most_common(40)]
    lines += ["", "## Feature counts", "", "| Category | Converted | Unresolved | Systems with converted entries |", "|---|---:|---:|---:|"]
    for cat in CATEGORIES:
        converted = sum((s.get("counts", {}).get(cat, {}).get("converted") or 0) for s in systems)
        unresolved_values = [s.get("counts", {}).get(cat, {}).get("unresolved") for s in systems]
        unresolved = "unknown" if any(v is None for v in unresolved_values) else str(sum(unresolved_values))
        present = sum(bool(s.get("counts", {}).get(cat, {}).get("converted", 0)) for s in systems)
        lines.append(f"| {cat} | {converted} | {unresolved} | {present} |")
    lines += ["", "## Object samples (JSON, at most 40 lines each)", ""]
    by_kind = {}
    for obj in objects.values(): by_kind.setdefault(obj.get("kind", "<missing>"), obj)
    def sample(title, obj):
        lines.extend([f"### {title}", "", "```json"])
        text = json.dumps(obj, ensure_ascii=False, indent=2).splitlines()[:40]
        lines.extend(text); lines.extend(["```", ""])
    for kind, obj in sorted(by_kind.items()): sample(f"kind: {kind}", obj)
    if enabled: sample("Enabled system", enabled[0])
    if disabled: sample("Disabled system", disabled[0])
    for cat in CATEGORIES:
        row = next((row for o in objects.values() if o.get("kind") == "disabled-items"
                    for row in o.get("items", []) if row.get("kind") == cat), None)
        if row: sample(f"Unresolved {cat} row", row)
    rng = random.Random(7319)
    other = [s for s in disabled if s.get("id") != (enabled[0].get("id") if enabled else None)]
    for i, s in enumerate(rng.sample(other, min(5, len(other))), 1):
        sample(f"Disabled system sample {i}: ECU/link/protocol fields", {k: s.get(k) for k in
            ("id", "ecuSource", "functionSource", "entrySource", "transportLabel", "functionKey", "entrySelector", "enabled", "reasons")})
    result = "\n".join(lines) + "\n"
    if len(result.encode()) > MAX_REPORT:
        result = result.encode()[:MAX_REPORT].decode("utf-8", errors="ignore") + "\n\n[Report truncated at 150 KB]\n"
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    text = report(args.directory)
    if args.output: args.output.write_text(text)
    print(text, end="")

if __name__ == "__main__": main()
