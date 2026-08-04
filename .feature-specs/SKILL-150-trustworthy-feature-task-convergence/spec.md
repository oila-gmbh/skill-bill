# SKILL-150 Trustworthy Feature-Task Convergence

## Status

Rewritten 2026-08-04 against current `main`. The original seven-subtask design was
authored before SKILL-153 (phase-output structural repair), SKILL-155 (typed
bounded DB failures), SKILL-157 (unbounded Blocker remediation, no review pass
cap), and SKILL-159 (delegated-review subsystem removal) landed, and much of it
answered questions the runtime no longer asks. This rewrite keeps the three
problems that remain unsolved on `main` and drops the rest:

- Dropped: the generic eight-kind convergence-record ledger (original subtask 1) —
  each surviving subtask owns the narrow durable slice it needs instead.
- Dropped: lossless review generations (original subtask 4) — it existed to make
  capped-verdict reopening safe; SKILL-157 removed the cap, settlement is
  disposition-driven, and SKILL-142 already routes Major and lower into the
  unaddressed-findings ledger.
- Dropped: adaptive sizing and the pre-review quality seam (original subtask 6) —
  a separate feature, not a convergence guarantee; propose independently if wanted.
- Dropped: the migration/e2e umbrella (original subtask 7) — each subtask now
  carries its own migration and end-to-end obligations.

Salvage: the parked branch `quarry/SKILL-150-convergence-rebased` (rebased onto
`main` at `50506d4c`-era, compiles, tests unreconciled) contains prior
implementations of all three surviving subtasks — truthful completion
(`94a94fe4`, `ed746dbd`), audit repair convergence (`77d32753`, `e051f2cd`),
scoped checkpoint isolation (`2f41e027`), and audit-convergence migrations
renumbered to the 23–28 range. Treat it as a code quarry: salvaged code must be
re-validated against the acceptance criteria below, never bulk-merged. The
remote `feat/SKILL-150-trustworthy-feature-task-convergence` still holds the
pre-rebase history.

## Mode

decomposed

Three dependency-light units:

1. Truthful implementation completion — a phase cannot settle `completed` while
   plan tasks remain open.
2. Audit repair convergence — durable, recurrence-aware audit gap and repair
   history with closure-complete re-entry. Depends on unit 1's completion gate.
3. Scoped checkpoint isolation — checkpoints stage only workflow-owned paths and
   never absorb foreign work. Independent of units 1–2.

## Intended Outcome

Feature-task implementation and completeness audit converge from durable evidence
instead of agent claims or replaceable snapshots, and workflow-owned commits never
absorb unrelated work. Incomplete work remains visibly incomplete, audit findings
survive retries and repository changes, and a checkpoint is exactly the active
workflow's delta.

## Problem Statement

Observed on real runs (SKILL-134 lineage) and still reproducible on current `main`:

- An implementation attempt can report `completed` while its own receipt declares
  unfinished plan tasks; audit and review then reason from a false premise.
  SKILL-153 repairs *structurally invalid* output, but a structurally valid,
  semantically false `completed` passes every existing gate.
- Audit repairs can claim closure while repeating the same production defect.
  Aggregate counters do not preserve identity across generations, so a recurring
  gap looks like a new one and the loop re-litigates instead of converging. This
  is the recorded "inspection-only fixed claims" recurrence failure mode.
- Checkpoint commits stage the full worktree. A concurrently prepared foreign
  feature spec (SKILL-149 during the SKILL-134 run) was committed, reviewed, and
  attributed to the wrong workflow; any user work dirty in the tree is one
  checkpoint away from being absorbed.

