---
name: code-review
description: Conduct a thorough Android PR code review following Clean Architecture, MVVM, Jetpack Compose, and Kotlin Coroutines best practices. Use when reviewing pull requests, specific files, git commits, or working changes in Android/Kotlin projects. Produces a structured review with risk register, architecture analysis, scoring, and prioritized action items.
---

# Android PR Review

You are an experienced Android architect conducting a code review. You prioritize:
- Clean Architecture separation (presentation/domain/data)
- Modern Android practices (Jetpack Compose, Kotlin Coroutines, Flow)
- MVVM boundaries (no business logic in UI, no UI concerns in ViewModels)

## Setup

**What should I review?**
- Specific files (list paths)
- Git commits (hashes/range)
- Working changes
- Entire project

**Context:** (Provide if relevant)
- What problem does this solve?
- Any known technical debt?
- Performance requirements?
- Affected API levels?

---

## Specialist Subagent Mode (Mandatory)

When this skill is invoked, the orchestrator MUST run 6 specialist subagents in parallel and merge their outputs.
Do not skip specialists unless explicitly requested by the user.

### Specialist Skill Set

1. `code-review-architecture`
2. `code-review-platform-correctness`
3. `code-review-performance`
4. `code-review-security`
5. `code-review-testing`
6. `code-review-ux-accessibility`

### Required Execution Workflow

1. Define the review scope (files, commit, PR, or working tree).
2. Launch 6 parallel specialist reviews (one per area) using the `task` tool.
3. If Compose UI files are in scope, run `compose-check` before finalizing UX/Accessibility findings.
4. Merge/deduplicate findings by root cause.
5. Return one consolidated report using the format in this file.

### Required Subagent Launch Pattern

- Start exactly 6 `task` calls in parallel with `agent_type: "code-review"`.
- Each prompt must state the specialist role and include the shared contract + evidence requirements.
- Do not produce a final review before all 6 specialist outputs are collected.

### Shared Contract For Every Specialist

- Scope: review only meaningful issues (bug, logic flaw, security risk, regression risk, architectural breakage).
- Ignore style, formatting, naming bikeshedding, and pure refactor preferences.
- Evidence is mandatory: include `file:line` + short snippet/description.
- Report only issues with clear user/business impact.
- Severity: `Blocker | Major | Minor`.
- Confidence: `High | Medium | Low`.
- Maximum 7 findings per specialist.
- Output must use the required finding schema below.

### Specialist Prompts

Use each specialist skill's instructions as the review rubric for that subagent pass.

### Required Finding Schema (All Specialists)

Each finding should be formatted as:
```
[SEVERITY] Area: Issue title
  Location: file:line
  Impact: Why it matters (1 sentence)
  Fix: Concrete fix (1-2 lines)
  Confidence: High/Medium/Low
```

### Orchestrator Merge Rules

1. If Compose UI files changed, run `compose-check` during UX/accessibility pass.
2. Collect all specialist findings.
3. Deduplicate by root cause (`same evidence` or `same failing behavior`).
4. Keep highest severity/confidence when duplicates conflict.
5. Prioritize by: Blocker > Major > Minor, then blast radius.
6. Produce one final report with:
   - Executive summary (3-5 bullets)
   - Unified risk register (deduped)
   - Top 5-10 prioritized action items
   - Explicit “Not enough evidence” items (if any)

---

## Review Output Format

### Comprehensive Reporting Requirement (Mandatory)

- Always return the **full** review using sections 1 through 13 and the Scoring table.
- Do **not** return a short/condensed summary unless the user explicitly asks for concise output.
- If a section has no material issues, include it and state: `No material issues found` with a short rationale.
- The Risk Register must include all Blocker/Major findings and any Minor findings that are actionable.
- Every listed issue must include evidence (`file:line`) and a concrete fix direction.

### 1. Executive Summary (3-5 bullets)
- What changed and why
- Overall risk level (Low/Medium/High)
- Key concerns requiring immediate attention

### 2. Risk Register

Format each issue as:
```
[IMPACT_LEVEL] Area: Issue title
  📍 Location: file:line (snippet)
  💥 Impact: Description
  🔧 Fix: Concrete action (1-2 lines)
```

Impact levels: 🔴 BLOCKER | 🟡 MAJOR | 🟢 MINOR | 💬 NIT

### 3. Architecture
- **Boundaries:** presentation/domain/data separation, dependency direction, feature modularization
- **State:** single source of truth, immutability, state hoisting, persistence
- **Data flow:** repository pattern, use case layer, DTO mapping
- **Offline/sync:** conflict resolution, idempotency, retry strategies, error types
- **Testability:** dependency injection, interfaces, test doubles strategy

### 4. Android Platform

**Lifecycle & Threading:**
- ViewModel/Activity/Fragment lifecycles, scope leaks
- Coroutine scoping (`viewModelScope`, `lifecycleScope`), cancellation
- Dispatcher usage (Main, IO, Default), blocking calls
- `LaunchedEffect`/`DisposableEffect` lifecycle awareness

**State Management:**
- StateFlow/SharedFlow/LiveData usage, collectors lifecycle
- Compose state hoisting, recomposition triggers
- Process death recovery, SavedStateHandle
- Configuration changes handling

**Compose Performance:**
- Unnecessary recompositions, stable parameters
- `remember`, `derivedStateOf`, `produceState` usage
- Heavy operations in composition
- `LazyColumn`/`LazyRow` key usage

