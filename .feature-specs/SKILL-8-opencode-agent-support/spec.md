# Opencode agent support

- Issue key: SKILL-8
- Status: In Progress
- Date: 2026-04-11
- Sources:
  - User request: "i want to add support for opencode"
  - User detail: opencode uses its global skills directory for skill installation
  - User decision: include MCP registration in the opencode global config file

## Acceptance criteria

1. `install.sh` can target opencode and install selected skills into the opencode global skills directory.
2. `uninstall.sh` removes Skill Bill-managed installs from the opencode skills directory.
3. The installer also registers the Skill Bill MCP server in the opencode global config file, and the uninstaller removes that registration.
4. User-facing docs list opencode as a supported agent with the correct install path.
5. `bill-new-skill-all-agents` includes opencode in its known agent targets and paths.
6. Automated coverage is updated for the new agent path and MCP registration behavior.

## Non-goals

- Adding opencode-specific platform packages.
- Changing skill taxonomy or routing behavior.
- Refactoring unrelated installer or uninstaller logic.

## Consolidated spec

Add opencode as a first-class supported agent alongside Copilot, Claude, GLM, and Codex.

The installer must allow selecting opencode, install skills into the opencode global skills directory, and register the local Skill Bill MCP server in the opencode global config file.

The uninstaller must remove Skill Bill-managed skill installs from the opencode skills directory and remove the Skill Bill MCP entry from the opencode config file.

User-facing documentation and the `bill-new-skill-all-agents` skill must also recognize opencode as a supported target.

Tests must cover the new install path and MCP registration/removal behavior.
