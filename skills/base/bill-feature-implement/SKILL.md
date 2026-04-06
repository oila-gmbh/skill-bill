---
name: bill-feature-implement
description: Use when doing end-to-end feature implementation from design doc to verified code. Automatically scales ceremony based on feature size — lightweight for small changes, full orchestration for large features. Collects design spec, plans, implements, reviews, and audits completeness.
---

# Feature Implement v2

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-feature-implement` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults. When you read another skill inline, also apply that skill's matching section from `.agents/skill-overrides.md` when present.

## Step 1: Collect Design Doc + Assess Size

Ask the user for:
1. **Feature design doc** — inline text, file path, or directory of spec files
2. **Issue key** (e.g., `ME-5066`)

Accept PDFs (read in page ranges if >10 pages), markdown, images (note as visual references). If a directory, read all files and synthesize. If spec exceeds ~8,000 words, ask which sections matter most.

### Single-Pass Assessment

After reading the spec, perform the assessment **in one pass** — do not ask multiple sequential questions. Present everything together:

1. **Extract acceptance criteria** — numbered list
2. **Identify non-goals** — things explicitly out of scope
3. **Flag open questions** — unresolved decisions (if any)
4. **Determine feature size** — SMALL / MEDIUM / LARGE based on:
   - Number of expected tasks
   - Number of boundaries touched
   - Whether new boundaries or user-facing surfaces are needed
   - Whether sync/offline/migration is involved
5. **Infer feature name** from the spec (e.g., `daily-report-ai-empty`)
6. **Infer rollout need** — if the spec, user, existing rollout path, or repo policy requires guarded rollout, note the expected mechanism; otherwise default to `N/A` and do not invent a new feature flag only because the feature is large

Present this as a single block:

```
📋 ACCEPTANCE CRITERIA:
1. ...
2. ...

🚫 NON-GOALS: ...
❓ OPEN QUESTIONS: ... (or "None")

