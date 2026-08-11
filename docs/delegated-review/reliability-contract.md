# SKILL-145 delegated review reliability contract

> **Historical record (SKILL-159).** This file is the historical record of the
> external delegated-review subsystem that SKILL-159 removed. It is not current
> guidance. The live contract is
> [`orchestration/review-delegation/PLAYBOOK.md`](../../orchestration/review-delegation/PLAYBOOK.md).

**Contract:** `delegated_review_reliability` 0.1  
**Status:** enforceable baseline; promotion remains deferred  
**Authority:** the immutable review packet and assignment projection remain
authoritative for scope. This document governs lifecycle state around them.

## Boundary

The reliability contract is separate from the review-context contract. The
review-context schema owns caller-selected revisions, packet contents, lane
decisions, assigned paths and hunks, baseline-untracked policy, and the
forbidden-rediscovery surface. The lifecycle contract owns durable identity,
progress, worker execution, deadlines, terminal outcomes, aggregation, retry,
cancellation, and bounded diagnostics. A lifecycle record may reference a
packet or assignment digest, but it may not recreate or mutate their scope.

The current packet and assignment versions are `review-context` 0.8. The
lifecycle snapshot is `review-lifecycle` 0.1 and lifecycle evidence is
`review-lifecycle-evidence` 0.2. A version mismatch fails at the parse seam;
it is not repaired by interpreting an older record as a newer one.

## Identity and durable progress

Every durable record carries the following identity facts where applicable:

| Identity | Required binding | Retention rule |
|---|---|---|
| Review | `review_id`, packet digest, event sequence | Stable for one review lifecycle. |
| Packet | Packet digest, base revision, head revision, review revision | Resolved by the immutable packet; never rebuilt by a worker. |
| Assignment | Assignment digest, packet digest, lane, routed area | Immutable for an attempt; a changed assignment is a new digest. |
| Provider | Provider id, capability classification | Resolved through the provider registry; no cross-provider inference. |
| Worker | Stable worker id, provider id, assignment digest, area | Stable across observations; a retry increments the attempt. |
| Attempt | Positive attempt number plus assignment and worker identity | One current attempt may produce one terminal worker outcome. |
| Event | Event id, sequence, occurred-at timestamp | Event id is the idempotency key; conflicting replay is rejected. |
| Diagnostic | Bounded reference, summary, observed-at timestamp, optional digest | Reference-only; no raw prompt, diff, transcript, source body, or tool log. |

Durable progress is a typed `worker_progress` event containing a progress id,
label, timestamp, and the same packet/assignment/worker/attempt identity. A
process heartbeat, MCP heartbeat, file activity, stdout, provider output, or
completion token count is an observation only. It can be recorded as evidence
but cannot advance a worker or satisfy a deadline.

## Lifecycle and ownership

The coordinator owns preparation, selected assignments, queue admission,
capacity accounting, wave creation, and crash reconciliation. A worker owns
its launch, running observation, durable progress, and terminal result. The
aggregation owner admits only complete, identity-matching worker results. The
terminal owner persists the final classification after aggregation or failure
reconciliation.

Worker states are:

`selected -> queued -> launched -> running -> completed -> aggregated`

`selected`, `queued`, and `launched` may terminate as `cancelled` or
`timed_out`; `launched` and `running` may also terminate as `failed`.
`completed` is the only worker state eligible for aggregation. Terminal worker
states are not silently converted back to running. A coordinator crash is a
separate lifecycle outcome that reconciles incomplete rows to an explicit
terminal failure or a resumable attempt according to durable state.

The following boundaries are persisted before the next owner acts:

1. coordinator preparation;
2. worker selection and queue admission;
3. launch and running observation;
4. durable specialist progress;
5. worker terminal outcome and bounded result envelope;
6. aggregation start and aggregation outcome;
7. terminal completion, failure, timeout, cancellation, or crash outcome.

## Capacity and waves

