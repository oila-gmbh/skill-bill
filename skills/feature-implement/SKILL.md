---
name: feature-implement
description: End-to-end feature implementation from design doc to verified code. Collects design spec, optionally applies feature flags, creates an implementation plan, executes it, runs code review, and performs a completeness audit against the original spec. Orchestrates feature-guard, code-review, and gcheck skills automatically.
---

# Feature Implement

End-to-end feature implementation orchestrator. Takes a design doc and delivers reviewed, verified code.

## Workflow Overview

```
Design Doc → Save Spec → Read History → Feature Flag? → Plan → Implement → Code Review → Completeness Audit → Write History
                                                                              ↑              ↓
                                                                              └── Fix ←── Gaps found?
```

## Step 1: Collect Design Doc

Ask the user:
> **Provide the feature design doc** — paste text, give me a file path, or point me to a folder with spec files.

Accept any of:
- Inline text (paste)
- Single file path (PDF or markdown)
- **Directory path** containing multiple spec files (PDFs, images, markdown)

Design docs are often **multi-file** — e.g., a folder with:
- Main spec PDF (goals, background, risks)
- High-level design PDF (architecture, API contracts, data models)
- UI updates PDF (screen changes, new components)
- Screenshots/mockups (UI flows, Figma exports)

**Reading PDFs:** If given PDF files, use `pdfplumber` (install with `pip install pdfplumber` if needed) to extract text. If extraction fails or produces garbage, ask the user to provide a markdown/text version instead.

**Spec size limit:** If the total extracted text exceeds ~8,000 words, ask the user:
> **The spec is very large. Which sections are most relevant for this implementation?**
>
> Summarize the rest and keep the identified sections in full.

When given a directory:
1. List all files and summarize what each contains
2. Read all text-based files (PDF, markdown)
3. Note image files as visual references (reference them by name in the plan)
4. Synthesize into a unified understanding

Then ask:
> **What should this feature be called?** (used for file naming, e.g. `special-occurrence-images`)

## Step 2: Save Spec & Extract Acceptance Criteria

Save the design doc to the project for future reference:

```
.feature-specs/<feature-name>/
├── spec.md          ← consolidated text from all source files
├── sources.md       ← list of original file names (relative or descriptive, not absolute paths)
└── (original PDFs/images are NOT copied — just referenced by name)
```

**Note:** Consider adding `.feature-specs/` to `.gitignore` if specs should not be committed. If specs should be version-controlled, commit the directory as-is.

Format `spec.md` as:
```markdown
# Feature: <feature-name>
Created: <date>
Status: In Progress
Sources: <list of original file names>

---

<consolidated content from all spec files>
```

**Consolidation rules for large specs:**
- Preserve code blocks (GraphQL schemas, data models, API contracts) verbatim — do not summarize these
- Preserve numbered lists, field definitions, and enum values verbatim
- Narrative/background sections can be summarized if space is needed
- Separate each source file's content with a clear heading: `## From: <filename>`

### Extract Non-Goals & Open Questions

Before extracting acceptance criteria, identify:

**Non-Goals** — things explicitly out of scope:
```
🚫 NON-GOALS (from spec):
- Backend API changes (assumed fully implemented)
- Editing the qualification list
```

**Open Questions** — unresolved decisions in the spec:
```
❓ OPEN QUESTIONS (from spec):
- Evaluate technical complexity of photo gallery integration
- Edge case: same person added with different qualifications?
```

Ask user to **resolve open questions** before proceeding:
> **The spec has unresolved questions. Please clarify these before I plan — don't plan around unknowns.**

### Extract Acceptance Criteria

After resolving open questions, extract **numbered acceptance criteria** from the spec and present them:

```
📋 ACCEPTANCE CRITERIA extracted from spec:

1. Users can attach one or more photos per special occurrence entry
2. Supported formats: PNG, JPG, JPEG, WEBP
3. No limit on number of photos per occurrence
4. Photos displayed as gallery/thumbnail view under each entry
5. Deleting a special occurrence cascade-deletes all associated photos
6. Text description becomes optional when photos are attached
7. "Copy Previous Day" does NOT copy photos
8. Only users with edit permissions can upload/delete photos
...
```

