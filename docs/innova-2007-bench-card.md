# Bench card — 2007 Kijang Innova 2.7 petrol, no VSC

**Ready to run** with the verified pack that enables this exact system. Use
this read-only procedure only. This card is not evidence of a vehicle test.
Do not use the other Innova ECMECT variant as a substitute.

1. Connect the owner's approved adapter and confirm the vehicle is stationary
   and safely powered. Select **Toyota → Kijang Innova → TGN · -2009.07 →
   MINI VAN · w/o VSC**, then open **ECMECT** (`SYSLIST_EU.BIN@00023a75`).
2. Confirm ECMECT is enabled and labelled read-only, then use its read-only
   bring-up and review the selected ECU before starting. The first values are coolant
   temperature (`21 05`) and engine speed (`21 0C`); the calibration ID read
   is `09 04`. These are source read definitions, not proof of vehicle support.
3. If initialization fails, shows a timeout, lacks a complete valid `C1` reply,
   or reports a wrong-address/checksum reply, **stop**. A failed FAST init must **never fall back to the pilot** or its selector or
   headers.
   Do not retry with another vehicle profile, clear faults or perform writes.
4. In **Read-only ECU bring-up**, tap **Share bring-up report** after the run;
   use the app report share/export action as well. Include adapter model and
   firmware, exact vehicle and engine, app revision and pack hash, command/reply
   trace, timing and the first failing step. Share the export with the owner;
   redact personal information as appropriate.

The source join, source packets and compiled definitions do not establish
Toyota-to-MIC3313 equivalence or physical vehicle operation. Only an actual
owner bench capture can provide that evidence.
