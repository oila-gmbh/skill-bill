# SKILL-151 - trustworthy-feature-task-convergence

## Mode

decomposed

## Intended Outcome

Make feature-task implementation, completeness audit, and code review converge from durable evidence instead of agent claims or replaceable snapshots. Incomplete work remains incomplete, audit and review findings survive retries and repository changes, and workflow checkpoint commits isolate unrelated ambient work without imposing path authorization.

## Overview

SKILL-134 exposed interacting reliability failures in truthful implementation completion, continuation receipts, audit repair closure, review generation history, checkpoint isolation, risk classification, early build checks, legacy reconciliation, and observability. This feature introduces a normalized, generation-aware convergence model and carries unresolved obligations forward until governed terminal dispositions exist. Repository file, delta, checkpoint, or commit-hash changes may trigger re-evaluation or a new generation, but never block audit or review merely because identity changed.

## Acceptance Criteria

1. A mutating phase cannot settle as completed while any governed plan task, carried audit repair item, or required remediation finding remains unresolved.
2. Retryable incomplete work uses a continuation path distinct from schema-invalid output handling and receives the complete bounded prior receipt on retry and resume.
3. Implementation attempt outcomes, audit gaps and repair results, and review findings and dispositions have durable generation-aware records that are never deleted merely because a later repository checkpoint exists.
4. Audit re-entry carries one closure-complete unresolved repair batch, and every subsequent audit dispositions all carried gaps before it can clear the phase.
5. Review approval is impossible while any prior or current Blocker lacks a durable resolved, superseded, accepted, or otherwise governed terminal disposition.
6. Repository-delta invalidation creates a new review generation without clearing historical review evidence or the unresolved-finding ledger.
7. Checkpoint commits capture the complete workflow-produced repository delta across staged, unstaged, and untracked files without an ownership-based authorization gate, while durable baseline or isolation mechanics exclude unrelated ambient work.
8. Repository paths discovered during implementation, audit repair, review remediation, validation, or history work may be committed without expanding a durable path allowlist or requesting operator authorization.
9. Planning automatically escalates or decomposes work whose boundary breadth, task graph, risk, or expected change surface exceeds the resolved feature-size contract.
10. Cross-cutting or high-risk changes receive an appropriate minimum review depth. Build, compilation, formatting, and static-analysis checks may run during implementation, review, and validation, while final validation remains authoritative.
11. Legacy active workflows migrate or reconcile into the durable convergence model idempotently, with typed loud failures or quarantine for incompatible state and no silent evidence loss.
12. Status, watch, and telemetry report implementation continuations, schema retries, audit recurrence, review generations, carried findings, and repair outcomes from durable records without exposing raw phase output.
13. Crash, resume, operator-decision, changed-delta, and concurrent-dirty-worktree production paths preserve the same convergence and isolation guarantees in standalone and goal-child execution.
14. Production convergence guarantees ensure a partial implementation remains in implementation, a closure-complete audit repair requires only one subsequent verification pass when correct, and a carried review Blocker cannot disappear across repeated delta changes.
15. Audit and review never block solely because files, repository deltas, checkpoints, or commit hashes changed; re-evaluation preserves all unresolved governed evidence and dispositions.

## Constraints

- Keep governed runtime contracts versioned under `orchestration/contracts/`, with matching Kotlin constants, typed schema errors, parity enforcement, and loud-fail parse seams.
- Use normalized SQLite records when append-only history, stable identity, transactional ownership, or cross-generation queries would otherwise be weakened by replaceable artifact JSON.
- Keep workflow artifact projections bounded and derived; do not persist raw prompts, raw phase responses, private diagnostics, full diffs, or unbounded review text.
- Preserve dynamic, manifest-driven routing and platform packs without hard-coded platform catalogues.
- Preserve bounded retry policies.
- Repository path ownership must not introduce an operator decision or blocking seam.
- Preserve the audit production-behavior boundary.
- Permit build and compilation commands during implementation, review, and validation.
- Keep final validation authoritative even when earlier build checks pass.
- Do not weaken audit or review to reduce iteration counts.
- Do not add new tests, test fixtures, test resources, or test-only infrastructure. Existing failing tests may be corrected.
- Do not add comments, and remove comments from any modified file as required by repository instructions.
- Branch from `base-for-trustworthy-feature-task-convergence`.

## Non-Goals

- Guaranteeing that every model completes a large feature in one process invocation.
- Replacing code review with checks or replacing validation with code review.
- Automatically accepting unresolved findings because their source checkpoint became stale.
- Preserving unrelated ambient dirty-worktree changes by committing them into workflow checkpoints.
- Storing full agent prompts, raw model responses, private rejected-output bodies, full diffs, or unbounded finding text as convergence history.
- Rewriting or completing SKILL-134.
- Squashing or rewriting existing feature branches automatically.
- Adding new tests or test-only infrastructure.

## Validation Strategy

Use existing repository validation only:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

Build and test execution are required. Fix failing production behavior and correct stale existing test expectations when necessary. Do not add new tests.
