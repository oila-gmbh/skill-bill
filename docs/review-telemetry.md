# Review telemetry

## Bounded delegated-review accounting

Delegated reviews enforce byte, evidence-read, result-size, and assignment-expansion limits before or during a lane. The named `ReviewContextBudgetPolicy.DEFAULT` policy uses 524288 parent-packet bytes, 65536 lane-launch bytes, 262144 cumulative lane-evidence bytes, 65536 bytes per evidence result and lane result, and three assignment expansions. Repositories may override individual values through the strict `review_context_budget` object in `.skill-bill/config.yaml`; malformed, negative, unknown, or inconsistent nested values fail before launch.

Two further per-specialist limits bound a lane's own work: 40 tool calls and 24 model turns. Overrides are strict — every supplied value must be a positive integer, evidence-result bytes cannot exceed cumulative lane-evidence bytes, and lane-launch bytes cannot exceed parent-packet bytes. An incoherent combination fails before launch rather than being clamped.

Provider usage reports input, cached-input, output, reasoning, and total tokens independently when available. Provider input is cumulative for a session, so it already includes the tokens re-sent on every turn; cached input is the part of that the provider served from its own cache and is reported on its own axis rather than subtracted from input. Fresh-token approximation is `max(input - cached_input, 0) + output`. It is a regression signal for how much new context a lane pulled in, not a billing reconciliation: providers differ in what they meter, and a lane with no reported dimensions reports none rather than zero.

Direct usage owns only one session; inclusive usage already contains descendants and is not added to child totals again. Counters — launch, evidence, and result bytes, expansions, tool calls, and model turns — aggregate the same way, so `aggregate_counters` and `aggregate_direct_usage` count each session exactly once no matter how deep the tree is.

Thresholds that a provider can enforce live terminate the affected lane with `review_context_budget_exceeded`; its sibling lanes keep running. Providers without reliable live cancellation classify threshold excess after completion as `budget_regression`: the overrun is recorded, the lane's result is kept as produced, and nothing is retroactively truncated or relaunched.

Delegated specialists never rediscover what the parent already resolved. Repository status, scope, base/head revisions, diff recomputation, build and test invocation, platform-pack and add-on resolution, routing, learnings resolution, and project-guidance files are refused at the broker with a typed forbidden-operation category. Evidence outside a lane's assignment requires an authorized expansion recorded in the parent packet's ledger; the default budget admits three per assignment.

Provider-native specialists are verified before any worker starts. A missing, stale, or dangling managed native-agent link fails the review with the logical worker name, provider, expected path, reason, and the repair command `skill-bill install apply`. There is no generic-worker fallback — repair the install and rerun.

Durable output and telemetry retain only numeric accounting, lane identifiers, packet and assignment digests, enforcement classification, and terminal outcomes. Prompts, diffs, source, project guidance, rubric bodies, and tool output remain transient.

Skill Bill can record a measurement loop for code-review usefulness. Telemetry uses a three-level model selected during install: `off`, `anonymous` (default), or `full`.

- each top-level review session should expose a `Review session ID: ...` using `rvs-<uuid4>` (e.g. `rvs-550e8400-e29b-41d4-a716-446655440000`)
- each concrete review output should expose a `Review run ID: ...` using `rvw-YYYYMMDD-HHMMSS-XXXX` (4-char random alphanumeric suffix for uniqueness)
- each finding in `### 2. Risk Register` should use `- [F-001] Severity | Confidence | file:line | description`
- feedback history and learnings stay local in SQLite regardless of telemetry state

The `skill-bill` CLI and MCP server are installed automatically by `./install.sh`. The MCP server exposes review, learning, workflow-state, workflow-stats, telemetry, scaffold, quality-check, PR-description, health, and optional Readian bridge tools. The CLI provides the same runtime functionality plus learnings CRUD, install primitives, validation, and telemetry management.

```bash
skill-bill --help
```

Default database path:

```text
~/.skill-bill/review-metrics.db
```

Default config path (durable, survives installs):

```text
~/.config/skill-bill/config.json
```

You can override the database path with `--db` or `SKILL_BILL_REVIEW_DB`.

Typical workflow:

1. Save a review output to a text file.
2. Import the review so the run and findings are stored locally.
3. Use numbered triage to respond with issue numbers instead of raw finding ids.
4. Optionally store reusable learnings separately from raw feedback history.
5. Resolve active learnings for the next review context when you want that feedback to influence future reviews explicitly.
6. Query summary stats for one run or for all imported runs.

Example:

```bash
skill-bill import-review review.txt
skill-bill triage --run-id rvw-20260402-001
skill-bill triage --run-id rvw-20260402-001 --decision "1 fix - keep current terminology" --decision "2 skip - intentional"
skill-bill triage --run-id rvw-20260402-001 --decision "fix=[1] reject=[2]"
skill-bill triage --run-id rvw-20260402-001 --decision "all fix"
skill-bill learnings resolve --repo oila-gmbh/skill-bill --skill bill-kotlin-code-review --review-session-id rvs-20260402-001
skill-bill stats --run-id rvw-20260402-001 --format json
skill-bill implement-stats --format json
skill-bill verify-stats --format json
```

The `triage` command maps the visible numbers back to the stable `F-001` ids internally. For agent-driven flows, prefer a structured selection string like `fix=[1,3] reject=[2]` so every finding is resolved deterministically in one step. Use `all <action>` to apply the same action to every finding. Supported triage actions are:

