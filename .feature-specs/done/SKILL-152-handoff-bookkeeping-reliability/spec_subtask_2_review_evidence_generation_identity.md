# SKILL-152 Subtask 2 — Producer evidence survives a review generation restart

Parent: `.feature-specs/SKILL-152-handoff-bookkeeping-reliability/spec.md` (unit 2)

## Scope

Give retained producer output evidence a key that stays unique when a review generation restarts, without losing the evidence the current key protects.

Two mechanisms disagree about what an attempt number means. `resetInvalidatedReviewGeneration` (`FeatureTaskRuntimeRunState.kt:158`) clears the review and implement-fix attempt watermarks so a fresh generation is not blocked as fix-loop exhausted before it launches — the comment at `:145` states that intent, and it must keep working. `producer_output_evidence` is keyed `(workflow_id, phase_id, attempt)`, has no generation dimension, and is deleted only by a retention sweep on `recorded_at`. So the counter rewinds and the evidence does not, and the next accepted review output at the rewound attempt fails its read-back equality check and raises `Conflict`.

Observed in the local runtime database:

```
durable phase record:  review  status=running  attempt_count=1
producer evidence:     review  attempt=1  sha cf794f6a0c
                       review  attempt=2  sha e630546f6d
```

The write-once guard at `SqliteRejectedOutputDiagnosticRepository.kt:113` is correct and stays. `INSERT OR IGNORE` plus read-back comparison is what makes an identical re-write idempotent and a differing one fatal. `INSERT OR REPLACE` or a delete would silently destroy the prior generation's evidence — the loss the guard exists to prevent. The fix is the key, not the guard.

The work:

- Add the generation dimension to the evidence identity so a restarted generation writing at attempt 1 does not collide with the prior generation's attempt 1. The runtime already tracks `currentReviewPassNumber` and `completedReviewPassNumber` in run state; prefer an existing durable generation identity over minting a new concept.
- Migrate the table under the `AGENTS.md` recipe: contract-version constant, parity test, typed error, loud-fail parse seams. Existing rows must map to a defined generation rather than being dropped.
- Reconcile workflows already in the collision-primed state on their next run, in-band, with no operator surgery and no evidence discarded.
- Keep the idempotent path intact: a retry producing byte-identical output must still be a no-op, and a genuinely reused key with differing content must still fail loudly.

## Acceptance Criteria

1. Retained producer output evidence is addressed by a key that stays unique across a review generation restart in which the attempt watermark rewinds.
2. Evidence retained by a prior generation is never overwritten, replaced, or deleted to resolve a collision.
3. `resetInvalidatedReviewGeneration` keeps clearing the attempt watermarks, and a restarted review generation is still not blocked as fix-loop exhausted before launch.
4. A retry producing byte-identical output to retained evidence remains idempotent.
5. A differing write to a genuinely reused key still raises a typed loud failure.
6. Workflows already carrying evidence at an attempt at or above the current watermark reconcile idempotently on the next run, without operator database surgery.
7. The table migration follows the `AGENTS.md` recipe and maps every existing row to a defined generation without loss.
8. A regression fixture drives a review generation restart over retained evidence through a real transition and proves the run advances.
9. Status, watch, and telemetry surfaces continue to expose no raw phase output as a result of this change.

## Non-Goals

- Building the durable review-generation and finding-disposition model, carrying Blockers across generations, or changing review approval rules; those are SKILL-150 subtask 4.
- Changing the evidence retention policy or its sweep.
- Changing fix-loop attempt caps or the watermark reset's purpose.
- Relaxing write-once evidence semantics.

## Dependency Notes

Independent of subtask 1; the two touch different seams and may land in either order.

Must not depend on SKILL-150 landing first. SKILL-150 subtask 4 AC-1 defines a review generation identity — workflow, review base, reviewed delta digest, pass number, repository checkpoint — that is exactly the missing dimension here, but SKILL-150's Non-Goals explicitly exclude the rejected-output and producer-evidence tables, so it will not fix this collision on its own. If that identity exists when this subtask is implemented, consume it. If not, use the generation signal already in run state and keep the seam narrow enough that SKILL-150 can adopt it later without a second migration.

## Validation Strategy

- Seed a workflow whose attempt watermark sits below its retained evidence, run it, and assert it advances with all prior evidence intact.
- Assert the idempotent and conflicting write paths separately: identical bytes are a no-op, differing bytes at a genuinely reused key still fail loudly.
- Drive the regression through a real generation-restart transition, not a direct repository call, since the seam that blocked is the run loop.
- Exercise the migration against a database snapshot carrying pre-migration evidence rows and assert no row is lost or duplicated.
- Then run:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

## Next Path

Final subtask of this feature. With subtask 1 complete, the parent acceptance criteria are fully covered.
