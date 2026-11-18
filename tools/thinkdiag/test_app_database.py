import sqlite3
import struct
import unittest

from build_app_database import create_database, insert_decoded, insert_english, insert_menu, validate_database
from test_ggp import FORMAT_XOR, gag_fixture


def table(tag, count, body):
    return struct.pack("<HII", tag, count, len(body)) + body


class AppDatabaseTest(unittest.TestCase):
    def setUp(self):
        self.db = create_database(":memory:")

    def tearDown(self):
        self.db.close()

    def test_hex_ecu_schemas_become_paged_tables_not_executable_definitions(self):
        body = b"abcdefgh"
        data = struct.pack("<IBI", 1, 23, 9) + table(62, 2, body)
        insert_decoded(self.db, "EU/Ecu_Data_00ab.BIN", data, {})
        self.assertEqual((23, 62, 2, 4, 19), self.db.execute(
            "SELECT slot,tag,row_count,stride,source_offset FROM data_table").fetchone())
        self.assertEqual(b"efgh", self.db.execute("SELECT substr(payload,source_offset+5,4) FROM data_table JOIN source_file ON source_file.name=data_table.file").fetchone()[0])
        self.assertEqual(data, self.db.execute("SELECT payload FROM source_file").fetchone()[0])
        self.assertEqual("ecu-sections", self.db.execute("SELECT status FROM source_file").fetchone()[0])

    def test_all_text_records_are_lossless_including_nuls_and_invalid_utf8(self):
        records = {b"\x00\x00\x00\x01": b"name\0value\0\0", b"\x00\x00\x00\x02": b"\xff\xfe\0"}
        insert_english(self.db, "EXPRESS.GAG", gag_fixture(records), FORMAT_XOR)
        found = self.db.execute("SELECT id,value,raw FROM text_record ORDER BY id").fetchall()
        self.assertEqual(("00000001", "name\0value\0\0", b""), found[0])
        self.assertEqual(("00000002", None, b"\xff\xfe\0"), found[1])
        self.assertEqual((2, 1), self.db.execute("SELECT records,non_utf8 FROM text_library").fetchone())

    def test_unknown_payload_is_retained_with_an_explicit_issue(self):
        insert_decoded(self.db, "SPECFUNC.BIN", b"\0opaque\xff", {})
        status, issue, payload = self.db.execute("SELECT status,issue,payload FROM source_file").fetchone()
        self.assertEqual("opaque", status)
        self.assertIn("not an executable", issue)
        self.assertEqual(b"\0opaque\xff", payload)

    def test_command_shortfall_is_audited_not_padded_and_bad_frames_fail_validation(self):
        body = struct.pack("<IBBIBB", 0, 2, 0, 2, 2, 0) + b"\x21\x01\x61\x01"
        insert_decoded(self.db, "EU/Toyota_bin_00.BIN", (table(0, 2, body))[:-2], {})
        rows = self.db.execute("SELECT id,bytes_hex,error FROM command ORDER BY id").fetchall()
        self.assertEqual((1, "21 01", None), rows[0])
        self.assertIsNone(rows[1][1])
        self.assertIsNotNone(rows[1][2])
        insert_decoded(self.db, "EU/Toyota_bin_11.BIN", table(17, 1, struct.pack("<4H", 17, 10001, 0, 0)), {})
        self.assertEqual(1, validate_database(self.db)["invalidCommands"])
        self.db.execute("UPDATE frame SET ref2=10002")
        with self.assertRaisesRegex(ValueError, "invalid command"):
            validate_database(self.db)
        self.db.execute("UPDATE frame SET ref2=10003")
        with self.assertRaisesRegex(ValueError, "does not resolve"):
            validate_database(self.db)

    def test_function_frame_links_are_checked_not_invented(self):
        insert_decoded(self.db, "EU/Toyota_bin_12.BIN", table(274, 1, struct.pack("<8H", 1, 2, 3, 99, 0, 0, 0, 0)), {})
        with self.assertRaisesRegex(ValueError, "function/frame"):
            validate_database(self.db)
        self.db.execute("UPDATE function_frame SET selector8=1")
        self.assertEqual(1, validate_database(self.db)["function_frame"])

    def test_system_labels_stay_in_their_correct_namespace(self):
        record = bytearray(37)
        for pos, value in [(0, 1), (4, 2), (8, 3), (23, 4), (28, 5)]:
            struct.pack_into("<I", record, pos, value)
        source = struct.pack("<IIIH", 861, 12, 0xFFFFFFFF, 1) + record
        texts = {f"{i:08X}": name for i, name in [(1, "Not a function label"), (2, "Engine"),
                 (3, "Diesel"), (4, "Ecu_Data_00ab.BIN"), (5, "Can1")]}
        insert_decoded(self.db, "SYSLIST_EU.BIN", source, texts)
        self.assertEqual(("EU", 861, 12), self.db.execute("SELECT * FROM vehicle").fetchone())
        self.assertEqual((1, "Engine", "Diesel", "Ecu_Data_00ab.BIN", "Can1"),
                         self.db.execute("SELECT function_key,name,description,ecu_file,transport FROM system").fetchone())

    def test_only_actual_root_manual_branch_is_selectable_no_vin_autoselection(self):
        root = bytearray(84)
        struct.pack_into("<I", root, 0, 84)
        struct.pack_into("<I", root, 8, 1)
        struct.pack_into("<H", root, 36, 2)
        struct.pack_into("<I", root, 57, 84)
        struct.pack_into("<I", root, 80, 122)
        a = bytearray(38); struct.pack_into("<I", a, 0, 38); struct.pack_into("<I", a, 8, 2)
        b = bytearray(38); struct.pack_into("<I", b, 0, 38); struct.pack_into("<I", b, 8, 3)
        insert_menu(self.db, bytes(root + a + b), {"00000001": "Show Menu", "00000002": "Manual Select", "00000003": "VIN Search"})
        self.assertEqual([(0, 0), (84, 1), (122, 0)], self.db.execute("SELECT id,manual FROM menu ORDER BY id").fetchall())
        self.assertEqual(2, self.db.execute("SELECT count(*) FROM menu_edge").fetchone()[0])

    def test_malformed_known_table_fails_instead_of_being_quietly_marked_opaque(self):
        with self.assertRaises(ValueError):
            insert_decoded(self.db, "EU/Ecu_Data_000b.BIN", b"bad", {})
        self.assertEqual(0, self.db.execute("SELECT count(*) FROM source_file").fetchone()[0])


if __name__ == "__main__":
    unittest.main()