- `fix` -> records `fix_applied`
- `accept` -> records `finding_accepted`
- `edit` -> records `finding_edited`
- `skip`, `dismiss`, or `reject` -> records `fix_rejected`
- `false positive` -> records `false_positive`

In agent-driven review flows, only the parent review that owns the final merged review output for the current review lifecycle should call `import_review` and `triage_findings`. Delegated child reviews stay telemetry-free and return their review data and metadata to that parent.

You can still use the low-level command when you want direct control:

```bash
skill-bill record-feedback --run-id rvw-20260402-001 --event fix_applied --finding F-001 --note "keep current terminology"
```

## Learnings

Learnings are actionable domain-specific knowledge derived from **rejected** review findings. When you reject a finding and explain why, you can promote that rejection into a reusable learning so future reviews avoid the same mistake.

```bash
# First reject a finding during triage:
skill-bill triage --run-id rvw-20260402-001 --decision "2 reject - installer wording is intentionally informal"

# Then promote the rejection into a learning:
skill-bill learnings add --scope repo --scope-key oila-gmbh/skill-bill --title "Installer wording is intentionally informal" --rule "Do not flag installer prompt wording as inconsistent — the informal tone is a deliberate UX choice for CLI tools." --from-run rvw-20260402-001 --from-finding F-002

# Manage learnings:
skill-bill learnings list
skill-bill learnings show --id 1
skill-bill learnings edit --id 1 --reason "Confirmed by repeated skip feedback."
skill-bill learnings disable --id 1
skill-bill learnings delete --id 1
```

Both `--from-run` and `--from-finding` are required — learnings must trace back to a rejected finding. When `--reason` is omitted, the rationale is auto-populated from the rejection note.

Raw finding-outcome history and learnings are stored separately. That means you can wipe or disable reusable learnings without losing the original review-feedback history.

When you want future reviews to use those learnings explicitly, resolve the active learnings for the current review context:

```bash
skill-bill learnings resolve --repo oila-gmbh/skill-bill --skill bill-kotlin-code-review --review-session-id rvs-20260402-001 --format json
```

Resolution stays local-first and explicit:

- only `active` learnings apply
- precedence is `skill > repo > global`
- the helper returns stable learning references such as `L-003`
- `--review-session-id` is required when telemetry is enabled so the resolved-learning event can be grouped with the matching review session
- the top-level code-review caller owns learnings resolution and passes the applied references through routed/delegated reviews
- review output should surface `Applied learnings: ...` so the behavior is auditable

This is intentionally not hidden auto-learning. The learnings layer remains inspectable, editable, disable-able, and deletable by the user.

## Telemetry levels

Telemetry has three levels:

| Level | What is sent |
|-------|-------------|
| `off` | Nothing. No events are queued or sent. |
| `anonymous` | Aggregate counts, finding ids with issue category/severity/confidence/outcome type, anonymized learning references. No file paths, descriptions, notes, or learning content. |
| `full` | Everything in `anonymous` plus: finding descriptions/titles, file locations, rejection notes, and learning content (title, rule text). Useful for teams that want actionable detail. |

The default level is `anonymous`. Existing configs with `telemetry.enabled: true` are migrated to `anonymous`; `enabled: false` becomes `off`.

## Telemetry events

The telemetry model emits a single event per review lifecycle:

- one `skillbill_review_finished` event when a review lifecycle becomes fully resolved (all findings triaged)

The finished event carries: total/accepted/rejected/unresolved finding counts, accepted/rejected rates, accepted/rejected finding details, a nested `learnings` object, normalized routed skill, normalized `review_platform`/`detected_stack`/`platform_slug`, optional `detected_stack_detail`, normalized `scope_type`, execution mode, specialist reviews, fallback metadata, and a distinct canonical `review_session_id` field so related telemetry can be grouped together in PostHog. Finding details always include `issue_category`, `severity`, `confidence`, and `outcome_type`; file locations, descriptions, and rejection notes are included only at `full` level. `unresolved_findings` is the count of findings whose latest outcome is not terminal yet; the finished event is emitted only once that count reaches zero. If a later import materially changes the review and reopens unresolved findings, Skill Bill clears the finish marker and emits a fresh event the next time the review becomes fully resolved.

The review issue category taxonomy is: `behavior_correctness`, `data_persistence`, `concurrency_lifecycle`, `ux_accessibility`, `testing_quality_gate`, `security_privacy`, `docs_contract`, and `other`.

When `learnings resolve` is called with `--review-session-id`, the resolved learnings are cached locally and included in the matching `skillbill_review_finished` event when it fires.

## Where this contract lives

The shared telemetry contract — standalone-first behavior, orchestrated flag semantics, child_steps aggregation, routers-never-emit rule, and ownership rules for `import_review` / `triage_findings` — is the single source of truth at `orchestration/telemetry-contract/PLAYBOOK.md`.

Each telemeterable skill consumes this contract through a generated `telemetry-contract.md` support pointer in its installed staging directory. That pointer resolves to `orchestration/telemetry-contract/PLAYBOOK.md`, so agents reading the installed skill always reach the canonical rules without needing to know the repo layout. The pointer is render/install output, not a committed source file under `skills/`. Skill-specific telemetry fields (session id format, event names, payload fields) remain in each skill's rendered `SKILL.md` and authored `content.md`.

