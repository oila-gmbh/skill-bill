# Subtask 4: Rationalize MCP and CLI Compatibility Surfaces

## Scope

Reduce the MCP and deprecated CLI surface to demonstrated agent-native and compatibility-required use cases.

Retain and regression-test legitimate MCP behavior for prose workflow persistence, feature verification workflow state, review/learnings, lifecycle telemetry, quality checks, PR descriptions, goal prose telemetry, and update checking.

Individually evaluate:

- The uncalled `feature_task_runtime_*` MCP lifecycle, stats, and workflow family, because foreground runtime mode owns its services directly.
- The Readian bridge and its six tools, which have no current governed skill consumer.
- Docs-only or CLI-duplicated `doctor`, scaffold, stats, and remote telemetry query tools.
- `feature_task_continuation_lookup`, whose implementation is tested but whose governed source invocation is not explicit.
- Ten hidden `feature_implement_*` MCP aliases.
- Deprecated `feature-task-runtime` and `runtime-stats` CLI aliases.

For every removable surface, delete registry entries, dispatch handlers, schemas, mappers, exclusive application/port/adapter code, dependencies, tests, documentation, and install/runtime-image implications together.

## Acceptance Criteria

1. Every MCP and deprecated CLI name has an evidence-backed disposition covering governed skills, rendered/installed output, scripts, docs, desktop wiring, telemetry, and release compatibility.
2. Compatibility aliases are deleted only when their documented removal window or version policy permits it and migration guidance exists; otherwise they remain with an explicit rationale and removal condition.
3. Removed MCP tools disappear from discovery, hidden dispatch, strict input schemas, telemetry schema branches, mappers, runtime composition, tests, and docs.
4. Removing Readian eliminates its runtime, secret redactor, schema branches, tests, documentation, and exclusive dependencies without affecting Skill Bill workflows.
5. Foreground feature-task runtime behavior and its persistence remain intact even if duplicate MCP endpoints are removed.
6. Retained MCP tools initialize, list, validate arguments, dispatch, persist, and report typed errors through installed stdio smoke tests.
7. Runtime-mcp, runtime-cli, installer, and agent install smoke tests pass.

## Non-Goals

- Removing `runtime-mcp` itself.
- Moving application business logic into MCP handlers.
- Deleting active foreground runtime services or durable state.
- Treating low observed usage as sufficient evidence to break a published interface.

## Dependency Notes

Depends on Subtasks 1 and 3. Review-related MCP tools must also preserve the active behavior established in Subtask 2.

## Validation Strategy

- Compare tool/command inventories against all sources and generated install artifacts.
- Add negative discovery/dispatch assertions for removed names and regression tests for retained names.
- Run installed stdio `initialize`, `tools/list`, and representative `tools/call` smoke tests.
- Run CLI and install integration tests.

## Next Path

Proceed to Subtask 5 for the final generated-code and dependency sweep.

