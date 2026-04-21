# Feature Implement Reference

This reference holds the briefing templates, return contracts, and detailed substep instructions for `bill-feature-implement`. Read this alongside `SKILL.md`.

## Briefing Principles

Every subagent prompt is **self-contained**. Subagents do not have access to this conversation's prior turns, so the orchestrator must bundle everything the subagent needs:

1. **What to do** — phase-specific instructions.
2. **Contract** — acceptance criteria and non-goals.
3. **Context** — pre-planning digest, plan, spec path, issue key, feature-flag mode, validation strategy (whichever are relevant for the phase).
4. **Return shape** — the exact structured object the subagent must return.
5. **Standards** — pointer to `CLAUDE.md`, `AGENTS.md`, and the matching `.agents/skill-overrides.md` section.
6. **Tools and constraints** — e.g. do not use `git add -A`, do not re-read the raw spec, use `orchestrated=true` when calling specific MCP tools.

Every subagent **returns a single structured block** as the final text message, prefixed with `RESULT:` and containing valid JSON matching the declared contract, so the orchestrator can parse it deterministically. Narrative explanation (if any) goes above the `RESULT:` block; the orchestrator only consumes the JSON.

## Workflow State Contract

`bill-feature-implement` now owns a durable workflow-state layer in addition to
its telemetry session.

The orchestrator must maintain:

- `session_id` — from `feature_implement_started`, used only for telemetry
- `workflow_id` — from `feature_implement_workflow_open`, used for durable
  workflow state
- `child_steps` — local list of orchestrated child telemetry payloads

### Required workflow tools

- `feature_implement_workflow_open`
- `feature_implement_workflow_update`
- `feature_implement_workflow_get`

### Operational recovery tools

External callers may inspect and reactivate persisted runs through:

- `feature_implement_workflow_resume` — dry-run recovery summary
- `feature_implement_workflow_continue` — re-open a resumable run and emit a recovered continuation brief

### Canonical step ids

Use these exact step ids when updating workflow state:

1. `assess`
2. `create_branch`
3. `preplan`
4. `plan`
5. `implement`
6. `review`
7. `audit`
8. `validate`
9. `write_history`
10. `commit_push`
11. `pr_description`
12. `finish`

### Canonical artifacts

