# SKILL-207 subtask 1 — Phase I/O envelope and review string handoff

## Scope

Introduce the shared agent-phase I/O types and rewire the review path so the
worker’s returned text is the authoritative phase `output`. Register admission
must not delete near-miss findings from that handoff.

In scope:

- Shared Kotlin types for phase input (`input`, `requestedAction`) and phase
  output (`output`), named to fit existing package conventions.
- `ParallelCodeReviewRunner` (and any thin CLI/driver mapping) populates and
  returns/persists review results through that envelope.
- Removal or bypass of “admit or drop” as the filter between worker stdout and
  the review result consumers see: near-miss `[F-XXX]` text remains in `output`.
- Tests that pin a near-miss line surviving into review `output`.

Out of scope for this subtask: claim-verification rewrite; governed skill
`content.md` rewrites (subtask 2).

## Acceptance Criteria

1. Shared phase I/O types exist with inbound `input: String` and
   `requestedAction: String`, and outbound `output: String`.
2. Standalone / runner review completion exposes the worker’s returned text as
   `output` (or an equivalent field on the shared type) without replacing
   substantive worker text with an empty stand-in solely because register lines
   failed admission.
3. A worker stdout containing a near-miss register line that
   `ParallelReviewFindingParser` would reject as `unmatched_candidate_line`
   still appears verbatim (or as part of the full stdout) in the phase `output`.
4. Typed launch plumbing (repo, agent, evidence broker, budgets) remains outside
   the phase I/O string fields and is not stuffed into `input` as a substitute
   for process setup.
5. Unit or seam tests cover criterion 3 with a concrete near-miss example (for
   example confidence replaced by area labels, or location without `:line`).

## Non-Goals

- Rewiring claim verification (subtask 2).
- Updating `bill-code-review` / `bill-code-review-inline` content.md (subtask 2).
- Deleting `ParallelReviewFindingParser` entirely if still useful for optional
  diagnostics — only stopping it from being the handoff filter.
- Migrating plan/implement/validate phases onto the envelope.

## Dependency Notes

- Depends on: none (first subtask).
- Unblocks: subtask 2 (verify + skills), which requires review `output` to be
  the prose blob.

## Validation Strategy

- Targeted tests under `runtime-application` / `runtime-domain` for the new
  types and the review handoff seam (near-miss survival).
- Compile the affected runtime modules; do not run unrelated full `check` as the
  acceptance bar for this subtask.

## Next Path

Subtask 2 consumes review `output` as claim-verification `input` and aligns
skill content with the prose-centric boundary.
