---
name: bill-ios-code-check
description: Use when validating iOS changes with the shared quality-check contract.
internal-for: bill-code-check
---

# Quality-Check Content

## Purpose

Use when validating iOS changes with the shared quality-check contract.

## Execution Steps

1. Determine the files in scope for the current unit of work.
2. Discover build files, repository wrappers, and CI configuration before falling back to direct tool commands. Inspect `Makefile`, `Mintfile`, fastlane lanes, Tuist configuration, CI workflows, and other project-owned entrypoints.
3. Identify the pack's quality-check entrypoint and prefer the repository-owned command that CI uses.
4. Discover the Xcode workspace or project, shared schemes, build configuration, destinations, and CI-selected simulator settings. Never invent a scheme or destination.
5. For an Xcode-backed scope, run the repository entrypoint or an appropriate `xcodebuild build` or `xcodebuild test` command with the discovered workspace-or-project, scheme, configuration, and destination. For an SPM-only scope, run `swift build` and `swift test`.
6. Discover `.swiftlint.yml`, `.swiftformat`, and other SwiftFormat configuration before running configured `swiftlint` and `swiftformat --lint` checks.
7. Verify package resolution and generated state with repository-owned commands or applicable `swift package resolve`, `xcodebuild -resolvePackageDependencies`, and code-generation checks; do not rewrite lockfiles or generated output without an input change.
8. Exercise configured strict-concurrency diagnostics and warnings-as-errors through the selected build command, respecting the target's Swift language mode.
9. Run configured security or static-analysis paths such as `swiftlint analyze`, CodeQL, or repository scanners only when their configuration and required environment are present.
10. Capture scoped failures, assign ownership to the current work or pre-existing state, and fix only failures that belong to the current work unless the contract says otherwise. Report missing destinations, credentials, tools, and maintainer decisions as explicit blockers.

## Fix Strategy

- Use this priority-ordered fix ladder: build and configuration failures; compile and type errors; behavioral test failures; lint failures; formatting failures.
- Prefer root-cause fixes and never suppress a scoped failure with inline `swiftlint:disable`, an equivalent suppression, weakened configuration, or a TODO-based bypass.
- Re-run targeted checks after each fix category. Escalate to the full suite when targeted checks cannot establish safety.
- Keep changes aligned with the project's existing conventions and build tooling.
- Escalate missing schemes, genuine tool defects, and policy decisions to maintainers instead of guessing.
