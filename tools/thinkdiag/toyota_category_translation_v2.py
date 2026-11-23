"""Parameterized P3/P4 DTC translation for one exact source system row (Stage 1b).

This module reuses the existing, already-tested source compiler
`toyota_runtime_compile.compile_p3p4_diagnostics` (`toyota_runtime_compile.py:28`),
which compiles the complete default P3/P4 DTC caller, labels, clear and
dispatch-14 identity for the 2007 Innova review. The same function accepts any
exact regional SYSLIST row, so Stage 1b can attempt it for a *group
representative* row instead of only the pinned one.

Nothing here touches the pinned ECMECT packet path (`toyota_ecmect_pack.py`);
that path stays byte-identical at 1,414 DTCs.

No oracle claim is made: `faultDecoderComparison` / `p5DecoderComparison`
(`toyota_native_oracle.py:609`, `:726`) are decoder-level synthetic-vector
comparisons and are not keyed by (Ecu_Data file, function, selector). A
successful compile therefore stays a **candidate** translation with
`oracleStatus = 'not_established:decoder_level_oracle_only'`; nothing is enabled.
"""
from toyota_runtime_compile import compile_p3p4_diagnostics
from toyota_category_translation import normalize_ecu_file

CATEGORIES = ('pids', 'dtcs', 'ecuInfo', 'actuators', 'coding', 'specialFunctions')
NO_ORACLE = 'not_established:decoder_level_oracle_only'


def parameterized_dtc_group(sources, libraries, *, region, system_id, system_bytes, native_transport):
    """Compile P3/P4 DTC definitions for one exact source system row.

    `sources` is the verified decoded-source mapping that
    `convert_vehicle_packs.load_sources` produces (or any mapping returning the
    same checked bytes). `libraries` needs the decoded English tables
    `TCODE_EN.GAG` and `TEXT_EN.GAG`.

    Returns a literal outcome. Any compiler refusal becomes a reject reason with
    the exception class and message, never a definition and never an enablement.
    """
    if region not in ('EU', 'NA', 'JP'):
        raise ValueError('Exact regional system row required')
    try:
        diagnostics = compile_p3p4_diagnostics(
            sources, libraries, region=region, system_id=system_id,
            system_bytes=system_bytes, native_transport=native_transport)
    except Exception as error:
        return {'executable': False, 'rejectReason': f'{type(error).__name__}:{error}',
                'dtcCount': 0, 'readRequest': None, 'clearRequest': None,
                'workflowQueries': 0, 'identificationCount': 0,
                'oracleStatus': NO_ORACLE, 'definition': None}
    faults = diagnostics.get('faults') if isinstance(diagnostics, dict) else None
    if not isinstance(faults, dict):
        return {'executable': False, 'rejectReason': 'compiled_no_fault_section',
                'dtcCount': 0, 'readRequest': None, 'clearRequest': None,
                'workflowQueries': 0, 'identificationCount': 0,
                'oracleStatus': NO_ORACLE, 'definition': None}
    layout = faults.get('layout') or {}
    labels = layout.get('labels') or []
    read = faults.get('read') or {}
    if not labels or not read.get('request'):
        return {'executable': False, 'rejectReason': 'compiled_no_dtc_labels_or_read',
                'dtcCount': len(labels), 'readRequest': None, 'clearRequest': None,
                'workflowQueries': 0, 'identificationCount': 0,
                'oracleStatus': NO_ORACLE, 'definition': None}
    clear = faults.get('clear') or {}
    workflow = faults.get('workflow') or {}
    return {'executable': True, 'rejectReason': None, 'dtcCount': len(labels),
            'readRequest': read.get('request'), 'clearRequest': clear.get('request'),
            'workflowQueries': len(workflow.get('queries') or []),
            'identificationCount': len(diagnostics.get('identification') or []),
            'oracleStatus': NO_ORACLE, 'definition': diagnostics}
