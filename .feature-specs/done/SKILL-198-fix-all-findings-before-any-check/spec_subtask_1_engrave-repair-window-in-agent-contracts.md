# SKILL-198 Subtask 1: Engrave The Repair Window In Agent-Facing Contracts

## Intended Outcome

Every Skill Bill v1 agent-facing quality-check and validate instruction states one rule at the same strength: after a complete finding set exists, nothing runs until that set is fully repaired. Pack files that currently say "After each fix, re-run targeted checks" are the primary defect.

## Scope

- Rewrite the Fix Strategy / Execution Steps in every maintained platform-pack quality-check `content.md` so they collect one complete finding set, repair the whole set, and only then allow a verification gate.
- Rewrite `bill-code-check` and feature-task validate briefing (runtime-owned and agent-run fallback) to the same rule, including an explicit forbidden-command list.
- State that subagents are in scope: they must not run detekt, ktlint, tests, compile, or any other check while the parent holds an open finding set.
- Remove language that treats "the gate" as only the cache-bypassing full `check`, which is what lets targeted module runs slip through.

## Applicable Architectural Invariants

- Pack quality-check content remains the stack-specific checker; the shell stays thin.
- Runtime-owned `validation_gate` remains the authority for gate argv when declared.
- Findings stay bounded extracted records, not raw logs.

## Acceptance Criteria

1. No maintained pack quality-check `content.md` instructs the agent to rerun targeted checks after each fix, after each category, or after each file.
2. Each of those files states that during an open finding set the agent must not invoke any check, test, compile, format-task, or quality-check command, including Gradle module tasks and subagent runs.
3. Feature-task validate briefing uses the same prohibition and names targeted `detekt`, `ktlintCheck`, `test`, and `compileKotlin` as forbidden during the repair window, not only the full `--rerun-tasks` gate.
4. `bill-code-check` standalone and orchestrated paths use the same repair-window rule.
5. Agent-run fallback (pack with no `validation_gate`) still forbids intermediate checks; it does not revive "rerun the failing command after each fix."
6. The schema or playbook text that calls `full_gate_command` an "intermediate repair-cycle" run is corrected so it cannot be read as permission to check during repair.

## Failure And Recovery Behavior

- A pack that still contains the old rerun-after-each-fix sentence fails review of this subtask.
- Ambiguous wording that forbids only "the full gate" while allowing module tasks fails this subtask.

## Non-Goals

- Runtime command interception (subtask 2).
- Changing collect-all or cache-bypassing argv lists.

## Dependency Notes

None. This subtask can land first so agent-facing text stops contradicting the intended loop.

## Validation Strategy

Search all maintained quality-check `content.md` files, `bill-code-check`, and validate briefings for "after each fix", "targeted checks", "re-run the smallest", and equivalent phrasing. Confirm each file states the repair window and the forbidden-command list.

## Next Path

Subtask 2 enforces the same window in the v1 runtime so agents cannot invoke gate argv while a finding set is open.
