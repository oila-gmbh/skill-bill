# SKILL-126 Audit Fix Context Reuse

Status: ready

## Intended Outcome

Audit-gap remediation in both standalone feature-task runs and goal-child task runs reuses the preplan and implementation plan captured at the beginning of the durable workflow. An audit finding starts only the work needed to fix the unmet acceptance criteria; it never launches preplan or plan again.

## Scope

- Change the runtime audit-gap transition and phase-recovery behavior so audit remediation bypasses preplan and plan.
- Hydrate audit remediation from the original persisted preplan and plan artifacts in the workflow database.
- Apply the same behavior to standalone task workflows and task workflows launched by a decomposed goal.
- Preserve durable audit-gap iteration tracking, crash-safe resume, review budgeting, status output, and telemetry.
- Update governed runtime skill guidance and generated/install output when runtime behavior changes require it.

## Acceptance Criteria

1. When an audit returns `gaps_found`, the runtime starts implementation remediation without relaunching either `preplan` or `plan`.
2. Every audit-gap remediation iteration uses the preplan and plan artifacts persisted for the workflow's initial task run; it does not regenerate or overwrite those original planning artifacts.
3. Standalone feature-task runs and goal-child feature-task runs follow the same audit-gap context-reuse behavior.
4. Audit-gap remediation remains scoped to the unmet acceptance criteria carried by the latest audit verdict and preserves the current working tree's already-settled implementation.
5. A crash or interruption during audit-gap remediation resumes from durable workflow state without rerunning preplan or plan and without double-applying fixes.
6. Audit-gap ledger entries, iteration counters, phase status, and terminal telemetry continue to report each remediation iteration accurately after the transition changes.
7. Existing review-fix behavior and the shared two-pass review budget continue to work when composed with one or more audit-gap remediation iterations.
8. Automated tests reject any audit-gap transition or recovery path that schedules preplan or plan and verify persisted planning-context reuse for both task and goal-child execution.
9. Governed documentation describes the audit fix loop as context reuse and no longer describes it as an audit-to-plan re-plan loop.

## Constraints

- The workflow database remains authoritative for the saved preplan and plan context.
- Initial preplan and plan execution at the beginning of each task workflow remains unchanged.
- Missing, malformed, or incompatible persisted planning artifacts must fail loudly with typed/runtime-consistent errors; remediation must not silently regenerate them.
- Runtime agent behavior remains strategy- and workflow-driven; do not add agent-identity branching to the process runner.
- Preserve manifest-driven platform behavior and existing workflow contract validation.

## Non-Goals

- Removing the initial preplan or plan phases from task execution.
- Changing acceptance-criteria audit semantics.
- Adding a fixed cap to the audit-gap remediation loop.
- Changing feature-spec decomposition or goal scheduling outside the child task's audit remediation behavior.

## Validation Strategy

- Add or update domain transition tests for the new audit-gap destination and reopened phase span.
- Add application/runtime tests proving persisted preplan and plan artifacts are reused across repeated audit gaps and resume.
- Add goal-child coverage proving parity with standalone task workflows.
- Run the affected Gradle test suites, then route repository validation through `bill-code-check`.
