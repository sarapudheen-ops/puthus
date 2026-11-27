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

class Stage1fAddendumTests(unittest.TestCase):
    def test_control_population_and_shared_join(self):
        from stage1f_detail_probe import controls, CONTROL_SYSTEM
        from toyota_innova_2007_reads import SYSTEM
        def r(id, region='EU', enabled=False):
            return {'id': id, 'region': region, 'entrySelector': 17,
                    'groupDtcStatus': {'status': 'enabled'} if enabled else None}
        ledger = [r(CONTROL_SYSTEM), r(SYSTEM)]
        ledger += [r('eu'+str(i), enabled=True) for i in range(78)]
        ledger += [r('jp'+str(i), 'JP', True) for i in range(30)]
        with patch('stage1f_detail_probe.candidate_rows', return_value=[{}, {}]) as lookup:
            found = controls({}, None, ledger)
        self.assertEqual([1,1,78,30], [v['systems'] for v in found.values()])
        self.assertEqual(110, lookup.call_count)
        self.assertEqual({2:78}, found['Stage 1d EU 0x11']['rowsHistogram'])

    def test_pinned_object_comparison_uses_real_packed_bytes(self):
        import tempfile
        from pathlib import Path
        from vehicle_bundle import BundleWriter
        from stage1f_detail_probe import compare_pinned_objects, CONTROL_SYSTEM
        from toyota_innova_2007_reads import SYSTEM
        def build(root, changed=False):
            w = BundleWriter(root)
            def system_leaf(identity, changed):
                obj = w.add({'id': identity, 'value': int(changed)})
                manual = w.add({'systems': [{'id': identity, 'object': obj}]})
                return w.add({'systemsSource': manual})
            e = system_leaf(CONTROL_SYSTEM, changed)
            i = system_leaf(SYSTEM, False)
            def page(name, ref): return w.add({'children': [{'name': name, 'object': ref}]})
            e = page('MINI VAN · w/ VSC (Vehicle Stability Control)', e)
            i = page('MINI VAN · w/o VSC (Vehicle Stability Control)', i)
            generations = w.add({'children': [{'name': 'TGN · 2009.07-2017.07', 'object': e},
                                               {'name': 'TGN · -2009.07', 'object': i}]})
            catalog = page('Toyota', page('Kijang Innova', generations))
            w.finish(catalog, {}, catalog_root=catalog)
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)/'base'; current = Path(tmp)/'current'
            build(base); build(current, changed=True)
            result = compare_pinned_objects(current, base)
            self.assertFalse(result['ECMECT']['byteIdentical'])
            self.assertTrue(result['Innova']['byteIdentical'])
