# Toyota fast-KWP link evidence (work in progress)

`toyota_link_map.py` inventories the 284 source lifecycle plans from the pinned,
independently native-compared lifecycle contract. It emits no Android link or
executable definition. The records are regional function/entry contexts, not
permission to reuse one system's route on another. CI publishes the inventory as
`source-link-evidence.json` with the vehicle-pack research artifacts.

For `SYSLIST_EU.BIN@00023a75`, the exact EU/function 61/full-selector `51` join
is `SYSTEMENTER.BIN@00002f96` (transport 5, flags `33`, baud 10416, pin bytes
`07 07`, source timing `05 3c 00 19 19`). The source fast-init packet is
`8110F08102`; the source keepalive request is `3E` (wire `8110F03EBF`), from
`EU/Toyota_bin_12.BIN@0000401a` and `EU/Toyota_bin_11.BIN@0000050a`.
Transport 5 also requires the source ISO-fallback path. These bytes do not prove
an app response header or a MIC3313 configuration. Do not enable the system.

## Adapter equivalence work remaining

This is an inventory of app commands, not an equivalence table or approval.
All mappings below remain **unproven** until adapter documentation and explicit
negative, native-trace and owner bench evidence establish the relationship.

| App primitive | App purpose / outstanding evidence |
|---|---|
| `ATPC`, `ATSPx` | close and select app protocol; source dispatch and fallback equivalence unproven |
| `ATH1`, `ATSW00`, `ATKW0` | app response formatting and adapter policy; source semantics unproven |
| `ATST`, `ATAT0`, `STIAT0`, `STIP1X`, `STPTO`, `STPTRQ`, `STIP4` | app timeout/timing settings; source interval arguments must not be relabelled P1/P2/P3 |
| `STPBR` | app baud parameter; native binary32 conversion and adapter behaviour require equivalence evidence |
| `STIIAP`, `STIP`, `STITW` | app parity/slow-init settings; source-to-adapter meaning unproven |
| `ATIIA`, `ATSH` | app address settings; source FAST init address and response-header mapping unproven; `00` is a pilot placeholder, not source data |
| `STIFI`, `ATSI` | app fast / five-baud initialization; transport-5 fallback, pin/line and reply semantics unproven |

Source pin bytes alone do not establish a MIC3313 line-selection command or an
adapter-default connector. The existing driver's non-strict handling of rejected
ST commands is not evidence of success. No new adapter primitive or relaxed
fallback is introduced here. Toyota↔MIC3313 equivalence and physical operation
remain unproven without owner-provided bench evidence.
