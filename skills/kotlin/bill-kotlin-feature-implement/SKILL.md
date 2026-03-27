---
name: bill-kotlin-feature-implement
description: Use when doing end-to-end feature implementation from design doc to verified code. Automatically scales ceremony based on feature size — lightweight for small changes, full orchestration for large features. Collects design spec, plans, implements, reviews, and audits completeness.
---

# Feature Implement v2

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-kotlin-feature-implement` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults. When you read another skill inline, also apply that skill's matching section from `.agents/skill-overrides.md` when present.

## Workflow Overview

```
Design Doc + Issue Key → Assess + Extract Criteria → [Size gate] → Create Branch →

  SMALL (≤5 tasks, ≤3 modules):
    Save Spec → [Read History] → Plan → Implement → bill-kotlin-code-review (narrow scope, dynamic 2-6 agents) → Completeness Audit → Final Validation Gate (auto-selected) → [Write History if impactful] → PR Description

  MEDIUM (6-15 tasks, ≤6 modules):
    Save Spec → Read History → Plan → Implement →
    Compact → bill-kotlin-code-review (dynamic 2-6 agents) → Completeness Audit → Final Validation Gate (auto-selected) → Write History → PR Description

  LARGE (>15 tasks or >6 modules):
    Save Spec → Read History → Feature Flag? → Plan (phased) → Implement →
    Compact → bill-kotlin-code-review (dynamic 2-6 agents) → Compact → Completeness Audit → Final Validation Gate (auto-selected) → Write History → PR Description
```

## Step 1: Collect Design Doc + Assess Size

Ask the user:
> **Provide the feature design doc** — paste text, give me a file path, or point me to a folder with spec files.
> Also provide the **Jira issue key** (e.g., `ME-5066`).

Accept any of:
- Inline text (paste)
- Single file path (PDF or markdown)
- **Directory path** containing multiple spec files (PDFs, images, markdown)

**Reading PDFs:** If given PDF files, use the Read tool (it supports PDFs). For large PDFs (>10 pages), read in page ranges.

**Spec size limit:** If the total extracted text exceeds ~8,000 words, ask the user:
> **The spec is very large. Which sections are most relevant for this implementation?**

When given a directory:
1. List all files and summarize what each contains
2. Read all text-based files (PDF, markdown)
3. Note image files as visual references (reference them by name in the plan)
4. Synthesize into a unified understanding

### Single-Pass Assessment

After reading the spec, perform the assessment **in one pass** — do not ask multiple sequential questions. Present everything together:

1. **Extract acceptance criteria** — numbered list
2. **Identify non-goals** — things explicitly out of scope
3. **Flag open questions** — unresolved decisions (if any)
4. **Determine feature size** — SMALL / MEDIUM / LARGE based on:
   - Number of expected tasks
   - Number of modules touched
   - Whether new modules/screens are needed
   - Whether sync/offline/migration is involved
5. **Infer feature name** from the spec (e.g., `daily-report-ai-empty`)
6. **Infer feature flag need** — if it's a new user-facing feature with rollout risk, suggest a flag; if it's an enhancement behind an existing flag, say N/A

Present this as a single block:

```
📋 ACCEPTANCE CRITERIA:
1. ...
2. ...

🚫 NON-GOALS: ...
❓ OPEN QUESTIONS: ... (or "None")

📏 SIZE: SMALL (estimated ~N tasks, ~N modules)
🏷️ FEATURE NAME: <name>
🌿 BRANCH: feat/<ISSUE_KEY>-<feature-name>
🚩 FEATURE FLAG: N/A (or suggested name + pattern)
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

**All sizes:** Always **Save Spec** (the acceptance criteria serve as the contract for the completeness audit), **Read Module History** if history files exist in affected modules, and determine the **final validation strategy** automatically.
**MEDIUM and LARGE only:** Also discover codebase patterns. Perform Feature Flag Setup when the feature is LARGE or when the user confirmed a flag is needed.

### Save Spec

Save the design doc to `.feature-specs/<feature-name>/spec.md`:

```markdown
# Feature: <feature-name>
Created: <date>
Status: In Progress
Sources: <list of original sources>

## Acceptance Criteria
1. ...

---

<consolidated spec content>
```

**Consolidation rules for large specs:**
- Preserve code blocks (GraphQL schemas, data models, API contracts) verbatim
- Preserve numbered lists, field definitions, and enum values verbatim
- Narrative/background sections can be summarized if space is needed

### Read Module History

Look for `agent/history.md` in each module the feature will touch. If found, use it to:
- Read newest entries first
- Keep scanning while entries are still relevant to the current feature, module, entities, or patterns
- Stop once older entries are clearly no longer relevant or you already have enough signal; do not read the whole file by default
- Reuse components from previous features
- Follow the latest patterns (not outdated ones)
- Account for recent entity changes

If no history exists, skip.

### Feature Flag Setup (LARGE only, or when user confirmed flag needed)

