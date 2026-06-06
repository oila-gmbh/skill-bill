---
status: Complete
---

# SKILL-69 Subtask 4 - Review Noise Tuning and Validation Gate

Parent spec: [.feature-specs/SKILL-69-code-review-telemetry-health/spec.md](./spec.md)
Issue key: SKILL-69

## Scope

Make the narrow review-guidance adjustment justified by the telemetry analysis,
then run the full validation gate. This subtask owns prompt/rubric text changes,
final docs consistency, and end-to-end validation.

- Adjust review guidance so low-value `Minor` / `Medium` findings are
  de-emphasized unless tied to an explicit contract, user-visible bug,
  regression risk, quality gate failure, or persisted learning.
- Preserve strict `Blocker` / `Major` review obligations and high-severity
  detection.
- Ensure the new category taxonomy and review-health reporting are reflected in
  relevant code-review skill/source guidance without violating authored
  `content.md` boundaries.
- Verify that feature-implement large-feature risk recommendations from subtask
  3 are documented and do not change runtime behavior.
- Run and record the final maintainer validation commands.

## Acceptance Criteria

1. Code-review guidance explicitly narrows low-value `Minor` / `Medium` findings
   while preserving high-severity findings.
2. Guidance changes are made in authored skill sources or platform-pack content,
   not generated `SKILL.md` wrappers or generated support pointers.
3. Existing specialist review contracts still require actionable, evidence-based
   findings and do not suppress real correctness, security, persistence,
   lifecycle, testing, or accessibility defects.
4. Documentation and spec language are consistent with the implemented telemetry
   fields and category taxonomy.
5. Full validation passes:
   - `skill-bill validate`
   - `(cd runtime-kotlin && ./gradlew check)`
   - `npx --yes agnix --strict .`
   - `scripts/validate_agent_configs`

## Non-Goals

- Do not perform broad platform-specialist rewrites unrelated to the measured
  `Minor` / `Medium` noise cluster.
- Do not change telemetry contracts except for final integration fixes required
  by validation.
- Do not add new dashboards or external PostHog entities.

## Dependency Notes

Depends on subtasks 1, 2, and 3. Review guidance should refer to the final
taxonomy and measurement surfaces after they exist.

## Validation Strategy

- Source validation for changed skill `content.md` files.
- Existing review skill tests/validators.
- Full maintainer gate listed in AC5.
- Manual scan that no generated `SKILL.md`, support pointer, native-agent output,
  or install staging artifact was committed.

## Next Path

Close SKILL-69 after validation passes and the branch contains one commit per
subtask.

## Spec Path

.feature-specs/SKILL-69-code-review-telemetry-health/spec_subtask_4_review-noise-tuning-and-validation-gate.md
