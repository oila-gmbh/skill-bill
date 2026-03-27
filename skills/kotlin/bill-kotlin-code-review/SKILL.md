---
name: bill-kotlin-code-review
description: Use when conducting a thorough Kotlin PR code review across shared or generic Kotlin code, or when providing the baseline Kotlin review layer for Android/KMP and backend/server reviews. Select shared Kotlin specialists for architecture, correctness, security, performance, and testing. Produces a structured review with risk register and prioritized action items.
---

# Adaptive Kotlin PR Review

You are an experienced Kotlin architect conducting a code review.

This skill owns the baseline Kotlin review layer. It covers shared Kotlin concerns for libraries, CLIs, shared utilities, and the common Kotlin layer that platform-specific review overrides build on top of.

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-kotlin-code-review` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults. Pass relevant project-wide guidance and matching per-skill overrides to all spawned sub-agents.

## Setup

Determine the review scope:
- Specific files (list paths)
- Git commits (hashes/range)
- Working changes (`git diff`)
- Entire PR

---

## Kotlin-Family Classification

Inspect both the changed files and repo markers (`build.gradle*`, `settings.gradle*`, `gradle/libs.versions.toml`, `pom.xml`, `application.yml`, `application.conf`, source layout, module names, imports).

Before classifying, read `orchestration/stack-routing/PLAYBOOK.md`. Use it as the source of truth for broad stack signals. This skill owns only the Kotlin-family baseline after a caller decides Kotlin is in scope.

Classify the review as one of:
- `kotlin`
- `kmp-baseline`
- `backend-kotlin-baseline`

### Additional Backend/Server Signals

- `io.ktor.server`, `routing {}`, `Application.module`
- `spring-boot`, `@RestController`, `@Controller`, `@Service`, `@Repository`, `@Transactional`
- Micronaut, Quarkus, http4k, Javalin, gRPC server code
- `application.yml`, `application.yaml`, `application.conf`
- SQL/ORM/data-access layers: Exposed, jOOQ, Hibernate/JPA, JDBC, R2DBC, Flyway, Liquibase
- Queues, schedulers, consumers, caches, metrics, tracing, server auth middleware

### Decision Rules

- If this skill is invoked from `bill-kmp-code-review`, accept Android/KMP scope and classify it as `kmp-baseline`. In that mode, review only shared Kotlin concerns and let `bill-kmp-code-review` add mobile-specific specialists.
- If this skill is invoked from `bill-backend-kotlin-code-review`, accept backend/server scope and classify it as `backend-kotlin-baseline`. In that mode, review only shared Kotlin concerns and let `bill-backend-kotlin-code-review` add backend-specific specialists.
- If strong Android/KMP markers are present and this skill is invoked standalone, clearly say that `bill-kmp-code-review` is required for full Android/KMP coverage. Continue only if the caller explicitly wants the baseline Kotlin layer.
- If backend/server signals clearly dominate and this skill is invoked standalone, delegate to `bill-backend-kotlin-code-review` and stop instead of pretending this baseline layer is the full backend review.
- Otherwise use the `kotlin` route.

---

## Dynamic Agent Selection

### Step 1: Always spawn `bill-kotlin-code-review-architecture`

Architecture review is relevant for every non-trivial change.

### Step 2: Choose route baseline

- `kotlin`: baseline is `architecture` + `bill-kotlin-code-review-platform-correctness`
- `kmp-baseline`: baseline is `architecture` + `bill-kotlin-code-review-platform-correctness`
- `backend-kotlin-baseline`: baseline is `architecture` + `bill-kotlin-code-review-platform-correctness`

### Step 3: Analyze the diff and select additional agents

| Signal in the diff | Agent to spawn |
|---------------------|----------------|
| `launch`, `Flow`, `StateFlow`, `viewModelScope`, `LifecycleOwner`, `DispatcherProvider`, `Mutex`, `Semaphore`, `suspend fun`, coroutine scopes, concurrent mutation | `bill-kotlin-code-review-platform-correctness` |
| Auth, tokens, keys, passwords, encryption, HTTP clients, interceptors, sensitive data | `bill-kotlin-code-review-security` |
| Heavy computation, blocking I/O, retry/polling loops, bulk data processing, redundant I/O | `bill-kotlin-code-review-performance` |
| Test files modified (`*Test.kt`), new test classes, mock setup changes, coverage-padding or tautological tests | `bill-kotlin-code-review-testing` |

### Step 4: Apply minimum

- Minimum 2 agents (architecture + at least one other)
- If no additional triggers match, spawn `bill-kotlin-code-review-platform-correctness` as the default second agent
- Maximum 5 agents
- Do not spawn KMP-only specialists or backend-only specialists from this skill; leave those to the platform-specific override that owns them

### Step 5: Launch in parallel

Spawn all selected agents simultaneously using the `task` tool. Each agent gets:
- the detected project type
- the list of changed files
- instructions to read its own skill file for the review rubric
- the shared contract below

---

## Shared Contract For Every Specialist

- Scope: review only the changes in the current PR/unit of work — do not flag pre-existing issues in unchanged code
- Review only meaningful issues (bug, logic flaw, security risk, regression risk, architectural breakage)
- Flag newly introduced deprecated components, APIs, or patterns when a supported alternative exists, or when deprecated usage is broad in scope and not explicitly justified
- Ignore style, formatting, naming bikeshedding, and pure refactor preferences
- Evidence is mandatory: include `file:line` + short description
- Severity: `Blocker | Major | Minor`
- Confidence: `High | Medium | Low`
- Maximum 7 findings per specialist
- Include a minimal, concrete fix for each finding

### Required Finding Schema

```text
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

### 1. Classification & Agent Summary
```text
Detected stack: kotlin | kmp-baseline | backend-kotlin-baseline
Signals: <markers>
Agents spawned: bill-kotlin-code-review-architecture, bill-kotlin-code-review-platform-correctness
Reason: <why this Kotlin baseline route was selected>
```

### 2. Risk Register

Format each issue as:
```text
[IMPACT_LEVEL] Area: Issue title
  Location: file:line
  Impact: Description
  Fix: Concrete action
```

Impact levels: BLOCKER | MAJOR | MINOR

### 3. Action Items (Max 10, prioritized)

```text
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

If invoked standalone, ask: **"Which item would you like me to fix?"**

If invoked from `bill-feature-implement`, `bill-feature-verify`, `bill-kmp-code-review`, `bill-backend-kotlin-code-review`, or another orchestration skill, do not pause for user selection. Return prioritized findings so the caller can auto-fix P0/P1 items and decide whether to carry Minor items forward.

After all P0 and P1 items are resolved, run `bill-quality-check` as final verification when the project uses a routed quality-check path and this review is being run standalone.

---

## Review Principles

- Changed code only: review what was added or modified in this PR — do not report issues in untouched code, even if it violates current rules
- Evidence-based: cite `file:line`
- Project-aware: each agent has project-specific rules in its skill file
- Actionable: every issue must have a concrete fix
- Proportional: don't nitpick style if architecture is broken
- No overoptimization: do not report negligible performance findings with no measurable user-facing or production-facing impact
- Honest: if unsure, say what context is missing