The shell-owned source of truth for artifact names lives in [SKILL.md](SKILL.md#stable-artifact-names).

Persist these named artifacts through `artifacts_patch` when the workflow runtime reaches the corresponding phase:

- `assessment`
- `branch`
- `preplan_digest`
- `plan`
- `implementation_summary`
- `review_result`
- `audit_report`
- `validation_result`
- `history_result`
- `commit_push_result` (reserved shell-owned name; Step 8 currently does not persist it)
- `pr_result`

### Phase-to-artifact mapping

At each phase boundary, persist the structured result the orchestrator already
has in hand:

- Step 1 → `assessment`
- Step 1b → `branch`
- Step 2 → `preplan_digest`
- Step 3 → `plan`
- Step 4 → `implementation_summary`
- Step 5 → `review_result`
- Step 6 → `audit_report`
- Step 6b → `validation_result`
- Step 7 → `history_result`
- Step 8 → no persisted artifact today; `commit_push_result` remains a shell-documented reserved name until runtime support exists
- Step 9 → `pr_result`

### Open sequence

Immediately after Step 1 is confirmed:

1. Call `feature_implement_started`.
2. Save `session_id` even when the tool returns `status: skipped`.
3. Call `feature_implement_workflow_open` with:
   - `session_id`
   - `current_step_id: "assess"`
4. Save `workflow_id`.
5. Initialize `child_steps = []`.

### Update rules

After every major phase boundary:

- call `feature_implement_workflow_update`
- set the parent-owned `workflow_status`
- set the new `current_step_id`
- pass only the changed steps in `step_updates`
- merge the new structured artifact through `artifacts_patch`

When a loop sends work backwards:

- set the next active step explicitly
- increment that step's `attempt_count`
- keep the latest artifact for the phase that triggered the loop

### Terminal-state rules

When the workflow succeeds:

- mark `finish` completed
- set `workflow_status: "completed"`

When the user abandons:

- set `workflow_status: "abandoned"`
- leave `current_step_id` on the step where the workflow stopped
- persist a small final artifact patch such as `{"terminal_note": "..."}`
  when useful

When the workflow errors:

- set `workflow_status: "failed"`
- leave `current_step_id` on the failing step
- persist a final artifact patch describing the failure when useful

Workflow state is independent of telemetry. Do not skip workflow-state writes
just because telemetry is disabled.

## Continuation Mode Contract

When an external caller invokes `feature_implement_workflow_continue`, the
returned payload becomes the supported re-entry contract for
`bill-feature-implement`.

The continuation payload includes:

- `skill_name` — always `bill-feature-implement`
- `continuation_mode` — currently `resume_existing_workflow`
- `continue_status` — `reopened`, `already_running`, `blocked`, or `done`
- `continue_step_id` and `continue_step_label`
- `continue_step_directive` — the step-specific rule for the resumed phase
- `reference_sections` — the exact governed sections to re-read before
  resuming
- `step_artifacts` — the recovered structured artifacts that should replace
  chat-history reconstruction
- `session_summary` — saved Step 1 metadata when a telemetry session exists
- `continuation_brief` — short human-facing summary
- `continuation_entry_prompt` — a paste-ready prompt for an orchestrator or AI
  caller

Re-entry rules:

- Do not open a new workflow when continuing an existing run.
- Keep using the same `workflow_id` and `session_id`.
- Treat `step_artifacts` as authoritative inputs for the resumed phase.
- Skip earlier completed steps unless the normal workflow loops send work
  backwards.
- After the resumed step completes, continue the standard `bill-feature-implement`
  sequence from that point onward.

## Pre-planning subagent briefing

Launch via the `Agent` tool with `subagent_type: "general-purpose"`.

```
You are the pre-planning subagent for feature implementation. Do not re-read the raw spec; operate only on the briefing below and on the files you explore in the repo.

Goal: produce a concise digest that the planning subagent will use. Do not write the plan yourself.

Feature: {feature_name}
Issue key: {issue_key}
Feature size: {feature_size}  # SMALL | MEDIUM | LARGE
Rollout needed: {rollout_needed}  # true | false
Spec (for SMALL — inline; for MEDIUM/LARGE — save to disk and return path): {spec_content_or_path}

Acceptance criteria (contract — do not restate, plan against these):
{numbered_list_of_acceptance_criteria}

Non-goals:
{bullet_list_of_non_goals}

Instructions:
1. Read `CLAUDE.md`, `AGENTS.md`, and any `.agents/skill-overrides.md` section matching `bill-feature-implement`. Treat all standards as mandatory.
2. For MEDIUM/LARGE, save the spec to `.feature-specs/{issue_key}-{feature_name}/spec.md` with status "In Progress", sources, acceptance criteria, and consolidated spec content. Preserve code blocks, schemas, and enums verbatim.
3. Read `agent/history.md` in each boundary likely to be touched (newest first; stop when no longer relevant). Rate boundary-history value using one of the following anchored definitions:
   - `none` — no `agent/history.md` file existed at pre-read time in any touched boundary (nothing was read because nothing was there).
   - `irrelevant` — history existed and was read, but no entry concerned a boundary, pattern, or pitfall related to this feature.
   - `low` — history existed and at least one entry grazed an adjacent area, but no entry materially shaped pre-planning or the plan.
   - `medium` — history existed and at least one entry directly informed pre-planning: a reused pattern, a named pitfall avoided, or a concrete constraint carried into the plan.
   - `high` — history existed and at least one entry was decisive: it changed the plan's shape, reused an established pattern verbatim, or prevented a known-bad approach that would otherwise have been taken.
   If you report `medium` or `high`, `boundary_history_digest` MUST cite the specific past entry (issue key, date, or entry title) that drove the rating. Otherwise downgrade to `low`.
4. Scan `agent/decisions.md` header lines in each likely boundary; open full entries only when titles look relevant.
5. Discover codebase patterns: similar features referenced in the spec, build/runtime dependencies, reusable components.
   When `kmp` signals dominate, resolve governed add-ons only after stack routing settles on `kmp`. Start from `Selected add-ons: none`. Let the routed pack own add-on detection and selection, then scan the matching pack-owned add-on supporting files' `## Section index` headings first. If the add-on is split into topic files, open only the linked topic files whose cues match the work during pre-planning / pattern discovery.
6. Confirm `bill-quality-check` can route this repo. If it cannot, pick the closest existing repo-native validation command.
7. If rollout uses a feature flag, read `bill-feature-guard` inline and choose a pattern: legacy | di_switch | simple_conditional. Record flag name and switch point.
8. Do NOT produce a plan. Do NOT implement anything.

Return exactly one RESULT: block as your final message, containing valid JSON with this shape:

RESULT:
{
  "spec_path": "<path or null for SMALL>",
  "selected_addons": ["<addon-slug>", ...],
  "boundaries_touched": ["<module/package/area>", ...],
  "boundary_history_digest": "<concise summary — patterns to reuse, pitfalls to avoid>",
  "boundary_history_value": "none|irrelevant|low|medium|high",
  "boundary_decisions_digest": "<concise summary or empty string>",
  "codebase_patterns_digest": "<concise summary — similar features, reusable components, gotchas>",
  "validation_strategy": "bill-quality-check | <repo-native command>",
  "feature_flag": {
    "used": false,
    "pattern": "none|simple_conditional|di_switch|legacy",
    "flag_name": "<name or empty>",
    "switch_point": "<where the switch lives, or empty>"
  },
  "standards_notes": "<anything from CLAUDE.md/AGENTS.md/skill-overrides.md the planner must honor>"
}
```

## Planning subagent briefing

```
You are the planning subagent for feature implementation. Do not re-read the raw spec; operate on the briefing below.

Goal: produce an ordered, atomic task plan the implementation subagent will execute.

Feature: {feature_name}
Issue key: {issue_key}
Feature size: {feature_size}

Acceptance criteria (contract):
{numbered_list}

Non-goals:
{non_goals}

Pre-planning digest (from Step 2):
{pre_planning_digest_json}

Planning rules:
- Break work into atomic tasks; each completable in one turn.
- Order tasks by dependency (data layer → domain → presentation).
- Each task must reference the acceptance criteria it satisfies.
- If the plan includes testable logic, the FINAL task must be a dedicated test task. Implementation tasks may have `tests: "None"` only because the final test task will cover them. Skip the final test task only when there is genuinely nothing testable (pure config, docs, agent-config/skill prose, UI changes with no test infra).
- For MEDIUM/LARGE: if the plan exceeds 15 tasks, split into phases with a checkpoint between each.
- If rollout uses a feature flag, every task states how it respects the flag strategy (pattern: {feature_flag_pattern}).
- Reference design artifacts (mockups, screenshots, wireframes, API examples) by filename where relevant.
- Do NOT implement anything.
- Do NOT expose a separate "codebase patterns" section; fold those findings into task descriptions.

Return exactly one RESULT: block as your final message, containing valid JSON with this shape:

RESULT:
{
  "rollout_summary": "<feature flag + pattern, or N/A>",
  "validation_strategy": "<inherit from pre-planning digest>",
  "phases": [
    {
      "name": "<phase name or 'single'>",
      "tasks": [
        {
          "id": 1,
          "description": "<what this task does>",
          "files": ["<path or path-pattern>", ...],
          "satisfies_criteria": [1, 3],
          "tests": "<test coverage description or 'None' (deferred to final test task) or 'None (nothing testable)'>"
        }
      ]
    }
  ],
  "task_count": <int>,
  "phase_count": <int>,
  "has_dedicated_test_task": <bool>
}
```

## Implementation subagent briefing

```
You are the implementation subagent for feature implementation. Do not re-read the raw spec; operate on the briefing below and on the files in the repo.

Goal: execute the plan atomically and return a structured summary.

Feature: {feature_name}
Issue key: {issue_key}
Feature size: {feature_size}
Spec path (MEDIUM/LARGE): {spec_path}

Acceptance criteria (contract):
{numbered_list}

Plan (from Step 3):
{plan_json}

Pre-planning digest (from Step 2):
{pre_planning_digest_json}

Execution rules:
- After each task, print progress: "✅ [<n>/<total>] <task description>".
- Follow standards in `CLAUDE.md`, `AGENTS.md`, and any matching `.agents/skill-overrides.md` section.
- Write production-grade code. Do not introduce deprecated components, APIs, or patterns when a supported alternative exists.
- Write tests exactly as specified in each task's `tests` field.
- If a task reveals the plan is wrong, STOP and return with `plan_deviation_notes` populated describing what changed and why; do not try to silently re-plan.
- Do not skip or combine tasks.
- If the plan has phases, pause between phases for a brief self-checkpoint.
- When removing user-facing code, shared resources, or wiring: immediately clean up orphaned artifacts in the same task.
- When changing agent-config or skill repositories: update adjacent catalogs and wiring in the same task.
- Test gate: before returning, verify unit tests were written if the plan included testable logic.
- For MEDIUM/LARGE: before returning, perform a post-implementation self-compact — summarize files created/modified, feature-flag info, criteria-to-file mapping, and any plan deviations. Then re-read the saved spec to verify every criterion is mapped.
- Do NOT commit or push. Do NOT open a PR. Those are the orchestrator's job.

Return exactly one RESULT: block as your final message, containing valid JSON with this shape:

RESULT:
{
  "tasks_completed": <int>,
  "files_created": [<path>, ...],
  "files_modified": [<path>, ...],
  "tests_written": [<path>, ...],
  "plan_deviation_notes": "<empty if no deviations>",
  "criteria_to_file_map": {"1": ["path"], "2": ["path"]},
  "notes_for_review": "<anything reviewers should focus on>",
  "stopped_early": <bool>,
  "stopped_reason": "<empty if stopped_early is false>"
}
```

### Fix-loop briefing (used by Step 5 review loop)

When the code-review step produces Blocker/Major findings, the orchestrator respawns the implementation subagent with a fix-focused briefing:

```
You are the implementation subagent, invoked to fix findings from the code-review step. Scope: fix only the findings listed below; do not add unrelated changes.

Acceptance criteria (contract, for reference only — do not expand scope):
{numbered_list}

Findings to fix:
{risk_register_rows_with_F-ids_and_file_line_paths}

Current branch diff pointer: {branch_or_commit_range}

Test gate is relaxed: write tests only when the finding being fixed requires them (for example, a finding about missing regression coverage or a broken test). Do not treat the standard "write tests if the plan included testable logic" gate as mandatory in fix mode — the plan is not being re-executed here.

Return the standard implementation return contract, with `notes_for_review` describing which finding each change addresses.
```

## Completeness audit subagent briefing

```
You are the completeness audit subagent. Do not re-read the raw spec unless you need to resolve ambiguity in a criterion; prefer the briefing.

Goal: verify every acceptance criterion is actually satisfied by the implementation.

Feature: {feature_name}
Feature size: {feature_size}
Spec path (MEDIUM/LARGE): {spec_path}

Acceptance criteria (contract):
{numbered_list}

Implementation summary (from Step 4):
{implementation_return_json}

Branch diff pointer (MEDIUM/LARGE): {branch_or_commit_range}

Instructions:
- SMALL: produce a quick confirmation per criterion. Read only the files mentioned in the implementation summary.
- MEDIUM/LARGE: produce a full per-criterion report with evidence paths. Verify against actual code, not the summary.
- Do NOT implement fixes. Do NOT edit files.
- If a criterion is partially satisfied, record it as a gap with `suggested_fix`.

Return exactly one RESULT: block as your final message, containing valid JSON with this shape:

RESULT:
{
  "pass": <bool>,
  "per_criterion": [
    {
      "id": 1,
      "criterion": "<text>",
      "verdict": "pass|partial|fail",
      "evidence": ["<path:line>", ...]
    }
  ],
  "gaps": [
    {
      "criterion_id": <int>,
      "missing": "<what is missing>",
      "suggested_fix": "<concrete suggestion>"
    }
  ]
}
```

## Quality-check subagent briefing

```
You are the quality-check subagent. Your job is to run the final validation gate and return a structured result.

Feature: {feature_name}
Validation strategy: {validation_strategy}  # 'bill-quality-check' or a repo-native command
Scope: branch diff since main for MEDIUM/LARGE, current unit of work for SMALL.

Instructions:
1. If validation_strategy is `bill-quality-check`, read `bill-quality-check` and apply inline; it auto-routes to the matching stack-specific quality-check skill.
2. Otherwise, run the provided repo-native command.
3. Fix any issues at their root cause. Do not use suppressions unless explicitly allowed by project standards.
4. Call the `quality_check_finished` MCP tool with `orchestrated=true`. Pass all started+finished fields directly (skip `quality_check_started` in orchestrated mode): `routed_skill`, `detected_stack`, `scope_type`, `initial_failure_count`, plus the finished fields.
5. Capture the `telemetry_payload` returned by `quality_check_finished` verbatim.

Return exactly one RESULT: block as your final message, containing valid JSON with this shape:

RESULT:
{
  "validation_result": "pass|fail|skipped",
  "routed_skill": "<skill name or empty>",
  "detected_stack": "<stack or empty>",
  "initial_failure_count": <int>,
  "final_failure_count": <int>,
  "fixes_applied": "<brief summary>",
  "telemetry_payload": { ... verbatim from quality_check_finished ... }
}
```

## PR-description subagent briefing

```
You are the PR-description subagent. Your job is to create the pull request and return its URL.

Feature: {feature_name}
Issue key: {issue_key}
Branch: feat/{issue_key}-{feature_name}
Base branch: main (or the repo's main branch if different)

Acceptance criteria (for reference when drafting the PR body):
{numbered_list}

Implementation summary (from Step 4):
{implementation_return_json}

Instructions:
1. Read `bill-pr-description` and apply inline. Respect repo-native PR templates if present (`.github/pull_request_template.md`, `PULL_REQUEST_TEMPLATE.md`, etc.).
2. Create the PR with `gh pr create` using a HEREDOC for the body.
3. Call the `pr_description_generated` MCP tool with `orchestrated=true` once the PR is created.
4. Capture the `telemetry_payload` returned by `pr_description_generated` verbatim.

Return exactly one RESULT: block as your final message, containing valid JSON with this shape:

RESULT:
{
  "pr_created": <bool>,
  "pr_url": "<url or empty>",
  "pr_title": "<title>",
  "used_repo_template": <bool>,
  "template_path": "<path or empty>",
  "telemetry_payload": { ... verbatim from pr_description_generated ... }
}
```

## Size Reference

| | SMALL (≤5 tasks, ≤3 boundaries) | MEDIUM (6-15 tasks, ≤6 boundaries) | LARGE (>15 tasks or >6 boundaries) |
|---|---|---|---|
| Save spec to disk | No | Yes | Yes |
| Post-implementation compact (inside impl subagent) | No | Yes | Yes |
| Completeness audit | Quick confirmation | Full per-criterion report | Full per-criterion report |
| Boundary history write | If impactful | Yes | Yes |
| Codebase discovery (inside pre-planning subagent) | No | Yes | Yes |

All sizes: feature flag if required, code review (inline in orchestrator), quality check (subagent), boundary history (inline), commit/push (inline), PR description (subagent).

## Error Recovery

- **Pre-planning subagent fails** — report the error and ask the user whether to retry, adjust scope, or abandon. If abandoned, call `feature_implement_finished` with `completion_status: "abandoned_at_planning"`.
- **Planning subagent returns an invalid plan** (missing fields, no dedicated test task when testable logic exists, etc.) — respawn it once with a corrective briefing that lists the violations. If it still fails, abandon at planning.
- **Implementation subagent stops early with `stopped_early: true`** — the orchestrator decides: if `plan_deviation_notes` imply a re-plan, respawn the planning subagent with the deviation notes and then a fresh implementation subagent; otherwise, hand to the user.
- **Code-review fix loop exceeds 3 iterations** — stop, report remaining findings, hand to user. Call `feature_implement_finished` with `completion_status: "abandoned_at_review"`.
- **Completeness audit loops exceed 2 iterations** — report remaining gaps, let user decide. Call `feature_implement_finished` accordingly.
- **Quality-check subagent returns `validation_result: "fail"`** — escalate to the user (do not silently commit). If the user abandons, call `feature_implement_finished` with `completion_status: "error"`.
- **PR-description subagent fails to create the PR** — report the error, offer to retry. If abandoned, call `feature_implement_finished` with `completion_status: "error"`.

In all early-exit cases, close the telemetry session with the appropriate `completion_status` so the run is not orphaned.

In those same early-exit cases, also close the workflow state with
`feature_implement_workflow_update` so the durable workflow does not remain in
`running`.

## Skills Invoked

Read each skill's file and apply inline when its step is reached. Subagents read these files themselves from inside their own context; the orchestrator does not pre-read them.

- `bill-feature-guard` — pre-planning subagent, if rollout uses a feature flag
- `bill-code-review` — **orchestrator**, after implementation
- `bill-quality-check` — quality-check subagent, final validation gate
- `bill-boundary-history` — **orchestrator**, after completeness audit
- `bill-pr-description` — PR-description subagent