Ask user:
> **Are these acceptance criteria complete? Add, remove, or modify any before I plan.**

This confirmed list is the **contract** for the completeness audit in Step 8.

## Step 2b: Read Module History

Before planning, check for historical context in the affected module(s).

Look for `agent/history.md` in each module that will be touched by this feature:
```
<module-path>/agent/history.md
```

This applies to **any module** — feature, data, domain, core, shared. Any module the implementation plan will touch should be checked for history.

If found, read it. This file contains summaries of the last 5 feature implementations in this module — what changed, what patterns were introduced, what to watch out for.

**How to use history:**
- If a previous feature added a component you can reuse, reference it in the plan
- If a previous feature changed a shared entity (e.g., DailyReport), account for new fields
- If a previous feature introduced a new pattern (e.g., updated sync approach), follow the new pattern, not the old one
- If a previous feature left known limitations or TODOs, be aware of them

If no `history.md` exists, skip — this is the first tracked feature in this module.

**Example:** When implementing "special occurrence photos" in the dailyreports module, history might show:
```
## [2026-02-10] visits-name-qualification
Modules: feature/dailyreports
- Added NameSelectionScreen with search + grouped contacts (reusable)
- Updated DailyReport entity with new visits fields
- Used DI switch pattern with feature flag `feature-visits-details`

## [2026-02-14] companies-workforce-details  
Modules: feature/dailyreports
- Added per-worker time tracking to CompanyAttendance entity
- Created shared QualificationSelector composable (reusable)
- Photo gallery component added for company attendance images — follow same pattern
- Sync pattern updated: now uses batched presigned URLs for multiple uploads
```

→ Agent now knows: reuse NameSelectionScreen, follow batched presigned URL pattern (not single), photo gallery component already exists.

## Step 3: Feature Flag Decision

Ask the user:
> **Does this feature need a feature flag for safe rollout?**

If yes:
- Ask for the flag name (or suggest one based on feature name)
- Read the `feature-guard` skill instructions and apply them inline to establish the flag strategy
- Determine the pattern (Legacy / DI Switch / Simple Conditional) based on change size
- The flag strategy shapes the entire implementation plan
- Record the chosen pattern, flag name, and switch point for use in planning

If no:
- Proceed directly to codebase discovery

## Step 3b: Discover Existing Codebase Patterns

Before planning, explore the existing codebase to understand conventions:

1. **Find similar features** — if the spec references existing patterns (e.g., "follow the same pattern as `generalImage`"), locate and study those implementations
2. **Identify project conventions** — DI framework, navigation pattern, repository structure, naming conventions, module layout
3. **Note reusable components** — existing base classes, shared utilities, common patterns that new code should follow

Summarize findings briefly:
```
📂 CODEBASE PATTERNS:
- DI: Hilt with @Provides in *Module classes
- Navigation: Compose Navigation with NavHost
- Images: generalImage pattern — presigned URL → S3 upload → sync metadata
- Repository: offline-first with Room + GraphQL sync
```

These patterns **must** be followed in the implementation plan.

## Step 4: Create Implementation Plan

Using the design doc, acceptance criteria, and feature-guard strategy (if applicable), create a structured plan.

**Planning rules:**
- Break into **atomic tasks** — each task should be completable in one agent turn
- If plan exceeds **15 tasks**, split into phases with a checkpoint between each phase
- Order tasks by dependency (data layer → domain → presentation)
- Each task must reference which acceptance criteria it satisfies
- If feature-guarded, every task must state how it respects the flag strategy (no modifications to legacy code)
- Reference UI mockups/screenshots by filename where relevant

