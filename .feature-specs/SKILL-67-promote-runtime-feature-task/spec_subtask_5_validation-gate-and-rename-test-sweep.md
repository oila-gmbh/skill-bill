---
status: In Progress
---

# SKILL-67 Subtask 5 - Validation Gate and Rename Test Sweep

Parent spec: [.feature-specs/SKILL-67-promote-runtime-feature-task/spec.md](./spec.md)
Issue key: SKILL-67

## Scope

Closing gate. Sweep every test, golden file, and fixture touched by the CLI, MCP,
skill, and goal renames into a consistent state, confirm no review/audit/validation
gate was weakened, and run the full maintainer command set.

- Repair/extend the golden and parity tests across the renamed surfaces:
  `CliAuthoringParityTest`, `CliFeatureTaskRuntimeRuntimeTest`,
  `McpStdioServerTest`, the contract-version parity tests, and any fixture that
  asserts command names, tool names, or skill names.
- Add focused regression tests proving the alias compatibility contract: the
  deprecated `feature-task-runtime` command and `feature_task_runtime_*` tools
  still resolve, the legacy `feature_implement_*` family still works, and
  `bill-feature-task-runtime` resolves to `bill-feature-task` on install.
- Add/confirm a goal regression test proving a child subtask records
  `feature_task_runtime` workflow state and resumes through runtime `resume`.
- Confirm the promoted path still passes the same unmodified review/audit/validate
  gate it passed as the experimental path (no gate weakened, per the SKILL-65
  criterion).

## Acceptance Criteria

1. All renamed-surface golden/parity tests pass and assert canonical names while
   still covering the deprecated aliases.
2. Regression tests assert: deprecated CLI/MCP aliases resolve; `feature_implement_*`
   remains functional; `bill-feature-task-runtime` -> `bill-feature-task` install
   alias resolves.
3. A goal regression test asserts child subtask state is `feature_task_runtime`
   and resume skips completed phases.
4. No review, audit, or validation gate is weakened relative to pre-promotion;
   this is explicitly verified and noted.
5. The full maintainer command set passes:
   - `skill-bill validate`
   - `(cd runtime-kotlin && ./gradlew check)`
   - `npx --yes agnix --strict .`
   - `scripts/validate_agent_configs`

## Non-Goals

- No new behavior; this subtask only sweeps tests and runs the gate.
- Do not retire the legacy surfaces (scheduled follow-up).
- Do not weaken any gate to make the sweep pass; fix the root cause instead.

## Dependency Notes

Depends on: Subtasks 1-4 (all renames, the goal coupling, and the docs/decision
records must be in place). Final subtask of SKILL-67.

## Validation Strategy

Run the full maintainer command set as the closing gate; treat any
green-by-suppression as a failure. On completion the SKILL-67 goal is complete and
the dual-maintenance state is ended except for the time-boxed legacy surface.

## Next Path

Final subtask. On completion, the only remaining work is the scheduled follow-up
that retires `bill-feature-task-legacy` and the `feature_implement_*` family
together at the close of the deprecation window.

## Spec Path

.feature-specs/SKILL-67-promote-runtime-feature-task/spec_subtask_5_validation-gate-and-rename-test-sweep.md
