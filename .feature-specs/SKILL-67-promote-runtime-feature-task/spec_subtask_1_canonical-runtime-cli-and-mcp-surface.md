---
status: Complete
---

# SKILL-67 Subtask 1 - Canonical Runtime CLI and MCP Surface

Parent spec: [.feature-specs/SKILL-67-promote-runtime-feature-task/spec.md](./spec.md)
Issue key: SKILL-67

## Scope

Rename the experimental runtime surface to its canonical names and remove the
EXPERIMENTAL framing, while keeping every existing caller working. This is the
foundation subtask: everything downstream references the canonical command and
MCP names produced here.

- Rename the CLI command `skill-bill feature-task-runtime` (`run` / `resume` /
  `status`) to canonical `skill-bill feature-task`. Retain `feature-task-runtime`
  as a hidden/deprecated command alias for the removal window. Touch
  `FeatureTaskRuntimeCliCommands.kt`, `FeatureTaskRuntimeRunCommand`, and the
  command registration in `CliCommandGroups.kt`.
- Rename the runtime MCP tools `feature_task_runtime_*` (`_workflow_*`,
  `_started`, `_finished`, `_stats`) to canonical `feature_task_*` in
  `McpToolRegistry.kt` and `McpToolDispatcher.kt`. Retain `feature_task_runtime_*`
  as deprecated aliases for the window, with deprecation notes in descriptions.
- Keep the legacy `feature_implement_*` MCP/workflow family **registered and
  functional**; only add a deprecation note to its descriptions. Do not retire
  it.
- Update canonical names in supporting contracts: `FeatureTaskRuntimeSchemaPaths`
  and any workflow-family / remote-stats name mapping so the canonical command
  and tools resolve to the same durable `feature_task_runtime` workflow family.
- Remove "EXPERIMENTAL" labels from canonical command help and canonical tool
  descriptions. Aliases may retain a "deprecated" note.

## Acceptance Criteria

1. `skill-bill feature-task run|resume|status` works with the same arguments,
   options (including `--agent`, `--agent-override`, `--phase-agent`, and the
   `--goal-*` flags), and behavior the experimental command had.
2. `skill-bill feature-task-runtime ...` still works as a deprecated alias and
   emits a deprecation note, with no behavioral difference.
3. Canonical `feature_task_*` MCP tools are registered, dispatch correctly, and
   validate payloads exactly as the experimental tools did; the
   `feature_task_runtime_*` aliases remain callable with deprecation notes.
4. The `feature_implement_*` MCP/workflow family remains registered and
   functional, with deprecation notes added but no behavior change.
5. Canonical command help and canonical tool descriptions contain no
   "EXPERIMENTAL" language.
6. All contract-version parity tests
   (`FeatureTaskRuntimePhaseOutputSchemaContractVersionTest`,
   telemetry-event parity tests, `CliAuthoringParityTest`, `McpStdioServerTest`)
   pass or are updated in lockstep with the rename.

## Non-Goals

- No goal-runner changes (subtask 3).
- No skill directory or install changes (subtask 2).
- Do not retire `feature_implement_*` or the experimental aliases.
- Do not change phase definitions, schema gate semantics, or handoff payloads.

## Dependency Notes

Depends on: nothing. First subtask; produces the canonical names every later
subtask references.

## Validation Strategy

`(cd runtime-kotlin && ./gradlew check)` with focus on
`CliFeatureTaskRuntimeRuntimeTest`, `CliAuthoringParityTest`,
`McpStdioServerTest`, and the contract-version parity tests; manual smoke of
`skill-bill feature-task --help` and the deprecated alias.

## Next Path

Subtask 2 promotes the skill and demotes the prose orchestrator, referencing the
canonical `skill-bill feature-task` command introduced here.

## Spec Path

.feature-specs/SKILL-67-promote-runtime-feature-task/spec_subtask_1_canonical-runtime-cli-and-mcp-surface.md
