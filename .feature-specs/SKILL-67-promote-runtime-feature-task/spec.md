# SKILL-67 - promote runtime feature-task

Created: 2026-06-05
Status: Draft
Issue key: SKILL-67
Parent: executes the **promote** branch of the authoritative SKILL-65 promote/kill
criterion (`.feature-specs/SKILL-65-experimental-feature-task-runtime/spec.md` ->
"Promote / Kill Criterion (Authoritative)"), building on SKILL-65 / SKILL-65.1
runtime work and the SKILL-66 goal-telemetry family.

## Decomposition

This feature is decomposed because promotion spans four distinct seams plus a
closing gate, in strict dependency order:

1. the canonical runtime surface — rename the `feature-task-runtime` CLI command
   and `feature_task_runtime_*` MCP tools to canonical `feature-task` /
   `feature_task_*` names, de-experimentalize them, and keep the legacy
   `feature_implement_*` family alive (cli + mcp + contracts + tests);
2. the skill promotion — make the runtime-backed skill the canonical
   `bill-feature-task`, demote the prose orchestrator to a deprecated
   `bill-feature-task-legacy`, and wire install/uninstall aliases (skills +
   install);
3. the goal coupling — `skill-bill goal` invokes the canonical runtime directly
   per subtask using the existing `--goal-*` flags, moving child subtask state
   from the `feature_implement` workflow family to `feature_task_runtime`
   (application);
4. routing, docs, and the recorded promote decision — `bill-feature` dispatch,
   `ARCHITECTURE.md` / README / AGENTS.md catalog, the dated maintainer promote
   decision, and the deprecation-window contract (skills + docs + decisions);
5. the closing validation gate — full maintainer command set plus the
   golden/parity test sweep across every renamed surface (gate).

Implement on one branch with a commit per subtask:

1. [Canonical Runtime CLI and MCP Surface](./spec_subtask_1_canonical-runtime-cli-and-mcp-surface.md)
2. [Skill Promotion and Legacy Deprecation](./spec_subtask_2_skill-promotion-and-legacy-deprecation.md)
3. [Goal Runner Direct Runtime Coupling](./spec_subtask_3_goal-runner-direct-runtime-coupling.md)
4. [Dispatcher, Docs, and Recorded Promote Decision](./spec_subtask_4_dispatcher-docs-and-recorded-promote-decision.md)
5. [Validation Gate and Rename Test Sweep](./spec_subtask_5_validation-gate-and-rename-test-sweep.md)

## Sources

- The authoritative promote/kill criterion in
  `.feature-specs/SKILL-65-experimental-feature-task-runtime/spec.md`
  ("Promote / Kill Criterion (Authoritative)") is the single source for the
  criterion and recorded promote decision. SKILL-67 executes that decision.
- The pointer entry in root `agent/decisions.md`
  (`[2026-06-03] feature-task-runtime-promote-kill-criterion-pointer`) names the
  same single authoritative source and says it is revisited "when the maintainer
  records a promote or kill decision," which this feature does.
- Scoping exploration on 2026-06-05 confirmed the current state:
  - `bill-feature-task` is the 1045-line prose orchestrator
    (`skills/bill-feature-task/content.md`); it drives the `feature_implement_*`
    MCP/workflow family via `skill-bill workflow` commands.
  - `bill-feature-task-runtime` is the 102-line experimental trigger
    (`skills/bill-feature-task-runtime/content.md`) over the
    `skill-bill feature-task-runtime` driver; it owns its own phase loop, schema
    gates, and durable `feature_task_runtime_*` workflow family.
  - The runtime CLI (`FeatureTaskRuntimeCliCommands.kt`) already accepts
    `--goal-parent-issue-key`, `--goal-subtask-id`, `--goal-branch`, and
    `--suppress-pr` — goal coupling is pre-wired but not yet connected.
  - `skill-bill goal` launches each child subtask with a prose prompt that
    hard-codes "Use the installed `bill-feature-task` skill in non-interactive
    goal-continuation mode" (`AgentRunCommandBuilders.kt:150`); the child then
    drives `skill-bill workflow continue` against the `feature_implement`
    workflow family.
  - `bill-feature` (`skills/bill-feature/content.md`) routes single-spec ->
    `bill-feature-task`, decomposed -> `bill-feature-goal`, and never references
    the runtime.
  - Skill name == directory name under `skills/`; `InstallLegacySkillNames.kt`
    is the established rename/alias mechanism (precedent: SKILL-13 sunset of
    `bill-feature-implement` -> `bill-feature-task`).
  - MCP tool names/descriptions live in `runtime-mcp/.../McpToolRegistry.kt`;
    dispatch in `McpToolDispatcher.kt`. Both the `feature_implement_*` and
    `feature_task_runtime_*` families are registered today.
