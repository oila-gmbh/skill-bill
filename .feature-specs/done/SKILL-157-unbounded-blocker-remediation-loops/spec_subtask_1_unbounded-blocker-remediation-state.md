# SKILL-157 Subtask 1 - Unbounded Blocker Remediation State And Transitions

Parent spec: [.feature-specs/SKILL-157-unbounded-blocker-remediation-loops/spec.md](spec.md)
Issue key: SKILL-157

## Scope

Remove every iteration-count termination path from semantic Blocker review
remediation, widen the durable review-pass contract, and lock in the existing
uncapped audit transition.

In scope:

- Change the `review_fix` backward edge to have no finite `perEdgeCap`. Re-enter
  `implement_fix` whenever the runtime's unresolved-Blocker signal is true, and
  advance when it is false.
- Preserve `audit_gap` as uncapped and add regression coverage proving that a
  `gaps_found` verdict can re-enter implementation after iteration three.
- Replace `GOAL_SUBTASK_REVIEW_MAX_PASSES` and all two-pass settlement logic with
  an unbounded positive pass sequence. Preserve ordered reservation, completion,
  emission, disposition, and crash-resume invariants.
- Widen `goal-subtask-review-state-schema.yaml` pass counts, pass result arrays,
  and pass-number constraints. Bump the schema contract version and Kotlin
  constant together, loud-fail incompatible legacy state at parse seams, and use
  the existing in-band quarantine/regeneration path where the runtime owns it.
- Make each remediation review disposition the Blockers from the immediately
  preceding pass rather than assuming pass one is always the predecessor.
- Keep pass one on its selected review tier. Resolve every later pass to
  `bill-code-review mode:inline context:feature-remediation` against only the
  current round's remediation delta.
- Remove cap-exhaustion settlement and operator-retry accounting that exists only
  to buy another review iteration. Preserve explicit operator decisions for
  independent pause or non-convergence paths.
- Keep the transition function loop-generic and free of audit/review phase-name
  branching.

## Acceptance Criteria

1. `review_fix` declares no finite iteration cap and re-enters `implement_fix` at
   iterations 1, 2, 4, 10, and any later count while an unresolved Blocker is
   present.
2. The first review result with no Blocker advances normally, regardless of how
   many earlier remediation passes ran; Major, Minor, and Nit findings retain
   their current ledger and advancement behavior.
3. `audit_gap` remains uncapped and a `gaps_found` verdict re-enters implementation
   at iterations above three, while a satisfied audit advances to review.
4. Durable review state accepts arbitrary positive pass numbers and preserves
   contiguous ordered reservation, result, completion, and emission watermarks
   across serialization and resume.
5. Each remediation pass dispositions the Blocker identifiers from its immediately
   preceding completed pass, including newly introduced Blockers and passes above
   two.
6. Pass one retains its resolved tier; every pass from two onward runs inline and
   reviews only the remediation delta since that round's pre-fix checkpoint.
7. A crash during `implement_fix` or re-review resumes the same loop position and
   reserved pass without allocating another pass or double-applying a mutation.
8. The review-state schema version and Kotlin constant remain equal, incompatible
   old records fail with the typed schema error, and runtime-owned recovery
   quarantines/regenerates them in band where contracted.
9. Malformed-output correction and record-regeneration backward edges retain their
   existing finite caps and cap-exhaustion behavior.

## Non-Goals

- Adding the user-facing warning threshold; Subtask 2 owns it.
- Updating governed skill prose; Subtask 3 owns it.
- Changing Blocker or audit-gap classification.

## Dependency Notes

Depends on: none

This unit establishes the unbounded durable loop accounting that the warning
threshold reads in Subtask 2.

## Validation Strategy

Add transition-function tests at low and high iteration counts, review-state
schema round-trip and rejection tests, review-pass tier tests beyond pass two,
run-loop tests that clear Blockers after at least five remediation iterations,
and crash/resume regressions during both loop phases. Assert that all unrelated
bounded backward edges retain their current caps. Run
`(cd runtime-kotlin && ./gradlew check)`.

## Next Path

Subtask 2 - shared warning threshold and resume-safe user output.

## Spec Path

.feature-specs/SKILL-157-unbounded-blocker-remediation-loops/spec_subtask_1_unbounded-blocker-remediation-state.md
