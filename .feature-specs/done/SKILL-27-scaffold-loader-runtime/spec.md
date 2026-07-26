---
issue_key: SKILL-27
feature_name: scaffold-loader-runtime
feature_size: LARGE
status: In Progress
created: 2026-04-25
rollout_needed: false
workflow_id: wfl-20260425-094018-u5is
session_id: fis-20260425-094015-pdii
branch: feat/SKILL-27-scaffold-loader-runtime
source_context:
  - .feature-specs/SKILL-27-kotlin-runtime-port/spec.md
  - docs/migrations/SKILL-27-kotlin-runtime-port.md
  - runtime-kotlin/agent/history.md
---

# SKILL-27 - Scaffold, Loader, And Install Runtime Port

## Status

In Progress.

## Goal

Port the remaining governed scaffold, loader, and install primitives needed by
the current runtime surface into `runtime-kotlin/`, preserving the Python
runtime as the production entrypoint and behavioral oracle until a later
cutover phase.

This stage starts after the completed workflow-runtime phase and targets the
reserved scaffold, loader, and install surfaces before Kotlin runtime cutover.

## Acceptance Criteria

1. Add `.feature-specs/SKILL-27-scaffold-loader-runtime/spec.md` with this stage's accepted scope and status `In Progress`.
2. Port governed loader behavior from Python to Kotlin: manifest discovery, shell contract 1.1 enforcement, required section validation, governed add-on discovery shape where relevant, and named loud-fail errors.
3. Port scaffold planning/write/rollback behavior for governed skill creation and mutation, preserving payload validation, manifest mutation semantics, symlink behavior, and rollback guarantees.
4. Port install primitives needed by the current CLI surface without switching production entrypoints or deleting Python fallback behavior.
5. Preserve current payloads, CLI command shapes, MCP `new_skill_scaffold` behavior, and the zero silent fallback policy.
6. Add parity tests for loader failures, scaffold success/rejection/rollback, manifest writes, symlinks, and CLI/MCP wiring as implemented.
7. Update `runtime-kotlin/ARCHITECTURE.md`, `docs/migrations/SKILL-27-kotlin-runtime-port.md`, `runtime-kotlin/agent/history.md`, and any relevant catalog/stage docs.

## Non-Goals

- Kotlin default runtime cutover.
- Python runtime deletion.
- Shell contract version bump.
- Redesigning governed skill or platform-pack layout.
- Launcher fallback strategy or release packaging changes.

## Source Context

- `.feature-specs/SKILL-27-kotlin-runtime-port/spec.md` is the umbrella migration spec.
- `docs/migrations/SKILL-27-kotlin-runtime-port.md` is the active carryover note.
- `runtime-kotlin/agent/history.md` includes the latest runtime history, including `workflow-runtime-phase-5`, module split, model ownership, and reserved scaffold/install/launcher surfaces.
- The previous workflow runtime phase is complete on `main`; this stage should target the remaining reserved scaffold, loader, and install surfaces before cutover.
