---
issue_key: SKILL-27
feature_name: workflow-runtime
feature_size: MEDIUM
status: In Progress
created: 2026-04-25
phase: 5 - Workflow runtime
rollout_needed: false
---

# SKILL-27 Phase 5 - Workflow Runtime

## Sources

- `docs/migrations/SKILL-27-kotlin-runtime-port.md`
- `.feature-specs/SKILL-27-kotlin-runtime-port/spec.md`
- `.feature-specs/SKILL-27-surface-integration/spec.md`
- `.feature-specs/SKILL-28-runtime-architecture/spec.md`
- `skill_bill/feature_implement.py`
- `skill_bill/feature_verify.py`
- `skill_bill/cli.py`
- `skill_bill/mcp_server.py`
- `runtime-kotlin/ARCHITECTURE.md`
- `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/persistence/WorkflowStateRepository.kt`
- `runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/db/WorkflowStateStore.kt`

## Acceptance Criteria

1. Kotlin owns feature-implement workflow state/runtime behavior for open,
   update, get, list, latest, resume, and continue semantics.
2. Kotlin owns feature-verify workflow state/runtime behavior for the same
   workflow-state and continuation semantics.
3. Resume/continue payload builders preserve the Python contract fields, step
   ids, artifact handling, blocked/done/reopened behavior, and step-specific
   directives.
4. Existing SQLite workflow persistence is reused through runtime ports; no new
   local store or schema redesign.
5. Add parity-focused Kotlin tests for workflow rows, list/latest resolution,
   resume summaries, continuation payloads, and representative implement/verify
   behavior.
6. Keep production Python entrypoints, loader/scaffolder, install behavior,
   launcher/cutover wiring, and Python deletion out of scope.

## Non-goals

- Cutting over `skill-bill` or `skill-bill-mcp` production entrypoints.
- Porting scaffold, loader, install, launcher, or cutover behavior.
- Changing workflow contracts or step ids.
- Redesigning the SQLite schema or deleting Python runtime code.

## Consolidated Scope

Phase 5 ports the two durable workflow runtimes into `runtime-kotlin/` while
leaving externally installed Python entrypoints active. The Python modules
`skill_bill/feature_implement.py` and `skill_bill/feature_verify.py` remain the
behavioral oracle for this phase.

The Kotlin runtime should replace the current reserved workflow surfaces with
real in-module runtime behavior for:

- `bill-feature-implement`
- `bill-feature-verify`

The port must preserve the existing SQLite-backed workflow tables:

- `feature_implement_workflows`
- `feature_verify_workflows`

Persistence should flow through the existing runtime ports and adapters,
especially `WorkflowStateRepository` and `WorkflowStateStore`, extending them
only as needed for list/latest/update behavior. Do not create a parallel store,
new schema, or workflow database.

The workflow payload contract must preserve these shared fields:

- `workflow_id`
- `session_id`
- `workflow_name`
- `contract_version`
- `workflow_status`
- `current_step_id`
- `steps`
- `artifacts`
- `started_at`
- `updated_at`
- `finished_at`
- `status`
- `db_path`

Resume payloads must preserve:

- `resume_mode`
- `resume_step_id`
- `last_completed_step_id`
- `available_artifacts`
- `required_artifacts`
- `missing_artifacts`
- `can_resume`
- `next_action`

Continue payloads must preserve:

- `skill_name`
- `continuation_mode`
- `workflow_status_before_continue`
- `continue_status`
- `continue_step_id`
- `continue_step_label`
- `continue_step_directive`
- `reference_sections`
- `step_artifact_keys`
- `step_artifacts`
- `session_summary`
- `continuation_brief`
- `continuation_entry_prompt`

For `bill-feature-implement`, continue payloads also preserve:

- `feature_name`
- `feature_size`
- `branch_name`

The implementation workflow keeps these stable step ids:

`assess`, `create_branch`, `preplan`, `plan`, `implement`, `review`, `audit`,
`validate`, `write_history`, `commit_push`, `pr_description`, `finish`.

The implementation workflow keeps these stable artifact names:

`assessment`, `branch`, `preplan_digest`, `plan`, `implementation_summary`,
`review_result`, `audit_report`, `validation_result`, `history_result`,
`commit_push_result`, `pr_result`.

The verify workflow keeps these stable step ids:

`collect_inputs`, `extract_criteria`, `gather_diff`, `feature_flag_audit`,
`code_review`, `completeness_audit`, `verdict`, `finish`.

The verify workflow keeps these stable artifact names and continuation inputs:

`input_context`, `criteria_summary`, `diff_summary`,
`feature_flag_audit_result`, `review_result`, `completeness_audit_result`,
`verdict_result`, `session_notes`, `review_diff_pointer`.

Continue behavior must preserve Python semantics for `blocked`, `done`,
`already_running`, and `reopened`. Reopening increments the selected step's
`attempt_count`, marks that step `running`, sets `workflow_status` to
`running`, and keeps prior artifacts authoritative. Blocked continuations must
surface `missing_artifacts` and the existing error text rather than silently
continuing.

Kotlin tests should use the current Python tests as the parity map:

- `tests/test_feature_implement_workflow_state.py`
- `tests/test_feature_implement_workflow_e2e.py`
- `tests/test_feature_implement_agent_resume.py`
- `tests/test_feature_verify_workflow_state.py`
- `tests/test_feature_verify_workflow_e2e.py`
- `tests/test_feature_verify_agent_resume.py`
- `tests/test_feature_verify_workflow_contract.py`
- workflow-related coverage in `tests/test_cli.py`, `tests/test_mcp_server.py`,
  and `tests/test_mcp_stdio.py`

The architectural direction from SKILL-28 applies: CLI and MCP are adapters,
application owns orchestration, persistence flows through ports, JSON payloads
are boundary concerns, and public model declarations belong in explicit
`model` packages.

## Phase 5 Implementation Notes

- Kotlin runtime owns workflow state/runtime semantics in `WorkflowService`,
  `WorkflowEngine`, and workflow contract builders.
- Persistence reuses the existing SQLite workflow tables through
  `WorkflowStateRepository` and `WorkflowStateStore`; no schema redesign or new
  local workflow store is part of this phase.
- Python production entrypoints, loader/scaffolder behavior, install behavior,
  launcher/cutover wiring, and Python runtime deletion remain out of scope.
