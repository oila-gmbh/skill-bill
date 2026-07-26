# SKILL-45 Subtask 4: Desktop Packaging and Runtime Bundling

Parent spec: [spec.md](./spec.md)

Status: Complete

## Scope

Package the desktop app as native OS installables and ensure the installed app can include or locate the packaged Skill Bill runtime assets. This subtask owns packaging, resource bundling, installer metadata, and installed runtime lookup behavior. It should build on the shared install plan/apply backend and desktop gateway rather than adding a separate installer algorithm.

The packaging must cover macOS, Windows, and Linux. Compose Desktop currently has DMG, MSI, and Deb signals; add the missing Linux/Arch-friendly path or documented fallback package format in the build so Linux users outside Deb-based distributions have a clear installable artifact.

Likely files/boundaries:

- `runtime-kotlin/runtime-desktop/build.gradle.kts`
- `runtime-kotlin/runtime-desktop/src/main/resources/**`
- `runtime-kotlin/runtime-desktop/src/main/kotlin/**/runtime/**`
- `runtime-kotlin/runtime-desktop/src/test/kotlin/**`
- `runtime-kotlin/build-logic/**`
- Root or runtime packaging scripts used to assemble `runtime-cli`, `runtime-mcp`, governed skills, `platform-packs`, and `orchestration`
- README/docs snippets only where packaging commands or artifact names must be discoverable for validation

## Acceptance Criteria

1. Native OS installables exist for macOS, Windows, and Linux that install the Skill Bill desktop app.
2. The installed desktop app includes or can locate the packaged Skill Bill runtime: desktop app, `runtime-cli`, `runtime-mcp`, governed skills, `platform-packs`, and `orchestration`.
3. Runtime asset lookup works from installed app locations and development launches without writing generated artifacts into source directories.
4. Platform packs are packaged/discovered dynamically from the bundled or located `platform-packs` directory; base skills remain always included by install planning.
5. Packaging preserves the existing install staging model under `~/.skill-bill/installed-skills` when the desktop wizard applies an install plan.
6. Windows packaging/install behavior accounts for symlink/elevation constraints and exposes clear failure or fallback behavior through the runtime outcome model.
7. Tests or smoke checks cover runtime asset resolution, bundled platform-pack discovery, and package task wiring where practical.

## Non-Goals

- Do not build the wizard UI in this subtask except for small integration fixes needed to locate packaged runtime assets.
- Do not replace governed install staging.
- Do not create a remote marketplace for packs.
- Do not commit generated `SKILL.md`, generated support pointers, provider-specific native-agent outputs, or packaged binary artifacts.
- Do not redesign authoring, manifests, or routing contracts.

## Dependencies

Depends on Subtask 1 for runtime plan/apply and runtime distribution inputs. It should preferably run after Subtask 3 so packaging can validate the desktop wizard against installed runtime lookup, but packaging resource work can proceed independently if the gateway contract is stable.

## Validation Strategy

Run packaging-related Gradle tasks available for the current host and tests for runtime asset lookup, then run:

```bash
(cd runtime-kotlin && ./gradlew check)
```

Where host limitations prevent producing every native package locally, document the exact package tasks and expected CI/host requirements in the implementation notes.

## Handoff Prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-45-initial-install-desktop-wizard/spec_subtask_4_desktop-packaging-runtime-bundling.md`.
