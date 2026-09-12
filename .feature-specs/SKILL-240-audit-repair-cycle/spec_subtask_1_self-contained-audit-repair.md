# Subtask 1: Self-contained audit-and-repair execution

## Scope

Trace the current audit-gap loop from audit output through implement re-entry, repository checkpointing, persistence, resume, and downstream phase selection. Add one runtime-owned audit-repair cycle that retains the audit's complete gap diagnosis, repairs those gaps in the same agent session, re-reads the changed tree, and exposes the final result through the existing workflow and status boundaries.

## Acceptance Criteria

1. The cycle records a pre-repair audit result containing the complete unmet-criterion set and repair guidance.
2. The cycle applies repairs from that result in the same session and does not require a separate agent to reconstruct the diagnosis.
3. The cycle records a post-repair repository checkpoint and re-audits the tree before advancing.
4. The cycle advances only when the post-repair audit is satisfied; repair prose alone cannot settle it.
5. The cycle persists pre-repair and post-repair evidence and restores both correctly after interruption or resume.
6. Unresolved gaps and unchanged repository state use the existing no-progress pause or operator-action path and do not silently retry forever.
7. Review and validation receive only the final satisfied audit result, while blocked audit-repair state remains visible in goal status and telemetry.
8. Legacy audit-gap records remain readable or fail through the established typed contract and regeneration path.
9. Focused tests cover all acceptance criteria, including multi-gap repair, final re-audit failure, no-progress, interruption, resume, and status projection.
10. The touched modules pass the dominant-stack quality check.

## Non-Goals

- No changes to platform-pack commands or validation ownership.
- No replacement of review or validation phases.
- No automatic repair of paths outside the cycle's declared ownership.

## Dependency Notes

This subtask is self-contained and owns the cycle contract, runtime transition, durable evidence, resume behavior, and regression coverage.

## Validation Strategy

- Run focused runtime transition and phase-settlement tests.
- Run persistence, contract, resume, status, and telemetry projection tests.
- Run the dominant-stack quality check for touched modules.

## Next Path

After this subtask settles, the runtime should expose one auditable audit-and-repair cycle that can close all identified acceptance gaps before review and validation begin.
