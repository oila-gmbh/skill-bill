---
status: Ready for implementation
issue_key: SKILL-117
source: inline user request
---

# SKILL-117: Local work list in the CLI and desktop app

## Outcome

Skill Bill users can inspect every durable feature-work run in the selected local SQLite database from either the CLI or the desktop app. Both surfaces show the same shared read model, ordered newest first, with the issue key, workflow kind, workflow id, start time, current state, and the time the run entered that state.

The primary CLI surface is:

```bash
skill-bill work list
```

The desktop left panel gains an expandable **Work** section containing the read-only work list, backed directly by the same application service. The desktop must not invoke the CLI or implement a second SQL/query interpretation.

## Background / Problem

Durable work is already present in the local database, but it is exposed only through family-specific workflow inspection commands such as `workflow list` and `verify-workflow list`. Those commands require users to know which workflow family to query, do not provide one issue-key-oriented inventory, and have no equivalent desktop surface.

The workflow tables already store `started_at`, `updated_at`, and `finished_at`. However, `updated_at` changes on every persisted workflow update, including step and artifact changes that do not change `workflow_status`. It therefore cannot truthfully answer when the workflow entered its current state. A dedicated persisted state-entry timestamp is required.

The relevant existing surfaces are:

- `runtime-infra-sqlite/.../db/core/DatabaseSchema.kt`, `DatabaseMigrations.kt`, and `DatabaseColumnMigrations.kt` for the local database schema and legacy-database healing.
- `runtime-infra-sqlite/.../db/workflow/WorkflowStateStore.kt` for workflow persistence.
- `runtime-ports/.../persistence/WorkflowStateRepository.kt` and `WorkflowStateRecord.kt` for the persistence boundary.
- `runtime-application/.../application/workflow/WorkflowService.kt` for workflow application behavior.
- `runtime-cli/.../cli/workflow/WorkflowCliCommands.kt` for the existing family-specific commands.
- `runtime-desktop/core/data/.../DesktopRuntimeApplicationServices.kt` and the `feature/skillbill` state/route/frame files for in-process runtime access and the desktop left panel.

## Decided Semantics

### What one row represents

One result row represents one persisted top-level durable work run, not one distinct issue key. Repeated issue keys are allowed because retries, verification, goals, and separate workflow modes are distinct historical work. The `workflow_id` and `workflow_kind` fields disambiguate them.

The initial inventory includes the current feature workflow tables and the issue-level goal-progress table:

- `feature_task_workflows`, covering `prose` and `runtime` modes;
- `feature_verify_workflows`; and
- `goal_issue_progress`, exposed as goal work using `parent_workflow_id` as the workflow id and `first_started_at` as the start time.

`goal_run_sessions` and `goal_subtask_events` are not additional top-level entries because `goal_issue_progress` is their issue-level work projection. A goal's child feature-task workflows remain visible as their own runs, with `workflow_kind` making the parent goal and child execution distinguishable. Review runs, quality-check sessions, telemetry outbox events, and learning records are not feature work for this list.

### Issue-key behavior

New issue-keyed feature workflow runs persist the normalized issue key as an explicit workflow-table field at the workflow creation/update seam. Goal progress already persists its issue key explicitly. The list query must not depend on ad hoc JSON extraction from `artifacts_json` as its steady-state contract.

Legacy rows are migrated without data loss. The migration may backfill an issue key only from an existing authoritative persisted value associated with that workflow (for example an existing goal-continuation artifact or runtime session joined by session id). If no authoritative value exists, the stored/read-model issue key is null and both surfaces display an explicit unknown marker (`-` in table output and `Unknown issue` in the desktop). Unknown-key rows remain in the list; they are never dropped or assigned an invented key.

### State and timestamps

- `started_at` is the persisted workflow creation time.
- `current_state` is the workflow's current `workflow_status`, using its stored wire value without CLI- or UI-specific renaming.
- `state_entered_at` changes only when `workflow_status` changes. A save that changes the current step, artifacts, session id, or another field while preserving the same status must leave it unchanged.
- New rows initialize `state_entered_at` to their creation time.
- Existing rows cannot always provide an exact historical transition time. Workflow migration backfills the best persisted bound in this order: `finished_at`, then `updated_at`, then `started_at`; goal progress uses `finished_at`, then `last_activity_at`, then `first_started_at`. Every backfill is marked estimated. The next real status transition replaces it with the transition time and clears the estimated marker.
- Timestamps remain persisted as UTC and are returned in the machine-readable model as unambiguous ISO-8601 values. Human CLI and desktop rendering uses the user's local timezone while preserving the instant.

