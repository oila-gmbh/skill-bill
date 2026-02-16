---
name: source-of-truth-sync
description: Define and enforce a single source of truth for synced data across local storage, remote APIs, and UI flows. Use for sync engines, workers, cache invalidation, and conflict-prone data refresh logic.
---

# Source of Truth Sync Guard

Use this skill when data can come from multiple sources (local DB, API, cache, background workers) and behavior is drifting.

## Core rules
- Pick one authoritative source per entity and document it in code.
- Never mix authority silently (e.g., fallback chains that change behavior unexpectedly).
- Encode empty-remote semantics explicitly: either **clear local** or **preserve local**; do not leave implicit.
- Treat sync stages as contracts: fetch -> validate -> apply -> verify.

## Implementation checklist
1. Identify entities and assign authority (local, remote, derived).
2. Ensure write paths are transactional and FK-safe.
3. Make merge behavior deterministic and idempotent.
4. Log failures with entity identifiers and operation phase.
5. Return concrete outcomes (success/failed/retry), not ambiguous states.

## Testing checklist
- Remote returns empty list (clear vs preserve behavior asserted).
- Remote failure does not corrupt local authoritative data.
- Repeated sync runs are stable (idempotency).
- Worker failure propagates retry behavior.
- Integration test verifies DB state after sync.
