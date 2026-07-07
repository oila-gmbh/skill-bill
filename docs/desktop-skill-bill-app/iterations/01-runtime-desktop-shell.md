wsl# Iteration 01: Runtime Desktop Shell

Status: Draft

## Parent Spec Context

This iteration belongs to the desktop Skill Bill app spec in
`docs/desktop-skill-bill-app/README.md`. The Skill Bill app is an optional repo-based
desktop app shipped from this project. It must use existing Skill Bill runtime
services or CLI-equivalent adapters for governed behavior, expose authored
source only, and avoid duplicating scaffold, manifest, validation, routing, or
native-agent render rules.

When this iteration is implemented, update the completion checklist in
`docs/desktop-skill-bill-app/README.md`.

## Goal

Add an optional Compose Multiplatform desktop module that can launch a native desktop window from the existing `runtime-kotlin` Gradle build.

## User Value

A maintainer can run the Skill Bill app locally and see the stable two-panel application frame without changing any Skill Bill repository content.

## Scope

- Add `runtime-desktop` to `runtime-kotlin/settings.gradle.kts`.
- Configure Compose Multiplatform for JVM desktop.
- Use the KMPComposeStarter source-set shape: shared UI/state in `commonMain`,
  desktop window entrypoint in `jvmMain`, and desktop behavior tests in
  `jvmTest`.
- Use the KMPComposeStarter convention-plugin pattern for the desktop app
  Gradle setup while keeping the plugin repo-local to `runtime-kotlin`.
- Use the KMPComposeStarter app/core/feature host boundary: app state and
  composition-local user/screen component providers in the app shell, reusable
  contracts and preferences in core modules, and feature-owned screen state in
  `feature:*`.
- Create the first desktop application entrypoint.
- Render the Skill Bill app frame:
  - top repo toolbar
  - left tree placeholder
  - right editor placeholder
  - bottom status placeholder
- Add a repo selector control that stores an in-memory path only.
- Add basic app state classes for selected repo path and selected tree item.

## Out of Scope

- Real Skill Bill repo discovery.
- Real tree loading.
- Editing.
- Packaging installers.

## Architecture Notes

The module should depend only on the minimum existing runtime modules required for later integration. If no runtime dependency is needed in this iteration, keep it isolated and add dependencies in Iteration 02.

The UI should be structured around replaceable service interfaces from the start:

- `RepoSessionService`
- `SkillTreeService`
- `AuthoringGateway`

Use in-memory placeholder implementations until real services are introduced.

## Acceptance Criteria

- `./gradlew :runtime-desktop:run` launches the Skill Bill app.
- The app shows a stable two-panel layout at desktop size.
- The left panel remains narrow and resizable or fixed within a sensible width.
- No repository files are modified by launching or closing the app.
- Existing `./gradlew check` continues to pass.

## Validation

```bash
cd runtime-kotlin
./gradlew :runtime-desktop:run
./gradlew check
```

## Risks

- Compose plugin setup can add Gradle complexity. Keep the module optional and avoid affecting CLI or MCP outputs.
- Desktop dependencies should not leak into runtime-core or runtime-application.