## Session correlation

Skill Bill uses **parent-owned telemetry** across the whole skill suite. A single user-initiated workflow produces **exactly one** telemetry event, even when multiple skills run inside it.

### Standalone-first contract

Every telemeterable skill must be usable alone. When invoked directly by a user, each skill generates its own session id and emits its own events:

- `bill-code-review` lifecycle — `skillbill_review_finished` once the final review output is imported and all findings resolve
- `bill-code-check` lifecycle — `skillbill_quality_check_started` + `_finished`
- `bill-feature-verify` — `skillbill_feature_verify_started` + `_finished`
- `bill-pr-description` — `skillbill_pr_description_generated`
- `bill-feature-task` — `skillbill_feature_task_prose_started` + `_finished`

### The `orchestrated` flag

Every telemeterable MCP tool accepts `orchestrated: bool = false`.

- **`orchestrated=false` (standalone):** the tool generates its own session id, emits started/finished events, and owns its lifecycle.
- **`orchestrated=true` (nested):** the tool emits **zero** telemetry events. Instead it returns a `telemetry_payload` dict on the tool result that the caller (the orchestrator) collects.

The orchestrator is responsible for setting the flag. A child skill never infers orchestrated mode from ambient state.

### `child_steps` aggregation

When the parent's finished event fires, it embeds each collected `telemetry_payload` in a `child_steps` array. One workflow, one event:

```json
{
  "event": "skillbill_feature_task_prose_finished",
  "session_id": "fis-20260413-104704-l84r",
  "completion_status": "completed",
  "duration_seconds": 1820,
  "child_steps": [
    {
      "skill": "bill-code-review",
      "review_session_id": "rvs-...",
      "total_findings": 7,
      "accepted_findings": 6,
      "rejected_findings": 1,
      "unresolved_findings": 0,
      "accepted_rate": 0.86,
      "rejected_rate": 0.14,
      "platform_slug": "kotlin",
      "scope_type": "branch_diff",
      ...
    },
    {
      "skill": "bill-code-check",
      "routed_skill": "bill-kotlin-code-check",
      "result": "pass",
      "iterations": 2,
      ...
    },
    {
      "skill": "bill-pr-description",
      "commit_count": 3,
      "pr_created": true,
      ...
    }
  ],
  ...
}
```

### Graceful degradation

If a parent skill forgets to pass `orchestrated=true` to a child, the child emits its own standalone event. The workflow produces extra events but nothing is lost. Always pass the flag from the orchestrator's `SKILL.md` instructions.

### Non-goals

- **No cross-event correlation field.** There is no `parent_session_id` joining separate events — correlation by construction (one event per workflow) replaces correlation by foreign key.
- **No auto-detection.** Children never decide "am I orchestrated?" themselves.
- **No global session registry.** Each skill run is self-sufficient.

### Router skills never emit

`bill-code-review` and `bill-code-check` are thin routers. They do not emit telemetry merely because routing happened. Routing metadata is carried inside the concrete routed skill's telemetry call, and the router passes `orchestrated` through to the routed concrete skill unchanged. The user-facing standalone lifecycle can still produce the events listed below once the routed workflow reaches its telemetry seam.

### Event catalog

| Event | Emitted by | Orchestrated alternative |
|-------|------------|--------------------------|
| `skillbill_feature_task_prose_started` | `bill-feature-task` (Step 1 confirm) | — (top-level only) |
| `skillbill_feature_task_prose_finished` | `bill-feature-task` (Step 9 / early exit) | — (top-level only; carries `child_steps`) |
| `skillbill_review_finished` | top-level code-review lifecycle once findings are resolved | `import_review` / `triage_findings` with `orchestrated=true` return payload instead |
| `skillbill_quality_check_started` | standalone quality-check lifecycle | skipped in orchestrated mode |
| `skillbill_quality_check_finished` | standalone quality-check lifecycle | `quality_check_finished(orchestrated=true)` returns payload |
| `skillbill_feature_verify_started` | `bill-feature-verify` (standalone) | skipped in orchestrated mode |
| `skillbill_feature_verify_finished` | `bill-feature-verify` (standalone) | `feature_verify_finished(orchestrated=true)` returns payload |
| `skillbill_pr_description_generated` | `bill-pr-description` (standalone) | `pr_description_generated(orchestrated=true)` returns payload |

## Quality-check telemetry

The quality-check workflow emits two events per standalone session:

- `skillbill_quality_check_started` — emitted once stack routing is resolved and the first check run begins
- `skillbill_quality_check_finished` — emitted when the quality-check loop terminates

Session id format: `qck-YYYYMMDD-HHMMSS-XXXX`.

### Quality-check payloads

Both `anonymous` and `full`:

| Field | Type | Description |
|-------|------|-------------|
| `session_id` | string | `qck-YYYYMMDD-HHMMSS-XXXX` |
| `routed_skill` | string | Concrete checker delegated to, normalized without namespace prefixes; blank/unresolved routing emits `unrouted` |
| `detected_stack` | string | Normalized stack slug routed for; blank/unresolved stack emits `unknown` |
| `fallback` | boolean | Whether routing used a fallback path |
| `fallback_reason` | string | Optional stable reason supplied by a route that actually used a fallback |
| `scope_type` | string | `files`, `working_tree`, `branch_diff`, or `repo` |
| `initial_failure_count` | integer | Failing checks before the first fix run |

