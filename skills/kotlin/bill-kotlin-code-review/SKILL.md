---
name: bill-kotlin-code-review
description: Use when conducting a thorough Kotlin PR code review across Android, KMP, and backend/server projects. Detect project type conservatively, preserve Android/KMP review depth, and select the right specialist agents for the diff, including real test-value review when tests change. Produces a structured review with risk register and prioritized action items.
---

# Adaptive Kotlin PR Review

You are an experienced Kotlin architect conducting a code review.

Your first job is to classify the project safely so Android/KMP reviews stay as deep as before. This skill must expand coverage without weakening the existing Android/KMP review path.

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

## Project Classification

Inspect both the changed files and repo markers (`build.gradle*`, `settings.gradle*`, `gradle/libs.versions.toml`, `pom.xml`, `application.yml`, `application.conf`, source set layout, module names, imports).

Classify the review as one of:
- `Android`
- `KMP`
- `Backend/Server`
- `Generic Kotlin`
- `Mixed`

### Android/KMP Signals

- `com.android.application`, `com.android.library`, `androidx`, `androidMain`, `iosMain`, `commonMain`
- `kotlin("multiplatform")`, `org.jetbrains.kotlin.multiplatform`, `expect` / `actual`
- `AndroidManifest.xml`, `res/`, `R.string`, Activities, Fragments, `ViewModel`
- `@Composable`, `remember`, `LaunchedEffect`, `collectAsStateWithLifecycle()`

### Backend/Server Signals

- `io.ktor.server`, `routing {}`, `Application.module`
- `spring-boot`, `@RestController`, `@Controller`, `@Service`, `@Repository`, `@Transactional`
- Micronaut, Quarkus, http4k, Javalin, gRPC server code
- `application.yml`, `application.yaml`, `application.conf`
- SQL/ORM/data-access layers: Exposed, jOOQ, Hibernate/JPA, JDBC, R2DBC, Flyway, Liquibase
- Queues, schedulers, consumers, caches, metrics, tracing, server auth middleware

### Decision Rules

- If Android/KMP signals are strong, use the Android/KMP route. Do **not** replace that route with backend specialists.
- If backend/server signals clearly dominate and there are no meaningful Android/KMP signals in scope, use the backend/server route.
- If neither route is clear, use the generic Kotlin route.
- If both appear in one PR, classify it as `Mixed`, choose specialists based on the changed areas, and do not drop Android/KMP specialists for Android/KMP files.
- When uncertain, prefer the safer route that preserves Android/KMP review depth.

---

## Dynamic Agent Selection

### Step 1: Always spawn `bill-kotlin-code-review-architecture`

Architecture review is relevant for every non-trivial change.

### Step 2: Choose route baseline

- `Android` / `KMP`: preserve the existing mobile review behavior
- `Backend/Server`: baseline is `architecture` + `bill-kotlin-code-review-platform-correctness`
- `Generic Kotlin`: baseline is `architecture` + `bill-kotlin-code-review-platform-correctness`
- `Mixed`: baseline is `architecture` + `bill-kotlin-code-review-platform-correctness`, then add route-specific specialists for the touched areas

### Step 3: Analyze the diff and select additional agents

For `Mixed` classification, inspect each changed file or tightly related change cluster separately:
- If a file/change has Android/KMP signals, apply the Android/KMP Route triggers
- If a file/change has backend/server signals, apply the Backend/Server Route triggers
- If a file/change is only generic Kotlin infrastructure, apply the Generic Kotlin Route triggers
- A single PR may spawn specialists from multiple routes, but keep the total at 6 or fewer
- Preserve Android/KMP specialists for any Android/KMP files even when backend files are changed in the same PR

#### Android/KMP Route

Keep the current mobile triggers intact:

| Signal in the diff | Agent to spawn |
|---------------------|----------------|
| `@Composable` functions, UI state classes, Modifier chains, `remember`, `LaunchedEffect` | `bill-kmp-code-review-compose-check` |
| `launch`, `Flow`, `StateFlow`, `viewModelScope`, `LifecycleOwner`, `DispatcherProvider`, `suspend fun` | `bill-kotlin-code-review-platform-correctness` |
| Auth, tokens, keys, passwords, encryption, HTTP clients, interceptors, sensitive data | `bill-kotlin-code-review-security` |
| `LazyColumn`/`LazyRow`, animations, heavy computation, image loading, retry/polling, bulk DB ops | `bill-kotlin-code-review-performance` |
| Test files modified (`*Test.kt`), new test classes, mock setup changes, coverage-padding or tautological tests | `bill-kotlin-code-review-testing` |
| User-facing UI changes, `stringResource`, accessibility attributes, navigation, error states, localization files | `bill-kmp-code-review-ux-accessibility` |

#### Backend/Server Route

| Signal in the diff | Agent to spawn |
|---------------------|----------------|
| Routes/controllers, request/response DTOs, serializers, content negotiation, validation, status-code mapping, OpenAPI/schema changes | `bill-kotlin-code-review-backend-api-contracts` |
| Repositories/DAOs, SQL, ORM mappings, transactions, migrations, optimistic locking, upserts, bulk writes | `bill-kotlin-code-review-backend-persistence` |
| Timeouts, retries, circuit breakers, queues, schedulers, idempotency, caching, metrics, tracing, startup/shutdown lifecycle | `bill-kotlin-code-review-backend-reliability` |
| `launch`, `Flow`, `StateFlow`, `Mutex`, `Semaphore`, `suspend fun`, coroutine scopes, concurrent mutation | `bill-kotlin-code-review-platform-correctness` |
| Auth, tokens, keys, passwords, request signing, sensitive data, security middleware | `bill-kotlin-code-review-security` |
| Heavy request-path work, blocking I/O, N+1 queries, redundant downstream calls, unbounded buffering | `bill-kotlin-code-review-performance` |
| Test files modified (`*Test.kt`), contract/integration tests, mock setup changes, coverage-padding or tautological tests | `bill-kotlin-code-review-testing` |

#### Generic Kotlin Route

| Signal in the diff | Agent to spawn |
|---------------------|----------------|
| `launch`, `Flow`, `StateFlow`, `Mutex`, `Semaphore`, `suspend fun`, coroutine scopes | `bill-kotlin-code-review-platform-correctness` |
| Auth, tokens, keys, passwords, encryption, sensitive data | `bill-kotlin-code-review-security` |
| Heavy computation, retry/backoff loops, bulk data processing, redundant I/O | `bill-kotlin-code-review-performance` |
| Test files modified (`*Test.kt`), new test classes, mock setup changes, coverage-padding or tautological tests | `bill-kotlin-code-review-testing` |

### Step 4: Apply minimum

- Minimum 2 agents (architecture + at least one other)
- If no additional triggers match, spawn `bill-kotlin-code-review-platform-correctness` as the default second agent
- Maximum 6 agents
- On Android/KMP reviews, prefer the established mobile specialists before backend specialists

### Step 5: Launch in parallel

Spawn all selected agents simultaneously using the `task` tool. Each agent gets:
- The detected project type
- The list of changed files
- Instructions to read its own skill file for the review rubric
- The shared contract below

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

### 1. Classification & Agent Summary
```
Detected project type: Android | KMP | Backend/Server | Generic Kotlin | Mixed
Signals: @Composable, AndroidManifest.xml, ViewModel
Agents spawned: bill-kotlin-code-review-architecture, bill-kotlin-code-review-platform-correctness, bill-kmp-code-review-compose-check
Reason: Android/KMP signals were high-confidence, so the preserved mobile path was used
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

If invoked standalone, ask: **"Which item would you like me to fix?"**

If invoked from `bill-kotlin-feature-implement` or another orchestration skill, do not pause for user selection. Return prioritized findings so the caller can auto-fix P0/P1 items and decide whether to carry Minor items forward.

After all P0 and P1 items are resolved, run `bill-kotlin-quality-check` as final verification when the project uses Gradle and this review is being run standalone.

---

## Review Principles

- Changed code only: review what was added or modified in this PR — do not report issues in untouched code, even if it violates current rules
- Evidence-based: cite `file:line`
- Project-aware: each agent has project-specific rules in its skill file
- Actionable: every issue must have a concrete fix
- Proportional: don't nitpick style if architecture is broken
- No overoptimization: do not report negligible performance findings with no measurable user-facing or production-facing impact
- Honest: if unsure, say what context is missing
