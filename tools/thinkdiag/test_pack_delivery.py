import gzip
import hashlib
import io
from pathlib import Path
import tempfile
import unittest

from check_pack_delivery import check, check_url, fetch
from vehicle_bundle import BundleWriter, canonical, read_object
from source_verification import load_verification


class Response(io.BytesIO):
    def __init__(self, data=b"", status=206, **headers):
        super().__init__(data)
        self.status = status
        self.headers = headers
        self.reads = 0

    def read(self, size=-1):
        self.reads += 1
        return super().read(size)


class Opener:
    def __init__(self, *responses):
        self.responses = list(responses)
        self.requests = []

    def open(self, request, timeout):
        self.requests.append(request)
        return self.responses.pop(0)


class DeliveryTest(unittest.TestCase):
    def setUp(self):
        self.raw = canonical({"kind": "synthetic", "value": 42})
        self.packed = gzip.compress(self.raw, mtime=0)
        self.ref = {"offset": 11, "bytes": len(self.packed), "jsonBytes": len(self.raw),
                    "sha256": hashlib.sha256(self.packed).hexdigest()}
        self.total = 11 + len(self.packed) + 100
        self.container = "toyota-" + "0" * 64 + ".bin"
        self.headers = {"Content-Range": f"bytes 11-{10 + len(self.packed)}/{self.total}",
                        "Content-Length": str(len(self.packed)), "Content-Encoding": "identity"}

    def test_exact_ranges_and_allowlisted_redirects_only(self):
        opener = Opener(Response(status=302, Location="https://release-assets.githubusercontent.com/synthetic"),
                        Response(self.packed, **self.headers))
        self.assertEqual(42, fetch(self.container, self.total, self.ref, opener)["value"])
        self.assertEqual(2, len(opener.requests))
        for request in opener.requests:
            self.assertEqual(f"bytes=11-{10 + len(self.packed)}", request.get_header("Range"))
            self.assertEqual("identity", request.get_header("Accept-encoding"))

    def test_rejects_full_file_wrong_range_length_encoding_and_redirect(self):
        for status, override in ((200, {}), (206, {"Content-Range": "bytes 0-10/100"}),
                                 (206, {"Content-Length": "1234567"}), (206, {"Content-Encoding": "gzip"})):
            response = Response(self.packed, status, **(self.headers | override))
            with self.subTest(status=status, override=override), self.assertRaises(ValueError):
                fetch(self.container, self.total, self.ref, Opener(response))
            self.assertEqual(0, response.reads) # never read a rejected full corpus
        with self.assertRaises(ValueError):
            fetch(self.container, self.total, self.ref, Opener(Response(status=302, Location="https://evil.example/data")))
        for url in ("http://github.com/data", "https://github.com.evil.example/data", "https://u@github.com/data", "https://github.com:8443/data"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                check_url(url)

    def test_rejects_corrupt_oversized_truncated_or_overexpanding_object(self):
        for data in (self.packed[:-1], self.packed + b"x", b"x" * len(self.packed)):
            with self.subTest(length=len(data)), self.assertRaises(ValueError):
                fetch(self.container, self.total, self.ref, Opener(Response(data, **self.headers)))
        for length in (1, len(self.raw) + 1):
            with self.subTest(json_length=length), self.assertRaises(ValueError):
                fetch(self.container, self.total, self.ref | {"jsonBytes": length}, Opener(Response(self.packed, **self.headers)))

    def test_delivery_walk_fetches_one_path_and_one_system_not_the_corpus(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            writer = BundleWriter(root)
            system = writer.add({"kind": "vehicle-system", "id": "synthetic-system"})
            ref = writer.add({"kind": "converted-vehicle", "id": "variant", "systems": [{"object": system}]})
            identity = "variant"
            for parent in ("generation", "model", "make", "root"):
                ref = writer.add({"kind": "catalog-level", "id": parent,
                                  "children": [{"id": identity, "level": identity, "object": ref}]})
                identity = parent
            index = writer.finish(ref, {"executableDefinitions": 0, "sourceVerification": load_verification()}, ref)
            requests = []

            def local(container, total, wanted):
                self.assertEqual(index["container"], container)
                requests.append(wanted)
                return read_object(root / container, wanted)

            result = check(root, local)
            self.assertEqual(6, result["requests"])
            self.assertEqual(6, len(requests))
            self.assertEqual("variant", result["vehicleId"])
            self.assertEqual("synthetic-system", result["systemId"])
