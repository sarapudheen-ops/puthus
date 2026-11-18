"""Standard-library-only app asset contract shared by building and verification."""
import hashlib
from pathlib import Path

SOURCE_SHA256 = "26c77a39070e751cb58a86db970e8f14991e21611eb4fb0dfffa2cd2cddfe05a"
FORMAT_VERSION = 1
DB_NAME = "toyota-source.db"
MAX_ZIP_BYTES = 60 * 1024 * 1024
MAX_DB_BYTES = 384 * 1024 * 1024
EXPECTED_COUNTS = {"archive_entry": 1604, "source_file": 1410, "data_table": 15215, "text_library": 8,
                   "text_record": 198091, "menu": 149731, "menu_edge": 149730, "vehicle": 10144,
                   "system": 287030, "function_index": 1275, "command": 9774, "frame": 8775,
                   "function_frame": 36997, "invalidCommands": 3, "opaqueFiles": 8}
PROCESSOR_FILES = ("app_library_format.py", "build_app_database.py", "schema/app_schema.sql",
                   "toyota_structure.py", "ggp.py", "yzjm.py", "analyze_toyota.py", "decode_toyota.py", "extract_english.py")


def fingerprint():
    root = Path(__file__).parent
    return hashlib.sha256(b"".join((root / name).read_bytes() for name in PROCESSOR_FILES)).hexdigest()
