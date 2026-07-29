# SKILL-150 Trustworthy Feature-Task Convergence

## Intended Outcome

Make feature-task implementation, completeness audit, and code review converge from durable evidence instead of agent claims or replaceable snapshots. Incomplete work must remain incomplete, audit and review findings must survive retries and repository changes, and workflow-owned commits must never absorb unrelated work.

## Problem Statement

The SKILL-134 runtime run exposed interacting reliability failures:

- an implementation attempt could report `completed` while declaring unfinished plan tasks;
- retryable `blocked` and `failed` implementation outputs were described as schema failures and relaunched without their full unresolved-work receipt;
- audit repairs could claim closure while repeating the same production defect;
- review invalidation cleared prior results and unresolved findings when the repository delta changed;
- checkpoint commits staged the full worktree and committed an unrelated feature spec;
- a cross-module persistence, privacy, lifecycle, CLI, and recovery change was classified MEDIUM and reviewed at light depth;
- deterministic compilation and migration failures were not caught early enough by build checks available to implementation and review;
- aggregate counters did not preserve enough append-only evidence to explain recurring gaps and review generations.

## Acceptance Criteria

1. A mutating phase cannot settle as completed while any governed plan task, carried audit repair item, or required remediation finding remains unresolved.
2. Retryable incomplete work uses a continuation path distinct from schema-invalid output handling and receives the complete bounded prior receipt on retry and resume.
3. Implementation attempt outcomes, audit gaps and repair results, and review findings and dispositions have durable generation-aware records that are never deleted merely because a later repository checkpoint exists.
4. Audit re-entry carries one closure-complete unresolved repair batch, and every subsequent audit dispositions all carried gaps before it can clear the phase.
5. Review approval is impossible while any prior or current Blocker lacks a durable resolved, superseded, accepted, or otherwise governed terminal disposition.
6. Repository-delta invalidation creates a new review generation without clearing historical review evidence or the unresolved-finding ledger.
7. Checkpoint commits capture the complete current repository delta, including staged, unstaged, and untracked files, without an ownership-based authorization gate.
8. Repository paths discovered during implementation, audit repair, review remediation, validation, or history work may be committed without expanding a durable path allowlist or requesting operator authorization.
9. Planning automatically escalates or decomposes work whose boundary breadth, task graph, risk, or expected change surface exceeds the resolved feature-size contract.
10. Cross-cutting or high-risk changes receive an appropriate minimum review depth. Build, compilation, formatting, and static-analysis checks may run during implementation, review, and validation; test execution runs only during validation.
11. Legacy active workflows migrate or reconcile into the durable convergence model idempotently, with typed loud failures or quarantine for incompatible state and no silent evidence loss.
12. Status, watch, and telemetry report implementation continuations, schema retries, audit recurrence, review generations, carried findings, and repair outcomes from durable records without exposing raw phase output.
13. Crash, resume, operator-decision, changed-delta, and concurrent-dirty-worktree tests prove the same convergence and isolation guarantees in standalone and goal-child execution.
14. Deterministic end-to-end fixtures demonstrate that a partial implementation remains in implementation, a correct closure-complete audit repair needs at most one verification pass, and a carried review Blocker cannot disappear across repeated delta changes.

## Constraints

- Keep governed runtime contracts versioned under `orchestration/contracts/`, with matching Kotlin constants, typed schema errors, parity tests, and loud-fail parse seams.
- Use normalized SQLite records when append-only history, stable identity, transactional ownership, or cross-generation queries would otherwise be weakened by replaceable artifact JSON.
- Keep workflow artifact projections bounded and derived; do not move raw prompts, raw phase responses, private diagnostics, full diffs, or unbounded review text into SQLite or artifact JSON.
- Preserve dynamic, manifest-driven routing and platform packs. Do not hard-code Kotlin, KMP, or a fixed platform catalogue into orchestration.
- Preserve bounded retry policies. Repository path ownership must not introduce an operator decision or blocking seam.
- Preserve the audit test-exclusion contract: completeness audit reports production behavior and implementation gaps; validation owns test execution and failures.
- Permit build and compilation commands in implementation, review, and validation. Do not classify a build command as a test solely because it may compile test sources; commands that execute tests remain validation-only.
- Keep final validation and its test execution authoritative even when implementation or review build checks pass.
- Do not weaken audit or review to reduce iteration counts.

## Non-Goals

- Guaranteeing that every model completes a large feature in one process invocation.
- Replacing code review with tests or replacing validation with code review.
- Automatically accepting unresolved findings because their source checkpoint became stale.
- Preserving unrelated dirty-worktree changes outside workflow checkpoint commits.
- Storing full agent prompts, raw model responses, or private rejected-output bodies as convergence history.
- Rewriting or completing SKILL-134 as part of this feature.
- Squashing or rewriting existing feature branches automatically.

## Decomposition

1. Add durable convergence-state contracts and persistence.
2. Enforce truthful implementation completion and continuation semantics.
3. Make audit repair state append-only and convergence-gated.
4. Preserve review findings and dispositions across review generations.
5. Isolate checkpoint commits to workflow-owned paths.
6. Add adaptive sizing, review-depth routing, and phase-appropriate non-test quality checks.
7. Migrate legacy state and prove end-to-end recovery, telemetry, and convergence.

## Validation Strategy

- Add contract-version parity and strict schema acceptance/rejection coverage for every new runtime contract.
- Add migration tests from current production database versions and artifact-only workflow fixtures.
- Exercise implementation completion, blocked continuation, schema-invalid retry, audit recurrence, review invalidation, operator decisions, and crash recovery through real workflow state transitions.
- Use concurrent dirty-worktree fixtures with staged, unstaged, untracked, and unrelated governed spec paths.
- Run focused module tests after each subtask, then run:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```
