# Follow-up specification: lifecycle and reliability schemas

**Order:** 1 of 9  
**Depends on:** the current packet/assignment contract and this decision  
**Purpose:** make the lifecycle envelope versioned, strict, loud-failing, and
separate from immutable review-context contents.

## Scope and targets

- `orchestration/contracts/review-lifecycle-schema.yaml`
- `orchestration/contracts/review-context-schema.yaml` (reference only; packet
  and assignment scope remains authoritative)
- `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/contracts/review`
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/contracts/review`

The implementation must add or preserve explicit envelopes for lifecycle
identity, worker state, attempt, provider capability, coordinator slot,
predicted and actual waves, all five deadline scopes, terminal class,
aggregation completeness, retry/cancellation reconciliation, and bounded
diagnostic reference. The lifecycle snapshot must persist
`total_process_slots`, `coordinator_slots`, `worker_slots`,
`selected_worker_count`, `predicted_wave_count`, and `actual_wave_count`.
`worker_slots` is durable even though it is derived so restart admission does
not depend on live process configuration. Packet and assignment schema changes
are prohibited unless a separate governed context-contract change approves
them.

Capacity coherence is validated at both write and read seams:

- `total_process_slots` and `coordinator_slots` are positive integers,
  `coordinator_slots < total_process_slots`, and
  `worker_slots = total_process_slots - coordinator_slots`;
- `selected_worker_count` equals the unique selected assignment count and is
  positive;
- `predicted_wave_count = ceil(selected_worker_count / worker_slots)`;
- actual wave membership contains each selected worker exactly once, and its
  count reconciles to the persisted predicted count when the plan completes.

The persisted values are authoritative after restart; a changed runtime
configuration cannot silently recompute capacity or predicted waves.

## Acceptance and rejection cases

Accept a current contract version with digest-bound packet, assignment,
provider, worker, and attempt identity; positive timestamps; unique event and
assignment ids; complete wave accounting; positive deadline limits; valid
terminal transitions; exact aggregation coverage; and diagnostics within the
declared byte and character limits. Accept a coherent persisted capacity
snapshot with all six capacity/wave fields above, including the formula and
restart round-trip values. Accept terminal retention metadata with
`terminal_persisted_at`, `evidence_expires_at` exactly 30 days later, and a
nullable `evidence_pruned_at` that is populated only after cleanup. Promotion
fixtures use at least 20 launched canaries per provider and size class (60 per
provider) within one 30-consecutive-UTC-day window. They use the nearest-rank
p95 estimator over every launched sample, including failed or timed-out
samples; any such non-completed sample rejects promotion and cannot be
replaced. Small reviews have a 120-second p95 limit, medium reviews of 3–5
areas have a 300-second limit, and multi-area reviews of 6 or more areas have
a 600-second limit. Evidence retention is at most 256 lifecycle events and
1,048,576 aggregate UTF-8 bytes per review, expiring within 30 days of
terminal persistence.

Reject stale or unknown versions, missing identity, malformed digests or
timestamps, duplicate worker/area ownership, zero or negative capacity,
missing capacity fields, a mismatched `worker_slots` formula, invalid wave
counts or membership, unknown deadline scope, impossible transition,
incomplete aggregation, stale retry attempt, mismatched provider, and
oversized or raw-content diagnostics. Reject missing or incomplete
provider/size-class sample sets, a window outside 30 consecutive UTC days,
an estimator other than nearest-rank, a p95 latency above its fixed bound,
any failed or timed-out canary, an evidence count above 256, aggregate
evidence above 1,048,576 bytes, or retention older than 30 days. Reject
`evidence_expires_at` values other than terminal persistence plus 30 days and
reject a pruned marker that has no completed cleanup transaction. Reject
prompt, complete-diff, source-body, transcript, and tool-log fields through
strict additional-property validation.

## Contract and parity work

The schema must declare its `contract_version` and schema id. Kotlin exposes a
matching version constant and classpath/repository paths, a typed invalid-schema
exception, and one shared validation function used at both producer and
consumer seams. A configuration-cache-friendly resource copy task must guard
missing schema resources at execution time. Schema version drift, malformed
fixtures, and consumer repair attempts fail loudly; they do not silently
coerce legacy lifecycle records. The persistence mapping must round-trip each
durable capacity field and retention timestamp without deriving missing values
from current configuration or wall-clock time at read time.

## Exclusions

Do not conflate lifecycle state with packet contents. Do not change SKILL-144 or
SKILL-146 routing, fallback, assignment, least-context, or installation
contracts. Do not persist prompts, full diffs, source bodies, raw transcripts,
or tool logs.
