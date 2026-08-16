# SKILL-193 Subtask 3: Brokered hunk fetch and lane evidence budget

## Intended Outcome

Workers receive assigned hunk **locators**, not bodies, in launch envelopes
and inline prompt assembly. The broker delivers assigned hunk bodies in
bounded batches. Body overflow makes that lane incomplete; it does not fail
parent composition or sibling lanes.

## Scope

- Stop serializing `hunk.content` in `ReviewLaneAssembledEntry.toEnvelope`,
  `GovernedReviewLaunch` delivered entries, and
  `ParallelCodeReviewRunner.appendAssignedBundleEvidence`. Launch and inline
  assembly name locators, ids, spans, and digests.
- Deliver bodies only through the existing review evidence broker, in
  batches counted against `max_lane_evidence_bytes`. Serve only hunks in the
  assignment; out-of-assignment paths still require a recorded expansion.
- If assigned bodies cannot all be delivered within
  `max_lane_evidence_bytes`, the lane ends incomplete with
  `review_context_budget_exceeded`, `budget_dimension` naming the evidence
  budget, and the unreviewed hunk ids. Do not truncate delivered hunks, skip
  a required lane, or fall back to embedding the full patch.
- Inline mode is one lane over the full assignment and uses the same broker
  rule. A huge mechanical split may complete parent compose and still report
  incomplete inline coverage; that is honest coverage, not a parent block.
- Integration pass still must not be reported as closing an incomplete
  lane's gap.
- Findings, accounting summaries, and goal-facing review output remain free
  of hunk bodies.
- Update runner, broker, and launch-envelope tests. Add a SKILL-16-scale
  fixture: parent compose succeeds; the single inline lane is incomplete if
  the stored patch exceeds `max_lane_evidence_bytes`, or completes if the
  test raises only the lane evidence budget.

## Acceptance Criteria

1. Launch envelopes have no hunk `content`; schema validation rejects one.
2. Inline prompt assembly does not append stored hunk text from the packet
   or launch envelope; it names locators and lets the broker supply bodies.
3. The broker returns only assigned hunks. An out-of-assignment read without
   an authorized expansion is a forbidden-rediscovery / expansion failure.
4. Brokered body bytes count against `max_lane_evidence_bytes`. Crossing the
   limit terminates that lane as incomplete and lists unreviewed hunk ids.
5. Parent composition is not retried or failed when a lane evidence budget
   is exceeded.
6. A required lane is never omitted to stay under budget.
7. Delivered hunk bodies are not truncated. Partial last-hunk delivery is
   not a success path.
8. Integration accounting still treats incomplete lanes as non-clean
   coverage.
9. Finding register format, 7-finding cap, and no-body accounting rules are
   unchanged.
10. Tests cover: launch schema rejection of `content`; broker assigned-only
    serving; evidence-budget incomplete lane; sibling lane still runs;
    SKILL-16-scale parent compose success; digest mismatch on broker read
    loud-fails. `skill-bill validate` passes.

## Non-Goals

- Raising default `max_parent_packet_bytes` or `max_lane_evidence_bytes` as
  the product fix (tests may raise a lane budget locally to prove the
  complete path).
- Replacing the broker with direct filesystem reads from workers.
- SKILL-191 verification/adjudication prompt contents beyond keeping them
  free of parent-packet hunk bodies.

## Dependency Notes

Depends on subtasks 1 and 2.

## Validation Strategy

- Launch envelope fixture without `content` validates; with `content` fails.
- Broker test: assigned hunk served, unassigned path rejected.
- Budget test: evidence overflow → incomplete lane, parent result still
  composed, unreviewed ids present.
- Scale fixture: stored patch > 524288, parent compose succeeds.
- `skill-bill validate`.

## Next Path

Commit on the feature branch. Parent spec acceptance is complete when this
subtask lands.
