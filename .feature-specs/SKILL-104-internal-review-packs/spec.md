# SKILL-104 - Internal review packs: one listed code-review entry point

Created: 2026-07-05
Status: Complete
- Agent: claude
Issue key: SKILL-104
Parent: none (continues the SKILL-102 internal-skills theme)

## Decomposition

Three dependency-ordered subtasks, mirroring the SKILL-102 shape (mechanism → migration → surface):

1. The install/authoring/validate mechanism must learn to classify and stage platform-pack skills as internal **before** any pack skill opts in, and it must stay inert until one does. This is Kotlin-only work with its own test surface.
2. The migration flips all 34 review-pack skills to internal and rewrites every call site to the sidecar file-read contract. This is prose/frontmatter work plus (at most) the composition-instruction renderer, and it is only safe once subtask 1 is merged.
3. Docs, user-facing surface, end-to-end verification, and records depend on the final shape produced by subtasks 1–2.

- [Subtask 1 — Pack-aware internal-skill mechanism](spec_subtask_1_pack-aware-internal-skill-mechanism.md)
- [Subtask 2 — Review-pack migration and call-site rewrite](spec_subtask_2_review-pack-migration.md)
- [Subtask 3 — Docs, surface reconciliation, verification, records](spec_subtask_3_docs-surface-verification.md)

## Pinned Decisions (binding for all subtasks)

Do not re-decide, redesign, or "improve" these. They were settled during spec preparation with full knowledge of the SKILL-102 mechanism. If one turns out to be impossible during implementation, stop and surface the conflict instead of silently deviating.

**PD1 — Same mechanism, one relaxed rule.** Internal classification stays exactly the SKILL-102 mechanism: the `internal-for: <parent-skill-name>` key in `content.md` frontmatter, evaluated by the single shared evaluator (`InternalSkillClassification.kt`) at all three seams (authoring discovery, install-plan discovery, `skill-bill validate`). The ONLY rule that changes is the base-skill-only restriction (`InternalSkillClassification.kt:35-37`): platform-pack skills may now declare `internal-for`. Every other rule keeps its exact behavior and message: missing/empty value, self parent, unknown parent, **parent must be a listed base skill under `skills/`** (a pack skill can never be a parent), and **depth is 1** (chained `internal-for` stays forbidden). Do not fork a second classification path or add a manifest-level internality flag; the pack manifest (`platform.yaml`) is not touched by classification.

**PD2 — Flatten, do not nest.** All 34 review-pack skills — the 4 stack entry skills AND their 30 specialists — declare `internal-for: bill-code-review`. Stack entry skills do NOT become parents of their specialists. Every sidecar is a sibling: `bill-code-review/`'s installed directory contains `SKILL.md` plus up to 34 `<skill-name>.md` sidecars. This keeps depth at 1 (PD1), and co-location is exactly what the review flow needs — a stack orchestrator sidecar reads its specialist rubric sidecars as siblings in the same directory.

**PD3 — Selection-aware sidecars.** A pack skill renders a sidecar into its parent's staged directory only when its pack is selected (`PlatformPackSelection`: `NONE`/`SELECTED`/`ALL`). The parent's content hash folds only the selected sidecars, so changing platform selection re-stages `bill-code-review` and cache reuse stays correct. With `NONE` selected, `bill-code-review` stages byte-identically to a repo with no internal pack skills. Unselected packs contribute nothing — no sidecar, no hash bytes.

**PD4 — Frozen identities and paths.** Skill names, the `routed_skill = bill-<slug>-code-review` routing contract (`orchestration/stack-routing/PLAYBOOK.md`), telemetry payload values, native-agent names, and the repo source layout under `platform-packs/` are all unchanged. Only the installed listing surface changes: no standalone skills-dir entries, no slash commands for the 34. Specialist-selection tables inside stack orchestrators (e.g. the Step 3 signal table in `bill-kotlin-code-review/content.md`) keep naming specialists by skill name — those names are identity strings for subagent spawning and sidecar resolution, not Skill-tool call sites.

