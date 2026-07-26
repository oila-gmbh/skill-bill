---
issue_key: SKILL-27
feature_name: cutover-preparation
feature_size: MEDIUM
status: Complete
created: 2026-04-25
rollout_needed: false
branch: feat/SKILL-27-cutover-preparation
source_context:
  - .feature-specs/SKILL-27-kotlin-runtime-port/spec.md
  - docs/migrations/SKILL-27-kotlin-runtime-port.md
  - runtime-kotlin/agent/history.md
---

# SKILL-27 - Cutover Preparation

## Status

Complete.

## Goal

Prepare the Kotlin runtime for a later default-runtime switch without making
that switch in this stage. Python remains the production entrypoint and
fallback. Kotlin runtime surfaces that already own behavior should be recorded
accurately, and maintainers should have a concrete cutover checklist instead of
inventing the plan during Phase 9.

## Acceptance Criteria

1. Add this stage spec with accepted scope, non-goals, and status
   `In Progress`.
2. Add a maintainer-facing cutover checklist that names the current default
   runtime, the Kotlin opt-in/parity path, the rollback path, and the gates
   required before Kotlin can become default.
3. Update runtime architecture and surface contracts so scaffold and install
   are no longer documented as placeholder-only after the completed
   scaffold-loader-install adapter stage.
4. Keep launcher/default-runtime behavior reserved: do not switch `skill-bill`
   or `skill-bill-mcp` entrypoints to Kotlin in this stage.
5. Update SKILL-27 migration notes and runtime history with the cutover-prep
   status and next-step guidance.
6. Add or update tests that pin the runtime surface contract statuses and the
   documented cutover boundary.
7. Run `cd runtime-kotlin && ./gradlew check`.

## Non-Goals

- Kotlin default runtime cutover.
- Python runtime deletion.
- Packaging or release automation changes.
- Changing the stable CLI or MCP command/tool inventory.
- Widening Kotlin behavior beyond already ported runtime surfaces.

## Source Context

- `.feature-specs/SKILL-27-kotlin-runtime-port/spec.md` defines Phase 8 as
  cutover preparation and Phase 9 as final cutover.
- `docs/migrations/SKILL-27-kotlin-runtime-port.md` currently records Python
  as the production source of truth after Phase 4 and subsequent runtime-port
  stages.
- `runtime-kotlin/agent/history.md` records that workflow runtime and
  scaffold-loader-install behavior have already been ported into Kotlin while
  Python remains the broader oracle.
