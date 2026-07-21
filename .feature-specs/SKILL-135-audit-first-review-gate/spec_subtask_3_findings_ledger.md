# SKILL-135 Subtask 3: Goal-Wide Unaddressed Findings Ledger

## Scope

Persist every unaddressed review finding with goal, subtask, workflow, and pass association, expose a goal-wide retrieval surface, surface a compact default count in goal terminal output, and complete governed guidance, documentation, and repository validation.

## Acceptance Criteria

1. Every finding recorded without being addressed is durably associated with issue key, subtask id, workflow id, review pass number, severity, issue category, and location.
2. Persistence changes preserve existing imported-review rows and their behavior, and column migrations run unconditionally at startup so already-created databases gain the new columns.
3. A retrieval surface returns the goal-wide unaddressed-findings ledger for a given issue key across every subtask of that goal, both while the goal is in progress and after it completes.
4. The retrieval surface distinguishes absent, empty, and malformed ledger states through typed errors rather than silent empty results.
5. The goal terminal summary surfaces the unaddressed-findings count and severity breakdown by default as compact path-free output.
6. Location-bearing finding detail is returned only through the explicit retrieval surface and never appears in goal, status, watch, telemetry, or PR output.
7. Governed `content.md` guidance for the feature-goal, feature-task-runtime, feature-task-prose, and subtask-runner surfaces describes the audit-first phase order, the delegated-then-inline pass sequence, blocker versus non-blocker disposition, and the unaddressed-findings ledger; generated wrappers stay uncommitted. Do NOT run `./install.sh` or any install flow during this goal — the operator refreshes the local install after the goal completes.
8. Contract, domain, application, persistence, CLI/status, telemetry, standalone, goal-child, and end-to-end acceptance and rejection tests pass, followed by `skill-bill validate`, `./gradlew check`, strict Agnix validation, and agent-config validation.

## Non-Goals

- Automatically resolving, reopening, or scheduling deferred findings; this is a retrieval and triage surface only.
- Changing the review severity taxonomy or finding classification.
- Changing phase order or pass sequence behavior established by subtasks 1 and 2.

## Dependency Notes

Depends on subtask 1 for the reordered phase graph and on subtask 2 for the pass sequence and disposition semantics that determine which findings are recorded as unaddressed.

## Validation Strategy

- Persistence tests for findings association, migration self-healing against an existing database, and coexistence with imported-review rows.
- Retrieval tests for in-progress and completed goals, and for typed absent, empty, and malformed states.
- Output tests proving the default summary is path-free and that location detail never leaks into goal, status, watch, telemetry, or PR surfaces.
- Governed content and install-boundary tests.
- Full repository validation gates listed in acceptance criterion 8.

## Next Path

Open the parent pull request after this subtask commits.
