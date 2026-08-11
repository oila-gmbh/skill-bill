# SKILL-181 · Subtask 2 — In-run preplan refresh with heading-set comparison

## Scope

When `GoalPlanningSweep` classifies the saved shared preplan as valid but not fresh, refresh it
in-run instead of stopping:

1. Refuse refresh while the goal is live, using the same liveness rules that already guard scoped
   replan.
2. Re-run the preplan phase once against the current parent spec (one refresh per launch; no loop).
3. Compare `selected_boundary_headings` set equality between saved and new payloads:
   - **Unchanged** — persist provenance updated to current, keep the saved `preplan_payload` /
     `payload_sha256`, keep every sibling plan row (terminal and non-terminal).
   - **Changed** (grow or shrink) — persist the new full payload + provenance, and discard only
     non-terminal sibling plan rows (terminal exclusion lands in subtask 3; until then, cascade
     through the shared filter subtask 3 will own, or leave a clearly marked temporary call site).
4. Persist atomically: a crash mid-refresh leaves either the old valid record or the new one, never
   a provenance/payload mismatch.
5. Leave runtime subtask fields (`status`, `commit_sha`, `workflow_id`) and out-of-band acceptances
   untouched.
6. Keep `replan --include-shared-preplan` as an explicit force-regeneration path.

`preplan_payload` is the full `preplanning_digest` JSON. Cascade is gated only on heading-set
equality; prose-field drift alone does not cascade when the set is unchanged.

## Acceptance Criteria

1. A stale-but-valid preplan is refreshed in-run on relaunch with no operator command and without a
   blocked terminal state.
2. When the refreshed heading set equals the saved set, every sibling plan row remains and the
   persisted payload bytes stay the saved payload (provenance alone advances to current).
3. When the refreshed heading set differs, the new payload is adopted and only non-terminal sibling
   plan rows are discarded once the terminal filter from subtask 3 is in place; until then, the
   refresh path must call one shared cascade helper rather than inlining a discard-all.
4. Refresh is refused while the goal is live.
5. A second stale classification in the same launch does not re-enter refresh (at most one refresh
   per launch).
6. A simulated crash between deleting the old shared preplan and writing the new one cannot leave a
   durable record whose `payload_sha256` disagrees with `preplan_payload`.
7. Explicit `replan --include-shared-preplan` still forces shared-preplan regeneration.

## Non-Goals

- Validity/freshness classification itself (subtask 1).
- Implementing the terminal-subtask cascade exclusion (subtask 3), beyond routing discards through
  one helper.
- Exit codes and `planning_reason` truthfulness (subtask 4).

## Dependencies

- Subtask 1 (validity vs freshness signal).

## Validation Strategy

- Integration-style sweep test: body-only parent-spec edit with identical heading selection →
  provenance advances, plans retained, no stop.
- Same setup with a fixture that forces a different heading set → new payload adopted; non-terminal
  plans discarded via the shared helper.
- Assert live-goal refresh is refused.
- Assert a second stale check in one prepare does not launch a second preplan agent.
- Build and test the affected modules.

## Next Path

Subtask 3 makes the shared cascade helper skip terminal subtasks on every path.
