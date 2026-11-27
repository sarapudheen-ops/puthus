#!/usr/bin/env python3
"""Check the committed Inventory-7 summary against this SHA's report.

Run from the repository root with the report directory as argv[1], mirroring
``verify_cluster_document.py``. It refuses to pass when the Toyota control
failed, when the name-agnostic classifier disagreed with the name-keyed
converter readers, or when any archive was not reached inside the time budget:
in those cases the published verdicts are not comparable with the committed
numbers, so a mismatch must not be reported as a documentation problem.
"""
import json
from pathlib import Path
import sys

path = Path(sys.argv[1])
state = json.loads((path / 'inventory7.json').read_text())
control, summary = state['control'], state['summary']
if not control['passed']:
    raise SystemExit('STOP: the Inventory-7 Toyota control failed, so no brand verdict is publishable')
if control['disagreementCount']:
    raise SystemExit('STOP: the name-agnostic classifier disagreed with the name-keyed converter readers '
                     f"{control['disagreementCount']} time(s)")
if summary['archives'] != len(state['brands']):
    raise SystemExit('Inventory-7 summary does not account for every archive it probed')
if state['pending']:
    raise SystemExit(f"Inventory-7 did not reach every archive: {', '.join(state['pending'])}")
report = (path / 'inventory7.md').read_text()
actual = report[report.index('## Documentation summary'):].strip()
doc = Path('docs/brand-inventory.md').read_text()
expected = doc.split('<!-- INVENTORY7 START -->', 1)[1].split('<!-- INVENTORY7 END -->', 1)[0].strip()
if actual != expected:
    raise SystemExit('Inventory-7 documented summary does not match this SHA’s report')
print('Inventory-7 documented summary matches this SHA’s report.')
