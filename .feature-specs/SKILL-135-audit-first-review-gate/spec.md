# SKILL-135 Audit-First Review Gate

Status: ready

## Intended Outcome

The feature-task phase loop runs implementation, then audit with its existing gap-repair cycles, then code review as a terminal two-pass gate, then validation. Audit is a correctness gate that finishes before review begins, and review never takes a backward edge into audit. Audit verifies only the acceptance criteria not yet marked satisfied: a criterion that reached a satisfied verdict is durably closed and is not re-verified on a later audit-gap iteration, so the unsatisfied set shrinks monotonically and the uncapped audit-gap cycle terminates by construction.

Code review reviews the subtask's full delta from that subtask's immutable base. Pass one uses the selected delegated mode and pass two is forced inline. Blocker findings unresolved after pass two block loudly with durable evidence; every other finding is recorded, never prevents advancement, and remains retrievable as a goal-wide ledger the operator can triage after the goal completes.

## Motivation

A live SKILL-131 goal exposed a compounding failure. Subtask 1's review base was pinned at branch creation and never advanced, because the base only advances when a review pass completes and review never completed. Each blocked attempt wrote another remediation checkpoint commit before mutating-phase re-entry, so the unreviewed delta grew monotonically. Nine checkpoints later the child was asked to review 134 files and roughly 517KB of diff, and returned output the contract validator could not parse as an object.

Three structural problems sit behind that outcome:

- Review runs before audit, so review polishes code that audit may force to be rewritten, and audit-gap remediation re-enters review with a scope that has already grown.
- The code-review mode is immutable across both passes, so a delegated pass that fails to produce usable output is retried under exactly the conditions that just failed.
- Non-blocking findings are persisted only as per-workflow review evidence. There is no path from a goal to the findings it chose not to address, so deferred work is invisible once the goal ends.

Reordering the loop puts the large-change gate first and the small-change gate last, which minimizes rework. Forcing pass two inline gives the second pass a different execution shape rather than a retry of the first. A goal-wide findings ledger turns silent deferral into an explicit, reviewable backlog.

## Scope

- Reorder the runtime phase definition so audit precedes review and validation follows review.
- Remove review's backward edge into audit; audit satisfaction is terminal for that subtask.
- Preserve audit's existing gap-repair cycles, repair-plan contract, and reconciliation behavior unchanged.
- Scope each audit to the acceptance criteria not yet durably marked satisfied, and durably record satisfied criteria so later audit-gap iterations never re-verify them.
- Keep review scoped to the subtask's full delta from its existing immutable per-subtask base.
- Replace code-review mode immutability with an immutable pass sequence: pass one delegated, pass two inline.
- Preserve blocker-blocks and non-blocker-advances disposition semantics.
- Durably associate every unaddressed finding with issue key, subtask id, workflow id, pass number, severity, and location.
- Provide a goal-wide retrieval surface for unaddressed findings after goal completion.
- Preserve standalone feature-task and goal-child parity, crash-safe resume, telemetry, status projection, and generated/install boundaries.

## Acceptance Criteria

