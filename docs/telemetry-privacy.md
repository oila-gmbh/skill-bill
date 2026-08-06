# Telemetry Privacy

This document states what Skill Bill collects, where it goes, how to turn it off, and what
correlates events. It describes shipped behavior only.

The default telemetry level is `anonymous`. Collection is on unless you disable it.

## Levels

| Level | Behavior |
|-------|----------|
| `off` | Nothing is transmitted: `sync` and `autoSync` short-circuit on the disabled level. Two events are still written to the local outbox — `skillbill_runtime_exception` and `skillbill_feature_task_runtime_projection_measurement` — see [What is still queued at `off`](#what-is-still-queued-at-off). |
| `anonymous` | Counts, enums, durations, and identifiers derived by one-way hash. No file paths, descriptions, notes, learning text, error messages, or non-Skill-Bill stack frames. |
| `full` | Everything in `anonymous`, plus the free-text and path fields marked below. |

## Per-level collection

`✓` = collected as-is. `hashed` = replaced with a salted SHA-256 prefix. `redacted` = replaced
with a fixed placeholder. `—` = not present in the payload. `queued only` = written to the local
outbox but never transmitted while the level is `off`.

### What is still queued at `off`

Two producers enqueue without consulting the telemetry level:

- `TelemetryService.captureException` enqueues `skillbill_runtime_exception` guarded only by
  `database.databaseExists`; the level is used solely to choose redaction, so at `off` the row is
  written with the redacted `error_message` (`[redacted]`) and `skillbill.` frames only.
- `FeatureTaskRuntimePhaseRecorder.recordProjectionMeasurements` enqueues
  `skillbill_feature_task_runtime_projection_measurement` with no telemetry gate; the row carries
  the bounded counters and the repository checkpoint fingerprint.

Nothing is transmitted while the level is `off`: `TelemetryService.sync` and
`TelemetryService.autoSync` return before upload when the resolved settings are disabled.

Enabling telemetry later does not discard those rows. `clearsPendingOutbox("off", "anonymous")` is
`false` — the pending outbox is cleared only on a downgrade or a move to `off` — so rows enqueued
while `off` remain pending and upload on the next sync after you enable. Clear them by running
`skill-bill telemetry disable` (or `set-level off`), which clears the pending outbox, before
enabling.

### Envelope, on every uploaded event

| Field | off | anonymous | full | Source |
|-------|-----|-----------|------|--------|
| `event` (event name) | — | ✓ | ✓ | `telemetryProxyBatchPayload` |
| `timestamp` (local enqueue time) | — | ✓ | ✓ | `telemetryProxyBatchPayload` |
| `install_id` (also the `distinct_id`) | — | ✓ | ✓ | `telemetryProperties` |
| `skill_bill_version` | — | ✓ | ✓ | `telemetryProperties`, from the `telemetry_outbox.skill_bill_version` column; omitted on rows enqueued before release attribution existed |
| `$process_person_profile` (always `false`) | — | ✓ | ✓ | `telemetryProperties` |

### `skillbill_goal_started`, `skillbill_goal_finished`, `skillbill_goal_issue_finished`, `skillbill_goal_subtask_finished`

| Field | off | anonymous | full | Source |
|-------|-----|-----------|------|--------|
| `workflow_id` / `parent_workflow_id` | — | hashed where the issue key is embedded | ✓ | `redactedWorkflowId` → `redactIssueKeyReferences` |
| `issue_key` | — | hashed | ✓ | `redactIssueKey` |
| `status`, `mode`, `resumed`, `stop_reason`, `blocked_reason` | — | ✓ | ✓ | `GoalTelemetryPayloadSupport` |
| `started_at`, `first_started_at`, `finished_at`, `duration_seconds` | — | ✓ | ✓ | `GoalTelemetryPayloadSupport` |
| `subtask_total`, `subtask_id`, `subtasks_complete`, `subtasks_blocked`, `subtasks_skipped`, `attempt_count`, `total_invocations`, `total_blocks`, `total_resumes` | — | ✓ | ✓ | `GoalTelemetryPayloadSupport` |
| `feature_name` | — | — | ✓ | `goalStartedPayload` |
| `subtask_name`, `finalizing_agent_id`, `participating_agent_ids`, `boundary_history_written`, `boundary_history_value` | — | — | ✓ | `goalSubtaskFinishedPayload` |

### `skillbill_feature_task_prose_started` / `skillbill_feature_task_prose_finished`

| Field | off | anonymous | full | Source |
|-------|-----|-----------|------|--------|
| `session_id`, `source` | — | ✓ | ✓ | `featureImplementStartedPayload` |
| `issue_key_provided`, `issue_key_type` | — | ✓ | ✓ | `featureImplementStartedPayload` |
| `spec_input_types`, `spec_word_count`, `feature_size`, `rollout_needed`, `acceptance_criteria_count`, `open_questions_count` | — | ✓ | ✓ | `featureImplementStartedPayload` |
| `completion_status`, `plan_correction_count`, `plan_task_count`, `plan_phase_count`, `feature_flag_used`, `feature_flag_pattern`, `files_created`, `files_modified`, `tasks_completed`, `review_iterations`, `audit_result`, `audit_iterations`, `validation_result`, `boundary_history_written`, `boundary_history_value`, `pr_created`, `child_steps`, `duration_seconds` | — | ✓ | ✓ | `featureImplementFinishedPayload` |
| `feature_name`, `spec_summary` | — | — | ✓ | `featureImplementStartedPayload` |
| `plan_deviation_notes` | — | — | ✓ | `featureImplementFinishedPayload` |

### `skillbill_feature_task_runtime_started` / `skillbill_feature_task_runtime_finished`

| Field | off | anonymous | full | Source |
|-------|-----|-----------|------|--------|
| `session_id`, `feature_size` | — | ✓ | ✓ | `featureTaskRuntimeStartedPayload` |
| `issue_key` | — | hashed | ✓ | `redactIssueKey` |
| `completion_status`, `completed_phase_ids`, `phase_outcomes`, `review_fix_iteration_count`, `audit_*` counters, `regeneration_*` counters, `crash_reconciliation_*` counters, `last_incomplete_phase`, `blocked_reason`, `duration_seconds` | — | ✓ | ✓ | `featureTaskRuntimeFinishedPayload` |
| `feature_name` | — | — | ✓ | `featureTaskRuntimeStartedPayload` |
| `resolved_branch` | — | — | ✓ | `featureTaskRuntimeFinishedPayload` |

### `skillbill_feature_task_runtime_projection_measurement`

| Field | off | anonymous | full | Source |
|-------|-----|-----------|------|--------|
| `projection_contract_id`, `producer_iteration`, `repository_checkpoint_fingerprint`, `projected_utf8_bytes`, `projected_collection_items`, `estimated_tokens`, `private_evidence_utf8_bytes`, `delivered_projection_utf8_bytes`, `failure_classification` | queued only | ✓ | ✓ | `FeatureTaskRuntimeProjectionMeasurement.toTelemetryMap` |

This event is enqueued regardless of level. At `off` the row is written locally and not sent.

### `skillbill_quality_check_started` / `skillbill_quality_check_finished`

| Field | off | anonymous | full | Source |
|-------|-----|-----------|------|--------|
| `session_id`, `routed_skill`, `detected_stack`, `fallback`, `fallback_reason`, `scope_type`, `initial_failure_count`, `orchestrated` | — | ✓ | ✓ | `qualityCheckStartedPayload` |
| `final_failure_count`, `iterations`, `result`, `duration_seconds` | — | ✓ | ✓ | `qualityCheckFinishedPayload` |
| `failing_check_names`, `unsupported_reason` | — | — | ✓ | `qualityCheckFinishedPayload` |

### `skillbill_feature_verify_started` / `skillbill_feature_verify_finished`

| Field | off | anonymous | full | Source |
|-------|-----|-----------|------|--------|
| `session_id`, `acceptance_criteria_count`, `rollout_relevant`, `orchestrated` | — | ✓ | ✓ | `featureVerifyStartedPayload` |
| `feature_flag_audit_performed`, `review_iterations`, `audit_result`, `completion_status`, `history_relevance`, `history_helpfulness`, `duration_seconds` | — | ✓ | ✓ | `featureVerifyFinishedPayload` |
| `spec_summary`, `gaps_found` | — | — | ✓ | `featureVerifyStartedPayload`, `featureVerifyFinishedPayload` |

### `skillbill_review_finished`

| Field | off | anonymous | full | Source |
|-------|-----|-----------|------|--------|
| Finding counts and rates (`total_findings`, `accepted_findings`, `rejected_findings`, `unresolved_findings`, `accepted_rate`, `rejected_rate`) | — | ✓ | ✓ | `filterReviewFinishedSummary` |
| `review_run_id`, `review_session_id`, `routed_skill`, `review_subskills`, `review_scope`, `review_platform`, `detected_stack`, `detected_stack_detail`, `fallback`, `fallback_reason`, `platform_slug`, `scope_type`, `execution_mode`, `review_finished_at` | — | ✓ | ✓ | `reviewFinishedPayload` |
| Finding detail `issue_category`, `severity`, `confidence`, `outcome_type` | — | ✓ | ✓ | `reviewFindingDetails` |
| Finding detail `location`, `description`, `note` | — | emptied | ✓ | `reviewFindingDetails` |
| `learnings.applied_count`, `applied_references`, `applied_summary`, `scope_counts`, entry `reference` and `scope` | — | ✓ | ✓ | `buildLearningsSection` |
| Learning entry `title`, `rule_text` | — | — | ✓ | `learningsEntries` |
| `review_context_accounting` (bounded counters) | — | ✓ | ✓ | `reviewFinishedPayload` |

### `skillbill_pr_description_generated`

| Field | off | anonymous | full | Source |
|-------|-----|-----------|------|--------|
| `session_id`, `commit_count`, `files_changed_count`, `was_edited_by_user`, `pr_created` | — | ✓ | ✓ | `prDescriptionPayload` |
| `pr_title` | — | — | ✓ | `prDescriptionPayload` |

### `skillbill_runtime_exception`

| Field | off | anonymous | full | Source |
|-------|-----|-----------|------|--------|
| `workflow_phase`, `error_type` (exception class simple name) | queued only | ✓ | ✓ | `enqueueRuntimeException` |
| `error_message` | queued only, redacted to `[redacted]` | redacted to `[redacted]` | ✓ (first 512 characters) | `enqueueRuntimeException` |
| `stack_trace` | queued only, `skillbill.` frames only, first 12 | `skillbill.` frames only, first 12 | first 12 frames | `redactedStackTrace` |

This event is enqueued regardless of level. At `off` the row is written locally with the redacted
message and `skillbill.` frames, and not sent.

An unresolved or unrecognized level falls to the redacted branch, not the `full` branch.

No payload builder emits a repository name, remote URL, or working-directory path. The
`resolve_learnings` MCP tool accepts a `repo` learning scope, but that value is validated locally
and no outbox payload carries a `repo` property.

## Where events go

- Default destination: `https://skill-bill-telemetry-proxy.skillbill.workers.dev`
  (`DEFAULT_TELEMETRY_PROXY_URL`), used when no custom proxy is configured.
- The relay is self-hostable. The example Cloudflare Worker ships in
  `docs/cloudflare-telemetry-proxy/`; deploy it and keep the backend credential in the Worker
  secret store.
- Re-point it with `SKILL_BILL_TELEMETRY_PROXY_URL`, or with `proxy_url` in
  `~/.config/skill-bill/config.json`.
- When a proxy is configured, it becomes the only remote telemetry destination. Skill Bill does
  not also send to the default relay.

## Opting out

Every supported mechanism:

- `skill-bill telemetry disable` — sets the level to `off`.
- `skill-bill telemetry set-level off` — equivalent to `telemetry disable`; `TelemetrySetLevelCommand`
  reaches the same `TelemetryService.setLevel` path.
- Choose `off` at the telemetry level prompt during `./install.sh`.
- `SKILL_BILL_TELEMETRY_LEVEL=off`.
- `SKILL_BILL_TELEMETRY_ENABLED=false` — legacy override, maps to `off` (`true` maps to
  `anonymous`).
- Writing `telemetry.level: "off"` (or the legacy `telemetry.enabled: false`) directly into
  `~/.config/skill-bill/config.json` has the same effect.

At `off`, no telemetry is transmitted and no telemetry config is required. Payload building is
skipped for every event except the two listed in
[What is still queued at `off`](#what-is-still-queued-at-off), which are enqueued locally without
consulting the level and are not discarded when telemetry is later enabled.

## What correlates events

`install_id` is the sole correlation key. It is a random UUID generated on first install
(`UUID.randomUUID()` in `ensureTelemetryConfigFile`) and stored in
`~/.config/skill-bill/config.json`. It is sent as the event `distinct_id` and as the
`install_id` property.

- It persists across reinstalls: the durable config lives outside the `~/.skill-bill/` tree that
  installs wipe, and `install.sh` migrates a legacy `~/.skill-bill/config.json` to the durable
  path before the pre-install cleanup.
- It persists across enable/disable cycles: disable is an in-place `telemetry.level` write, never
  a delete, so re-enabling reuses the same identity instead of minting a fresh UUID.
- No hardware, machine, or device identifier is collected or derived. There is no MAC address,
  serial number, hostname, username, or machine fingerprint in any payload. `install_id` is not
  derived from any machine property.

`SKILL_BILL_INSTALL_ID` lets you pin the id explicitly, which is how CI installs stay
deterministic.

## Data retention

Stated as what is true today:

- Automated retention enforcement is not implemented in the relay. The example Worker forwards
  events and does not expire or delete them.
- Retention of forwarded events is governed by the analytics backend the relay points at, under
  whatever settings that backend is configured with.
- Self-hosters set their own retention. Pointing `SKILL_BILL_TELEMETRY_PROXY_URL` at your own
  relay puts retention entirely under your control.
- Local queued telemetry is not retained indefinitely: `skill-bill telemetry disable` and any
  level downgrade clear the pending outbox. An upgrade — including `off` to `anonymous` — does not,
  so rows enqueued while `off` survive until the next sync or the next downgrade. Local review snapshots have no expiry and are pruned
  with `skill-bill prune-snapshots --confirm`.