- Maintainer decisions recorded at intake on 2026-06-05 (authoritative for this
  spec):
  1. The prose orchestrator is **deprecated as `bill-feature-task-legacy` with a
     removal window**, not hard-deleted in this feature.
  2. `skill-bill goal` couples to the runtime by **invoking it directly** per
     subtask (not by routing through the skill prose).
  3. The runtime CLI/MCP surface is **renamed to canonical** names; the legacy
     `feature_implement_*` family is retired only at window close.

## Problem

SKILL-65 shipped the runtime-driven feature-task loop as an explicitly
experimental, additive capability alongside the prose orchestrator, with a
written ban on indefinite dual maintenance. The runtime has since proven out in
practice. Two execution paths for the same job now coexist:

- two skills (`bill-feature-task` prose, `bill-feature-task-runtime`),
- two CLI entry points (`skill-bill workflow ...` vs
  `skill-bill feature-task-runtime ...`),
- two MCP/workflow families (`feature_implement_*` vs `feature_task_runtime_*`),
- a goal runner still bound to the prose path and the `feature_implement` family.

This is exactly the dual-maintenance state the SKILL-65 criterion forbids. The
naming is also inconsistent: the canonical skill says "task" while the runtime
command/tools say "task-runtime", and the goal loop does not benefit from the
runtime's durable per-phase state and runtime-owned observability.

## Goals

1. Make the runtime-driven path the single canonical feature-task path, under the
   canonical `bill-feature-task` skill name and canonical `feature-task` /
   `feature_task_*` CLI and MCP names.
2. Demote the prose orchestrator to a clearly deprecated, non-auto-routed
   `bill-feature-task-legacy` with a defined removal window.
3. Make `skill-bill goal` run each decomposed subtask through the canonical
   runtime directly, so goal subtasks gain the same durable per-phase state,
   schema gates, and runtime-owned observability as standalone feature-task runs.
4. Record the maintainer promote decision in the single authoritative source and
   update every pointer/doc/catalog so no doc restates or contradicts it.
5. End the dual-maintenance state for everything except the time-boxed legacy
   surface, which is retired (skill + `feature_implement_*` family together) at
   window close.

## Non-Goals

- Do **not** hard-delete `bill-feature-task-legacy` or the `feature_implement_*`
  MCP/workflow family in this feature. Their coordinated removal at window close
  is a scheduled follow-up (see "Deprecation Window Contract").
- Do **not** weaken or skip any existing review, audit, or validation gate to
  make promotion land — the SKILL-65 criterion forbids it.
- Do **not** change the phase loop, schema gates, handoff payloads, or
  comparison harness defined by SKILL-65 / SKILL-65.1; this feature renames and
  rewires surfaces, it does not redesign the loop.
- Do **not** migrate already-completed historical `feature_implement` workflow
  rows into the canonical family; existing rows remain readable through the
  retained legacy surface for the window.
- Do **not** re-run or re-decide the SKILL-65 comparison evidence; the promote
  decision is taken as given by the maintainer at intake.

## Target User Experience

- A user (or `bill-feature`) running a single governed spec invokes
  `bill-feature-task`, which is now the runtime-backed skill: one confirmation
  gate, then `skill-bill feature-task run <issue_key> <spec_path>` drives the
  durable phase loop with runtime-owned per-phase state.
- A user running a decomposed goal invokes `bill-feature-goal` ->
  `skill-bill goal <issue_key>`; each child subtask now runs through the
  canonical runtime, so `goal status` / `goal watch` report runtime-owned phase
  state and the per-phase schema gates apply to every subtask.
- A user who still types `bill-feature-task-runtime` (the old skill name) is
  transparently resolved to the canonical `bill-feature-task` via the install
  alias.
- A user who explicitly invokes `bill-feature-task-legacy` gets the prose
  orchestrator with a loud deprecation notice and a removal-window date; it is
  never auto-routed by `bill-feature`.
- MCP clients see canonical `feature_task_*` tools; the deprecated
  `feature_implement_*` tools remain callable (for the window) with deprecation
  notes in their descriptions.

## Acceptance Criteria

1. The runtime CLI command is canonical `skill-bill feature-task`
   (`run` / `resume` / `status`), with `feature-task-runtime` retained as a
   hidden/deprecated alias for the removal window; help text no longer labels the
   path EXPERIMENTAL.
2. The runtime MCP/workflow family is exposed under canonical `feature_task_*`
   names; the `feature_task_runtime_*` names are retained as deprecated aliases
   for the window; the legacy `feature_implement_*` family is kept registered and
   functional (not retired) with deprecation notes.
