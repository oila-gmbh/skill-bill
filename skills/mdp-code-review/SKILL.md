---
name: mdp-code-review
description: Conduct a thorough Android PR code review following Clean Architecture, MVVM, Jetpack Compose, and Kotlin Coroutines best practices. Use when reviewing pull requests, specific files, git commits, or working changes in Android/Kotlin projects. Produces a structured review with risk register, architecture analysis, scoring, and prioritized action items.
---

# Android PR Review

You are an experienced Android architect conducting a code review.

## Project Overrides

If an `AGENTS.md` file exists in the project root, read it and apply its rules alongside the defaults. Project rules take precedence when they conflict. Pass this instruction to all spawned sub-agents.

## Setup

Determine the review scope:
- Specific files (list paths)
- Git commits (hashes/range)
- Working changes (`git diff`)
- Entire PR

---

## Dynamic Agent Selection

### Step 1: Always spawn `mdp-code-review-architecture`

Architecture review is relevant for every non-trivial change.

### Step 2: Analyze the diff and select additional agents

Read the changed files and match against these triggers:

| Signal in the diff | Agent to spawn |
|---------------------|----------------|
| `@Composable` functions, UI state classes, Modifier chains, `remember`, `LaunchedEffect` | `mdp-code-review-compose-check` |
| `launch`, `Flow`, `StateFlow`, `viewModelScope`, `LifecycleOwner`, `DispatcherProvider`, `suspend fun` | `mdp-code-review-platform-correctness` |
| Auth, tokens, keys, passwords, encryption, HTTP clients, interceptors, sensitive data | `mdp-code-review-security` |
| `LazyColumn`/`LazyRow`, animations, heavy computation, image loading, retry/polling, bulk DB ops | `mdp-code-review-performance` |
| Test files modified (`*Test.kt`), new test classes, mock setup changes | `mdp-code-review-testing` |
| User-facing UI changes, `stringResource`, accessibility attributes, navigation, error states, localization files | `mdp-code-review-ux-accessibility` |

### Step 3: Apply minimum

- Minimum 2 agents (architecture + at least one other)
- If no additional triggers match, spawn `mdp-code-review-platform-correctness` as default second agent
- Maximum 6 agents

### Step 4: Launch in parallel

Spawn all selected agents simultaneously using the `task` tool. Each agent gets:
- The list of changed files
- Instructions to read its own skill file for the review rubric
- The shared contract below

---

## Shared Contract For Every Specialist

- Scope: review only the changes in the current PR/unit of work — do not flag pre-existing issues in unchanged code
- Review only meaningful issues (bug, logic flaw, security risk, regression risk, architectural breakage)
- Ignore style, formatting, naming bikeshedding, and pure refactor preferences
- Evidence is mandatory: include `file:line` + short description
- Severity: `Blocker | Major | Minor`
- Confidence: `High | Medium | Low`
- Maximum 7 findings per specialist
- Include a minimal, concrete fix for each finding

### Required Finding Schema

```
[SEVERITY] Area: Issue title
  Location: file:line
  Impact: Why it matters (1 sentence)
  Fix: Concrete fix (1-2 lines)
  Confidence: High/Medium/Low
```

---

## Orchestrator Merge Rules

1. Collect all specialist findings.
2. If a specialist agent fails or returns no output, note it in the summary and continue with available results.
3. Deduplicate by root cause (same evidence or same failing behavior).
4. Keep highest severity/confidence when duplicates conflict.
5. Prioritize: Blocker > Major > Minor, then blast radius.
6. Produce one consolidated report.

---

## Review Output Format

### 1. Agent Selection Summary
```
Agents spawned: architecture, mdp-code-review-compose-check, platform-correctness
Reason: Compose files modified, coroutine scoping in delegate
```

### 2. Risk Register

Format each issue as:
```
[IMPACT_LEVEL] Area: Issue title
  Location: file:line
  Impact: Description
  Fix: Concrete action
```

Impact levels: BLOCKER | MAJOR | MINOR

### 3. Action Items (Max 10, prioritized)

```
1. [P0 BLOCKER] Fix issue (Effort: S, Impact: High)
2. [P1 MAJOR] Fix issue (Effort: M, Impact: Medium)
3. [P2 MINOR] Fix issue (Effort: S, Impact: Low)
```

Priority: P0 (blocker) | P1 (critical) | P2 (important) | P3 (nice-to-have)
Effort: S (<1h) | M (1-4h) | L (>4h)

### 4. Verdict

`Ship` | `Ship with fixes [list P0/P1 items]` | `Block until [list blockers]`

---

## Implementation Mode

After review, ask: **"Which item would you like me to fix?"**

After all P0 and P1 items are resolved, run `mdp-gcheck` as final verification.

---

## Review Principles

- Changed code only: review what was added or modified in this PR — do not report issues in untouched code, even if it violates current rules
- Evidence-based: cite `file:line`
- Project-aware: each agent has project-specific rules in its skill file
- Actionable: every issue must have a concrete fix
- Proportional: don't nitpick style if architecture is broken
- No overoptimization: do not report negligible performance findings that have no measurable user-facing impact. Only flag performance issues that cause jank, ANR, memory pressure, or battery drain.
- Honest: if unsure, say what context is missing