`_finished` adds:

| Field | Type | Description |
|-------|------|-------------|
| `final_failure_count` | integer | Failing checks after the last fix attempt |
| `iterations` | integer | Fix-run cycles |
| `result` | string | `pass`, `fail`, `skipped`, or `unsupported_stack` |
| `duration_seconds` | integer | Wall-clock seconds from started to finished |

`full` level additionally includes in `_finished`:

| Field | Type | Description |
|-------|------|-------------|
| `failing_check_names` | list | Check names that remained failing at the end |
| `unsupported_reason` | string | Explanation when `result` is `unsupported_stack` |

## Feature-verify telemetry

The feature-verify workflow emits two events per standalone session:

- `skillbill_feature_verify_started` — emitted after Step 2 (acceptance criteria confirmed)
- `skillbill_feature_verify_finished` — emitted after Step 8 (verdict delivered) or when the workflow ends early

Session id format: `fvr-YYYYMMDD-HHMMSS-XXXX`.

### Feature-verify payloads

Both levels:

| Field | Type | Description |
|-------|------|-------------|
| `session_id` | string | `fvr-YYYYMMDD-HHMMSS-XXXX` |
| `acceptance_criteria_count` | integer | Number of criteria extracted from the spec |
| `rollout_relevant` | boolean | Whether the spec requires a guarded rollout audit |

`_finished` adds:

| Field | Type | Description |
|-------|------|-------------|
| `feature_flag_audit_performed` | boolean | Whether the rollout/feature-flag audit ran |
| `review_iterations` | integer | Code-review iteration count |
| `audit_result` | string | `all_pass`, `had_gaps`, or `skipped` |
| `completion_status` | string | `completed`, `abandoned_at_review`, `abandoned_at_audit`, or `error` |
| `history_relevance` | string | How relevant history-entry reading was: `none`, `irrelevant`, `low`, `medium`, or `high` |
| `history_helpfulness` | string | How helpful history-entry reading was: `none`, `irrelevant`, `low`, `medium`, or `high` |
| `duration_seconds` | integer | Wall-clock seconds from started to finished |

`full` adds:

| Field | Type | Description |
|-------|------|-------------|
| `spec_summary` | string | One-sentence summary of the verified feature (from `_started`) |
| `gaps_found` | list | Short descriptions of gaps identified during the completeness audit |

## PR description telemetry

The PR description workflow emits a single event per generation:

- `skillbill_pr_description_generated` — emitted after the PR description is presented (and, when applicable, after the PR is created)

Session id format: `prd-YYYYMMDD-HHMMSS-XXXX`.

### PR description payload

Both levels:

| Field | Type | Description |
|-------|------|-------------|
| `session_id` | string | `prd-YYYYMMDD-HHMMSS-XXXX` |
| `commit_count` | integer | Commits included in the PR |
| `files_changed_count` | integer | Files changed in the PR |
| `was_edited_by_user` | boolean | Whether the user requested changes to the generated description |
| `pr_created` | boolean | Whether the PR was actually created |

`full` adds:

| Field | Type | Description |
|-------|------|-------------|
| `pr_title` | string | Generated PR title |

## Feature-task telemetry

The feature-task workflow emits two events per session:

- `skillbill_feature_task_prose_started` — emitted after Step 1 assessment is confirmed by the user
- `skillbill_feature_task_prose_finished` — emitted after Step 9 (PR created) or when the workflow ends early

Each feature-task session uses a `session_id` in the format `fis-YYYYMMDD-HHMMSS-XXXX` (4-char random alphanumeric suffix). The finished event is self-contained — it includes all started fields so each event can be analyzed independently in PostHog.

The MCP server exposes `feature_task_prose_started` and `feature_task_prose_finished` as agent tools. The skill instructions tell the agent when to call each tool.

### Started event payload

Both `anonymous` and `full` levels include:

| Field | Type | Description |
|-------|------|-------------|
| `session_id` | string | `fis-YYYYMMDD-HHMMSS-XXXX` |
| `issue_key_provided` | boolean | Whether the user provided a Jira/Linear/GitHub issue key |
| `issue_key_type` | string | `jira`, `linear`, `github`, `other`, or `none` |
| `spec_input_types` | list | Input types: `raw_text`, `pdf`, `markdown_file`, `image`, `directory` |
| `spec_word_count` | integer | Approximate word count of the design spec |
| `feature_size` | string | `SMALL`, `MEDIUM`, or `LARGE` |
| `rollout_needed` | boolean | Whether a feature flag / guarded rollout is needed |
| `acceptance_criteria_count` | integer | Number of acceptance criteria |
| `open_questions_count` | integer | Number of open questions before resolution |

`full` level adds:

| Field | Type | Description |
|-------|------|-------------|
| `feature_name` | string | Inferred feature name |
| `spec_summary` | string | One-sentence summary of the feature |

### Finished event payload

Includes all started fields plus:

Both `anonymous` and `full` levels:

| Field | Type | Description |
|-------|------|-------------|
| `completion_status` | string | `completed`, `abandoned_at_planning`, `abandoned_at_implementation`, `abandoned_at_review`, or `error` |
| `plan_correction_count` | integer | Times the user corrected the assessment/plan (0 = confirmed immediately) |
| `plan_task_count` | integer | Total tasks in the plan |
| `plan_phase_count` | integer | Number of phases |
| `feature_flag_used` | boolean | Whether a feature flag was used |
| `feature_flag_pattern` | string | `simple_conditional`, `di_switch`, `legacy`, or `none` |
| `files_created` | integer | New files created |
| `files_modified` | integer | Existing files modified |
| `tasks_completed` | integer | Tasks completed |
| `review_iterations` | integer | Code review iteration count |
| `audit_result` | string | `all_pass`, `had_gaps`, or `skipped` |
| `audit_iterations` | integer | Completeness audit iteration count |
| `validation_result` | string | `pass`, `fail`, or `skipped` |
| `boundary_history_written` | boolean | Whether boundary history was written |
| `pr_created` | boolean | Whether a PR was created |
| `duration_seconds` | integer | Wall-clock seconds from started to finished |

`full` level adds:

| Field | Type | Description |
|-------|------|-------------|
| `plan_deviation_notes` | string | Brief note if the plan changed during execution |

Both levels also include:

| Field | Type | Description |
|-------|------|-------------|
| `child_steps` | list | `telemetry_payload` dicts collected from child tools invoked with `orchestrated=true` during the session (see the "Session correlation" section). Empty list when no children were orchestrated. |

Fields always excluded (both levels): repo name, branch name, raw spec content, raw plan content, file paths, acceptance criteria text.

## Audit-repair counters

Runtime-mode feature-task sessions report their own terminal event through the `feature_task_runtime_finished` MCP tool, emitted as `skillbill_feature_task_runtime_finished`. Alongside `review_fix_iteration_count` and `audit_gap_iteration_count`, that event carries five counters describing how the completeness-audit repair loop behaved. They are compact numbers and one boolean — no gap text, criterion text, diagnoses, evidence strings, paths, or agent output is sent at any telemetry level.

| Field | Type | Description |
|-------|------|-------------|
| `audit_first_pass_convergence` | boolean | Whether the first audit was satisfied outright, with no audit-gap iteration at all. True only when `audit_gap_iteration_count` is 0. |
| `audit_recurring_gap_count` | integer | Gaps still carried as recurring in the run's final durable audit state — reported unmet again under their original identity after a repair pass had already attempted them. |
| `audit_new_gap_count` | integer | Gaps raised under a new identity by a later audit rather than as a recurrence of a gap it inherited. |
| `audit_attempted_repair_item_count` | integer | Repair items remediation carried and returned a terminal result for; never smaller than the terminal results held in durable state. |
| `audit_resolved_repair_item_count` | integer | Carried repair items that reached a terminal resolved outcome (`fixed` or `already_satisfied`) with the required evidence. |

What an operator can read from them:

- `audit_first_pass_convergence` is the headline quality signal for the implementation phase: a high rate means implementation is landing acceptance criteria the first time, and a falling rate means specs, planning, or review are letting unmet criteria through to the audit.
- `audit_recurring_gap_count` above zero means a repair pass ran and did not fix what it was asked to fix. Sustained recurrence is the leading indicator of the non-progress block described in `docs/capabilities.md`, and usually points at a mis-diagnosed gap rather than a lazy repair.
- `audit_new_gap_count` distinguishes discovery from failure. New gaps mean later audits are finding genuinely different problems — closer to an under-specified spec than to a broken repair loop.
- `audit_attempted_repair_item_count` measures how much repair scope the audit created; comparing it against `audit_gap_iteration_count` shows whether one audit described the whole repair scope or the loop discovered it piecemeal.
- `audit_resolved_repair_item_count` compared to the attempted count is the repair-pass yield. The runtime requires every carried item to reach a terminal result, so a durable gap between the two is a signal to inspect the run rather than a normal steady state.

## PostHog dashboard spec

The local `skill-bill implement-stats` and `skill-bill verify-stats` commands are the source of truth for workflow-summary semantics. PostHog dashboards should mirror those summaries instead of inventing a separate analytics vocabulary.

Recommended global filters:

- date ranges: last 7 days, last 30 days, and rolling quarter
- use started events for intake/adoption charts
- use finished events for completion, iteration, and duration charts
- exclude non-production installs by default: require `properties.install_id IS NOT NULL`, `trim(toString(properties.install_id)) != ''`, and `toString(properties.install_id) != 'test-install-id'`
- do not mix started and finished denominators in one chart unless the chart explicitly describes funnel dropoff

### Feature-verify dashboard

Use `skillbill_feature_verify_started` for intake metrics and `skillbill_feature_verify_finished` for outcome metrics.

Recommended tiles:

- `Verify runs started`: count of `skillbill_feature_verify_started`
- `Verify runs finished`: count of `skillbill_feature_verify_finished`
- `Verify completion rate`: `completion_status = completed` on finished events divided by all finished events
- `Verify abandonment rate`: `completion_status != completed` on finished events divided by all finished events
- `Rollout-relevant rate`: `rollout_relevant = true` on started events divided by all started events
- `Audit gaps rate`: `audit_result = had_gaps` on finished events divided by all finished events
- `History read rate`: finished events where history telemetry is not `none` divided by all finished events
- `History relevant rate`: finished events where `history_relevance in (medium, high)` divided by all finished events
- `History helpful rate`: finished events where `history_helpfulness in (medium, high)` divided by all finished events
- `Average review iterations`: average `review_iterations` on finished events
- `Average verify duration`: average `duration_seconds` on finished events
- `Acceptance criteria distribution`: histogram or average of `acceptance_criteria_count` on started events