**PD5 — File-read invocation contract extends to review routing.** The SKILL-102 PD5 contract applies verbatim: the Skill tool resolves only listed skills, so anything that previously resolved a review-pack skill standalone now reads the sibling sidecar file `<skill-name>.md` inside `bill-code-review`'s installed directory. Concretely: routed dispatch resolves the dominant pack's entry sidecar; stack orchestrators resolve specialist rubric files as sibling sidecars; the KMP baseline layer resolves `bill-kotlin-code-review.md` as a sibling sidecar. Delegated workers keep receiving the rendered runtime instructions and rubric content/paths from the parent (as `orchestration/review-delegation/PLAYBOOK.md` already requires); no worker may resolve a hidden skill via the Skill tool or a standalone skills-dir path. Lane-2 parallel reviews keep invoking `/bill-code-review` (still listed).

**PD6 — Native agents unaffected.** The `native-agents/agents.yaml` bundles under each pack's entry skill keep installing for selected packs and selected agents exactly as today. `InstallApplyNativeAgents.nativeAgentSourceRoots` already enumerates internal skills deliberately ("Do not add an internalFor filter", `InstallApplyNativeAgents.kt:182-190`) — preserve that. Specialist reviewers remain available as native subagents (`.claude/agents/bill-*-code-review-*.md` and per-provider equivalents) even though their skills are hidden.

**PD7 — The exact hidden set.** Hidden (34): every skill under `platform-packs/{ios,kotlin,kmp,php}/code-review/` — iOS entry + 10 specialists, Kotlin entry + 8 specialists, KMP entry + 2 specialists, PHP entry + 10 specialists. Listed and unchanged: `bill-code-review`, `bill-code-review-parallel`, `bill-code-check`, and the pack quality-check skills (`bill-ios-code-check`, `bill-kotlin-code-check`, `bill-php-code-check`). Internal-izing the quality-check pack skills is an explicit non-goal (natural follow-up issue).

**PD8 — Baseline co-presence guard.** `bill-kmp-code-review` declares `bill-kotlin-code-review` as a required baseline layer (`platform-packs/kmp/platform.yaml`, `code_review_composition.baseline_layers`). Once both are sidecars, selecting KMP without Kotlin would leave the baseline sidecar missing at review time. Install planning must loud-fail (new typed error in `ShellContentContractErrors.kt`) when the selection includes a pack whose manifest declares a required baseline layer in an unselected pack. No silent auto-include — consistent with the shell's "never silently substitute" ethos. `ALL` selection is trivially safe. Today's behavior (selecting KMP alone silently installs a review whose baseline skill is absent) is a pre-existing gap that this feature makes load-bearing, which is why the guard is pinned here and not deferred.

## Sources

- `.feature-specs/SKILL-102-internal-skills/spec.md` — the mechanism this feature extends; its PD1–PD7 remain in force for base skills.
- `docs/skill-source-generation.md` (`## Internal Skills`) — normative internal-skill contract.
- `docs/internal-skills-architecture.md` — architecture companion; routing walkthrough pattern to replicate for the review family.
- `runtime-kotlin/runtime-infra-fs/.../scaffold/authoring/InternalSkillClassification.kt` — shared evaluator; the relaxed rule lives at lines 35-37, parent rules at 50-57.
- `runtime-kotlin/runtime-infra-fs/.../install/plan/InstallPlanSkillDiscovery.kt` — `platformSkills()` materialization; `validateInstallPlanInternalSkills`.
- `runtime-kotlin/runtime-infra-fs/.../install/staging/{InternalSkillSidecars,InstallStaging,InstallStagingIO}.kt` — sidecar discovery, hash folding, cache reuse.
- `runtime-kotlin/runtime-domain/.../install/policy/InstallPlanPolicy.kt` — pack selection semantics (`selectedPlatformSlugs`, line 256).
- `runtime-kotlin/runtime-infra-fs/.../scaffold/runtime/RepoValidationRuntime.kt` — validate-side rules (classification 773-794, README exclusion 452-481, sidecar references 802-824, collision guard 625-648).
- `orchestration/stack-routing/PLAYBOOK.md`, `orchestration/review-delegation/PLAYBOOK.md`, `orchestration/review-orchestrator/` — the routing and delegation contracts whose call sites subtask 2 rewrites.
- `platform-packs/*/platform.yaml` — pack manifests; KMP baseline layer at `platform-packs/kmp/platform.yaml`.

