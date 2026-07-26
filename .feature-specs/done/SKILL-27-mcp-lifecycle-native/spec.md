---
issue_key: SKILL-27
feature_name: mcp-lifecycle-native
feature_size: MEDIUM
status: Complete
created: 2026-04-25
rollout_needed: false
branch: feat/SKILL-27-cutover-preparation
source_context:
  - docs/migrations/SKILL-27-cutover-checklist.md
  - docs/migrations/SKILL-27-kotlin-runtime-port.md
  - runtime-kotlin/agent/history.md
---

# SKILL-27 - MCP Lifecycle Native Kotlin

## Status

Complete.

## Goal

Port the remaining MCP telemetry lifecycle tools from the temporary Python
bridge into Kotlin services and SQLite persistence, while preserving the
existing MCP tool names and payload shapes.

## Acceptance Criteria

1. Handle `feature_implement_started` and `feature_implement_finished`
   natively in Kotlin.
2. Handle `quality_check_started` and `quality_check_finished` natively in
   Kotlin, including orchestrated child payload behavior.
3. Handle `feature_verify_started` and `feature_verify_finished` natively in
   Kotlin, including orchestrated child payload behavior.
4. Handle `pr_description_generated` natively in Kotlin, including
   orchestrated child payload behavior.
5. Remove the Python MCP bridge helper and Kotlin bridge dispatcher.
6. Add Kotlin tests for standalone persistence/outbox emission and
   orchestrated no-outbox behavior.
7. Update docs, migration notes, and runtime history.
8. Run the runtime and repo validation gates.

## Non-Goals

- Deleting the Python MCP server fallback.
- Retiring the Python CLI fallback.
- Changing MCP tool names or external payload fields.