Recommended breakdowns:

- `completion_status`
- `audit_result`
- `rollout_relevant`
- `history_relevance`
- `history_helpfulness`

Mirror to local stats:

- `total_runs` ~= count of started events
- `completion_status_counts` = finished breakdown by `completion_status`
- `audit_result_counts` = finished breakdown by `audit_result`
- `rollout_relevant_rate` = started filter on `rollout_relevant = true`
- `history_read_rate` = finished runs where `history_relevance != none` or `history_helpfulness != none`
- `history_relevant_rate` = finished runs where `history_relevance in (medium, high)`
- `history_helpful_rate` = finished runs where `history_helpfulness in (medium, high)`
- `average_review_iterations` = average on finished `review_iterations`
- `average_duration_seconds` = average on finished `duration_seconds`

### Feature-task dashboard

Use `skillbill_feature_task_prose_started` for intake/sizing and `skillbill_feature_task_prose_finished` for outcome metrics.

Recommended tiles:

- `Implement runs started`: count of `skillbill_feature_task_prose_started`
- `Implement runs finished`: count of `skillbill_feature_task_prose_finished`
- `Implement completion rate`: `completion_status = completed` on finished events divided by all finished events
- `Feature size mix`: breakdown of started events by `feature_size`
- `Rollout-needed rate`: `rollout_needed = true` on started events divided by all started events
- `Feature-flag usage rate`: `feature_flag_used = true` on finished events divided by all finished events
- `PR-created rate`: `pr_created = true` on finished events divided by all finished events
- `Average review iterations`: average `review_iterations` on finished events
- `Average audit iterations`: average `audit_iterations` on finished events
- `Average implementation duration`: average `duration_seconds` on finished events
- `Validation result mix`: breakdown of finished events by `validation_result`
- `Audit result mix`: breakdown of finished events by `audit_result`

Recommended breakdowns:

- `feature_size`
- `completion_status`
- `validation_result`
- `audit_result`
- `feature_flag_pattern`

Mirror to local stats:

- `total_runs` ~= count of started events
- `feature_size_counts` = started breakdown by `feature_size`
- `completion_status_counts` = finished breakdown by `completion_status`
- `validation_result_counts` = finished breakdown by `validation_result`
- `audit_result_counts` = finished breakdown by `audit_result`
- `rollout_needed_rate` = started filter on `rollout_needed = true`
- `feature_flag_used_rate` = finished filter on `feature_flag_used = true`
- `pr_created_rate` = finished filter on `pr_created = true`
- `average_review_iterations` = average on finished `review_iterations`
- `average_audit_iterations` = average on finished `audit_iterations`
- `average_duration_seconds` = average on finished `duration_seconds`

### Health stat defaults

Local health views use the rows available in the local telemetry database. They exclude `source = test` and `source = synthetic` telemetry from health denominators by default. Excluded and malformed records are still reported as data-quality debt so dashboards do not hide instrumentation problems.

Review health combines two review payload sources:

- standalone `skillbill_review_finished` events
- embedded code-review entries inside `skillbill_feature_task_prose_finished.child_steps`

Do not attempt to de-duplicate standalone and embedded review payloads unless a stable shared key is present. Local stats report `source_counts` for `standalone`, `embedded`, and `malformed`. Rejected findings mean reviewer feedback explicitly rejected or marked a finding false positive. Unresolved findings mean the latest finding outcome is missing or not accepted/rejected.

Feature-task health uses production rows with valid `fis-*` session ids as the denominator. It reports `source_counts`, excluded non-production rows, malformed session ids, unknown sources, duplicate terminal finished calls, invalid durations, synthetic zero-duration runs, long-running durations, and malformed `child_steps` as exclusion or data-quality signals. Duration averages, medians, and p90 values use only normal production durations.

Large-feature health is segmented by `feature_size`. `LARGE` runs report completion, abandonment, error, open-run, and duration summaries separately. The deterministic recommendation threshold is any non-zero unhealthy `LARGE` rate (`>= 0.001`) that is at least the overall unhealthy rate; in that case, dashboards should recommend decomposing large features or blocking earlier before implementation.

### PostHog query patterns

Use these named HogQL patterns as dashboard starting points. Keep them close to the local stats definitions rather than copying exploratory SQL from a one-off analysis.

`review_health_last_60_days`:

