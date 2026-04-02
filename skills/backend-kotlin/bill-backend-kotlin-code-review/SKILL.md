---
name: bill-backend-kotlin-code-review
description: Use when conducting a thorough Kotlin backend/server PR code review. Preserve backend review depth by running bill-kotlin-code-review as the baseline Kotlin review layer, then add backend-specific specialists such as API contracts, persistence, and reliability. Produces a structured review with risk register and prioritized action items.
---

# Backend Kotlin PR Review

You are an experienced backend Kotlin architect conducting a code review.

Your job is to preserve backend/server review depth without duplicating the shared Kotlin review logic.

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-backend-kotlin-code-review` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

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

## Project Classification

Inspect both the changed files and repo markers (`build.gradle*`, `settings.gradle*`, `gradle/libs.versions.toml`, `pom.xml`, `application.yml`, `application.conf`, source layout, module names, imports).

## Additional Resources

- For shared stack-routing signals and tie-breakers, see [stack-routing.md](stack-routing.md).
- For shared review-orchestration rules, see [review-orchestrator.md](review-orchestrator.md).
- For agent-specific delegated review execution, see [review-delegation.md](review-delegation.md).

Before classifying, read [stack-routing.md](stack-routing.md). Use it as the source of truth for broad stack signals. This skill owns only the backend/server override that happens after Kotlin is already in scope.

Before selecting backend specialist review passes or formatting the final report, read [review-orchestrator.md](review-orchestrator.md). Use it as the source of truth for the shared specialist contract, merge rules, common output sections, shared standalone behavior, and review principles used by stack-specific review orchestrators.

Before delegating baseline or backend specialist review passes, read [review-delegation.md](review-delegation.md). Use it as the source of truth for agent-specific subagent execution.

Classify the review as one of:
- `backend-kotlin`
- `mixed-backend-kotlin`
- `not-backend-kotlin`

### Backend/Server Signals

- `io.ktor.server`, `routing {}`, `Application.module`
- `spring-boot`, `@RestController`, `@Controller`, `@Service`, `@Repository`, `@Transactional`
- Micronaut, Quarkus, http4k, Javalin, gRPC server code
- `application.yml`, `application.yaml`, `application.conf`
- SQL/ORM/data-access layers: Exposed, jOOQ, Hibernate/JPA, JDBC, R2DBC, Flyway, Liquibase
- Queues, schedulers, consumers, caches, metrics, tracing, server auth middleware

### Decision Rules

- If this skill is invoked from `bill-kmp-code-review`, accept mixed Android/KMP + backend scope and focus only on backend/server coverage while leaving KMP-only concerns to the caller.
- If backend/server signals are strong, keep the backend route.
- If backend/server signals are weak or absent, delegate to `bill-kotlin-code-review` and stop instead of pretending backend-specific coverage exists.
- If shared Kotlin infrastructure is touched alongside backend files, keep the backend route and let `bill-kotlin-code-review` handle shared Kotlin coverage while this skill adds backend specialists.

---

## Layered Review Plan

### Step 1: Choose execution mode

Select `inline` or `delegated` using [review-orchestrator.md](review-orchestrator.md).

- Use `inline` only when the backend review scope stays small and low-risk under the shared execution-mode contract
- Use `delegated` when the diff is large, backend-only specialist risk is present, multiple layers are meaningfully involved, or the safest choice is unclear

### Step 2: Run `bill-kotlin-code-review` as the baseline review

Run `bill-kotlin-code-review` against the same scope first. That skill owns:
- shared Kotlin architecture, correctness, security, performance, and testing review
- the baseline Kotlin findings that every backend/server review should inherit

When invoking it from this skill in either execution mode:
- tell it that backend/server scope is valid and should be treated as `backend-kotlin-baseline`
- tell it to keep backend-only review concerns out of scope
- pass the same diff source, changed files, and relevant override guidance

If execution mode is `inline`, apply `bill-kotlin-code-review` inline in the current thread.

If execution mode is `delegated`, run `bill-kotlin-code-review` as a delegated subagent and use the runtime-specific delegation contract from [review-delegation.md](review-delegation.md).

### Step 3: Analyze the diff and select backend-specific specialist reviews

| Signal in the diff | Specialist review to run |
|---------------------|--------------------------|
| Routes/controllers, request/response DTOs, serializers, content negotiation, validation, status-code mapping, OpenAPI/schema changes | `bill-backend-kotlin-code-review-api-contracts` |
| Repositories/DAOs, SQL, ORM mappings, transactions, migrations, optimistic locking, upserts, bulk writes | `bill-backend-kotlin-code-review-persistence` |
| Timeouts, retries, circuit breakers, queues, schedulers, idempotency, caching, metrics, tracing, startup/shutdown lifecycle | `bill-backend-kotlin-code-review-reliability` |

### Step 4: Run backend specialist reviews

If execution mode is `inline`:
- run the selected backend specialist review passes sequentially in the current thread
- read each backend specialist skill file as the primary rubric for that pass
- apply the shared specialist contract in [review-orchestrator.md](review-orchestrator.md)
- keep findings attributed to each layer before merging and deduplicating them for the final report

If execution mode is `delegated`:
- run one delegated subagent per selected backend specialist review pass
- pass the detected project type, list of changed files, instructions to read the backend specialist skill file, the parent thread's model when the runtime supports delegated-worker model inheritance, and the shared specialist contract in [review-orchestrator.md](review-orchestrator.md)
- if delegated review is required for this scope but the current runtime lacks a documented delegation path or cannot start the required subagent(s), stop and report that delegated review is required for this scope but unavailable on the current runtime

If no backend-only triggers match but backend/server signals are clearly present, keep the baseline Kotlin review output and state that no extra backend-specific specialist was needed for this scope.

---

## Review Output Format

### 1. Classification & Layer Summary
```text
Detected review scope: <staged changes / unstaged changes / working tree / commit range / PR diff / files>
Detected stack: backend-kotlin | mixed-backend-kotlin
Signals: application.yml, @RestController, Exposed
Execution mode: inline | delegated
Baseline review: bill-kotlin-code-review
Backend specialist reviews: bill-backend-kotlin-code-review-api-contracts
Reason: backend/server signals were high-confidence, so the backend layer was added on top of the baseline Kotlin review
```

For the shared risk register, action items, verdict format, merge rules, and review principles, follow [review-orchestrator.md](review-orchestrator.md).

## Implementation Mode Notes

- If invoked from `bill-feature-implement`, `bill-feature-verify`, `bill-kmp-code-review`, or another orchestration skill, do not pause for user selection. Return prioritized findings so the caller can auto-fix P0/P1 items and decide whether to carry Minor items forward.
- After all P0 and P1 items are resolved, run `bill-quality-check` as final verification when the project uses a routed quality-check path and this review is being run standalone.
