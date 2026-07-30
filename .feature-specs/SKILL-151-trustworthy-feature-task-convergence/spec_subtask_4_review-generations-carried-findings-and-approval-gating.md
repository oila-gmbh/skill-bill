# SKILL-151 Subtask 4 - Review generations, carried findings, and approval gating

Parent spec: [.feature-specs/SKILL-151-trustworthy-feature-task-convergence/spec.md](./spec.md)
Issue key: SKILL-151

## Scope

Update goal-subtask review state, review generation repositories, finding and disposition settlement, invalidation, remediation loops, status projection, and prompt delivery. Create a new generation whenever re-evaluation is needed without clearing prior passes, findings, or dispositions. Carry unresolved findings across arbitrary file, checkpoint, delta, and commit changes. Require every prior or current Blocker to have a contract-authorized terminal disposition before approval.

## Acceptance Criteria

1. Review approval is impossible while any historical or current Blocker lacks a terminal governed disposition.
2. Repository-delta invalidation creates a new generation without deleting history or the unresolved-finding ledger.
3. Changed files, delta digest, checkpoint, or commit SHA never block review merely because they changed.
4. New generations re-evaluate current repository state while carried findings retain stable identity and disposition history.
5. A carried Blocker cannot disappear across repeated delta changes, retries, crashes, standalone resume, or goal-child resume.
6. Stale checkpoint identity alone neither resolves a finding nor produces schema-invalid handling.

## Non-Goals

- Automatically accepting findings because their source checkpoint is stale.
- Weakening review depth or remediation requirements.
- Adding or modifying tests or test infrastructure.

## Dependency Notes

Depends on: 1, 2

Review generations use the durable evidence model from subtask 1 and remediation obligations enforced by subtask 2.

## Validation Strategy

Run existing focused module checks and repository validation commands. Add or modify no tests or fixtures.

## Next Path

Proceed to complete checkpoint capture without path authorization.

## Spec Path

.feature-specs/SKILL-151-trustworthy-feature-task-convergence/spec_subtask_4_review-generations-carried-findings-and-approval-gating.md
