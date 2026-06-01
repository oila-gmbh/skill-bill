# Iteration 04: Scaffold Wizards

Status: Draft

## Parent Spec Context

This iteration belongs to the desktop Skill Bill app spec in
`docs/desktop-skill-bill-app/README.md`. The Skill Bill app is an optional repo-based
desktop app shipped from this project. It must use existing Skill Bill runtime
services or CLI-equivalent adapters for governed behavior, expose authored
source only, and avoid duplicating scaffold, manifest, validation, routing, or
native-agent render rules.

For this iteration, wizard forms must produce the existing scaffold payload
contract documented in
`orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`. The UI must not
create platform behavior outside the approved scaffold kinds.

When this iteration is implemented, update the completion checklist in
`docs/desktop-skill-bill-app/README.md`.

## Goal

Provide guided creation flows for Skill Bill governed artifacts by emitting the existing scaffold payload contract and executing the existing scaffolder.

## User Value

A user can create a new platform pack, skill, specialist, or add-on without hand-writing JSON payloads or editing manifests manually.

## Scope

- Add a `New...` action from the toolbar and relevant tree nodes.
- Implement wizard forms for:
  - horizontal skill
  - platform pack
  - platform override for piloted families
  - code-review area specialist
  - add-on
- Generate payloads matching `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`.
- Run scaffold dry-run when available.
- Show planned operations before applying.
- Execute scaffold through existing runtime behavior.
- Refresh the tree after successful scaffold.

## Platform Pack Wizard

Fields:

- platform slug
- display name
- description
- routing signals, only when no built-in preset exists
- optional subagent specialists when supported

Platform pack creation always generates the baseline code-review skill, default
quality-check skill, and every approved code-review specialist. Remove unwanted
focus areas afterward through governed removal paths.

The wizard must not create feature-implement or feature-verify overrides for a new platform pack.

## Required Runtime Behavior

Use existing runtime behavior equivalent to:

```bash
skill-bill new --payload <payload.json> --dry-run --format json
skill-bill new --payload <payload.json> --format json
```

The scaffold operation must remain atomic. If validation, manifest write, install staging, or generated-link handling fails, the repository must roll back according to existing scaffolder behavior.

## Out of Scope

- Freeform manifest editing.
- Editing scaffolded generated output.
- Creating unsupported platform behavior outside approved kinds.
- Publishing changes to Git.

## Acceptance Criteria

- Wizard payloads conform to the scaffold payload contract.
- Dry-run displays planned file and manifest operations.
- Applying a scaffold updates only expected authored source and manifest files.
- Tree refresh shows the new artifact.
- Scaffold failures surface runtime exception names/messages.
- Dirty repo warnings are shown before scaffold execution.

## Validation

```bash
cd runtime-kotlin
./gradlew :runtime-desktop:test
./gradlew check
```

Manual smoke:

1. Open a temporary repo copy.
2. Create a full platform pack.
3. Validate the repo.
4. Confirm generated wrappers are not checked in.
5. Revert the temporary copy outside the app.

## Risks

- The UI could accidentally encode outdated scaffold rules. Keep wizard option lists sourced from shared constants or runtime metadata where possible.
- Dry-run and execute payloads must be byte-equivalent except for the dry-run flag.
