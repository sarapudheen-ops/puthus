#!/usr/bin/env python3
"""Verify the compact documented eligibility table against this run's output."""
from pathlib import Path
import json
import sys

report = Path(sys.argv[1])
state = json.loads((report / 'inventory5.json').read_text())
if len(state['rows']) != state['total'] or state['total'] != 33:
    raise SystemExit('Inventory-5 results are incomplete')
text = (report / 'inventory5.md').read_text()
actual = text[text.index('| brand | stems present'):text.index('\n\n## Ranking')].strip()
doc = Path('docs/brand-inventory.md').read_text()
expected = doc.split('<!-- INVENTORY5 START -->', 1)[1].split('<!-- INVENTORY5 END -->', 1)[0].strip()
if actual != expected:
    raise SystemExit('Documented Inventory-5 table differs from this SHA’s census report')
print('Documented Inventory-5 table matches this SHA’s census report.')
