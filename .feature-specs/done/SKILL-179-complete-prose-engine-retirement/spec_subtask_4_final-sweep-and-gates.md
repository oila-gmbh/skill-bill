# SKILL-179 Subtask 4 - Final sweep, parity locks, and gates

Parent spec: [.feature-specs/SKILL-179-complete-prose-engine-retirement/spec.md](spec.md)
Issue key: SKILL-179

## Scope

Finish SKILL-175's unfinished subtask 7 sweep and prove every product gate
green with a single feature engine.

### Debug instrumentation

SKILL-175's final commit `fb973bf42` shipped leftover debug `println` calls,
including one in production code
(`WorkflowService.kt`, `println("DEBUG sync ...")`). These were removed in the
working tree ahead of this feature; confirm none remain and that no equivalent
instrumentation has re-entered. `println` in production runtime code is not an
accepted logging mechanism.

### Parity locks and dual-maintenance tests

Retire or rewrite runtime-only any remaining test or comment that requires
runtime/prose guided parity: SKILL-157 lineage, unbounded remediation governed
content locks, SKILL-159 review-mode "both paths" assertions, and goal review
parity that still names prose.

### Scripts

- `scripts/agent_install_smoke_test.sh`
- `scripts/agent_nesting_smoke_test.sh`
- `scripts/validate_agent_configs`
- install smoke lists, if any still require prose MCP tools

### Documentation sweep

- `docs/capabilities.md`
- `docs/getting-started.md`, `docs/getting-started-for-teams.md`
- `docs/review-telemetry.md`, `docs/telemetry-privacy.md`
- `docs/skill-source-generation.md`
- `docs/internal-skills-architecture.md`
- `docs/token-economy.md` — claims must not be qualified as "runtime mode vs prose"
- `runtime-kotlin/ARCHITECTURE.md`
- `runtime-kotlin/docs/architecture/feature-task-runtime-comparison.md` — retire
  or mark archival if it still reads as live dual maintenance
- `orchestration/*/PLAYBOOK.md` leftovers
- pack and skill cross-references still pointing at prose Attribution

### Predecessor pointer

Add a pointer in the SKILL-175 spec tree recording that it was abandoned and
that the remaining work landed under SKILL-179. Do not otherwise edit that tree.

## Acceptance Criteria

1. `(cd runtime-kotlin && ./gradlew check)` passes with no test failures and no
   detekt violations.
2. `skill-bill validate` passes.
3. `scripts/validate_agent_configs` passes, and the install and nesting smoke
   scripts pass without prose skills, prose MCP tools, or `goal mode:prose`.
4. No `println` debug instrumentation remains in production runtime code.
5. No required test enforces runtime/prose dual maintenance or prose as a live
   engine.
6. The documentation surfaces listed above describe a single runtime feature
   engine, with no "runtime mode vs prose" qualifiers.
7. The repo-wide prose-engine and OpenCode/zcode guard test passes with an
   unwidened allowlist.
8. The SKILL-175 spec tree carries a pointer recording its abandonment and this
   successor.

## Non-Goals

- Re-adding OpenCode/zcode runtime support.
- Editing `.feature-specs/done/**`.
- Cleaning already-installed files on operator machines.

## Dependency Notes

- Depends on subtasks 1, 2, and 3. This is the final subtask; the full gate run
  is only meaningful once the test surfaces and lifecycle fixes have landed.

## Validation Strategy

- Full gate set in Acceptance Criteria 1-3.
- Grep allowlist review for prose-mode and OpenCode/zcode tokens.

## Next Path

```bash
skill-bill goal SKILL-179
```
