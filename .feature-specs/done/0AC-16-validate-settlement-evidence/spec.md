# Issue 0AC-16: Evidence-backed validation settlement

## Intended Outcome

Prevent a feature-task subtask from being recorded as complete when the
validation strategy required by its spec was not executed successfully. Durable
validation evidence must identify the command that ran and its exit code, and
the goal status view must expose that evidence so a claimed completion can be
audited after the run.

## Acceptance Criteria

1. A validation settlement records every validation command used for the
   subtask, including its command identity and exit code.
2. A validate phase can settle as complete only when the recorded validation
   evidence contains a required successful result with exit code `0`; prose
   claiming that tests pass cannot substitute for evidence.
3. Missing, malformed, or non-zero validation evidence leaves the subtask
   incomplete and surfaces a typed, actionable failure instead of recording a
   green completion.
4. Goal status reports the recorded validation command and result for each
   completed subtask, and identifies completed records that lack valid evidence.
5. Regression coverage reproduces a red validation command at the subtask
   boundary and proves that the manifest cannot report that subtask as
   complete.
6. Existing valid phase-settlement and goal-resume records remain readable, or
   are rejected and regenerated through the repository's established
   versioned-contract path.
7. Focused runtime, persistence, CLI, and contract tests pass, and the
   dominant-stack quality check reports no new findings on touched modules.
8. The feature-spec manifest and executable subtask spec remain schema-valid
   and acceptance-criteria extractable by the goal runtime.

## Constraints

- Treat validation evidence as a runtime-owned contract, not an agent-authored
  assertion.
- Preserve loud failure for malformed envelopes, incompatible contract
  versions, and missing required output.
- Use the existing durable phase-settlement and goal-status seams; do not add a
  second validation ledger.
- Keep validation command execution and evidence collection in the existing
  quality-check/runtime gate path.
- Prefer regression tests at settlement, resume, and status boundaries over
  broad refactors.

## Non-Goals

- Changing which validation command a platform pack declares.
- Replacing the existing review or audit evidence contracts.
- Re-running validation automatically after a completed goal has already been
  finalized.
- Repairing unrelated historical manifests that do not participate in the
  affected resume or status path.

## Affected Areas

- `../../../runtime-kotlin/runtime-contracts` validation and phase-settlement contracts
  and wire vocabulary.
- `../../../runtime-kotlin/runtime-domain` settlement and validation evidence models.
- `../../../runtime-kotlin/runtime-engine` validation gate completion and subtask
  completion invariants.
- `../../../runtime-kotlin/runtime-infra-sqlite` durable settlement persistence.
- `../../../runtime-kotlin/runtime-cli` goal status projections and diagnostics.
- Focused tests across contract, engine, persistence, and CLI modules.

## Validation Strategy

- Run focused contract and phase-settlement tests for valid, missing,
  malformed, and non-zero validation evidence.
- Run runtime completion and resume tests proving failed validation cannot
  settle a subtask as complete.
- Run goal status tests proving command identity and exit status are visible
  after completion.
- Run the dominant-stack quality check for the touched modules.

## Delivery Plan

1. Define and validate the durable validation-evidence projection at the
   settlement boundary.
2. Require successful runtime-owned evidence before completion and expose the
   recorded result through goal status.
3. Add regression coverage for red, missing, malformed, and successful
   validation outcomes.
