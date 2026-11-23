#!/usr/bin/env python3
"""Fail-fast corpus check for Stage 1b parameterized DTC probe.

Exits non-zero with path/error if corpus or libraries cannot be opened.
Never records corpus-open failure as per-system reject.
"""
import sys
from pathlib import Path

def main():
    paths = [
        (".local/thinkdiag/TOYOTA.zip", "corpus archive"),
        (".local/thinkdiag/decoded", "decoded corpus directory"),
        (".local/thinkdiag/english", "english corpus directory"),
        (".local/thinkdiag/report/native-oracle.json", "native oracle manifest"),
        (".local/thinkdiag/vehicle-packs", "ecmect review directory")
    ]
    for p, desc in paths:
        path = Path(p)
        if not path.exists():
            print(f"FAIL-FAST: {desc} missing at {p}", file=sys.stderr)
            sys.exit(1)
        if path.is_file() and path.stat().st_size == 0:
            print(f"FAIL-FAST: {desc} empty at {p}", file=sys.stderr)
            sys.exit(1)
    print("FAIL-FAST: All corpus paths present and non-empty.")

if __name__ == "__main__":
    main()
