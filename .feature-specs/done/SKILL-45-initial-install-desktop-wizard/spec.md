# SKILL-45 Initial Install Desktop Wizard

Status: Complete

## Sources

- Raw conversation text from the Step 2 pre-planning request.

## Acceptance Criteria

1. Native OS installables exist for macOS, Windows, and Linux that install the Skill Bill desktop app.
2. The installed app includes or can locate the packaged Skill Bill runtime: desktop app, runtime-cli, runtime-mcp, governed skills, platform-packs, and orchestration.
3. First launch offers a setup wizard for agent selection, platform pack selection, telemetry level, and MCP registration.
4. Platform packs are discovered dynamically from platform-packs; base skills are always included.
5. Telemetry choices are anonymous, full, and off, matching existing CLI behavior.
6. Supported agents include copilot, claude, codex, opencode, and junie, with detection plus manual selection.
7. The setup flow uses the existing install staging model under ~/.skill-bill/installed-skills.
8. Expose reusable install plan/apply behavior to CLI and desktop rather than making the desktop scrape interactive shell output.
9. install.sh remains usable, ideally as a thin wrapper around the reusable install/apply path.
10. Windows symlink/elevation behavior is handled explicitly with clear failure or fallback behavior.
11. Tests cover install plan/apply, agent/platform selection, telemetry configuration, and desktop wizard state.

## Non-Goals

- Redesigning skill authoring or platform-pack manifest contracts.
- Committing generated SKILL.md, support pointer files, or provider-specific native-agent output.
- Replacing governed install staging.
- Building a remote marketplace for packs in this feature.
- Changing the existing supported-agent set unless implementation discovers a contract gap.

## Completion Notes

SKILL-45 is implemented through the decomposed subtasks in this directory and
the shared install plan/apply decomposition under
`.feature-specs/SKILL-45-shared-install-plan-apply/`.

Final validation completed on a CachyOS Linux host. The loose Compose Desktop
app image was produced with `:runtime-desktop:createDistributable`; native Linux
package production was blocked by local `jpackage` support rejecting both
`rpm` and `deb` package types, with `rpmbuild` and `dpkg-deb` absent from the
host. macOS DMG and Windows MSI production remain host-specific release checks
for matching OS runners.

## Consolidated Spec

Run Step 2 pre-planning for bill-feature-implement in `/home/sermilion/StudioProjects/skill-bill`.

Issue key: `SKILL-45`

Feature name: `initial-install-desktop-wizard`

Feature size: `LARGE`

Rollout need: `N/A`

Spec input type: raw conversation text.

User Linux packaging preference: primary CachyOS/Arch Linux, so include an Arch-friendly package path; also include the most common Linux package target(s), currently Compose Desktop already has Deb.

Pre-planning instructions:

- Save the spec to `.feature-specs/SKILL-45-initial-install-desktop-wizard/spec.md` with status In Progress.
- Read `AGENTS.md` plus relevant `agent/history.md` and `agent/decisions.md` files for likely touched boundaries, especially runtime-kotlin runtime-core/install, runtime-cli, runtime-desktop, docs, and scripts/install.
- Discover existing package build, desktop wizard, install primitives, telemetry, MCP registration, native agent linking, and validation patterns.
- Confirm `bill-quality-check` can route this repo; if not, identify repo-native validation commands.
- Determine if this should be decomposed before implementation and why.
