# SKILL-164 · Subtask 3: Audit and review projection delivery

## Scope

Deliver the shared evidence to both `audit` and `review` through the existing closed-world
projection mechanism, as a bounded reference rather than inlined bytes, and retire the
"read the diff yourself" derived-context instructions that the artifact now backs.

In scope:

- A new projection contract carrying the evidence **reference**: the store path, the checkpoint
  fingerprint, `base_ref` / `head_ref`, and the file/hunk index. No diff bytes cross the
  projection boundary.
- Adding that projection to `PHASE_PROJECTION_MATRIX` for `PHASE_REVIEW`.
- Adding that projection to `PHASE_PROJECTION_MATRIX` for `PHASE_AUDIT` **in addition to** the
  existing `scoped_repository_state` derived context. Audit's scoped repository read is
  retained in full: the shared diff is a floor for audit, never its whole evidence set, because
  audit's highest-value finding is a criterion with no code behind it and therefore no diff.
- Replacing the `diff` and `current_unit_of_work` entries in
  `FeatureTaskRuntimePhaseBriefingAssembler.DERIVED_CONTEXT_INSTRUCTIONS` so they name the
  delivered reference instead of instructing the agent to read the diff itself. The
  `scoped_repository_state` instruction keeps its existing "treat actual state, not any
  upstream receipt claim, as the evidence" wording.
- Backward-edge behaviour: re-entry via `audit_gap` and `review_fix` reuses the stored artifact
  at an unchanged checkpoint and re-derives when remediation moved the tree, following the
  existing `REFRESH_FROM_REPOSITORY` and `MUST_MATCH` checkpoint policies rather than
  introducing a new invalidation rule.

Out of scope: schema registration, telemetry, docs, end-to-end runtime tests — all in
subtask 4.

## Acceptance Criteria

1. A projection contract exists carrying store path, checkpoint fingerprint, `base_ref`,
   `head_ref`, and the file/hunk index, with an explicit declared field allowlist.
2. The projection delivered to `review` and to `audit` contains no diff bytes; its serialized
   size is independent of branch diff size.
3. `PHASE_REVIEW` declares the shared evidence projection in `PHASE_PROJECTION_MATRIX`.
4. `PHASE_AUDIT` declares the shared evidence projection **in addition to** its existing
   projections, and audit's `scoped_repository_state` derived context is retained unchanged.
5. The `diff` and `current_unit_of_work` derived-context instructions no longer tell the agent
   to read the diff itself; each names the delivered reference.
6. The `scoped_repository_state` instruction retains its existing wording requiring the actual
   repository state, not an upstream receipt claim, as criterion evidence.
7. Re-entry through `audit_gap` at an unchanged checkpoint reuses the stored artifact; re-entry
   after remediation that moved the tree re-derives.
8. Re-entry through `review_fix` reuses or re-derives per the edge's existing `MUST_MATCH`
   checkpoint policy, with no new invalidation concept introduced.
9. The phase topology is unchanged: forward edges, the `review`-requires-`audit`-`satisfied`
   entry gate, and both semantic backward edges are untouched.
10. A projection whose referenced artifact is absent at consumption time causes a re-derivation
    rather than a run failure.

## Non-Goals

- Merging the audit and review phases; the entry gate and both backward edges stay exactly as
  declared.
- Changing `ceremonyScaling`, review scope mapping, or any verdict semantics.
- Widening any consumer's field allowlist beyond the declared projection.

## Dependency Notes

Depends on subtask 1 (store) and subtask 2 (phase-neutral assembly).

## Validation Strategy

Projection tests asserting the declared field allowlist and that no diff bytes appear in the
delivered payload, including a large-diff case asserting projection size does not scale with
diff size. Matrix tests asserting both `PHASE_REVIEW` and `PHASE_AUDIT` declarations and that
audit retains `scoped_repository_state`. Briefing assembler tests over the rewritten
instruction strings. Backward-edge tests exercising `audit_gap` and `review_fix` re-entry at
both unchanged and moved checkpoints. A topology regression test asserting entry gates and
backward edges are unchanged. An absent-artifact test asserting re-derivation rather than
failure.

## Next Path

Proceed to subtask 4.
