"""Synthetic AArch64 code only. No supplied library or device is used in unit tests."""
import struct
import unittest

from native_replay import NativeSandbox, ReplayError


def elf_fixture(code=bytes.fromhex('0000018b c0035fd6'), *, relocation=None, symbol_type=2):
    """Minimal ET_DYN with one load segment, symbols and an optional RELA import."""
    text = bytearray(256)
    text[:len(code)] = code
    names = b'\0entry\0oracle\0'
    sym = bytes(24) + struct.pack('<IBBHQQ', 1, 0x12, 0, 1, 0x1000, len(code))
    sym += struct.pack('<IBBHQQ', 7, 0x10 | symbol_type, 0, 0 if symbol_type == 2 else 1,
                       0 if symbol_type == 2 else 0x1040, 0 if symbol_type == 2 else 8)
    rela = struct.pack('<QQq', 0x1020, 2 << 32 | relocation, 0) if relocation is not None else b''
    shstr = b'\0.text\0.dynstr\0.dynsym\0.rela.dyn\0.shstrtab\0'
    sections = [(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)]
    payload = bytearray(0x1000) + text
    sections.append((1, 1, 7, 0x1000, 0x1000, len(text), 0, 0, 16, 0))
    for name_at, kind, data, link, align, entsize in (
            (7, 3, names, 0, 1, 0), (15, 11, sym, 2, 8, 24),
            (23, 4, rela, 3, 8, 24), (33, 3, shstr, 0, 1, 0)):
        while len(payload) % align: payload.append(0)
        offset = len(payload); payload += data
        sections.append((name_at, kind, 0, offset, offset, len(data), link, 1 if kind == 11 else 0, align, entsize))
    while len(payload) % 8: payload.append(0)
    shoff = len(payload)
    for section in sections: payload += struct.pack('<IIQQQQIIQQ', *section)
    ident = b'\x7fELF\x02\x01\x01' + bytes(9)
    payload[:64] = struct.pack('<16sHHIQQQIHHHHHH', ident, 3, 183, 1, 0x1000, 64, shoff, 0, 64, 56, 1, 64, len(sections), 5)
    payload[64:120] = struct.pack('<IIQQQQQQ', 1, 7, 0, 0, 0, len(payload), len(payload) + 128, 4096)
    return bytes(payload)


CALL_IMPORT = bytes.fromhex('fd7bbfa9 f0000058 00023fd6 fd7bc1a8 c0035fd6')