1. The runtime phase definition orders implementation, audit, review, and validation so that audit completes before review starts, and the transition function rejects any path that enters review before audit reaches a satisfied verdict.
2. Review has no backward edge into audit. A review verdict, a review fix pass, and review pass exhaustion all advance toward validation or block; none of them re-enters audit or reopens an audit repair plan.
3. Audit retains its existing gap-repair cycle, repair-plan contract, identifier stability, reconciliation, and non-progress detection behavior. Its verification scope narrows: each audit verifies only the acceptance criteria not yet durably marked satisfied, a satisfied criterion is durably closed and never re-verified by a later audit-gap iteration, and an audit reaching an empty unsatisfied set settles `satisfied`.
4. Code review is scoped to the subtask's complete delta from that subtask's immutable base, including committed, staged, unstaged, and child-owned untracked paths. Per-subtask baseline capture semantics are unchanged.
5. Review runs at most two passes per subtask. Pass one executes in the selected delegated mode; pass two is forced inline regardless of the caller's original selection.
6. The pass sequence is immutable and durable. A resume reuses the recorded sequence and pass position; an explicit attempt to change the mode of an already-started sequence fails loudly before a child is launched.
7. Durable review state expresses the delegated-then-inline sequence through reserved, completed, and emitted pass accounting, and a crash that leaves a reserved pass without completed output resumes that reserved pass rather than allocating another.
8. Blocker findings unresolved after pass two block the subtask loudly with full durable evidence and a compact path-free goal-facing summary.
9. Non-blocker findings never prevent advancement. They are recorded durably and the subtask proceeds to validation.
10. Every finding that is recorded without being addressed is durably associated with issue key, subtask id, workflow id, review pass number, severity, issue category, and location.
11. A retrieval surface returns the goal-wide unaddressed-findings ledger for a given issue key, spanning every subtask of that goal, after the goal completes and while the goal is still in progress.
12. The goal terminal summary surfaces the unaddressed-findings count and severity breakdown by default as compact path-free output. Location-bearing detail is returned only through the explicit retrieval surface, never in goal, status, watch, or PR output.
13. Persistence changes add issue key, workflow id, subtask id, and pass number association for findings without breaking existing imported-review rows, and column migrations self-heal on every startup for already-created databases.
14. Standalone feature-task runs and goal-child runs share the same phase order, pass sequence, disposition, and ledger behavior.
15. Governed `content.md` guidance for the feature-goal, feature-task-runtime, feature-task-prose, and subtask-runner surfaces describes the new phase order, the delegated-then-inline pass sequence, blocker versus non-blocker disposition, and the unaddressed-findings ledger. Generated wrappers stay uncommitted. Local install refresh is explicitly deferred to the operator after the goal completes: do NOT run `./install.sh` or any install flow at any point during this goal.
16. Contract, domain, application, persistence, CLI/status, telemetry, standalone, goal-child, and end-to-end acceptance and rejection tests pass, followed by the repository validation gates:

    ```bash
    skill-bill validate
    (cd runtime-kotlin && ./gradlew check)
    npx --yes agnix --strict .
    scripts/validate_agent_configs
    ```

## Constraints

- Runtime contract changes follow the repository schema, version-constant, parity-test, typed-error, and classpath-copy recipe, and loud-fail incompatible durable records.
- Durable review state and the findings ledger persist compact structured evidence only. No prompts, diffs, source bodies, complete test logs, or raw agent transcripts.
- Goal-facing output, `goal_event` lines, and status projections stay path-free and compact; location-bearing evidence stays behind the explicit retrieval surface.
- Column migrations run unconditionally at startup so already-created databases gain new columns.
- Agent-specific runtime behavior stays behind injectable strategies; no agent-identity branching in the process runner.
- Update authored `content.md` sources rather than generated skill wrappers. Do NOT run `./install.sh` or any install flow during this goal — reinstalling mid-run would replace the skill sources driving the active goal loop. The operator runs the install after the goal completes.
- Preserve manifest-driven platform behavior, goal-child review baselines, decomposition scheduling, and unrelated working-tree changes.

## Non-Goals

- Changing per-subtask review baseline capture semantics.
- Re-verifying acceptance criteria that already reached a satisfied verdict. Audit scope is the not-yet-satisfied set; regression detection on already-closed criteria is out of scope for this feature and belongs to review and validation.
- Introducing a third review pass or removing the two-pass cap.
- Changing the review severity taxonomy, feature decomposition, goal scheduling, or PR behavior.
- Fixing the goal-review schema-invalid retry asymmetry, which is a separate contained change.
- Automatically resolving, reopening, or scheduling deferred findings; the ledger is a retrieval and triage surface only.

## Validation Strategy

- Add transition-function tests proving audit precedes review, that review cannot start before a satisfied audit verdict, and that no review outcome re-enters audit.
- Add pass-sequence tests for delegated-then-inline ordering, sequence immutability across resume, rejection of an explicit mid-sequence mode change, and reserved-pass resumption after a crash.
- Add disposition tests for blocker-blocks-with-evidence and non-blocker-advances.
- Add persistence tests for findings association, migration self-healing on an existing database, and coexistence with imported-review rows.
- Add retrieval tests for goal-wide ledger queries in progress and after completion, and for path-free default summary output.
- Add standalone and goal-child parity tests covering phase order, pass sequence, and ledger behavior.
- Run focused tests during implementation, then the complete repository validation gates listed in acceptance criterion 16.

## Next Path

Run bill-feature on `.feature-specs/SKILL-135-audit-first-review-gate/spec.md`.