### Ordering and refresh

The shared service returns all rows by default, ordered by `started_at DESC`, then `workflow_id DESC` for deterministic ties. The CLI supports an optional positive `--limit`; absence means no application-level truncation. The desktop initially loads the full result, provides a **Refresh** action, and does not require an open repository because the default local database is user-scoped rather than repository-scoped.

## Scope

### 1. Persistence and migration

Add the explicit issue-key and state-entry fields required by the read model to both workflow tables, and the state-entry fields to `goal_issue_progress`, through the append-only migration system and the repository's established legacy-column healing path. The schema and migration tests must cover a fresh database, a legacy database with all prior migrations recorded, and idempotent repeated opens.

Update both workflow upsert paths and the goal issue-progress update path so they:

1. initialize `state_entered_at` for inserts;
2. compare the persisted and incoming `workflow_status` on conflict;
3. advance `state_entered_at` and clear its estimated marker only on an actual status transition;
4. preserve both fields for same-status updates; and
5. continue to apply the existing `updated_at` and terminal `finished_at` behavior.

Issue-key propagation must be typed through workflow/domain/application models rather than introduced as SQL-only JSON parsing. Existing workflow-state schema validation remains authoritative for workflow snapshots; the inventory metadata must not weaken or bypass it.

### 2. Shared work-list read model and service

Introduce a provider-neutral application result model with at least:

- `issueKey: String?`
- `workflowId: String`
- `workflowKind` (`feature-task-prose`, `feature-task-runtime`, `feature-verify`, or `feature-goal`)
- `startedAt`
- `currentState`
- `stateEnteredAt`
- `stateEnteredAtEstimated: Boolean`

Add one persistence read capability that unions the supported workflow tables, applies deterministic ordering and the optional limit, and maps through one application service used by every presentation surface. This is a read-only path and must run through the existing database read/unit-of-work boundary so `--db` continues to select the same alternate database as other CLI commands.

Malformed durable rows must loud-fail through a typed result/error at the shared read seam; the CLI and desktop must not independently skip or reinterpret them.

### 3. CLI command

Add a top-level `work` command group with a `list` subcommand:

```bash
skill-bill work list [--limit N] [--format table|json]
```

Requirements:

- default human output is a stable table with columns `ISSUE`, `KIND`, `WORKFLOW`, `STARTED`, `STATE`, and `STATE SINCE`;
- estimated legacy state-entry times are visibly marked in human output and represented by `state_entered_at_estimated: true` in JSON;
- `--format json` returns a stable envelope and unmodified ISO-8601 UTC instants suitable for automation;
- an empty database is successful and produces an empty table/body or empty JSON list, not an error;
- `--limit` accepts only positive integers; and
- the existing global `--db` override works without a second database-selection option.

Family-specific `workflow list` and `verify-workflow list` commands remain unchanged.

### 4. Desktop work list

Add an expandable **Work** section to the desktop workspace left panel, alongside the existing left-panel content. Expanding it renders the read-only work list in that panel; it is not launched from a toolbar button or a modal/overlay. The implementation should follow the desktop's existing state/controller/route split and obtain data through a small desktop domain gateway backed by the shared application service in `DesktopRuntimeApplicationServices`.

The view must provide:

- initial loading, populated, empty, and error states;
- an accessible expand/collapse control and state consistent with existing left-panel sections;
- a **Refresh** action that rereads the database without restarting the app;
- distinct but non-color-only presentation of current state;
- an explicit indicator/accessible description for estimated legacy `STATE SINCE` values;
- deterministic newest-first row order identical to the CLI; and
- horizontal/vertical scrolling or responsive sizing so workflow ids and timestamps remain inspectable at supported window sizes.

The Work section remains usable when no repository is open, but is disabled while another exclusive desktop operation or blocking first-run dialog makes loading it unsafe.

All new user-facing text belongs in Compose resources. Interactive controls require roles, content descriptions or visible labels, keyboard access, and test tags following existing desktop conventions.

## Acceptance Criteria

