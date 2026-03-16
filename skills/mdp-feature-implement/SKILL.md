---
name: mdp-feature-implement
description: End-to-end feature implementation from design doc to verified code. Automatically scales ceremony based on feature size — lightweight for small changes, full orchestration for large features. Collects design spec, plans, implements, reviews, and audits completeness.
---

# Feature Implement v2

End-to-end feature implementation orchestrator. Takes a design doc and delivers reviewed, verified code. **Adapts workflow intensity to feature size.**

## Workflow Overview

```
Design Doc + Issue Key → Assess + Extract Criteria → [Size gate] → Create Branch →

  SMALL (≤5 tasks, ≤3 modules):
    Save Spec → [Read History] → Plan → Implement → Targeted Review → Completeness Audit → mdp-gcheck → [Write History if impactful] → PR Description

  MEDIUM (6-15 tasks, ≤6 modules):
    Save Spec → Read History → Plan → Implement →
    Compact → Code Review (3 agents) → Completeness Audit → mdp-gcheck → Write History → PR Description

  LARGE (>15 tasks or >6 modules):
    Save Spec → Read History → Feature Flag? → Plan (phased) → Implement →
    Compact → Code Review (6 agents) → Compact → Completeness Audit → mdp-gcheck → Write History → PR Description
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

**All sizes:** Always **Save Spec** (the acceptance criteria serve as the contract for the completeness audit) and **Read Module History** if history files exist in affected modules.
**MEDIUM and LARGE only:** Also perform Feature Flag Setup and Discover Codebase Patterns.

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
- Reuse components from previous features
- Follow the latest patterns (not outdated ones)
- Account for recent entity changes

If no history exists, skip.

### Feature Flag Setup (LARGE only, or when user confirmed flag needed)

- Read the `mdp-feature-guard` skill instructions and apply them inline
- Determine the pattern (Legacy / DI Switch / Simple Conditional)
- Record the chosen pattern, flag name, and switch point
- Do not auto-apply a fixed prefix (including `me-android-`) when proposing new flag names; only use a prefix if the user explicitly asks for it

### Discover Codebase Patterns

Explore the codebase concurrently with planning:
1. Read `CLAUDE.md` / `AGENTS.md` at project root — treat all standards as mandatory
2. Find similar features referenced in the spec
3. Identify build dependencies for affected modules
4. Note reusable components

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
- Follow project standards from CLAUDE.md / AGENTS.md
- Write clean, production-grade code
- **Write tests as specified** in each task's `Tests:` field
- If a task reveals the plan is wrong, **stop and re-plan from that point**
- Do NOT skip or combine tasks without user consent
- If plan has phases, pause between phases for a brief checkpoint
- **When removing UI components or code:** immediately clean up orphaned resources (string keys, drawables, imports, unused mappers) in the same task — don't leave dead code for review to catch
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

### Agent Selection

**Always spawn:** `mdp-code-review-architecture` — boundaries, DI, source-of-truth, state management. Architecture review is relevant for every non-trivial change.

**Analyze the actual diff** and spawn additional specialists based on what changed:

| Trigger | Agent |
|---------|-------|
| Compose files modified or created (`@Composable`, UI state classes) | `mdp-code-review-compose-check` |
| Coroutines, Flows, lifecycle-aware code, threading (`launch`, `Flow`, `LifecycleOwner`, `Dispatchers`) | `mdp-code-review-platform-correctness` |
| Auth, tokens, keys, passwords, encryption, HTTP clients, or sensitive data handling | `mdp-code-review-security` |
| Lists, lazy layouts, animations, heavy computation, image loading, or retry/polling logic | `mdp-code-review-performance` |
| New/modified tests, or test files touched | `mdp-code-review-testing` |
| User-facing UI changes, accessibility attributes, navigation, error states, localization | `mdp-code-review-ux-accessibility` |

**Rules:**
- Spawn all selected agents **in parallel**
- Minimum 2 agents (architecture + at least one other), maximum 6
- If no additional triggers match, spawn `mdp-code-review-platform-correctness` as the second agent (coroutine/lifecycle issues are common enough to justify a default)
- If **blockers or major issues** found: auto-fix, re-run only the affected agents
- If only minor issues: report them and continue
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

## Step 6b: mdp-gcheck (All sizes)

**Always run `mdp-gcheck` as the final gate** after completeness audit passes. This ensures formatting, lint, and tests pass regardless of feature size. Follow the `mdp-gcheck` skill instructions: run `./gradlew check` and fix all issues at root cause without suppressions.

## Step 7: Write Module History

Run the `mdp-module-history` skill and apply it inline.

Pass the required inputs from this run:
- Feature name
- Feature size (SMALL/MEDIUM/LARGE)
- Primary module + affected modules
- Feature flag name/pattern (or N/A)
- Acceptance criteria coverage summary

The `mdp-module-history` skill owns the write/skip rules, entry format, and retention limits.

## Step 8: Generate PR Description (All sizes)

Run the `mdp-pr-description` skill to generate a PR title, description, and QA steps.

## Error Recovery

- Implementation fails mid-plan: stop, report which task failed and why, ask user
- Review enters fix loop (>3 iterations): stop, report remaining issues, hand to user
- Completeness audit loops (>2 iterations): report remaining gaps, let user decide

## Skills Invoked

This skill orchestrates (by reading their instructions and applying inline):
- `mdp-feature-guard` — if feature flag is needed (LARGE, or when confirmed)
- `mdp-code-review` — automatic after implementation (LARGE uses full 6-agent pass)
- `mdp-gcheck` — always, as final gate after completeness audit
- `mdp-module-history` — writes/maintains `agent/history.md` for impactful or larger features

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
| Codebase discovery section | Inline | Inline | Separate |
| Compaction steps | No | Post-impl | Post-impl + post-review |
| Review agents | Dynamic (2-6, based on diff) | Dynamic (2-6, based on diff) | Dynamic (2-6, based on diff) |
| mdp-gcheck | Yes | Yes | Yes |
| Write module history | If impactful | Yes | Yes |
| PR description | Yes | Yes | Yes |
