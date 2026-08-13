# SKILL-189 · Subtask 5 — Regression, conformance, and contract documentation

## Scope

Correct the contract documentation the topology contradicts.

This subtask was originally specified as the regression and conformance suite
proving subtasks 1–4 against the SKILL-16 incident shape. The repository
prohibits new tests (`AGENTS.md`, "Testing"), and the owner confirmed that rule
holds here, so the coverage half is deliberately not implemented. What remains
in scope is the documentation half.

- Documentation corrections in the runtime contract and the runtime skill.

Not implemented, by decision rather than oversight: ACs 1–10 and 13 below. They
are retained as the written record of what would prove this feature, and as the
starting point should the test rule ever be relaxed. Subtasks 1–4 are therefore
verified by construction and by the existing suites continuing to pass, which
catches regressions in old behaviour but not defects in new behaviour.

## Acceptance Criteria

ACs 1–10 and 13 are **not implemented** — see Scope. ACs 11, 12, and 14 are
implemented.

1. NOT IMPLEMENTED. A regression test replays the SKILL-16 shape: three rounds patching the same
   recovery path, each round's finding a defect in the previous round's remedy.
   The fourth round is told which finding's constructs it is disturbing before
   it edits.
2. NOT IMPLEMENTED. A regression test proves an `implement_fix` round that silently removes a
   construct recorded as another finding's remedy is rejected, and that a round
   which removes it while stating the disturbed finding and rationale is
   accepted.
3. NOT IMPLEMENTED. A regression test proves a genuine new defect in remediation-authored code
   still produces an advance-blocking finding and still reopens the loop.
   Repair memory must not suppress real defects.
4. NOT IMPLEMENTED. A regression test proves review scope is unchanged across rounds: the
   reviewed delta stays `diff(pre-fix tree -> post-fix HEAD)` under the
   workflow-owned pathspec while the carried context grows.
5. NOT IMPLEMENTED. A regression test proves the churn pause fires on a changing finding set
   against ledger-recorded constructs, and that the pre-existing
   identical-findings-plus-unchanged-digest pause still fires unchanged.
6. NOT IMPLEMENTED. A regression test proves a `plan_fix` escalation verdict pauses with the
   ledger as evidence, does not advance to `implement_fix`, and does not mutate
   `preplan` or `plan` outputs.
7. NOT IMPLEMENTED. Privacy conformance asserts that no goal-facing status, pause reason,
   telemetry event, PR description, generated file, or ordinary log contains a
   diff hunk, line number, source body, or raw review output originating in any
   projection added by this feature.
8. NOT IMPLEMENTED. Budget conformance asserts every new projection enforces its declared UTF-8
   byte and collection limits, and that an oversized ledger degrades to an
   explicitly marked summary rather than a silent truncation.
9. NOT IMPLEMENTED. Crash-safety conformance asserts resume correctness for a death inside
   `plan_fix`, inside `implement_fix` after a partial receipt, and between a
   round's receipt and the next review reservation.
10. NOT IMPLEMENTED. Loop-accounting conformance asserts `review_fix` iteration counts, the
    advisory warning threshold, and finished telemetry are semantically
    unchanged by the added phase.
11. The stale backward-edge documentation claiming *"Major, Minor, and Nit
    findings never produce `changes_requested`"* is corrected to match
    `blocksAdvance`, which is Blocker or Major.
12. `skills/bill-feature-task-runtime/content.md` describes the loop as
    `review -> plan_fix -> implement_fix -> review`, documents the repair
    ledger and its status vocabulary, and documents the churn pause alongside
    the existing non-convergence pause.
13. NOT IMPLEMENTED. Tests use synthetic sentinel findings and construct names. No real rejected
    output, source body, database path, or company identifier appears in any
    fixture, spec, or generated artifact.
14. The runtime check suite passes.

## Non-Goals

- No new runtime behavior; this subtask documents subtasks 1–4.
- No performance benchmarking of the remediation loop.
- No backfill of receipts or ledger entries for historical workflows.

## Dependency Notes

Depends on subtasks 1, 2, 3, and 4. Covers the runtime only; the IDE status
field and its plugin consumer are proved by subtask 6, which is independent of
this one and may land in either order.

## Validation Strategy

```bash
cd /home/sermilion/StudioProjects/skill-bill/runtime-kotlin
./gradlew check -x sourcesJar
```

The full runtime check suite is the gate. With the coverage half not
implemented, it proves only that the documentation corrections break nothing.

## Next Path

Runtime work complete. Subtask 6 carries the pause to the IDE. Once both have
landed, reconcile the parent spec and manifest to their final state, then
observe `review_fix` iteration counts and pause frequency on subsequent goal
runs to decide whether a dedicated `replan_subtask` operator decision is
warranted.