📏 SIZE: SMALL (estimated ~N tasks, ~N boundaries)
🏷️ FEATURE NAME: <name>
🌿 BRANCH: feat/<ISSUE_KEY>-<feature-name>
🚩 ROLLOUT: N/A | existing feature flag <name> | new feature flag <name/pattern>
```

Then ask:
> **Confirm or adjust the above before I plan.**

If there are open questions, they must be resolved before proceeding.

This confirmed acceptance criteria list is the **contract** for the completeness audit.

## Step 1b: Create Feature Branch

After the user confirms the assessment, create and switch to a new feature branch:

1. Branch name format: `feat/{ISSUE_KEY}-{feature-name}` (e.g., `feat/ME-5066-sj-so-thumbnail`)
2. Base branch: current branch (typically `main`)
3. Run: `git checkout -b feat/{ISSUE_KEY}-{feature-name}`
4. Print confirmation: `🌿 Created and switched to branch: feat/{ISSUE_KEY}-{feature-name}`

## Step 2: Pre-Planning

**All sizes:** **Read Boundary History** if history files exist near the affected module/package/area, and determine the **final validation strategy** automatically.
**MEDIUM and LARGE only:** Also **Save Spec** to disk, discover codebase patterns, and perform Feature Flag Setup only when the chosen rollout strategy requires a feature flag.

### Save Spec (MEDIUM and LARGE only)

Save to `.feature-specs/{ISSUE_KEY}-{feature-name}/spec.md` with: feature name, issue key, date, status (`In Progress`), sources, acceptance criteria, and consolidated spec content. Preserve code blocks, schemas, field definitions, and enums verbatim; narrative sections may be summarized.

For SMALL features the acceptance criteria stay in context — no spec file needed.

### Read Boundary History

Look for `agent/history.md` in each boundary the feature touches. Read newest entries first, stop once entries are no longer relevant. Use to reuse components and follow latest patterns. Skip if none exist.

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

## Step 3: Create Implementation Plan

**Planning rules (all sizes):**
- Break into **atomic tasks** — each task completable in one turn
- Order tasks by dependency (data layer → domain → presentation)
- Each task must reference which acceptance criteria it satisfies
- **If the plan includes testable logic, the final task must be a dedicated test task.** This task writes unit tests covering the new/changed logic. Implementation tasks may set `Tests: None` only because testing is deferred to this final task. Skip the test task only when there is genuinely nothing testable (pure config, documentation, agent-config/skill prose, or UI changes with no test infra).

**Additional rules for MEDIUM/LARGE:**
- If plan exceeds **15 tasks**, split into phases with a checkpoint between each
- If the rollout strategy uses a feature flag, every task states how it respects that flag strategy
- Reference relevant design artifacts by filename where relevant (for example mockups, screenshots, wireframes, API examples)

**Plan format:** Include rollout info (flag + pattern, or N/A), final validation strategy (`bill-quality-check`), then numbered tasks. Each task: description, files to create/modify, which acceptance criteria it satisfies, and test coverage (or "None" if deferred to the final test task).

Present the plan, then proceed to implementation. The user already confirmed the acceptance criteria — the plan is the agent's breakdown, not a second approval gate. If the plan is materially wrong, the completeness audit will catch it.

## Step 4: Execute Plan

 Implement each task in order:
 - After each task, print progress: `✅ [3/10] Created PaymentRepository with Room integration`
 - Follow project standards from `CLAUDE.md`, `AGENTS.md`, and any matching `.agents/skill-overrides.md` sections used by this workflow
 - Write clean, production-grade code
  - Never introduce deprecated components, APIs, or patterns when a supported alternative exists. If absolutely no viable alternative exists, call that out explicitly, explain why, and keep the deprecated usage as narrow as possible.
  - **Write tests as specified** in each task's `Tests:` field
  - If a task reveals the plan is wrong, **stop and re-plan from that point**
  - Do NOT skip or combine tasks without user consent
  - If plan has phases, pause between phases for a brief checkpoint
- **When removing user-facing code, shared resources, or wiring:** immediately clean up orphaned artifacts (for example resource entries, assets, imports, unused mappers) in the same task — don't leave dead code for review to catch
- **When changing agent-config or skill repositories:** update adjacent catalogs and wiring in the same task (README skill tables/counts, installer/config references, validation scripts/workflows) so the repo stays self-consistent
- **Test gate:** Before moving to review/compaction, verify that unit tests were written if the plan included testable logic. If the test task was omitted from a plan with testable code, stop and write tests now.

### Post-Implementation Compact (MEDIUM and LARGE only)

Before review, summarize: files created/modified, feature flag info, criteria-to-file mapping, and plan deviations. Then re-read `.feature-specs/{ISSUE_KEY}-{feature-name}/spec.md` to refresh acceptance criteria and verify every criterion is mapped.

## Step 5: Code Review

Run `bill-code-review` (read its skill file and apply inline). Scope: current unit of work for SMALL, branch diff for MEDIUM/LARGE.

**Review loop:** Auto-fix Blocker and Major findings, re-run review. Continue past Minor-only findings. Max **3 iterations** — after that, report remaining issues and hand back to user. Do not pause to ask the user which finding to fix.

## Step 6: Completeness Audit

**SMALL:** Quick confirmation that all acceptance criteria are satisfied. List any gaps — no formal per-criterion report needed.

**MEDIUM and LARGE:** Verify every numbered acceptance criterion against actual code. For each criterion report: implemented (with file:line), missing (with reason), or partial (with what's missing). If the project targets multiple platforms, also verify platform-specific entry points and shared-vs-platform declarations.

If gaps found: ask user, then plan → implement → review → re-audit. Max **2 audit iterations**. When fully complete, update spec status to **Complete** (MEDIUM/LARGE only).

## Finalization sequence (Steps 6b → 9)

Once the completeness audit passes, run Steps 6b through 9 as a **continuous sequence without pausing for user confirmation**. The only reason to stop is if a step fails. Do not ask the user to approve commits, pushes, or PR creation — these are part of the contracted workflow output.

## Step 6b: Final Validation Gate (All sizes)

After completeness audit passes, **infer the final validation gate automatically** from the repo shape and changed files. Do not ask the user to choose.

Run `bill-quality-check` — it detects the dominant stack (including agent-config repos) and routes to the matching stack-specific quality-check skill automatically.

If `bill-quality-check` reports no supported stack for the affected repo, fall back to the closest existing repo-native validation command or test command already present in the project.

## Step 7: Write Boundary History

Run `bill-boundary-history` (read its skill file and apply inline). The skill owns write/skip rules and entry format.

## Step 8: Commit and Push

Commit all changes and push the feature branch:

1. Stage all new and modified files from this feature (do not use `git add -A`)
2. Commit with message format: `feat: [<ISSUE_KEY>] <concise description>`
3. Push the branch to the remote with `-u` to set upstream tracking

## Step 9: Generate PR Description (All sizes)

Run `bill-pr-description` (read its skill file and apply inline) to generate a PR title, description, and QA steps.

## Error Recovery

- Implementation fails mid-plan: stop, report which task failed and why, ask user
- Review enters fix loop (>3 iterations): stop, report remaining issues, hand to user
- Completeness audit loops (>2 iterations): report remaining gaps, let user decide

## Skills Invoked

Read each skill's file and apply inline when its step is reached:
- `bill-feature-guard` — if rollout uses a feature flag
- `bill-code-review` — after implementation
- `bill-quality-check` — final validation gate
- `bill-boundary-history` — after completeness audit
- `bill-pr-description` — PR generation

## Size Reference

| | SMALL (≤5 tasks, ≤3 boundaries) | MEDIUM (6-15 tasks, ≤6 boundaries) | LARGE (>15 tasks or >6 boundaries) |
|---|---|---|---|
| Save spec to disk | No | Yes | Yes |
| Compaction | No | Post-impl | Post-impl + post-review |
| Completeness audit | Quick confirmation | Full per-criterion report | Full per-criterion report |
| Boundary history | If impactful | Yes | Yes |
| Codebase discovery | No | Inline | Inline |

All sizes: feature flag if required, code review (dynamic 2-6 agents), `bill-quality-check`, PR description.
