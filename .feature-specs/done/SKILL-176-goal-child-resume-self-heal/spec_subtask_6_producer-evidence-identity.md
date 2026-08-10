# SKILL-176 · Subtask 6 — Producer-evidence identity across attempts and agents

## Scope

Stop a producer-output evidence write from crashing the run when the phase attempt it names already holds different immutable output.

Primary sites:

- `runtime-infra-sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/SqliteRejectedOutputDiagnosticRepository.kt` — `retainProducerOutput` (line 114): `INSERT OR IGNORE` on `(workflow_id, phase_id, generation, attempt)`, read-back at line 136, and the sha / byte-size / payload comparison throwing `RejectedOutputDiagnosticError.Conflict` at line 146.
- `runtime-application/src/main/kotlin/skillbill/application/featuretask/RejectedOutputDiagnosticService.kt` — `retainProducerOutput` (line 63) and `producerEvidenceValidator`.
- `runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimePhaseRecorder.kt` — `retainProducerOutput` (line 181) and `producerOutput` (line 186), whose read uses the same four-part key.

`agent_id` and `model` are persisted on the row but are not part of the identity. The write is neither idempotent nor last-writer-wins: `INSERT OR IGNORE` keeps the existing row, the read-back returns the *previous* producer's payload, the comparison fails, and the error propagates as a crash.

Observed on child `wftr-20260808-175505-c5po`. The key `review:0:2` was held by a claude pass recorded 2026-08-08T18:49:48Z at 14382 bytes, sha `8a5dfb56fd3d`. A relaunch under cursor re-entered review at attempt 2, produced different output, and crashed while storing evidence — after review preparation had succeeded. The run only escaped because a later relaunch advanced the attempt counter to 3, leaving `review:0:3` free; nothing fixed the collision.

Re-entering an attempt number without advancing it makes the collision reachable. Switching agent mid-run makes it certain, since different output for one key is precisely what a different producer yields. Both are ordinary occurrences on a resumable runtime.

The resolution is a design decision this subtask must make and record: widen the identity to include the producer, make retention idempotent per producer, or define a deterministic outcome for a contested key. Whichever is chosen, evidence must stay immutable once written — the point is that a second producer's evidence has somewhere to go, not that the first producer's evidence can be overwritten.

## Acceptance Criteria

1. Retaining producer-output evidence for a phase attempt that already holds different immutable output does not throw out of the phase-recording path or terminate the run.
2. Evidence already written remains immutable and byte-identical; no path overwrites a retained payload in place.
3. A second producer's output for a contested attempt reaches a defined durable outcome, and that outcome is reconstructable afterward — which producer wrote what, for which attempt.
4. Retaining byte-identical evidence for the same key stays idempotent and remains a silent no-op.
5. `producerOutput` reads resolve to the evidence belonging to the producer being asked about, so the diagnostic a consumer reads is the one its own attempt generated.
6. The chosen resolution is recorded in the runtime area's boundary decision log, stating why evidence identity does or does not include the producer.
7. A regression test seeds evidence at `review` generation 0 attempt 2 under one agent, then retains different output for the same key under another, and asserts the run continues; it fails against the pre-fix runtime.
8. Any change to the evidence key leaves rows written by the current runtime readable, and existing retention and expiry behavior unchanged.

## Non-Goals

- Changing how attempt numbers are allocated or when a resume advances the counter. That would hide this collision rather than resolve it, and the identity is wrong regardless of counter behavior.
- Changing evidence retention windows, expiry, or the cleanup path.
- Relaxing evidence immutability.

## Dependencies

None. Independently landable.

## Validation Strategy

- Reproduce from the observed shape: same workflow, phase `review`, generation 0, attempt 2, differing agent and payload.
- Assert the pre-existing row is byte-identical after the second retention, since a fix that resolves the crash by overwriting is a worse defect.
- Idempotency test retaining identical bytes twice, asserting no error and no duplicate.
- A read test proving `producerOutput` returns the correct producer's evidence once more than one exists for an attempt.

## Next Path

Feature complete once subtasks 1 through 6 land. Verify against the parent spec acceptance criteria.
