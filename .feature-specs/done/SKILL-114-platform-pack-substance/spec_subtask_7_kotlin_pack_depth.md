# SKILL-114 Subtask 7 - Kotlin Pack Depth

## Scope

Make Kotlin a full standalone language pack, not only the non-UI baseline for
KMP. Deepen the existing eight areas and add Kotlin UI and UX/accessibility
specialists for Kotlin-owned desktop, server-rendered, terminal, and other
non-KMP surfaces. KMP retains more specific Compose Multiplatform/Android
overrides through composition.

Required depth includes type/nullability/generics/value/data/sealed semantics,
coroutines/Flow/cancellation/dispatchers/structured concurrency, JVM and native
interop where owned, Gradle/module/source-set architecture, serialization and
HTTP/service contracts, Spring/Ktor and persistence ecosystems when detected,
transactions/migrations/ORM sessions, input/deserialization/auth/path/process
security, allocation/collections/blocking/backpressure, tests and virtual time,
queues/retries/shutdown/telemetry, and Kotlin UI/accessibility frameworks with
clear KMP routing boundaries.

Deepen quality checking around wrapper-owned Gradle tasks, compiler and API
validation, formatting/static analysis, tests, dependency/security checks,
JVM/toolchain matrices, generated source, and targeted-to-full escalation.

## Acceptance Criteria

1. Kotlin declares all ten approved areas, with new manifest entries,
   `content.md` sources, pointers, metadata, native agents, render/install
   coverage, and tests for UI and UX/accessibility.
2. All ten Kotlin specialists meet the substance gate with concrete Kotlin,
   coroutine, Flow, serialization, Gradle, JVM/framework and toolchain failure
   modes.
3. Architecture, correctness, performance, reliability, security, API,
   persistence, and testing deepen cancellation/scope/dispatcher/Flow,
   nullability/type/interop, Gradle/module, transaction/session, serialization,
   service, blocking/resource, retry/shutdown and test-evidence behavior.
4. Kotlin UI and UX/accessibility cover applicable Compose Desktop, Swing/JavaFX
   interop, server-rendered Kotlin, and CLI/TUI surfaces while explicitly
   routing Android/KMP-specific ownership to KMP.
5. The Kotlin baseline routes every area and preserves deterministic merging
   when KMP later supplies more-specific UI/UX/platform-correctness lanes.
6. The quality checker covers discovered Gradle commands plus compiler/API,
   format/static analysis, tests, dependency/security, toolchain/target and
   generated-source validation.
7. Kotlin passes both duplication thresholds and its history records the
   standalone-coverage decision.
8. Kotlin pack tests, `skill-bill validate`, and relevant Gradle checks pass.

## Non-Goals

- No duplication of Android/KMP add-on guidance into Kotlin.
- No removal of the KMP composition edge.
- No assumption that all Kotlin repositories use Spring, Ktor, or Compose.

## Dependency Notes

Depends on subtask 1. Blocks subtask 8 because KMP composes this baseline.

## Validation Strategy

Run the maintained-pack audit for Kotlin, focused Kotlin composition/render/
install tests, `skill-bill validate`, and the relevant Gradle suite.

## Next Path

Proceed to subtask 8 after completion.
