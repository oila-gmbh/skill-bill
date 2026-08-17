# SKILL-191 · Subtask 3 — Spec intent projection resolver

## Scope

Resolve the governed spec for a review scope and compile it into the bounded
`spec_intent_projection` stage 2 consumes.

**Resolution order**, first match wins:

1. An explicitly supplied spec path from the caller — the feature-task runtime always
   supplies one.
2. The decomposition manifest reachable from the review scope: when the delta belongs
   to a subtask, prefer that subtask's `spec_path` and carry the parent `spec.md` as
   surrounding context.
3. A single-match search of `.feature-specs/{ISSUE_KEY}-*/spec.md`, where the issue
   key is derived from the branch name. More than one match resolves to none.

**Extraction.** Parse intended outcome, acceptance criteria, constraints, non-goals,
and deferred items. Acceptance-criteria parsing reuses the existing format contract
that `FileSystemFeatureTaskRuntimeRunInvariantsSource` enforces — heading beginning
`## Acceptance Criteria`, one criterion per list item — rather than a second parser
that can disagree with it. Record the spec path and content digest as provenance.

**Bounding.** The projection carries its own declared budget and is rejected when it
exceeds it, in line with the handoff-projection rule that a phase receives a whole
validated projection or fails loudly. Do not truncate: a silently clipped constraints
section produces a confidently wrong adjudication.

**Degradation.** No spec is the common case for ad-hoc standalone review and is not
an error. Record `spec_context: none` with a reason drawn from a closed vocabulary —
`no_spec_found`, `ambiguous_match`, `not_applicable_scope` — and let the run proceed
with stage 2 skipped. An explicitly supplied spec path that cannot be read or parsed
loud-fails, because the caller asserted it exists.

Retire the `criteria_references` placeholder at
`ParallelReviewPreparationCompiler.kt:137`: populate it from the resolved projection,
or leave it empty with the recorded degradation reason.

## Acceptance Criteria

1. The resolver returns a `spec_intent_projection` carrying intended outcome, acceptance criteria, constraints, non-goals, deferred items, and provenance as spec path plus content digest.
2. An explicitly supplied spec path wins over manifest resolution, which wins over branch-derived search.
3. When the delta belongs to a decomposition subtask, the subtask's `spec_path` is the projection's primary source and the parent `spec.md` is carried as surrounding context.
4. Acceptance-criteria extraction uses the same format contract as `FileSystemFeatureTaskRuntimeRunInvariantsSource`, and a spec that reader accepts is accepted here.
5. A projection exceeding its declared budget is rejected with a typed error naming the projection; it is never truncated.
6. No resolvable spec records `spec_context: none` with a reason from the closed vocabulary and lets the run proceed.
7. More than one `.feature-specs/{ISSUE_KEY}-*/spec.md` match resolves to none with reason `ambiguous_match` rather than picking one.
8. An explicitly supplied spec path that is missing, unreadable, or unparseable loud-fails with a typed error.
9. `criteria_references` on the lane assignment and specialist launch is populated from the resolved projection, and the literal placeholder string is gone.

## Non-Goals

- Consuming the projection. Subtask 5 owns adjudication.
- Authoring, repairing, or validating spec content beyond extraction.
- Detecting a stale spec relative to the delta.
- Changing the acceptance-criteria format contract or its reader.

## Dependency Notes

Depends on subtask 1 for `spec_intent_projection`. Independent of subtask 2 and
parallel to subtask 4.

## Validation Strategy

- One test per resolution-order rung (criteria 2 and 3) asserting the selected source.
- One test that a spec accepted by the runtime invariants reader is accepted here,
  since a second parser drifting from the first is the realistic failure.
- One over-budget rejection test asserting the typed error, not truncation.
- One test per degradation reason that the run proceeds with stage 2 skipped.
- One loud-fail test for a supplied-but-unreadable path.

## Next Path

Subtask 5 — spec adjudication runner, once subtask 4 lands.