- Read the `bill-feature-guard` skill instructions and its matching `.agents/skill-overrides.md` section, then apply them inline
- Determine the pattern (Legacy / DI Switch / Simple Conditional)
- Record the chosen pattern, flag name, and switch point
- Do not auto-apply a fixed prefix (including `me-android-`) when proposing new flag names; only use a prefix if the user explicitly asks for it

### Discover Codebase Patterns

Explore the codebase concurrently with planning:
1. Read `CLAUDE.md`, `AGENTS.md`, and the matching `bill-kotlin-feature-implement` section in `.agents/skill-overrides.md` when present — treat all standards as mandatory
2. Find similar features referenced in the spec
3. Identify build dependencies for affected modules
4. Note reusable components
5. Identify validation surfaces so the final gate is chosen automatically:
   - Gradle/Kotlin project or modules → `bill-kotlin-quality-check`
   - Agent-config / skill repository (`SKILL.md`, `AGENTS.md`, `.agents/skill-overrides.md`, `CLAUDE.md`, `.claude/`, `.cursor/`, `.github/copilot-instructions*`, installer/config files, repo-native validation scripts) → inline agent-config validation
   - Mixed repository → run both
6. If a repo-native validation script already exists, reuse it instead of inventing a new ad hoc checklist

Do NOT present a separate "codebase patterns" section to the user — fold these findings directly into the implementation plan.

## Step 3: Create Implementation Plan

**Planning rules (all sizes):**
- Break into **atomic tasks** — each task completable in one turn
- Order tasks by dependency (data layer → domain → presentation)
- Each task must reference which acceptance criteria it satisfies
- **The final task in every plan MUST be a dedicated test task.** This task writes unit tests covering the new/changed logic. It is never optional — if there is code to implement, there are tests to write. The `Tests:` field on this task must list the specific scenarios to cover (e.g., happy path, edge cases, guard clauses). Implementation tasks may set `Tests: None` only because testing is deferred to this final task.

**Additional rules for MEDIUM/LARGE:**
- If plan exceeds **15 tasks**, split into phases with a checkpoint between each
- If feature-guarded, every task states how it respects the flag strategy
- Reference UI mockups/screenshots by filename where relevant

**Plan format:**
```
## Implementation Plan: <feature-name>

### Feature Flag
- Flag: <name> (or N/A)
- Pattern: <pattern> (or N/A)

### Final Validation
- Strategy: `bill-kotlin-quality-check` | inline agent-config validation | both
- Reason: <why this gate applies to the affected repo/modules>
- Commands/scripts: <existing commands or repo-native scripts to run>

### Tasks
1. [ ] Task description
   Files: list of files to create/modify
   Criteria: #1, #3
   Tests: what test coverage (or "None")

2. [ ] ...
```

Present the plan and ask:
> **Ready to implement?**

Wait for user confirmation.

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
- **When removing UI components or code:** immediately clean up orphaned resources (string keys, drawables, imports, unused mappers) in the same task — don't leave dead code for review to catch
- **When changing agent-config or skill repositories:** update adjacent catalogs and wiring in the same task (README skill tables/counts, installer/config references, validation scripts/workflows) so the repo stays self-consistent
- **Test gate:** Before moving to review/compaction, verify that unit tests were written. If the test task was somehow skipped or omitted from the plan, stop and write tests now. Never proceed to code review without tests.

### Post-Implementation Compact (MEDIUM and LARGE only)

Before review, summarize to free context:

```
📦 IMPLEMENTATION SUMMARY

Files created:  <list>
Files modified: <list>
Feature flag:   <name and pattern, or N/A>

Criteria coverage:
  #1 → FileA.kt, FileB.kt
  #2 → FileC.kt
  ...

Plan deviations: <any, or "None">
```

Then re-read `.feature-specs/<feature-name>/spec.md` to refresh acceptance criteria. Check that every criterion is mapped.

## Step 5: Code Review

Run the `bill-kotlin-code-review` skill and its matching `.agents/skill-overrides.md` section, then apply them inline. Treat that skill as the source of truth for:
- Project classification
- Dynamic specialist selection
- Android/KMP vs backend/server routing
- Parallel review execution
- Finding deduplication and prioritization

Pass the following context from this run:
- Review scope: current unit of work for SMALL features, current branch diff for MEDIUM/LARGE features
- Confirmed acceptance criteria and spec path
- Feature size
- Feature flag name/pattern (or `N/A`)
- The rule that newly introduced deprecated components, APIs, or patterns are not allowed when a supported alternative exists; any unavoidable usage must be explicitly justified and kept narrowly scoped

### Review loop rules
- Do not duplicate specialist trigger tables in this skill
- If Blocker or Major issues are found, auto-fix them and re-run `bill-kotlin-code-review` (or only the affected specialists if that skill supports it)
- If only Minor issues remain, report them and continue
- When `bill-kotlin-code-review` is invoked from this skill, do not stop to ask the user which finding to fix first
- Max **3 review iterations** — after that, report remaining issues and hand back to user

