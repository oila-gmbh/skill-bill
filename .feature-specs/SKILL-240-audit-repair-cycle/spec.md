# Issue SKILL-240: Self-contained audit-and-repair cycle

## Intended Outcome

Replace repeated audit-to-implement context handoffs with one self-contained remediation cycle. The cycle audits the current repository, repairs every identified acceptance-criteria gap, re-audits the changed tree, and advances only from the final tree-based result.

## Acceptance Criteria

1. A remediation cycle audits the repository and records every unmet acceptance criterion before applying repairs.
2. The same cycle repairs the recorded gaps without handing the diagnosis to an unrelated agent session.
3. The cycle re-audits the repaired tree and settles as satisfied only when the final tree satisfies every acceptance criterion.
4. Repair claims cannot substitute for the final audit's repository evidence.
5. Pre-repair and post-repair repository checkpoints, audit results, and repair outcomes remain durable and attributable to the cycle.
6. A cycle that makes no measurable progress pauses or fails through the existing operator-action path instead of retrying indefinitely.
7. Review and validation start only after the final audit settles as satisfied, and audit-repair failures remain distinguishable from review and validation failures.
8. Existing audit-gap and valid completed workflow records remain readable through the established versioned-contract behavior.
9. Focused runtime, persistence, and status tests cover complete repair, unresolved gaps, no-progress, interruption, resume, and final re-audit behavior.
10. The touched modules pass the dominant-stack quality check.

## Constraints

- Preserve repository-owned evidence boundaries: the final audit must read the repaired tree rather than trust the repair receipt.
- Keep validation responsible for builds and tests; audit-repair may not turn validation claims into audit evidence.
- Preserve scoped ownership, checkpoint, review, and commit boundaries.
- Keep the cycle manifest-driven and independent of any particular platform pack.
- Make interrupted cycles resumable without discarding the pre-repair audit or reapplying completed repairs.

## Non-Goals

- Changing acceptance criteria extraction or feature-spec authoring.
- Replacing review, verify-findings, or validation phases.
- Removing operator controls for no-progress or repeated remediation.
- Automatically repairing unrelated worktree changes.

## Affected Areas

- Feature-task audit and audit-gap transition state.
- Audit and implement phase handoff models and durable workflow artifacts.
- Repository checkpoint and progress accounting.
- Goal status and telemetry projections.
- Focused runtime, persistence, contract, and CLI tests.

## Validation Strategy

- Test the cycle's pre-repair audit, repair, and post-repair audit settlement.
- Test unresolved gaps, unchanged repository fingerprints, interruption, and resume.
- Test durable evidence and status projections for both completed and paused cycles.
- Run the dominant-stack quality check for touched modules.

## Delivery Plan

1. Define the durable audit-repair cycle contract and transition state.
2. Execute repair from the audit's complete gap set and require a final tree-based re-audit.
3. Preserve no-progress, interruption, resume, status, telemetry, and compatibility behavior.
