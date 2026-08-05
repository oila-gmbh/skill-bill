# SKILL-139 · Subtask 1: drop redundant staged content.md and the ceremony sibling-body directive

## Scope

Single implementation pass covering listed skills, internal sidecars, the
ceremony contract, docs, and tests.

1. **Staging copy** — stop copying `content.md` into the installed staging dir
   for listed (content-managed) skills, while keeping source `content.md` in the
   install content-hash inputs.
   - Touch points: `InstallStaging.kt` (`authoredFilesFor`, `stageInstalledSkill`
     name sets), `InstallStagingIO.kt` (`copyAuthoredIntoStaging`),
     `InstallContentHash.kt` (`computeInstallContentHash` must keep reading source
     `content.md`), and the reconcile/intent name-set builders
     (`InstallReconcilePolicy.kt`, `InstallStagingIntentBuilder.kt`,
     `InstallApplyStaging.kt`) so `authoredStagingNames` and the reuse integrity
     set stay consistent with the new staged layout.
2. **Internal sidecars** — audit `InternalSkillSidecars.kt` /
   `InstallStagingIO.kt` sidecar staging for any redundant verbatim `content.md`
   copy inside a parent's staging dir and remove it. Keep each rendered
   `<skill-name>.md` wrapper full and self-contained (PD6).
3. **Ceremony** — remove the sibling-`content.md` body directive from
   `orchestration/shell-content-contract/shell-ceremony.md` and confirm no
   rendered ceremony/support pointer reproduces it.
4. **Docs** — update `docs/skill-source-generation.md` install-staging contents
   list and any `AGENTS.md` / shell-content-contract wording implying staged
   `content.md` is a runtime body source.
5. **Tests/fixtures** — update and extend coverage per the acceptance criteria.

## Acceptance Criteria (this subtask)

1. A listed skill's installed staging dir contains `SKILL.md`, `.content-hash`,
   generated pointers, and any `native-agents/` output, but not a verbatim
   `content.md` copy.
2. Rendered `SKILL.md` still contains the full inlined `## Execution` body
   (SKILL-41 shape unchanged; render snapshots updated only where the staged
   file listing changed, not the SKILL.md body).
3. The install content hash still incorporates source `content.md`; editing
   source `content.md` changes the hash and forces a re-stage.
4. Internal sidecar staging carries no redundant verbatim `content.md`, and each
   rendered sidecar wrapper remains full and self-contained.
5. `shell-ceremony.md` no longer tells agents to read a sibling `content.md` as
   the authored body; no rendered pointer reproduces that directive.
6. `skill-bill show`, `explain`, `render`, `validate`, `fill`, `edit`, and
   native-agent composition still read source `content.md` and behave
   identically.
7. `docs/skill-source-generation.md` and `AGENTS.md` reflect that listed-skill
   staging no longer ships `content.md`.
8. `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, and
   `scripts/validate_agent_configs` all pass.

## Non-goals

- No revert of SKILL-41; no SKILL-31 thin-pointer `## Execution`.
- No changes to source `content.md` files or the authored-source contract.
- No changes to native-agent install locations or composition semantics.
- No trimming of the rendered internal sidecar wrapper format.
- No change to install/pack selection scope.

## Dependency notes

None. This is the only subtask.

## Validation strategy

- Unit: assert staged file set excludes `content.md` for a content-managed skill;
  assert `SKILL.md` still contains the inlined body; assert content hash changes
  when source `content.md` changes; assert internal sidecar staging has no
  redundant `content.md`.
- Contract: update reuse/reconcile expectations and render/staging snapshots.
- Gates: `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`,
  `scripts/validate_agent_configs`.
- Manual: install a pack, inspect `~/.skill-bill/installed-skills/<skill>-<hash>/`
  and confirm no `content.md`, `SKILL.md` present with inlined `## Execution`.

## Next path

Final subtask. On completion, run the validation gates and proceed to PR
description.
