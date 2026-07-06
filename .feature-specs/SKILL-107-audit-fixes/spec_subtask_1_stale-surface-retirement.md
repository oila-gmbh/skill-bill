# SKILL-107 Subtask 1: Stale-Surface Retirement

**Parent:** [spec.md](spec.md)
**Depends on:** none (run first)
**Covers:** audit finding 5 (code portion + owned doc lines) and the mid-planning correction (retire residual `feature-implement` identifiers).

## Context

`feature-implement` is a retired family name; the current family is `feature-task`. The repo still carries the old name in orchestration, scaffold runtime, CLI, and docs. Separately, the `skills/kotlin/` and `skills/kmp/` pre-shell directories were deleted long ago but code and docs still claim they exist.

Default direction: retire the old name repo-wide. Keep a compat alias ONLY where a durable record, installed artifact, or legacy-migration map references the old string — and record why.

## Scope

### A. Rename now (repo-local, no durable state behind them)

- `orchestration/skill-classes/feature-implement.yaml` → `orchestration/skill-classes/feature-task.yaml`, with `class: feature-task`. Matchers already target `^bill-[a-z0-9-]*feature-task$` and stay unchanged. Update every loader/test fixture that references the old filename or class id, including `runtime-kotlin/runtime-infra-fs/src/test/kotlin/skillbill/scaffold/ScaffoldManifestEditsRemoveTest.kt` and `runtime-kotlin/runtime-infra-fs/src/test/kotlin/skillbill/skillremove/SkillRemoveJvmFileSystemTest.kt`.
- `PRE_SHELL_FAMILIES` in `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/runtime/ScaffoldSupport.kt:39` → `setOf("feature-task", "feature-verify")`, plus the `UnknownPreShellFamilyError` catalog and `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md` mentions.
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/authoring/AuthoringContentMutation.kt:83` slug mapping keys on `feature-task` (drop the `feature-implement` suffix branch or keep it only as an input alias mapping to the `feature-task` class).
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/rendering/ScaffoldTemplateRendering.kt:36` label branch keys on `feature-task`.
- Kotlin type/test names that are purely internal identifiers (e.g. `FeatureImplementWorkflowRuntime`, `FeatureImplementTelemetryValidator`) rename to `FeatureTask*` — types are safe to rename; the durable STRINGS inside them are governed by section B.
- `AGENTS.md` line 143: rewrite "feature-implement or feature-verify overrides" to name `feature-task`, and reconcile with the pre-shell reality from section C.
- Desktop test fixtures asserting `preShellFamilies` contents (`JvmRuntimeScaffoldGatewayTest.kt:289`, `SkillBillViewModelScaffoldTest.kt:47`).

### B. Keep as recorded compat aliases (assess each; do NOT break)

- Durable workflow family name `"feature-implement-workflow"` (`FeatureImplementWorkflowRuntime.kt:9`, `RuntimeSurfaceContractTest.kt:93`): existing workflow DB rows reference this string. Renaming it orphans durable state silently (worse than a loud fail). Keep the string; the Kotlin type may still rename.
- `wfi-` session-id prefix and its description in `orchestration/contracts/workflow-state-schema.yaml:288`: durable format. Keep the prefix; update the description wording to "feature-task (session prefix `wfi-`, retained from the retired feature-implement name)".
- Legacy-migration maps that BY DEFINITION map old → new: `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/install/support/InstallLegacySkillNames.kt:25-27` and `uninstall.sh:354-356`. Unchanged.
- CLI alias table `"feature-implement-stats"` in `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/core/SkillBillCommand.kt:35`: keep as a legacy alias if removing it breaks operator muscle memory or scripts; otherwise remove. Assess and document the choice in the code structure (no comments explaining what).
- MCP golden fixture `mcp-feature-implement-workflow.json`: rename only if the golden's observable payload strings do not include the durable workflow name; otherwise keep.
- `FeatureImplementTelemetryValidator` session-id pattern and error text `"session_id must be a feature-implement session id."`: if the accepted session-id FORMAT is durable (`wfi-…`), keep the format, rename the message to say feature-task while still describing the `wfi-` shape.

