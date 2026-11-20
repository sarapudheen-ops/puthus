# Bounded Toyota numeric equivalence review

**Numeric semantics only; no executable recovered ECU or vehicle definition.**
`tools/thinkdiag/toyota_calc.py` recognizes exactly two literal source programs. It does not run
EXPRESS, simplify arbitrary expressions, approve an ECU link, or grant import/write trust. The
production converter and pack verifier still allow **zero executable recovered definitions**.

## Source and static observations

[Inspection 44489358675](https://github.com/sarapudheen-ops/puthus/actions/runs/44489358675)
(`72c608e2caca30d8f6111c8a8ed10b6667ac8c95`) supplied 546 checksum-verified reports, including
original non-code objects and static reader disassembly for `libTOYOTA_CALC.so`, library SHA-256
`364645c275977f139e92ac0a9b7995ab20939f32a86371876e0bdbe04a7e1197`.
That historical inspection did not execute vendor native code. The current owner-authorized offline
oracle additionally executes the supplied CALC instructions in bounded AArch64 emulation: the numeric
consumer on all 65,792 reviewed inputs and eight `ResultCalcFromScript` edge calls. Constructors,
host `dlopen`, syscalls, files, JNI, adapter and ECU access are not bridged. libc/printf is a declared
shim; this is not proof of native libc or a physical vehicle. See [the current native domains and CI evidence](conversion-report.md#native-execution-and-fault-decoder-port--not-counted-as-executable).

The review requires that exact library hash and all four exact object address/size/content hashes:

| Object/selector | Bytes | Purpose |
|---|---:|---|
| `opchTbl @0x38008` | 300 | 25 token/length/opcode records; `*`=2, `/`=3, `+`=6, `-`=7, `(`=21, `)`=22 |
| `@0x20cac:0x5c` | 92 | Incoming binding powers; arithmetic 11/11/10/10, left parenthesis 13, right parenthesis 1 |
| `@0x20d08:0x5c` | 92 | Stacked binding powers; arithmetic 11/11/10/10, left parenthesis 1 |
| `@0x20980:0x50` | 80 | 20 signed relative branch offsets for binary operations |

The second binding-power **slice is not a 23-entry table**: its bytes after ID 21 contain the text
`error`. They are included in its hash, but never interpreted as stacked priorities. Hash/address
mismatch, a missing/duplicate object or a different library fails the numeric review. These checks
are reproducibility checks, not a new app trust root; fixtures can reproduce the small tables but
cannot enable a real ECU.

The static reader establishes the following limited paths:

- `ResultCalcFromScript @0xf9bc..0xf9d8` removes adjacent `y=`; the ordinary numeric branch at
  `0xfd9c` calls the helper at `0x1032c`.
- `0x10b88..0x10bec`, `0x10c34` and `0x10f38..0x10f48`: `x` and `x1` select the first input byte,
  `x2` selects the second; the byte load is unsigned and converted to binary64.
- `0x10ae0..0x10b58`: a `0x` literal is accumulated in base 16. The literal `0x100` is exactly 256.
- `0x10830..0x10850` compares incoming and stacked priorities; equal arithmetic priorities reduce
  first. The reviewed multiplication/addition grouping therefore does not rely on Python precedence.
- The jump table selects `fmul @0x10918`, guarded division at `0x10920..0x10930`, addition at
  `0x10974`, and left-minus-right subtraction at `0x1097c`. Division rejects absolute denominators
  at or below `1e-7` (constant loaded from `0x20748`); this is **not** the app's general division rule.
- `0xfde0..0xfe08` renders integral values with `%.0f`; non-integral values go through `%.3f` at
  `0xff34`. The native public result is text, not an unformatted double.

## The entire reviewed input domains

| Exact source program | App numeric formula | Complete input domain | Equivalence argument |
|---|---|---|---|
| `y=x-40;` | `(A-40)` | 256 unsigned byte values | The result is an exact integer from -40 to 215; native integer formatting loses nothing. |
| `y=(0x100*x1+x2)/4;` | `(((256*A)+B)/4)` | 65,536 unsigned byte pairs | The intermediate integer is 0..65,535; division by 4 produces an exact binary quarter, 0..16,383.75. Four is above the native division guard. Both native numeric-format branches preserve the result. |

`NativeLiveScenarios.reviewedUnsignedArithmeticCoversItsEntireByteDomain()` runs the actual app
`CustomPid.decode` for **all 65,792 inputs**, comparing with independent exact decimal spellings of
the native integer/quarter output. It also rejects truncated and out-of-range bytes. The high unsigned
values are deliberately retained even beyond a source display range: a display maximum is not an
ECU-validity clamp. This is an independently implemented decoder test, **not** a native execution or
physical ECU comparison.

Only the literal source text, exact emitted formula and required byte count receive this metadata.
Different whitespace, case, decimal/hex spellings, unary rules, variable division, signed helpers,
strings, bitfield programs and control flow do not inherit approval. The wider AST-based arithmetic
translator still emits disabled candidates with `calc_semantics_unreviewed` for other programs.

## Effect on the exact EU/function-61/ECU-0081 review

The same 53 checksum-verified applicability/text inputs were reconstructed locally from the existing
72c reports; the full source archive was not re-downloaded. All **1,015 rows** remain accounted for:
**353 disabled numeric candidates**, **662 rejected** (630 applicability, 21 formula/window, 11 dynamic
scaling). Of those 353, **30 rows use one of these two literal numeric forms**. They retain their
original EXPRESS/source-row IDs and gain separate `calcReview` provenance. This is neither 30 working
features nor complete applicability for a vehicle. All 353 still have `ecu_entry_untranslated`;
323 also retain `calc_semantics_unreviewed`. Nothing is copied to app `data.pids`.

Representative source IDs remain `FUNCDATA_EU.BIN@00051245#function61:live28` (coolant, EXPRESS `16`)
and `FUNCDATA_EU.BIN@00051371#function61:live38` (RPM, EXPRESS `19`). Their separate exact A801 support
records/discovery prerequisites are unchanged. The sweep performs this review separately from the
all-disabled production converter; missing or changed native evidence cannot silently approve it.

## ECU-entry boundary, still open

For EU/function 61, selector `0x11 @SYSTEMENTER.BIN:0x2f63` is native transport 1, flags `0x81`,
**10,416 baud**, pin bytes `07 07`, and timing bytes `05 3c 00 19 19`. The reviewed consumers at
`COMM InitCommSet_Ex @0x18c84`, `InitKWPISOMode @0x19830` and `SYSP FastKWPEnterMode @0x109b8`
pass native `SetEcuCommTimeInterval` arguments **(float 5, integer 60, integer 600)** and use 25/25
pulse parameters. Those register arguments must not simply be relabeled P1/P2/P3, nor may 10,416
be silently replaced by the app's 10,400 default. The complete adapter-equivalent timing, pin/line,
entry-frame, keepalive/exit and retry behavior remains a separate transport conversion requirement.
Selector `0x51` is a different native fast/ISO alternative, not permission to substitute `0x11`.

The native abstract-layer configuration bytes (including `0x6003` timing commands and `55aa...61`
wrappers) are adapter packets, not ECU requests, and are not emitted by this numeric review.

A separate closed source port, `toyota_transport.py`, now projects the literal COMM configuration API
sequence for all **284 native transport-1/5 entries**, not just the first ECU. Native execution matches
**568 configuration traces, 17,892 source packet vectors and eight synthetic configuration probes**.
The **2,016**-vector source-short-packet digest also matches Kotlin `KLineFrames.encode`. API returns
are explicit trace-only fakes; they prove neither an ST/VT mapping nor an initialized ECU. The port
retains full selector/region/function isolation, original baud (including the native 9,600-baud branch),
explicit pin-pair selection, binary32 baud conversion and the full shifted timing-word ABI argument on
the alternate connector. Tests reject source flag changes and packets above the 63-byte short-frame
limit rather than silently switching variants. No P1–P4 name, receive address or operational grant is
inferred from this intermediate projection; the per-ECU production blocker above remains unresolved.

The additional `toyota_lifecycle.py` projection now joins the exact regional/function initial,
keepalive and exit bindings for all 284 entries. A linked native FILE → SYSP → COMM comparison
matches first-positive-entry, keepalive-on/off and exit call traces without Python frame/packet
substitutions. Source keepalives have **six** exact variants, not a universal `3E`, and native pulse
arguments include **35/15** as well as **25/25**; the first ECU does not supply global defaults.
Adapter APIs and the positive reply are still synthetic. Native failure/retry/ISO-fallback paths,
ST/VT-equivalent timing semantics and production emission remain unfinished.
