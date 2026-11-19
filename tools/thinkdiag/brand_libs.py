#!/usr/bin/env python3
"""Compare native library file hashes across vendor brand archives.

Read-only: opens each ZIP, hashes member names ending in `.so`, and reports which
libraries are byte-identical across brands. This answers a single question - whether the
decoder's key material (read statically from libDEVICEID.so / libSTD.so) can be shared
across brands or must be re-derived per brand. Nothing is loaded or executed.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys
import zipfile

# Libraries the Toyota decoder reads constants from, plus the ones that vary per brand.
TRACKED = ("libDEVICEID.so", "libSTD.so", "libCOMM_ABSTRACT_LAYER.so")


def library_hashes(archive: pathlib.Path) -> dict[str, str]:
    """Map base library name -> SHA-256. First occurrence wins; duplicates are noted."""
    found: dict[str, str] = {}
    duplicates: dict[str, str] = {}
    with zipfile.ZipFile(archive) as zf:
        for info in zf.infolist():
            if info.is_dir():
                continue
            name = pathlib.PurePosixPath(info.filename.replace("\\", "/")).name
            if not name.lower().endswith(".so"):
                continue
            with zf.open(info) as handle:
                digest = hashlib.sha256()
                for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                    digest.update(chunk)
            hexdigest = digest.hexdigest()
            if name in found and found[name] != hexdigest:
                duplicates[name] = hexdigest
            else:
                found.setdefault(name, hexdigest)
    for name, hexdigest in duplicates.items():
        found[f"{name}#dup"] = hexdigest
    return found


def compare(archives: list[pathlib.Path]) -> dict:
    per_brand = {}
    for archive in archives:
        brand = archive.stem.upper()
        try:
            per_brand[brand] = library_hashes(archive)
        except (zipfile.BadZipFile, OSError) as error:
            per_brand[brand] = {"#error": str(error)}

    names = sorted({name for libs in per_brand.values() for name in libs})
    matrix = []
    for name in names:
        variants = {}
        for brand, libs in per_brand.items():
            if name in libs:
                variants.setdefault(libs[name], []).append(brand)
        matrix.append({
            "library": name,
            "hash": next(iter(variants), ""),
            "identical": len(variants) == 1,
            "variantCount": len(variants),
            "brands": {h: bs for h, bs in variants.items()},
        })
    return {"brands": per_brand, "libraries": matrix}


def markdown(result: dict) -> str:
    brands = sorted(result["brands"])
    lines = ["| library | variants | identical across brands | brands |",
             "| --- | ---: | :---: | --- |"]
    for row in result["libraries"]:
        present = sorted({b for bs in row["brands"].values() for b in bs})
        lines.append(
            f"| `{row['library']}` | {row['variantCount']} | "
            f"{'YES' if row['identical'] else 'NO'} | {', '.join(present) or '-'} |")
    short = [b for b in brands if b in TRACKED or b.startswith("lib")]
    lines.append("")
    lines.append(f"Brands compared: {', '.join(brands)} (tracked: {', '.join(short) or 'none'})")
    # Spell out the hashes so a reviewer can verify byte identity directly.
    lines.append("")
    lines.append("| brand | `libDEVICEID.so` | `libSTD.so` |")
    lines.append("| --- | --- | --- |")
    for brand in brands:
        libs = result["brands"][brand]
        device = libs.get("libDEVICEID.so", "absent")
        std = libs.get("libSTD.so", "absent")
        lines.append(f"| {brand} | `{device[:16]}` | `{std[:16]}` |")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=pathlib.Path, help="Directory of brand .zip files")
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args(argv)
    archives = sorted(args.directory.glob("*.zip"))
    if not archives:
        print(f"No .zip files in {args.directory}", file=sys.stderr)
        return 2
    result = compare(archives)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / "library-hashes.json").write_text(
            json.dumps(result, indent=2) + "\n", encoding="utf-8")
        (args.output / "library-hashes.md").write_text(markdown(result), encoding="utf-8")
    print(markdown(result), end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
