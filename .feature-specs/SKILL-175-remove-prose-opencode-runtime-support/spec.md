# SKILL-175: Remove prose mode and OpenCode/zcode support

Status: Abandoned

> **Abandoned.** Subtasks 1–4 landed; subtasks 5–7 were left unfinished.
> Remaining work continues under successor
> [SKILL-179](../SKILL-179-complete-prose-engine-retirement/spec.md).
> Do not resume this tree as an active goal.

## Intended Outcome

Skill Bill has a single feature execution engine: **runtime**. Product prose mode
(`mode:prose`, `bill-feature-task-prose`, `feature_task_prose_*`,
`WorkflowFamily.IMPLEMENT` / `FeatureImplement*`) is fully removed.

OpenCode and zcode support is **fully removed** from the product — not merely
feature-runtime-refused. No install/symlink/MCP/native-agent/detect/enum/docs/
test surface remains for those agents. A future proper OpenCode integration
starts clean; this feature leaves no compatibility shim and no prose fallback.

Users invoke Skill Bill the same way — `/bill-feature` / `skill-bill goal` — and
get only the durable runtime path that owns shared preplan, compact projections,
budgets, agent-independent resume, and overnight multi-subtask non-degradation.
Supported agents for feature runtime remain the ones that actually work today
(Claude Code, Codex, Cursor, and any other agents still in the install/runtime
matrix after this cut — not OpenCode/zcode).

## Background

Prose mode was the original in-session orchestrator. Runtime later became the
default and the real product: DB-owned phase loop, shared preplan hydration,
projection budgets, worker leases, agent-independent resume. Prose cannot deliver
those guarantees. Keeping both means dual skills, MCP tools, CLI families,
telemetry, IDE status enums, tests, and parity locks — a second product with
weaker semantics.

OpenCode/zcode today are install-first-class but **feature-runtime refused**
(`RUNTIME_REFUSED_AGENTS`) because foreground Bash ceilings and nested-run
harvest failures cannot drive the runtime child model. Refusals currently
**redirect to prose**. Keeping install-only “support” after deleting prose would
still leave agent matrix noise and a fake support tier. Product decision: remove
OpenCode/zcode entirely for now (no traces in live product code); return later
only with a real runtime integration. Never keep prose as compatibility.

## Decisions

1. **Runtime-only feature engine.** No `mode:prose` / `mode:runtime` selector on
   feature entry. Runtime is the only path.
2. **Delete the prose product surface**, not rename it. Skills, MCP tools, CLI
   workflow family, telemetry tool events, IDE `feature-task-prose` family, and
   Kotlin `FeatureImplement*` / `TASK_PROSE` / `WorkflowFamily.IMPLEMENT` branch
   go away (with an explicit migration/quarantine story for in-flight rows).
3. **OpenCode/zcode = full removal (no traces).** Delete install enum entries,
   detection signals, symlink/MCP/native-agent providers, CLI path helpers,
   shell install/uninstall agent list entries, schemas, skills mentions, docs,
   fixtures, and tests. Do not leave `RUNTIME_REFUSED_AGENTS` rows whose only
   job was “point at prose.” A later OpenCode return is a new feature, not an
   un-delete of this shim.
4. **English “prose” stays.** Writing guidance, “governed prose” (authored skill
   text), review/PR prose, schema “prose summaries,” and similar natural-language
   uses are out of scope for deletion.
5. **Careful cutover.** Prose is load-bearing and shared with runtime on
   `feature_task_workflows.mode`. Removal is dependency-ordered: stance →
   caller + OpenCode/zcode purge → skill delete → MCP/telemetry → CLI →
   persistence/IDE → tests/docs.
6. **No dual-maintenance after this feature.** Runtime↔prose parity tests and
   “must work on both paths” requirements are retired; runtime is sole authority.

## Detailed surface map (parent inventory)

Treat this as the authoritative removal checklist. Subtasks own slices; nothing
here is optional “keep for later” except historical archives.

### A. Skills / orchestration content

