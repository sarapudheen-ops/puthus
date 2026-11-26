# Brand inventory — exploratory Toyota-table investigation

Read-only evidence only; nothing is enabled. This document's inventory sections are verified in CI against the report generated on the commit containing this file. For complete per-file data, consult the [CI reports release](https://github.com/sarapudheen-ops/puthus/releases/tag/ci-reports-v1) and the check run for that commit. A commit cannot embed its own SHA without changing that SHA.

## Main baseline

Main `aa4a816216f845867bf9345200cb532d50bf7450`: [release-data run](https://github.com/sarapudheen-ops/puthus/actions/runs/45638513378) succeeded. [Toyota masked-step outcomes](https://github.com/sarapudheen-ops/puthus/runs/139302918325):

| step | raw outcome |
| --- | --- |
| code1_lifecycle_sweep | success |
| publish_ecu_input | success |
| publish_native_outcome | success |
| publish_link_promotion | success |
| publish_stage1b_report | success |
| publish_stage1c_report | success |
| publish_sweep_reports | success |
| publish_convert_log | success |
| publish_sweep_evidence | success |
| publish_static_reports | success |
| publish_dtc_shards | success |
| publish_inspection_check | success |
| publish_stage3a_report | success |
| publish_stage0_report | failure (known and accepted) |

## Toyota control

108/108 applicable sampled BINs parsed; strict majority (>50%) passed, with zero errors. The outer sample comprises all 8 eligible files; the nested sample comprises 100 of 1,307 eligible files (50 shallowest, then uniform stride). See the generated report for the exact path and control details.

<!-- INVENTORY3 START -->
## Inventory-3: BIN headers, libDEVICEID, and Toyota-table experiment

exploratory: Toyota table applied to other brands; success is self-verifying because the decoded bytes must parse in the container reader, failure proves nothing.

YZJM magic: `05 00 59 5a 4a 4d 00`. Other recognized magic uses the explicit signatures listed in `tools/inventory/inventory3.py`; text, entropy and arbitrary nonzero bytes are not treated as recognized magic. Unmeasured samples include encrypted or oversized members.

The same per-archive sampling rule as Inventory-2 is used; the exploratory denominator is sampled YZJM files with a converter reader keyed to the file name. An empty denominator is not a 0% failure. Decoded YZJM counts mean the decoder returned bytes, not that the key or payload is valid: YZJM has no integrity check. The Toyota table is kept only in memory, never published.

| brand | sampled | YZJM | other magic (top first-8-byte hex: count) | no recognized header | unmeasured | libDEVICEID size / SHA-256 | vs Toyota | same VA bytes | contains Toyota table | decoded YZJM | exploratory parsed/applicable | outcome |
| --- | ---: | ---: | --- | ---: | ---: | --- | --- | --- | --- | ---: | --- | --- |
| TOYOTA | 76 | 75 | none (total 0) | 1 | 0 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | reference | not applicable | not applicable | 75 | 68/68 | decodes with Toyota table, container layout not verified |
| ASTONMARTIN | 6 | 5 | none (total 0) | 1 | 0 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 5 | 0/0 | no verdict change |
| AUDI | 24 | 14 | none (total 0) | 9 | 1 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 14 | 0/0 | no verdict change |
| AUSTHOLDEN | 26 | 16 | none (total 0) | 10 | 0 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 16 | 0/0 | no verdict change |
| AUTOSEARCH | 100 | 99 | none (total 0) | 0 | 1 | 79600 / `f3c1ce545cb5181703de5e7cb2c8a36a9853800fe247a22a03723b82eedee3b9` | different | false | true | 99 | 0/0 | no verdict change |
| BENZ | 25 | 6 | none (total 0) | 19 | 0 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 6 | 0/0 | no verdict change |
| BMW | 100 | 8 | none (total 0) | 92 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 8 | 0/0 | no verdict change |
| BXFIAT | 2 | 1 | none (total 0) | 1 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 1 | 0/0 | no verdict change |
| BXGM | 3 | 0 | none (total 0) | 3 | 0 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 0 | 0/0 | no verdict change |
| CHANGAN | 33 | 32 | none (total 0) | 1 | 0 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 32 | 0/0 | no verdict change |
| CHANGCHENG | 33 | 20 | none (total 0) | 13 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 20 | 0/0 | no verdict change |
| CHANGHE | 14 | 7 | none (total 0) | 7 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 7 | 0/0 | no verdict change |
| CHRYSLER | 24 | 20 | none (total 0) | 4 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 20 | 0/0 | no verdict change |
| CITROEN | 22 | 17 | none (total 0) | 3 | 2 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 17 | 0/0 | no verdict change |
| DAEWOO | 3 | 0 | none (total 0) | 3 | 0 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 0 | 0/0 | no verdict change |
| DAIHATSU | 3 | 2 | none (total 0) | 1 | 0 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 2 | 0/0 | no verdict change |
| DEMO | 2 | 0 | none (total 0) | 2 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 0 | 0/0 | no verdict change |
| DONGNAN | 12 | 11 | none (total 0) | 1 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 11 | 0/0 | no verdict change |
| EOBD2 | 2 | 2 | none (total 0) | 0 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 2 | 0/0 | no verdict change |
| FERRARI | 7 | 6 | none (total 0) | 1 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 6 | 0/0 | no verdict change |
| FIAT | 12 | 5 | none (total 0) | 7 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 5 | 0/0 | no verdict change |
| FLYER | 74 | 69 | none (total 0) | 5 | 0 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 69 | 0/0 | no verdict change |
| FUTIAN | 9 | 8 | none (total 0) | 1 | 0 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 8 | 0/0 | no verdict change |
| GM | 37 | 33 | none (total 0) | 4 | 0 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 33 | 0/0 | no verdict change |
| HAFEI | 7 | 3 | none (total 0) | 4 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 3 | 0/0 | no verdict change |
| HMAZDA | 14 | 11 | none (total 0) | 3 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 11 | 0/0 | no verdict change |
| HONDA | 11 | 8 | none (total 0) | 3 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 8 | 0/0 | no verdict change |
| HUACHEN | 21 | 12 | none (total 0) | 9 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 12 | 0/0 | no verdict change |
| HYUNDAI | 63 | 18 | 504b030400000808: 1 (total 1) | 44 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 18 | 0/0 | no verdict change |
| INDIANMAHINDRA | 3 | 0 | none (total 0) | 3 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 0 | 0/0 | no verdict change |
| INDIANMARUTI | 12 | 9 | none (total 0) | 3 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 9 | 0/0 | no verdict change |
| INDIANTATA | 3 | 0 | none (total 0) | 3 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 0 | 0/0 | no verdict change |
| ISUZU | 12 | 2 | none (total 0) | 10 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 2 | 0/0 | no verdict change |
| IVECO | 7 | 0 | none (total 0) | 7 | 0 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 0 | 0/0 | no verdict change |
| JACTY | 36 | 22 | none (total 0) | 14 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 22 | 0/0 | no verdict change |
| JIAO | 11 | 8 | none (total 0) | 3 | 0 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 8 | 0/0 | no verdict change |
| JINLONG | 7 | 5 | none (total 0) | 2 | 0 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 5 | 0/0 | no verdict change |
| JPISUZU | 12 | 7 | none (total 0) | 5 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 7 | 0/0 | no verdict change |
| KARRY_TY | 13 | 9 | none (total 0) | 4 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 9 | 0/0 | no verdict change |
| KIA | 46 | 20 | 504b030400000808: 1 (total 1) | 25 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 20 | 0/0 | no verdict change |
| LANDROVER | 100 | 88 | none (total 0) | 11 | 1 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 88 | 0/0 | no verdict change |
| LIFAN | 9 | 7 | none (total 0) | 2 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 7 | 0/0 | no verdict change |
| LIUWEI_TY | 26 | 23 | none (total 0) | 3 | 0 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 23 | 0/0 | no verdict change |
| LUFENG | 6 | 5 | none (total 0) | 1 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 5 | 0/0 | no verdict change |
| LUXGEN | 2 | 1 | none (total 0) | 1 | 0 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 1 | 0/0 | no verdict change |
| MASERATI | 7 | 6 | none (total 0) | 1 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 6 | 0/0 | no verdict change |
| MAXUS | 21 | 20 | none (total 0) | 1 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 20 | 0/0 | no verdict change |
| MAZDA | 13 | 10 | none (total 0) | 3 | 0 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 10 | 0/0 | no verdict change |
| MITSUBISHI | 100 | 53 | none (total 0) | 47 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 53 | 0/0 | no verdict change |
| NISSAN | 286 | 15 | none (total 0) | 271 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 15 | 0/0 | no verdict change |
| OPEL | 19 | 16 | none (total 0) | 3 | 0 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 16 | 0/0 | no verdict change |
| PEUGEOT | 22 | 17 | none (total 0) | 3 | 2 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 17 | 0/0 | no verdict change |
| PORSCHE | 45 | 30 | none (total 0) | 15 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 30 | 0/0 | no verdict change |
| QIRUI_TY | 19 | 11 | none (total 0) | 8 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 11 | 0/0 | no verdict change |
| QOROS | 7 | 6 | none (total 0) | 1 | 0 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 6 | 0/0 | no verdict change |
| RENAULT | 100 | 7 | none (total 0) | 93 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 7 | 0/0 | no verdict change |
| SAAB | 3 | 1 | none (total 0) | 2 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 1 | 0/0 | no verdict change |
| SAICMG | 16 | 15 | none (total 0) | 1 | 0 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 15 | 0/0 | no verdict change |
| SAICROEWE | 14 | 12 | none (total 0) | 2 | 0 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 12 | 0/0 | no verdict change |
| SEAT | 22 | 11 | none (total 0) | 10 | 1 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 11 | 0/0 | no verdict change |
| SGM | 32 | 28 | none (total 0) | 4 | 0 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 28 | 0/0 | no verdict change |
| SKODA | 22 | 12 | none (total 0) | 9 | 1 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 12 | 0/0 | no verdict change |
| SMART | 6 | 3 | none (total 0) | 3 | 0 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 3 | 0/0 | no verdict change |
| SPRINTER | 9 | 3 | none (total 0) | 6 | 0 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 3 | 0/0 | no verdict change |
| SSANGYONG | 10 | 5 | none (total 0) | 5 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 5 | 0/0 | no verdict change |
| SUBARU | 10 | 6 | none (total 0) | 4 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 6 | 0/0 | no verdict change |
| SUZUKI | 10 | 9 | none (total 0) | 1 | 0 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 9 | 0/0 | no verdict change |
| USAFORD | 4 | 1 | none (total 0) | 3 | 0 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 1 | 0/0 | no verdict change |
| VAZ | 4 | 2 | none (total 0) | 2 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 2 | 0/0 | no verdict change |
| VOLVO | 7 | 4 | none (total 0) | 3 | 0 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 4 | 0/0 | no verdict change |
| VW | 29 | 14 | none (total 0) | 14 | 1 | 79600 / `44c9976c37f4f957f955b0b9fe90899b6a0a36208c908ac01ffc5f9b42184cb4` | identical | true | true | 14 | 0/0 | no verdict change |
| ZHONGTAI | 19 | 15 | none (total 0) | 4 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 15 | 0/0 | no verdict change |
| ZHONGXING | 15 | 11 | none (total 0) | 4 | 0 | 79600 / `e6a1276c431bb661541ab83ae1903d01604f7bf863466ff15377200505b7562f` | different | false | true | 11 | 0/0 | no verdict change |

### Exploratory ranking

| rank | brand | parsed/applicable | rate |
| ---: | --- | --- | ---: |
| 1 | ASTONMARTIN | 0/0 | not applicable |
| 2 | AUDI | 0/0 | not applicable |
| 3 | AUSTHOLDEN | 0/0 | not applicable |
| 4 | AUTOSEARCH | 0/0 | not applicable |
| 5 | BENZ | 0/0 | not applicable |
| 6 | BMW | 0/0 | not applicable |
| 7 | BXFIAT | 0/0 | not applicable |
| 8 | BXGM | 0/0 | not applicable |
| 9 | CHANGAN | 0/0 | not applicable |
| 10 | CHANGCHENG | 0/0 | not applicable |
| 11 | CHANGHE | 0/0 | not applicable |
| 12 | CHRYSLER | 0/0 | not applicable |
| 13 | CITROEN | 0/0 | not applicable |
| 14 | DAEWOO | 0/0 | not applicable |
| 15 | DAIHATSU | 0/0 | not applicable |
| 16 | DEMO | 0/0 | not applicable |
| 17 | DONGNAN | 0/0 | not applicable |
| 18 | EOBD2 | 0/0 | not applicable |
| 19 | FERRARI | 0/0 | not applicable |
| 20 | FIAT | 0/0 | not applicable |
| 21 | FLYER | 0/0 | not applicable |
| 22 | FUTIAN | 0/0 | not applicable |
| 23 | GM | 0/0 | not applicable |
| 24 | HAFEI | 0/0 | not applicable |
| 25 | HMAZDA | 0/0 | not applicable |
| 26 | HONDA | 0/0 | not applicable |
| 27 | HUACHEN | 0/0 | not applicable |
| 28 | HYUNDAI | 0/0 | not applicable |
| 29 | INDIANMAHINDRA | 0/0 | not applicable |
| 30 | INDIANMARUTI | 0/0 | not applicable |
| 31 | INDIANTATA | 0/0 | not applicable |
| 32 | ISUZU | 0/0 | not applicable |
| 33 | IVECO | 0/0 | not applicable |
| 34 | JACTY | 0/0 | not applicable |
| 35 | JIAO | 0/0 | not applicable |
| 36 | JINLONG | 0/0 | not applicable |
| 37 | JPISUZU | 0/0 | not applicable |
| 38 | KARRY_TY | 0/0 | not applicable |
| 39 | KIA | 0/0 | not applicable |
| 40 | LANDROVER | 0/0 | not applicable |
| 41 | LIFAN | 0/0 | not applicable |
| 42 | LIUWEI_TY | 0/0 | not applicable |
| 43 | LUFENG | 0/0 | not applicable |
| 44 | LUXGEN | 0/0 | not applicable |
| 45 | MASERATI | 0/0 | not applicable |
| 46 | MAXUS | 0/0 | not applicable |
| 47 | MAZDA | 0/0 | not applicable |
| 48 | MITSUBISHI | 0/0 | not applicable |
| 49 | NISSAN | 0/0 | not applicable |
| 50 | OPEL | 0/0 | not applicable |
| 51 | PEUGEOT | 0/0 | not applicable |
| 52 | PORSCHE | 0/0 | not applicable |
| 53 | QIRUI_TY | 0/0 | not applicable |
| 54 | QOROS | 0/0 | not applicable |
| 55 | RENAULT | 0/0 | not applicable |
| 56 | SAAB | 0/0 | not applicable |
| 57 | SAICMG | 0/0 | not applicable |
| 58 | SAICROEWE | 0/0 | not applicable |
| 59 | SEAT | 0/0 | not applicable |
| 60 | SGM | 0/0 | not applicable |
| 61 | SKODA | 0/0 | not applicable |
| 62 | SMART | 0/0 | not applicable |
| 63 | SPRINTER | 0/0 | not applicable |
| 64 | SSANGYONG | 0/0 | not applicable |
| 65 | SUBARU | 0/0 | not applicable |
| 66 | SUZUKI | 0/0 | not applicable |
| 67 | USAFORD | 0/0 | not applicable |
| 68 | VAZ | 0/0 | not applicable |
| 69 | VOLVO | 0/0 | not applicable |
| 70 | VW | 0/0 | not applicable |
| 71 | ZHONGTAI | 0/0 | not applicable |
| 72 | ZHONGXING | 0/0 | not applicable |

### Exploratory literal error histograms

- **TOYOTA**: no failed applicable samples
- **ASTONMARTIN**: no failed applicable samples
- **AUDI**: no failed applicable samples
- **AUSTHOLDEN**: no failed applicable samples
- **AUTOSEARCH**: no failed applicable samples
- **BENZ**: no failed applicable samples
- **BMW**: no failed applicable samples
- **BXFIAT**: no failed applicable samples
- **BXGM**: no failed applicable samples
- **CHANGAN**: no failed applicable samples
- **CHANGCHENG**: no failed applicable samples
- **CHANGHE**: no failed applicable samples
- **CHRYSLER**: no failed applicable samples
- **CITROEN**: no failed applicable samples
- **DAEWOO**: no failed applicable samples
- **DAIHATSU**: no failed applicable samples
- **DEMO**: no failed applicable samples
- **DONGNAN**: no failed applicable samples
- **EOBD2**: no failed applicable samples
- **FERRARI**: no failed applicable samples
- **FIAT**: no failed applicable samples
- **FLYER**: no failed applicable samples
- **FUTIAN**: no failed applicable samples
- **GM**: no failed applicable samples
- **HAFEI**: no failed applicable samples
- **HMAZDA**: no failed applicable samples
- **HONDA**: no failed applicable samples
- **HUACHEN**: no failed applicable samples
- **HYUNDAI**: no failed applicable samples
- **INDIANMAHINDRA**: no failed applicable samples
- **INDIANMARUTI**: no failed applicable samples
- **INDIANTATA**: no failed applicable samples
- **ISUZU**: no failed applicable samples
- **IVECO**: no failed applicable samples
- **JACTY**: no failed applicable samples
- **JIAO**: no failed applicable samples
- **JINLONG**: no failed applicable samples
- **JPISUZU**: no failed applicable samples
- **KARRY_TY**: no failed applicable samples
- **KIA**: no failed applicable samples
- **LANDROVER**: no failed applicable samples
- **LIFAN**: no failed applicable samples
- **LIUWEI_TY**: no failed applicable samples
- **LUFENG**: no failed applicable samples
- **LUXGEN**: no failed applicable samples
- **MASERATI**: no failed applicable samples
- **MAXUS**: no failed applicable samples
- **MAZDA**: no failed applicable samples
- **MITSUBISHI**: no failed applicable samples
- **NISSAN**: no failed applicable samples
- **OPEL**: no failed applicable samples
- **PEUGEOT**: no failed applicable samples
- **PORSCHE**: no failed applicable samples
- **QIRUI_TY**: no failed applicable samples
- **QOROS**: no failed applicable samples
- **RENAULT**: no failed applicable samples
- **SAAB**: no failed applicable samples
- **SAICMG**: no failed applicable samples
- **SAICROEWE**: no failed applicable samples
- **SEAT**: no failed applicable samples
- **SGM**: no failed applicable samples
- **SKODA**: no failed applicable samples
- **SMART**: no failed applicable samples
- **SPRINTER**: no failed applicable samples
- **SSANGYONG**: no failed applicable samples
- **SUBARU**: no failed applicable samples
- **SUZUKI**: no failed applicable samples
- **USAFORD**: no failed applicable samples
- **VAZ**: no failed applicable samples
- **VOLVO**: no failed applicable samples
- **VW**: no failed applicable samples
- **ZHONGTAI**: no failed applicable samples
- **ZHONGXING**: no failed applicable samples

## Summary

| brand | files | archive bytes | decoded parsed/applicable | generic overlap | discriminating overlap | named exports¹ | role libraries | verdict | reason |
| --- | ---: | ---: | --- | --- | --- | --- | --- | --- | --- |
| TOYOTA | 209 | 71755650 | 68/68 | 36/36 | 156/156 | 1: yes, 2: yes, 3: yes, 4: yes, 5: yes, 6: yes | libTOYOTA_ACTF.so, libTOYOTA_CALC.so, libTOYOTA_CDSF.so, libTOYOTA_COMM.so, libTOYOTA_DTCF.so, libTOYOTA_ECUF.so, libTOYOTA_ECUK.so, libTOYOTA_FDATA.so, libTOYOTA_FILE.so, libTOYOTA_FRZF.so, libTOYOTA_FZIP.so, libTOYOTA_INFO.so, libTOYOTA_OLD.so, libTOYOTA_SCAN.so, libTOYOTA_SCUST.so, libTOYOTA_SPEC.so, libTOYOTA_SYSP.so | same family as Toyota | Toyota reference; decoded control passed |
| ASTONMARTIN | 34 | 7154262 | 0/1 | 34/36 | 4/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libASTONMARTIN_CALC.so, libASTONMARTIN_CDSF.so, libASTONMARTIN_COMM.so, libASTONMARTIN_DTCF.so, libASTONMARTIN_FILE.so, libASTONMARTIN_SPEC.so, libASTONMARTIN_SYSP.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| AUDI | 70 | 104882444 | 0/1 | 35/36 | 1/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libAUDI_CALC.so, libAUDI_COMM.so, libAUDI_FILE.so, libAUDI_OLD.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| AUSTHOLDEN | 60 | 42146060 | 0/1 | 35/36 | 4/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libAUSTHOLDEN_ACTF.so, libAUSTHOLDEN_CALC.so, libAUSTHOLDEN_CDSF.so, libAUSTHOLDEN_COMM.so, libAUSTHOLDEN_DTCF.so, libAUSTHOLDEN_FILE.so, libAUSTHOLDEN_OLD.so, libAUSTHOLDEN_SYSP.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| AUTOSEARCH | 496 | 91606306 | 0/0 | 28/36 | 3/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | none | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| BENZ | 1870 | 817896907 | 0/1 | 34/36 | 1/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libBENZ_CALC.so, libBENZ_COMM.so, libBENZ_FILE.so, libBENZ_OLD.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| BMW | 2760 | 1973240752 | 0/1 | 34/36 | 4/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libBMW_CALC.so, libBMW_COMM.so, libBMW_OLD.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| BXFIAT | 28 | 4789970 | 0/1 | 32/36 | 1/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | none | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| BXGM | 25 | 4465150 | 0/1 | 34/36 | 3/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libBXGM_ACTF.so, libBXGM_CALC.so, libBXGM_CDSF.so, libBXGM_COMM.so, libBXGM_DTCF.so, libBXGM_FILE.so, libBXGM_OLD.so, libBXGM_SYSP.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| CHANGAN | 63 | 33767269 | 0/1 | 35/36 | 8/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libCHANGAN_OLD.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| CHANGCHENG | 281 | 33516510 | 0/1 | 35/36 | 4/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libCHANGCHENG_OLD.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| CHANGHE | 54 | 12217645 | 0/1 | 34/36 | 4/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libCHANGHE_OLD.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| CHRYSLER | 60 | 38976420 | 0/1 | 35/36 | 3/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libCHRYSLER_ACTF.so, libCHRYSLER_CALC.so, libCHRYSLER_CDSF.so, libCHRYSLER_COMM.so, libCHRYSLER_DTCF.so, libCHRYSLER_FILE.so, libCHRYSLER_OLD.so, libCHRYSLER_SPEC.so, libCHRYSLER_SYSP.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| CITROEN | 99 | 73336139 | 0/1 | 36/36 | 7/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libCITROEN_OLD.so, libCITROEN_SPEC.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| DAEWOO | 33 | 3620001 | 0/1 | 35/36 | 0/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libDAEWOO_ACTF.so, libDAEWOO_CALC.so, libDAEWOO_COMM.so, libDAEWOO_DTCF.so, libDAEWOO_FILE.so, libDAEWOO_OLD.so, libDAEWOO_SYSP.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| DAIHATSU | 50 | 8457341 | 0/1 | 36/36 | 10/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: yes | libDAIHATSU_ACTF.so, libDAIHATSU_CALC.so, libDAIHATSU_CDSF.so, libDAIHATSU_COMM.so, libDAIHATSU_DTCF.so, libDAIHATSU_FILE.so, libDAIHATSU_FRZF.so, libDAIHATSU_SPEC.so, libDAIHATSU_SYSP.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| DEMO | 253 | 60542274 | 0/0 | 33/36 | 0/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | none | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| DONGNAN | 40 | 9363412 | 0/1 | 35/36 | 8/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libDONGNAN_OLD.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| EOBD2 | 24 | 7084892 | 0/0 | 34/36 | 1/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | none | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| FERRARI | 41 | 6095516 | 0/1 | 33/36 | 5/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | none | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| FIAT | 62 | 27446550 | 0/1 | 35/36 | 5/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libFIAT_CALC.so, libFIAT_COMM.so, libFIAT_FILE.so, libFIAT_INFO.so, libFIAT_OLD.so, libFIAT_SPEC.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| FLYER | 99 | 39501934 | 0/1 | 34/36 | 8/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libFLYER_OLD.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| FUTIAN | 32 | 10240484 | 0/1 | 34/36 | 8/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | none | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| GM | 70 | 81427611 | 0/1 | 35/36 | 4/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libGM_ACTF.so, libGM_CALC.so, libGM_CDSF.so, libGM_COMM.so, libGM_DTCF.so, libGM_FILE.so, libGM_OLD.so, libGM_SYSP.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| HAFEI | 28 | 1955310 | 0/1 | 34/36 | 2/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libHAFEI_OLD.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| HMAZDA | 41 | 8655492 | 0/1 | 34/36 | 8/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libHMAZDA_OLD.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| HONDA | 174 | 35064563 | 0/0 | 34/36 | 6/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libHONDA_ACTF.so, libHONDA_CALC.so, libHONDA_CDSF.so, libHONDA_COMM.so, libHONDA_DTCF.so, libHONDA_FILE.so, libHONDA_FRZF.so, libHONDA_SPEC.so, libHONDA_SYSP.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| HUACHEN | 64 | 13961981 | 0/1 | 34/36 | 4/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libHUACHEN_OLD.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| HYUNDAI | 298 | 97477672 | 0/1 | 34/36 | 15/156 | 1: yes, 2: no, 3: yes, 4: no, 5: no, 6: no | libHYUNDAI_ACTF.so, libHYUNDAI_CALC.so, libHYUNDAI_COMM.so, libHYUNDAI_DTCF.so, libHYUNDAI_ECUF.so, libHYUNDAI_FILE.so, libHYUNDAI_FRZF.so, libHYUNDAI_OLD.so, libHYUNDAI_SYSP.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| INDIANMAHINDRA | 33 | 6901825 | 0/1 | 35/36 | 1/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | none | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| INDIANMARUTI | 144 | 23447578 | 0/1 | 35/36 | 5/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | none | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| INDIANTATA | 34 | 6477772 | 0/1 | 35/36 | 3/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | none | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| ISUZU | 41 | 12737896 | 0/1 | 32/36 | 1/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libISUZU_OLD.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| IVECO | 41 | 11507833 | 0/0 | 32/36 | 4/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | none | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| JACTY | 118 | 23210542 | 0/1 | 34/36 | 4/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | none | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| JIAO | 38 | 4086316 | 0/1 | 35/36 | 2/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libJIAO_OLD.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| JINLONG | 38 | 6130703 | 0/1 | 35/36 | 4/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | none | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| JPISUZU | 83 | 16298713 | 0/1 | 35/36 | 5/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libJPISUZU_OLD.so, libJPISUZU_SYSP.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| KARRY_TY | 55 | 20047040 | 0/1 | 35/36 | 4/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | none | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| KIA | 280 | 87457269 | 0/1 | 34/36 | 14/156 | 1: yes, 2: no, 3: yes, 4: no, 5: no, 6: no | libKIA_ACTF.so, libKIA_CALC.so, libKIA_COMM.so, libKIA_DTCF.so, libKIA_ECUF.so, libKIA_FILE.so, libKIA_FRZF.so, libKIA_OLD.so, libKIA_SYSP.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| LANDROVER | 1012 | 221491702 | 0/1 | 36/36 | 11/156 | 1: yes, 2: no, 3: yes, 4: no, 5: no, 6: yes | none | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| LIFAN | 34 | 6983671 | 0/1 | 34/36 | 8/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | none | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| LIUWEI_TY | 52 | 16045883 | 0/1 | 34/36 | 8/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | none | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| LUFENG | 30 | 3385986 | 0/1 | 35/36 | 2/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libLUFENG_OLD.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| LUXGEN | 37 | 4621630 | 0/1 | 33/36 | 1/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libLUXGEN_CALC.so, libLUXGEN_CDSF.so, libLUXGEN_COMM.so, libLUXGEN_DTCF.so, libLUXGEN_FILE.so, libLUXGEN_SYSP.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| MASERATI | 124 | 22442491 | 0/1 | 33/36 | 5/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | none | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| MAXUS | 48 | 17422250 | 0/1 | 34/36 | 8/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | none | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| MAZDA | 55 | 30461807 | 0/1 | 35/36 | 8/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libMAZDA_ACTF.so, libMAZDA_CALC.so, libMAZDA_CDSF.so, libMAZDA_COMM.so, libMAZDA_DTCF.so, libMAZDA_FILE.so, libMAZDA_OLD.so, libMAZDA_SPEC.so, libMAZDA_SYSP.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| MITSUBISHI | 640 | 81990250 | 0/1 | 34/36 | 6/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libMITSUBISHI_ACTF.so, libMITSUBISHI_CALC.so, libMITSUBISHI_CDSF.so, libMITSUBISHI_COMM.so, libMITSUBISHI_DTCF.so, libMITSUBISHI_FILE.so, libMITSUBISHI_FRZF.so, libMITSUBISHI_OLD.so, libMITSUBISHI_SPEC.so, libMITSUBISHI_SYSP.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| NISSAN | 189 | 93893330 | 0/1 | 35/36 | 10/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libNISSAN_ACTF.so, libNISSAN_CALC.so, libNISSAN_CDSF.so, libNISSAN_COMM.so, libNISSAN_DTCF.so, libNISSAN_FILE.so, libNISSAN_FRZF.so, libNISSAN_OLD.so, libNISSAN_SYSP.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| OPEL | 72 | 20288626 | 0/1 | 34/36 | 6/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libOPEL_OLD.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| PEUGEOT | 102 | 93817125 | 0/1 | 36/36 | 7/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libPEUGEOT_OLD.so, libPEUGEOT_SPEC.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| PORSCHE | 186 | 138720919 | 0/1 | 35/36 | 5/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libPORSCHE_ACTF.so, libPORSCHE_CALC.so, libPORSCHE_CDSF.so, libPORSCHE_COMM.so, libPORSCHE_DTCF.so, libPORSCHE_FILE.so, libPORSCHE_SYSP.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| QIRUI_TY | 71 | 16222386 | 0/1 | 35/36 | 4/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | none | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| QOROS | 30 | 5034092 | 0/1 | 33/36 | 2/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | none | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| RENAULT | 2488 | 74272742 | 0/1 | 35/36 | 7/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libRENAULT_CALC.so, libRENAULT_COMM.so, libRENAULT_OLD.so, libRENAULT_SCAN.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| SAAB | 31 | 3953696 | 0/1 | 34/36 | 2/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libSAAB_OLD.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| SAICMG | 46 | 23713539 | 0/1 | 34/36 | 7/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | none | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| SAICROEWE | 47 | 22003435 | 0/1 | 34/36 | 7/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | none | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| SEAT | 73 | 65944685 | 0/1 | 35/36 | 1/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | none | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| SGM | 73 | 42172085 | 0/1 | 34/36 | 5/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libSGM_ACTF.so, libSGM_CALC.so, libSGM_CDSF.so, libSGM_COMM.so, libSGM_DTCF.so, libSGM_FILE.so, libSGM_OLD.so, libSGM_SYSP.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| SKODA | 68 | 70347681 | 0/1 | 35/36 | 1/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | none | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| SMART | 230 | 28418037 | 0/1 | 34/36 | 5/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | none | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| SPRINTER | 855 | 60951766 | 0/1 | 34/36 | 5/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libSPRINTER_CALC.so, libSPRINTER_COMM.so, libSPRINTER_FILE.so, libSPRINTER_OLD.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| SSANGYONG | 216 | 39931734 | 0/1 | 35/36 | 12/156 | 1: yes, 2: no, 3: yes, 4: no, 5: no, 6: yes | libSSANGYONG_ACTF.so, libSSANGYONG_CALC.so, libSSANGYONG_COMM.so, libSSANGYONG_DTCF.so, libSSANGYONG_FILE.so, libSSANGYONG_OLD.so, libSSANGYONG_SYSP.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| SUBARU | 111 | 24613174 | 0/1 | 36/36 | 7/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libSUBARU_ACTF.so, libSUBARU_CALC.so, libSUBARU_CDSF.so, libSUBARU_COMM.so, libSUBARU_DTCF.so, libSUBARU_FILE.so, libSUBARU_FRZF.so, libSUBARU_OLD.so, libSUBARU_SPEC.so, libSUBARU_SYSP.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| SUZUKI | 159 | 28573927 | 0/1 | 35/36 | 5/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libSUZUKI_ACTF.so, libSUZUKI_CALC.so, libSUZUKI_CDSF.so, libSUZUKI_COMM.so, libSUZUKI_DTCF.so, libSUZUKI_FILE.so, libSUZUKI_OLD.so, libSUZUKI_SYSP.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| USAFORD | 79 | 62472136 | 0/1 | 35/36 | 8/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libUSAFORD_ACTF.so, libUSAFORD_CALC.so, libUSAFORD_CDSF.so, libUSAFORD_COMM.so, libUSAFORD_DTCF.so, libUSAFORD_FILE.so, libUSAFORD_OLD.so, libUSAFORD_SPEC.so, libUSAFORD_SYSP.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| VAZ | 33 | 4260470 | 0/1 | 35/36 | 1/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libVAZ_ACTF.so, libVAZ_CALC.so, libVAZ_CDSF.so, libVAZ_COMM.so, libVAZ_DTCF.so, libVAZ_FILE.so, libVAZ_OLD.so, libVAZ_SYSP.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| VOLVO | 83 | 17560256 | 0/1 | 35/36 | 5/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libVOLVO_ACTF.so, libVOLVO_CALC.so, libVOLVO_CDSF.so, libVOLVO_COMM.so, libVOLVO_DTCF.so, libVOLVO_FILE.so, libVOLVO_SYSP.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| VW | 76 | 87237127 | 0/1 | 35/36 | 1/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | none | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| ZHONGTAI | 43 | 9016649 | 0/1 | 35/36 | 8/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | libZHONGTAI_OLD.so | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |
| ZHONGXING | 54 | 10683026 | 0/1 | 35/36 | 4/156 | 1: no, 2: no, 3: yes, 4: no, 5: no, 6: no | none | NOT ESTABLISHED | exploratory Toyota-table parse rate below 90% or not applicable |

¹ Named export order: 1. `SystemEnterPart`; 2. `GetDTCCodeFromAnsP3P4`; 3. `CanSetParameterNormal`; 4. `SendCmdAndGetDtcP3P4`; 5. `ReadDtcModeP3P4`; 6. `GetShowDtcInfo`

## Ranking

Descending decoded parsed/applicable rate, then discriminating overlap; no-applicable brands rank last. This is evidence, not a measure of enabled functionality.

| rank | brand | decoded parsed/applicable | discriminating overlap |
| ---: | --- | --- | ---: |
| 1 | HYUNDAI | 0/1 | 15 |
| 2 | KIA | 0/1 | 14 |
| 3 | SSANGYONG | 0/1 | 12 |
| 4 | LANDROVER | 0/1 | 11 |
| 5 | DAIHATSU | 0/1 | 10 |
| 6 | NISSAN | 0/1 | 10 |
| 7 | CHANGAN | 0/1 | 8 |
| 8 | DONGNAN | 0/1 | 8 |
| 9 | FLYER | 0/1 | 8 |
| 10 | FUTIAN | 0/1 | 8 |
| 11 | HMAZDA | 0/1 | 8 |
| 12 | LIFAN | 0/1 | 8 |
| 13 | LIUWEI_TY | 0/1 | 8 |
| 14 | MAXUS | 0/1 | 8 |
| 15 | MAZDA | 0/1 | 8 |
| 16 | USAFORD | 0/1 | 8 |
| 17 | ZHONGTAI | 0/1 | 8 |
| 18 | CITROEN | 0/1 | 7 |
| 19 | PEUGEOT | 0/1 | 7 |
| 20 | RENAULT | 0/1 | 7 |
| 21 | SAICMG | 0/1 | 7 |
| 22 | SAICROEWE | 0/1 | 7 |
| 23 | SUBARU | 0/1 | 7 |
| 24 | MITSUBISHI | 0/1 | 6 |
| 25 | OPEL | 0/1 | 6 |
| 26 | FERRARI | 0/1 | 5 |
| 27 | FIAT | 0/1 | 5 |
| 28 | INDIANMARUTI | 0/1 | 5 |
| 29 | JPISUZU | 0/1 | 5 |
| 30 | MASERATI | 0/1 | 5 |
| 31 | PORSCHE | 0/1 | 5 |
| 32 | SGM | 0/1 | 5 |
| 33 | SMART | 0/1 | 5 |
| 34 | SPRINTER | 0/1 | 5 |
| 35 | SUZUKI | 0/1 | 5 |
| 36 | VOLVO | 0/1 | 5 |
| 37 | ASTONMARTIN | 0/1 | 4 |
| 38 | AUSTHOLDEN | 0/1 | 4 |
| 39 | BMW | 0/1 | 4 |
| 40 | CHANGCHENG | 0/1 | 4 |
| 41 | CHANGHE | 0/1 | 4 |
| 42 | GM | 0/1 | 4 |
| 43 | HUACHEN | 0/1 | 4 |
| 44 | JACTY | 0/1 | 4 |
| 45 | JINLONG | 0/1 | 4 |
| 46 | KARRY_TY | 0/1 | 4 |
| 47 | QIRUI_TY | 0/1 | 4 |
| 48 | ZHONGXING | 0/1 | 4 |
| 49 | BXGM | 0/1 | 3 |
| 50 | CHRYSLER | 0/1 | 3 |
| 51 | INDIANTATA | 0/1 | 3 |
| 52 | HAFEI | 0/1 | 2 |
| 53 | JIAO | 0/1 | 2 |
| 54 | LUFENG | 0/1 | 2 |
| 55 | QOROS | 0/1 | 2 |
| 56 | SAAB | 0/1 | 2 |
| 57 | AUDI | 0/1 | 1 |
| 58 | BENZ | 0/1 | 1 |
| 59 | BXFIAT | 0/1 | 1 |
| 60 | INDIANMAHINDRA | 0/1 | 1 |
| 61 | ISUZU | 0/1 | 1 |
| 62 | LUXGEN | 0/1 | 1 |
| 63 | SEAT | 0/1 | 1 |
| 64 | SKODA | 0/1 | 1 |
| 65 | VAZ | 0/1 | 1 |
| 66 | VW | 0/1 | 1 |
| 67 | DAEWOO | 0/1 | 0 |
| 68 | HONDA | 0/0 | 6 |
| 69 | IVECO | 0/0 | 4 |
| 70 | AUTOSEARCH | 0/0 | 3 |
| 71 | EOBD2 | 0/0 | 1 |
| 72 | DEMO | 0/0 | 0 |
<!-- INVENTORY3 END -->
