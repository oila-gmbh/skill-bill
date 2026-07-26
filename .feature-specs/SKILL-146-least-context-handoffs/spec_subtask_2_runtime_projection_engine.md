# SKILL-146 Subtask 2: Runtime projection engine, checkpoint authority, and resume selection

## Scope

Build the runtime-owned projection and launch assembly service. Validate private artifacts, select the exact producing iteration, project only declared fields, enforce budgets before launch, validate or refresh repository checkpoints by policy, persist the exact delivered projection, and use one assembly path for fresh launch, retry, crash recovery, backward edges, and resume. Remove complete upstream envelopes from prompt-facing briefing state.

## Acceptance Criteria

1. Parent AC 2–6 are enforced by the shared projector and launch assembly seam.
2. Parent AC 17 selects the latest valid exact iteration/checkpoint and isolates sibling subtasks.
3. Parent AC 22 rejects overflow or uses only a contract-declared lossless reference, without truncation or full-artifact fallback.
4. Parent AC 23 loud-fails incompatible briefing/workflow records with actionable guidance.
5. Parent AC 24–26 are covered through privacy-safe measurements, positive/negative prompt assertions, persistence mappings, fixtures, and runtime surface updates.
6. Fresh, resumed, retried, crash-recovered, and remediating launches assemble the same projection.

## Non-Goals

- Defining all phase receipt payloads or provider-specific review launches.
- Replacing repository evidence with claims.
- Adding agent-controlled retrieval or process-runner agent identity branches.

## Dependency Notes

Depends on Subtask 1. Subtasks 3, 4, and 5 consume this shared machinery.

## Validation Strategy

- Fresh/resume/retry/crash equivalence tests.
- Latest-iteration, sibling-isolation, stale-checkpoint reject/refresh, and backward-edge tests.
- Database round-trip and prompt snapshot tests.
- Oversize tests proving no truncation or full-source fallback.
- Focused domain, application, persistence, and runtime suites.

## Next Path

Subtasks 3, 4, and 5 may start after this subtask is committed.