## Step 6: Completeness Audit

For **all sizes**, verify every numbered acceptance criterion against the actual code:

```
📋 COMPLETENESS AUDIT: <feature-name>

Acceptance criteria: <total>
Implemented:         <count> ✅
Missing:             <count> ❌
Partial:             <count> ⚠️

─────────────────────────────

✅ #1: <criterion text>
   Implemented in: FileA.kt:42, FileB.kt:88

❌ #6: <criterion text>
   Not found — <reason>

⚠️ #8: <criterion text>
   Partial — <what's missing>
```

**Platform coverage check** (only if project targets multiple platforms):
- Verify platform-specific source sets, manifests, expect/actual declarations

### If gaps found:

> **<N> requirements are missing or incomplete. Want me to implement the remaining items?**

If yes: plan missing items → implement → review → re-audit. Max **2 audit iterations**.

### If fully complete:

```
✅ All acceptance criteria implemented and verified.
✅ Code review passed.
```

Update spec status to **Complete** (MEDIUM/LARGE only).

## Step 6b: Final Validation Gate (All sizes)

After completeness audit passes, **infer the final validation gate automatically** from the repo shape and changed files. Do not ask the user to choose.

### Validation strategy

- **Gradle/Kotlin repos or modules touched:** run `bill-kotlin-quality-check`
- **Agent-config / skill repos touched:** run inline agent-config validation
- **Both:** run both gates
- **Neither:** run the closest existing repo-native validation command or test command already present in the project

### Inline agent-config validation

When the repo contains agent-config surfaces such as `SKILL.md`, `AGENTS.md`, `.agents/skill-overrides.md`, `CLAUDE.md`, `.claude/`, `.cursor/`, `.github/copilot-instructions*`, agent installer/config files, or repo-native validation scripts:

1. Run `agnix` when available
   - Prefer installed `agnix`
   - Otherwise use `npx agnix .` if Node/npm is available
2. Run any repo-native validation scripts and workflows already provided by the repo
3. Fix issues at root cause in the changed files
4. If the change created repo-wide metadata drift directly tied to the work (README tables/counts, skill references, manifest metadata, workflow wiring), fix that drift in the same pass

Do **not** create a separate utility skill just for this validation path unless the repository itself is explicitly centered on validation as a product concept. `bill-kotlin-feature-implement` owns this behavior.

## Step 7: Write Module History

Run the `bill-module-history` skill and its matching `.agents/skill-overrides.md` section, then apply them inline.

Pass the required inputs from this run:
- Feature name
- Feature size (SMALL/MEDIUM/LARGE)
- Primary module + affected modules
- Feature flag name/pattern (or N/A)
- Acceptance criteria coverage summary
- Change summary (what changed, reusable components/patterns, breaking changes or limitations)

The `bill-module-history` skill owns the write/skip rules, entry format, and history-file hygiene rules.

## Step 8: Generate PR Description (All sizes)

Run the `bill-pr-description` skill and its matching `.agents/skill-overrides.md` section to generate a PR title, description, and QA steps.

Pass along:
- Feature name / spec path if available
- The actual comparison base used for this feature branch (or enough git context for the skill to compute the merge-base correctly)
- Manual verification notes from this run

## Error Recovery

- Implementation fails mid-plan: stop, report which task failed and why, ask user
- Review enters fix loop (>3 iterations): stop, report remaining issues, hand to user
- Completeness audit loops (>2 iterations): report remaining gaps, let user decide

## Skills Invoked

This skill orchestrates (by reading their instructions and applying inline):
- `bill-feature-guard` — if feature flag is needed (LARGE, or when confirmed)
- `bill-kotlin-code-review` — automatic after implementation; it classifies the diff and selects 2-6 specialists dynamically
- `bill-kotlin-quality-check` — when the affected repo/modules use the Gradle/Kotlin validation path
- `bill-module-history` — writes/maintains `agent/history.md` for impactful or larger features

## Size Reference

| Indicator | SMALL | MEDIUM | LARGE |
|-----------|-------|--------|-------|
| Tasks | ≤5 | 6-15 | >15 |
| Modules touched | ≤3 | ≤6 | >6 |
| New screens/modules | No | Maybe | Yes |
| Sync/migration involved | No | No | Yes |
| Save spec to disk | Yes | Yes | Yes |
| Read module history | If exists | Yes | Yes |
| Feature flag ceremony | No | If needed | Full |
| Codebase discovery section | Inline | Inline | Inline |
| Compaction steps | No | Post-impl | Post-impl + post-review |
| Review agents | Dynamic (2-6, based on diff) | Dynamic (2-6, based on diff) | Dynamic (2-6, based on diff) |
| Final validation gate | Auto-detected | Auto-detected | Auto-detected |
| Write module history | If impactful | Yes | Yes |
| PR description | Yes | Yes | Yes |
