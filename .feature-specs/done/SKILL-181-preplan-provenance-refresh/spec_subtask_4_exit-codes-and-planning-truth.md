# SKILL-181 · Subtask 4 — Distinct exit codes and truthful planning stops

## Scope

Fix the reporting defects observed alongside the provenance gate:

1. **Distinct process exit codes** for paused, blocked, and failed goal terminations. A durable
   operator pause must not exit as a failure (`1`). Document the codes where operators and harnesses
   already learn goal CLI behaviour (CLI help and/or adjacent runtime docs the project already uses
   for goal exits — do not invent a new doc tree).
2. **Remedy-naming stop messages.** Any surviving planning stop (genuine invalid provenance, or any
   remaining hard stop) must name the exact remedy command, e.g. `skill-bill goal replan <issue>
   --subtask <id> --include-shared-preplan` when that is the recovery path. Remove wording that
   claims planning "cannot be recovered" when a documented flag recovers it.
3. **Status / launch coherence.** The status projection's `planning_reason` must not claim planning
   is resumable when the launch path would refuse to resume for the same durable planning state.

Touch the goal CLI exit mapping, `GoalPlanningSweep.incompatibleProvenance` (and any successor stop
builders), and the status projection that emitted
`Saved plans will be reused; planning can resume at subtask 2` while launch blocked.

## Acceptance Criteria

1. A durable operator pause terminates the goal process with an exit code distinct from blocked and
   from failed; harnesses can distinguish pause from failure by exit code alone.
2. Blocked and failed terminations also use distinct documented exit codes.
3. Every surviving planning stop message names the exact remedy command string an operator can run.
4. After a valid-but-stale (or invalid) planning state that launch would refuse, `goal status` does
   not claim planning can resume at a subtask.
5. Documentation of the three exit codes is updated in the existing goal CLI / operator surface.
6. `./gradlew check` passes for the touched modules as part of this subtask's validation; full-repo
   `./gradlew check` is the parent gate.

## Non-Goals

- Changing pause/resume control semantics beyond exit classification.
- Replacing the validity/freshness or refresh behaviour from earlier subtasks.
- Broad CLI UX redesign.

## Dependencies

- Subtask 1 (stop taxonomy and when incompatible provenance still fires must be settled).

## Validation Strategy

- CLI or application test: operator pause path asserts the pause exit code, not `1`.
- Assert blocked vs failed fixtures emit different codes.
- Assert incompatible-provenance (or successor) message contains the exact replan remedy command.
- Status fixture whose launch path would refuse planning must not advertise resumable planning.
- Build and test the affected modules.

## Next Path

Parent acceptance: end-to-end idle mid-run parent-spec edit continues without operator replan;
`./gradlew check` at repo root.
