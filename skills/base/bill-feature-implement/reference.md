# Feature Implement Reference

## Pre-Planning Details

### Save Spec (MEDIUM and LARGE only)

Save to `.feature-specs/{ISSUE_KEY}-{feature-name}/spec.md` with: feature name, issue key, date, status (`In Progress`), sources, acceptance criteria, and consolidated spec content. Preserve code blocks, schemas, field definitions, and enums verbatim; narrative sections may be summarized.

The directory name **must** begin with the issue key collected in Step 1 (e.g. `SKILL-10-awesome-feature`, `ME-5066-payment-retry`). Never create a spec directory without the issue-key prefix — Step 1 is responsible for rejecting any run that lacks one.

For SMALL features the acceptance criteria stay in context — no spec file needed.

### Read Boundary History

Look for `agent/history.md` in each boundary the feature touches. Read newest entries first, stop once entries are no longer relevant. Use to reuse components and follow latest patterns. Skip if none exist.

After reading, assess how useful the history was for this feature: `irrelevant` (nothing applied), `low` (minor context), `medium` (reused patterns or avoided pitfalls), `high` (directly shaped the approach). Use `none` if no history files existed. Report this as `boundary_history_value` in the finished telemetry.

### Read Boundary Decisions

Look for `agent/decisions.md` in each boundary the feature touches. If the file exists, scan only the `## [date] title` header lines first. Read full entries only for decisions whose titles are relevant to the boundaries, patterns, or interfaces this feature will touch. Skip if none exist.

### Feature Flag Setup (only when rollout uses a feature flag)

- Read the `bill-feature-guard` skill instructions and its matching `.agents/skill-overrides.md` section, then apply them inline
- Determine the pattern (Legacy / DI Switch / Simple Conditional)
- Record the chosen pattern, flag name, and switch point
- Do not auto-apply a fixed stack- or app-specific prefix when proposing new flag names; only use a prefix if the user explicitly asks for it

### Discover Codebase Patterns

Explore the codebase concurrently with planning:
1. Read `CLAUDE.md`, `AGENTS.md`, and the matching `bill-feature-implement` section in `.agents/skill-overrides.md` when present — treat all standards as mandatory
2. Find similar features referenced in the spec
3. Identify build/runtime dependencies for affected boundaries
4. Note reusable components
5. Confirm that `bill-quality-check` can route the affected repo or boundaries (it handles all supported stacks including agent-config repos)
6. If a repo-native validation script already exists, reuse it instead of inventing a new ad hoc checklist

Do NOT present a separate "codebase patterns" section to the user — fold these findings directly into the implementation plan.

## Planning Rules

**All sizes:**
- Break into **atomic tasks** — each task completable in one turn
- Order tasks by dependency (data layer → domain → presentation)
- Each task must reference which acceptance criteria it satisfies
- **If the plan includes testable logic, the final task must be a dedicated test task.** This task writes unit tests covering the new/changed logic. Implementation tasks may set `Tests: None` only because testing is deferred to this final task. Skip the test task only when there is genuinely nothing testable (pure config, documentation, agent-config/skill prose, or UI changes with no test infra).

**Additional rules for MEDIUM/LARGE:**
- If plan exceeds **15 tasks**, split into phases with a checkpoint between each
- If the rollout strategy uses a feature flag, every task states how it respects that flag strategy
- Reference relevant design artifacts by filename where relevant (for example mockups, screenshots, wireframes, API examples)

**Plan format:** Include rollout info (flag + pattern, or N/A), final validation strategy (`bill-quality-check`), then numbered tasks. Each task: description, files to create/modify, which acceptance criteria it satisfies, and test coverage (or "None" if deferred to the final test task).

## Execution Rules