## Problem

SKILL-102 established that skills which are only ever reached through a governed entry point should not be listed, and proved the mechanism on the feature-execution family (five skills hidden behind `bill-feature`). The code-review family has the same disease at ~7× the size: 34 stack-specific review skills are listed to users, yet the supported entry point is `/bill-code-review`, which detects the dominant stack from `platform.yaml` routing signals and routes automatically. Users never need `/bill-php-code-review-ux-accessibility` as a slash command; its being listed pollutes every agent's skill list (34 of the ~50 listed skills are review-pack internals), dilutes trigger matching, and misrepresents the product surface ("opinionated autonomous system", not a menu of 50 knobs).

The SKILL-102 mechanism cannot absorb them as-is: it deliberately loud-fails `internal-for` on platform-pack skills, and pack skills are discovered, selected, staged, and hashed through a different pipeline (`platform.yaml`-declared, selection-gated) than base skills (directory-scanned, always installed).

## Goals

- Only `bill-code-review` (plus the already-listed `bill-code-review-parallel` and `bill-code-check`) remains visible for code review; all 34 stack review skills become internal sidecars of `bill-code-review`.
- One classification mechanism for base and pack skills (PD1); no forked logic.
- Pack selection continues to govern what is installed — hidden skills from unselected packs do not ship (PD3).
- The full review pipeline (routing, specialist spawning, KMP baseline layering, delegated execution, parallel lanes, telemetry, learnings) behaves identically after the migration.

## Non-Goals

- Internal-izing the quality-check pack skills (`bill-*-code-check`) or any other family (PD7).
- Nested internal skills / depth > 1 (PD1, PD2).
- Moving any file under `platform-packs/` or renaming any skill (PD4).
- Changing native-agent installation (PD6).
- Changing routing signals, tie-breakers, or the `routed_skill` contract (PD4).
- Auto-including baseline packs on selection (PD8 chooses loud-fail).

## Target User Experience

- `skill-bill install` with packs selected: the agent's skill list shows `bill-code-review` but none of the 34 stack review skills. `~/.claude/skills/bill-code-review/` contains `SKILL.md` plus one `<skill-name>.md` sidecar per selected-pack review skill.
- `/bill-code-review` behaves exactly as today from the user's point of view: detects the stack, runs the routed pack's orchestration, spawns specialist subagents, emits the same output format and telemetry.
- A user who types `/bill-kotlin-code-review` gets nothing (no such command) — the README and docs direct them to `/bill-code-review`.
- `skill-bill install --skills bill-kotlin-code-review-security` (direct link) refuses with the existing internal-skill error, naming the parent to install instead.
- Selecting the KMP pack without the Kotlin pack fails the install plan with a specific error naming the missing baseline pack (PD8).
- `skill-bill list` (authoring view) still shows all skills — intentionally unchanged (SKILL-102 precedent).

## Acceptance Criteria

