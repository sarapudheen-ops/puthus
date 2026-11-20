import copy
import unittest

from reviewed_hierarchy import CHOICES, PREFIX, normalize
from toyota_hierarchy import normalize_source_vehicle


class CompleteManualHierarchyTest(unittest.TestCase):
    def record(self, path, region="EU", key=1, identity="MENU.BIN@01234567"):
        return {"id": identity, "region": region, "path": list(path), "sourceKey": key}

    def test_preserves_all_four_reviewed_innova_choices_and_ids(self):
        for tail, key in CHOICES.items():
            r = self.record(PREFIX + tail, key=key)
            self.assertEqual(normalize(r), normalize_source_vehicle(r))

    def test_all_regions_and_arbitrary_detail_depth_are_lossless_not_heuristic(self):
        roots = [("EU", "16PIN DLC (Europe and Other)"), ("NA", "16PIN DLC (North America)"), ("JP", "16PIN DLC (Japan)")]
        for region, root in roots:
            for mode in ("Others", "can bus_P5", "kwp(9600)"):
                for tail in ([], ["ZVG13"], ["2023", "2ZR-FXE", "w/ Smart Key", "Others", "RHD"]):
                    with self.subTest(region=region, mode=mode, tail=tail):
                        r = self.record([root, mode, "Exact model"] + tail, region)
                        v = normalize_source_vehicle(r); nodes = v["nodes"]
                        self.assertEqual(["make", "model", "generation", "variant"], [n["level"] for n in nodes])
                        self.assertEqual("Exact model", nodes[1]["name"])
                        self.assertEqual(tail[0] if tail else "Not specified in source", nodes[2]["name"])
                        self.assertIn(root, nodes[-1]["name"]); self.assertIn(mode, nodes[-1]["name"])
                        for name in tail[1:]: self.assertIn(name, nodes[-1]["name"])
                        for node in nodes:
                            self.assertIn(" / ".join(r["path"]), node["notes"])
                            self.assertIn(r["id"], node["notes"])
                            self.assertFalse(set(node) & {"yearFrom", "yearTo", "engine", "fuel", "transmission"})
                        self.assertEqual(v, normalize_source_vehicle(copy.deepcopy(r)))

    def test_regions_communication_menus_and_duplicate_labels_never_collapse_variants(self):
        base = self.record(["16PIN DLC (Europe and Other)", "Others", "Model", "Chassis", "Variant"])
        records = [base, {**base, "id": "MENU.BIN@01234568"},
                   {**base, "path": [base["path"][0], "can bus_01", *base["path"][2:]]},
                   {**base, "region": "JP", "path": ["16PIN DLC (Japan)", *base["path"][1:]]}]
        self.assertEqual(4, len({normalize_source_vehicle(r)["nodes"][-1]["id"] for r in records}))

    def test_nonvehicle_manual_tasks_are_not_made_up_vehicles(self):
        for r in (self.record(["Auto Scan"]), self.record(["16PIN DLC (Japan)", "Others"]),
                  self.record(["wrong region", "Others", "Model"]),
                  self.record(["16PIN DLC (Europe and Other)", "Others", ""])):
            self.assertIsNone(normalize_source_vehicle(r))
