---
name: bill-kmp-code-check
description: Discover and run repository-owned Kotlin Multiplatform and Compose Multiplatform checks across common, Android, Apple, desktop, web, resource, dependency, packaging, and release boundaries.
internal-for: bill-code-check
---

# KMP Quality Check

## Purpose

Run the repository's authoritative multiplatform build matrix, distinguish unavailable host toolchains from code failures, and repair only failures owned by the requested change.

## Execution Steps

1. Determine the files in scope from `git diff --name-only`, settings, version catalogs, convention plugins, source-set declarations, and CI matrices; record every configured KMP target and changed module.
2. Discover commands from the repository build file, Gradle wrapper, and CI configuration, in that order, before falling back to Gradle conventions; inspect `./gradlew tasks --all` and never invent a target task from a conventional name.
3. Run the pack's quality-check entrypoint with common metadata and compilation tasks plus the discovered compilation task for every affected JVM, Android, iOS, macOS, Linux, Windows, JavaScript, or Wasm target.
4. Run affected common and target tests. On Android, run discovered lint, unit-test, instrumentation-build, and manifest/resource checks; on available Apple hosts, run discovered Kotlin/Native compilation, link, test, framework, and export checks.
5. Run Compose Multiplatform resource generation, resource accessor compilation, duplicate-resource validation, and packaging tasks for every affected target.
6. Validate Kotlin, Compose, Android Gradle, serialization, native, and other plugin versions; verify dependency alignment and that common dependencies resolve for every consumer target.
7. Run release variants and discovered distribution, framework, XCFramework, desktop package, browser production, publication, and shrinker tasks when the diff can affect packaging.
8. Apply the generated `android-r8` add-on only when Android release shrinking signals are present; validate consumer rules, keep rules, mapping output, and shrunk release startup without substituting it for core checks.
9. If a configured native or Apple target cannot run on the current host, report the exact unavailable toolchain and unexecuted task as unsupported evidence. Do not report environmental absence as a source failure or silently treat it as success.
10. Capture every command and outcome, classifying failures as scoped, pre-existing, environmental, or maintainer-owned configuration.
11. Verify expect/actual declarations, compiler arguments, opt-ins, and generated sources through both common metadata and each consuming target compilation rather than accepting metadata compilation alone.
12. Exercise target-specific test binaries and runtime launch tasks when shared behavior depends on platform clocks, serialization, coroutines, files, networking, or native interop.
13. Re-run the smallest discovered failing task after each fix, expand to every consumer target, and preserve exact task, host, environment, exit status, and diagnostic evidence.

## Fix Strategy

Use a priority-ordered fix ladder: repair source-set and Gradle topology first, then compilation and resource generation, tests and analysis, dependency alignment, and finally packaging or shrinker failures. Never suppress failures by disabling targets, removing release tasks, suppressing diagnostics, or weakening tests. Re-run targeted checks after each repair and the affected target matrix after they pass. Run the full suite when targeted checks cannot establish safety, especially after shared build logic, common APIs, dependency alignment, resources, or publication behavior changes.

When the host cannot supply a required SDK, simulator, signing identity, browser, or native toolchain, return a blocker containing the discovered task, host constraint, affected target, and the command maintainers must run on a capable runner.
