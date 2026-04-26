# Adaptive Kotlin PR Review

You are an experienced Kotlin architect conducting a code review.

This skill owns the baseline Kotlin review layer. It covers shared Kotlin concerns for libraries, CLIs, shared utilities, and the common Kotlin layer that platform-specific review overrides build on top of.
---

## Kotlin-Family Classification

Inspect both the changed files and repo markers (`build.gradle*`, `settings.gradle*`, `gradle/libs.versions.toml`, `pom.xml`, `application.yml`, `application.conf`, source layout, module names, imports).

Classify the review as one of:
- `kotlin`
- `kmp-baseline`

### Additional Backend/Server Signals

- `io.ktor.server`, `routing {}`, `Application.module`
- `spring-boot`, `@RestController`, `@Controller`, `@Service`, `@Repository`, `@Transactional`
- Micronaut, Quarkus, http4k, Javalin, gRPC server code
- `application.yml`, `application.yaml`, `application.conf`
- SQL/ORM/data-access layers: Exposed, jOOQ, Hibernate/JPA, JDBC, R2DBC, Flyway, Liquibase
- Queues, schedulers, consumers, caches, metrics, tracing, server auth middleware

### Decision Rules

- If this skill is invoked from `bill-kmp-code-review`, accept Android/KMP scope and classify it as `kmp-baseline`. In that mode, review only shared Kotlin concerns and let `bill-kmp-code-review` add mobile-specific specialists.
- If strong Android/KMP markers are present and this skill is invoked standalone, clearly say that `bill-kmp-code-review` is required for full Android/KMP coverage. Continue only if the caller explicitly wants the baseline Kotlin layer.
- Backend/server markers stay on the `kotlin` route. Select backend-focused Kotlin specialists for API contracts, persistence, and reliability when backend/server signals are present.
- Otherwise use the `kotlin` route.

---

## Dynamic Specialist Selection

### Step 1: Always include `bill-kotlin-code-review-architecture`

Architecture review is relevant for every non-trivial change.

### Step 2: Choose route baseline

- `kotlin`: baseline is `architecture` + `bill-kotlin-code-review-platform-correctness`
- `kmp-baseline`: baseline is `architecture` + `bill-kotlin-code-review-platform-correctness`

### Step 3: Analyze the diff and select additional specialist reviews

| Signal in the diff                                                                                                                                                  | Specialist review to run                       |
|---------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------|
| `launch`, `Flow`, `StateFlow`, `viewModelScope`, `LifecycleOwner`, `DispatcherProvider`, `Mutex`, `Semaphore`, `suspend fun`, coroutine scopes, concurrent mutation | `bill-kotlin-code-review-platform-correctness` |
| Auth, tokens, keys, passwords, encryption, HTTP clients, interceptors, sensitive data                                                                               | `bill-kotlin-code-review-security`             |
| Heavy computation, blocking I/O, retry/polling loops, bulk data processing, redundant I/O                                                                           | `bill-kotlin-code-review-performance`          |
| Test files modified (`*Test.kt`), new test classes, mock setup changes, coverage-padding or tautological tests                                                      | `bill-kotlin-code-review-testing`              |
| Routes/controllers, request/response DTOs, serializers, content negotiation, validation, status-code mapping, OpenAPI/schema changes                                | `bill-kotlin-code-review-api-contracts`        |
| Repositories/DAOs, SQL, ORM mappings, transactions, migrations, optimistic locking, upserts, bulk writes                                                            | `bill-kotlin-code-review-persistence`          |
| Timeouts, retries, circuit breakers, queues, schedulers, idempotency, caching, metrics, tracing, startup/shutdown lifecycle                                         | `bill-kotlin-code-review-reliability`          |

### Step 4: Apply minimum

- Minimum 2 agents (architecture + at least one other)
- If no additional triggers match, include `bill-kotlin-code-review-platform-correctness` as the default second specialist review
- Maximum 8 agents so backend-heavy Kotlin diffs can include the restored server specialists without dropping shared Kotlin coverage
- Do not run KMP-only specialists from this skill; leave those to the platform-specific override that owns them

### Step 5: Scale review depth

- For small, low-risk Kotlin diffs, keep the review compact.
- For larger or higher-risk diffs, split the work into focused specialist passes so each lane can inspect the files most relevant to it.

### Step 5.5: Scope diff per specialist when review depth increases

When the review is split into focused specialist passes, build a per-specialist file list first:

1. Scan each changed file's name and imports for the routing-table signals from Step 3
2. Map each file to the specialists whose signals it matches
3. `bill-kotlin-code-review-architecture` always receives all changed files
4. Every other specialist receives only files matching its routing-table signals
5. If a non-architecture specialist's scoped file list is empty, drop it from the selected set
6. After scoping, re-check the minimum-2-specialist requirement; if only architecture remains, add `bill-kotlin-code-review-platform-correctness` with all changed files as the default second

This is a lightweight file-level classification (names + imports), not a full review.

### Step 6: Run selected specialist reviews

- Run each selected specialist lane against its scoped files.
- Read each specialist skill file as the primary rubric for that lane.
- Keep findings attributed to each specialist before merging and deduplicating them into the final review.

---

## Run Context

`Review session ID: <review-session-id>`
`Review run ID: <review-run-id>`
`Applied learnings: none | <learning references>`


## Scope Resolution

Resolve the scope before reviewing. If the caller asks for staged changes, inspect only the staged diff and keep unstaged edits out of findings except for repo markers needed for classification.

