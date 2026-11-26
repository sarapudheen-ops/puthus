#!/usr/bin/env python3
"""Verify the documented inventory observations against this commit's CI report."""
import json
from pathlib import Path
import sys

report = Path(sys.argv[1])
doc = Path('docs/brand-inventory.md').read_text()
state = json.loads((report / 'brand-inventory.json').read_text())
text = (report / 'brand-inventory.md').read_text()
if not state['control']['passed'] or state['pending']:
    raise SystemExit('Cannot verify document: control failed or brand inventory is incomplete')
actual = text[text.index('## Inventory-4:'):text.index('## Inventory-3:')].strip()
expected = doc.split('<!-- INVENTORY4 START -->', 1)[1].split('<!-- INVENTORY4 END -->', 1)[0].strip()
if actual != expected:
    raise SystemExit('Committed decoded observations, errors or stem census differ from this SHA’s CI report')
control = state['control']
if f"{control['parsed']}/{control['sample']}" not in doc:
    raise SystemExit('Committed Toyota control differs from this SHA’s CI report')
print('Committed decoded observations, errors, stems and control match this SHA’s CI report.')
