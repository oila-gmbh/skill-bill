# SKILL-175 Subtask 3 - Delete prose skills and native agents; relocate briefing SoT

Parent spec: [.feature-specs/SKILL-175-remove-prose-opencode-runtime-support/spec.md](spec.md)
Issue key: SKILL-175

## Scope

Remove the prose orchestrator skill family from source and install staging, and
relocate any runtime briefing “lockstep with prose native agents” source of
truth so runtime prompts do not depend on deleted files.

### Detailed surfaces to delete

- `skills/bill-feature-task-prose/content.md`
- `skills/bill-feature-task-prose/native-agents/agents.yaml` and the agents:
  - `bill-feature-task-pre-planning`
  - `bill-feature-task-planning`
  - `bill-feature-task-implementation`
  - `bill-feature-task-implementation-fix`
  - `bill-feature-task-completeness-audit`
  - `bill-feature-task-quality-check`
  - `bill-feature-task-pr-description`
  - `bill-feature-task-subtask-runner` (bundle entry)
- `skills/bill-feature-task-subtask-runner/` (standalone internal skill + content)
- Install/render expectations that stage `bill-feature-task-prose.md` under
  `bill-feature` (`FeatureFamilyRenderingIntegrationTest` and related)
- `orchestration/skill-classes/feature-launch-warning.yaml` consumers naming
  prose / subtask-runner
- `orchestration/shell-content-contract/peak-hours-warner.md` prose launch refs
- Smoke scripts that require prose MCP tools or `goal mode:prose` nesting:
  - `scripts/agent_install_smoke_test.sh`
  - `scripts/agent_nesting_smoke_test.sh`
  (update or remove prose assertions; do not leave failing required smokes)

### Briefing source-of-truth relocation

- `FeatureTaskRuntimePhasePromptDirectives.kt` (and any comments/tests that say
  keep lockstep with prose `agents.yaml`) must point at a **runtime-owned**
  briefing source (existing runtime prompts, a runtime-local agents bundle, or
  documented contract files) **before or as** prose `agents.yaml` is deleted.
- `UnboundedRemediationLoopGovernedContentTest` / SKILL-157-style governed
  content parity tests that require prose+runtime lockstep → runtime-only.

### Docs

- `docs/getting-started.md` sections listing prose phase native agents
- `docs/skill-source-generation.md` / `docs/internal-skills-architecture.md`
  internal-skill maps that still show prose sidecar
- `docs/capabilities.md` durable prose workflow bullet if still present after
  subtask 2

## Acceptance Criteria

1. `skills/bill-feature-task-prose/` and `skills/bill-feature-task-subtask-runner/`
   no longer exist in the repository.
2. Install/render/validate no longer expect or stage
   `bill-feature-task-prose.md` or prose-phase native agents for the feature
   family.
3. Runtime phase briefing directives do not reference deleted prose
   `native-agents/agents.yaml` as required lockstep; a runtime-owned source of
   truth exists and is tested.
4. Skill-class / peak-hours warner configuration no longer lists deleted prose
   skills.
5. Smoke scripts and feature-family rendering tests pass without prose artifacts.
6. `skill-bill validate` succeeds after the skill tree deletion.
7. Grep over `skills/` for `bill-feature-task-prose`, `mode:prose` engine
   instructions, and `goal_prose_` returns no governed product-path hits.

## Non-Goals

- Removing MCP tool registrations (subtask 4) — skills may already stop calling
  them; server cleanup is separate.
- Deleting Kotlin `FeatureImplement*` persistence (subtask 6).
- OpenCode/zcode purge (owned by subtask 2; verify no reintroduction here).

## Dependency Notes

- Depends on subtasks 1–2 (callers no longer route to these skills).
- Should complete before or in lockstep with MCP removal so agents are not
  instructed to call tools that still exist only briefly — prefer skills gone
  first, then MCP (subtask 4).

## Validation Strategy

- `skill-bill validate`
- Feature family rendering / skill-class loader tests
- Runtime prompt directive tests after SoT relocation
- Repo grep for deleted skill names under `skills/` and install staging fixtures

## Next Path

```bash
skill-bill goal SKILL-175
```

After this subtask: remove MCP + telemetry contracts (subtask 4).
