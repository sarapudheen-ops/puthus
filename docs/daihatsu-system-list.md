# DAIHATSU-1: the linked system-list reader, traced

One objective, one archive. This records what the DAIHATSU system-list reader's
own code establishes about `DAIHATSU_SYS_LIST.BIN`, field by field, and why no
converter, pack or app change follows. It is read-only research: no field meaning
is inferred from an export name or an integer value, no DTC or diagnostic
operation is claimed, and nothing is enabled.

Evidence: `tools/inventory/daihatsu_trace.py`, run in GitHub Actions (corpus
access is CI-only), published as the `DAIHATSU-1 system-list trace` check parts
and as `daihatsu-trace.md` / `daihatsu-trace.json` in `ci-reports-v1`. The
disassembly citations below are virtual addresses in the archive's own
`libDAIHATSU_FILE.so`; every instruction quoted is in that published trace.

## 1. Target reproduction and verification

| item | value |
| --- | --- |
| Archive | `DAIHATSU.zip`, 8,457,341 bytes |
| Archive SHA-256 | `07dc46c86678f09e6408fa1b68cdf61e076231334195123eba86ff685001ec72` |
| Hash source | the exact `sha256` Inventory-8 recorded for the `DAIHATSU.zip · keyed-index-8` pair; the tool refuses any other archive |
| Member | `DIAGNOSTIC/VEHICLES/DAIHATSU/V10.22/DAIHATSU_SYS_LIST.BIN` |
| Member raw | 109,795 bytes, SHA-256 `0368d955a68acc67f09c671d4b49c0a2575884f3b8a3d24b4099e446f6effc6b` |
| Member decoded | 109,764 bytes, SHA-256 `7e6db75a586cfc1da6b4d238ca9ae52e62a7fcc40e62d533c4dcb6c447943cee` |
| YZJM header | make `DAIHATSU`, version `V20.61`, seed `612577241` |
| Format table | from the archive's own `libDEVICEID.so`, SHA-256 `370e1e039131ef0da4a129335dc4f10b91bf61fae767726b79380af14033557a` |
| Reader library | `libDAIHATSU_FILE.so`, 108,208 bytes, SHA-256 `b98d46468096c1ec634be773d05c2933920f5719979069300e1993ee7988082d`, stripped (no `.symtab`), 83 exports |
| Literal | VA `0x15a07` = `DAIHATSU_SYS_LIST.BIN`, exact match |

The decoded member is produced by `yzjm.decode_yzjm` with the archive's own
32-byte format table — the same wrapper path the repo already uses for Toyota,
not a guessed key.

## 2. The filename link is real, and it is in `GetVehicleInfoData`

Inventory-8 found the literal but did not say which function references it. The
trace finds exactly one code cross-reference:

- `0xdf70` (`adrp`@`0xdf60` page `0x15000` + `0xa07`) inside **`GetVehicleInfoData`
  @ `0xdeec` (448 bytes)** — not `GetSystemListData`.

That `add x1, x1, #0xa07` is the second argument to `fopenEx`:

```text
0000df6c: ldr  x0, [x0, #0xa68]   ; [0x29a68] reloc FOPEN
0000df70: add  x1, x1, #0xa07     ; 0x15a07: 'DAIHATSU_SYS_LIST.BIN'
0000df74: add  x2, x2, #0x9c5     ; 0x159c5: 'rb'
0000df78: blr  x8                 ; fopenEx
```

So the function that **opens** `DAIHATSU_SYS_LIST.BIN` is `GetVehicleInfoData`,
and the literal is its filename argument. A filename literal alone never proved
this; the `fopenEx` call site does. `GetSystemListData` is then called by
`GetVehicleInfoData` at `0xe058` (`bl GetSystemListData@plt`) to parse one block.
No other library in the archive imports either export (bounded scan), so the
callers that supply the requested ID and consume the output struct live outside
this archive and were not available to trace.

## 3. What the code establishes about the format

### 3.1 Index (`GetVehicleInfoData`, `0xdfc8`–`0xe058`)

`GetVehicleInfoData(output, id)` seeks to 0 and reads consecutive LE32 words,
comparing each to its `id` argument (`cmp w8, w20`, `0xe010`), stopping at
`0xFFFFFFFF` (`cmn w8, #1`, `0xe004`). On a match at position *p* it reads the
LE32 at *p+4* and passes it to `GetSystemListData` as a file offset. The data
confirms the shape: 8-byte rows `(LE32 key, LE32 offset)`, keys
`0xf000, 0xf001, … 0xf18d` (398 rows), sentinel `0xFFFFFFFF` at `0xc70`, index
ends `0xc74`. This is exactly what `sentinel_index(data, 0, 8)` accepts.