The coordinator slot is reserved before worker admission. Every persisted
lifecycle snapshot stores positive `total_process_slots`,
`coordinator_slots`, and `worker_slots` values; the coherence rule is
`worker_slots = total_process_slots - coordinator_slots`, with
`coordinator_slots < total_process_slots`. `worker_slots` is stored even
though it is derived so that restart admission and wave prediction use the
same durable capacity rather than current process configuration. Selected
worker ids are unique. Predicted waves are deterministic chunks of the
selected set, with
`predicted_wave_count = ceil(selected_worker_count / worker_slots)`; actual
waves must contain exactly the same worker ids with no duplicate or missing
area and must reconcile to the persisted prediction. A retry of a completed
assignment is forbidden. A retry of an incomplete assignment gets a new
attempt and remains bound to the original assignment digest.

The snapshot records selected-area count, selected-worker count,
`total_process_slots`, `coordinator_slots`, `worker_slots`, predicted and
actual wave counts, worker records, wave membership, elapsed time, token
dimensions, process count, observable MCP startup count, completed-area count,
and lost-worker count. These are measurement facts, not proof of progress.

## Deadlines

All five deadline scopes use an injectable clock and are durable at terminal
reconciliation:

| Scope | Current bounded default | Expiry result |
|---|---:|---|
| Startup | 30 seconds | launch or preparation timeout |
| Progress idle | 120 seconds | worker timeout unless durable progress was persisted |
| Per worker | 30 minutes | timed-out worker and review failure |
| Aggregation | 30 seconds | blocked aggregation |
| Whole review | 30 minutes | deterministic whole-review timeout |

These defaults are an enforceable starting point, not promotion evidence. A
future governed change may revise them only with representative small,
medium, and multi-area measurements and falsifiable acceptance thresholds.
Cancellation always records the boundary at which it occurred: before launch,
during a worker, between waves, during aggregation, or before terminal
persistence. An expired lease or interrupted process is reconciled from the
durable row; process existence cannot extend the deadline.

## Terminal failures and aggregation

The terminal classification set is:

`completed`, `failed`, `timed_out`, `cancelled`, `blocked_unsupported`,
`blocked_aggregation`, `interrupted_before_launch`,
`interrupted_during_worker`, `interrupted_between_waves`,
`interrupted_during_aggregation`, and
`interrupted_before_terminal_persistence`.

Successful aggregation requires all of the following:

- the durable selected assignment set is non-empty and unchanged;
- exactly one completed zero-exit result exists for each selected assignment;
- worker, provider, packet, assignment, area, and current attempt identities
  match;
- every declared area is covered exactly once;
- every finding satisfies the bounded result-envelope schema; and
- aggregation completion is persisted before terminal completion.

Missing, duplicate, stale-attempt, provider-mismatched, invalid, or incomplete
results produce `blocked_aggregation` and a bounded diagnostic. They are not
successful output and cannot enter schema repair. Schema repair is permitted
only for a normally completed zero-exit response whose result envelope alone
is invalid; process failure, interruption, timeout, unavailable provider, and
aggregation failure are never repairable review output.

## Retry and cancellation idempotency

Event writes are keyed by `event_id`. Replaying the same event with the same
payload is a no-op; replaying the id with different evidence is rejected. An
assignment that has a durable completed result is not relaunched. A retry is
allowed only for an incomplete non-terminal assignment, increments `attempt`,
and cannot overwrite a prior attempt. Terminal reconciliation is atomic with
the final lifecycle classification, so a restart cannot expose a successful
summary whose lifecycle rows are incomplete.

Cancellation marks every still-active selected assignment deterministically,
records the cancellation boundary, and closes the coordinator or aggregation
row. A worker that races cancellation is admitted only if its current attempt
was durably completed before cancellation; otherwise cancellation wins and the
late result is rejected as stale.

## Provider isolation

Codex, Claude, and Cursor keep independent command builders, output decoders,
lifecycle callbacks, cancellation, timeout, and token strategies. The generic
process runner receives strategy objects and does not branch on provider
identity. Junie and Copilot are explicit unsupported
outcomes in the current registry; an explicit delegated request for one of
them terminates as unsupported and never falls back to inline review.

Provider observations are evidence for the provider's own classification. A
successful Codex run cannot promote Claude or Cursor, and a provider-specific
workaround cannot change an unchanged adapter.

## Bounded diagnostics and retention