class NativeReplayTest(unittest.TestCase):
    def test_emulated_instructions_memory_and_complete_return(self):
        sandbox = NativeSandbox({'synthetic.so': elf_fixture()})
        self.assertEqual(19, sandbox.call('synthetic.so', 'entry', 7, 12))
        self.assertEqual(21, sandbox.call('synthetic.so', 0x1000, 20, 1))
        data = sandbox.allocate(16, b'fixture\0')
        self.assertEqual(b'fixture', sandbox.string(data))
        self.assertEqual(b'fixture\0' + bytes(8), sandbox.read(data, 16))

    def test_import_has_no_implicit_success_and_cannot_call_the_host(self):
        sandbox = NativeSandbox({'synthetic.so': elf_fixture(CALL_IMPORT, relocation=1026)})
        with self.assertRaisesRegex(ReplayError, 'no experiment binding'):
            sandbox.call('synthetic.so', 'entry')
        sandbox.bind('oracle', lambda a, b, *_: a * 10 + b)
        self.assertEqual(123, sandbox.call('synthetic.so', 'entry', 12, 3))

    def test_function_pointer_cells_need_explicit_binding(self):
        # Literal loads the address of a local function-pointer cell (ABS64),
        # then the code dereferences it. A null/uninitialized cell must fail.
        code = bytes.fromhex('fd7bbfa9 f0000058 100240f9 00023fd6 fd7bc1a8 c0035fd6')
        sandbox = NativeSandbox({'synthetic.so': elf_fixture(code, relocation=257, symbol_type=1)})
        with self.assertRaises(ReplayError): sandbox.call('synthetic.so', 'entry')
        sandbox.bind('oracle', lambda a, *_: a + 19, indirect=True, library='synthetic.so')
        self.assertEqual(42, sandbox.call('synthetic.so', 'entry', 23))

    def test_unmapped_access_loops_and_unsupported_relocations_abort(self):
        sandbox = NativeSandbox({'synthetic.so': elf_fixture(bytes.fromhex('000040f9 c0035fd6'))})
        with self.assertRaisesRegex(ReplayError, 'Replay stopped'):
            sandbox.call('synthetic.so', 'entry', 0)
        loop = NativeSandbox({'synthetic.so': elf_fixture(bytes.fromhex('00000014'))}, instruction_limit=100)
        with self.assertRaisesRegex(ReplayError, 'budget exhausted'):
            loop.call('synthetic.so', 'entry')
        with self.assertRaisesRegex(ReplayError, 'Unsupported AArch64 relocation'):
            NativeSandbox({'synthetic.so': elf_fixture(relocation=1028)})
        for n in (-1, 33):
            with self.assertRaises(ReplayError): sandbox.reg(n)
        with self.assertRaises(ReplayError): sandbox.allocate(sandbox.HEAP_BYTES + 1)
        with self.assertRaises(ReplayError): sandbox.call('synthetic.so', 'entry', *range(17))

    def test_integer_stack_arguments_follow_aapcs_and_do_not_leak_between_calls(self):
        # ldr x0,[sp]; ldr x1,[sp,#56]; add x0,x0,x1; add x0,x0,x7; ret
        code = bytes.fromhex('e00340f9 e11f40f9 0000018b 0000078b c0035fd6')
        sandbox = NativeSandbox({'synthetic.so': elf_fixture(code)})
        self.assertEqual(7 + 8 + 15, sandbox.call('synthetic.so', 'entry', *range(16)))
        self.assertEqual(7 + 8, sandbox.call('synthetic.so', 'entry', *range(9)))
        self.assertEqual(7, sandbox.call('synthetic.so', 'entry', *range(8)))
        self.assertEqual(0, sandbox.call('synthetic.so', 'entry'))
        with self.assertRaises(ReplayError): sandbox.call('synthetic.so', 'entry', *([0] * 8 + [True]))

    def test_elf_identity_and_bounds_are_not_guessed(self):
        fixture = elf_fixture()
        for offset, value in ((4, 1), (5, 2), (16, 2), (18, 62)):
            bad = bytearray(fixture); bad[offset] = value
            with self.subTest(offset=offset), self.assertRaises(ReplayError):
                NativeSandbox({'synthetic.so': bytes(bad)})
        bad = bytearray(fixture); struct.pack_into('<Q', bad, 64 + 40, NativeSandbox.MAX_LIBRARY_SPAN + 1)
        with self.assertRaises(ReplayError): NativeSandbox({'synthetic.so': bytes(bad)})
        for name in ('../vendor.so', 'native.bin'):
            with self.assertRaises(ReplayError): NativeSandbox({name: fixture})

    def test_bounded_printf_uses_aapcs_gp_and_fp_banks(self):
        sandbox = NativeSandbox({'synthetic.so': elf_fixture()})
        fmt = sandbox.allocate(64, b'%04X %.3f %s %%\0')
        text = sandbox.allocate(16, b'fixture\0')
        out = sandbox.allocate(256)
        from native_replay import XREGS
        sandbox.uc.reg_write(XREGS[2], 43)
        sandbox.uc.reg_write(XREGS[3], text)
        sandbox.set_f64(3.25)
        self.assertEqual(20, sandbox._sprintf(out, fmt))
        self.assertEqual(b'002B 3.250 fixture %', sandbox.string(out))
        sandbox.write(fmt, b'%n\0')
        with self.assertRaisesRegex(ReplayError, 'printf format'): sandbox._sprintf(out, fmt)


if __name__ == '__main__':
    unittest.main()
