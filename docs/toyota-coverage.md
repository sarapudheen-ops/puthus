# Toyota conversion coverage: accounting and delivery plan

This is a plan, not a report of newly converted features. The source-record
accounting helpers introduced for M0 are not yet wired into pack generation or
verification. No physical vehicle validation is implied.

## Accounting boundaries

Feature counts count executable objects; source coverage counts distinct source
record identities. They are different units: one definition can cite several
records and one source record can justify several definitions. Do not add feature
counts to obtain a source-record total. Unknown denominators must remain unknown,
not zero. For any claimed converted source record, the verifier must establish a
reachable executable definition, not merely trust a definition identifier in a
ledger. A terminal policy reason must be backed by the source evidence appropriate
to that reason; an allow-listed label alone does not prove applicability.

System-level coverage can overlap across systems because source records may be
shared. A corpus-wide summary must deduplicate by source identity and detect
conflicting dispositions, rather than summing system totals. Report any unresolved
scope or incomplete attribution explicitly. The verifier must independently
recompute the summary from the pinned data, and a version change must make older
objects fail closed rather than interpret missing fields as zero.

## Container budget and sharding plan

The last documented container is 466,298,841 bytes against the 512 MiB
(536,870,912-byte) cap: 70,572,071 bytes of nominal headroom, before any
additional coverage metadata or definitions. The generated index remains capped
at 256 KiB, each object at 8 MiB JSON / 4 MiB compressed, and the private cache
at 128 MiB. These numbers are limits and a historical measurement, not a fresh
measurement of the current branch.

Before expanding executable coverage, measure a full verified CI conversion,
including index, object and compressed-container sizes. If projected growth
threatens the cap, shard by region or manifest group, choosing boundaries from
measured distribution rather than assuming equal sizes. Version the index to pin
an immutable hash and size for each shard and associate every range with its
owning shard. Update the writer, independent verifier, Android index reader,
range loader, cache keys, publication and delivery checks together. Reject missing
or ambiguous shard references, range overflow and hash mismatches; test offline
reopen, cancellation and old-index rejection. Keep previously published
content-addressed containers available for older APKs and do not silently raise
any cap. CI must report the actual per-shard and total sizes.

## Evidence status

Source semantic equivalence, adapter equivalence and physical vehicle validation
are separate claims. CI exercises format and scripted control flow; it cannot
substitute for an owner-provided bench capture. Until such evidence is attached,
Toyota-to-MIC3313 equivalence and real-vehicle operation remain unproven.
