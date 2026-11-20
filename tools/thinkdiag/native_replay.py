"""Bounded AArch64 *offline* semantic replay; never Android code or a device driver.

Supplied instructions execute only in Unicorn's emulated address space. ELF
constructors are not called. There is no host dlopen, syscall, JNI, file, socket,
adapter or ECU bridge. Every external function must be explicitly implemented
or bound by the experiment; unknown calls/relocations/instructions abort.

This is an oracle for source-to-app ports, not a diagnostic runtime or evidence
that a vehicle was tested. Inputs and traces belong in ignored research output.
"""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import io
import math
import re
import struct
from typing import Callable

from elftools.common.exceptions import ELFError
from elftools.elf.elffile import ELFFile
from elftools.elf.relocation import RelocationSection
from unicorn import (Uc, UcError, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE,
                     UC_PROT_READ, UC_PROT_WRITE, UC_PROT_EXEC)
from unicorn.arm64_const import (UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X29,
    UC_ARM64_REG_X30, UC_ARM64_REG_SP, UC_ARM64_REG_PC, UC_ARM64_REG_TPIDR_EL0,
    UC_ARM64_REG_D0, UC_ARM64_REG_S0)

PAGE = 4096
MASK64 = (1 << 64) - 1
# Unicorn's X29/X30 enum IDs are not contiguous with X0..X28.
XREGS = [UC_ARM64_REG_X0 + i for i in range(29)] + [UC_ARM64_REG_X29, UC_ARM64_REG_X30]


class ReplayError(ValueError):
    """A replay is incomplete/unsupported; never silently substitute a result."""


@dataclass
class Library:
    name: str
    sha256: str
    base: int
    elf: ELFFile
    symbols: dict
    relocations: list


