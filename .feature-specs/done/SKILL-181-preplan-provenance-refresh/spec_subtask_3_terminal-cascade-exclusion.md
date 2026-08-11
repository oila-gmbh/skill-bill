# SKILL-181 · Subtask 3 — Exclude terminal subtasks from plan cascade

## Scope

Ensure no subtask with a terminal status and a `commit_sha` ever has its plan row discarded, on
every cascade path:

- in-run preplan refresh when the heading set changes (subtask 2)
- explicit `goal replan <issue> --subtask <id> --include-shared-preplan`

Today's CLI cascade in the replan writer discards every sibling plan id when
`--include-shared-preplan` is set (see `GoalRunnerTest` / replan write path). That is the WE-4719
defect: subtask 1 was `complete` with a pushed commit and still lost its plan row.

Define one shared predicate/filter: a plan row is cascade-eligible only when the corresponding
manifest subtask is **not** terminal-with-`commit_sha`. Use that filter everywhere plans are
cascaded because the shared preplan was discarded or replaced.

Do not reopen terminal subtasks, clear `commit_sha` / `workflow_id`, or change scoped replan without
`--include-shared-preplan`.

## Acceptance Criteria

1. `--include-shared-preplan` discards the shared preplan and cascades only non-terminal sibling plan
   rows; a `complete` subtask with a `commit_sha` keeps its plan row.
2. In-run refresh with a changed heading set uses the same filter and likewise preserves
   terminal-with-commit plan rows.
3. A non-terminal sibling plan is still discarded on both paths when cascade applies.
4. Runtime fields on terminal subtasks (`status`, `commit_sha`, `workflow_id`) remain unchanged
   across both cascade paths.
5. Existing CLI coverage that expected cascading a completed sibling is updated to assert
   preservation instead.

## Non-Goals

- Changing when cascade is triggered (subtask 2 owns set comparison).
- Exit-code remapping (subtask 4).
- Accept / hard-reset / reopen flows.

## Dependencies

- Subtask 2 (refresh cascade call site must exist so the shared filter has both consumers).

## Validation Strategy

- Reproduce the WE-4719 shape in a replan test: subtask 1 complete+commit, replan subtask 2 with
  `--include-shared-preplan` → cascaded ids exclude 1; plan row for 1 still present.
- Refresh-path test with a changed heading set and one terminal sibling → that sibling's plan
  survives.
- Non-terminal sibling still appears in `cascaded_plan_subtask_ids`.
- Build and test the affected modules.

## Next Path

Subtask 4 fixes exit codes, remedy-naming stops, and `planning_reason` truthfulness.