3. The runtime-backed skill is installed as canonical `bill-feature-task`; the
   prose orchestrator is preserved as `bill-feature-task-legacy`, marked
   deprecated and never auto-routed; `InstallLegacySkillNames.kt`, `install.sh`,
   and `uninstall.sh` map `bill-feature-task-runtime` -> `bill-feature-task` and
   leave existing legacy aliases intact.
4. `skill-bill goal` launches each decomposed subtask by invoking the canonical
   runtime directly with the existing `--goal-*` flags (no prose-skill
   continuation prompt), and child subtask state is recorded in the
   `feature_task_runtime` workflow family; goal observability/telemetry
   (SKILL-61 / SKILL-66) and terminal-outcome reconciliation continue to pass.
5. `bill-feature` routes single-spec work to the canonical (runtime-backed)
   `bill-feature-task` and never to `bill-feature-task-legacy`.
6. The maintainer promote decision is recorded in the single authoritative source
   (the SKILL-65 parent spec section), the root `agent/decisions.md` pointer is
   updated to reflect the recorded decision, and `ARCHITECTURE.md`, README, and
   the AGENTS.md skill catalog no longer describe the runtime as experimental and
   do not restate the criterion.
7. A "Deprecation Window Contract" is documented in a single authoritative place:
   it names the window, states that `bill-feature-task-legacy` and the
   `feature_implement_*` family are retired together at window close, and forbids
   leaving either alive past the window. The actual removal is explicitly a
   scheduled follow-up, not part of this feature.
8. No review/audit/validation gate is weakened; the promoted path passes the same
   unmodified maintainer gate it did as the experimental path.
9. Maintainer validation passes:
   - `skill-bill validate`
   - `(cd runtime-kotlin && ./gradlew check)`
   - `npx --yes agnix --strict .`
   - `scripts/validate_agent_configs`

## Design Notes

- **Canonical naming.** Command `feature-task`; MCP/workflow prefix
  `feature_task_`. Both the prior experimental name (`feature_task_runtime_*`,
  `feature-task-runtime`) and the prose name (`feature_implement_*`) are distinct
  from the canonical prefix, so aliases avoid collisions. Prefer retaining old
  names as thin deprecated aliases over breaking external callers mid-window.
- **Alias mechanism.** Reuse `InstallLegacySkillNames.kt` (the SKILL-13
  precedent). The skill rename is a directory move plus an alias entry; no new
  mechanism is introduced.
- **Goal coupling seam.** The single hard-coded reference at
  `AgentRunCommandBuilders.kt:150` and its surrounding goal-continuation prompt
  builder are the seam. The runtime already accepts the `--goal-*` flags, so the
  change is: build a direct `skill-bill feature-task run/resume ... --goal-*`
  invocation instead of a prose "use the skill" prompt, and point goal
  resume/continue at the `feature_task_runtime` workflow family. Keep goal's flat
  worker model, fresh-child-per-attempt boundary, and durable-state authority
  (SKILL-56/58) intact.
- **Single source of truth.** The promote decision and the criterion stay in the
  SKILL-65 parent spec; every other doc points to it. The deprecation-window
  contract likewise gets exactly one authoritative home to avoid the
  dual-maintenance trap the criterion warns about.
- **Boundary contracts** from `runtime-kotlin/ARCHITECTURE.md` and
  `RuntimeArchitectureTest` still hold: application must not depend on
  Clikt/MCP/JDBC; schema validators are reached only through domain-owned ports;
  `runtime-core` is the only composition root.

## Deprecation Window Contract

This is the authoritative home for the window (subtask 4 records it; no other doc
restates it):

- Window name: **SKILL-67 One-Release Legacy Compatibility Window**.
- For this window, three surfaces stay alive and functional but deprecated:
  `bill-feature-task-legacy` (skill), the `feature_implement_*` MCP/workflow
  family, and the `feature-task-runtime` / `feature_task_runtime_*` aliases.
- The legacy skill depends on the `feature_implement_*` family, so the two must be
  retired **together** in the same follow-up; neither may outlive the other or
  remain alive past the window.
- At window close a scheduled follow-up removes the legacy skill and the
  `feature_implement_*` family and drops the deprecated aliases. That removal is
  out of scope here.

## Validation Strategy

Per-subtask validation is defined in each subtask spec. The closing subtask runs
the full maintainer command set and the golden/parity test sweep across every
renamed CLI/MCP/skill surface, and confirms no gate was weakened.

## Open Questions

- Canonical MCP tool spelling for stats/started/finished (`feature_task_started`
  vs reusing `feature_implement_*` semantics) — resolved in subtask 1 against the
  telemetry-event contract; default to `feature_task_*` mirroring the command.
