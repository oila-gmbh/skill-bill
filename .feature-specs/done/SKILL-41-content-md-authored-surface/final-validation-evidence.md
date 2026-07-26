# SKILL-41 Final Validation Evidence

Subtask: `spec_subtask_4_final-validation.md`
Parent spec: `spec.md`

## Parent Acceptance Criteria Evidence

| # | Parent acceptance criterion | Implementation evidence | Validation evidence |
|---|---|---|---|
| 1 | Every governed `content.md` in the repo contains authored skill content only; no `## Descriptor`, `## Execution`, or `## Ceremony` wrapper sections remain. | `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/scaffold/AuthoredContentValidator.kt` rejects exact generated wrapper headings and self-referential content pointers outside fenced code. `orchestration/shell-content-contract/PLAYBOOK.md`, `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`, and `AGENTS.md` document `content.md` as authored source. | `rg -n '^## (Descriptor\|Execution\|Ceremony)\s*$\|Follow the shell ceremony in \[shell-ceremony\.md\]\|Follow the instructions in \[content\.md\]\(content\.md\)' --glob '*content.md'` returned no matches. Final gates recorded below. |
| 2 | Generated `SKILL.md` output still contains the governed wrapper shape: Descriptor, Execution, and Ceremony. | `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/scaffold/AuthoringRenderOutputTest.kt` asserts rendered wrappers include all three governed H2 sections without writing source `SKILL.md`. Snapshot tests cover standalone and platform-pack output in `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/scaffold/AuthoringRenderSnapshotTest.kt`. | Fresh render spot checks for `bill-code-review` and `bill-kmp-code-review-ui` are recorded below. |
| 3 | `ShellContentLoader` accepts clean authored `content.md` and loud-fails invalid/missing authored content. | `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/scaffold/ShellContentLoader.kt` validates manifest-declared `content.md` files through `validateGovernedSkill`; `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/scaffold/ShellContentLoaderParityTest.kt` covers valid packs, missing manifests/content, bad versions, malformed metadata, empty content, title-only content, and wrapper-boilerplate rejection. | Covered by `runtime-kotlin` check and final gates below. |
| 4 | Validation rejects reintroduced wrapper boilerplate in source `content.md`. | `AuthoredContentValidator.kt` rejects exact `## Descriptor`, `## Execution`, and `## Ceremony` wrapper headings while tolerating rich authored markdown. `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/scaffold/RepoValidationRuntimeTest.kt` and `ShellContentLoaderParityTest.kt` pin repo validation and loader rejection paths. | Covered by `npx --yes agnix --strict .`, `scripts/validate_agent_configs`, and `runtime-kotlin` check below. |
| 5 | `skill-bill render <skill>` produces deterministic generated wrappers for every skill. | `AuthoringRenderOutputTest.kt` checks repeated in-memory render equality and no source writes. `AuthoringRenderSnapshotTest.kt` checks deterministic stdout and snapshots for a standalone skill, a Kotlin platform-pack skill, and a KMP platform-pack specialist. | Manual render spot checks and final gates recorded below. |
| 6 | Scaffolding a new governed skill creates clean authored `content.md`, not wrapper-shaped source. | `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/scaffold/ScaffoldServiceParityTest.kt` covers horizontal, platform-pack, code-review-area, and quality-check scaffolds, including rollback when generated wrapper headings are supplied and assertions that source `SKILL.md`/pointer files are not created. | Covered by `runtime-kotlin` check below. |
| 7 | `skill-bill fill` and `skill-bill edit` target authored content sections and do not require generated wrapper headings. | `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/scaffold/AuthoringOperationsTest.kt` verifies fill accepts clean authored bodies, rejects wrapper headings with rollback, edits authored H2 sections, and explains that `Descriptor` is generated instead of mutating `content.md`. CLI parity coverage is in `runtime-kotlin/runtime-cli/src/test/kotlin/skillbill/cli/CliAuthoringParityTest.kt`. | Covered by `runtime-kotlin` check below. |
| 8 | Native-agent files remain source files, but direct specialist-native agents can compose from the corresponding `content.md` without duplicated execution prose. | `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/nativeagent/NativeAgentComposition.kt` resolves `compose: governed-content` through sibling content or platform manifests. `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/nativeagent/NativeAgentValidationTest.kt` covers manifest-driven resolution, arbitrary platform slugs, malformed directives, and source-file preservation. | `git ls-files '*/native-agents/*.md'` plus final gates recorded below. |
| 9 | Installed provider-native agents remain self-contained after native-agent composition/rendering. | `NativeAgentValidationTest.kt` checks composed content and declared sidecars are inlined before provider output and unresolved local links fail validation. `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/install/InstallStagingTest.kt` checks install staging renders fresh `SKILL.md`/pointer output outside the repo, copies authored files verbatim, and leaves the source tree unchanged. CLI install coverage is in `runtime-kotlin/runtime-cli/src/test/kotlin/skillbill/cli/CliInstallRuntimeTest.kt`. | Covered by `runtime-kotlin` check and final gates below. |
| 10 | `(cd runtime-kotlin && ./gradlew check)` passes. | Gradle test and static analysis suite. | Passed: `BUILD SUCCESSFUL in 9s`; `runtime-cli:validateAgentConfigs` reported 26 skills, 12 governed add-on files, 2 platform packs, and 17 native agents. |
| 11 | `npx --yes agnix --strict .` passes. | Agent config validation. | Passed: 0 errors, 0 warnings, 1 informational message about unpinned tool/spec versions. |
| 12 | `scripts/validate_agent_configs` passes. | Repo-native agent config validator. | Passed: validated 26 skills, 12 governed add-on files, 2 platform packs, 17 native agents, README catalog, skill references, and workflow contracts. |

