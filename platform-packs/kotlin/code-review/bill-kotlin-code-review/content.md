---
name: bill-kotlin-code-review
description: Use when conducting a thorough Kotlin PR code review across shared, backend/server, or generic Kotlin code, or when providing the baseline Kotlin review layer for Android/KMP reviews. Select shared Kotlin specialists for architecture, correctness, security, performance, and testing, and add backend-focused specialists for API contracts, persistence, and reliability when server signals are present. Produces a structured review with risk register and prioritized action items. Use when user mentions Kotlin review, review Kotlin PR, Kotlin code review, or asks to review .kt files.
internal-for: bill-code-review
---

# Adaptive Kotlin PR Review

Review Kotlin libraries, CLIs, backend services, and the shared Kotlin baseline used by manifest-declared KMP composition.

## Classification Rules

- If generated Review Composition instructions invoke this skill in manifest-declared `kmp-baseline` mode, preserve that route even when backend markers coexist; backend markers add Kotlin backend lanes without replacing KMP composition.
- If Android/KMP markers such as `androidMain`, `iosMain`, `AndroidManifest.xml`, or `expect`/`actual` appear during standalone routing, require the KMP pack for full coverage and use this pack only as an explicitly requested baseline.
- If Kotlin/JVM, Gradle, backend framework, or server configuration markers dominate without manifest-declared KMP composition, classify the diff as `kotlin`.
- Otherwise classify generic `.kt` libraries, CLIs, and utilities as `kotlin`.

Always keep the `architecture` and `platform-correctness` specialists as the baseline. Backend signals include Ktor or Spring entry points, server configuration, Exposed, jOOQ, Hibernate/JPA, JDBC, R2DBC, Flyway, Liquibase, queues, consumers, schedulers, caches, metrics, and tracing.

## Diff-Signal Routing Table

- Module ownership, dependency direction, DI scope, use-case ownership, Gradle boundaries, or coroutine-scope ownership -> `architecture` specialist.
- Hot paths, `runBlocking`, blocking JDBC, dispatcher use, eager collections, Flow buffering, N+1 access, or serialization-triggered lazy loads -> `performance` specialist.
- Coroutines, `CancellationException`, `SupervisorJob`, `Mutex`, `StateFlow`, `SharedFlow`, races, retries, or state invariants -> `platform-correctness` specialist.
- Authentication, authorization, tenant isolation, secrets, SSRF, command/path/SQL/template sinks, deserialization, or JWT verification -> `security` specialist.
- `*Test.kt`, `runTest`, test dispatchers, virtual time, Flow collection, persistence verification, or negative paths -> `testing` specialist.
- Request/response DTOs, validation, status/error mapping, serialization settings, enums, dates, nullability, defaults, or schema changes -> `api-contracts` specialist.
- Transactions, `newSuspendedTransaction`, repositories, ORM mappings, migrations, locking, tenant filters, or bulk writes -> `persistence` specialist.
- Timeouts, retries, consumers, queues, replay, outbox ordering, caching, telemetry, startup, or shutdown -> `reliability` specialist.

## Mixed Diffs

- Keep the baseline specialists for the whole review, add only area-relevant specialist lanes, and do not force every file through every specialist.
- Use lightweight file-level classification from paths, imports, framework markers, and the routing table to build each specialist scope.
- Give architecture all Kotlin-owned changed files; give other specialists only matching files and drop empty lanes.
- Exclude generated, vendored, build-output, generated serialization/protobuf, and non-stack (non-Kotlin) files from specialist scope and from dominance scoring.
- Re-check the two-specialist minimum after scoping; if only architecture remains, give all Kotlin-owned files to platform-correctness as the default second lane.
- Run more than eight selected lanes in deterministic waves while retaining every selected result.

## Finding Discipline

- Calibrate severity to concrete production or client impact using only the governed severity vocabulary.
- Verify each triggering precondition and reachable failure path before reporting a finding.
- Keep findings attributed to their specialist lane through collection and merge.
- Deduplicate overlapping findings without losing the strongest evidence, consequence, or ownership attribution.