| Surface | Action |
|---------|--------|
| `skills/bill-feature-task-prose/` (+ `native-agents/agents.yaml`) | Delete |
| `skills/bill-feature-task-subtask-runner/` | Delete |
| `skills/bill-feature-task/content.md` | Runtime-only; remove mode router + any opencode/zcode branches |
| `skills/bill-feature-task-runtime/content.md` | Remove prose redirect + opencode/zcode special cases |
| `skills/bill-feature-goal/content.md` | Remove `mode:prose` + `goal_prose_*`; runtime-only; drop opencode/zcode |
| `skills/bill-feature/content.md` | Drop engine-mode composition language if any |
| `skills/bill-feature-verify/content.md` | Remove opencode-safe / prose contrast framing |
| `skills/bill-code-review*` / parallel | Drop opencode/zcode from supported-agent tables/prompts |
| `orchestration/skill-classes/feature-launch-warning.yaml` | Drop prose/subtask-runner consumers |
| `orchestration/shell-content-contract/peak-hours-warner.md` | Drop prose launch surfaces |

### B. MCP

| Surface | Action |
|---------|--------|
| `feature_task_prose_{started,finished,stats,workflow_*}` | Remove |
| Legacy aliases `feature_implement_*` → prose | Remove |
| `goal_prose_{started,subtask_finished,finished}` | Remove |
| Handlers/mappers/goldens under `runtime-mcp/` | Remove or retarget |
| OpenCode/zcode MCP registration (`McpOpenCodeConfig`, `McpZcodeConfig`, register/unregister cases) | Remove |

### C. CLI

| Surface | Action |
|---------|--------|
| `skill-bill workflow {open,update,show,get,list,latest,resume,continue}` (`TASK_PROSE`) | Remove |
| `skill-bill implement-stats` | Remove |
| Feature-task / goal / parallel-review refusal copy naming prose or opencode/zcode as supported | Remove / retarget |
| Install CLI `opencode-agents-path` / `zcode-agents-path` / link-unlink helpers | Remove |
| `--parallel-review-agent` allow-lists including opencode/zcode | Remove those values |

### D. Kotlin / persistence / IDE

| Surface | Action |
|---------|--------|
| `FeatureTaskWorkflowMode.PROSE` / `mode` CHECK including `prose` | Remove after migration |
| `WorkflowFamilyKind.TASK_PROSE` / `WorkflowFamily.IMPLEMENT` | Remove |
| `FeatureImplementWorkflowDefinition` + `FeatureImplement*` stack | Remove |
| `feature_implement_sessions` table + stats builders | Remove or archive per migration subtask |
| `WorkItemKind.FEATURE_TASK_PROSE` / `IdeStatusWorkflowFamily.FEATURE_TASK_PROSE` | Remove |
| Shared `feature_task_workflows` prose branch in stores/services | Remove |
| Goal `goal_run_sessions.mode = prose` attribution | Remove |
| `InstallAgent.OPENCODE` / `ZCODE`, `NativeAgentProviderId`, link providers, symlink providers | Remove |
| `RUNTIME_REFUSED_AGENTS` / `RUNTIME_REFUSED_AGENT_MESSAGE` / `isRuntimeRefusedAgent` | Remove (no agents left to refuse this way) |
| Invoking-agent context signals for `OPENCODE*` / `ZCODE_*` | Remove |
| Dead zcode stdout normalize / orphaned PTY-only paths if solely for these agents | Remove if unused by remaining agents |

### E. Contracts / telemetry / install schemas

| Surface | Action |
|---------|--------|
| `workflow-state-schema.yaml` prose branch | Remove |
| `feature-task-execution-identity-schema.yaml` `prose` enum | Remove |
| `ide-status-schema.yaml` `feature-task-prose` | Remove |
| `telemetry-event-schema.yaml` prose/goal_prose tools+events | Remove (version bump) |
| `install-plan-schema.yaml` / `native-agent-link-inventory-schema.yaml` opencode/zcode enums | Remove (version bump as required) |
| Cloudflare telemetry proxy unions for prose/implement events | Update |

### F. OpenCode/zcode install & shell (full purge)

| Surface | Action |
|---------|--------|
| `install.sh` / `uninstall.sh` `SUPPORTED_AGENTS` + opencode/zcode skill/agent path helpers | Remove |
| `config.yaml` `opencode.skills_dir` (and any zcode config) | Remove |
| `InstallPrimitives` / `InstallOperations` / apply/native-agent link ops | Remove provider cases |
| Native-agent rendering `Opencode` / `Zcode` providers + snapshots/tests | Remove |
| Smoke scripts listing opencode/zcode | Remove those agents |
| Delegated-review provider matrix rows for Opencode/Zcode | Remove rows (not “unsupported”) |
| Docs listing OpenCode/zcode as install or runtime targets | Remove |

Optional operator note (release notes only, not code support): previously installed
symlinks under `~/.config/opencode` / `~/.zcode` may remain on disk for the
user to delete; live product code must not special-case those agents.