1. The shared evaluator accepts `internal-for` on platform-pack skills and enforces all remaining rules unchanged (blank value, self parent, unknown parent, parent must be a listed base skill, no chained internal-for), with updated tests per rule at all three seams (PD1).
2. A selected pack's review skill stages as a `<skill-name>.md` sidecar with the full governed wrapper at the top level of `bill-code-review`'s staged directory; an unselected pack's review skill stages nothing (PD2, PD3).
3. `bill-code-review`'s content hash folds exactly the selected sidecars: changing platform selection or editing a selected child's `content.md` invalidates cache reuse; a repo with no internal pack skills produces byte-identical staging to before subtask 1 (inertness) (PD3).
4. After a scratch install with all packs selected, no standalone directory or agent skills-dir link exists for any of the 34 skills, and all 34 sidecars are present inside `bill-code-review/`; with only the Kotlin pack selected, exactly the 9 Kotlin sidecars are present (PD2, PD3).
5. Direct `link-skill`/single-skill install of any of the 34 refuses with the existing internal-skill error (PD1).
6. Native agents for selected packs install unchanged — same agent names, same per-provider artifacts, byte-identical agent bodies (PD6).
7. Every call site that resolved a review-pack skill standalone is rewritten to the sidecar file-read contract: routed dispatch to the pack entry, specialist rubric reads, and the KMP baseline read; no shipped prose or generated instruction tells any runtime to resolve one of the 34 via the Skill tool or a standalone skills-dir path (PD5).
8. `routed_skill` values, telemetry payloads, native-agent names, specialist-selection tables, and all `platform-packs/` paths are unchanged (PD4).
9. Install planning loud-fails with a new typed error when the selection includes a pack that declares a required baseline layer in an unselected pack, and passes when both packs (or `ALL`) are selected (PD8).
10. `skill-bill validate` rules cover pack internal skills: classification at the validate seam, sidecar collision guard against `bill-code-review`'s authored files, sidecar file-reference validation across pack content files, and README-catalog exclusion for internal pack skills (PD1).
11. README, getting-started docs, and any other user-facing surface no longer present the 34 as directly invocable; `docs/skill-source-generation.md` and `docs/internal-skills-architecture.md` document the pack extension (selection-aware sidecars, flattening, PD8 guard) with the review family as the worked example.
12. E2e install-layout verification evidence is captured from a from-source scratch install for criterion 4's both selection shapes; interactive routed-review dispatch checks are run on Claude Code, or recorded honestly as deferred with what remains outstanding.
13. Boundary decision recorded in `runtime-kotlin/agent/decisions.md` (flatten-vs-nest, selection-aware hashing, PD8 loud-fail) and feature history in `runtime-kotlin/agent/history.md`; parent and subtask specs plus the decomposition manifest reconciled to final state.
14. Maintainer validation passes at every subtask boundary: `./gradlew check`, `skill-bill validate` (0 issues), `agnix --strict` (0 errors), `scripts/validate_agent_configs`.

## Design Notes

- **Why flatten instead of nest (PD2).** Nesting (specialists internal to their stack entry, entries internal to `bill-code-review`) reads naturally but requires depth-2 sidecars — a sidecar hosting sidecars — which SKILL-102 explicitly forbade and the staging model cannot express (a sidecar is a file, not a directory). Flattening keeps the mechanism, and sibling co-location is what the runtime flow actually wants: the routed entry sidecar and the specialist rubrics it reads live in one directory.
- **Why frontmatter, not a manifest flag.** A `platform.yaml`-level "internal" flag would create a second classification source and desynchronize the three seams; PD1's whole point is one evaluator. The 34 one-line frontmatter additions are mechanical and grep-auditable.
- **Sidecar discovery must become source-aware.** `discoverInternalSidecarTargets` currently scans the skills root one level deep. For pack children it must instead consult the plan's selected pack skills (which already carry `sourceDir` and parsed `internalFor` via `InstallPlanSkill`) — do not re-scan `platform-packs/` independently of selection.
- **The rendered wrapper for pack sidecars** must be the same full governed wrapper pack skills get today (shell + content + generated composition/pointer sections), not a trimmed body — SKILL-102 PD6 (parity over token savings) applies. Verify the render path used for pack skills (`renderWrapper` equivalent for platform skills) is reused, not reimplemented.
- **Delegation prose is nearly ready.** `orchestration/review-delegation/PLAYBOOK.md` already says workers "read the delegated skill file" — subtask 2 mostly re-points *which file* (sibling sidecar) rather than changing the delegation model. The generated Review Composition instructions (KMP baseline) come from the Kotlin renderer, so that rewrite may land in renderer text rather than authored prose — inventory before editing.
- **README exclusion nuance.** `validateReadme` computes `internalSkillNames` from base-skill files today; extend the collection to pack content files so validate does not demand catalog rows for hidden pack skills. (Earlier grep suggests the README already avoids per-skill pack rows — verify and reconcile whichever way the README actually reads.)
- **`InstallLegacySkillNames.kt`** mentions pack skill names — check whether legacy-name cleanup interacts with the new sidecar layout (a previously-installed standalone `bill-kotlin-code-review/` dir must be removed on upgrade, or reconcile handles it as an orphan).

