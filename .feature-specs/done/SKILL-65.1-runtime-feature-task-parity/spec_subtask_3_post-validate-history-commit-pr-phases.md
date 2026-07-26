# SKILL-65.1 · Subtask 3 — Post-Validate Phases: History, Commit, PR

Parent: [SKILL-65.1 full parity](./spec.md)
Issue key: SKILL-65.1
Status: Draft

## Scope

Add the three terminal phases that `bill-feature-task` runs after `validate` and
the runtime is missing: `write_history` (Step 7, `bill-boundary-history`),
`commit_push` (Step 8), and `pr` (Step 9, `bill-pr-description` + `gh pr create`).
After this subtask a completed run ends with boundary history written, the work
committed and pushed on the feature branch from subtask 1, and a PR opened with
its URL recorded as durable phase output.

## Acceptance Criteria

1. `FeatureTaskRuntimePhaseWorkflowDefinition` declares `PHASE_WRITE_HISTORY =
   "write_history"`, `PHASE_COMMIT_PUSH = "commit_push"`, and `PHASE_PR = "pr"`
   appended after `validate`, each with `stepLabels`, `requiredArtifactsByStep`
   (real upstreams: history after `validate`; `commit_push` after the
   implemented + validated work; `pr` after `commit_push`), and `resumeActions`
   entries. `completedTerminalSummaryArtifact` moves to `PHASE_PR`.
2. `pr` declares the derived `diff` context (like `review`) so the agent authors
   an accurate PR body.
3. `FeatureTaskRuntimePhasePromptComposer.phaseDirectives` gains entries:
   - `write_history`: invoke `bill-boundary-history` inline and apply its
     write/skip rules; emit a `history_result` payload.
   - `commit_push`: stage and commit the implemented changes (issue-key commit
     message) on the feature branch, push it; emit `commit_push_result` with the
     commit SHA. Honors a `suppress_pr`/goal-continuation signal where the
     terminal success is `commit_push` (wiring completed in subtask 7).
   - `pr`: invoke `bill-pr-description`, run `gh pr create` (respecting a
     repo-native PR template), call `pr_description_generated`
     (`orchestrated=true`), and emit `produced_outputs` with PR URL/number,
     title, and `pr_created`.
4. No phase-output schema edit and no `FEATURE_TASK_RUNTIME_CONTRACT_VERSION`
   bump. A blocked/failed terminal phase (e.g. `gh` missing, push rejected, no
   commits, `gh pr create` fails) sets `status` to `blocked`/`failed` and blocks
   the run loudly with the offending phase id — no false completion.
5. PR creation is idempotent across resume: if the branch already has an open PR,
   the phase reports it rather than opening a second one; a completed `pr`/
   `commit_push` is skipped on resume from its durable record.
6. The runner, status service, CLI counts, and `--monitor` transitions include
   all three new phases; `FeatureTaskRuntimeRunnerTest`'s completed-phase
   assertion is updated to the full phase list and covers the terminal blocking
   path.

## Non-Goals

- No branch creation (subtask 1 owns it); this subtask assumes the feature branch
  from subtask 1 is checked out.
- No PR-suppression *policy* decision here beyond exposing the hook — subtask 7
  wires goal-driven suppression and the "commit_push is terminal" semantics.
- No change to `bill-boundary-history` or `bill-pr-description` themselves.

## Dependency Notes

- Depends on: subtask 1 (feature branch must exist to commit/push/PR).
- Downstream: subtask 6 (telemetry reports these phases), subtask 7 (goal
  cooperation drives `suppress_pr` and commit-as-terminal).

## Validation Strategy

- Update phase-definition, prompt-composer, runner, and status-service tests for
  the three new phases, labels, required-artifacts, terminal-summary artifact,
  and the derived `diff` on `pr`.
- Runner tests for the blocking paths (gh missing / push rejected / pr-create
  failure) and for idempotent resume (no duplicate PR).
- `(cd runtime-kotlin && ./gradlew check)` and `skill-bill validate` pass.

## Next path

Proceed to [subtask 4 — Size Assessment and Ceremony Scaling](./spec_subtask_4_size-assessment-and-ceremony-scaling.md),
or run `skill-bill goal SKILL-65.1`.