**Plan format:**
```
## Implementation Plan: <feature-name>

### Feature Flag
- Flag: <name> (or N/A)
- Pattern: Legacy / DI Switch / Simple Conditional (or N/A)
- Switch point: where the flag check lives (or N/A)

### Phase 1: <phase name> (Tasks 1-N)

### Prerequisites
- [ ] Any setup needed (dependencies, config, etc.)

### Tasks
1. [ ] Task description
   Files: list of files to create/modify
   Criteria: #1, #3 (references to acceptance criteria)
   Flag: how this task respects feature-guard (or N/A)

2. [ ] Task description
   Files: ...
   Criteria: #5, #7
   Flag: ...
```

## Step 5: User Confirmation + Model Reminder

Present the plan and remind:
> **Review the plan above.**
> 💡 Planning is best done with a premium/reasoning model. If your agent supports model switching, consider switching to a faster model for implementation to save cost and latency.
> 
> **Ready to implement?**

Wait for user confirmation before proceeding.

## Step 6: Execute Plan

Implement each task in order:
- After each task, print progress: `✅ [3/10] Created PaymentRepository with Room integration`
- Mark tasks as completed in the plan
- Follow feature-guard patterns if applicable
- Write clean, production-grade code
- If a task reveals the plan is wrong (e.g., assumption was invalid, dependency doesn't work as expected), **stop and re-plan from that point** — do not force through a broken plan
- Do NOT skip tasks or combine them without user consent
- If plan has phases, pause between phases for a brief checkpoint

## Step 6b: Compact State (Post-Implementation)

Before proceeding to review, **summarize the implementation pass** to free context:

```
📦 IMPLEMENTATION SUMMARY

Files created:  <list of new file paths>
Files modified: <list of changed file paths>
Feature flag:   <name> | Pattern: <Legacy/DI/Conditional> | Switch: <location>

Criteria coverage:
  #1 → SpecialOccurrenceScreen.kt, ImageRepository.kt
  #2 → ImagePicker.kt
  #3 → ImageRepository.kt (no limit enforced)
  ...

Plan deviations: <any changes from original plan, or "None">
```

Discard the detailed implementation log — the code is on disk. This summary is sufficient context for review and audit.

## Step 7: Code Review

When all tasks are completed, automatically:
1. Announce: **"Implementation complete. Running code review..."**
2. Follow the `code-review` skill instructions: launch 6 parallel specialist subagents via the `task` tool (architecture, platform-correctness, performance, security, testing, ux-accessibility), then merge findings into a consolidated report per the code-review output format
3. If **blockers or major issues** are found:
   - Auto-fix the issues
   - Re-run code review (full 6-specialist pass)
   - Repeat until no blockers/majors remain
4. If only minor issues: report them and continue

`code-review` will auto-trigger `gcheck` at the end — do not run gcheck separately.

## Step 7b: Compact State (Post-Review)

Before the completeness audit, **summarize the review outcome** to free context:

```
📦 REVIEW SUMMARY

Review result:    Passed (no blockers/majors)
Issues fixed:     <count> (list 1-liners)
Remaining minors: <count> (list 1-liners)
Files touched during fixes: <list>
gcheck:           ✅ Passed
```

Carry forward: the acceptance criteria list + criteria-to-file mapping from Step 6b. Discard the full review findings — fixes are on disk.

## Step 8: Completeness Audit

After code review passes, perform a **spec completeness audit**:

1. Read the saved spec and **confirmed acceptance criteria** from `.feature-specs/<feature-name>/spec.md`
2. For each numbered acceptance criterion, verify it is implemented by checking the actual code
3. Produce a completeness report:

```
📋 COMPLETENESS AUDIT: <feature-name>

Acceptance criteria: <total>
Implemented:         <count> ✅
Missing:             <count> ❌
Partial:             <count> ⚠️  (code exists but doesn't fully satisfy the criterion — missing edge case, error state, or variant)

─────────────────────────────

✅ #1: Users can attach one or more photos per special occurrence entry
   Implemented in: SpecialOccurrenceScreen.kt:42, ImageRepository.kt:88

✅ #2: Supported formats: PNG, JPG, JPEG, WEBP
   Implemented in: ImagePicker.kt:15 (format filter)

❌ #6: Text description becomes optional when photos are attached
   Not found — description field still marked required

⚠️ #8: Only users with edit permissions can upload/delete photos
   Partial — upload permission checked, delete permission missing
```

### If gaps are found:

Present the report and ask:
> **<N> requirements are missing or incomplete. Want me to implement the remaining items?**

If user says yes:
1. Create a new plan covering only the missing/partial items
2. If the original plan used feature-guard, carry forward the flag strategy — new tasks must follow the same pattern (Legacy/DI Switch/Conditional) and flag name
3. Execute the plan
4. Re-run code review (Step 7)
5. Re-run completeness audit (Step 8)
6. Repeat until fully complete or user says stop

### If fully complete:

```
✅ All acceptance criteria implemented and verified.
✅ Code review passed.
✅ gcheck passed.
```

Update `.feature-specs/<feature-name>/spec.md` status to: **Complete**

## Step 9: Write Module History

After successful completion, update `agent/history.md` in the **primary module only** — the main module where the feature lives (e.g., `feature/dailyreports/` for a daily reports feature).

Ask the user if the primary module is not obvious:
> **Which module is the primary home for this feature?**

**Location:** `<primary-module-path>/agent/history.md`

Create the file and `agent/` directory if they don't exist.

**Entry format:**
```markdown
## [<date>] <feature-name>
Modules: <list of affected modules>
- <what changed — entities, repositories, UI components> (1-2 lines each)
- <new patterns introduced or existing patterns followed>
- <reusable components created> (mark with "reusable")
- <breaking changes or migrations>
- <known limitations or deferred items>
Feature flag: <name and pattern, or N/A>
Acceptance criteria: <count> implemented, <count> deferred
```

**Rules:**
- Each entry is **max 15 lines** — concise, not a changelog
- Keep only the **last 5 entries** per file — when adding a 6th, remove the oldest
- Focus on information useful for the **next** feature implementation in this module
- Do NOT include code snippets — just describe what exists and where
- New entries are prepended (newest first)

**Example history.md after 3 features:**
```markdown
# Module History: feature/dailyreports

## [2026-02-16] special-occurrence-photos
Modules: feature/dailyreports
- Added SpecialOccurrenceImage Room entity + DAO following generalImage pattern
- Created SpecialOccurrenceImageRepository (offline-first sync)
- Integrated photo gallery into SpecialOccurrence detail screen (reusable: PhotoGallery composable)
- Description field now optional when photos attached
- "Copy Previous Day" explicitly skips photo copying
Feature flag: feature-special-occurrence-photos (DI switch)
Acceptance criteria: 15/15 implemented

## [2026-02-14] companies-workforce-details
Modules: feature/dailyreports
- Added per-worker time tracking to CompanyAttendance entity
- Created shared QualificationSelector composable (reusable)
- Photo gallery component added for company attendance images
- Sync: batched presigned URLs for multiple uploads (new pattern)
Feature flag: feature-companies-workforce (Legacy pattern)
Acceptance criteria: 12/12 implemented

## [2026-02-10] visits-name-qualification
Modules: feature/dailyreports
- Added NameSelectionScreen with search + grouped contacts (reusable)
- Updated DailyReport entity with new visits fields
- Used DI switch pattern with feature flag `feature-visits-details`
Feature flag: feature-visits-details (DI switch)
Acceptance criteria: 10/10 implemented
```

## Error Recovery

- If implementation fails mid-plan: stop, report which task failed and why, ask user how to proceed
- If code review enters a fix loop (>3 iterations): stop, report remaining issues, hand back to user
- If completeness audit loops (>2 iterations): report remaining gaps, let user decide

## Skills Invoked

This skill orchestrates (by reading their instructions and applying inline):
- `feature-guard` — if feature flag is needed (Step 3)
- `code-review` — automatic after implementation (Step 7), launches 6 specialist subagents via `task` tool
- `gcheck` — triggered automatically by code-review