- **Index key — meaning established (structural):** a lookup key compared to the
  caller's requested ID; the paired value is its block offset. *Opaque:* what
  real-world entity the key denotes. The keys are a sequential enumeration from
  `0xf000`; nothing in the traced code ties a key to a vehicle model name.
- **Index value — meaning established:** a byte offset into this file, used
  directly as `fseekEx`'s target (`0xe020`–`0xe048`).
- **Sentinel `0xFFFFFFFF` — meaning established:** index terminator.

### 3.2 Block and records (`GetSystemListData`, `0xe0ac`, 2028 bytes)

`GetSystemListData(file, offset, output)` reads a **LE16 count** at `offset`
(`freadEx(sp+0x10, 2, 1, file)`, `0xe150`–`0xe164`), then loops `count` times
(`cmp w28, w8`, `0xe844`), advancing the file offset by **`0x12` = 18 bytes per
record** (`add w21, w21, #0x12`, `0xe83c`). The output is an array of
**1060-byte (`0x424`) system structs**; the current system index is a LE16 at
`output[0]`, incremented once per record (`0xe82c`–`0xe834`). Per record the code
reads these typed fields (file offset within the 18-byte record → output offset
within the 1060-byte struct):

| record offset | type | output offset | evidence (freadEx size + store) |
| ---: | --- | ---: | --- |
| 0 | LE16 | +6 | `0xe1c4` read 2; `strh w27, [x8, #6]` `0xe1e8` |
| 2 | LE32 | +12 | `0xe234` read 4 → `output+idx*1060+12` |
| 6 | u8 | +11 | `0xe27c` read 1 → `+11` |
| 7 | u8 | +24 | `0xe2c4` read 1 → `+24` |
| 8 | u8 | +10 | `0xe30c` read 1 → `+10` |
| 9 | u8 | +25 | `0xe358` read 1 → `+25` |
| 10 | LE32 | +20 | `0xe494` read 4 → `+20` |
| 14 | LE32 | +16 | `0xe4dc` read 4 → `+16` |

- **LE16 count — meaning established:** number of records in the block (loop bound).
- **Record stride 18 — meaning established:** `0xe83c`. The data corroborates it:
  block `0xc74` (count 4) ends at `0xc74 + 2 + 4*18 = 0xcbe`, exactly the next
  block's offset; likewise `0xcbe` (count 5) → `0xd1a`, `0xd1a` (count 3) → `0xd52`.
- **Every field's offset and type — meaning established (layout)** per the table.
- **Every field's denotation — opaque.** The code only copies these values into
  the output struct; it performs no text lookup and derives no diagnostic
  address, service, PID or timing from any of them.

### 3.3 Two transforms the code does perform (still not meanings)

1. **`0xFFFF` indirection.** If the record's LE16@0 equals `0xFFFF`
   (`cmp w27, w8` with `w8=0xffff`, `0xe1f0`–`0xe1f8`), the LE32@2 is treated as a
   pointer: the code seeks to it, reads a LE16 sub-count, and iterates 16-byte
   (`0x10`) sub-records, calling `EnterSystemByID` (`blr x9`, `0xe574`). The
   *transform* is established; what the sub-list denotes is opaque.
2. **Bit-mask flag.** The byte at output+25 (record u8@9) is read (`ldrb w8,
   [x8, #0x19]`, `0xe414`), bounded by `0x20`, and tested as
   `(1 << value) & 0x104020080` (`0xe424`–`0xe438`). A non-zero result sets
   output+9 to 1 and, once, the global byte at `output+0x34468` to 1
   (`0xe440`–`0xe454`). Mask bits are {7, 17, 26, 32}. The *transform* is
   established; what the flag means is opaque.

### 3.4 Returned values

`GetSystemListData` returns `w0 = 1` (`0xe888`) after processing the block;
`GetVehicleInfoData` returns `cset w0, ne` (`0xe084`) — 1 if the requested ID was
found, 0 otherwise. These are success/found flags, not diagnostic data.

## 4. Row reconciliation (all DAIHATSU system-list members)

The archive has three BIN members; all were processed:

