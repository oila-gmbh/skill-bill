# SKILL-146 Subtask 3: Feature-task phase receipts and consumer projection matrix

## Scope

Implement typed projectors for the pre-planning digest, executable plan, plan commitment, implementation receipt, audit clearance and repair request, review repair request, validation request/receipt, change receipt, boundary candidates, commit request/receipt, and PR request. Replace feature-task forward and remediation edges with bounded projections and keep receipts classified as claims or runtime gate attestations.

## Acceptance Criteria

1. Parent AC 6–8 define selective run facts, the bounded preplan digest, and the executable plan without narration.
2. Parent AC 9–12 define audit, review, review-fix, and audit-remediation inputs with exact checkpoints and forbidden histories excluded.
3. Parent AC 13–16 define validation, history, commit, and PR requests/receipts without forwarding prior reports or raw outputs.
4. Parent AC 17 selects the correct repeated-phase and remediation projection.
5. Parent AC 20 and 22 keep telemetry outside domain receipts and enforce each projection budget.
6. Parent AC 25 and 26 are proved by an exact allowed/forbidden projection matrix and aligned runtime fixtures, prompts, and goldens.
7. Add-on content is projected only to manifest-declared consumers.

## Non-Goals

- Changing phase policy, severity taxonomy, repair caps, or treating claims as proof.
- Persisting complete diffs or sending add-ons to undeclared consumers.
- Prose, feature verification, or delegated-review specialist delivery.

## Dependency Notes

Depends on Subtask 2. May run alongside Subtasks 4 and 5. Subtasks 6 and 7 depend on it.

## Validation Strategy

- Table-driven positive and negative tests for every forward/backward edge.
- Stable task/finding/gap id and criterion-reference tests.
- Per-phase checkpoint and add-on selection tests.
- Repair idempotency, audit non-progress, and review two-pass regressions.
- Prompt and persisted-launch goldens plus focused runtime end-to-end tests.

## Next Path

Proceed to Subtask 6 after Subtasks 3, 4, and 5 are complete.

