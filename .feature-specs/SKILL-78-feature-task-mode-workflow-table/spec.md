---
status: Complete
---

# SKILL-78 - feature-task mode workflow table

Created: 2026-06-11
Issue key: SKILL-78
Mode: single_spec

## Intended Outcome

Make `bill-feature-task` the durable workflow identity for feature-task work,
with `mode: prose | runtime` recorded on each workflow row. Prose and runtime
remain separate implementation modes with separate validators and artifact
contracts, but they persist in one feature-task workflow table and resolve
through one workflow lookup path.

The product model is:

- `bill-feature-task` is the skill surface.
- `prose` and `runtime` are modes selected by the router.
- `bill-feature-task-prose` and `bill-feature-task-runtime` are implementation
  skills, not separate top-level workflow families.

## Motivation

SKILL-70 promoted prose mode to first-class status and made `bill-feature-task`
the router. The durable persistence model still encodes the earlier split:
prose lives in `feature_implement_workflows`, while runtime lives in
`feature_task_runtime_workflows`. That naming and table split makes prose look
like an older feature-implement path and runtime look like the canonical
feature-task workflow, which contributed to bugs where prose mode was treated as
legacy.

The project has not shipped yet, so there is no need to preserve old local
database rows or ship migrations for existing installs. The storage model should
be corrected now to match the product contract before release.

## Current State

- `skills/bill-feature-task/content.md` is the router and accepts
  `mode:prose` or `mode:runtime`.
- `skills/bill-feature-task-prose/content.md` declares prose as first-class and
  currently uses `feature_implement_workflow_*` durable workflow tools.
- `skills/bill-feature-task-runtime/content.md` launches the foreground
  `skill-bill feature-task` runtime.
- SQLite has separate workflow tables:
  - `feature_implement_workflows`
  - `feature_task_runtime_workflows`
  - `feature_verify_workflows`
- `WorkflowStateRepository` exposes separate feature-implement and
  feature-task-runtime repository methods.
- Runtime phase records, phase briefings, resolved branch data, goal
  continuation data, and the phase ledger ride inside runtime-mode
  `artifacts_json`.

## Desired Model

Introduce a single feature-task workflow table, for example
`feature_task_workflows`, with an explicit mode discriminator:

```text
workflow_id TEXT PRIMARY KEY
skill_name TEXT NOT NULL DEFAULT 'bill-feature-task'
mode TEXT NOT NULL CHECK(mode IN ('prose', 'runtime'))
implementation_skill TEXT NOT NULL
contract_version TEXT NOT NULL
workflow_status TEXT NOT NULL DEFAULT 'pending'
current_step_id TEXT NOT NULL DEFAULT ''
session_id TEXT NOT NULL DEFAULT ''
steps_json TEXT NOT NULL DEFAULT ''
artifacts_json TEXT NOT NULL DEFAULT ''
started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
finished_at TEXT
```

The exact column names may vary to match local conventions, but the persisted
record must explicitly identify both the public skill (`bill-feature-task`) and
the selected mode (`prose` or `runtime`).

Mode-specific contracts remain separate:

- `mode=prose` uses the prose step ids and prose artifact names from
  `bill-feature-task-prose`.
- `mode=runtime` uses the runtime phase ids, runtime phase record artifacts,
  phase briefings, resolved branch artifact, goal continuation artifacts, and
  runtime phase ledger.

One table must not mean one shared artifact schema. Reads must dispatch
validation and decoding by mode.

## Acceptance Criteria

1. SQLite persists feature-task prose and runtime workflow rows in one
   feature-task workflow table, not in separate
   `feature_implement_workflows` / `feature_task_runtime_workflows` tables.
2. Each feature-task workflow row records `mode` as exactly `prose` or
   `runtime`, and records the public skill identity as `bill-feature-task`.
3. The prose implementation opens, updates, gets, lists, resumes, and continues
   workflows through the shared feature-task workflow store with `mode=prose`.
4. The runtime implementation opens, updates, gets status, resumes, and records
   phase state through the same feature-task workflow store with `mode=runtime`.
5. Workflow lookup by `workflow_id` no longer needs callers to know which
   feature-task table owns the row; the persistence layer resolves the row and
   exposes its mode.
6. Validation and decoding are mode-aware:
   - prose rows validate against the prose workflow definition, step ids,
     required artifacts, continuation sections, and artifact contract;
   - runtime rows validate against the runtime phase workflow definition,
     runtime phase output contract, phase-record artifacts, and runtime ledger
     contract.