```sql
SELECT
  source,
  count() AS review_payload_records,
  avg(total_findings) AS average_findings,
  quantile(0.5)(total_findings) AS median_findings,
  quantile(0.9)(total_findings) AS p90_findings,
  sum(accepted_findings) AS accepted_findings,
  sum(rejected_findings) AS rejected_findings,
  sum(unresolved_findings) AS unresolved_findings
FROM (
  SELECT
    'standalone' AS source,
    toInt(properties.total_findings) AS total_findings,
    toInt(properties.accepted_findings) AS accepted_findings,
    toInt(properties.rejected_findings) AS rejected_findings,
    toInt(properties.unresolved_findings) AS unresolved_findings
  FROM events
  WHERE event = 'skillbill_review_finished'
    AND timestamp >= now() - INTERVAL 60 DAY
    AND properties.install_id IS NOT NULL
    AND trim(toString(properties.install_id)) != ''
    AND toString(properties.install_id) != 'test-install-id'
  UNION ALL
  SELECT
    'embedded' AS source,
    JSONExtractInt(child_raw, 'total_findings') AS total_findings,
    JSONExtractInt(child_raw, 'accepted_findings') AS accepted_findings,
    JSONExtractInt(child_raw, 'rejected_findings') AS rejected_findings,
    JSONExtractInt(child_raw, 'unresolved_findings') AS unresolved_findings
  FROM events
  ARRAY JOIN JSONExtractArrayRaw(toString(properties.child_steps)) AS child_raw
  WHERE event = 'skillbill_feature_task_prose_finished'
    AND timestamp >= now() - INTERVAL 60 DAY
    AND properties.install_id IS NOT NULL
    AND trim(toString(properties.install_id)) != ''
    AND toString(properties.install_id) != 'test-install-id'
    AND JSONExtractString(child_raw, 'skill') LIKE '%code-review%'
)
GROUP BY source
```

`feature_task_prose_health_last_60_days`:

```sql
SELECT
  properties.feature_size AS feature_size,
  count() AS denominator_runs,
  countIf(properties.completion_status = 'completed') AS completed_runs,
  countIf(properties.completion_status IN ('abandoned_at_planning', 'abandoned_at_implementation', 'abandoned_at_review')) AS abandoned_runs,
  countIf(properties.completion_status = 'error') AS error_runs,
  quantile(0.5)(toInt(properties.duration_seconds)) AS median_duration_seconds,
  quantile(0.9)(toInt(properties.duration_seconds)) AS p90_duration_seconds
FROM events
WHERE event = 'skillbill_feature_task_prose_finished'
  AND timestamp >= now() - INTERVAL 60 DAY
  AND properties.install_id IS NOT NULL
  AND trim(toString(properties.install_id)) != ''
  AND toString(properties.install_id) != 'test-install-id'
  AND coalesce(properties.source, 'production') = 'production'
  AND match(toString(properties.session_id), '^fis-[A-Za-z0-9][A-Za-z0-9_-]*$')
  AND toInt(properties.duration_seconds) > 0
  AND toInt(properties.duration_seconds) < 86400
GROUP BY properties.feature_size
```

`feature_task_prose_data_quality_debt_last_60_days`:

```sql
SELECT
  countIf(NOT match(toString(properties.session_id), '^fis-[A-Za-z0-9][A-Za-z0-9_-]*$')) AS malformed_session_id_runs,
  countIf(coalesce(properties.source, 'production') NOT IN ('production', 'test', 'synthetic')) AS unknown_source_runs,
  countIf(coalesce(properties.source, 'production') IN ('test', 'synthetic')) AS excluded_non_production_runs,
  countIf(toInt(properties.duplicate_terminal_finished_events) > 0) AS duplicate_terminal_finished_events,
  countIf(coalesce(properties.source, 'production') = 'production' AND toInt(properties.duration_seconds) = 0) AS invalid_duration_runs,
  countIf(coalesce(properties.source, 'production') = 'synthetic' AND toInt(properties.duration_seconds) = 0) AS synthetic_zero_duration_runs,
  countIf(toInt(properties.duration_seconds) >= 86400) AS long_running_duration_runs
FROM events
WHERE event = 'skillbill_feature_task_prose_finished'
  AND timestamp >= now() - INTERVAL 60 DAY
  AND properties.install_id IS NOT NULL
  AND trim(toString(properties.install_id)) != ''
  AND toString(properties.install_id) != 'test-install-id'
```

### Alignment rule

If a PostHog chart disagrees with local CLI/MCP stats for the same date range, treat the local stats command as the contract and fix the chart definition first.

## Remote stats contract

For org-wide analysis, Skill Bill can query aggregate workflow metrics through the configured telemetry proxy instead of reading one install's local SQLite database.

CLI:

```bash
skill-bill telemetry capabilities
skill-bill telemetry stats verify --since 30d
skill-bill telemetry stats implement --date-from 2026-04-01 --date-to 2026-04-22
skill-bill telemetry stats verify --since 30d --group-by day
skill-bill telemetry stats implement --since 30d --group-by week
```

MCP:

- `telemetry_proxy_capabilities`
- `telemetry_remote_stats`

Client capability contract:

```json
{
  "contract_version": "1",
  "supports_ingest": true,
  "supports_stats": true,
  "supported_workflows": [
    "bill-feature-verify",
    "bill-feature-task"
  ]
}
```

Client request contract:

```json
{
  "workflow": "bill-feature-verify",
  "date_from": "2026-04-01",
  "date_to": "2026-04-22",
  "group_by": "day"
}
```

The MCP tool accepts either canonical workflow ids
(`bill-feature-verify`, `bill-feature-task`) or short aliases
(`verify`, `implement`). The dispatcher maps aliases to canonical ids before
calling the proxy. The CLI subcommands use the short forms
`skill-bill telemetry stats verify` and `skill-bill telemetry stats implement`.

The client sends that payload to:

