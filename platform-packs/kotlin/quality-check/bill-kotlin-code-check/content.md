---
name: bill-kotlin-code-check
description: Discover and run repository-owned Kotlin and Gradle quality checks across compiler, API, analysis, tests, dependencies, toolchains, and generated sources.
internal-for: bill-code-check
---

# Kotlin Quality Check

## Purpose

Run the repository's authoritative Kotlin workflow, fix root causes only in changed files or owned build configuration, and report blockers without weakening checks.

## Execution Steps

1. Establish files in scope from the requested work unit and `git diff --name-only`; record changed modules, source sets, generated code, and build logic.
2. Discover the build file, Gradle wrapper, and CI configuration in `settings.gradle.kts`, module `build.gradle.kts` files, convention plugins, `gradle/libs.versions.toml`, `gradlew`, and repository scripts, in that order, before falling back to a conventional Gradle entrypoint.
3. For feature-task validate and any collect-all pass, run the pack's quality-check entrypoint as the pack `validation_gate.collect_all_full_gate_command` exactly: `./gradlew check --continue` from the Gradle project root that owns the wrapper (this repository: `runtime-kotlin`). Do not substitute a rediscovered `./gradlew check` without `--continue`, and do not invent alternate full-suite entrypoints.
4. List Gradle tasks when ownership is unclear, then run focused compiler and API validation such as `./gradlew :module:compileKotlin`, `apiCheck`, `binaryCompatibilityCheck`, or configured equivalents while repairing.
5. Run configured formatting and static analysis such as `ktlintCheck`, `detekt`, `spotlessCheck`, and compiler warning gates as targeted repair tasks when they are part of the same pack gate.
6. Run focused tests such as `./gradlew :module:test`, then broader tasks only when required to clear a finding from the collect-all set.
7. Run configured dependency and security validation such as `dependencyCheckAnalyze`, dependency verification, version-catalog checks, or repository scanners when those failures appear in the collect-all output.
8. Validate Java and Kotlin toolchains, `jvmTarget` alignment, source-set targets, Gradle compatibility, and configured build matrices when those failures appear in the collect-all output.
9. Validate KSP, kapt, protobuf, OpenAPI, and other generated sources by running their generation and compilation tasks when those failures appear in the collect-all output.
10. Capture full output, retain the files in scope, and attribute each failure to scoped work, pre-existing state, environment, or maintainer-owned configuration.

## Fix Strategy

Use this priority-ordered fix ladder:

1. Repair structural, source-set, Gradle, compiler, toolchain, and generated-source failures.
2. Repair public API or binary compatibility regressions according to the intended contract.
3. Apply repository formatters and fix static-analysis findings at their source.
4. Repair behavioral test failures without weakening assertions or deleting coverage.
5. Resolve dependency or security failures through supported versions and verification metadata.

Never suppress a failure with annotations, baselines, disabled rules, or skipped tests.

### Repair Window

Run `./gradlew check --continue` once and read that output. Fix every finding in the same session. Do not invoke the full collect-all gate after each individual finding. Targeted compile, test, and module checks are allowed while repairing. Repair every finding at its root cause; re-run `./gradlew check --continue` once to confirm after the full set is repaired. When a cache-bypassing confirm is required, use the pack `validation_gate.cache_bypassing_collect_all_full_gate_command`.

Run the full suite when targeted checks cannot establish safety, including when build logic, shared APIs, toolchains, generated sources, dependencies, or cross-module behavior changed.

Report a blocker with the exact command, owned failure, attempted diagnosis, and required maintainer decision when credentials, unavailable infrastructure, conflicting compatibility requirements, or out-of-scope repository state prevents completion.