### G. Docs

`AGENTS.md` Product Intent, `README.md`, `docs/getting-started*.md`,
`docs/capabilities.md`, `docs/internal-skills-architecture.md`,
`docs/skill-source-generation.md`, `docs/review-telemetry.md`,
`docs/telemetry-privacy.md`, `runtime-kotlin/ARCHITECTURE.md`,
delegated-review provider docs, demo storyboard if prose-specific,
`runtime-kotlin/runtime-*/agent/decisions.md` superseded OpenCode-prose stance.

### H. Must NOT delete (English / unrelated)

- “Active prose” writing guidance in `AGENTS.md`
- “Governed prose” meaning authored skill/pack body text
- Review/PR “plain prose” reply language
- Schema “prose summary” / tie-breaker wording unrelated to mode
- Historical `.feature-specs/done/**` archives (leave as history)
- This feature’s own specs under `.feature-specs/SKILL-175-…/`

## Acceptance Criteria

1. There is no supported way to launch feature-task or goal work in `mode:prose`;
   entry skills and CLI/MCP no longer advertise or accept a prose engine selector.
2. `bill-feature-task-prose`, `bill-feature-task-subtask-runner`, and prose-phase
   native-agent definitions are removed from source and install staging.
3. MCP tools `feature_task_prose_*`, legacy `feature_implement_*` aliases, and
   `goal_prose_*` are gone; contracts/goldens/docs match.
4. CLI prose workflow family (`skill-bill workflow …`) and `implement-stats` are
   removed; operators use runtime/goal status surfaces only.
5. Kotlin persistence and application code no longer implement a live prose
   workflow family; in-flight prose rows are migrated, quarantined, or loudly
   rejected per the migration policy in subtask 1 — not silently half-executed.
6. IDE/work-list status no longer exposes `feature-task-prose` as a workflow
   family.
7. OpenCode and zcode have **no live product traces**: not in `InstallAgent` /
   provider enums, install/uninstall agent lists, MCP registration, native-agent
   render/link, detection signals, skill support tables, CLI allow-lists, or
   docs describing them as supported/install targets. `RUNTIME_REFUSED_AGENTS`
   prose-redirect machinery for those agents is gone (not rewritten into a
   permanent refuse tier).
8. Dual-path parity tests and docs that require runtime↔prose lockstep are
   removed or rewritten as runtime-only.
9. Docs and product intent describe a single runtime feature engine; token-economy
   and resume claims are not qualified as “runtime mode only.”
10. `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, and
    `scripts/validate_agent_configs` (or project-equivalent gates named in
    subtasks) pass.

## Non-Goals

- Implementing a working OpenCode or zcode feature-runtime / headless driver
  (future issue; clean re-add).
- Deleting English “prose” wording unrelated to product mode.
- Rewriting historical done feature-specs or git history.
- Changing review `mode:inline|delegated|auto` (orthogonal to feature engine).
- Removing Cursor/Claude/Codex/Junie (or other remaining) runtime support.
- Guaranteeing cleanup of already-installed files on user machines (release-note
  guidance only).

## Constraints

- Prose shares `feature_task_workflows` with runtime via `mode`; do not drop the
  table blindly — remove the prose branch and definition with an explicit row
  policy.
- Runtime phase prompt directives that currently “lockstep with prose native
  agents” need a new source of truth before those agents are deleted.
- Schema/contract version bumps must loud-fail legacy shapes with typed errors;
  remote telemetry proxy must accept or explicitly drop retired event names per
  privacy/telemetry policy.
- Do not leave any user-visible string that says “use prose,”
  `bill-feature-goal mode:prose`, or that OpenCode/zcode are supported Skill Bill
  agents.
- Live-product greps for `opencode` / `zcode` (case-insensitive product tokens)
  must be clean outside allowlisted archives and this feature-spec tree.

## Validation Strategy

- Subtask-level unit/CLI/MCP/golden/install tests as listed per subtask.
- Full `./gradlew check` in `runtime-kotlin` after persistence, MCP, and agent
  enum cuts.
- `skill-bill validate` + agent-config validation after skill/install changes.
- Repo greps for prose-mode tokens and opencode/zcode product tokens (allowlist
  archives + SKILL-175 specs only).

## Decomposition

Seven dependency-ordered subtasks — stance/migration first, then caller + full
OpenCode/zcode purge, prose skill deletion, MCP/telemetry, CLI, Kotlin/IDE
persistence, tests/docs sweep. See `decomposition-manifest.yaml`.