- After each task, print progress: `✅ [3/10] Created PaymentRepository with Room integration`
- Follow project standards from `CLAUDE.md`, `AGENTS.md`, and any matching `.agents/skill-overrides.md` sections used by this workflow
- Write clean, production-grade code
- Never introduce deprecated components, APIs, or patterns when a supported alternative exists
- **Write tests as specified** in each task's `Tests:` field
- If a task reveals the plan is wrong, **stop and re-plan from that point**
- Do NOT skip or combine tasks without user consent
- If plan has phases, pause between phases for a brief checkpoint
- **When removing user-facing code, shared resources, or wiring:** immediately clean up orphaned artifacts in the same task
- **When changing agent-config or skill repositories:** update adjacent catalogs and wiring in the same task
- **Test gate:** Before moving to review/compaction, verify that unit tests were written if the plan included testable logic

### Post-Implementation Compact (MEDIUM and LARGE only)

Before review, summarize: files created/modified, feature flag info, criteria-to-file mapping, and plan deviations. Then re-read `.feature-specs/{ISSUE_KEY}-{feature-name}/spec.md` to refresh acceptance criteria and verify every criterion is mapped.

## Finalization Steps

### Step 6b: Final Validation Gate (All sizes)

After completeness audit passes, **infer the final validation gate automatically** from the repo shape and changed files. Do not ask the user to choose.

Run `bill-quality-check` — it detects the dominant stack (including agent-config repos) and routes to the matching stack-specific quality-check skill automatically.

If `bill-quality-check` reports no supported stack for the affected repo, fall back to the closest existing repo-native validation command or test command already present in the project.

**Orchestrated telemetry:** call `quality_check_finished` with `orchestrated=true` once the check completes. Pass all started fields (`routed_skill`, `detected_stack`, `scope_type`, `initial_failure_count`) alongside the finished fields. Append the returned `telemetry_payload` to the `child_steps` list.

### Step 7: Write Boundary History

Run `bill-boundary-history` (read its skill file and apply inline). The skill owns write/skip rules and entry format.

### Step 8: Commit and Push

1. Stage all new and modified files from this feature (do not use `git add -A`)
2. Commit with message format: `feat: <concise description>` (omit the issue key — the branch name already carries it)
3. Push the branch to the remote with `-u` to set upstream tracking

### Step 9: Generate PR Description (All sizes)

Run `bill-pr-description` (read its skill file and apply inline) to generate a PR title, description, and QA steps.

**Orchestrated telemetry:** call `pr_description_generated` with `orchestrated=true` once the PR is created. Append the returned `telemetry_payload` to the `child_steps` list. Pass that list to `feature_implement_finished`.

## Size Reference

| | SMALL (≤5 tasks, ≤3 boundaries) | MEDIUM (6-15 tasks, ≤6 boundaries) | LARGE (>15 tasks or >6 boundaries) |
|---|---|---|---|
| Save spec to disk | No | Yes | Yes |
| Compaction | No | Post-impl | Post-impl + post-review |
| Completeness audit | Quick confirmation | Full per-criterion report | Full per-criterion report |
| Boundary history | If impactful | Yes | Yes |
| Codebase discovery | No | Inline | Inline |

All sizes: feature flag if required, code review (dynamic 2-6 agents), `bill-quality-check`, PR description.

## Error Recovery

- Implementation fails mid-plan: stop, report which task failed and why, ask user
- Review enters fix loop (>3 iterations): stop, report remaining issues, hand to user
- Completeness audit loops (>2 iterations): report remaining gaps, let user decide

In all early-exit cases, call `feature_implement_finished` with the appropriate `completion_status` (`abandoned_at_planning`, `abandoned_at_implementation`, `abandoned_at_review`, or `error`) so the telemetry session is closed.

## Skills Invoked

Read each skill's file and apply inline when its step is reached:
- `bill-feature-guard` — if rollout uses a feature flag
- `bill-code-review` — after implementation
- `bill-quality-check` — final validation gate
- `bill-boundary-history` — after completeness audit
- `bill-pr-description` — PR generation
