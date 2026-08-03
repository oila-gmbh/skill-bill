# Follow-up specification: delegated review domain

**Order:** 2 of 9  
**Depends on:** `spec_followup_schema.md`  
**Purpose:** encode lifecycle ownership and invariants in pure domain types.

## Scope and targets

- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/review/model`
- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/review`
- `runtime-kotlin/runtime-domain/src/test/kotlin/skillbill/review`

The domain owns selected, queued, launched, running, completed, failed,
timed-out, cancelled, and aggregated states; transition ownership; coordinator
and worker capacity; deterministic waves; five deadline scopes; terminal
failure classes; retry identity; cancellation reconciliation; and exact
aggregation invariants. Durable state is authoritative. Process existence, MCP
activity, CPU activity, stdout, and heartbeats are observations only.

## Required behavior

Use an injected clock for startup, progress-idle, per-worker, aggregation, and
whole-review deadline decisions. Require a coordinator slot and unique selected
assignments. Persist `total_process_slots`, `coordinator_slots`, and
`worker_slots` in the capacity value object and reject any snapshot where
`worker_slots != total_process_slots - coordinator_slots`. Predict waves as
`ceil(selected_worker_count / worker_slots)` from that durable value object;
predicted and actual waves must cover the same selected workers without
omission or duplication. A completed assignment cannot be relaunched; an
incomplete retry increments the attempt and preserves scope identity.

Aggregation accepts only a complete current-attempt result set with one owner
per declared area and valid bounded findings. Missing, duplicate, mismatched,
or invalid results are terminal aggregation failures.

## Test matrix

Positive cases cover every normal transition, one-lane and multi-wave plans,
capacity round-trips after restart, all deadline scopes, idempotent completed
retries, cancellation at each boundary, and exact successful aggregation.
Negative cases cover illegal transitions, missing or incoherent capacity
fields, a predicted-wave formula mismatch, missing coordinator capacity,
duplicate workers or areas, deadline expiry without durable progress, stale
attempts, partial coverage, provider mismatch, and aggregation after a worker
failure.

## Exclusions

Do not select areas, redesign routing, add inline fallback, or treat provider
heartbeats as domain progress.