7. A prose workflow row cannot be decoded as runtime, and a runtime workflow row
   cannot be decoded as prose; mismatches loud-fail with a typed workflow-state
   error.
8. Runtime-specific artifact keys remain available only to runtime mode:
   `feature_task_runtime_phase_records`,
   `feature_task_runtime_phase_ledger`,
   `feature_task_runtime_phase_briefings`,
   `feature_task_runtime_resolved_branch`, and goal-continuation artifacts.
9. Prose-specific step ids and artifact names remain unchanged for prose mode:
   `assess`, `create_branch`, `preplan`, `plan`, `implement`, `review`,
   `audit`, `validate`, `write_history`, `commit_push`, `pr_description`,
   `finish`, and their existing prose artifacts.
10. Feature-verify workflow persistence is not collapsed into this table unless
    the implementation introduces a generic all-workflows table with a distinct
    `workflow_family`; this feature only requires unifying feature-task
    prose/runtime persistence.
11. Pre-release reset is acceptable: no compatibility migration is required for
    existing local rows in old feature-task tables. Tests and setup should create
    fresh rows in the new shape.
12. Any old compatibility aliases or names that remain (`feature_implement_*`,
    `feature_task_runtime_*`) are clearly documented as aliases to the
    feature-task mode store, not as separate authoritative workflow families.
13. `runtime-kotlin/ARCHITECTURE.md` and any relevant skill content are updated
    so prose is described as `bill-feature-task mode=prose`, runtime is
    described as `bill-feature-task mode=runtime`, and neither is described as a
    legacy workflow store.
14. Tests cover:
    - opening a prose feature-task workflow writes one row with `mode=prose`;
    - opening a runtime feature-task workflow writes one row with `mode=runtime`;
    - lookup by workflow id returns the correct mode without table-specific
      probing by the caller;
    - prose update/get/continue behavior still uses prose step and artifact
      contracts;
    - runtime status/resume/phase-record behavior still uses runtime phase and
      artifact contracts;
    - malformed or mismatched mode data loud-fails with a typed workflow-state
      error;
    - old feature-task table names are absent or no longer used as
      authoritative stores.
15. `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`,
    `npx --yes agnix --strict .`, and `scripts/validate_agent_configs` pass.

## Non-Goals

- No compatibility migration for pre-existing local SQLite rows.
- No automatic import from `feature_implement_workflows` or
  `feature_task_runtime_workflows`.
- No attempt to make prose and runtime share one artifact schema.
- No change to the public router behavior: `mode:prose` remains the default and
  `mode:runtime` remains explicit.
- No removal of the implementation skills
  `bill-feature-task-prose` or `bill-feature-task-runtime`.
- No feature-verify persistence redesign unless a generic all-workflows table is
  chosen as an implementation detail and feature-verify remains clearly
  separated by family.
- No changes to decomposition-manifest semantics beyond updating any workflow-id
  lookup assumptions.

## Constraints

- Preserve the authored skill source contract: edit `content.md`, not generated
  `SKILL.md` wrappers.
- Keep mode as a first-class persisted value; do not infer mode only from
  workflow id prefixes, implementation skill names, or artifact keys.
- Keep loud-fail behavior for missing manifests, malformed workflow snapshots,
  wrong contract versions, unknown modes, and mode/contract mismatches.
- Keep runtime-owned observability for runtime mode: the runtime still stamps
  phase start/finish times, durations, attempts, resolved agent id, status, and
  ledger entries.
- Keep prose-owned workflow updates for prose mode: the prose orchestrator still
  persists phase boundaries through workflow update tools.
- Keep the runtime architecture boundaries: domain owns pure workflow rules,
  application owns orchestration, infrastructure owns SQLite, and adapters own
  CLI/MCP shape.

## Validation Strategy

- Add or update SQLite schema tests for the new table and required `mode`
  discriminator.
- Add or update repository tests proving prose and runtime rows coexist in the
  same table and round-trip through the correct mode-specific APIs.
- Add workflow service tests for mode-aware open/get/list/latest/resume/continue
  behavior.
- Add runtime phase recorder/status/resume tests proving runtime artifacts still
  persist and decode from `mode=runtime` rows.
- Add prose workflow tests proving `mode=prose` uses the existing prose step
  sequence and continuation contract.
- Run:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

## Next Path

Run bill-feature-task on .feature-specs/SKILL-78-feature-task-mode-workflow-table/spec.md