Lifecycle evidence may retain event ids, sequence numbers, timestamps,
provider/worker/attempt identities, packet and assignment digests, routed
areas, process outcomes, bounded byte counts and hashes, capability labels,
wave membership, terminal status, and diagnostic references. Diagnostic
summaries and references are bounded to the schema limits. The contract never
persists prompts, complete diffs, source bodies, raw transcripts, or tool logs.

At terminal persistence, the lifecycle record stores
`terminal_persisted_at` and `evidence_expires_at`, where the latter is exactly
30 days after the former. A clock-driven persistence cleanup runs at startup
recovery and on the configured maintenance tick. For each terminal review at
or past `evidence_expires_at`, one transaction deletes its lifecycle evidence
and bounded diagnostic payloads, then records `evidence_pruned_at`; the
terminal classification, identities, aggregate status, and retention marker
remain. The delete and marker update are atomic per review, so a failed
cleanup rolls back both and a repeated cleanup is a no-op. Evidence is not
considered expired before the boundary, and schema or telemetry rejection
does not substitute for this deletion.

## Deterministic promotion sampling protocol

Promotion evidence is collected independently for each provider and adapter
digest. A provider sample set contains at least 20 launched canary runs for
each fixed size class (small, medium, and multi-area), for at least 60 runs in
total, collected within one 30-consecutive-UTC-day window that starts with the
provider's first launched canary. Samples are not pooled across providers,
size classes, adapter digests, or windows; a changed adapter starts a new set.
The fixture, declared-area count, lifecycle contract version, runner build,
and authenticated provider identity are recorded with each bounded sample.

Every launched canary remains in the sample denominator. A failed, timed-out,
cancelled, interrupted, or blocked canary counts toward the required sample
count, is never removed or replaced by a retry, and makes the provider
promotion set fail. A retry is a separately identified sample and does not
erase the original outcome. For the latency calculation, use elapsed
milliseconds through the durable terminal outcome; a watchdog timeout uses
the applicable deadline as its elapsed value. This treatment prevents
discarding slow or failed attempts from improving p95.

For a class with `n` samples, sort all terminal elapsed values in ascending
order and use the nearest-rank estimator
`p95 = value[ceil(0.95 * n)]` with one-based indexing. The protocol records
`n`, window start/end, estimator, included terminal classes, and the ordered
sample ids. A provider cannot enter promotion evaluation until all three
classes meet the minimum count and window rule, and one run can never satisfy
that precondition.

## Fixed promotion measurement bounds

Promotion measurements use these fixed, inclusive size classes and p95 elapsed
time limits:

| Review size | Declared areas | Maximum p95 elapsed time |
|---|---:|---:|
| Small | 1–2 | 120 seconds |
| Medium | 3–5 | 300 seconds |
| Multi-area | 6 or more | 600 seconds |

The evidence-retention gate is also fixed per review: at most 256 lifecycle
evidence events and at most 1,048,576 aggregate UTF-8 bytes may be retained,
and retained evidence expires no later than 30 days after terminal persistence.
Individual diagnostic references remain limited to 200 characters and
diagnostic summaries to 500 characters by the lifecycle evidence schema. A
missing size-class sample, missing retention measurement, or value above any
limit fails the promotion gate; it is never treated as a pass by omission.

## Promotion gate

Promotion from experimental explicit opt-in to supportable requires independent
authenticated evidence for the provider and all of these falsifiable checks:

1. the sampling precondition above is satisfied, and p95 elapsed time computed
   by the nearest-rank protocol is at most 120, 300, or 600 seconds for small,
   medium, or multi-area reviews respectively;
2. 100% of declared areas are covered exactly once;
3. zero selected workers disappear without a durable terminal status;
4. every run reaches one deterministic terminal classification;
5. every review remains at or below 256 evidence events, 1,048,576 aggregate
   evidence bytes, and 30 days of retention, with each diagnostic reference
   and summary within its schema limit; and
6. provider-isolation fixtures show no change to other providers' strategies.

Until a separate governed change records those measurements and approves the
gate, delegated remains the default and `auto` resolves by pass number: pass one
and pass-number-free scopes to delegated, follow-up and remediation passes to
inline.