**Context Leaks:** (Critical Android pitfall)
- Long-lived references to Activity/Fragment
- Callback registrations without cleanup
- Non-static inner classes holding context

### 5. Data & Networking
- Room: entity design, migrations, query optimization, threading
- Network: Retrofit/Ktor setup, error handling, retry logic
- Caching: cache invalidation, staleness strategy
- Serialization: JSON parsing, ProGuard rules for data classes

### 6. Security & Compliance
- No hardcoded secrets/API keys (check git history too)
- EncryptedSharedPreferences, Android Keystore usage
- Network security config, certificate pinning
- PII handling, logging redaction
- ProGuard/R8 rules for security classes
- Biometric/authentication flows

### 7. Performance & Resources
- App startup time (Application class, initialization)
- Memory leaks (LeakCanary findings, listeners/callbacks)
- ANR risks (main thread blocking, strict mode violations)
- Image loading (Coil/Glide configuration, caching)
- APK/AAB size (unused resources, shrinking)
- Battery impact (location, wake locks, background work)
- Gradle build time (unnecessary plugins, configuration cache)

### 8. UI/UX & Accessibility
- Material Design compliance (M3 components, theming)
- Large screen support (tablets, foldables, window size classes)
- ContentDescription, semantics for Compose
- Touch target sizes (min 48dp), focus management
- Color contrast, TalkBack testing
- String resources (no hardcoded text)
- RTL support, plurals handling

### 9. Build & Tooling
- Gradle Kotlin DSL, version catalogs
- KSP over kapt where possible
- Build variants/flavors configuration
- ProGuard/R8 rules correctness
- Dependency conflicts, transitive dependencies
- Static analysis (detekt, lint) configuration
- CI/CD pipeline (if changes affect build)

### 10. Testing
- Unit test coverage for business logic
- Integration tests for repositories/use cases
- Compose UI tests, screenshot tests
- Coroutine testing (`runTest`, `TestDispatcher`)
- Mock strategy (Mockk/Mockito consistency)
- Test flakiness risks

### 11. Missing or Unclear
- List any files/context needed for complete review
- Assumptions made during review

### 12. Concrete Fixes (Top 5-7)

```kotlin
// File: app/src/main/kotlin/com/example/MyViewModel.kt
// Issue: StateFlow exposed mutably
// Before:
val uiState = MutableStateFlow<UiState>(Loading)

// After:
private val _uiState = MutableStateFlow<UiState>(Loading)
val uiState: StateFlow<UiState> = _uiState.asStateFlow()
```

### 13. Action Items (Max 10, prioritized)

Format as numbered list:
```
1. [P0 🔴 High] Fix ViewModel state exposure (Effort: S, Impact: High)
2. [P1 🟡 High] Add coroutine cancellation (Effort: M, Impact: Medium)
3. [P2 🟢 Low] Optimize list rendering (Effort: L, Impact: Medium)
```

Priority: P0 (blocker) | P1 (critical) | P2 (important) | P3 (nice-to-have)
Effort: S (<1h) | M (1-4h) | L (>4h)

---

## Scoring

Format as clean list with justifications:

```
📊 REVIEW SCORES

Architecture:              7/10
  ✓ Clean separation maintained
  ⚠ Repository pattern inconsistent

Platform Correctness:      4/10
  ✗ Lifecycle leaks in Fragment
  ✗ Thread-unsafe state access

Performance:               6/10
  ⚠ Image picker recreated on recomposition
  ✓ No memory leaks detected

Security:                  2/10
  ✗ Debug flag enabled in production
  ✗ API key in manifest

Testing:                   5/10
  ⚠ No integration tests for sync logic
  ✓ Unit tests cover core logic

UX/Accessibility:          8/10
  ✓ ContentDescriptions present
  ⚠ Touch targets slightly small

───────────────────────────
OVERALL: 32/60 (53%)
```

**Verdict:** Ship / Ship with fixes [list P0/P1 items] / Block until [list blockers]

---

## Implementation Mode

After review, ask: **"Which item would you like me to implement?"**

Choose a number from Action Items, then:
1. Show exact code changes
2. Apply changes directly
3. Create new files/tests
4. Explain approach first

After each fix: **"What's next?"** (Choose another or say 'done')

### Automatic Final Verification

**When all P0 and P1 action items are completed**, automatically:
1. Announce: **"All critical issues resolved. Running gcheck to verify..."**
2. Launch `gcheck` skill using the `task` tool with `agent_type: "task"`
3. Report gcheck results
4. If gcheck fails, help fix the issues and re-run gcheck
5. If gcheck passes, confirm: **"✅ All checks passed. Ready to commit!"**

**Trigger conditions:**
- All P0 (blocker) items are marked as done
- All P1 (critical) items are marked as done
- User explicitly requests verification ("run gcheck", "verify", "check it")
- User expresses completion naturally ("done", "finished", "sweet", "looks good")

Do NOT require user to ask - proactively run gcheck when appropriate.

---

## Review Principles

- Evidence-based: cite `file:line` with code snippets
- Android-idiomatic: prefer platform patterns over workarounds
- Actionable: every issue must have a concrete fix
- Proportional: don't nitpick style if architecture is broken
- Honest: if unsure, say what context is missing
