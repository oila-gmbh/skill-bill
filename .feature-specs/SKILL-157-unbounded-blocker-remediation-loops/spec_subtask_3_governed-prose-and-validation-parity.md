# SKILL-157 Subtask 3 - Governed Prose And Validation Parity

Parent spec: [.feature-specs/SKILL-157-unbounded-blocker-remediation-loops/spec.md](./spec.md)
Issue key: SKILL-157

## Scope

Align every governed feature-task surface with the runtime's unbounded Blocker
remediation and threshold-three warning policy, then run the full validation gate.

In scope:

- Update authored `content.md` for `bill-feature-task-runtime`,
  `bill-feature-task-prose`, `bill-feature-goal`, and
  `bill-feature-task-subtask-runner` wherever they state a one-remediation,
  two-pass, cap-reached, or pass-three prohibition.
- Update the provider-neutral native-agent source under
  `skills/bill-feature-task-prose/native-agents/` so prose-goal children continue
  to repair and re-review while Blockers remain.
- State the audit/review warning policy consistently: threshold `3`, warn when
  entering iteration `4`, continue remediation, and advance only after blocking
  items clear.
- Keep audit-before-review ordering, exact child-owned diff scope, review tier
  selection, compact parent summaries, location-bearing private evidence, and
  non-Blocker behavior unchanged.
- Add content/runtime parity tests that reject old phrases and bind the documented
  threshold and loop behavior to runtime constants/declarations.
- Run `./install.sh` after source changes, while leaving generated wrappers,
  pointers, provider-specific agents, and install staging uncommitted.

## Acceptance Criteria

1. No governed feature-task or goal source says review is limited to two passes,
   one remediation iteration, a single possible re-review, or that pass three
   must never start.
2. Runtime and prose guidance both require audit repair/re-audit and Blocker
   fix/re-review to continue without an iteration cap until blocking items clear,
   subject only to existing non-count-based failure or non-convergence paths.
3. Every affected surface states that crossing from iteration 3 to 4 prints a
   warning that remediation continues, and none describes the warning threshold
   as a cap.
4. Review pass one retains the selected mode, every later review remains inline
   and remediation-delta-scoped, and Major/Minor/Nit behavior is unchanged in all
   updated guidance.
5. Parity tests fail if the runtime warning threshold differs from documented
   value 3, if either semantic loop regains a finite cap, or if legacy two-pass
   wording reappears.
6. `./install.sh` refreshes local installed staging after governed source changes,
   and forbidden generated source files remain absent from version control.
7. `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`,
   `npx --yes agnix --strict .`, and `scripts/validate_agent_configs` pass.

## Non-Goals

- Rewriting unrelated feature-task phases or orchestration prose.
- Changing standalone review or verification skill loop policies.
- Committing generated install output.

## Dependency Notes

Depends on: 1, 2

This unit documents and locks in the runtime behavior after the transition/state
and warning implementations are stable.

## Validation Strategy

Search governed sources for the removed cap vocabulary, run focused content and
contract parity tests, render/validate affected skills, run `./install.sh`, verify
the worktree contains no forbidden generated source, and finish with all four
repository validation commands from the parent acceptance criteria.

## Next Path

Complete the SKILL-157 goal and prepare the PR handoff.

## Spec Path

.feature-specs/SKILL-157-unbounded-blocker-remediation-loops/spec_subtask_3_governed-prose-and-validation-parity.md
