# SKILL-65.1 · Subtask 1 — Runtime Run Setup and Feature Branch

Parent: [SKILL-65.1 full parity](./spec.md)
Issue key: SKILL-65.1
Status: Draft

## Scope

Add a runtime-owned run-setup step that guarantees a feature branch is checked
out before any file-mutating phase runs. Today the runtime has zero git/branch
handling: `FeatureTaskRuntimeRunner`, `FeatureTaskRuntimeCliCommands` (`run`),
and the phase directives never create or switch branches, so a run started on the
default branch edits the working tree on `main` (observed on a real SKILL-66
run). This subtask is the foundation for the commit/push/PR phases (subtask 3)
and the goal-runner branch cooperation (subtask 7).

This step is runtime-owned (a setup action and/or an explicit pre-`implement`
guarantee), not delegated to agent prose — porting the dropped
`git checkout -b feat/{ISSUE_KEY}-{feature-name}` instruction into the runtime is
the whole point.

## Acceptance Criteria

1. Before the `implement` phase runs, the runtime ensures a non-default feature
   branch is checked out, derived from the issue key
   (`feat/{ISSUE_KEY}-{feature-name}` by convention, matching
   `bill-feature-task/SKILL.md:139`).
2. If the run starts on the default branch (`main`/repo default), the runtime
   creates and switches to the feature branch. If it is already on a non-default
   branch, it reuses that branch (this is the hook subtask 7 relies on for
   goal-driven runs that pre-create the branch).
3. The runtime never edits, commits, or leaves changes on the default branch; if
   it cannot establish a feature branch it blocks the run loudly with an
   actionable reason rather than proceeding on the default branch.
4. The resolved branch is recorded in durable runtime state so resume
   re-attaches to the same branch and never creates a duplicate or divergent one.
5. Branch setup is idempotent across resume and re-run: re-running never
   force-switches away from a branch already holding the run's work and never
   creates a second branch for the same run.
6. The CLI `run`/`resume` surface and `--monitor` report the branch-setup
   outcome (resolved branch name) without requiring a new flag.

## Non-Goals

- No commit, push, or PR creation (subtask 3).
- No stacked-branch support; only single feature branch
  (`same_branch_commit_per_subtask` semantics).
- No change to how `bill-feature-task` names or creates branches.

## Dependency Notes

- Depends on: none.
- Downstream: subtask 3 (commit/push/PR need a feature branch), subtask 7
  (goal-runner runs hand the runtime a pre-created branch to reuse).

## Validation Strategy

- Unit tests for the run-setup branch resolver: starts-on-default → creates;
  starts-on-feature-branch → reuses; cannot-establish → blocks loudly; resume →
  re-attaches without duplication.
- A runner-level test asserting no file-mutating phase launches while on the
  default branch.
- `(cd runtime-kotlin && ./gradlew check)` and `skill-bill validate` pass.

## Next path

Proceed to [subtask 2 — Pre-Planning Phase](./spec_subtask_2_preplan-phase.md),
or run `skill-bill goal SKILL-65.1`.
