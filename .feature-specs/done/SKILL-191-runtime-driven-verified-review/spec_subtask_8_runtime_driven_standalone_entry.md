# SKILL-191 · Subtask 8 — Runtime-driven standalone entry

## Scope

Route standalone `bill-code-review` through the runtime driver and strip the
orchestration prose the driver now owns.

**A second lane becomes optional.** `CodeReviewParallelCommand` currently requires
`--agent2`. Introduce the single-lane entry — `skill-bill code-review` — over the
same `ParallelCodeReviewRunner`, so a plain review is a runtime-driven run with one
lane rather than a prose-orchestrated one. `parallel:<agent>` and the
`code_review_parallel_agent` config fallback keep their meaning and add the second
lane; their precedence and validation are unchanged.

**`content.md` shrinks to an entry contract.** It keeps: argument recognition
(`mode:`, `parallel:`, `context:feature-remediation`), the config fallback call, the
invocation of the driver, and how to present the returned register. It loses: scope
resolution, diff-signal classification, rubric naming, worker preflight, commit-focused
sequencing prose, budget accounting narrative, the merge step, the `mktemp` and
stdin-pipe lane-2 mechanics that the CLI delegation path already supersedes, and
failure handling. Every removed rule must be enforced in the runtime before its prose
is deleted — a rule that exists in neither place is a silent regression.

Update `orchestration/skill-classes/code-review-shell.yaml` in step, so the governed
shell contract describes an entry over a driver rather than an orchestration
procedure. `detected_scope` labels stay governed and unchanged.

**Worker preflight moves into the driver.** A missing, dangling, stale, unreadable, or
undeclared native worker stays a hard failure reporting the governed repair command,
and never degrades to a general-purpose worker — enforced in the runtime rather than
asserted in prose.

**No new `claude -p` paths.** Lane 1 and every stage run through the existing
`AgentRunLauncher` route the driver already uses for the inline parent lane and
specialists.

Run `./install.sh` after changing the skill source.

## Acceptance Criteria

1. `skill-bill code-review` runs a standalone review end to end through `ParallelCodeReviewRunner` with no second lane required, producing the register with stage verdicts.
2. `parallel:<agent>` and the `code_review_parallel_agent` config fallback keep their existing precedence, validation, and unsupported-value errors, and add a second lane to the same driver.
3. `skills/bill-code-review/content.md` no longer contains scope resolution, diff-signal classification, rubric naming, commit-focused sequencing, budget accounting narrative, merge steps, `mktemp` lane-2 mechanics, or failure-handling prose.
4. Every behaviour removed from `content.md` is enforced in the runtime, and a review that violates it fails through the runtime rather than passing unnoticed.
5. Native-worker preflight is enforced by the driver: a missing, dangling, stale, unreadable, or undeclared worker stops the run and reports the governed repair command, and never substitutes a general-purpose worker.
6. `orchestration/skill-classes/code-review-shell.yaml` describes the entry-over-driver contract, and the governed `detected_scope` vocabulary is unchanged.
7. No new `claude -p` or equivalent subprocess path is introduced for work the existing `AgentRunLauncher` route covers.
8. `skill-bill validate`, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs` pass, and `./install.sh` regenerates the installed skill.

## Non-Goals

- Changing `mode:auto|inline|delegated` resolution rules or the named auto rules.
- Changing lane-2 agent support, the supported-agent list, or model override parsing.
- Changing the `detected_scope` vocabulary or scope labels.
- Removing `bill-code-review-parallel` or altering its two-lane semantics beyond the
  optional second lane.

## Dependency Notes

Depends on subtask 6, since the standalone entry emits through verdict-aware register
assembly. Subtask 9 depends on this driver entry existing.

## Validation Strategy

- One end-to-end test that a standalone single-lane review produces a register with
  stage verdicts, which is the whole point of the rewiring.
- One test per removed prose rule that the runtime now enforces it — scope resolution
  and worker preflight are the two where a gap would be silent and damaging.
- One test that `parallel:` precedence and unsupported-value errors are unchanged, since
  this is the regression risk of making `--agent2` optional.

## Next Path

Subtask 9 — feature-task review phase delegation.
