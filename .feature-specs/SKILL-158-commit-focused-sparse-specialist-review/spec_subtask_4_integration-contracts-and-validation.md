# SKILL-158 Subtask 4 - Integration Contracts And Validation

Parent spec: [.feature-specs/SKILL-158-commit-focused-sparse-specialist-review/spec.md](./spec.md)
Issue key: SKILL-158

## Scope

Add one bounded final integration check for cross-commit behavior, align all
governed review contracts and generated native-agent surfaces with the
commit-focused sparse model, and validate the complete runtime path.

In scope:

- Define the parent-owned or dedicated integration pass that checks final
  feature behavior and interactions between specialist-reviewed commits once
  per PR/review.
- Ensure the integration pass consumes bounded final-state evidence and
  specialist summaries without replaying every specialist rubric over every
  commit.
- Update review context schemas, lifecycle evidence, telemetry mappings,
  report contracts, and aggregation tests for commit/lane dispositions and
  attribution.
- Update `review-scope`, `review-delegation`, `review-orchestrator`, specialist
  contract, stack-specific guidance, and native-agent source where they state
  whole-PR or aggregate-diff review behavior.
- Refresh installed native-agent staging after governed source changes and
  prove generated outputs remain uncommitted.

## Acceptance Criteria

1. A complete delegated review performs one bounded integration pass after
   specialist lanes finish, and the pass can report cross-commit findings with
   evidence without launching every specialist rubric again.
2. Integration review receives enough final-state and specialist-summary
   context to detect interactions between commits, while unrelated raw lane
   evidence and parent transcripts remain excluded.
3. Lifecycle and telemetry schemas record commit sequence identity, lane
   assignment/disposition counts, focused/skipped commits, per-lane bundle size
   and segment counts, incomplete lanes from budget exhaustion, parent analysis
   budget consumption, and integration-pass terminal state with stable
   attribution.
4. Crash, cancellation, timeout, retry, and resume tests prove that specialist
   completion and integration completion are distinct durable boundaries, that
   retry is lane-granular, and that aggregation rejects missing, duplicate, or
   mismatched commit/lane results.
5. Governed prose consistently says that the parent owns discovery and relevance,
   that specialists receive one assembled bundle of assigned hunks with commit
   metadata and review it in a single pass, that irrelevant commits are excluded
   before launch with recorded reasons, and that one final integration pass
   covers cross-commit behavior.
6. Generated native-agent prompts and provider launch projections contain the
   same rules and do not instruct workers to run broad diff discovery, re-decide
   relevance, step through commits individually, or review every commit by
   default.
7. Inline and non-commit review paths retain their existing semantics and
   explicitly report when commit-focused delegated sequencing is unavailable or
   not applicable.
8. `./install.sh` refreshes local installed staging after governed source
   changes, and generated install output remains absent from version control.
9. Aggregation and reporting treat a lane that ended incomplete from budget
   exhaustion as non-clean coverage, naming its unreviewed units, and the
   integration pass is not presented as compensating for that gap.
10. `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`,
    `npx --yes agnix --strict .`, and `scripts/validate_agent_configs` pass.

## Non-Goals

- Adding another specialist rubric or changing the severity/finding contract.
- Replacing the bounded evidence broker with full-tree or full-PR worker access.
- Making the integration pass a second copy of every specialist review.
- Changing standalone inline review into a multi-worker commit pipeline.

## Dependency Notes

Depends on: 1, 2, 3

This unit closes the feature after the evidence model, sparse routing, and
single-pass lane review are stable.

## Validation Strategy

Run full contract, lifecycle, aggregation, native-agent, and end-to-end review
fixtures. Verify a mixed six-commit PR produces sparse specialist work followed
by one integration pass, with worker count equal to lane count regardless of
commit count, while a single-commit and working-tree review preserve existing
output. Finish with all validation commands in Acceptance Criterion 10.

## Next Path

Complete the SKILL-158 goal and prepare the PR handoff.

## Spec Path

.feature-specs/SKILL-158-commit-focused-sparse-specialist-review/spec_subtask_4_integration-contracts-and-validation.md
