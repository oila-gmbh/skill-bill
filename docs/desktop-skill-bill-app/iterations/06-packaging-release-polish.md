# Iteration 06: Packaging and Release Polish

Status: Draft

## Parent Spec Context

This iteration belongs to the desktop Skill Bill app spec in
`docs/desktop-skill-bill-app/README.md`. The Skill Bill app is an optional repo-based
desktop app shipped from this project. It must use existing Skill Bill runtime
services or CLI-equivalent adapters for governed behavior, expose authored
source only, and avoid duplicating scaffold, manifest, validation, routing, or
native-agent render rules.

For this iteration, documentation must preserve the parent-spec boundary: the
desktop app complements the CLI and runtime, remains optional, operates on a
local repo checkout, and publishes contribution changes through the user's fork.

When this iteration is implemented, update the completion checklist in
`docs/desktop-skill-bill-app/README.md`.

## Goal

Make the desktop Skill Bill app easy to launch, document, and validate as an optional official Skill Bill surface.

## User Value

A user can install or run the app from the canonical repo, then use it against their own checkout or fork with clear documentation.

## Scope

- Add a root-level or runtime-level launch command documented in README material.
- Package the desktop app for local distribution where Compose supports it.
- Add onboarding copy inside the app for first repo selection only.
- Add docs for:
  - running from source
  - opening an existing checkout
  - cloning or configuring a fork
  - authoring and validating
  - committing and pushing
- Add screenshot or short demo asset only after UI stabilizes.
- Ensure CI or local validation includes desktop module checks.

## Out of Scope

- Auto-update infrastructure.
- Signed production installers.
- Cross-platform packaging guarantees beyond tested maintainer environments.
- Mobile targets.

## Documentation Requirements

Docs should make these boundaries explicit:

- the app is optional
- the app uses the existing Skill Bill runtime
- users edit authored source only
- generated artifacts are hidden or read-only
- pushing contribution changes should go to the user's fork

## Acceptance Criteria

- A new user can run the app from source using one documented command.
- Packaging task succeeds on the primary maintainer platform.
- README or getting-started docs mention the desktop Skill Bill app without making it mandatory.
- Validation commands include the desktop module where appropriate.
- UI smoke tests cover launch and basic repo open behavior.

## Validation

```bash
cd runtime-kotlin
./gradlew :runtime-desktop:run
./gradlew :runtime-desktop:packageDistributionForCurrentOS
./gradlew check
```

Run repo-level validation as usual:

```bash
skill-bill validate
npx --yes agnix --strict .
scripts/validate_agent_configs
```

## Risks

- Packaging behavior can vary by OS. Keep source-run workflow as the reliable baseline.
- Documentation should not imply that the Skill Bill app replaces CLI workflows; it complements them.
