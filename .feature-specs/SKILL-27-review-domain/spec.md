# SKILL-27 review-domain

## Status

Completed

## Sources

- `docs/migrations/SKILL-27-kotlin-runtime-port.md`
- `.feature-specs/SKILL-27-kotlin-runtime-port/spec.md`
- Phase 3 assessment confirmed in chat

## Acceptance Criteria

1. Port the Phase 3 review-domain behavior from `skill_bill/review.py`, `triage.py`, `learnings.py`, `stats.py`, and `sync.py` into `runtime-kotlin/`.
2. Preserve current local contracts for review import/parsing, feedback recording, learnings resolution/session caching, local stats aggregation, and telemetry sync/remote-stats client behavior.
3. Add Kotlin parity tests for representative review import, triage/feedback, learnings resolution, stats payloads, and telemetry sync behaviors before touching CLI or MCP layers.
4. Update the migration note and source-of-truth statement so Phase 3 is explicitly documented on exit.

## Non-goals

- Porting CLI command behavior.
- Porting MCP tool behavior.
- Porting workflow runtime behavior.
- Porting scaffold, governed loader, or install behavior.

## Consolidated Spec Content

`SKILL-27` remains a phased Kotlin runtime migration where Python is the source of
truth until each subsystem is explicitly ported with parity evidence.

Phase 1 established the standalone JVM module and shared build logic.
Phase 2 established the Kotlin SQLite persistence foundation, including schema
bootstrap, additive migrations, telemetry outbox rows, and workflow-state row
stores.

Phase 3 moves the next dependency layer above persistence:

1. review import and parsing helpers from `skill_bill/review.py`
2. triage parsing and feedback recording behavior from `skill_bill/triage.py`
3. learnings CRUD, resolution ordering, and session-learnings caching from
   `skill_bill/learnings.py`
4. local review and workflow stats aggregation plus review-finished telemetry
   payload shaping from `skill_bill/stats.py`
5. telemetry config resolution, proxy capabilities/stats requests, and outbox
   sync behavior from `skill_bill/config.py` and `skill_bill/sync.py`

Frozen contracts for this phase:

- preserve review import requirements for review run id, review session id,
  summary fields, and bullet/table finding parsing behavior
- preserve triage normalization and feedback outcome semantics
- preserve learnings scope validation, precedence ordering, source validation,
  payload shape, and session-learnings cache semantics
- preserve local stats payload semantics for representative review and workflow
  summaries used by later CLI and MCP layers
- preserve telemetry config precedence, proxy capability defaults, remote stats
  request/response shaping, and sync outcome semantics

Relevant Python source-of-truth surfaces for this phase:

- `skill_bill/constants.py`
- `skill_bill/config.py`
- `skill_bill/review.py`
- `skill_bill/triage.py`
- `skill_bill/learnings.py`
- `skill_bill/stats.py`
- `skill_bill/sync.py`
- representative tests in `tests/test_review_metrics.py`,
  `tests/test_remote_telemetry_stats.py`, and
  `tests/test_telemetry_network_isolation.py`

Do not start CLI, MCP, workflow-runtime, or scaffold/loader work before the
review-domain services and tests are in place.