## Manual Audit Commands

- `git ls-files '*SKILL.md'`
- `rg -n '^## (Descriptor|Execution|Ceremony)\s*$|Follow the shell ceremony in \[shell-ceremony\.md\]|Follow the instructions in \[content\.md\]\(content\.md\)' --glob '*content.md'`
- Fresh Gradle install distribution before CLI render spot checks:
  `(cd runtime-kotlin && ./gradlew :runtime-cli:installDist)`
- Render spot checks:
  - `runtime-kotlin/runtime-cli/build/install/runtime-cli/bin/runtime-cli render bill-code-review --repo-root .`
  - `runtime-kotlin/runtime-cli/build/install/runtime-cli/bin/runtime-cli render bill-kmp-code-review-ui --repo-root .`

## Final Validation Results

Full required gates:

- `(cd runtime-kotlin && ./gradlew check)` passed.
- `npx --yes agnix --strict .` passed with 0 errors and 0 warnings.
- `scripts/validate_agent_configs` passed.

Focused behavior checks:

- `./gradlew :runtime-core:test --tests skillbill.scaffold.ShellContentLoaderParityTest --tests skillbill.scaffold.RepoValidationRuntimeTest --tests skillbill.scaffold.GovernedSkillDriftValidationTest` passed.
- `./gradlew :runtime-core:test --tests skillbill.scaffold.ScaffoldServiceParityTest --tests skillbill.scaffold.AuthoringOperationsTest --tests skillbill.nativeagent.NativeAgentValidationTest --tests skillbill.install.InstallStagingTest :runtime-cli:test --tests skillbill.cli.CliAuthoringParityTest --tests skillbill.cli.CliInstallRuntimeTest` passed.
- `(cd runtime-kotlin && ./gradlew :runtime-cli:installDist)` passed before manual render checks, ensuring the CLI binary was fresh.

Manual checks:

- `git ls-files '*SKILL.md'` returned no tracked governed generated `SKILL.md` files.
- `git ls-files '*/native-agents/*.md'` returned the tracked nested native-agent source and snapshot markdown files.
- `rg -n '^## (Descriptor|Execution|Ceremony)\s*$|Follow the shell ceremony in \[shell-ceremony\.md\]|Follow the instructions in \[content\.md\]\(content\.md\)' --glob '*content.md'` returned no matches.
- All 26 governed skill ids under `skills/` and `platform-packs/` rendered twice through the rebuilt CLI with byte-identical output and generated `## Descriptor`, `## Execution`, and `## Ceremony` sections.
- Fresh render spot checks passed for:
  - `runtime-kotlin/runtime-cli/build/install/runtime-cli/bin/runtime-cli render bill-code-review --repo-root .`
  - `runtime-kotlin/runtime-cli/build/install/runtime-cli/bin/runtime-cli render bill-kmp-code-review-ui --repo-root .`