What `main` already solves and this spec must not re-solve: structural output
repair and producer-side gates (SKILL-153), read-path lock contention and typed
DB failures (SKILL-155), Blocker-disposition-terminated unbounded remediation
(SKILL-157), review-mode restructure with inline default (SKILL-159, #252),
Blocker-only reopen and lane severity calibration (SKILL-142).

## Acceptance Criteria

Unit 1 — truthful implementation completion:

1. An implementation phase reporting `completed` advances only when its receipt
   closes every task ID declared by the authoritative executable plan and has no
   unresolved item or actionable deviation; the gate reports the exact missing
   task or unresolved field.
2. Retryable `blocked` and `failed` envelopes remain schema-valid outcomes with
   their own continuation path; they are not converted into `schemaInvalid` to
   enter the bounded correction cap, and genuine extraction/schema/projection
   failures keep the existing correction path.
3. Incomplete-work retries use a distinct continuation prompt carrying the
   complete bounded prior receipt, reconstructed from durable records on retry
   and resume — never from an in-memory prompt or replaceable snapshot.
4. Status and telemetry distinguish semantic implementation continuation, schema
   correction, process retry, crash resume, and audit or review re-entry.

Unit 2 — audit repair convergence:

5. Each completeness audit persists one generation: repository checkpoint,
   satisfied criteria, gaps, closure-complete repair batch, bounded evidence
   references. History is append-only; a later plan or checkpoint never discards
   earlier gap text, repair results, or recurrence.
6. Gap and repair-item identities are stable across generations with explicit
   new / recurring / resolved / superseded / still-open transitions; a recurring
   gap increments durable recurrence under the same identity.
7. Implementation re-entry receives the complete ordered set of unresolved repair
   items with dependencies and prior evidence; repair cannot report `completed`
   (per unit 1's gate) until every carried item has a terminal disposition.
8. Follow-up audit reverifies every carried gap and inspects the repair batch's
   blast radius for newly introduced gaps before emitting `satisfied`; audit
   convergence metrics derive from durable generations and agree with the phase
   ledger.
9. The audit test-exclusion contract is preserved: validation owns test execution
   and failures; audit repair evidence stays read-only repository facts.

Unit 3 — scoped checkpoint isolation:

10. Checkpoint creation stages only the durable workflow-owned path inventory for
    the active subtask and phase; no production checkpoint path runs
    repository-wide `git add -A`. Foreign staged, unstaged, and untracked paths
    remain byte-for-byte and index-for-index unchanged.
11. A path introduced by the active phase outside its allowed inventory, and any
    foreign governed `.feature-specs/` path, produces a typed non-retryable
    policy block naming the exact path before any commit; staging or commit
    failure restores the pre-checkpoint index.
12. Checkpoint identity (branch, phase, loop/generation, parent SHA, owned-path
    digest, commit SHA) is durable, and review input is constructed from the
    immutable checkpoint plus the same owned-path inventory so unrelated dirt
    cannot change its semantic delta digest.
13. A regression reproduces the concurrent-spec incident: two issue keys active,
    each checkpoint contains only its own paths or blocks safely on a real
    overlap.

Cross-cutting:

14. New durable state follows the runtime-contract recipe (schema, pinned Kotlin
    constant, parity test, typed error, loud-fail seams) and the append-only
    migration rule; new migrations are name-keyed and numbered after the current
    `main` tail, tolerating databases that ran the parked branch's 23–28 range.
15. Crash, resume, and operator-decision tests prove the same guarantees in
    standalone and goal-child execution.

## Constraints

- Build against current `main` semantics: unbounded disposition-terminated
  remediation (no `GOAL_SUBTASK_REVIEW_MAX_PASSES`), structural repair and
  producer-side projection gates (SKILL-153), typed `DatabaseAccessError`
  surfaces (SKILL-155), no delegated-review subsystem (SKILL-159).
- Keep governed runtime contracts versioned under `orchestration/contracts/`
  with matching Kotlin constants, typed schema errors, parity tests, and
  loud-fail parse seams.
- Keep workflow artifact projections bounded and derived; no raw prompts, raw
  phase responses, private diagnostics, or unbounded review text in SQLite or
  artifact JSON.
- Preserve explicit operator decisions and bounded correction policies; do not
  create a new unbounded autonomous loop and do not weaken audit or review to
  reduce iteration counts.
- Salvaged quarry code enters through the same review and validation gates as
  new code.

## Non-Goals

- Reintroducing any dropped original subtask (generic convergence ledger, review
  generations, adaptive sizing, pre-review quality seam, migration umbrella).
- Guaranteeing single-invocation completion of large features.
- Storing full agent prompts, raw model responses, or private rejected-output
  bodies as convergence history.
- Deleting, stashing, resetting, or auto-committing foreign user changes.
- Force-updating or deleting the parked quarry branch histories.

## Subtask Mapping

| subtask | title | depends on | status |
|---|---|---|---|
| 1 | Truthful implementation completion | — | pending |
| 2 | Audit repair convergence | 1 (required) | pending |
| 3 | Scoped checkpoint isolation | — | pending |

## Validation Strategy

- Contract-version parity and strict schema acceptance/rejection coverage for
  every new runtime contract.
- Migration tests from current production database versions, including a fixture
  seeded with the quarry branch's name-keyed 23–28 migration ledger rows.
- Reproduce the SKILL-134 false-completion sequence; assert the third receipt
  cannot advance. Reproduce the recurring-gap sequence; assert identity-stable
  recurrence. Reproduce the concurrent-spec commit; assert isolation.
- Run focused module tests after each subtask, then:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```
