# SKILL-175 Subtask 7 - Tests, parity locks, and final documentation sweep

Parent spec: [.feature-specs/SKILL-175-remove-prose-opencode-runtime-support/spec.md](spec.md)
Issue key: SKILL-175

## Scope

Finish the careful removal: eliminate dual-maintenance / runtime↔prose parity
locks, clean remaining docs and scripts, and prove the product gates green with
a single feature engine.

### Detailed surfaces

**Parity / dual-path tests to retire or rewrite runtime-only**

- Tests and comments requiring runtime↔prose guided parity (SKILL-157 lineage,
  unbounded remediation governed content locks, review-mode “both paths”
  assertions from SKILL-159, goal review parity that still names prose)
- `FeatureSpecSkillWiringContractTest` / nesting smokes expecting prose goal
  children
- Any remaining `mode:prose` fixtures in CLI/MCP/IDE goldens

**Scripts**

- `scripts/agent_install_smoke_test.sh`
- `scripts/agent_nesting_smoke_test.sh`
- `scripts/validate_agent_configs` (should follow enums; confirm no prose tool
  expectations)
- Install smoke lists if they still require prose MCP tools

**Docs final sweep (anything left after 2–6)**

- `docs/capabilities.md`
- `docs/getting-started.md` / `getting-started-for-teams.md`
- `docs/review-telemetry.md` / `telemetry-privacy.md`
- `docs/skill-source-generation.md`
- `docs/internal-skills-architecture.md`
- `docs/token-economy.md` — ensure claims are not qualified as “runtime mode
  vs prose”
- `runtime-kotlin/ARCHITECTURE.md`
- `runtime-kotlin/docs/architecture/feature-task-runtime-comparison.md` — mark
  historical / retire promote-vs-prose procedure as archival if it still reads
  as live dual-maintenance
- `docs/assets/skill-bill-demo-storyboard.md` / demo generator notes if
  prose-specific
- `orchestration/*/PLAYBOOK.md` leftovers
- Pack/skill cross-refs that still say “see prose Attribution”

**Repo-wide verification greps**

Allowlist only: this SKILL-175 feature-spec tree, subtask 1 decision
keep-examples for English “prose,” quarantine tests that must mention legacy
`prose` mode tokens, and `.feature-specs/done/**` archives. Fail the subtask if
these remain in live product paths:

- `mode:prose` / `mode:runtime` as feature **engine** selector
- `bill-feature-task-prose`
- `feature_task_prose_`
- `goal_prose_`
- `FEATURE_TASK_PROSE` / `TASK_PROSE`
- `feature-task-prose`
- Refusal strings containing `mode:prose` or `bill-feature-task-prose`
- Product tokens `\bopencode\b` / `\bzcode\b` / `OPENCODE` / `ZCODE` /
  `InstallAgent.OPENCODE` / `ZCODE` / `McpOpenCode` / `McpZcode` (no live
  install, refuse, MCP, native-agent, or docs support tier)

## Acceptance Criteria

1. No required test enforces runtime↔prose dual maintenance or prose as a live
   engine.
2. Smoke/validate scripts pass without prose skills, prose MCP tools, or
   `goal mode:prose`.
3. Documentation sweep above describes a single runtime feature engine;
   OpenCode/zcode are absent from product docs (not listed as install targets,
   refuse-tier agents, or “coming later” in-code support).
4. Repo grep policy for prose-mode tokens **and** opencode/zcode product tokens
   passes (allowlist above only).
5. Parent acceptance criteria 1–10 are satisfied end-to-end.
6. Final gates pass:
   - `skill-bill validate`
   - `(cd runtime-kotlin && ./gradlew check)`
   - `scripts/validate_agent_configs` (or `npx --yes agnix --strict .` if still
     required by repo policy)

## Non-Goals

- Implementing OpenCode/zcode runtime support (future clean re-add).
- Editing `.feature-specs/done/**` beyond optional pointers.
- Cleaning already-installed files on operator machines.

## Dependency Notes

- Depends on subtasks 1–6.
- Final subtask of the goal.

## Validation Strategy

- Full validation commands in Acceptance Criteria §6.
- Grep allowlist review for prose-mode and opencode/zcode tokens.
- Optional manual: tiny `skill-bill goal` / feature-task on a remaining
  supported agent.

## Next Path

```bash
skill-bill goal SKILL-175
```

When this subtask completes, the goal is done — prose engine is gone and
OpenCode/zcode have no live product traces.
