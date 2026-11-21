import unittest
from types import SimpleNamespace
from unittest.mock import patch

import toyota_native_oracle as oracle


class LifecycleEvidenceCompatibilityTest(unittest.TestCase):
    def test_evidence_none_and_empty_list_preserve_result_dict(self):
        sources = {f'FUNCDATA_{region}.BIN': b'' for region in ('EU', 'NA', 'JP')}
        sources['SYSTEMENTER.BIN'] = b''
        empty = lambda *a, **k: {}
        lifecycle = lambda *a, **k: {'sourceEntries': 0, 'vectors': {}}
        patches = [
            patch.object(oracle, 'FunctionBlocks', return_value=SimpleNamespace(rows={})),
            patch.object(oracle, 'compare_entries', empty),
            patch.object(oracle, 'compare_fast_kwp', empty),
            patch.object(oracle, 'compare_fast_kwp_lifecycle', lifecycle),
            patch.object(oracle, 'compare_frames', empty),
            patch.object(oracle, 'compare_function_readers', empty),
            patch.object(oracle, 'compare_regional_templates', empty),
            patch.object(oracle, 'compare_fault_decoder', empty),
            patch.object(oracle, 'compare_setup_predicates', empty),
            patch.object(oracle, 'compare_ecmect_query_selection', empty),
            patch.object(oracle, 'compare_dtc_read_reentry', empty),
            patch.object(oracle, 'compare_ecmect_detail_loading', empty),
            patch.object(oracle, 'compare_support_predicates', empty),
            patch.object(oracle, 'compare_ecu_programs', empty),
            patch.object(oracle, 'compare_calibration_14', empty),
            patch.object(oracle, 'compare_p5_decoder', empty),
            patch.object(oracle, 'compare_fault_labels', empty),
        ]
        with patches[0], patches[1], patches[2], patches[3], patches[4], patches[5], patches[6], patches[7], patches[8], patches[9], patches[10], patches[11], patches[12], patches[13], patches[14], patches[15], patches[16]:
            texts = {'TCODE_EN.GAG': ([],), 'TEXT_EN.GAG': ([],)}
            without = oracle.run({}, sources, text_libraries=texts, full_calc=False, lifecycle_evidence=None)
            with_empty = oracle.run({}, sources, text_libraries=texts, full_calc=False, lifecycle_evidence=[])
        self.assertEqual(without, with_empty)


if __name__ == '__main__':
    unittest.main()
