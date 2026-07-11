---
name: bill-kotlin-code-check
description: Run ./gradlew check and systematically fix all issues without using suppressions. Use when running Gradle checks, fixing lint errors, formatting issues, test failures, or deprecation warnings in Gradle/Kotlin projects. Fixes issues properly at the root cause instead of suppressing them. Use when user mentions gradlew check, Kotlin lint, ktfmt, detekt, or fix Gradle warnings.
internal-for: bill-code-check
---

# Kotlin Quality Check

## Purpose

Run the repository's authoritative Kotlin quality workflow, fix root causes only in files in scope, and preserve project-owned tooling and conventions.

## Execution Steps

1. Determine files in scope with `git diff --name-only` against the relevant base or current work-unit boundary.
2. Discover the authoritative command from build files, Gradle wrappers, version catalogs, Makefile targets, and CI configuration before falling back to `./gradlew check`.
3. Inspect `build.gradle`, `build.gradle.kts`, `settings.gradle`, `settings.gradle.kts`, `gradle/libs.versions.toml`, `gradlew`, `Makefile`, and CI workflows for repository-owned quality entrypoints.
4. Inspect named tool configuration when present: `detekt.yml`, `.editorconfig`, ktlint configuration, and Spotless configuration.
5. Invoke the pack's quality-check entrypoint using the discovered repository command, or `./gradlew check` only when no authoritative entrypoint exists, while retaining the files in scope from step 1.
6. Capture complete output, categorize structural, formatting, lint, deprecation, logic, and test failures, and address only failures caused by files in scope.
7. Apply fixes in priority order and iterate until the relevant checks are clean.

## Fix Strategy

Use this priority-ordered fix ladder:

0. Structural issues: package/directory mismatch, declaration/file-name mismatch, source-set placement, or Gradle module ownership
1. Formatting and configured ktlint or Spotless failures
2. Detekt, compiler, and other lint errors
3. Deprecation warnings and API migrations
4. Null-safety, type, and logic failures
5. Test failures, fixing implementation or behavioral expectations rather than weakening assertions

Never suppress a failure with `@Suppress`, lint baselines, ignore configuration, placeholder comments, or disabled checks. Refactor or correct the root cause.

After each fix, re-run targeted checks first. Run the full suite when targeted checks cannot establish safety, when shared build configuration changed, or when the discovered project workflow requires it.

Ask the user only for unresolved architectural choices, breaking API trade-offs, unclear business behavior, security policy decisions, or materially different valid fixes that repository conventions do not settle.
