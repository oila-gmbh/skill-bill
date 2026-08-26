# SKILL-211 subtask 1 — Replace preplanning digest with prose PhaseOutput

## Scope

Wire preplan → plan to `PhaseOutput` (`value`, optional `prompt`) on
`produced_outputs`. Keep the outer envelope. Drop the digest schema gate on
this edge. Adjust goal-sweep sidecar, heading refresh, and phase prompts so
they match the new handoff.

In scope:

- `skillbill.agent.model.PhaseOutput`
- Preplan `allOf` on the phase-output schema: required `value`, optional
  `prompt`, extra keys allowed
- Remove `preplanning_digest` from the planning-projections `oneOf` and bump
  that contract version
- `producedProjectionKindFor(preplan)` returns null; plan projection matrix
  delivers prose instead of `preplanningDigestDeclaration`
- Remove the plan → preplan `regenerate_preplan` consumer edge
- Keep `_goal_planning_shared_context` as a runtime-owned extra key
- Replace heading-set refresh with `value`/`prompt` hash comparison; stop
  resolving plan bodies from `selected_boundary_headings`
- Preplan/plan prompt directives and shape examples
- The five boundary tests in the parent spec; delete digest-rejection
  siblings that no longer name a real bug

## Acceptance Criteria

1. `PhaseOutput` exists with `value: String` and `prompt: String?` and is the
   agent-authored shape of preplan `produced_outputs`.
2. A completed preplan whose `produced_outputs` is only `{ "value": "…" }`
   advances to plan; the plan briefing contains that string.
3. Leftover digest keys plus `value` still complete; the producer gate does
   not re-enter preplan for digest-schema violations.
4. Blank or missing `value` on `status: completed` retries or blocks preplan.
5. Optional `prompt` appears in the plan briefing when present and is omitted
   cleanly when absent.
6. Goal sweep still round-trips `_goal_planning_shared_context` after a prose
   `value`.
7. `producedProjectionKindFor("preplan")` is null; plan does not parse
   `feature_task_runtime.preplanning_digest`; the `regenerate_preplan` consumer
   edge from plan is gone.
8. Stale shared-preplan refresh compares `value`/`prompt` hashes, not
   `selected_boundary_headings`.
9. Preplan and plan prompts recommend prose and do not require a gated digest
   JSON example.

## Non-Goals

- Rewiring plan’s `executable_plan` or any later phase onto `PhaseOutput`.
- Renaming `AgentPhaseOutput.output`.
- Auto-resolving boundary heading bodies from preplan prose.
- Telemetry proxy stats for this edge.

## Dependency Notes

- Depends on: none (only subtask).
- Unblocks: later skills that reuse `PhaseOutput` on other edges.

## Validation Strategy

- The five parent-spec boundary tests (criteria 2–5 and 7 / sidecar round-trip).
- Compile the affected runtime modules. Do not use a full repo-root `check` as
  the acceptance bar for this subtask.

## Next Path

Parent next path: the next shape-gated feature-task edge, starting with plan
→ implement when that skill is filed.
