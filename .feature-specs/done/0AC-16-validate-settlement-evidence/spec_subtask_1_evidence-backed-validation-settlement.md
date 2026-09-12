# Subtask 1: Evidence-backed validation settlement

## Scope

Trace the runtime-owned validate gate from command execution through phase
settlement, persistence, subtask completion, and goal status. Add one governed
validation-evidence projection that records the commands and exit codes used by
the gate. Enforce the projection at the completion boundary, persist it with
the existing settlement, and expose it in status output.

## Acceptance Criteria

1. A completed validate settlement contains a schema-valid list of validation
   command results with command identity and integer exit code.
2. The runtime accepts completion only when the required validation result is
   present and has exit code `0`; agent prose or an untrusted phase-output field
   cannot bypass this requirement.
3. Missing, malformed, and non-zero evidence produce a typed failure or blocked
   outcome and do not advance the subtask to complete.
4. Existing valid settlement records remain readable under the contract
   versioning rules, while unsupported legacy evidence is surfaced as an
   actionable incomplete validation rather than silently treated as passing.
5. Goal status shows each completed subtask's validation command and exit code,
   and marks absent or invalid evidence as a visible integrity problem.
6. Tests cover a passing command, a red command, missing evidence, malformed
   evidence, persistence round-trip, resume behavior, and status projection.
7. The touched modules pass the dominant-stack quality check.

## Non-Goals

- No changes to platform-pack validation commands or quality-check routing.
- No replacement of review, audit, build, or planning evidence contracts.
- No automatic re-execution of validation for already finalized subtasks.

## Dependency Notes

This subtask is self-contained and has no predecessor. Keep the change in one
reviewable commit because the contract, enforcement, persistence, status, and
regression coverage must land together for a complete boundary.

## Validation Strategy

- Run focused contract, phase-settlement, runtime-engine, SQLite, and CLI
  tests for the evidence lifecycle.
- Run the dominant-stack quality check for all touched modules.
- Inspect a serialized settlement and a goal status result to verify that the
  command identity and exit code survive the persistence boundary.

## Next Path

After this subtask is reviewed and validated, finalize the subtask commit and
advance the goal to its terminal state.
