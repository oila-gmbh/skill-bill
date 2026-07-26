# SKILL-27 Phase 4 - Surface Integration

## Status

Completed

## Context

This phase follows the completed persistence-core and review-domain Kotlin ports.
The Kotlin runtime already owns the local DB, review parsing/import, triage,
learnings, stats, and telemetry sync service layer. The remaining gap is the
externally visible surface that still lives only in Python.

## Acceptance Criteria

1. Replace the Kotlin marker-only CLI surface with a real `runtime-kotlin` CLI
   layer that maps existing stable review-domain commands onto the Phase 2/3
   Kotlin services without changing command names, argument shapes, or
   `text`/`json` output contracts.
2. Replace the Kotlin marker-only MCP surface with a real `runtime-kotlin` MCP
   layer for the already-ported review/learnings/stats/telemetry tools,
   preserving current tool names, request fields, response fields, and
   orchestrated child-payload semantics.
3. Keep the active workflow runtime, scaffolder/loader, and install paths
   Python-owned for this phase; only preserve their contract boundary where the
   CLI/MCP layers touch them.
4. Add Kotlin parity tests covering representative CLI outputs and MCP payloads
   for the Phase 4 surfaces.
5. Update the migration note so Phase 4 explicitly records which external
   surfaces are now Kotlin-backed and which remain Python-owned.

## Non-goals

1. Port `bill-feature-implement` or `bill-feature-verify` workflow runtime
   behavior.
2. Port governed loader, scaffolder, or install/upgrade behavior.
3. Rename commands, tools, arguments, or payload fields.
4. Widen into the full remaining CLI tree beyond the review/learnings/stats/
   telemetry slice unless the current contract forces it.

## Notes

- The earlier high-level migration spec labeled Phase 4 as workflow runtime,
  but the active carryover note was intentionally updated to make Phase 4 a
  surface-integration slice. This phase follows the carryover note as the
  operational source of truth for the next implementation step.