1. `skill-bill work list` reads the selected local database and returns every persisted feature-task prose, feature-task runtime, feature-verify, and issue-level feature-goal work row in deterministic start-time descending, workflow-id descending order; `goal_run_sessions` and goal subtask-event rows are not duplicated as separate entries.
2. Every work-list item exposes nullable issue key, workflow kind, workflow id, start time, current workflow state, state-entry time, and whether that state-entry time is estimated; rows lacking a recoverable legacy issue key remain visible with an explicit unknown marker.
3. New issue-keyed workflows persist their normalized issue key in typed workflow persistence metadata, without relying on presentation-layer parsing of `artifacts_json`.
4. A newly inserted workflow or goal-progress record initializes `state_entered_at` to its start time; a status transition advances it and clears the estimated flag; an update that preserves the stored workflow/goal status leaves both state-entry fields unchanged.
5. Fresh and legacy databases receive the new fields through append-only, idempotent migration/healing behavior. Legacy workflow state-entry values use `finished_at`, then `updated_at`, then `started_at`; goal values use `finished_at`, then `last_activity_at`, then `first_started_at`. Backfilled values remain marked estimated until a real transition occurs.
6. The CLI's default table contains `ISSUE`, `KIND`, `WORKFLOW`, `STARTED`, `STATE`, and `STATE SINCE`, visibly identifies estimated values, succeeds on an empty database, and honors an optional positive `--limit` plus the existing global `--db` override.
7. `skill-bill work list --format json` returns the shared read model in a stable envelope, preserves unambiguous UTC ISO-8601 instants, and includes `state_entered_at_estimated` for every row.
8. The desktop left panel exposes an expandable **Work** section that can open without a repository, loads through an in-process gateway backed by the same application service as the CLI, and never shells out to `skill-bill` or duplicates the inventory SQL.
9. The desktop work section renders the same fields and ordering as the CLI and has verified loading, populated, empty, error, expand/collapse, refresh, estimated-time accessibility, and constrained-window scrolling behavior.
10. Existing `workflow list` and `verify-workflow list` behavior and workflow-state schema loud-fail guarantees remain unchanged, and the full Kotlin quality gate passes.

## Non-goals / Constraints

- Do not add workflow mutation, resume, cancel, delete, or retry actions to the list.
- Do not collapse multiple runs with the same issue key into one row.
- Do not create top-level entries for review runs, quality checks, telemetry records, learnings, `goal_run_sessions`, or goal-subtask events; `goal_issue_progress` is the goal-level source.
- Do not add remote/Linear synchronization, repository scanning, or `.feature-specs` filesystem discovery; the database is the source of this view.
- Do not infer missing issue keys from workflow ids, branch names, free text, or directory names.
- Do not use `updated_at` as an exact state-entry time.
- Do not introduce agent-specific behavior or change workflow-state contract enums.

## Validation Strategy

- Persistence tests for fresh-schema columns, append-only migration registration, fully migrated legacy-database healing, authoritative issue-key backfill, unknown issue preservation, and repeated-open idempotence.
- Workflow and goal persistence tests for insert, status transition, same-status update, terminal transition, prose/runtime mode, verify and goal work, deterministic union ordering, empty results, and limit behavior.
- Application-service tests proving a single shared mapping and typed failure for malformed persisted timestamps/rows.
- CLI tests for human table, JSON envelope, all workflow kinds, repeated issue keys, unknown issue keys, estimated markers, empty database, invalid/valid limits, ordering, and global `--db` selection.
- Desktop controller/gateway tests for load, expand/collapse, refresh, empty, and error transitions, plus Compose UI tests for section availability, column content, ordering, scrolling, accessible estimated-state labeling, and test tags.
- Run the repository quality entry point and full Kotlin gate:

```bash
bill-code-check
(cd runtime-kotlin && ./gradlew check)
```

## Expected Affected Areas

- `runtime-kotlin/runtime-infra-sqlite` schema, migrations, workflow store, and tests
- `runtime-kotlin/runtime-ports` persistence capability and work-list models
- `runtime-kotlin/runtime-application` shared work-list service and mappings
- `runtime-kotlin/runtime-cli` top-level command registration, rendering, and tests
- `runtime-kotlin/runtime-desktop/core/domain` work-list gateway and UI state models
- `runtime-kotlin/runtime-desktop/core/data` in-process runtime service adapter
- `runtime-kotlin/runtime-desktop/feature/skillbill` left panel, controller/state, work section, resources, accessibility, and tests

## Next Path

```bash
Run bill-feature on .feature-specs/SKILL-117-local-work-list/spec.md
```
