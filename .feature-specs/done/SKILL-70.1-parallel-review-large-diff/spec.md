---
status: Complete
---

# SKILL-70.1 — parallel review: large-diff robustness

Created: 2026-06-07
Issue key: SKILL-70.1
Mode: single_spec

## Intended Outcome

The `bill-code-review parallel:<agent>` skill path handles arbitrarily large diffs without hitting
shell argument-length limits or producing truncated lane 2 output. For diffs above a size threshold,
the skill delegates to `skill-bill code-review-parallel` (the CLI path) rather than building a
bash subprocess inline. Agents that already support stdin-pipe continue to use it; the CLI path
is the authoritative fallback for all others and for large diffs.

## Motivation

The skill's current bash subprocess approach for lane 2 writes the full diff into a temp file and
either pipes it via stdin (claude, codex after the SKILL-70 quick fix) or expands it as a shell
argument via `$(cat ...)` (opencode, copilot, junie). The argument-expansion form breaks above
~200 KB on most shells (`ARG_MAX` / `E2BIG`). Even the stdin form is unbounded — agents may
buffer the entire prompt in RAM before processing. The `skill-bill code-review-parallel` CLI
avoids both problems: it resolves the diff and builds the prompt entirely in-process through the
`AgentRunLauncher` abstraction, with no shell argument at all.

A second problem: the dedup key in `ParallelReviewMerger` is an exact `location|description`
string match. Agents phrasing the same finding differently produce duplicate entries in the merged
register with separate provenance labels, increasing noise rather than boosting signal.

## Affected Files

- `skills/bill-code-review/content.md` — add size guard and CLI delegation path
- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/review/ParallelReviewMerger.kt` — fuzzier dedup key
- `runtime-kotlin/runtime-domain/src/test/kotlin/skillbill/review/ParallelReviewMergerTest.kt` — tests for fuzzy dedup
- `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/CodeReviewMergeCommand.kt` — no change expected
- (possibly) `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/ParallelCodeReviewRunner.kt` — subprocess timeout in `FileSystemDiffResolver` (see F-002 from SKILL-70 review; may be a separate ticket)

## Acceptance Criteria

1. (Skill content — size guard) The skill measures the diff byte count before writing the lane 2 prompt.
2. (Skill content — size guard) When the diff exceeds a documented threshold (default: 200 KB), the skill emits a notice and
   delegates both lanes to `skill-bill code-review-parallel --agent1 <lane1> --agent2 <lane2>
   --scope <scope> --repo-root <root>` instead of the inline bash subprocess path.
3. (Skill content — size guard) When the diff is within the threshold, the existing stdin-pipe path is used for agents that
   support it (claude: `claude -p < file`, codex: `codex exec - < file`).
4. (Skill content — size guard) Agents that do not support stdin-pipe (opencode, copilot, junie) always delegate to the CLI
   path regardless of diff size, and the skill documents this explicitly per-agent.
5. (Skill content — size guard) The threshold value and the per-agent stdin-pipe support flag are documented in the skill
   content so they can be updated without touching the routing logic.
6. (Merger — fuzzier dedup) The `ParallelReviewMerger` coalesces two findings as duplicates when they share the same
   file path (extracted from the location field) AND their descriptions have a token-overlap
   ratio (Jaccard on word sets) above a documented threshold (e.g. 0.6).
7. (Merger — fuzzier dedup) Findings at different file paths are never coalesced regardless of description similarity.
8. (Merger — fuzzier dedup) Severity disagreement on a coalesced pair still resolves to the higher severity (existing
   behaviour preserved).
9. (Merger — fuzzier dedup) `ParallelReviewMergerTest` covers: exact-match (existing), fuzzy-match coalescing,
   same-description-different-file not coalesced, token-overlap below threshold not coalesced.
10. (Merger — fuzzier dedup) The fuzzy threshold is a named constant in `ParallelReviewMerger`, not a magic number.
11. (No regression) All existing `ParallelReviewMergerTest` and `CliCodeReviewMergeRuntimeTest` tests pass.
12. (No regression) `skill-bill validate` passes after content changes.

## Out of Scope

- Subprocess timeout in `FileSystemDiffResolver` (F-002 from the SKILL-70 review) — separate ticket.
- Diff chunking by module (would require per-chunk merge and re-number; adds complexity without
  proportional gain given CLI delegation already handles large diffs).
- `opencode`/`copilot`/`junie` stdin-pipe support — blocked on upstream CLI changes.
