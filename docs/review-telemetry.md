# Review telemetry

Skill Bill can record a measurement loop for code-review usefulness. Telemetry uses a three-level model selected during install: `off`, `anonymous` (default), or `full`.

- each top-level review session should expose a `Review session ID: ...` using `rvs-<uuid4>` (e.g. `rvs-550e8400-e29b-41d4-a716-446655440000`)
- each concrete review output should expose a `Review run ID: ...` using `rvw-YYYYMMDD-HHMMSS-XXXX` (4-char random alphanumeric suffix for uniqueness)
- each finding in `### 2. Risk Register` should use `- [F-001] Severity | Confidence | file:line | description`
- feedback history and learnings stay local in SQLite regardless of telemetry state

The `skill-bill` CLI and MCP server are installed automatically by `./install.sh`. The MCP server exposes `import_review`, `triage_findings`, `resolve_learnings`, `review_stats`, `feature_implement_stats`, `feature_verify_stats`, and `doctor` as native agent tools. The CLI provides the same functionality plus learnings CRUD and telemetry management.

```bash
skill-bill --help
```

Default database path:

```text
~/.skill-bill/review-metrics.db
```

Default config path:

```text
~/.skill-bill/config.json
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
skill-bill learnings resolve --repo Sermilion/skill-bill --skill bill-kotlin-code-review --review-session-id rvs-20260402-001
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
skill-bill learnings add --scope repo --scope-key Sermilion/skill-bill --title "Installer wording is intentionally informal" --rule "Do not flag installer prompt wording as inconsistent — the informal tone is a deliberate UX choice for CLI tools." --from-run rvw-20260402-001 --from-finding F-002

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
skill-bill learnings resolve --repo Sermilion/skill-bill --skill bill-kotlin-code-review --review-session-id rvs-20260402-001 --format json
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
| `anonymous` | Aggregate counts, finding ids with severity/confidence/outcome type, anonymized learning references. No file paths, descriptions, notes, or learning content. |
| `full` | Everything in `anonymous` plus: finding descriptions/titles, file locations, rejection notes, and learning content (title, rule text). Useful for teams that want actionable detail. |

The default level is `anonymous`. Existing configs with `telemetry.enabled: true` are migrated to `anonymous`; `enabled: false` becomes `off`.

## Telemetry events

The telemetry model emits a single event per review lifecycle:

- one `skillbill_review_finished` event when a review lifecycle becomes fully resolved (all findings triaged)

The finished event carries: total/accepted/unresolved finding counts, accepted/rejected finding details, a nested `learnings` object, routed skill, review platform, normalized review scope type, execution mode, specialist reviews, and a distinct canonical `review_session_id` field so related telemetry can be grouped together in PostHog. The detail within finding and learning entries depends on the telemetry level (see table above). `unresolved_findings` is the count of findings whose latest outcome is not terminal yet; the finished event is emitted only once that count reaches zero. If a later import materially changes the review and reopens unresolved findings, Skill Bill clears the finish marker and emits a fresh event the next time the review becomes fully resolved.

When `learnings resolve` is called with `--review-session-id`, the resolved learnings are cached locally and included in the matching `skillbill_review_finished` event when it fires.

## Where this contract lives

The shared telemetry contract — standalone-first behavior, orchestrated flag semantics, child_steps aggregation, routers-never-emit rule, and ownership rules for `import_review` / `triage_findings` — is the single source of truth at `orchestration/telemetry-contract/PLAYBOOK.md`.

Each telemeterable skill consumes this contract through a `telemetry-contract.md` symlink sidecar inside the skill directory. That symlink resolves to `orchestration/telemetry-contract/PLAYBOOK.md`, so agents reading the skill always reach the canonical rules without needing to know the repo layout. Skill-specific telemetry fields (session id format, event names, payload fields) remain in each skill's own `SKILL.md`.

## Session correlation

Skill Bill uses **parent-owned telemetry** across the whole skill suite. A single user-initiated workflow produces **exactly one** telemetry event, even when multiple skills run inside it.

### Standalone-first contract

Every telemeterable skill must be usable alone. When invoked directly by a user, each skill generates its own session id and emits its own events:

- `bill-code-review` — `skillbill_review_finished` (once the review lifecycle resolves)
- `bill-quality-check` — `skillbill_quality_check_started` + `_finished`
- `bill-feature-verify` — `skillbill_feature_verify_started` + `_finished`
- `bill-pr-description` — `skillbill_pr_description_generated`
- `bill-feature-implement` — `skillbill_feature_implement_started` + `_finished`

### The `orchestrated` flag

Every telemeterable MCP tool accepts `orchestrated: bool = false`.

- **`orchestrated=false` (standalone):** the tool generates its own session id, emits started/finished events, and owns its lifecycle.
- **`orchestrated=true` (nested):** the tool emits **zero** telemetry events. Instead it returns a `telemetry_payload` dict on the tool result that the caller (the orchestrator) collects.

The orchestrator is responsible for setting the flag. A child skill never infers orchestrated mode from ambient state.

### `child_steps` aggregation

When the parent's finished event fires, it embeds each collected `telemetry_payload` in a `child_steps` array. One workflow, one event:

```json
{
  "event": "skillbill_feature_implement_finished",
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
      ...
    },
    {
      "skill": "bill-quality-check",
      "routed_skill": "bill-kotlin-quality-check",
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

`bill-code-review` and `bill-quality-check` are thin routers. They do not emit telemetry of their own — routing metadata is carried inside the concrete routed skill's telemetry call. They pass `orchestrated` through to the routed concrete skill unchanged.

### Event catalog

| Event | Emitted by | Orchestrated alternative |
|-------|------------|--------------------------|
| `skillbill_feature_implement_started` | `bill-feature-implement` (Step 1 confirm) | — (top-level only) |
| `skillbill_feature_implement_finished` | `bill-feature-implement` (Step 9 / early exit) | — (top-level only; carries `child_steps`) |
| `skillbill_review_finished` | `bill-code-review` (lifecycle resolved) | `import_review` / `triage_findings` with `orchestrated=true` return payload instead |
| `skillbill_quality_check_started` | `bill-quality-check` (standalone) | skipped in orchestrated mode |
| `skillbill_quality_check_finished` | `bill-quality-check` (standalone) | `quality_check_finished(orchestrated=true)` returns payload |
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
| `routed_skill` | string | Concrete stack-specific skill delegated to (`bill-kotlin-quality-check`, `bill-kmp-code-review`, …) |
| `detected_stack` | string | Dominant stack routed for |
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
- `skillbill_feature_verify_finished` — emitted after Step 7 (verdict delivered) or when the workflow ends early

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

## Feature-implement telemetry

The feature-implement workflow emits two events per session:

- `skillbill_feature_implement_started` — emitted after Step 1 assessment is confirmed by the user
- `skillbill_feature_implement_finished` — emitted after Step 9 (PR created) or when the workflow ends early

Each feature-implement session uses a `session_id` in the format `fis-YYYYMMDD-HHMMSS-XXXX` (4-char random alphanumeric suffix). The finished event is self-contained — it includes all started fields so each event can be analyzed independently in PostHog.

The MCP server exposes `feature_implement_started` and `feature_implement_finished` as agent tools. The skill instructions tell the agent when to call each tool.

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

## PostHog dashboard spec

The local `skill-bill implement-stats` and `skill-bill verify-stats` commands are the source of truth for workflow-summary semantics. PostHog dashboards should mirror those summaries instead of inventing a separate analytics vocabulary.

Recommended global filters:

- date ranges: last 7 days, last 30 days, and rolling quarter
- use started events for intake/adoption charts
- use finished events for completion, iteration, and duration charts
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

### Feature-implement dashboard

Use `skillbill_feature_implement_started` for intake/sizing and `skillbill_feature_implement_finished` for outcome metrics.

Recommended tiles:

- `Implement runs started`: count of `skillbill_feature_implement_started`
- `Implement runs finished`: count of `skillbill_feature_implement_finished`
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
    "bill-feature-implement"
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

The client sends that payload to:

- `<configured telemetry proxy url>/capabilities`
- `<configured telemetry proxy url>/stats`

The proxy owns backend-specific query logic. It may answer from PostHog, ClickHouse, BigQuery, or any other analytics store.

Normalized remote stats payloads now include:

- `started_runs`
- `finished_runs`
- `in_progress_runs`
- `in_progress_rate`
- optional `group_by`
- optional `series`

For `bill-feature-implement`, normalized remote stats also include:

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
export SKILL_BILL_CONFIG_PATH="$HOME/.skill-bill/config.json"  # optional override
```

When telemetry is enabled, the local config stores the generated install id used as the anonymous event `distinct_id`. You can edit `~/.skill-bill/config.json` directly if you want to keep the hosted relay or replace it with your own proxy target, but the supported way to opt out is `skill-bill telemetry disable`.
