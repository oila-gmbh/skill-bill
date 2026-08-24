# SKILL-200 Subtask 3 — Delete the prose no agent acts on

## Intended Outcome

`bill-feature-goal` and `bill-feature` lose every section that describes runtime internals,
documents CLI flags, repeats another section, or tells the agent to reproduce output the CLI
already prints. No behavior changes. This is deletion only, so the diff is legible and the
rewrite in subtask 5 starts from a much smaller file.

## Scope

Delete from `skills/bill-feature-goal/content.md`:

- Runtime-Owned Worker Model (28 lines). Describes the flat worker loop, workflow-store
  authority, and nested-subagent caveats. The agent takes no action on any of it.
- Goal child review contract (46 lines). `review_base_sha` capture, baseline untracked
  subtraction, pass reservation, severity gating, and ledger sanitization are all runtime
  enforced.
- Status Checks (64 lines), except the two read-only commands the skill must mention.
  The `--diff-stat`, `--diff-hunk`, `--diff-hunk-max-*` catalogue and its sample output blocks
  belong to `--help`.
- Bounded planning context and thin retention (21 lines). Discovery caps and parent retention
  are runtime internals.
- Audit-first review and findings ledger (3 lines). Duplicates the review contract above.
- Required: print the terminal monitoring command (24 lines). `GoalCliCommands.kt:951-954`
  already emits the monitor block with the real issue key substituted.
- Completion Signal (41 lines), reduced to the relay instruction and the launch-mode rules that
  are genuinely agent-owned. `GoalCliCommands.kt:1077-1110` already renders the finished line,
  the summary line, and the blocked, failed, and paused forms.
- Intake (17 lines). Duplicates `bill-feature`'s intake.
- Default output verbosity (13 lines). Overlaps Watching Long Runs.
- The runtime-restatement portions of the 49-line preamble, including the replan and hard-reset
  semantics paragraph and the review-mode, parallel-review, and add-on token rules already stated
  in `bill-feature`.

Delete from `../../../skills/bill-feature/content.md`:

- Least-Context Runtime Handoffs (8 lines). Runtime internals with no agent action.
- Two of the three fresh-conversation handoff copies, keeping one.

Fix while here: the `## Update Check` heading in `bill-feature` is separated from its own body,
which currently sits below the Agent add-on selection section. Reunite them.

Keep untouched in this subtask: the Decomposition Proposal confirmation gate, Confirmed Handoff,
the Linear rehydrate section, the anti-polling rules, and the launch-mode completion rules.
Subtask 5 rewrites those; deleting and rewriting in one commit would hide which change did what.

## Acceptance Criteria

1. No skill in the feature family describes the runtime worker model, the child review contract, review-base capture, baseline untracked subtraction, pass reservation or accounting, severity gating, ledger sanitization, remediation loop mechanics, planning-discovery caps, or parent thin-retention rules.
2. No skill in the feature family documents `--diff-stat`, `--diff-hunk`, `--diff-hunk-max-hunks`, `--diff-hunk-max-lines`, `--diff-hunk-max-bytes`, or shows their sample output.
3. No skill instructs an agent to compose or print a monitor block, and no skill instructs an agent to compose a terminal completion or summary line from structured fields.
4. The anti-polling rules appear exactly once across the feature family, and the fresh-conversation handoff appears exactly once.
5. Intake is stated once, in `bill-feature`, and `bill-feature-goal` does not restate it.
6. Review-mode, parallel-review, and agent add-on token rules appear once, in `bill-feature`, and `bill-feature-goal`'s preamble does not restate them.
7. The `## Update Check` section in `bill-feature` contains its own body, with no content belonging to it placed under another heading.
8. Replan and hard-reset operator semantics are stated at most once across the feature family.
9. The Decomposition Proposal gate, Confirmed Handoff, Linear rehydrate section, and launch-mode completion rules are still present and unmodified, since subtask 5 owns their rewrite.
10. No runtime, contract, CLI, test, or install behavior changes: `git diff --stat` for this subtask touches only files under `../../../skills`.
11. `skill-bill validate` passes, a clean `./install.sh` succeeds, and the Kotlin suites covering install staging and feature-family rendering pass with any governed-content assertions updated to match the reduced prose.
12. `npx --yes agnix --strict .` and `../../../scripts/validate_agent_configs` pass.

## Non-Goals

Rewriting the surviving sections or merging the two skills. Subtask 5.

Adding the preflight verb or changing add-on resolution. Subtask 4.

Deleting `skills/bill-feature-goal/` itself. Subtask 5, after the runtime owns its logic.

## Dependency Notes

Depends on subtask 1, which removes `bill-feature-task-runtime` and with it one of the three
copies of the anti-polling and completion-signal block. Doing this first would leave criterion 4
unsatisfiable without touching a file subtask 1 deletes anyway.

Independent of subtask 4. This is pure deletion and changes no behavior, so it can land while the
preflight verb is still being designed.

## Validation Strategy

`git diff --stat` is the primary check for criterion 10: anything outside `../../../skills` means scope
leaked. Then `skill-bill validate` and `./install.sh`. Governed-content tests that assert deleted
headings will fail; update them to the reduced prose rather than restoring the text.

Prove criteria 1 through 8 by searching the surviving feature-family sources for the named
concepts and confirming each appears the allowed number of times.

## Next Path

Proceed to subtask 4.
