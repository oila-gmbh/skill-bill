---
name: bill-kotlin-code-review-testing
description: Review Kotlin coroutine, Flow, framework, persistence, serialization, failure-path, generated-code, and toolchain test evidence.
internal-for: bill-code-review
---

# Testing Review Specialist

## Focus

- Behavioral evidence for concurrency, ordering, boundaries, compatibility, failures, and build variants

## Ignore

- Coverage-only demands for trivial accessors or generated mappings without behavior

## Applicability

Use for Kotlin unit, integration, contract, persistence, coroutine, and toolchain tests.

## Project-Specific Rules

### Testing Review Rules

- Require `runTest` with virtual-time control for delay, retry, and timeout behavior; real sleeps create slow flaky failure evidence.
- Verify `StandardTestDispatcher` queueing matches production ordering; accidental eager execution can hide a concurrency race.
- Reject `UnconfinedTestDispatcher` when thread confinement matters; immediate resumption can make an invalid lifecycle test pass.
- Require explicit cancellation assertions on child `Job` state; checking only a returned value can miss leaked coroutine work.
- Verify complete Flow sequences with Turbine `awaitItem` and `awaitComplete`; a single `first()` cannot detect dropped or reordered data.
- Require Spring, Ktor, or DI integration tests when interceptors and serialization boundaries change; unit mocks can miss framework contract failure.
- Reject persistence tests that assert only repository calls; verify commit, rollback, constraints, and durable rows through `Testcontainers` or an equivalent real boundary.
- Require serializer round trips for absent, null, default, enum, and time values; happy-path DTO tests can miss data compatibility regression.
- Verify authorization, validation, timeout, duplicate-delivery, and permanent-failure paths separately; one broad exception assertion can hide unsafe behavior.
- Reject tautological `assertEquals(stub, subject())` tests whose setup dictates the result; they provide no regression detection.
- Require generated KSP or kapt outputs to be compiled in the tested task; stale fixtures can hide generated-source build failure.
- Verify supported Java and Kotlin toolchains through Gradle matrix evidence such as `test` plus `apiCheck`; one local JDK can miss compiler or binary failure.
- Require `runTest` cleanup assertions to verify no child coroutine remains active; leaked jobs create lifecycle and concurrency failure.
- Reject unordered `toSet()` assertions when Flow order is contractual; lost sequencing evidence can hide a data regression.
- Verify `compileTestKotlin` on every supported source set; omitted targets can preserve an invalid build and compiler failure.
- Require `dependencyCheckAnalyze` fixtures or controlled scanner evidence for policy changes; untested security configuration risks exposure regression.
- For Blocker or Major findings, describe the concrete undetected-regression or false-positive test scenario.
