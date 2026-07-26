# SKILL-36 Subtask 1: Kotlin Contract and Scaffold Parity

Status: Complete

Parent spec: [spec.md](spec.md)

## Scope

Complete the Kotlin implementation for the governed maintainer behavior that is still covered by Python modules and Python tests. Focus on shell-content-contract loading, scaffold/render/fill/upgrade behavior, manifest edits, loud-fail exceptions, and scaffold atomicity. The implementation should extend the existing `runtime-kotlin/runtime-core/skillbill/scaffold` and `runtime-kotlin/runtime-cli/ScaffoldCliCommands.kt` surfaces rather than adding bridge or fallback code.

## Acceptance Criteria

1. Remaining `skill_bill/` bootstrap and maintainer behavior for scaffold, shell-content-contract loading, render/fill, and upgrade has Kotlin API/CLI parity.
2. Python tests for scaffold, shell-content-contract, manifest edits, rendering, fill/upgrade behavior, loud-fail paths, and scaffold atomicity are migrated to Kotlin tests or shell/script integration tests that exercise Kotlin commands.
3. Governed contracts remain preserved: manifest-driven routing, shell contract version 1.1 lockstep, named loud-fail behavior for missing/wrong manifest/content/sections, and scaffold atomicity.
4. No new runtime Python bridge/fallback markers are introduced.

## Non-Goals

- Do not update `install.sh`, `uninstall.sh`, or packaged runtime shims in this subtask except where tests need stable command invocation helpers.
- Do not migrate repo-level scripts under `scripts/`; that is owned by subtask 3.
- Do not delete `skill_bill/` or `pyproject.toml`; deletion is owned by subtask 4.
- Do not change the shell contract version unless the parent spec is updated with an explicit migration requirement.

## Dependencies

None. This is the foundation subtask because later shell, script, and deletion work need Kotlin-owned governed contract behavior before Python modules can be removed.

## Validation Strategy

Run `bill-quality-check`. Add and run the most targeted repo-native Kotlin or shell tests for the migrated contract/scaffold behavior before the full quality check.

## Recommended Next Prompt

Run bill-feature-implement on .feature-specs/SKILL-36-retire-python-tooling/spec_subtask_1_kotlin-contract-parity.md.
