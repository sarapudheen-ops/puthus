#!/usr/bin/env python3
"""Check the committed Inventory-8 summary against this SHA's report.

Run from the repository root with the report directory as argv[1], mirroring
``verify_family_document.py``. It refuses to compare at all when a control
failed, when an archive was not reached, or when the summary does not account for
every pair it measured: in those cases the published verdicts are unvalidated, so
a mismatch is a measurement problem rather than a documentation problem.
"""
import json
from pathlib import Path
import sys

path = Path(sys.argv[1])
state = json.loads((path / 'inventory8.json').read_text())
control, summary = state['control'], state['summary']
if not control['passed']:
    failed = [row['name'] for row in control['checks'] if not row['passed']]
    raise SystemExit('STOP: the Inventory-8 controls failed, so its measurements are unvalidated: '
                     + '; '.join(failed))
if summary['targets'] != len(state['targets']):
    raise SystemExit('Inventory-8 summary does not account for every pair it measured')
if summary['meaningsEstablished']:
    raise SystemExit('STOP: Inventory-8 reported established field meanings, which it may not do; '
                     'this document and the gate both need reviewing before anything is published')
if state['pending']:
    raise SystemExit(f"Inventory-8 did not reach every pair: {', '.join(state['pending'])}")
report = (path / 'inventory8.md').read_text()
actual = report[report.index('## Documentation summary'):].strip()
doc = Path('docs/brand-inventory.md').read_text()
expected = doc.split('<!-- INVENTORY8 START -->', 1)[1].split('<!-- INVENTORY8 END -->', 1)[0].strip()
if actual != expected:
    raise SystemExit('Inventory-8 documented summary does not match this SHA’s report')
print('Inventory-8 documented summary matches this SHA’s report.')
