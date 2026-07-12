---
name: bill-kotlin-code-check
description: Discover and run repository-owned Kotlin and Gradle quality checks across compiler, API, analysis, tests, dependencies, toolchains, and generated sources.
internal-for: bill-code-check
---

# Kotlin Quality Check

## Purpose

Run the repository's authoritative Kotlin workflow, fix root causes only in changed files or owned build configuration, and report blockers without weakening checks.

## Execution Steps

1. Establish scope from the requested work unit and `git diff --name-only`; record changed modules, source sets, generated code, and build logic.
2. Discover build files, the Gradle wrapper, and CI configuration in `settings.gradle.kts`, module `build.gradle.kts` files, convention plugins, `gradle/libs.versions.toml`, `gradlew`, and repository scripts before falling back to `./gradlew check`.
3. List Gradle tasks when ownership is unclear, then invoke the pack's quality-check entrypoint using the discovered repository command.
4. Run focused compiler and API validation such as `./gradlew :module:compileKotlin`, `apiCheck`, `binaryCompatibilityCheck`, or configured equivalents.
5. Run configured formatting and static analysis such as `ktlintCheck`, `detekt`, `spotlessCheck`, and compiler warning gates.
6. Run focused tests such as `./gradlew :module:test`, then integration, contract, or broader `check` tasks required by the changed boundary.
7. Run configured dependency and security validation such as `dependencyCheckAnalyze`, dependency verification, version-catalog checks, or repository scanners.
8. Validate Java and Kotlin toolchains, `jvmTarget` alignment, source-set targets, Gradle compatibility, and configured build matrices.
9. Validate KSP, kapt, protobuf, OpenAPI, and other generated sources by running their generation and compilation tasks and checking freshness where the repository defines it.
10. Capture full output, retain the files in scope, and attribute each failure to scoped work, pre-existing state, environment, or maintainer-owned configuration.

## Fix Strategy

Use this priority-ordered fix ladder:

1. Repair structural, source-set, Gradle, compiler, toolchain, and generated-source failures.
2. Repair public API or binary compatibility regressions according to the intended contract.
3. Apply repository formatters and fix static-analysis findings at their source.
4. Fix behavioral test failures without weakening assertions or deleting coverage.
5. Resolve dependency or security failures through supported versions and verification metadata.

Never suppress a failure with annotations, baselines, disabled rules, or skipped tests. After each fix, re-run targeted checks. Run the full suite when targeted checks cannot establish safety, including when build logic, shared APIs, toolchains, generated sources, dependencies, or cross-module behavior changed.

Report a blocker with the exact command, owned failure, attempted diagnosis, and required maintainer decision when credentials, unavailable infrastructure, conflicting compatibility requirements, or out-of-scope repository state prevents completion.