### C. Pre-shell dead paths (finding 5)

- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/domain/skillremove/SkillRemove.kt:92-93`: remove resolution of nonexistent `skills/kotlin` / `skills/kmp` directories.
- `SHIPPED_HORIZONTAL_SKILLS = setOf("kotlin", "kmp")` and `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/domain/skillremove/model/SkillRemovalTarget.kt`: prune or re-anchor the HorizontalSkill axis so it no longer protects deleted directories, WITHOUT breaking the `--allow-shipped` removal contract for shipped platform packs.
- `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/skillremove/RemoveCliCommand.kt:39,70,138` help text: reflect the pruned model.
- `AGENTS.md` taxonomy line 26 (`skills/<platform>/` legacy overrides) and protection line 75 (`skills/kotlin/` and `skills/kmp/` protected): rewrite to match reality — the directories are gone; packs `platform-packs/kotlin` and `platform-packs/kmp` remain user-removable via `--allow-shipped`.

## Acceptance criteria

1. `orchestration/skill-classes/feature-task.yaml` exists with `class: feature-task`; no file named `feature-implement.yaml` remains under `orchestration/skill-classes/`; skill-class discovery resolves `bill-feature-task` to it and pointer/ceremony output is byte-identical to before the rename (acceptance test).
2. `PRE_SHELL_FAMILIES` contains exactly `feature-task` and `feature-verify`; a scaffold payload declaring family `feature-implement` fails loudly with a typed error whose message names `feature-task` as the replacement (rejection test).
3. `grep -rn "feature-implement" --include='*.kt' --include='*.yaml' --include='*.md' --include='*.sh' .` (excluding `.feature-specs/` and `.git/`) returns ONLY the recorded compat aliases from section B: the durable workflow name string, the `wfi-` schema description, `InstallLegacySkillNames.kt` entries, `uninstall.sh` migration entries, plus any alias the subtask explicitly retains with rationale.
4. Existing feature-task workflow, telemetry, MCP, and CLI test suites pass unchanged in observable behavior; any golden fixture rename preserves payload content.
5. `--allow-shipped` removal of a shipped platform pack still succeeds and its acceptance/rejection tests pass; no code path resolves `skills/kotlin` or `skills/kmp`.
6. `AGENTS.md` lines formerly claiming `skills/kotlin/`/`skills/kmp/` exist (taxonomy, protection, Adding Platforms) are rewritten to the post-pruning reality and no longer mention `feature-implement`.
7. All four validators pass: `skill-bill validate`, `scripts/validate_agent_configs`, `npx --yes agnix --strict .`, `(cd runtime-kotlin && ./gradlew check)`.

## Non-goals

- No data migration of workflow DB rows.
- No changes to `addon_usage`/schema (subtask 2).
- No README/capabilities catalog edits (subtask 6).
- Historical `.feature-specs/` documents are immutable; do not rewrite them.

## Validation strategy

```bash
skill-bill validate
scripts/validate_agent_configs
npx --yes agnix --strict .
(cd runtime-kotlin && ./gradlew check)
grep -rn "feature-implement" --include='*.kt' --include='*.yaml' --include='*.md' --include='*.sh' . | grep -v '.feature-specs/' | grep -v '.git/'
```

## Risk notes

- Highest-risk subtask: touches durable-state seams. The bright line is strings-in-durable-records stay, Kotlin identifiers and repo-local files rename. When in doubt for a specific string, keep the alias and record it against criterion 3.
- `ScaffoldTemplateRendering` label branches may render into generated `SKILL.md` shells; run `./install.sh` after the change so staging hashes update, and diff a rendered feature-task shell before/after.

## Handoff

Run bill-feature-task on `.feature-specs/SKILL-107-audit-fixes/spec_subtask_1_stale-surface-retirement.md`.
