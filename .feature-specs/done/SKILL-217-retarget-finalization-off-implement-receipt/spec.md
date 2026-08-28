# SKILL-217 — Retarget finalization off the implement receipt

## Philosophy

SKILL-213 moved implement onto `phase_prose`. Audit already consumes implement
`value` verbatim. Finalization still pretends the old receipt exists.

The runtime projector `jsonDecode`s implement and plan `produced_outputs` and
fills `completed_task_ids`, `tests_added`, `tests_updated`, `deviations`,
`validation_strategy`, and leftover `changed_paths`. After SKILL-212/213 those
keys live inside `value`, so the projector delivers empty lists. Checkpoint
paths already cover inventory. The missing piece is the agent words.

Do not put validate, build, write_history, commit_push, or pr on `phase_prose`.
Keep runtime-owned measurement (working-tree paths, commit sha, validation /
build receipts). Deliver implement and plan as `phase_prose`. Stop extracting
typed receipt fields from those producers.

## Context

`finalizationProjectionContext` still reads implement `produced_outputs` as a
map. `pr_request` copies `completed_task_ids`, `tests_added`, `tests_updated`,
and `deviations` from that map, defaulting to empty. `validation_request`
copies `validation_strategy` and task `test_obligations` from plan
`produced_outputs`. `commit_request.required_exclusions` diffs leftover
implement `changed_paths` against the tree.

Those declarations still name `PHASE_IMPLEMENT` as the producer of
`validation_request`, `boundary_candidates`, `commit_request`, and
`pr_request`. Runtime-owned values win, so the handoff does not block. The
next agent never sees implement `value`. PR describes an empty receipt.

SKILL-213 already required changed-path inventory from the repository
checkpoint, not from receipt fields. This skill finishes that retarget:
prose to the agent, tree to Kotlin, no decode of `value`.

## Intended Outcome

Validate, build, write_history, commit_push, and pr launch without reading
typed implementation-receipt or executable-plan fields from implement or plan
`produced_outputs`.

- Path inventory, inclusions, boundary candidates, and `changed_paths` stay
  runtime-owned from the checkpoint working tree.
- Commit identity, gate attestations, base branch, and diff reference stay
  runtime-owned.
- Implement `value` (and optional `prompt`) reaches write_history, commit_push,
  and pr through `phaseProseDeclaration`, the same helper audit already uses.
- Plan `value` reaches validate and build the same way, so required-check
  guidance is agent-interpreted, not Kotlin-parsed from `validation_strategy`
  / `tasks`.
- `pr_request` no longer declares `completed_task_ids`, `tests_added`,
  `tests_updated`, or `deviations`.
- `finalizationProjectionContext` does not read implement or plan
  `produced_outputs` for those retired keys. Leftover sibling keys beside
  `value` are ignored. `value` is not parsed.

## Acceptance Criteria

1. A completed implement whose `produced_outputs` is only a non-blank `value`
   still launches validate, build, write_history, commit_push, and pr. None of
   those launches fail because `completed_task_ids`, `changed_paths`,
   `tests_added`, `validation_strategy`, or `tasks` are absent as sibling keys.
2. Write_history, commit_push, and pr briefings contain implement `value`
   unchanged. Optional `prompt` appears when present.
3. Validate and build briefings contain plan `value` unchanged. Optional
   `prompt` appears when present.
4. Changed-path inventory on validate, build, write_history, and commit_push
   still comes from the repository checkpoint working tree, not from implement
   or plan `produced_outputs` fields inside or beside `value`.
5. `pr_request` no longer declares `completed_task_ids`, `tests_added`,
   `tests_updated`, or `deviations`. Kotlin does not fill those names from
   implement output.
6. `commit_request.required_exclusions` does not diff leftover implement
   `changed_paths` against the tree.
7. `validation_request.required_checks` is not derived by decoding plan
   `validation_strategy` or `tasks`.
8. `finalizationProjectionContext` and `prRequestProjection` do not
   `jsonDecode` implement or plan `value` to recover receipt or plan fields.
9. Existing `phase_prose` contract, `phaseProseProducedOutputs` def, and
   `phaseProseDeclaration` helper are reused. No `implement_prose`,
   `plan_prose`, or `pr_prose` sibling contracts.
10. Automated tests cover criteria 1–8. One strong runner or handoff test per
    rule, not literal-variation siblings.

## Constraints

- Keep the outer phase envelope. Do not treat agent stdout as `PhaseOutput`.
- Do not put validate, build, write_history, commit_push, or pr on
  `PhaseOutput` / `phaseProseProducedOutputs`.
- Do not parse `value` at any Kotlin finalization seam.
- Preserve runtime-owned validation and build receipts, commit_sha writeback,
  and required commit `message`.
- Preserve loud-fail for envelope failures, blank implement/plan `value`,
  missing commit `message`, and contract-version drift.
- Extra keys beside implement or plan `value` stay ignored.

## Non-Goals

- Converting write_history, validate, build, commit_push, or pr onto
  `phase_prose`.
- Changing census I/O for verify_findings or implement_fix.
- Changing audit-gap topology, review findings, or commit finalisation git
  ownership.
- Unifying `PhaseOutput.value` and `AgentPhaseOutput.output`.
- Restoring structured task-id injection from implement into pr.

## Decomposition Rationale

One subtask. The leftover is one projector and four consumer declarations that
still name the old receipt. Splitting “add prose declarations” from “stop
decoding produced_outputs” would leave a commit that still ships empty typed
lists beside unread `value`.

## Next Path

None for this I/O program. Remaining finalization receipts are runtime
measurement (`history_result` presence, pack-gate receipts, commit `message`,
`pr_result` URL). Do not convert them by momentum.
