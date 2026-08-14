# SKILL-190 Subtask 6 — Ref lifecycle, documentation, and integrated verification

## Intended Outcome

Checkpoint refs are cleaned up once they can no longer be needed, the documentation describes the
delivered ceremony rather than the old one, and the whole bundle is verified end to end against a
real repository.

## Scope

- Prune `refs/skill-bill/checkpoints/<issue-key>/<subtask-id>/*` after that subtask's commit is
  pushed and its manifest entry records a non-blank `commit_sha`. Not before: an unpushed or
  unrecorded subtask may still need to roll back.
- Make pruning idempotent and resumable; an interrupted prune leaves a consistent state and the next
  attempt completes it.
- Prune refs for subtasks abandoned by a hard manifest reset
  (`DecompositionManifestWriter.kt:351` nulls every `commitSha`), so a reset does not strand refs
  forever.
- Update `AGENTS.md` to describe the one-commit-per-subtask ceremony, the runtime-owned finalisation,
  and the `refs/skill-bill/` namespace. `AGENTS.md:85` currently states commit prose style only and
  says nothing about commit structure.
- Update the workflow contract documentation in `orchestration/workflow-contract/PLAYBOOK.md`
  (`:295`, `:323`, `:334`) for the changed `commit_push` ownership.
- Update `skills/bill-feature-task-runtime/content.md` where it describes commit behaviour,
  including the guard note at `:376`.
- Run the integrated verification described below.

## Acceptance Criteria

1. Checkpoint refs for a subtask are deleted only after that subtask's commit is pushed and its
   manifest entry records a non-blank `commit_sha`.
2. Pruning is idempotent; running it twice succeeds, and an interrupted prune is completed by the
   next attempt without manual intervention.
3. A hard manifest reset prunes the refs of the subtasks it reset; no ref namespace grows without
   bound across repeated resets.
4. A blocked or abandoned subtask retains its refs, so it stays recoverable.
5. `AGENTS.md` describes the one-commit-per-subtask ceremony, runtime-owned finalisation, and the
   `refs/skill-bill/` namespace, and no longer implies checkpoint commits appear in branch history.
6. `PLAYBOOK.md` and `skills/bill-feature-task-runtime/content.md` describe the changed `commit_push`
   ownership, and no document instructs an agent to run `git commit` for a subtask.
7. A full feature-task run over a multi-subtask manifest, in a real temporary repository, produces
   exactly one branch commit per completed subtask, each with the correct trailer and outcome
   message.
8. `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, and `scripts/validate_agent_configs`
   all pass.
9. No document or code comment claims behaviour this bundle did not deliver.

## Non-Goals

- Garbage-collecting refs created by other tools or namespaces.
- A repository-wide documentation rewrite; only the surfaces this bundle changed.
- Retroactive cleanup of existing branches in any repository, including
  `feat/SKILL-16-runtime-production-hardening`.
- Any coverage target.

## Dependency Notes

Depends on subtasks 1 through 5. This is the closing subtask.

Note the known pre-existing failure: `./gradlew build` dies at `:runtime-infra-fs:sourcesJar` on
clean `main`. Verify with `-x sourcesJar` if that path is used, and do not attribute the failure to
this bundle.

## Validation Strategy

The integrated run is the point of this subtask. Drive a real multi-subtask manifest through a
temporary repository and assert on git state, not on log strings:

- One branch commit per completed subtask, each carrying its trailer and outcome message.
- Checkpoint refs present during a subtask, absent after it is pushed and recorded.
- A blocked subtask retains its refs and remains recoverable.
- A hard reset prunes the refs it orphaned.
- `.feature-specs/` unstaged throughout.
- The SKILL-189 review-base scenario still resolves a non-empty base, verifying subtask 4 under the
  full pipeline rather than in isolation.

Close with `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, and
`scripts/validate_agent_configs`.

## Next Path

Bundle complete. The `feat/SKILL-190-one-commit-per-subtask` branch is ready for PR via
`bill-pr-description`.
