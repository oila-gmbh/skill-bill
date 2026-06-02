---
status: Draft
---

# SKILL-65 Subtask 4 - CLI, MCP Surface, and Experimental Skill

Parent spec: [.feature-specs/SKILL-65-experimental-feature-task-runtime/spec.md](./spec.md)
Issue key: SKILL-65

## Scope

Expose the phase-loop runner through user-facing surfaces: a `skill-bill
feature-task-runtime` CLI command group (run + status + resume, mirroring the
goal commands), MCP tools following the `feature_implement_workflow_*`
registry/dispatcher pattern, and a thin, clearly-experimental
`bill-feature-task-runtime` skill that gathers the spec, presents one
confirmation gate, and launches the runtime command — mirroring the
`bill-feature-goal` thin-skill pattern.

## Acceptance Criteria

1. A new `feature-task-runtime` CLI command group (`run`, `status`, `resume`) is
   wired through `TopLevelCliCommands`, delegates to `FeatureTaskRuntimeRunner`,
   imports no concrete infrastructure, and keeps the CLI architecture test green.
2. The CLI accepts: issue key and spec path, repo root, db override, timeout,
   monitor mode, `--agent` / `--agent-override`, and an optional per-phase agent
   assignment; it defaults the agent to the invoking agent.
3. MCP tools for the runtime task family are registered in `McpToolRegistry`
   (names + descriptions + input schemas) and dispatched in `McpToolDispatcher`,
   following the existing workflow-tool pattern; golden tests cover their
   payloads.
4. `skills/bill-feature-task-runtime/content.md` exists as a thin, explicitly
   experimental skill: gather/confirm the spec -> single confirmation gate ->
   launch `skill-bill feature-task-runtime`. It does not re-implement phase
   orchestration in prose and does not reference or alter `bill-feature-task`.
5. The skill description and content clearly mark the capability experimental and
   note it must not be used as a default path.
6. CLI/MCP result mappers use compact semantics consistent with SKILL-64
   conventions; any golden additions/updates are deliberate and reviewed.
7. Skill catalog validation passes: `npx --yes agnix --strict .` and
   `scripts/validate_agent_configs` accept the new skill and any native-agent
   configs.

## Non-Goals

- No behavioral change to `bill-feature-task` and no default routing to the
  experimental path.
- No dual-agent cross-review merge.
- No new orchestration logic in the CLI/MCP layer; these surfaces only validate
  input and delegate to the application runner.

## Dependency Notes

Depends on: Subtask 3 (the runner the surfaces invoke).

Consumed by Subtask 5 (the comparison harness runs through the CLI surface).

## Validation Strategy

CLI command tests (option validation + delegation), MCP golden tests for the new
tools, `agnix --strict`, and `validate_agent_configs` for the experimental skill;
CLI/MCP architecture tests confirm delegation to application services only.

## Next Path

Run bill-feature-task on spec_subtask_5_comparison-harness-and-promote-kill-criteria.md.

## Spec Path

.feature-specs/SKILL-65-experimental-feature-task-runtime/spec_subtask_4_cli-mcp-surface-and-experimental-skill.md
