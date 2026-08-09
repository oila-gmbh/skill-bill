# Subtask 7 — parent acceptance criteria 1–10 closure evidence

Issue key: SKILL-175
Date: 2026-08-09

Concrete, reproducible evidence that parent acceptance criteria 1–10 (spec.md
§AC-1…AC-10) are satisfied end-to-end on this tree. Each item is a repo fact or
a verbatim command. Any criterion that depended on an upstream subtask was
walked against the live tree and remediated inside this subtask when unmet.

## AC-1 — no prose engine selector on feature entry

- `ls -d skills/bill-feature-task-prose skills/bill-feature-task-subtask-runner`
  → **No such file or directory** (both deleted, subtask 3).
- `grep -rn 'mode:prose' skills/` → no hit outside `skills/agent/history.md`
  (historical record; not a live selector).
- Entry skills `skills/bill-feature-goal/content.md` and
  `skills/bill-feature-task/content.md` assert no `mode:prose` / `mode:runtime`
  selector:
  - `FeatureSpecSkillWiringContractTest`: `assertFalse(task.contains("mode:prose"))`,
    `assertFalse(content.contains("mode:prose"))`,
    `assertFalse(content.contains("goal_prose_started"))` (runtime-only, task-2).

## AC-2 — prose skills and native agents removed from source and install staging

- Same `ls` evidence as AC-1: both skill trees absent.
- `FeatureFamilyRenderingIntegrationTest`: `assertFalse(Files.exists(...resolve("bill-feature-task-prose.md")))`
  (the feature-family staging no longer renders a prose skill).

## AC-3 — prose MCP tools (`feature_task_prose_*`, legacy `feature_implement_*`, `goal_prose_*`) gone

- `grep -rn 'feature_task_prose_\|goal_prose_' runtime-kotlin/runtime-mcp/src/main`
  → no hits (no live registration in the MCP main source).
- `McpStdioServerTest` (`SKILL-175 the advertised surface carries no prose family
  name`): asserts the advertised `tools/list` surface contains no
  `feature_task_prose_*` / `feature_implement_*` / `goal_prose_*` name.
- `TelemetryEventSchemaViolationsTest`: retired prose event names appear only as
  schema-violation fixtures, never as live tools.

## AC-4 — CLI prose workflow family and `implement-stats` removed

- `grep -rn 'implement-stats\|TASK_PROSE\|WorkflowFamily.IMPLEMENT' runtime-kotlin/runtime-cli/src/main`
  → no hits.
- `CliRuntimeTest` (`removed prose workflow and implement-stats commands are
  unknown`): the retired `skill-bill workflow …` and `implement-stats` commands
  report as unknown.

## AC-5 — persistence/application no longer implement a live prose workflow family; in-flight rows migrated/quarantined/loudly rejected

- The only retained prose tokens in main source are the read-only quarantine /
  migration surface (subtask 6) that reads in-flight prose rows, exactly the
  parent’s “migrated, quarantined, or loudly rejected — not silently
  half-executed” requirement:
  - `WorkItem.kt`: `FEATURE_TASK_PROSE` retained “as a legacy read-only wire
    value so historical rows can still be read”.
  - `WorkListService.kt` / `IdeStatusProjector.kt`: legacy
    `WorkItemKind.FEATURE_TASK_PROSE` maps to `null` (no live work item).
  - `FeatureTaskRuntimeRunnerTest` (`runtime run against a prose-mode workflow
    blocks with an actionable reason and launches nothing`): a foreign-mode
    (prose) row is loudly rejected with `"was created in 'prose' mode"`.
  - `DatabaseColumnMigrations.kt` / `WorkflowStateStore.kt` etc. keep the
    `feature_implement_sessions` table readable (no live writer).

## AC-6 — IDE/work-list status no longer exposes `feature-task-prose` as a live family

- `IdeStatusProjector.kt`: `WorkItemKind.FEATURE_TASK_PROSE -> null` with a
  SKILL-175 comment stating it is “retained … as a legacy read-only wire value”.
- `IdeStatusService.kt`: the prose kind is only a legacy/read-only projection,
  never a live launchable family.

## AC-7 — OpenCode/zcode have no live product traces

- `grep -rniE 'opencode|zcode' runtime-kotlin/runtime-*/src/main skills/ scripts/ docs/`
  → no hits (excluding `agent/history.md` / `agent/decisions.md` decision
  records). No install enum entry, provider, MCP registration, native-agent
  render, detection signal, skill support table, CLI allow-list, or docs
  support tier.
- `InstallerShellDelegationTest`: `assertFalse(installScript.contains("install link-opencode-agents"))`.
- `ScaffoldServiceParityTest`: `assertFalse(Files.exists(skillDir.resolve("opencode-agents")))`.
- No `RUNTIME_REFUSED_AGENTS` / `RUNTIME_REFUSED_AGENT_MESSAGE` refuse-tier
  machinery remains (removed in subtask 2; unknown agents use the general
  unavailable-launcher refusal path).

## AC-8 — dual-path parity tests removed or rewritten runtime-only

- `FeatureSpecSkillWiringContractTest` and
  `UnboundedRemediationLoopGovernedContentTest` are runtime-only (negative
  prose-absence assertions; “phase prompt directives must not lockstep with
  deleted prose skill trees”). No test requires runtime↔prose lockstep.

## AC-9 — docs and product intent describe a single runtime engine; no “runtime mode only” qualification

- `runtime-kotlin/ARCHITECTURE.md` “Feature-Task Workflow Family”:
  “`bill-feature-task` is the public workflow identity for the runtime-backed
  feature-task engine”, persisted rows use `mode=runtime` — a single engine.
- `grep -rni 'runtime mode only' docs/` → no hits (token-economy and resume
  claims are unqualified).
- `runtime-kotlin/docs/architecture/feature-task-runtime-comparison.md` is
  **ARCHIVED (2026-08-09)** with a header stating the promote-vs-prose procedure
  is retired and prose no longer exists (task-5), so it cannot read as a live
  dual-maintenance obligation.

## AC-10 — final gates

The gate set is owned by the validate phase (implement does not execute builds
or tests). The required gates, per subtask-7 spec §Acceptance Criteria 6:

- `skill-bill validate`
- `(cd runtime-kotlin && ./gradlew check)`
- `scripts/validate_agent_configs`

Reproducibility: the guard at
`runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/
RuntimeArchitectureTest.kt` (`SKILL-175 no live prose-engine or opencode-zcode
product surface remains`) encodes the allowlist and fails on any reintroduced
banned token, so AC-4/AC-7 remain enforced after this subtask.
