# SKILL-201 Subtask 5 — Verify against the recorded reproduction

## Intended Outcome

Re-running `mode:delegated` over the SKILL-190 branch range produces `Coverage: clean` with the same
finding set as review `rvw-20260820-052146-53de`, and no lane names a file the worker received.

## Scope

- End-to-end delegated review over `a1afecd5f..feat/SKILL-190-one-commit-per-subtask`.
- Compare coverage verdict and finding register against the recorded `rvw-20260820-052146-53de` run.
- Final validation pass across all repository validators.

## Acceptance Criteria

1. Re-running `mode:delegated` over the SKILL-190 branch range produces `Coverage: clean` with the same finding set, and no lane names a file the worker received.
2. `skill-bill validate` passes.
3. `(cd runtime-kotlin && ./gradlew check)` passes.
4. `npx --yes agnix --strict .` passes.
5. `scripts/validate_agent_configs` passes.

## Non-Goals

- Changing the SKILL-190 branch or its findings.
- Raising `max_lane_evidence_bytes` in `.skill-bill/config.yaml` to mask the defect.

## Dependency Notes

Depends on subtasks 1 through 4. This is the final verification subtask.

## Validation Strategy

Re-run `mode:delegated` over `a1afecd5f..feat/SKILL-190-one-commit-per-subtask` and compare against
the recorded `rvw-20260820-052146-53de` register: coverage clean, no lane naming a delivered file,
and the same findings. Run the full validator suite listed in acceptance criteria.

## Next Path

Goal complete when all subtasks are terminal.
