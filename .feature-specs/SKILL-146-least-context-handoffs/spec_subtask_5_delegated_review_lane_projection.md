# SKILL-146 Subtask 5: Delegated-review lane delivery and bounded evidence surface

## Scope

Project each delegated-review lane from authoritative parsed `ReviewDiffEvidence`. Supply assigned hunk content, rubric, specialist/consumer contracts, forbidden-rediscovery and evidence rules, and report structure in the launch. Exclude unassigned hunks and whole-diff references. Add hunk-window evidence admission, reasoned complete-file expansion, repeat-read rejection, typed forbidden outcomes, and semantic parity across Claude prompt, Codex native, and CLI delivery.

## Acceptance Criteria

1. Parent AC 1, 2, 4, 5, 22, and 25 apply the named/versioned contract, closed-world content, typed errors, immutable checkpoint, budgets, and positive/negative assertions to review lanes.
2. Parent AC 27 supplies only assigned hunk content and treats diff or scratch-artifact rediscovery as a typed forbidden outcome.
3. Parent AC 28 scopes evidence to hunk windows, requires a nonblank reachability reason for whole-file expansion, and rejects repeated reads through pagination variants.
4. Parent AC 29 gives Claude, Codex, and CLI the same semantic projection with no complete-diff path or artifact.
5. Parent AC 30 launches rubric/contracts/rules directly and proves any restatement byte-identical to one authoritative copy.
6. Parent AC 31 focused broker, provider, goal-child, standalone, retry, resume, and end-to-end review tests pass.

## Non-Goals

- Reducing lanes, baseline areas, rubric substance, or changing severity.
- Recomputing/widening diffs or permitting alternative provider baselines.
- Unreasoned complete-file admission.

## Dependency Notes

Depends on Subtask 2. May run alongside Subtasks 3 and 4. Reuse shared versions, budgets, checkpoints, and typed errors.

## Validation Strategy

- Assigned-hunk presence and cross-lane absence.
- Complete-diff, scratch-path, and unexpanded whole-file rejection.
- Hunk-window, reachability-reason, and repeat-read tests.
- Immutable review base and authoritative-rule parity tests.
- Claude/Codex/CLI golden launch parity plus broker and lifecycle regressions.

## Next Path

Proceed to Subtask 6 after Subtasks 3, 4, and 5 are complete.

