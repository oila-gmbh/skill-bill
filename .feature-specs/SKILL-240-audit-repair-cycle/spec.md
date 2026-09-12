# Issue SKILL-240: Self-contained audit-and-repair cycle

## Intended Outcome

An audit cycle diagnoses every unmet acceptance criterion, repairs authorized gaps in the same provider session, retains the repaired repository content and assesses every criterion again. Only the exact satisfied final assessment may release review and validation. Repeated gaps must produce an actionable pause rather than another unbounded repair loop.

## Acceptance Criteria

1. A remediation cycle audits the repository and records every unmet acceptance criterion before applying repairs.
2. The same cycle repairs the recorded gaps without handing the diagnosis to an unrelated agent session.
3. The cycle re-audits the repaired tree and settles as satisfied only when the final tree satisfies every acceptance criterion.
4. Repair claims cannot substitute for the final audit's repository evidence.
5. Pre-repair and post-repair repository checkpoints, audit results, and repair outcomes remain durable and attributable to the cycle.
6. A cycle that makes no measurable progress pauses or fails through the existing operator-action path instead of retrying indefinitely.
7. Review and validation start only after the final audit settles as satisfied, and audit-repair failures remain distinguishable from review and validation failures.
8. Existing audit-gap and valid completed workflow records remain readable through the established versioned-contract behavior.
9. Focused runtime, persistence, and status tests cover complete repair, unresolved gaps, no-progress, interruption, resume, and final re-audit behavior.
10. The touched modules pass the dominant-stack quality check.

## Scope

Complete and verify the existing audit-repair implementation across provider launch, stage ownership, recovery, Git retention, final eligibility, status and telemetry. Use three dependency-ordered subtasks. Each subtask checks its full acceptance census against the current tree and repairs any remaining defect within its scope.

## Current Baseline

This bundle was regenerated on 2026-09-12 after the source repairs and an authorized hard reset. The branch is feat/SKILL-240-audit-repair-cycle. Existing changes are uncommitted and have been installed locally.

[investigation.md](investigation.md) records the earlier defects. It is historical evidence, not a current failing-test list. [repair-validation.md](repair-validation.md) records the repaired source check, 5,091 tests, zero failures or errors and three skips. That receipt establishes the tested working-tree baseline; it does not accept these regenerated subtasks or replace their final repository audits.

Do not replay the old implementation plan as if none of the fixes existed. Inspect the current owners, preserve working fixes and produce fresh evidence for the requirements below. Tests may pass while an acceptance obligation still lacks proof.

## Constraints

- Preserve complete acceptance-criteria coverage and stable repair identities through interruption and resume.
- Keep repair claims separate from repository evidence. Preserve pending validation obligations through final settlement.
- Retain runtime-owned implementation and repair content without modifying the user's index, HEAD or unrelated files.
- Preserve one owner for coupled state changes and the existing operator decisions. Do not introduce an arbitrary new iteration cap.
- Keep platform behavior manifest-driven and audit failures distinct from review and validation failures.
- Preserve versioned compatibility for valid completed records. Missing or incompatible active authority must fail through typed recovery.

## Non-Goals

- Changing acceptance-criteria extraction, feature-spec authoring or platform discovery.
- Replacing review, verify-findings or validation.
- Repairing unrelated worktree changes or automatically accepting unmet criteria.
- Adding a provider family, telemetry backend, generic event framework or new checkpoint retention policy.

## Delivery Plan

| Subtask | Outcome | Dependencies |
| --- | --- | --- |
| 1 | Durable session ownership, recovery and operator control | None |
| 2 | Retained content checkpoints and final audit eligibility | 1 |
| 3 | Consistent audit status, durable telemetry and assembled acceptance | 1 and 2 |

Subtask 1 establishes the stage transaction and continuation contract that checkpoint recovery consumes. Subtask 2 establishes retained content and final eligibility that status and assembled acceptance consume. Subtask 3 closes those observable outcomes against the settled contracts. These are executable acceptance boundaries, with tests in their owning subtask. They are not a requirement to recreate already working code or split existing repairs mechanically by file.

## Acceptance Ownership

Parent criterion numbering remains unchanged.

| Parent AC | Owner |
| --- | --- |
| 1, complete diagnosis | Subtask 1 |
| 2, same-session repair | Subtask 1 |
| 3, final repaired-tree audit | Subtask 2 |
| 4, repository evidence over claims | Subtask 2 |
| 5, durable attributable evidence | Subtasks 1 and 2 |
| 6, measurable progress and operator pause | Subtask 1 |
| 7, downstream gating and distinct failures | Subtasks 2 and 3 |
| 8, versioned compatibility | Each changed contract owner in subtasks 1 and 2 |
| 9, behavioral coverage | Each subtask, assembled in subtask 3 |
| 10, quality checks | Each subtask's configured gate and parent full-quality evidence in subtask 3 |

## Execution-State Preparation

The authorized hard reset removed the original child wftr-20260912-111118-r9i0 and the old shared and child planning checkpoints. It left parent wftr-20260912-110650-2ev1 pending with its original one-subtask durable manifest. Reset therefore did not register this three-subtask bundle.

Pre-reset code, workflow database, specifications and checkpoint references are preserved at /home/sermilion/.skill-bill/recovery/SKILL-240-before-replan-20260912T165536Z. That archive is recovery evidence; no new subtask may reuse the deleted child's immutable acceptance census or claim its attempts as acceptance of the new scope.

All three entries in this prepared manifest are pending with no workflow IDs, commits or inherited outcomes. Before launching, register this exact decomposition through a supported runtime state-management operation and verify all three names, spec paths and dependencies. Then generate a fresh shared preplan and a fresh plan for every subtask. A file edit alone does not update the existing durable parent. Do not silently launch its one-subtask state or hand-edit SQLite to simulate adoption.

## Validation Strategy

Each subtask begins with its named failure cases and inspects existing test coverage before adding tests. Use the production SQLite adapter, real temporary Git repositories and actual provider command/event boundaries where those mechanisms determine acceptance. Assert observable outcomes and recovery after durable boundaries rather than mocked acknowledgements.

The full Kotlin quality command, run from runtime-kotlin in a validation-authorized context, is:

```sh
./gradlew check --continue --parallel -q --warning-mode none
```

A build-only goal phase remains build-only. Before parent completion, identify the final source state covered by focused regressions and full-quality evidence. Subtask 3 must account for every parent criterion and every missing proof. Historical receipts cannot establish a later changed tree.

## Next Path

Register and verify the three-subtask durable manifest, regenerate shared and child planning, then run:

```sh
skill-bill goal SKILL-240 --agent codex
```
