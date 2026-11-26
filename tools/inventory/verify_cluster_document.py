#!/usr/bin/env python3
"""Check the committed Inventory-6 summary against this SHA's report."""
import json
from pathlib import Path
import sys

path = Path(sys.argv[1])
state = json.loads((path / 'inventory6.json').read_text())
if len(state['profiles']) != len(state['representatives']):
    raise SystemExit('Inventory-6 representative profiles are incomplete')
report = (path / 'inventory6.md').read_text()
actual = report[report.index('## Documentation summary'):report.index('## Clusters at 0.8')].strip()
doc = Path('docs/brand-inventory.md').read_text()
expected = doc.split('<!-- INVENTORY6 START -->', 1)[1].split('<!-- INVENTORY6 END -->', 1)[0].strip()
if actual != expected:
    raise SystemExit('Inventory-6 documented summary does not match this SHA’s report')
print('Inventory-6 documented summary matches this SHA’s report.')
