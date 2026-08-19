# SKILL-198: Fix All Findings Before Any Check Runs

## Intended Outcome

Skill Bill v1 must engrave a hard repair window for validation and quality-check. One collect-all gate run produces the complete finding set. After that set exists, nothing runs until every finding in it is fixed at its root cause. No Gradle task, no detekt, no ktlint, no tests, no compile, no pack checker, no `bill-code-check`, no formatter task, no subagent check. Only then does the runtime run one verification gate.

Today the validate briefing already says to batch repair and not rerun "the gate" after an individual fix. Agents still treat that as "do not rerun `./gradlew check --rerun-tasks`" and keep running targeted module checks. Pack quality-check content still says "After each fix, re-run targeted checks." That contradiction is the defect. This feature removes the contradiction and makes the repair window a runtime rule, not a hint.

This is Skill Bill v1 product work: feature-task validate, `bill-code-check`, and every platform-pack quality-check. It is not skill-bill-v2 runtime hardening.

## Scope

- Define the repair window as a first-class validate and quality-check invariant.
- Replace every agent-facing instruction that tells the agent to rerun targeted checks after each fix or after each category.
- Forbid all check, test, compile, format-task, and quality-check invocations during the repair window, including subagents.
- Keep discovery to one collect-all gate and verification to one cache-bypassing full gate after the whole set is repaired.
- Enforce the window in the v1 runtime when a pack declares `validation_gate`, so the agent cannot invoke gate argv until it reports the current finding set repaired.
- Preserve collect-all `--continue` behavior so a failing task does not hide the rest of the finding set.

## Acceptance Criteria

1. After the runtime or agent holds a complete finding set from one collect-all gate run, no check command of any kind runs until every finding in that set is fixed at its root cause.
2. The forbidden set during the repair window includes the full gate, collect-all, build-only, every Gradle task (including `detekt`, `ktlintCheck`, `ktlintFormat`, `test`, `compileKotlin`, `check`, and module-scoped variants), `bill-code-check`, every pack quality-check sidecar, and the equivalent commands on other packs (`cargo`, `npm`, `pytest`, `ruff`, `clippy`, and so on).
3. Subagents inherit the same prohibition. Delegating a targeted detekt, ktlint, or test run is a spec violation, not a workaround.
4. Allowed work during the repair window is read, search, and edit of source and of the already-collected finding set. File edits may apply formatter-equivalent changes by editing files; invoking a formatter or check task is still forbidden.
5. The only verification run after a completed repair window is one cache-bypassing full gate. If it fails, its output is the new complete finding set and a new repair window starts. There is no per-fix, per-file, per-module, or per-category check run.
6. Every maintained platform-pack quality-check `content.md` no longer tells the agent to rerun targeted checks after each fix or after each category.
7. Feature-task validate briefing, agent-run fallback, and `bill-code-check` state the same repair-window rule in the same strength: nothing runs until the collected set is fully repaired.
8. When a pack declares `validation_gate`, the v1 runtime owns collect-all and the post-repair verification gate. The agent does not invoke those argv itself, and the runtime rejects or withholds check execution while a finding set remains unrepaired.
9. `full_gate_command` is no longer described or used as an "intermediate repair-cycle" check. Intermediate gate runs during an open repair window are forbidden.
10. Compiler diagnostics from a collect-all that fails to compile are part of the finding set. The agent does not rerun compile to rediscover them while that set is still open.
11. Suppression, baselines, disabled rules, skipped tests, and weakened configuration remain forbidden ways to close a finding.
12. A focused regression proves that an agent-facing prompt or runtime path which previously allowed a targeted check during an open finding set now forbids it, and that verification occurs only after the set is marked repaired.

## Constraints

- Do not weaken, skip, or baseline any repository check in order to shorten the loop.
- Do not change pack gate argv except where a description or use of `full_gate_command` as an intermediate repair-cycle run must be removed.
- Do not fold this work into SKILL-16 or the skill-bill-v2 production-hardening program.
- Preserve runtime-owned finding extraction. Do not dump raw gate logs into the agent handoff.
- Goal children keep existing validation-depth policy (build-only until the last subtask). Build-only is still a runtime-owned gate, not an agent-run targeted check during a repair window.

## Non-Goals

- Redesigning pack routing, finding artifact formats, or suppression detection.
- Changing which command a pack uses for collect-all or cache-bypassing verify.
- Skill-bill-v2 Slot Executioner, persistence, or installer work.
- Allowing a "cheap compile" or "one module detekt" exception for speed.

## Failure And Recovery Behavior

- If collect-all cannot run, loud-fail with the missing command or environment; do not substitute a targeted module check.
- If the verification gate produces new findings, open a new repair window over that complete set. Do not rerun the gate after fixing a subset.
- If the agent invokes a check during an open repair window, the runtime must refuse it when it owns the gate. In agent-run fallback, the briefing must make that invocation a contract violation.

## Validation Strategy

Inspect every maintained pack quality-check file, the validate briefing, `bill-code-check`, and the validation-gate schema/runtime path for remaining "rerun after each fix" language and for any remaining use of `full_gate_command` as an intermediate repair cycle. Prove the repair window with a focused test or contract check: findings present implies no check argv until repaired, then exactly one verify gate.
