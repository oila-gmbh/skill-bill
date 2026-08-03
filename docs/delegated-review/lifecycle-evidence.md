# SKILL-145 lifecycle evidence package

## Lifecycle contract

The durable lifecycle has four ownership components:

- coordinator: `coordinator_prepared`, worker selection, queue admission, and crash reconciliation;
- worker: `selected`, `queued`, `launched`, `running`, `completed`, `failed`, `timed_out`, `cancelled`, `unavailable`, or `invalid_output`;
- aggregation: `aggregation_started`, `aggregation_completed`, or `aggregation_failed`;
- terminal: `terminal_completed`, `terminal_failed`, `terminal_timed_out`, `terminal_cancelled`, or `coordinator_crashed`.

The following transitions are durable before the next ownership boundary: coordinator preparation,
each worker selection and queue admission, launch, running observation, worker terminal outcome,
aggregation start, aggregation outcome, and terminal persistence. `review_lifecycle_events` is the
recovery authority after interruption; the in-memory lane result is not.

## Evidence separation

`process_heartbeat` and `mcp_heartbeat` are liveness observations only. Provider output is retained
as a bounded byte count, outcome label, and SHA-256 reference. Declared specialist progress is a
provider assertion. Durable worker progress is a separately typed event and is the only evidence
that can satisfy a specialist-progress transition. Terminal completion is a separate event after
aggregation persistence.

The `ReviewLifecycleEvidenceSchema` excludes prompts, complete diffs, raw transcripts, and tool
logs. Diagnostic references identify bounded evidence without copying its content.

## Deterministic reproductions

`ReviewLifecycleEvidenceFixture` uses a fixed clock, an idempotent ledger, fake launcher/provider,
and fake aggregation. It covers workers launched without aggregation; interrupted, non-zero,
timed-out, unavailable, and invalid-output workers; missing worker results; complete worker results
followed by aggregation admission; and duplicate event replay, where the second write is a no-op
and conflicting evidence is rejected.

The production runner records the same boundaries through `ReviewLifecycleRecorder` and binds each
event to the packet digest, assignment digest, worker/provider identity, routed area, attempt,
process outcome, bounded diagnostic reference, and timestamp.

## Scope proof

The packet remains authoritative for caller-selected base and head revisions, changed hunks, and
packet digest. Each assignment carries the packet digest, assignment digest, exact paths and hunks,
and the baseline-untracked policy. `ReviewBaselineUntrackedPolicy` keeps included and excluded
baseline-untracked paths disjoint; changing that policy or either revision changes the packet and
assignment identity. Worker launch validation rejects a policy, path, hunk, revision, or lane
decision that differs from the packet.

## Failure admission

Interruption, non-zero exit, timeout, unavailable provider, invalid output, aggregation failure, and
missing results are explicit non-success lifecycle outcomes. `classifyReviewOutput` admits schema
repair only for a normally completed zero-exit process with an invalid result envelope. It rejects
process and lifecycle failure as repairable review output.

## Bounded evidence shape

The package carries only lifecycle event ids and sequence numbers, bounded timestamps, worker and
provider identities, packet and assignment digests, routed areas, process outcomes, separated
liveness/provider/declared/durable evidence, terminal status, and diagnostic references. It carries
no prompt body, complete diff, raw transcript, or tool-output log.

The historical disposition is in `failure-matrix.md`. Installation, routing, fallback, context,
and handoff items already covered by SKILL-144 or SKILL-146 are recorded there as existing evidence;
this subtask does not duplicate those implementations.
