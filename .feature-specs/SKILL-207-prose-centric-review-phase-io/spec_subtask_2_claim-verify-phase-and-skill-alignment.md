# SKILL-207 subtask 2 — Claim verification as phase call and skill alignment

## Scope

Drive claim verification from the review phase `output` string using the same
I/O envelope (`input` + `requestedAction` → `output`), and update governed
review skill content so prompts describe best-effort shape under Kotlin
governance — not a machine API that drops near-misses.

In scope:

- Claim verification invoked as: `input` = review `output`, `requestedAction`
  asks to verify each claim in that input; verifier `output` is prose (enrich
  later only if needed without replacing the string).
- Removal of dependence on regex-admitted finding lists as the *only* input to
  verification when review produced text.
- Updates to `skills/bill-code-review/content.md` and
  `skills/bill-code-review-inline/content.md` (and parent prompt copy in the
  runner if it still contradicts the philosophy) so shape guidance is
  best-effort and the authoritative result is the agent string.
- Tests at the verify seam: verification is launched from the string envelope.

Out of scope: other feature-task phases; parallel-lane merge redesign beyond
what is required so verify still runs.

## Acceptance Criteria

1. When the review pipeline runs claim verification, it passes the review
   phase `output` string as verification `input` and a `requestedAction` that
   instructs verifying claims in that input.
2. Claim verification no longer requires a non-empty regex-admitted finding
   list to attempt verification when review `output` contains claim text; the
   verifier agent works from the prose blob.
3. `bill-code-review` and `bill-code-review-inline` content state that register
   shape is best-effort, the phase result is the agent output string, and the
   runtime governs launch/evidence/persistence rather than policing format.
4. Parent inline/delegated prompt text in the review runner does not reintroduce
   a hard “prose-only is invalid / admit-or-drop” contract that contradicts
   criteria 1–3.
5. At least one automated test asserts verification is wired from the string
   envelope (request shape or launch prompt contains the review output / verify
   action), not solely from an admitted structured list.

## Non-Goals

- Perfect structured extraction of path/line inside Kotlin before verify.
- Rewriting merge/dedupe for parallel lanes into a full prose agent (only what
  verify needs).
- Installing or documenting the envelope for plan/implement/validate in this
  subtask.

## Dependency Notes

- Depends on: subtask 1 (review `output` must be the authoritative prose
  handoff).
- Unblocks: later skills that adopt the same envelope for other phases.

## Validation Strategy

- Seam/unit tests for verify launch from string envelope.
- Content review of the two skills against the parent acceptance criteria.
- After content changes, run `./install.sh` (or the repo’s governed install path)
  so staged skills match source.

## Next Path

Roll the same phase I/O envelope to the next feature-task phase that still
treats agent text as a brittle typed API (tracked under the parent Next Path).
