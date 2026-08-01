# SKILL-153 Subtask 3: Runtime Integration and Conformance

## Scope

Integrate the structural-repair result into the existing phase-output validation boundary used by planning and feature-task execution. Persist accepted repair evidence with the accepted result, retain rejected payloads only in the existing local diagnostic path, and verify retry, recovery, resume, and handoff behavior.

## Acceptance Criteria

- Every phase-output path that currently uses `FeatureTaskRuntimePhaseOutputValidator` receives the same deterministic validation behavior before handoff or artifact projection.
- Accepted repaired output and its typed evidence are persisted atomically with the accepted attempt/result and transition; no downstream phase sees the original malformed payload.
- Rejected and ambiguous output follows the existing typed retry, recovery, quarantine, and resume policy. Raw rejected output is absent from normal status, telemetry, PR descriptions, and generated files.
- Existing provider-neutral runtime behavior is preserved; no Codex, Claude Code, or Cursor conditional is added to shared validation or workflow logic.
- Integration tests cover planner output, non-planning phases, standalone validation where applicable, process failure, retry, duplicate submission, crash/resume, and diagnostic redaction.
- The complete repository validation suite passes with `scripts/validate`.

## Non-Goals

- Redesigning the workflow skeleton, phase transitions, schemas, or agent harnesses.
- Adding a second runtime validation loop or allowing validators to publish artifacts or select transitions.
- Semantic output correction or exposure of raw agent output outside local diagnostics.

## Dependency Notes

Depends on Subtasks 1 and 2. Reuse existing attempt, artifact, diagnostic, and transition persistence protocols rather than introducing a parallel evidence store.

## Validation Strategy

Run focused integration tests, then the full `scripts/validate` suite. Inspect database assertions for atomicity and fencing and inspect normal-output assertions for the absence of raw rejected payloads.

## Next Path

After validation passes, prepare the feature's review and delivery artifacts from the completed implementation.

