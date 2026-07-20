# SKILL-134 Subtask 1: Rejected Output Diagnostics

## Scope

Create the dedicated local persistence, typed service boundary, privacy protections, and CLI retrieval lifecycle for exact rejected phase responses.

## Acceptance Criteria

1. Each rejected workflow phase attempt stores one exact raw response with workflow, phase, attempt, rule, path, reason, agent/model, timestamp, byte size, digest, and stable identity before retry or block.
2. Retry, resume, and terminal reconciliation deduplicate the same attempt while preserving distinct rejected attempts.
3. Storage permissions, payload ceiling, retention, corruption handling, and cleanup behavior are explicit, configurable, deterministic, and covered by typed errors.
4. Raw bodies never enter workflow artifact JSON, telemetry, default logs, goal/status/watch projections, PR output, generated skills, or install staging.
5. CLI inspection supports workflow plus phase/attempt selection, metadata-only output by default, and an explicit raw-output flag for the exact body.
6. Contract, persistence, service, CLI, privacy, crash/resume, and standalone/goal-child tests cover acceptance and rejection paths.

## Non-Goals

- Remote upload or automatic redaction of exact responses.
- Using diagnostic records as workflow continuation authority.
- Changing audit-remediation deferral semantics.

## Dependency Notes

No dependency. Subtask 2 may reuse its stable validation-rule and JSON-path diagnostic model.

## Validation Strategy

Run focused persistence, application, CLI, permissions, retention, deduplication, privacy, and integration tests.

## Next Path

Continue with subtask 2 to replace free-text deferral matching.