| member | result |
| --- | --- |
| `DAIHATSU_SYS_LIST.BIN` | decoded; the system-list member traced above |
| `DAIHATSU_SYS_DATA.BIN` | decoded (251,324 bytes, SHA-256 `988adb39209136b5074031a28345d70d4b810a3fcca1882f42497fb26d3ab849`); `sentinel_index` rejects it (`Duplicate index identity 0`) — a different format, not the system list |
| `MENU.BIN` | not YZJM-wrapped (28,621 bytes, SHA-256 `ab3dc9563a9685e34c7d9e5c756494d390dab8707f25ac0a40f564fb2e233953`); not read by this reader |

For `DAIHATSU_SYS_LIST.BIN`:

- **398 index rows, 0 duplicate keys, 0 duplicate offsets** (398 distinct block
  offsets). `sentinel_index(0, 8)` accepts; the raw 8-byte scan agrees and finds
  the sentinel.
- **18-byte block tiling (the code's stride): 284 blocks tile exactly, 114 do
  not.** Every non-tiling block is a **gap** (its `count*18 + 2` end falls short
  of the next block offset), never an overlap. The gaps are inter-block data the
  fixed 18-byte stride does not account for; their contents and cause are **not
  established** by this trace (they are consistent with the `0xFFFF` sub-list
  payloads, but that is not proven here).
- **37-byte tiling (Toyota's shape, for contrast): 4 tile, 394 fail**, almost all
  by **overlap** — e.g. block `0xc74` count 4 would end at `0xd0a`, past the next
  block at `0xcbe`. `system_lists` (the Toyota LE16-count + 37-byte reader)
  rejects the member outright: `Truncated packed system-list records`. The
  earlier Inventory-7/8 `keyed-index-8` parse was the index only; the 37-byte
  record shape does **not** apply to DAIHATSU.

So: accepted index rows 398; rejected index rows 0; duplicate rows 0. At the
record level the layout is established but the block array is not a clean
uniform tile (114 gaps), which a faithful reader would have to resolve before it
could claim complete coverage.

## 5. Implementation gate: meanings remain opaque → no implementation

The trace establishes **layout and transforms**, not **field meanings**. No
field is tied to a displayed label or to a diagnostic address, service, PID or
timing; the traced code performs no text lookup and no such derivation, and the
callers that consume the output struct are outside this archive. Per the
objective's rule — never infer meaning from an export name or an integer value —
every record field's denotation stays **opaque**, and the index key's real-world
denotation stays opaque.

Therefore this change makes **no converter, no pack and no app change**. A
layout-only reader of opaque integers would not be a useful field-meaning
reader, and the app's index/pack schema (built around Toyota's named 37-byte
systems) cannot represent these opaque 18-byte records without Toyota
assumptions. Toyota loading is unchanged by construction: nothing under `app/`
or `tools/thinkdiag/` was modified.

### Exact remaining unknowns

1. The denotation of the index key (which vehicle/model/system-list enumeration
   `0xf000+i` names).
2. The denotation of each record field: LE16@0, LE32@2, u8@6, u8@7, u8@8, u8@9,
   LE32@10, LE32@14 (and the output structs they fill at +6, +12, +11, +24, +10,
   +25, +20, +16).
3. The meaning of the bit-mask flag (bits {7, 17, 26, 32}) and the global byte at
   `output+0x34468`.
4. The structure and denotation of the `0xFFFF` sub-lists (16-byte sub-records
   passed to `EnterSystemByID`).
5. The contents and cause of the 114 inter-block gaps.

### What would resolve them

Static disassembly has reached its limit: it can name offsets, types and
transforms, but it cannot say which real system, service or PID an opaque
identifier denotes. Naming any field requires **runtime observation of the
brand's own reader on a vehicle or ECU** (watching which output field feeds which
displayed label or diagnostic request) **or vendor documentation**. Neither is
available in this repository. No vehicle testing is claimed, and no vehicle
operation is enabled from system-list data.

## 6. Changed files

- `tools/inventory/daihatsu_trace.py` (new) — the read-only trace tool.
- `tools/inventory/test_daihatsu_trace.py` (new) — synthetic tests (hand-assembled
  AArch64 ELF decoded by capstone, YZJM round-trip, full-pipeline integration,
  index/block reconciliation).
- `.github/workflows/daihatsu-trace.yml` (new) — CI-only corpus access and report
  publication.
- `docs/daihatsu-system-list.md` (this file).

No raw archive member is added to the APK; SHA-256 verification and the app's
128 MiB pack cache (`PackObjectStore`) are untouched.
