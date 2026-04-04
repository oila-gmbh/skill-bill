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

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults. Pass relevant project-wide guidance and matching per-skill overrides to every delegated or inline specialist review pass.

## Setup

Determine the review scope:
- Specific files (list paths)
- Git commits (hashes/range)
- Staged changes (`git diff --cached`; index only)
- Unstaged changes (`git diff`; working tree only)
- Combined working tree (`git diff --cached` + `git diff`) only when the caller explicitly asks for all local changes
- Entire PR

Resolve the scope before reviewing. If the caller asks for staged changes, inspect only the staged diff and keep unstaged edits out of findings except for repo markers needed for classification.

---

## Kotlin-Family Classification

Inspect both the changed files and repo markers (`build.gradle*`, `settings.gradle*`, `gradle/libs.versions.toml`, `pom.xml`, `application.yml`, `application.conf`, source layout, module names, imports).

## Additional Resources

- For shared stack-routing signals and tie-breakers, see [stack-routing.md](stack-routing.md).
- For shared review-orchestration rules, see [review-orchestrator.md](review-orchestrator.md).
- For agent-specific delegated review execution, see [review-delegation.md](review-delegation.md).

When the caller already passed the detected stack, skip reading [stack-routing.md](stack-routing.md). For standalone invocation, read it before classifying.

Before selecting specialist review passes or formatting the final report, read [review-orchestrator.md](review-orchestrator.md) unless the caller already passed the shared review contract.

Before delegating specialist review passes, read only your current runtime's section in [review-delegation.md](review-delegation.md).

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

## Dynamic Specialist Selection

### Step 1: Always include `bill-kotlin-code-review-architecture`

Architecture review is relevant for every non-trivial change.

### Step 2: Choose route baseline

- `kotlin`: baseline is `architecture` + `bill-kotlin-code-review-platform-correctness`
- `kmp-baseline`: baseline is `architecture` + `bill-kotlin-code-review-platform-correctness`
- `backend-kotlin-baseline`: baseline is `architecture` + `bill-kotlin-code-review-platform-correctness`

### Step 3: Analyze the diff and select additional specialist reviews

| Signal in the diff | Specialist review to run |
|---------------------|--------------------------|
| `launch`, `Flow`, `StateFlow`, `viewModelScope`, `LifecycleOwner`, `DispatcherProvider`, `Mutex`, `Semaphore`, `suspend fun`, coroutine scopes, concurrent mutation | `bill-kotlin-code-review-platform-correctness` |
| Auth, tokens, keys, passwords, encryption, HTTP clients, interceptors, sensitive data | `bill-kotlin-code-review-security` |
| Heavy computation, blocking I/O, retry/polling loops, bulk data processing, redundant I/O | `bill-kotlin-code-review-performance` |
| Test files modified (`*Test.kt`), new test classes, mock setup changes, coverage-padding or tautological tests | `bill-kotlin-code-review-testing` |

### Step 4: Apply minimum

- Minimum 2 agents (architecture + at least one other)
- If no additional triggers match, include `bill-kotlin-code-review-platform-correctness` as the default second specialist review
- Maximum 5 agents
- Do not run KMP-only specialists or backend-only specialists from this skill; leave those to the platform-specific override that owns them

### Step 5: Choose execution mode

Select `inline` or `delegated` using [review-orchestrator.md](review-orchestrator.md).

- Use `inline` only when the Kotlin review scope stays small and low-risk under the shared execution-mode contract
- Use `delegated` when the diff is large, the risk profile is high, multiple layers are meaningfully involved, or the safest choice is unclear

### Step 6: Run selected specialist reviews

If execution mode is `inline`:
- run the selected specialist review passes sequentially in the current thread
- read each specialist skill file as the primary rubric for that pass
- apply the shared specialist contract in [review-orchestrator.md](review-orchestrator.md)
- keep findings attributed to each specialist before merging and deduplicating them for the final report

If execution mode is `delegated`:
- run one delegated subagent per selected specialist review pass
- pass the detected project type, list of changed files, applicable active learnings, instructions to read the specialist skill file, the parent thread's model when the runtime supports delegated-worker model inheritance, and the shared specialist contract in [review-orchestrator.md](review-orchestrator.md)
- if delegated review is required for this scope but the current runtime lacks a documented delegation path or cannot start the required subagent(s), stop and report that delegated review is required for this scope but unavailable on the current runtime

---

## Review Output Format

### 1. Classification & Specialist Summary
```text
Review session ID: <review-session-id>
Review run ID: <review-run-id>
Detected review scope: <staged changes / unstaged changes / working tree / commit range / PR diff / files>
Detected stack: kotlin | kmp-baseline | backend-kotlin-baseline
Signals: <markers>
Execution mode: inline | delegated
Applied learnings: none | <learning references>
Specialist reviews: bill-kotlin-code-review-architecture, bill-kotlin-code-review-platform-correctness
Reason: <why this Kotlin baseline route was selected>
```

For the shared risk register, action items, verdict format, merge rules, and review principles, follow [review-orchestrator.md](review-orchestrator.md).

## Implementation Mode Notes

- If invoked from `bill-feature-implement`, `bill-feature-verify`, `bill-kmp-code-review`, `bill-backend-kotlin-code-review`, or another orchestration skill, do not pause for user selection. Return prioritized findings so the caller can auto-fix P0/P1 items and decide whether to carry Minor items forward.
- After all P0 and P1 items are resolved, run `bill-quality-check` as final verification when the project uses a routed quality-check path and this review is being run standalone.
