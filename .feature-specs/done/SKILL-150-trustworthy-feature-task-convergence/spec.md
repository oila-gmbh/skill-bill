# SKILL-150 Trustworthy Feature-Task Convergence

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

Observed on real runs and still reproducible on current `main`:

- An implementation attempt can report `completed` while its own receipt declares
  unfinished plan tasks; audit and review then reason from a false premise. The
  structural-repair gate (SKILL-153) repairs *structurally invalid* output, but a
  structurally valid, semantically false `completed` passes every existing gate.
- Audit repairs can claim closure while repeating the same production defect.
  Aggregate counters do not preserve identity across generations, so a recurring
  gap looks like a new one and the loop re-litigates instead of converging: the
  recorded "inspection-only fixed claims" recurrence failure mode.
- Checkpoint commits stage the full worktree. A concurrently prepared foreign
  feature spec was committed, reviewed, and attributed to the wrong workflow;
  any user work dirty in the tree is one checkpoint away from being absorbed.

Adjacent ground `main` already covers, which this spec must not re-solve:
structural output repair and producer-side gates (SKILL-153), typed bounded DB
failures (SKILL-155), Blocker-disposition-terminated unbounded remediation
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
13. A concurrent-spec regression proves that with two issue keys active, each
    checkpoint contains only its own paths or blocks safely on a real overlap.

Cross-cutting:

14. New durable state follows the runtime-contract recipe (schema, pinned Kotlin
    constant, parity test, typed error, loud-fail seams) and the append-only,
    name-keyed migration rule, numbered after the current `main` tail.
15. Crash, resume, and operator-decision tests prove the same guarantees in
    standalone and goal-child execution.

## Constraints

- Build against current `main` semantics: unbounded disposition-terminated
  remediation (no review pass cap), structural repair and producer-side
  projection gates, typed `DatabaseAccessError` surfaces, no delegated-review
  subsystem.
- Keep governed runtime contracts versioned under `orchestration/contracts/`
  with matching Kotlin constants, typed schema errors, parity tests, and
  loud-fail parse seams.
- Keep workflow artifact projections bounded and derived; no raw prompts, raw
  phase responses, private diagnostics, or unbounded review text in SQLite or
  artifact JSON.
- Preserve explicit operator decisions and bounded correction policies; do not
  create a new unbounded autonomous loop and do not weaken audit or review to
  reduce iteration counts.

## Non-Goals

- A generic multi-kind convergence-record ledger; each unit owns only the
  durable slice it needs.
- Review-generation preservation machinery; Blocker settlement is already
  disposition-driven and the unaddressed-findings ledger already carries
  non-Blocker findings.
- Adaptive feature sizing or a pre-review quality seam.
- Guaranteeing single-invocation completion of large features.
- Storing full agent prompts, raw model responses, or private rejected-output
  bodies as convergence history.
- Deleting, stashing, resetting, or auto-committing foreign user changes.

## Subtask Mapping

| subtask | title | depends on | status |
|---|---|---|---|
| 1 | Truthful implementation completion | — | pending |
| 2 | Audit repair convergence | 1 (required) | pending |
| 3 | Scoped checkpoint isolation | — | pending |

## Validation Strategy

- Contract-version parity and strict schema acceptance/rejection coverage for
  every new runtime contract.
- Migration tests from current production database versions.
- Reproduce the false-completion sequence; assert the dishonest receipt cannot
  advance. Reproduce the recurring-gap sequence; assert identity-stable
  recurrence. Reproduce the concurrent-spec commit; assert isolation.
- Run focused module tests after each subtask, then:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```
