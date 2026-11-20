"""Strict source-record disposition primitives for Toyota coverage accounting.

These helpers do not infer a disposition from a row's difficulty: a caller must
supply evidence before classifying a record, and unknowns remain unresolved.
They are not yet the vehicle-pack schema or a replacement for bundle verification.
"""
from vehicle_system_validation import records, require

NOT_APPLICABLE_REASONS = frozenset({
    'flash_download',
    'write_outside_policy',
    'marker_padding_unclaimed_span',
    'non_diagnostic_menu_task',
})
DISPOSITIONS = frozenset({'converted', 'notApplicable', 'unresolved'})


def summarize_dispositions(items):
    """Count unique source identities, rejecting conflicting duplicate claims.

    Converted records must identify the executable definition that uses them;
    this function validates the claim's shape, not its reachability. The bundle
    verifier must independently establish reachability before publishing counts.
    """
    seen = {}
    for item in items:
        require(isinstance(item, dict), 'Disposition must be an object')
        require(item.keys() == {'id', 'disposition', 'reason', 'sourceRecords', 'definitionIds'},
                'Invalid disposition fields')
        refs = records(item, 'disposition')
        identity = item['id']
        require(isinstance(identity, str) and identity in refs, 'Disposition identity must be a source record')
        disposition = item['disposition']
        require(isinstance(disposition, str) and disposition in DISPOSITIONS, 'Unknown disposition')
        reason, definitions = item['reason'], item['definitionIds']
        require(isinstance(definitions, list) and all(isinstance(v, str) and v.strip() for v in definitions)
                and len(set(definitions)) == len(definitions), 'Invalid executable definition references')
        if disposition == 'notApplicable':
            require(isinstance(reason, str) and reason in NOT_APPLICABLE_REASONS and not definitions,
                    'Not-applicable record needs an allowed reason and no executable definition')
        elif disposition == 'unresolved':
            require(isinstance(reason, str) and bool(reason.strip()) and not definitions,
                    'Unresolved record needs a blocker and no executable definition')
        else:
            require(reason is None and bool(definitions), 'Converted record needs an executable definition reference')
        if identity in seen:
            require(seen[identity] == item, 'Conflicting dispositions for source record')
        else:
            seen[identity] = item
    result = dict.fromkeys(('converted', 'notApplicable', 'unresolved'), 0)
    for item in seen.values():
        result[item['disposition']] += 1
    return result
