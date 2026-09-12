# Issue 340 · Subtask 1 — File-level citation line normalization

## Scope

Harden review finding citation ingestion so file-level citations that report
`line: 0` are legitimate input, not a fatal parse error. At the agent-output
boundary (`ReviewFindingFieldCodec` and callers), coerce non-positive numeric
line values to `1` before constructing `ReviewFindingCitation`, while keeping
existing repository-relative path validation and per-finding degradation for
truly malformed citation entries.

Ensure no code path still passes `line: 0` into `ReviewFindingCitation` and
throws `IllegalArgumentException: Finding citation line must be a positive
integer`, including checkpointed review workflow resumes. Where issue 333
already rejects non-positive lines as diagnostics, align behavior so file-level
citations survive ingestion and review accounting continues.

## Acceptance Criteria

1. `line: 0` (number or numeric string) on a repository-relative citation path
   ingests as line `1` without throwing.
2. Missing, non-numeric, or blank line values continue to degrade at the
   per-citation boundary with a diagnostic rather than aborting the review pass.
3. Valid positive-line citations and otherwise usable findings in the same
   result are unchanged.
4. Regression tests cover `0`, positive integers, numeric strings, missing
   lines, and mixed valid/invalid citations at the codec boundary.
5. No review ingestion path constructs `ReviewFindingCitation` with `line < 1`.
6. Focused domain and application tests pass for the touched review paths.

## Non-Goals

- Changing review prompts to always emit explicit line numbers.
- Accepting blank paths or paths outside the repository.
- Reworking unrelated review telemetry or operator controls.

## Dependency Notes

- None. Runs from `main` as the first subtask.

## Validation Strategy

1. Run `ReviewFindingFieldCodecTest` and related review ingestion tests.
2. Run focused `runtime-application` claim verification parsing tests if
   touched.
3. Run the dominant-stack quality check for changed Kotlin modules.

## Next Path

After this subtask commits, subtask 2 fixes `goal repair --apply` database
contention and operator escalation messaging.