- `<configured telemetry proxy url>/capabilities`
- `<configured telemetry proxy url>/stats`

The proxy owns backend-specific query logic. It may answer from PostHog, ClickHouse, BigQuery, or any other analytics store.
The bundled PostHog proxy queries apply the same production-install default as dashboards: they exclude null `install_id`, blank `install_id`, and `install_id = 'test-install-id'`.

Normalized remote stats payloads now include:

- `started_runs`
- `finished_runs`
- `in_progress_runs`
- `in_progress_rate`
- optional `group_by`
- optional `series`

For `bill-feature-task`, normalized remote stats also include:

- `boundary_history_written_runs`
- `boundary_history_written_rate`
- `boundary_history_useful_runs`
- `boundary_history_useful_rate`
- `boundary_history_value_counts`

For `bill-feature-verify`, normalized remote stats also include:

- `history_read_runs`
- `history_read_rate`
- `history_relevant_runs`
- `history_relevant_rate`
- `history_helpful_runs`
- `history_helpful_rate`
- `history_relevance_counts`
- `history_helpfulness_counts`

For the proxy contract, `in_progress_runs` is a range-scoped estimate derived from event counts in the selected window: `max(started_runs - finished_runs, 0)`. It is useful for top-level workflow monitoring, but it is not a durable workflow-state query.
`boundary_history_useful_runs` is defined as `boundary_history_value in ('medium', 'high')`.
`history_relevant_runs` is defined as `history_relevance in ('medium', 'high')`.
`history_helpful_runs` is defined as `history_helpfulness in ('medium', 'high')`.

When `group_by` is provided, the proxy returns a `series` array with day or week buckets. Those buckets use the same event-window math as the top-level summary, so they are trend-oriented monitoring data, not cohort/session-complete analytics.

Recommended auth model:

- set `SKILL_BILL_TELEMETRY_PROXY_STATS_TOKEN` on the client machine
- have the proxy require `Authorization: Bearer <token>` for `/stats` and optionally `/capabilities`
- keep ingest and stats authorization concerns separate if you want public ingest but private aggregate reads

## Remote sync defaults

Fresh installs default telemetry to `anonymous`, with a level prompt during `./install.sh`. When telemetry level is not `off`, Skill Bill generates an install id, writes telemetry config to `~/.skill-bill/config.json`, and can batch-sync queued telemetry to the hosted Skill Bill relay. If you configure a custom proxy, Skill Bill sends telemetry to that proxy only.

- enabled telemetry (`anonymous` or `full`) can enqueue local telemetry events in SQLite before sync
- the helper can batch-sync pending events automatically after local writes to the hosted relay, or to a configured custom proxy override
- automatic sync and manual `skill-bill telemetry sync` both reconcile stale lifecycle rows before flushing the outbox, so CLI-only sessions receive the same terminal-event repair as MCP sessions
- reconciliation uses a durable cadence independent of upload timing and one globally ordered maximum batch per SQLite transaction; eligible repeated runs drain larger backlogs without holding an unbounded write transaction
- reconciliation is best-effort: a reconciliation failure is recorded diagnostically and does not prevent the requested telemetry flush
- if the remote destination is missing or unavailable, local workflows still succeed and the enabled telemetry outbox stays pending
- `off` telemetry is a no-op: no telemetry config is required, no telemetry events are queued locally, and telemetry payload-building is skipped
- `skill-bill telemetry disable` removes local telemetry config and clears any queued telemetry events without deleting non-telemetry review data

Default hosted relay:

- `https://skill-bill-telemetry-proxy.skillbill.workers.dev`
- used automatically when no custom proxy is configured

Custom proxy setup for your own deployment:

- deploy the example Worker in `docs/cloudflare-telemetry-proxy/`
- set it with `SKILL_BILL_TELEMETRY_PROXY_URL`
- keep the backend credential only in the Worker secret store
- when set, the custom proxy becomes the only remote telemetry destination

Telemetry commands:

```bash
skill-bill telemetry status
skill-bill telemetry enable                  # defaults to anonymous
skill-bill telemetry enable --level full     # enable with full detail
skill-bill telemetry set-level full          # change level directly
skill-bill telemetry set-level anonymous
skill-bill telemetry disable                 # sets level to off
skill-bill telemetry sync
```

Proxy configuration:

```bash
export SKILL_BILL_TELEMETRY_PROXY_URL="https://your-worker.your-subdomain.workers.dev"
export SKILL_BILL_TELEMETRY_PROXY_STATS_TOKEN="replace-with-your-stats-token"
export SKILL_BILL_TELEMETRY_LEVEL="full"                   # optional override (off, anonymous, full)
export SKILL_BILL_TELEMETRY_ENABLED="true"                 # legacy override (maps true→anonymous, false→off)
export SKILL_BILL_TELEMETRY_BATCH_SIZE="50"                # optional override
export SKILL_BILL_CONFIG_PATH="/custom/path/config.json"  # optional: pin config to a custom path (overrides the durable default)
```

When telemetry is enabled, the local config stores the generated install id used as the anonymous event `distinct_id`. The config lives at the durable `~/.config/skill-bill/config.json` by default, outside the `~/.skill-bill/` tree that installs wipe; you can edit it directly if you want to keep the hosted relay or replace it with your own proxy target, but the supported way to opt out is `skill-bill telemetry disable`.
