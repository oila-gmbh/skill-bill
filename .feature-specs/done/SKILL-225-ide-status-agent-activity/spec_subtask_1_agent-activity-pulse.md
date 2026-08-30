# SKILL-225 · Subtask 1 — Agent activity pulse end to end

## Scope

Deliver durable agent-activity stamps and surface them in IdeStatus and the
IntelliJ plugin details popup.

Edit / add as needed:

- Wait-loop / agent-run probes: on meaningful worktree, stdout / stream, or
  durable-progress ticks, persist `last_agent_activity_at` and a closed
  `last_agent_activity_label` for the active goal / feature-task run so a later
  IdeStatus poll can read them before the agent process exits.
- Review path: stamp `evidence read` when governed review evidence operations
  run (runtime-owned), without watching Cursor transcript files.
- `orchestration/contracts/ide-status-schema.yaml` plus Kotlin IdeStatus models,
  projector, validator, and parity tests: optional
  `last_agent_activity_at` / `last_agent_activity_label` (omit both when
  unset; closed label enum).
- Keep `updated_at` and freshness classification on the existing lease /
  workflow anchors from `IdeStatusLivenessAnchors`.
- IntelliJ plugin: map the fields into a details row
  `Agent activity: <label> | <time>`; omit the row when fields are absent.

## Acceptance Criteria

1. Mid-run persistence records `last_agent_activity_at` and
   `last_agent_activity_label` from wait-loop probe ticks (worktree write,
   stdout, durable progress, tool stream as applicable) while an agent phase
   is running.
2. Governed review evidence reads stamp `evidence read` without using Cursor
   agent-transcript paths as the primary signal.
3. IdeStatus schema and Kotlin projection expose both fields as optional and
   omit them together when no stamp exists; label values are restricted to the
   closed vocabulary fixed in the schema.
4. IdeStatus `updated_at` / freshness continue to use lease and workflow
   anchors; activity stamps do not redefine freshness.
5. IntelliJ status details show
   `Agent activity: <label> | <formatted time>` when both fields are present
   and omit the row when they are not.
6. Contract parity tests and focused unit / mapping tests cover present,
   omitted, and freshness-unchanged cases for the new fields.

## Non-Goals

- Cursor transcript-file watchers as the primary activity source.
- Free-form per-tool labels or transcript body preview in the plugin.
- VS Code extension changes.
- Redesign of elapsed clocks, Stop / Pause, or planning rows.

## Dependency Notes

- None. Single subtask; base branch is `main`.

## Validation Strategy

- IdeStatus schema / golden / projector tests for the new fields and unchanged
  freshness behavior.
- Wait-loop or persistence unit test proving a probe tick is visible to a
  subsequent status read before process exit.
- Plugin JSON mapping / details presentation test for the activity row.
- Targeted Gradle tests for touched modules; `skill-bill validate`.

## Next Path

`skill-bill goal SKILL-225`