## Validation Strategy

- Subtask 1: unit tests per relaxed/preserved rule at all three seams; staging tests for selection-shaped sidecar sets, hash variance across selections, cache reuse and re-render, link-skill refusal, native-agent parity, uninstall idempotency; explicit inertness test (no opted-in repo skill → byte-identical staged output). `./gradlew check` green.
- Subtask 2: `skill-bill validate` green with all 34 opted in; grep sweep proving no Skill-tool or standalone-path resolution of the 34 remains in shipped prose or rendered wrappers (sweep rendered staging output, not just sources — the SKILL-102 runtime-sidecar miss happened because the sweep matched phrasing too narrowly); full maintainer validation set.
- Subtask 3: from-source scratch install evidence for both selection shapes of criterion 4; PD8 negative and positive plan runs; interactive `/bill-code-review` route-and-review on Claude Code (or honest deferral); full maintainer validation set.

## Open Questions

- Should a follow-up issue internal-ize the quality-check pack skills (`bill-*-code-check`) under `bill-code-check` once this lands? (Out of scope here per PD7; expected answer is yes — file it during subtask 3 records if the maintainer agrees.)

**Resolution (subtask 3):** Expected answer confirmed yes. No `gh issue create` authorization was provided to the implementing agent, so the follow-up is recorded as a recommended action in `runtime-kotlin/agent/history.md` (SKILL-104 entry) rather than filed as a tracker issue. The maintainer should file it when convenient.

## Completion Corrections

(Filled at completion — reconcile any deviations from the pinned decisions here.)

- **Criterion 12 interactive routed review (deferred).** The interactive `/bill-code-review` routed review on Claude Code — exercising specialist spawn and rubric sidecar reads end-to-end — was not drivable from this implementing session (no interactive Claude Code harness available). All four automated install-layout and PD8 plan checks were captured with from-source CLI evidence: all-packs (34 sidecars in `bill-code-review-46700afff027524b/`, zero standalone links for the 34), kotlin-only (9 sidecars in `bill-code-review-8793b8fdc5d85fa7/`, hash variance proves selection-aware hashing), PD8 negative (`MissingBaselinePlatformSelectionError` on KMP-without-Kotlin), PD8 positive (KMP+Kotlin plan succeeds). The interactive check remains outstanding and should be run on a real agent harness.
- **Validation gate.** `./gradlew check` reports 1 failure in `CliFeatureTaskRuntimeRuntimeTest > feature-task-runtime run requires issue key and spec path()` — the zcode harness env causes the runtime-refusal path to short-circuit before the arg-validation assertion. This is the same pre-existing environmental failure documented in subtasks 1–2; subtask 3 touched no Kotlin source, so it is not a regression. `skill-bill validate` (run against the freshly built from-source runtime), `agnix --strict`, and `scripts/validate_agent_configs` all pass clean.
- **Packaged `skill-bill` on PATH.** The installed launcher at `~/.local/bin/skill-bill` is a pre-subtask-1 snapshot and still rejects `internal-for` on pack skills; from-source verification used the freshly built `runtime-kotlin/runtime-cli/build/install/runtime-cli/bin/runtime-cli`. A `./install.sh --from-source` (or `skill-bill update` once released) refreshes the packaged launcher.