class NativeSandbox:
    MAX_LIBRARY_BYTES = 32 * 1024 * 1024
    MAX_LIBRARY_SPAN = 32 * 1024 * 1024
    MAX_LIBRARIES = 32
    STACK = 0x6000000000
    STACK_BYTES = 4 * 1024 * 1024
    HEAP = 0x6100000000
    HEAP_BYTES = 128 * 1024 * 1024
    TLS = 0x6200000000
    SHIMS = 0x6300000000
    SHIM_BYTES = 1024 * 1024
    RETURN = SHIMS + SHIM_BYTES - PAGE

    def __init__(self, libraries: dict[str, bytes], *, instruction_limit=2_000_000, timeout_us=5_000_000):
        if not 0 < len(libraries) <= self.MAX_LIBRARIES or not 1 <= instruction_limit <= 50_000_000 or not 1 <= timeout_us <= 30_000_000:
            raise ReplayError("Invalid replay bounds")
        self.uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        self.instruction_limit, self.timeout_us = instruction_limit, timeout_us
        self.libraries: dict[str, Library] = {}
        self.hooks: dict[int, tuple[str, Callable]] = {}
        self.imports: dict[str, int] = {}
        self.bindings: dict[str, Callable] = {}
        self.trace = []
        self.allocations = {}
        self._next_heap = self.HEAP
        self._next_shim = self.SHIMS
        self._failure = None
        self._returned = False
        self.uc.mem_map(self.STACK, self.STACK_BYTES, UC_PROT_READ | UC_PROT_WRITE)
        self.uc.mem_map(self.HEAP, self.HEAP_BYTES, UC_PROT_READ | UC_PROT_WRITE)
        self.uc.mem_map(self.TLS, PAGE, UC_PROT_READ | UC_PROT_WRITE)
        self.uc.mem_map(self.SHIMS, self.SHIM_BYTES, UC_PROT_READ | UC_PROT_EXEC)
        self.uc.reg_write(UC_ARM64_REG_TPIDR_EL0, self.TLS)
        self.write(self.TLS + 0x28, b"toyota!!")
        for i, (name, data) in enumerate(sorted(libraries.items())):
            self._load(name, data, 0x10000000 + i * 0x4000000)
        for library in self.libraries.values():
            self._relocate(library)
        self.uc.hook_add(UC_HOOK_CODE, self._hook, begin=self.SHIMS, end=self.SHIMS + self.SHIM_BYTES - 1)
        self._bind_libc()

    def _load(self, name, data, base):
        if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9_.-]+\.so", name) or not isinstance(data, bytes) or not 64 <= len(data) <= self.MAX_LIBRARY_BYTES:
            raise ReplayError("Invalid replay library")
        try:
            elf = ELFFile(io.BytesIO(data))
            if elf['e_machine'] != 'EM_AARCH64' or elf.elfclass != 64 or not elf.little_endian or elf['e_type'] != 'ET_DYN':
                raise ReplayError("Only little-endian AArch64 shared objects are supported")
            segments = [s for s in elf.iter_segments() if s['p_type'] == 'PT_LOAD']
            if not segments:
                raise ReplayError("ELF has no loadable segments")
            low = min(s['p_vaddr'] for s in segments)
            high = max(s['p_vaddr'] + s['p_memsz'] for s in segments)
            if low < 0 or high > self.MAX_LIBRARY_SPAN:
                raise ReplayError("Unbounded ELF load span")
            pages = {}
            for s in segments:
                start, size, file_size = s['p_vaddr'], s['p_memsz'], s['p_filesz']
                if size < file_size or s['p_offset'] + file_size > len(data):
                    raise ReplayError("ELF segment exceeds file/memory bounds")
                flags = s['p_flags']
                perms = (UC_PROT_READ if flags & 4 else 0) | (UC_PROT_WRITE if flags & 2 else 0) | (UC_PROT_EXEC if flags & 1 else 0)
                for page in range(start & -PAGE, (start + size + PAGE - 1) & -PAGE, PAGE):
                    pages[page] = pages.get(page, 0) | perms
            for page, perms in pages.items():
                self.uc.mem_map(base + page, PAGE, perms)
            for segment in segments:
                self.write(base + segment['p_vaddr'], segment.data())
            dynsym = elf.get_section_by_name('.dynsym')
            if dynsym is None:
                raise ReplayError("ELF has no dynamic symbols")
            symbols = {s.name: s for s in dynsym.iter_symbols() if s.name and s['st_shndx'] != 'SHN_UNDEF'}
            relocations = []
            for section in elf.iter_sections():
                if isinstance(section, RelocationSection):
                    table = elf.get_section(section['sh_link'])
                    for reloc in section.iter_relocations():
                        symbol = table.get_symbol(reloc['r_info_sym']) if reloc['r_info_sym'] else None
                        relocations.append((reloc, symbol))
            self.libraries[name] = Library(name, hashlib.sha256(data).hexdigest(), base, elf, symbols, relocations)
        except (KeyError, TypeError, IndexError, ELFError, UcError) as error:
            raise ReplayError(f"Malformed {name}: {error}") from error

    def symbol(self, library, name):
        try:
            lib = self.libraries[library]
            return lib.base + lib.symbols[name]['st_value']
        except KeyError as error:
            raise ReplayError(f"Missing replay symbol {library}:{name}") from error

    def _resolve(self, name):
        matches = [lib.base + lib.symbols[name]['st_value'] for lib in self.libraries.values() if name in lib.symbols]
        if len(set(matches)) == 1:
            return matches[0]
        if len(set(matches)) > 1:
            raise ReplayError(f"Ambiguous replay symbol: {name}")
        if name not in self.imports:
            self.imports[name] = self._shim(name, lambda *_: self._unbound(name))
        return self.imports[name]

    def _relocate(self, lib):
        for reloc, symbol in lib.relocations:
            kind = reloc['r_info_type']
            at = lib.base + reloc['r_offset']
            addend = reloc['r_addend'] if reloc.is_RELA() else self.u64(at)
            if kind == 0:
                continue
            if kind == 1027:  # R_AARCH64_RELATIVE
                value = lib.base + addend
            elif kind in (257, 1025, 1026):  # ABS64, GLOB_DAT, JUMP_SLOT
                if symbol is None or not symbol.name:
                    raise ReplayError("Relocation needs a named symbol")
                value = (lib.base + symbol['st_value'] if symbol['st_shndx'] != 'SHN_UNDEF' else self._resolve(symbol.name)) + addend
            else:
                raise ReplayError(f"Unsupported AArch64 relocation {kind} in {lib.name}")
            try:
                self.write(at, struct.pack('<Q', value & MASK64))
            except UcError as error:
                raise ReplayError("Relocation outside mapped ELF") from error

    def _shim(self, name, callback):
        if self._next_shim >= self.RETURN:
            raise ReplayError("Replay import budget exceeded")
        at = self._next_shim
        self._next_shim += 8
        self.hooks[at] = (name, callback)
        self.write(at, bytes.fromhex('c0035fd6'))  # ret; the code hook sets X0 first.
        return at

    def bind(self, name, callback, *, indirect=False, library=None):
        """Override an import, or an explicit exported function-pointer cell.

        An indirect cell is never inferred from its name/size. The experiment
        must identify it from the source's open-library/consumer instructions.
        """
        if indirect:
            if library is None:
                owners = [lib.name for lib in self.libraries.values() if name in lib.symbols]
                if len(owners) != 1:
                    raise ReplayError(f"Indirection owner is not unique: {name}")
                library = owners[0]
            cell = self.symbol(library, name)
            self.write(cell, struct.pack('<Q', self._shim(name, callback)))
        else:
            address = self.imports.get(name) if library is None else None
            if address is None:
                # Hook a real exported function only on explicit request. A
                # library-scoped binding never overrides its sibling's export.
                # No changes to supplied bytes are stored on disk.
                owners = [lib for lib in self.libraries.values()
                          if name in lib.symbols and (library is None or lib.name == library)]
                if len(owners) != 1 or owners[0].symbols[name]['st_info']['type'] != 'STT_FUNC':
                    raise ReplayError(f"Unknown/ambiguous replay function: {library}:{name}")
                address = owners[0].base + owners[0].symbols[name]['st_value']
                if address not in self.hooks:
                    self.uc.hook_add(UC_HOOK_CODE, self._hook, begin=address, end=address)
            self.hooks[address] = (name, callback)
        self.bindings[name if library is None else library + ':' + name] = callback

    def link_function_pointer(self, library, cell, target_library, function):
        """Explicitly join a source-identified pointer cell to real guest code.

        This does not run Open*Dll, constructors, dlopen or a host loader. The
        experiment identifies BOTH consumer cell and supplier export; no name
        guessing, callback or fallback is used. A data object cannot be linked
        as executable code and an ordinary function cannot be used as a cell.
        """
        try:
            slot = self.libraries[library].symbols[cell]
            target = self.libraries[target_library].symbols[function]
            if (slot['st_info']['type'] != 'STT_OBJECT' or slot['st_size'] != 8
                    or target['st_info']['type'] != 'STT_FUNC' or target['st_size'] < 4):
                raise ReplayError('Native linkage needs an eight-byte pointer cell and a defined guest function')
        except KeyError as error:
            raise ReplayError('Missing explicitly selected guest linkage endpoint') from error
        self.write(self.symbol(library, cell), struct.pack('<Q', self.symbol(target_library, function)))

    def _unbound(self, name):
        raise ReplayError(f"External call has no experiment binding: {name}")

    def _hook(self, uc, address, size, _):
        if address == self.RETURN:
            self._returned = True
            uc.emu_stop()
            return
        try:
            item = self.hooks.get(address)
            if item is None:
                raise ReplayError("Execution entered an unbound shim")
            name, callback = item
            result = callback(*[self.reg(i) for i in range(8)])
            if result is not None:
                uc.reg_write(UC_ARM64_REG_X0, int(result) & MASK64)
            uc.reg_write(UC_ARM64_REG_PC, uc.reg_read(UC_ARM64_REG_X30))
        except Exception as error:
            self._failure = error
            uc.emu_stop()

    def reg(self, i):
        if not 0 <= i < 31:
            raise ReplayError("Invalid AArch64 register")
        return self.uc.reg_read(XREGS[i])

    def f32(self, i=0):
        return struct.unpack('<f', struct.pack('<I', self.uc.reg_read(UC_ARM64_REG_S0 + i)))[0]

    def f64(self, i=0):
        return struct.unpack('<d', struct.pack('<Q', self.uc.reg_read(UC_ARM64_REG_D0 + i)))[0]

    def set_f64(self, value):
        self.uc.reg_write(UC_ARM64_REG_D0, struct.unpack('<Q', struct.pack('<d', value))[0])

    def read(self, address, size):
        if not 0 <= size <= self.HEAP_BYTES:
            raise ReplayError("Unbounded replay read")
        return bytes(self.uc.mem_read(address, size))

    def write(self, address, data):
        if len(data) > self.HEAP_BYTES:
            raise ReplayError("Unbounded replay write")
        if data:
            self.uc.mem_write(address, bytes(data))

    def u64(self, address):
        return struct.unpack('<Q', self.read(address, 8))[0]

    def string(self, address, limit=16384):
        if not 0 < limit <= 1_048_576:
            raise ReplayError("Unbounded replay string")
        result = bytearray()
        for i in range(limit):
            b = self.read(address + i, 1)[0]
            if b == 0:
                return bytes(result)
            result.append(b)
        raise ReplayError("Replay string has no terminator within its bound")

    def allocate(self, size, data=b''):
        if type(size) is not int or not 0 <= size <= self.HEAP_BYTES or len(data) > size:
            raise ReplayError("Invalid replay allocation")
        aligned = (max(size, 1) + 15) & -16
        if self._next_heap + aligned > self.HEAP + self.HEAP_BYTES:
            raise ReplayError("Replay heap exhausted")
        at = self._next_heap
        self._next_heap += aligned
        self.allocations[at] = size
        self.write(at, bytes(size))
        self.write(at, data)
        return at

    def call(self, library, function, *args):
        # AAPCS64: x0..x7, then eight-byte stack slots at the 16-byte-aligned
        # entry SP. This is deliberately a bounded integer/pointer-only API;
        # aggregates, variadic metadata and implicit host pointers are not bridged.
        if len(args) > 16 or any(type(i) is not int for i in args):
            raise ReplayError("Replay arguments must be at most sixteen machine words")
        lib = self.libraries[library]
        if isinstance(function, str):
            address = self.symbol(library, function)
        elif type(function) is int and 0 <= function < self.MAX_LIBRARY_SPAN:
            address = lib.base + function
        else:
            raise ReplayError("Invalid replay entry")
        for i in range(31):
            self.uc.reg_write(XREGS[i], args[i] & MASK64 if i < len(args) else 0)
        stack = self.STACK + self.STACK_BYTES - 4096
        self.write(stack, b''.join(struct.pack('<Q', i & MASK64) for i in args[8:]).ljust(64, b'\0'))
        self.uc.reg_write(UC_ARM64_REG_SP, stack)
        self.uc.reg_write(UC_ARM64_REG_X30, self.RETURN)
        self._failure, self._returned = None, False
        try:
            self.uc.emu_start(address, self.RETURN + 4, timeout=self.timeout_us, count=self.instruction_limit)
        except UcError as error:
            raise ReplayError(f"Replay stopped at {self.uc.reg_read(UC_ARM64_REG_PC):#x}: {error}") from error
        if self._failure is not None:
            raise ReplayError(f"Replay callback failed: {self._failure}") from self._failure
        if not self._returned:
            raise ReplayError("Replay instruction/time budget exhausted or no complete return")
        return self.reg(0)

    def capture(self, name, *, result=0, indirect=True, library=None):
        """A declared fake boundary: record arguments; it is NOT that API's implementation."""
        def callback(*args):
            self.trace.append({'name': name, 'x': list(args), 's0': self.f32(), 'd0': self.f64()})
            return result
        self.bind(name, callback, indirect=indirect, library=library)

    def _bind_libc(self):
        def install(name, fn):
            if name in self.imports:
                self.bind(name, fn)
        def memcpy(dst, src, size, *_):
            self.write(dst, self.read(src, size)); return dst
        def memset(dst, value, size, *_):
            if size > self.HEAP_BYTES:
                raise ReplayError("Unbounded memset")
            self.write(dst, bytes([value & 255]) * size); return dst
        def strncpy(dst, src, size, *_):
            if size > 1_048_576:
                raise ReplayError("Unbounded strncpy")
            raw = self.read(src, size).split(b'\0', 1)[0]
            self.write(dst, raw + bytes(size - len(raw))); return dst
        def strcmp(a, b, *_):
            aa, bb = self.string(a), self.string(b)
            return (aa > bb) - (aa < bb)
        def strncmp(a, b, n, *_):
            aa, bb = self.read(a, n).split(b'\0')[0], self.read(b, n).split(b'\0')[0]
            return (aa > bb) - (aa < bb)
        def strcpy(dst, src, *_):
            self.write(dst, self.string(src) + b'\0'); return dst
        def strcat(dst, src, *_):
            self.write(dst + len(self.string(dst)), self.string(src) + b'\0'); return dst
        def strstr(a, b, *_):
            at = self.string(a).find(self.string(b)); return a + at if at >= 0 else 0
        def strchr(a, b, *_):
            at = (self.string(a) + b'\0').find(bytes([b & 255])); return a + at if at >= 0 else 0
        def memcmp(a, b, n, *_):
            aa, bb = self.read(a, n), self.read(b, n); return (aa > bb) - (aa < bb)
        def realloc(old, size, *_):
            if old and old not in self.allocations:
                raise ReplayError("realloc of unknown replay allocation")
            at = self.allocate(size)
            if old:
                self.write(at, self.read(old, min(size, self.allocations[old])))
            return at
        def atof(src, *_):
            text = self.string(src).decode('ascii')
            match = re.match(r'\s*[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[Ee][+-]?\d+)?', text)
            self.set_f64(float(match[0]) if match else 0.0); return None
        def power(*_):
            self.set_f64(math.pow(self.f64(0), self.f64(1))); return None
        for name, callback in {
            'memcpy': memcpy, 'memmove': memcpy, 'memset': memset, 'memcmp': memcmp,
            'strlen': lambda a, *_: len(self.string(a)), 'strcmp': strcmp, 'strncmp': strncmp,
            'strcpy': strcpy, 'strncpy': strncpy, 'strcat': strcat, 'strstr': strstr, 'strchr': strchr,
            'malloc': lambda n, *_: self.allocate(n), 'calloc': lambda n, m, *_: self.allocate(n * m),
            'free': lambda *_: 0, 'realloc': realloc, 'atof': atof, 'pow': power,
            '__stack_chk_fail': lambda *_: self._unbound('__stack_chk_fail'),
            'sprintf': self._sprintf,
        }.items():
            install(name, callback)

    def _sprintf(self, dst, fmt, *unused):
        # Small, explicit printf class for native CALC; %n and unknown formatting
        # always fail. AArch64 GP and FP varargs use independent register banks.
        text = self.string(fmt).decode('ascii')
        out, at, gp, fp = [], 0, 2, 0
        spec = re.compile(r'%([-+ #0]*)(\d{0,3})(?:\.(\d{1,3}))?(ll|l|h|hh)?([diuxXofFeEgGsc%])')
        while at < len(text):
            if text[at] != '%':
                out.append(text[at]); at += 1; continue
            match = spec.match(text, at)
            if match is None:
                raise ReplayError('Unimplemented native printf format')
            flags, width, precision, length, kind = match.groups()
            if kind == '%':
                if match[0] != '%%':
                    raise ReplayError('Unsupported literal printf conversion')
                out.append('%'); at = match.end(); continue
            if kind in 'fFeEgG':
                if fp >= 8:
                    raise ReplayError('Unimplemented FP stack varargs')
                value = self.f64(fp); fp += 1
            else:
                value = self.reg(gp) if gp < 8 else self.u64(self.uc.reg_read(UC_ARM64_REG_SP) + (gp - 8) * 8)
                gp += 1
                if kind == 's':
                    value = self.string(value).decode('latin1')
                elif kind == 'c':
                    value &= 255
                else:
                    bits = 64 if length in ('l', 'll') else 32
                    value &= (1 << bits) - 1
                    if kind in 'di' and value >= 1 << (bits - 1):
                        value -= 1 << bits
            pattern = '%' + flags + width + ('.' + precision if precision is not None else '') + ('d' if kind in 'iu' else kind)
            out.append(pattern % value)
            at = match.end()
        data = ''.join(out).encode('latin1')
        if len(data) > 16384:
            raise ReplayError('Unbounded native printf output')
        self.write(dst, data + b'\0')
        return len(data)
