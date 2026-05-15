# Iteration 02: Repo Browser and Read-Only Tree

Status: Implemented

## Parent Spec Context

This iteration belongs to the desktop Skill Bill app spec in
`docs/desktop-skill-bill-app/README.md`. The Skill Bill app is an optional repo-based
desktop app shipped from this project. It must use existing Skill Bill runtime
services or CLI-equivalent adapters for governed behavior, expose authored
source only, and avoid duplicating scaffold, manifest, validation, routing, or
native-agent render rules.

For this iteration, the most important parent-spec rule is that editable targets
must be discovered from runtime authoring/discovery behavior, not by UI-specific
guessing. Generated `SKILL.md` wrappers, generated pointer files, and install
cache outputs must remain hidden or read-only.

When this iteration is implemented, update the completion checklist in
`docs/desktop-skill-bill-app/README.md`.

## Goal

Open a local Skill Bill repository and render a read-only tree of authored skills, platform packs, add-ons, and native-agent source targets.

## User Value

A user can point the app at a checkout and understand what can be authored without knowing the repository layout.

## Scope

- Implement repo selection for a local directory.
- Validate that the selected directory is a Skill Bill repo.
- Build the left-panel tree from existing runtime discovery and authoring operations.
- Show metadata for selected tree items in the right panel.
- Identify editable targets, but do not allow editing yet.
- Mark generated artifacts as hidden or read-only.
- Provide an explicit refresh action that re-runs runtime discovery.

## Required Runtime Behavior

The desktop app should call shared runtime services directly. If the needed runtime surface is only available through CLI commands, add a shared application/runtime service and make the CLI continue to use that same behavior.

Required data:

- content-managed skill list
- skill names and authored `content.md` paths
- platform-pack manifests and declared files
- add-on paths
- provider-neutral native-agent source paths
- validation or drift status when already exposed by runtime authoring operations

## Tree Shape

```text
Horizontal Skills
  bill-code-review
  bill-quality-check
Platform Packs
  Kotlin
    code-review
      bill-kotlin-code-review
      architecture
      security
    quality-check
      bill-kotlin-quality-check
    add-ons
  KMP
    code-review
    add-ons
Repository
  validation
  install targets
```

The exact labels may evolve, but the tree must distinguish platform packs from horizontal skills.

## Out of Scope

- Editing and saving.
- Scaffolding new targets.
- Git diff and commit UI.
- Pull request creation.

## Acceptance Criteria

- Selecting a valid repo loads a tree without direct hand-parsing of generated wrappers.
- Selecting a skill shows its name, kind, authored path, and status.
- Selecting a generated artifact, if visible at all, clearly shows it as non-editable.
- Invalid repo selection produces a clear error and does not crash.
- Tree refresh reflects file changes after an explicit refresh action.

## Validation

```bash
cd runtime-kotlin
./gradlew :runtime-desktop:test
./gradlew check
```

Manual smoke:

1. Open this repository.
2. Confirm horizontal skills appear.
3. Confirm `platform-packs/kotlin` and `platform-packs/kmp` appear.
4. Confirm generated `SKILL.md` files are not offered as editable targets.

## Implementation Notes

- Runtime-backed browsing is wired through the desktop service boundary and uses
  `AuthoringOperations`, `RepoValidationRuntime`, and provider-neutral native-agent
  discovery.
- The tree shows authored `content.md` targets, pack add-ons, native-agent source
  entries, and any generated artifacts that are present as read-only metadata.
- The app remains read-only. Editing, scaffolding, Git diff/commit, and PR flows stay
  deferred to later iterations.

## Risks

- Runtime authoring list output may not include all tree metadata. Prefer extending shared runtime output over adding UI-only discovery.
- Platform-pack manifest drift should be shown as runtime validation state, not papered over in UI.
