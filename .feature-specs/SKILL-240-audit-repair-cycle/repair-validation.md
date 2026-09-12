# Audit repair validation

Validated on 2026-09-12 against the repaired working tree on `feat/SKILL-240-audit-repair-cycle`. This records source validation. It does not mark the original runtime child accepted.

## Repairs

| Investigation area | Changed behavior |
| --- | --- |
| Session ownership and replay | Provider identity bootstrap validates the active workflow and worker fence without requiring the identity being recorded. Structured provider start events persist the identity once. Stage access and replay validate ownership inside the transaction. Codex uses noninteractive resume, streaming providers expose identity before settlement, and callback failure terminates the child. |
| Checkpoint identity | Git captures the engine-owned implementation scope and repair paths with an isolated index. The cycle retains an immutable commit and content fingerprint. Broad repository activity remains a separate diagnostic value. Reconciliation verifies retained content without rebinding the intent to newer edits. |
| Final eligibility | Ordinary, carried and restored completion require the exact durable final assessment and current retained content. Missing run invariants cannot disable the audit cycle. Compatible completed legacy records remain readable. |
| Recovery | The runtime rebinds ownership before composing prompts, restores versioned evidence and preserves producer identity. Diagnosis, partial repair, pending checkpoint and final audit interruptions have explicit recovery transitions. A satisfied cycle can settle without another agent launch. |
| Progress and operator decisions | File churn does not establish AC progress. Repeated unresolved ACs or unchanged repair content pause the run. Stage advance, pause publication, progress and retry grant consumption commit together. Replay preserves later operator decisions. Counts represent repair rounds rather than stage revisions. |
| Status and telemetry | Status reads workflow artifacts and the explicitly owning cycle from one SQLite snapshot. Each stage change writes a payload-free event into the transactional outbox. Replay creates no duplicate event, and an outbox write failure rolls back the state change. |
| Audit instructions | Audit owns diagnosis, authorized repair and final AC assessment in the same session. Recovery carries earlier evidence, pending validation and repair identities. Stage rejection and paused acknowledgements stop further edits. |

The full CLI path also exposed a lease-phase mismatch. Worker leases retain the phase at acquisition, so a worker that starts at preplan must still be able to enter audit. Audit authorization now checks the workflow's current phase and the live ownership fence separately.

## Validation

Ran the Kotlin pack command from `runtime-kotlin` after the final source change:

```sh
./gradlew check --continue --parallel -q --warning-mode none
```

The command exited 0. Test reports contain 5,091 tests, zero failures, zero errors and three skips. Formatting, static analysis and architecture checks passed. `git diff --check` passed.

Four new boundary tests use temporary Git repositories and the production SQLite adapter. They cover dirty implementation retention, unrelated staging preservation, exact final eligibility, interrupted repair recovery, retry grant rollback, expired ownership, unchanged repair pauses and transactional outbox replay. Three provider tests cover structured session identity, noninteractive resume and child cleanup after a persistence callback failure. CLI fixtures now complete audit through the actual durable stage command instead of returning only a satisfied envelope.

## Existing workflow

At the time of source validation, the original child `wftr-20260912-111118-r9i0` had not been resumed, reset or accepted. The later authorized hard reset removed that child and its shared and child planning checkpoints after preserving the workflow database, code and Git checkpoint references. The parent still holds the original one-subtask durable manifest. The regenerated three-subtask bundle needs supported runtime registration before fresh planning and execution. Source validation does not establish acceptance through a live agent run.

## Spec regeneration

Regenerated the parent spec, all three subtask specs and the pending manifest on 2026-09-12. Preserved every acceptance criterion and its numbering. The production manifest schema and coherence validator accepted the complete bundle before file replacement. The production run-invariants reader extracted 10 parent criteria and 9, 10 and 8 subtask criteria. Every subtask has scope, non-goals, dependency notes, validation strategy and a next path. All workflow IDs and commit references are empty.

This preparation changes specification files only. It does not register the replacement manifest in the existing durable parent or launch a goal.
