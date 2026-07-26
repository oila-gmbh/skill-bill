# SKILL-36 Subtask 4: Python Package Removal and Final Gate

Parent spec: [spec.md](spec.md)

## Scope

Remove the remaining Python package, Python packaging metadata, and stale Python test/tooling paths after subtasks 1-3 have moved behavior to Kotlin or non-Python scripts. This is the final cleanup and regression gate for the parent feature.

## Acceptance Criteria

1. No install, uninstall, validation, script, test, runtime, or maintainer path imports or executes `skill_bill`.
2. The `skill_bill/` package is deleted.
3. `pyproject.toml` is deleted if feasible; if a non-runtime tool still requires it, remove Python console-script packaging and document why the file remains.
4. Python tests that only covered removed Python implementation are deleted after equivalent Kotlin or shell/script coverage exists.
5. Docs, installer messages, validation commands, and packaging references no longer present Python as required tooling.
6. The full repo quality gate passes through `bill-quality-check`.
7. Governed contracts remain intact after deletion: manifest-driven routing, shell contract loud-fails, scaffold atomicity, install/link behavior, and telemetry/workflow behavior.

## Non-Goals

- Do not port substantial missing behavior in this subtask; if a Python dependency is still required, stop and send that gap back to the owning earlier subtask.
- Do not change shell contract version unless the parent spec is updated with an explicit migration requirement.
- Do not remove clearly historical documentation references unless they confuse current install/runtime behavior.

## Dependencies

Depends on subtasks 1, 2, and 3. This subtask should start only after Kotlin/non-Python replacements and migrated tests are already in place.

## Validation Strategy

Run `bill-quality-check` as the final gate. Also run a repository search before deletion completion to verify no current path still imports or executes `skill_bill`, `python3 -m skill_bill`, Python console scripts, or the retired Python scripts.

## Recommended Next Prompt

Run bill-feature-implement on .feature-specs/SKILL-36-retire-python-tooling/spec_subtask_4_python-package-removal.md.
