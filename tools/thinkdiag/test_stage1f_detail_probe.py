import unittest
from unittest.mock import patch

from stage1f_detail_probe import classify, group_rows, unconditional_native, report


def row(detail=7, mode=0, frame=0, condition=0):
    return {'detailId': detail, 'supportMode': mode, 'frameId': frame,
            'commandCondition': condition, 'expectedByte': 0, 'minimumLength': 0}


class FakeNative:
    def __init__(self, details): self.details = details
    def allocate(self, length, raw=None): return 1
    def write(self, *args): pass
    def bind(self, *args, **kwargs): pass
    def call(self, *args): return 0
    def read(self, address, length):
        if length == 1: return bytes([len(self.details)])
        return b''.join(int(x).to_bytes(2, 'little') for x in self.details)


class Stage1fTests(unittest.TestCase):
    def test_classes_are_by_row_count(self):
        self.assertEqual(['zero rows','exactly one row','several rows'], [classify(n) for n in (0,1,2)])

    def test_group_key_preserves_region_path_and_selector(self):
        rows = [{'region':'EU','ecuFile':'Ecu_Data_1.BIN','functionKey':1,'entrySelector':17},
                {'region':'EU','ecuFile':'EU/Ecu_Data_1.BIN','functionKey':1,'entrySelector':17},
                {'region':'EU','ecuFile':'Ecu_Data_1.BIN','functionKey':1,'entrySelector':81}]
        self.assertEqual([1,2], sorted(map(len, group_rows(rows).values())))

    def test_conditional_support_not_guessed(self):
        result = unconditional_native({}, {}, 'EU', 1, [row(mode=1, frame=2),row(8)])
        self.assertEqual('not replayable', result['verdict'])
        self.assertIn('ECU-dependent', result['reason'])
        result = unconditional_native({}, {}, 'EU', 1, [row(condition=1),row(8)])
        self.assertIn('CmdCheckSupport', result['reason'])

    def test_native_list_is_not_forced_to_one(self):
        sources={'EU/Toyota_bin_1B.BIN':b'1', 'EU/Toyota_bin_1C.BIN':b'2'}
        with patch('stage1f_detail_probe._sandbox', return_value=FakeNative([7,8])), \
             patch('stage1f_detail_probe.default_add_info_rows', return_value=[]):
            result = unconditional_native({}, sources, 'EU', 1, [row(7),row(8)])
        self.assertEqual('disagree', result['verdict'])
        self.assertEqual([7,8], result['nativeDetailIds'])

    def test_native_mismatch_is_not_accepted(self):
        sources={'EU/Toyota_bin_1B.BIN':b'1', 'EU/Toyota_bin_1C.BIN':b'2'}
        with patch('stage1f_detail_probe._sandbox', return_value=FakeNative([8,7])), \
             patch('stage1f_detail_probe.default_add_info_rows', return_value=[]):
            result = unconditional_native({}, sources, 'EU', 1, [row(7),row(8)])
        self.assertEqual('disagree', result['verdict'])
        self.assertIn('differs', result['reason'])

    def test_report_counts_and_literal_reason(self):
        g={'group':['EU/a',1,17], 'systems':3,'candidateRowsPerSystem':0,'class':'zero rows', 'oracle':None}
        data={'headSha':'synthetic','groups':[g],'container':{'bytes':10,'under480MiB':True},
              'newEnabledSystems':0,'newEnabledDtcs':0,'rankedRejects':[]}
        text=report(data)
        self.assertIn('| zero rows | 1 | 3 |', text)
        self.assertIn('no several-row candidate', text)

if __name__ == '__main__': unittest.main()
