#!/usr/bin/env python3
"""Ensure committed inventory tables are exactly the ones generated for this SHA."""
import json
from pathlib import Path
import sys

report = Path(sys.argv[1])
doc = Path('docs/brand-inventory.md').read_text()
state = json.loads((report / 'brand-inventory.json').read_text())
text = (report / 'brand-inventory.md').read_text()
if not state['control']['passed'] or state['pending']:
    raise SystemExit('Cannot verify document: control failed or brand inventory is incomplete')
actual = text[text.index('## Summary'):text.index('## TOYOTA: decoded')].strip()
expected = doc.split('<!-- INVENTORY TABLES START -->', 1)[1].split('<!-- INVENTORY TABLES END -->', 1)[0].strip()
if actual != expected:
    raise SystemExit('Committed summary/ranking differ from this SHA’s CI report; regenerate them')
control = state['control']
if f"{control['parsed']}/{control['sample']}" not in doc:
    raise SystemExit('Committed Toyota control differs from this SHA’s CI report')
print('Committed summary, ranking and control count match this SHA’s CI report.')
