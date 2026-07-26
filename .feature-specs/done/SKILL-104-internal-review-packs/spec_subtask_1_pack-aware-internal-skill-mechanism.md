---
status: Complete
- Agent: zcode
---

# SKILL-104 Subtask 1 - Pack-aware internal-skill mechanism

Parent spec: [spec.md](spec.md)
Issue key: SKILL-104

Read the parent spec's **Pinned Decisions** before starting. PD1, PD2, PD3, PD6, and PD8 bind this subtask directly; PD4 constrains what must not change.

## Scope

Extend the SKILL-102 internal-skill mechanism so platform-pack skills can classify as internal and stage as selection-aware sidecars of a listed base-skill parent. Kotlin only. Fully inert at the end of this subtask: no repo skill opts in, and staged output for the current repo is byte-identical to before (parent criterion 3).

### Touch points (exact files)

- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/authoring/InternalSkillClassification.kt` — remove the base-skill-only violation (lines 35-37); keep every other rule byte-for-byte, including "parent must be a listed base skill" (lines 52-54) and depth-1 (lines 55-57). The `isBaseSkill` flag on declarations remains — it now feeds only the parent-side rule.
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/install/plan/InstallPlanSkillDiscovery.kt` — `platformSkills()` must parse `internal-for` from each pack skill's `content.md` (same parser: `parseInternalForFrontmatter`) and carry it on `InstallPlanSkill.internalFor`; `validateInstallPlanInternalSkills` evaluates pack skills with the relaxed rule.
- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/install/policy/InstallPlanPolicy.kt` — planned internal pack skills are excluded from standalone skill paths (mirror the existing `internalFor == null` filters) but only when their pack is selected do they surface as sidecar intents (PD3). Add the PD8 baseline co-presence guard here (plan-time): selection includes a pack whose manifest declares a required `code_review_composition.baseline_layers` entry in an unselected pack → loud-fail.
- `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/ShellContentContractErrors.kt` — new typed error for the PD8 guard (name it in the family style, e.g. `MissingBaselinePlatformSelectionError`), carrying the selecting slug, the required baseline slug, and the declaring manifest path.
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/install/staging/InternalSkillSidecars.kt` — sidecar discovery becomes source-aware: children of a parent are the union of (a) skills-root children with matching `internal-for` (today's scan) and (b) selected pack skills with matching `internal-for` from the plan (parent Design Notes: consult `InstallPlanSkill.sourceDir`, do not re-scan `platform-packs/`). Pack sidecar wrappers must render through the same wrapper path pack skills use today (full shell + generated composition/pointer sections — SKILL-102 PD6 parity).
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/install/staging/InstallStaging.kt` — hash folding (`computeInstallContentHash`, `--internal-sidecars--` section) includes pack sidecars only when selected; sidecar name exclusion (`authoredFilesFor`) covers pack child names.
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/install/staging/InstallStagingIO.kt` — `writeInternalSidecarFiles` collision guard and `isReusableInstallStaging` sidecar verification work with the selection-shaped sidecar set.
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/install/plan/InstallPlanBuilder.kt`, `.../install/apply/InstallApply.kt`, `.../install/plan/InstallPrimitives.kt` — the three existing `internalFor` filters (skill paths, standalone installables, link-skill refusal) already key off `InstallPlanSkill.internalFor`; verify they hold for pack skills and extend the link-skill refusal message test coverage to a pack skill.
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/install/apply/InstallApplyNativeAgents.kt` — no code change expected (PD6); add/extend a test proving a selected pack's `native-agents/agents.yaml` installs identically when the pack's skills are internal.
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/runtime/RepoValidationRuntime.kt` — validate-seam parity: classification over pack declarations with the relaxed rule; sidecar collision guard checks the parent's authored files against pack child names; `validateInternalSidecarReferences` scans pack `content.md` files and resolves effective parents across base+pack skills; `internalSkillNames` (README exclusion) collects pack content files too.
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/install/support/InstallLegacySkillNames.kt` — check upgrade behavior: a previously installed standalone dir for a newly-internal pack skill must be cleaned up by reconcile/replacement cleanup; add coverage if a gap exists (see `InstallApplyReplacementCleanupTest`).
- Tests: extend `InternalSkillClassificationTest`, `InstallPlanInternalSkillDiscoveryTest`, `InternalSkillStagingTest`, `RepoValidationRuntimeTest`, `InstallApplyTest`/`InstallApplyPlatformPackViewTest`; new policy tests for PD8 in the `InstallPlanPolicy` test surface.

### Out of scope for this subtask

- Any edit under `platform-packs/`, `skills/`, `orchestration/`, `docs/`, or `README.md`. No skill opts in here.
- Changing native-agent code paths (PD6 is verify-only).
- The quality-check family (PD7).

## Acceptance Criteria

1. `internal-for` on a platform-pack skill classifies as internal at all three seams; the removed violation has no remaining code path or test asserting it (PD1).
2. All preserved rules keep their exact behavior and messages, each covered by a test that includes at least one pack-skill variant: blank value, self parent, unknown parent, parent-is-pack-skill (still forbidden), chained internal-for (PD1).
3. With a synthetic repo fixture (pack skill opted in): selected pack → sidecar `<skill-name>.md` with the full pack wrapper inside the parent's staged dir, no standalone staging dir, no skills-dir link; unselected pack → no sidecar, no hash contribution (PD2, PD3).
4. Parent hash variance is proven by test: same repo, different `PlatformPackSelection` values → different parent hashes; editing the pack child's `content.md` → hash change; cache reuse re-verifies the selection-shaped sidecar set and re-renders when a sidecar is externally deleted (PD3).
5. Inertness: with the real repo (no opted-in skills), the full install plan and staged bytes are identical to pre-change — proven by test or by recorded staged-tree diff (parent criterion 3).
6. Direct link-skill of an internal pack skill refuses with the existing internal-skill error naming the parent (PD1).
7. Native-agent installation for a selected pack with internal skills is byte-identical to today (PD6).
8. PD8 guard: selecting KMP without Kotlin fails the plan with the new typed error naming both slugs; KMP+Kotlin and `ALL` pass; packs without baseline layers are unaffected.
9. `skill-bill validate` passes on the real repo, and each new validate rule has negative coverage in `RepoValidationRuntimeTest`.
10. Maintainer validation passes: `./gradlew check`, `skill-bill validate`, `agnix --strict`, `scripts/validate_agent_configs`.

## Non-Goals

Flipping any real skill to internal; prose or docs changes; auto-including baseline packs (PD8 is loud-fail only).

## Dependency Notes

None — first subtask. Subtasks 2 and 3 must not start until this merges.

## Validation Strategy

Unit tests per criterion above; the inertness proof (criterion 5) gates the merge. Run the full maintainer validation set at the end.

## Next Path

[Subtask 2 — Review-pack migration and call-site rewrite](spec_subtask_2_review-pack-migration.md)
